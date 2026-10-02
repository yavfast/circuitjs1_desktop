'use strict';
// [SP_MCP_01_04] [SP_MCP_03_04] Tool result shaping: the Agent API OperationResult becomes
//   structuredContent  the OperationResult (for circuit_render png: data.content = "<image>")
//   content[0]         text: an optional note line, then the OperationResult as compact JSON
//   content[1]         image/png for circuit_render png
//   isError            exactly when ok = false
// The text part stays within TEXT_LIMIT characters. The per-tool reductions (re-executing a
// read-only tool with smaller arguments) live in tools.js; this module measures, builds the
// result_too_large rejection and, as the last resort for a result that cannot be re-requested,
// trims whole array items of the text part only (never cutting mid-structure).

// [SP_MCP_03_04] about 20 000 tokens of numeric JSON, below the host's 25 000-token cap
const TEXT_LIMIT = 60000;

const IMAGE_PLACEHOLDER = '<image>';

/** Bounds a text echoed in a message (exception texts can carry client input). */
function clipText(text, max) {
  const s = String(text);
  return s.length > max ? s.slice(0, max) + '…' : s;
}

/** An Issue (SP_AGA_01_07) raised by the server itself; key per SP_AGA_01_07 (no elements, posts, at). */
function serverIssue(code, severity, message, hint) {
  return { code, severity, message, hint, key: `${code}|||` };
}

/**
 * [SP_MCP_03_03] An OperationResult with one internal_error issue, for an unexpected exception
 * in a tool; same shape as agent.js internalError.
 */
function internalErrorResult(message) {
  return {
    ok: false,
    issues: [serverIssue('internal_error', 'error', clipText(message, 500),
      'Retry the call; if it fails again, check the application log.')],
    truncatedIssues: 0,
  };
}

/**
 * [SP_MCP_03_04] result_too_large rejection of a result whose text part would exceed the limit
 * (SVG render text, export content). `data` keeps the small fields given; the original issues
 * follow the server issue.
 */
function resultTooLarge(result, chars, hint, data) {
  const issues = [serverIssue('result_too_large', 'error',
    `The result has ${chars} characters, more than the ${TEXT_LIMIT}-character limit of a tool result.`, hint)]
    .concat((result && result.issues) || []);
  const out = { ok: false };
  if (data) out.data = data;
  out.issues = issues.slice(0, 50);
  out.truncatedIssues = ((result && result.truncatedIssues) || 0) + Math.max(0, issues.length - 50);
  return out;
}

/** Length of the text part for this result and note. */
function textLength(result, note) {
  return (note ? note.length + 1 : 0) + JSON.stringify(result).length;
}

function fits(result, note) {
  return textLength(result, note) <= TEXT_LIMIT;
}

/**
 * Last resort for a result that cannot be re-requested (mutating tools, runs) or that a per-tool
 * reduction could not bring under the limit: halves the largest array of a copy until the JSON
 * fits, and names every cut array in the note. structuredContent keeps the whole result.
 * @returns {{text: string, cuts: string[]}}
 */
function trimmedText(result, note) {
  const copy = JSON.parse(JSON.stringify(result));
  const cuts = new Map(); // path -> original length
  for (let guard = 0; guard < 200; guard++) {
    const header = trimNote(note, cuts);
    const json = JSON.stringify(copy);
    if (header.length + 1 + json.length <= TEXT_LIMIT) {
      return { text: header + '\n' + json, cuts: [...cuts.keys()] };
    }
    const largest = largestArray(copy);
    if (!largest || largest.array.length === 0) break;
    if (!cuts.has(largest.path)) cuts.set(largest.path, largest.array.length);
    largest.array.length = Math.floor(largest.array.length / 2);
  }
  // nothing left to cut: the issue list and keys alone exceed the limit (not reachable with the
  // Agent API caps); answer with the bare outcome
  const bare = { ok: result.ok, issues: [], truncatedIssues: ((result.issues || []).length) + (result.truncatedIssues || 0) };
  return { text: trimNote(note, cuts) + '\n' + JSON.stringify(bare), cuts: [...cuts.keys()] };
}

function trimNote(note, cuts) {
  const parts = [];
  if (note) parts.push(note);
  const list = [...cuts.entries()].map(([p, n]) => `${p} (${n} items)`);
  parts.push(`[text part shortened to fit ${TEXT_LIMIT} characters; arrays cut to their first items: ${list.join(', ') || 'none'}; structuredContent holds the whole result]`);
  return parts.join(' ');
}

/** The array with the longest serialization in a JSON value, with its path. */
function largestArray(value) {
  let best = null;
  const visit = (v, path) => {
    if (Array.isArray(v)) {
      const size = JSON.stringify(v).length;
      if (v.length > 0 && (!best || size > best.size)) best = { array: v, path, size };
      v.forEach((item, i) => visit(item, `${path}[${i}]`));
    } else if (v && typeof v === 'object') {
      for (const k of Object.keys(v)) visit(v[k], path ? `${path}.${k}` : k);
    }
  };
  visit(value, '');
  return best;
}

/**
 * [SP_MCP_01_04] The CallToolResult of an OperationResult.
 * @param result the OperationResult of the effective Agent API call
 * @param opts {note?: string, image?: {data: string, mimeType: string}}
 */
function toolResult(result, opts) {
  const note = opts && opts.note;
  let text;
  if (fits(result, note)) {
    text = (note ? note + '\n' : '') + JSON.stringify(result);
  } else {
    text = trimmedText(result, note).text;
  }
  const content = [{ type: 'text', text }];
  if (opts && opts.image) content.push({ type: 'image', data: opts.image.data, mimeType: opts.image.mimeType });
  return { content, structuredContent: result, isError: result.ok !== true };
}

/**
 * [SP_MCP_01_04] circuit_render with format=png: the base64 PNG moves to the image part and
 * data.content becomes "<image>". Other formats and rejected renders are unchanged.
 * @returns {{result: object, image: {data, mimeType}|null}}
 */
function extractPng(result) {
  const d = result && result.data;
  if (!result || result.ok !== true || !d || d.format !== 'png' || typeof d.content !== 'string') {
    return { result, image: null };
  }
  const shaped = Object.assign({}, result, { data: Object.assign({}, d, { content: IMAGE_PLACEHOLDER }) });
  return { result: shaped, image: { data: d.content, mimeType: 'image/png' } };
}

module.exports = {
  TEXT_LIMIT,
  IMAGE_PLACEHOLDER,
  serverIssue,
  internalErrorResult,
  resultTooLarge,
  textLength,
  fits,
  trimmedText,
  toolResult,
  extractPng,
};
