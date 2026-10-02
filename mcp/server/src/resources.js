'use strict';
// [SP_MCP_02_03] Resources: the element catalogue, the open documents, a document's circuit,
// the bundled example circuits and the agent-format text. Resource reads are not tool results:
// they are returned whole, without the 60 000-character limit of SP_MCP_03_04. An unknown URI
// (and an unknown type, document or example path) is JSON-RPC -32002.

const { McpError, ErrorCode } = require('@modelcontextprotocol/sdk/types.js');
const { RESOURCE_NOT_FOUND, clip } = require('./protocol.js');
const AGENT_FORMAT = require('../agent-format.md');

const SCHEME = 'circuitjs://';
// Package paths, relative to the page (circuitjs.html); GWT.getModuleBaseURL() is circuitjs1/
const SETUP_LIST = 'circuitjs1/setuplist.txt';
const CIRCUITS_DIR = 'circuitjs1/circuits/';
// getCircuit page size used to read a whole circuit (its maximum, SP_AGA_02_05)
const PAGE = 500;
// [SP_MCP_02_01] bounds of client-supplied and Agent API text echoed in error messages
const MAX_URI_ECHO = 200;
const MAX_MESSAGE_ECHO = 300;

const FIXED = [
  {
    uri: 'circuitjs://catalogue',
    name: 'catalogue',
    title: 'Element catalogue',
    description: 'Index of every element type: {types: [{type, aliases, pins, geometry, summary}]}.',
    mimeType: 'application/json',
  },
  {
    uri: 'circuitjs://documents',
    name: 'documents',
    title: 'Open documents',
    description: 'The open documents (tabs): {documents: [{doc, title, active, modified, running, busy, elementCount, filePath}]}.',
    mimeType: 'application/json',
  },
  {
    uri: 'circuitjs://examples',
    name: 'examples',
    title: 'Example circuits',
    description: 'The example circuits bundled with the app: [{path, title, menu}]; read one at circuitjs://examples/{path}.',
    mimeType: 'application/json',
  },
  {
    uri: 'circuitjs://docs/agent-format',
    name: 'agent-format',
    title: 'Agent format',
    description: 'How agents describe circuits: grid-cell coordinates, ElementSpec, edit ops, nets, runs, issue codes, file rules.',
    mimeType: 'text/markdown',
  },
];

const TEMPLATES = [
  {
    uriTemplate: 'circuitjs://catalogue/{type}',
    name: 'type',
    title: 'Element type',
    description: 'TypeInfo of one element type or alias: pins, geometry, default size in grid cells, property keys.',
    mimeType: 'application/json',
  },
  {
    uriTemplate: 'circuitjs://documents/{doc}/circuit',
    name: 'circuit',
    title: 'Document circuit',
    description: 'The whole circuit of document {doc} at full detail, {elements, simulation, scopes}; importable unchanged through circuit_import.',
    mimeType: 'application/json',
  },
  {
    uriTemplate: 'circuitjs://examples/{path}',
    name: 'example',
    title: 'Example circuit',
    description: 'The text (legacy format) of one bundled example circuit; paths from circuitjs://examples.',
    mimeType: 'text/plain',
  },
];

function notFound(uri) {
  return new McpError(RESOURCE_NOT_FOUND, `Resource not found: ${clip(uri, MAX_URI_ECHO)}`);
}

/** An exception or issue message, bounded for an error reply. */
function reason(e) {
  return clip(e && e.message ? e.message : e, MAX_MESSAGE_ECHO);
}

function decode(part) {
  try {
    return decodeURIComponent(part);
  } catch (e) {
    return null;
  }
}

/**
 * [SP_MCP_02_03] Example index from setuplist.txt (format of CircuitLoader.processSetupList):
 * `#` comments, `+Title` opens a submenu, `-` closes it, `[>]file.txt Title` is an example.
 * @returns {{path: string, title: string, menu: string}[]}
 */
function parseSetupList(text) {
  const out = [];
  const menu = [];
  for (const line of String(text).split(/\r\n|\n|\r/)) {
    if (!line || line[0] === '#') continue;
    if (line[0] === '+') {
      menu.push(line.slice(1).trim());
    } else if (line[0] === '-') {
      menu.pop();
    } else {
      const i = line.indexOf(' ');
      if (i <= 0) continue;
      const path = line.slice(line[0] === '>' ? 1 : 0, i);
      const title = line.slice(i + 1).trim();
      if (path) out.push({ path, title, menu: menu.join(' / ') });
    }
  }
  return out;
}

/** Reads a text file of the app package relative to the page (all three run modes serve it). */
async function fetchPackageText(rel) {
  const base = typeof document !== 'undefined' && document.baseURI ? document.baseURI : undefined;
  const url = base ? new URL(rel, base).href : rel;
  const response = await fetch(url);
  if (!response.ok) throw new Error(`HTTP ${response.status} for ${rel}`);
  return response.text();
}

function json(uri, value) {
  return { contents: [{ uri, mimeType: 'application/json', text: JSON.stringify(value) }] };
}

/**
 * @param opts {agent, fetchText?}: agent = agent client of agent.js; fetchText(relPath) reads a
 *   package file (default: fetch relative to the page)
 * @returns {{list(), templates(), read(uri): Promise<ReadResourceResult>}}
 */
function createResources(opts) {
  const agent = opts.agent;
  const fetchText = opts.fetchText || fetchPackageText;
  let examples = null; // cached index (the package does not change while running)

  /** The data of a successful Agent API call; a rejection becomes a JSON-RPC error. */
  async function data(uri, op, args) {
    const r = await agent.call(op, args);
    if (r.ok === true) return r.data || {};
    const first = (r.issues && r.issues[0]) || { code: 'internal_error', message: 'The Agent API rejected the call.' };
    // the issue message may echo the client's type or handle: both texts are bounded
    if (first.code === 'unknown_type' || first.code === 'unknown_document') {
      throw new McpError(RESOURCE_NOT_FOUND, `Resource not found: ${clip(uri, MAX_URI_ECHO)} (${reason(first)})`);
    }
    throw new McpError(ErrorCode.InternalError, `Reading ${clip(uri, MAX_URI_ECHO)} failed: ${clip(first.code, 64)}: ${reason(first)}`);
  }

  async function exampleIndex() {
    if (!examples) {
      let text;
      try {
        text = await fetchText(SETUP_LIST);
      } catch (e) {
        throw new McpError(ErrorCode.InternalError, `The example index could not be read: ${reason(e)}`);
      }
      examples = parseSetupList(text);
    }
    return examples;
  }

  /** [SP_MCP_02_03] getCircuit at full detail, all pages, as an importable AgentCircuit. */
  async function wholeCircuit(uri, doc) {
    const elements = [];
    let first = null;
    let offset = 0;
    for (let guard = 0; guard < 100000; guard++) {
      const d = await data(uri, 'getCircuit', { doc, detail: 'full', offset, limit: PAGE });
      if (!first) first = d;
      const page = d.elements || [];
      for (const e of page) elements.push(e);
      if (d.nextOffset === undefined || d.nextOffset === null || page.length === 0) break;
      offset = d.nextOffset;
    }
    return { elements, simulation: first.simulation, scopes: first.scopes };
  }

  async function read(uri) {
    const u = String(uri);
    if (!u.startsWith(SCHEME)) throw notFound(u);
    const rest = u.slice(SCHEME.length);
    if (rest === 'catalogue') return json(u, await data(u, 'listTypes', {}));
    if (rest === 'documents') return json(u, await data(u, 'listDocuments', {}));
    if (rest === 'examples') return json(u, (await exampleIndex()).map((e) => ({ path: e.path, title: e.title, menu: e.menu })));
    if (rest === 'docs/agent-format') {
      return { contents: [{ uri: u, mimeType: 'text/markdown', text: AGENT_FORMAT }] };
    }
    let m = /^catalogue\/([^/]+)$/.exec(rest);
    if (m) {
      const type = decode(m[1]);
      if (!type) throw notFound(u);
      return json(u, await data(u, 'describeType', { type }));
    }
    m = /^documents\/([^/]+)\/circuit$/.exec(rest);
    if (m) {
      const doc = decode(m[1]);
      // the handle form of SP_AGA_01_02; anything else names no document
      if (!doc || !/^d[1-9][0-9]*$/.test(doc)) throw notFound(u);
      return json(u, await wholeCircuit(u, doc));
    }
    m = /^examples\/(.+)$/.exec(rest);
    if (m) {
      const path = decode(m[1]);
      // only the files the index lists: no other package path is readable
      const entry = path && (await exampleIndex()).find((e) => e.path === path);
      if (!entry) throw notFound(u);
      let text;
      try {
        text = await fetchText(CIRCUITS_DIR + entry.path);
      } catch (e) {
        throw new McpError(ErrorCode.InternalError, `Example ${clip(entry.path, MAX_URI_ECHO)} could not be read: ${reason(e)}`);
      }
      return { contents: [{ uri: u, mimeType: 'text/plain', text }] };
    }
    throw notFound(u);
  }

  return {
    list: () => FIXED.map((r) => Object.assign({}, r)),
    templates: () => TEMPLATES.map((t) => Object.assign({}, t)),
    read,
  };
}

module.exports = { createResources, parseSetupList, FIXED, TEMPLATES, AGENT_FORMAT };
