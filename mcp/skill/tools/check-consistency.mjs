#!/usr/bin/env node
// [SP_AGS_05_03] [SP_AGS_03_01] [SP_AGS_03_02] [SP_AGS_04_01] Consistency checks of the
// circuitjs-circuits skill against a running CircuitJS1 instance.
//
// Usage:
//   node mcp/skill/tools/check-consistency.mjs [--skill <dir>] [--docs <dir>] [--bridge <circuitjs-mcp.js>]
//        [--url <url> | --instance <id>] [--registry <dir>] [--timeout <ms>] [--offline]
//
//   --skill     skill directory (default: ../circuitjs-circuits next to this script)
//   --docs      directory with agent-api.sp.md and mcp-server.sp.md (default: <repo>/docs)
//   --bridge    bridge entry script (default: <repo>/mcp/bridge/bin/circuitjs-mcp.js)
//   --url, --instance, --registry, --timeout   passed to the bridge unchanged; the bridge's
//               environment variables (CIRCUITJS_MCP_URL, ...) work as well
//   --offline   form limits and issue-code coverage only; the live groups are reported as SKIP
//
// The bridge CLI does the spec-named calls (tools, read circuitjs://catalogue/<type>, circuit_import);
// the served input schemas, the server instructions and the full catalogue are read with direct
// JSON-RPC requests (tools/list, initialize, resources/read) to the URL of the instance the bridge selects.
//
// Groups (one PASS/FAIL/SKIP line each, failures listed above it):
//   form       SP_AGS_03_01: SKILL.md <= 250 lines, references <= 400, a contents table first in
//              references over 100 lines, references linked only from SKILL.md (one level), whole
//              cells in example circuits, half-cell lattice in every element spec
//   codes      every SP_AGA issue code (agent-api.sp.md §03_04-§03_06) and server code
//              (mcp-server.sp.md §03_03) has a diagnostics.md row; every row names a known code
//   names      every backticked snake_case word of the skill is an issue code, a property key, a tool,
//              an argument or enumeration value of a tool, or a catalogue geometry (live: full check;
//              offline: codes and tools only are known, so the group is SKIP)
//   tools      tool names vs `circuitjs-mcp tools` (+ the three bridge tools); argument keys of every
//              tool call, pattern run and edit written in the skill (shorthand calls by their keys)
//              vs the served input schemas; toolsVersion of the instance vs the SKILL.md line
//   catalogue  type, alias, pin, geometry and property names vs circuitjs://catalogue/<type>,
//              `set` edits resolved through the element IDs of their pattern section
//   examples   every ```json block that is an AgentCircuit ({elements: [...]}) imports into a scratch
//              document with ok = true and connectivity.errorCount = 0; the document is closed after
//
// Exit code: 0 all groups pass; 1 a check failed; 2 usage error or no reachable instance.

import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO = path.resolve(HERE, '..', '..', '..');
const BRIDGE_TOOLS = ['bridge_instances', 'bridge_select', 'bridge_launch']; // mcp/bridge/README.md
const NOT_CODES = new Set(['error', 'warning', 'info', 'elements']); // backticked words of §03_06 that are not codes
const OTHER_WORDS = new Set(['connected_to']); // JSON v2 file keys the skill names (not served by the tools)
// Minimum number of codes each spec section must yield, so a format drift cannot pass vacuously
const MIN_CODES = { 'SP_AGA_03_04': 5, 'SP_AGA_03_05': 9, 'SP_AGA_03_06': 30, 'SP_MCP_03_03': 1 };

// ------------------------------------------------------------------ options
function parseArgs(argv) {
  const o = { skill: path.join(HERE, '..', 'circuitjs-circuits'), docs: path.join(REPO, 'docs'), bridge: path.join(REPO, 'mcp/bridge/bin/circuitjs-mcp.js'), pass: [], offline: false, timeout: 130000 };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    const val = () => { if (i + 1 >= argv.length) usage(`${a} needs a value`); return argv[++i]; };
    if (a === '--skill') o.skill = path.resolve(val());
    else if (a === '--docs') o.docs = path.resolve(val());
    else if (a === '--bridge') o.bridge = path.resolve(val());
    else if (['--url', '--instance', '--registry', '--timeout'].includes(a)) { const v = val(); o.pass.push(a, v); if (a === '--timeout') o.timeout = Number(v) || o.timeout; if (a === '--url') o.url = v; }
    else if (a === '--offline') o.offline = true;
    else if (a === '--help' || a === '-h') { console.log(fs.readFileSync(fileURLToPath(import.meta.url), 'utf8').split('\n').slice(1, 42).map((l) => l.replace(/^\/\/ ?/, '')).join('\n')); process.exit(0); }
    else usage(`unknown argument ${a}`);
  }
  return o;
}
function usage(msg) { console.error(`check-consistency: ${msg}`); process.exit(2); }
const opt = parseArgs(process.argv.slice(2));
if (!fs.existsSync(path.join(opt.skill, 'SKILL.md'))) usage(`no SKILL.md in ${opt.skill}`);

// ------------------------------------------------------------------ instance access
class Unreachable extends Error {}
/** Bridge exit 2 (usage error, or a request the app rejects as invalid, e.g. -32602): a failed check where it answers a skill example. */
class BridgeRejected extends Unreachable {}
/** Bridge CLI: the parsed JSON document; a tool error result (exit 1) is returned as well. */
function cli(args, input) {
  let out;
  try {
    out = execFileSync(process.execPath, [opt.bridge, ...args, ...opt.pass], { input, encoding: 'utf8', maxBuffer: 1 << 28, stdio: ['pipe', 'pipe', 'pipe'] });
  } catch (e) {
    if (e.status === 1 && e.stdout) out = e.stdout;
    else if (e.status === 2) throw new BridgeRejected(`circuitjs-mcp ${args[0]}${args[1] ? ' ' + args[1] : ''}: ${String(e.stderr || e.message).trim()}`);
    else throw new Unreachable(`circuitjs-mcp ${args[0]}${args[1] ? ' ' + args[1] : ''}: ${String(e.stderr || e.message).trim()}`);
  }
  try { return JSON.parse(out); } catch (e) { throw new Unreachable(`circuitjs-mcp ${args[0]}: output is not JSON`); }
}
let rpcUrl = null, rpcId = 0;
/** Direct JSON-RPC request to the selected instance (stateless JSON-response endpoint). */
async function rpc(method, params) {
  let res;
  try {
    res = await fetch(rpcUrl, {
      method: 'POST',
      headers: { 'content-type': 'application/json', accept: 'application/json, text/event-stream', 'mcp-protocol-version': '2025-11-25' },
      body: JSON.stringify({ jsonrpc: '2.0', id: ++rpcId, method, params }),
      signal: AbortSignal.timeout(opt.timeout),
    });
  } catch (e) { throw new Unreachable(`${method} at ${rpcUrl}: ${e.message}`); }
  const type = res.headers.get('content-type') || '';
  if (!res.ok || !type.includes('application/json')) throw new Unreachable(`${method} at ${rpcUrl}: HTTP ${res.status} ${type}`);
  const msg = await res.json();
  if (msg.error) throw new Unreachable(`${method}: ${msg.error.message}`);
  return msg.result;
}
const resource = async (uri) => JSON.parse((await rpc('resources/read', { uri })).contents[0].text);

// ------------------------------------------------------------------ reporting
const groups = [];
let current = null;
function group(name) { current = { name, pass: 0, fail: 0, skip: null }; groups.push(current); }
function check(cond, msg) { if (cond) current.pass++; else { current.fail++; console.log(`  FAIL [${current.name}] ${msg}`); } return !!cond; }

// ------------------------------------------------------------------ skill text
const posix = (p) => p.split(path.sep).join('/');
function walk(dir) { return fs.readdirSync(dir, { withFileTypes: true }).flatMap((d) => (d.isDirectory() ? walk(path.join(dir, d.name)) : [path.join(dir, d.name)])); }
// evals/ (scenarios, fixtures, results) is tooling, not skill text
const text = Object.fromEntries(walk(opt.skill).filter((f) => f.endsWith('.md') && !posix(path.relative(opt.skill, f)).startsWith('evals/')).map((f) => [posix(path.relative(opt.skill, f)), fs.readFileSync(f, 'utf8')]));
const refs = Object.keys(text).filter((f) => f.startsWith('reference/'));
const lineCount = (t) => t.split('\n').length - (t.endsWith('\n') ? 1 : 0);
const lineAt = (t, i) => t.slice(0, i).split('\n').length;

// Sections: a "## " heading starts one; `set` edits resolve their id inside their section.
function sectionOf(t, i) { const s = t.lastIndexOf('\n## ', i); return s < 0 ? 0 : s; }

function balanced(s, i) { // s[i] === '{': end index (exclusive) of the balanced object, or -1
  let depth = 0, str = false;
  for (let j = i; j < s.length; j++) {
    const ch = s[j];
    if (str) { if (ch === '\\') j++; else if (ch === '"') str = false; continue; }
    if (ch === '"') str = true; else if (ch === '{') depth++; else if (ch === '}' && --depth === 0) return j + 1;
  }
  return -1;
}
const tryJson = (s) => { try { return JSON.parse(s); } catch (e) { return undefined; } };

const blocks = [];        // {f, line, sec, c} AgentCircuit blocks
const toolCalls = [];     // {f, line, tool, args}
const shorthand = [];     // {f, line, tool, keys}
const edits = [];         // {f, line, sec, edit}
const elementSpecs = [];  // {f, line, spec}
const badJson = [];       // {f, line}
function collect(f, line, sec, v) {
  if (Array.isArray(v)) v.forEach((x) => collect(f, line, sec, x));
  else if (v && typeof v === 'object') {
    if (typeof v.type === 'string' && v.start && typeof v.start === 'object') elementSpecs.push({ f, line, spec: v });
    if (typeof v.op === 'string') edits.push({ f, line, sec, edit: v });
    Object.values(v).forEach((x) => collect(f, line, sec, x));
  }
}
for (const [f, t] of Object.entries(text)) {
  for (const m of t.matchAll(/```json\n([\s\S]*?)\n```/g)) {
    const line = lineAt(t, m.index) + 1, sec = sectionOf(t, m.index);
    let v = tryJson(m[1]);
    if (v === undefined) v = tryJson('[' + m[1].replace(/,\s*$/, '') + ']'); // fragments: element specs one per line
    if (v === undefined) { badJson.push({ f, line }); continue; }
    if (v && !Array.isArray(v) && Array.isArray(v.elements)) blocks.push({ f, line, sec, c: v });
    collect(f, line, sec, v);
  }
  for (const m of t.matchAll(/\b((?:circuit|bridge)_[a-z]+)\s*\{/g)) {
    const start = m.index + m[0].length - 1, end = balanced(t, start);
    if (end < 0) continue;
    const body = t.slice(start, end), line = lineAt(t, m.index), sec = sectionOf(t, m.index);
    const args = tryJson(body);
    if (args !== undefined) { toolCalls.push({ f, line, tool: m[1], args }); collect(f, line, sec, args); }
    else shorthand.push({ f, line, tool: m[1], keys: [...body.matchAll(/(?<!:\s*)"([A-Za-z_]\w*)"/g)].map((k) => k[1]) });
  }
  for (const m of t.matchAll(/\*\*Run\.\*\*[^`\n]*`(\{[^`]*\})`/g)) {
    const args = tryJson(m[1]);
    if (args === undefined) badJson.push({ f, line: lineAt(t, m.index) });
    else toolCalls.push({ f, line: lineAt(t, m.index), tool: 'circuit_run', args, run: true });
  }
  for (const m of t.matchAll(/`(\{"op"[^`]*\})`/g)) {
    const v = tryJson(m[1]);
    if (v === undefined) badJson.push({ f, line: lineAt(t, m.index) });
    else collect(f, lineAt(t, m.index), sectionOf(t, m.index), v);
  }
}

// ------------------------------------------------------------------ group: form (SP_AGS_03_01)
group('form');
const skill = text['SKILL.md'];
check(lineCount(skill) <= 250, `SKILL.md has ${lineCount(skill)} lines (max 250)`);
const fm = skill.match(/^---\nname: ([^\n]*)\ndescription: ([^\n]*)\n---\n/);
check(fm && fm[1] === 'circuitjs-circuits' && /^[a-z0-9-]{1,64}$/.test(fm[1]), 'SKILL.md frontmatter name must be circuitjs-circuits');
check(fm && fm[2].length > 0 && fm[2].length <= 1024, 'SKILL.md frontmatter description missing or over 1024 characters');
check(refs.length > 0, 'no reference files');
for (const r of refs) {
  const t = text[r], n = lineCount(t);
  check(n <= 400, `${r} has ${n} lines (max 400)`);
  if (n > 100) check((t.split('\n').find((l) => /^## /.test(l)) || '') === '## Contents', `${r} has ${n} lines but does not start with a "## Contents" table`);
  check(!/\]\((?!https?:)[^)]*\)/.test(t), `${r} links another file (references are linked only from SKILL.md)`);
}
const links = [...skill.matchAll(/\]\(([^)]+)\)/g)].map((m) => m[1]).filter((l) => !/^https?:/.test(l));
for (const l of links) {
  check(/^[^/]+\/[^/]+$/.test(l), `SKILL.md link is not one level deep: ${l}`);
  check(fs.existsSync(path.join(opt.skill, l)), `SKILL.md link is broken: ${l}`);
}
for (const r of refs) check(links.includes(r), `SKILL.md does not link ${r}`);
for (const b of badJson) check(false, `${b.f}:${b.line} a json example does not parse`);
check(blocks.length > 0, 'no AgentCircuit blocks found');
check(elementSpecs.length > 0, 'no element specs found');
for (const b of blocks) for (const e of b.c.elements) for (const p of [e.start, e.end].filter(Boolean)) {
  check(Number.isInteger(p.x) && Number.isInteger(p.y), `${b.f}:${b.line} ${e.id || e.type} at (${p.x}, ${p.y}) is not on whole cells`);
}
for (const { f, line, spec } of elementSpecs) for (const p of [spec.start, spec.end].filter(Boolean)) {
  check(Number.isFinite(p.x) && Number.isFinite(p.y) && (p.x * 2) % 1 === 0 && (p.y * 2) % 1 === 0, `${f}:${line} ${spec.id || spec.type} at (${p.x}, ${p.y}) is off the half-cell lattice`);
}

// ------------------------------------------------------------------ group: codes (SP_AGS_03_02)
group('codes');
const section = (doc, from, to) => { const i = doc.indexOf(from), j = doc.indexOf(to, i + 1); return i < 0 ? '' : doc.slice(i, j < 0 ? undefined : j); };
const aga = fs.readFileSync(path.join(opt.docs, 'agent-api.sp.md'), 'utf8');
const mcpSpec = fs.readFileSync(path.join(opt.docs, 'mcp-server.sp.md'), 'utf8');
const bySection = {
  'SP_AGA_03_04': [...section(aga, '### 03_04.', '### 03_05.').matchAll(/\| `([a-z_]+)` \|/g)].map((m) => m[1]),
  'SP_AGA_03_05': [...section(aga, '### 03_05.', '### 03_06.').matchAll(/^\| ([a-z_]+) \|/gm)].map((m) => m[1]),
  'SP_AGA_03_06': [...section(aga, '### 03_06.', '### 03_07.').matchAll(/`([a-z_]+)`/g)].map((m) => m[1]).filter((c) => !NOT_CODES.has(c)),
  'SP_MCP_03_03': [...section(mcpSpec, '### 03_03.', '### 03_04.').matchAll(/issue `([a-z_]+)`/g)].map((m) => m[1]),
};
const specCodes = new Map();
for (const [sec, list] of Object.entries(bySection)) {
  check(new Set(list).size >= MIN_CODES[sec], `${sec}: only ${new Set(list).size} codes parsed (expected at least ${MIN_CODES[sec]}); the spec format changed?`);
  for (const c of list) if (!specCodes.has(c)) specCodes.set(c, sec);
}
check(bySection['SP_MCP_03_03'].includes('result_too_large'), 'SP_MCP_03_03 does not yield result_too_large');
const diag = text['reference/diagnostics.md'];
check(diag !== undefined, 'reference/diagnostics.md missing');
const rows = diag ? [...diag.matchAll(/^\| `([a-z_]+)` \|([^\n]*)$/gm)] : [];
const rowCodes = new Set(rows.map((m) => m[1]));
for (const [c, src] of specCodes) check(rowCodes.has(c), `diagnostics.md has no row for ${c} (${src})`);
for (const c of rowCodes) check(specCodes.has(c), `diagnostics.md row names a code not in the specs: ${c}`);
for (const m of rows) check(m[2].split('|').length >= 5 && m[2].split('|').slice(0, 4).every((c) => c.trim().length > 0), `diagnostics.md row ${m[1]} lacks a column (severity, cause, fix, confirm)`);
console.log(`  codes: ${Object.entries(bySection).map(([s, l]) => `${s} ${new Set(l).size}`).join(', ')}; ${rowCodes.size} rows`);

// ------------------------------------------------------------------ live data
const live = !opt.offline;
let tools, schemas, catalogue, typeInfos, toolsVersion;
if (live) {
  try {
    tools = cli(['tools']).map((t) => t.name);
    if (opt.url) rpcUrl = opt.url;
    else if (process.env.CIRCUITJS_MCP_URL && !opt.pass.includes('--instance')) rpcUrl = process.env.CIRCUITJS_MCP_URL;
    else rpcUrl = (cli(['instances']).find((i) => i.selected) || {}).url;
    if (!rpcUrl) throw new Unreachable('the bridge selects no instance');
    const init = await rpc('initialize', { protocolVersion: '2025-11-25', capabilities: {}, clientInfo: { name: 'check-consistency', version: '1' } });
    toolsVersion = ((init.instructions || '').match(/toolsVersion (\d+)\.(\d+)/) || []).slice(1).map(Number);
    schemas = new Map((await rpc('tools/list', {})).tools.map((t) => [t.name, t]));
    catalogue = (await resource('circuitjs://catalogue')).types;
    typeInfos = new Map(await Promise.all(catalogue.map(async (t) => [t.type, await resource(`circuitjs://catalogue/${t.type}`)])));
  } catch (e) {
    if (e instanceof Unreachable) { console.error(`check-consistency: ${e.message}`); process.exit(2); }
    throw e;
  }
}
// Name sets of the live catalogue and tool schemas
const byName = new Map(), allKeys = new Set(), schemaWords = new Set(), typeWords = new Set();
if (live) {
  for (const t of catalogue) {
    byName.set(t.type, t.type); for (const a of t.aliases) byName.set(a, t.type);
    typeWords.add(t.geometry); t.pins.forEach((p) => typeWords.add(p));
  }
  for (const ti of typeInfos.values()) { ti.properties.forEach((p) => allKeys.add(p.key)); typeWords.add(ti.idPrefix); }
  const words = (s) => {
    if (!s || typeof s !== 'object') return;
    if (Array.isArray(s)) return s.forEach(words);
    if (s.properties) Object.keys(s.properties).forEach((k) => schemaWords.add(k));
    for (const k of ['enum']) if (Array.isArray(s[k])) s[k].forEach((v) => typeof v === 'string' && schemaWords.add(v));
    if (typeof s.const === 'string') schemaWords.add(s.const);
    Object.values(s).forEach(words);
  };
  for (const t of schemas.values()) { words(t.inputSchema); words(t.outputSchema); }
}
const keysOf = (type) => new Set(typeInfos.get(byName.get(type)).properties.map((p) => p.key));

// ------------------------------------------------------------------ group: names (SP_AGS_03_02)
group('names');
if (!live) current.skip = 'offline';
else {
  let n = 0;
  for (const [f, t] of Object.entries(text)) {
    for (const m of t.matchAll(/`([a-z][a-z0-9]*(?:_[a-z0-9]+)+)`/g)) {
      const w = m[1]; n++;
      check(specCodes.has(w) || allKeys.has(w) || schemaWords.has(w) || tools.includes(w) || BRIDGE_TOOLS.includes(w) || typeWords.has(w) || OTHER_WORDS.has(w),
        `${f}:${lineAt(t, m.index)} \`${w}\` is no issue code, property key, tool, argument or catalogue word`);
    }
  }
  check(n > 0, 'no snake_case names found');
  console.log(`  names: ${n} snake_case names checked`);
}

// ------------------------------------------------------------------ group: tools (SP_AGS_03_02, SP_AGS_04_01, SP_AGS_05_03)
group('tools');
if (!live) current.skip = 'offline';
else {
  const all = Object.values(text).join('\n');
  for (const t of new Set([...all.matchAll(/\b((?:circuit|bridge)_[a-z]+)\b/g)].map((m) => m[1]))) check(tools.includes(t) || BRIDGE_TOOLS.includes(t), `the skill names a tool that does not exist: ${t}`);
  const skillNamed = new Set([...skill.matchAll(/\b((?:circuit|bridge)_[a-z]+)\b/g)].map((m) => m[1]));
  for (const t of [...tools, ...BRIDGE_TOOLS]) check(skillNamed.has(t), `SKILL.md does not name tool ${t}`);
  // toolsVersion: same MAJOR, instance MINOR >= the skill's
  const line = skill.match(/works with \*\*toolsVersion (\d+)\.(\d+)\*\*/);
  if (check(line, 'SKILL.md has no "works with **toolsVersion X.Y**" line') && check(toolsVersion.length === 2, 'the server instructions carry no toolsVersion')) {
    const [maj, min] = [Number(line[1]), Number(line[2])];
    check(toolsVersion[0] === maj && toolsVersion[1] >= min, `instance toolsVersion ${toolsVersion.join('.')} does not match the skill's ${maj}.${min}`);
  }
  // argument keys against the served input schemas (edits by their op branch)
  const keyErrors = (v, s, at, top) => {
    if (!s || v === null || typeof v !== 'object') return [];
    if (Array.isArray(v)) return s.items ? v.flatMap((x, i) => keyErrors(x, s.items, `${at}[${i}]`)) : [];
    if (s.oneOf) {
      if (v.op !== undefined) {
        const branch = s.oneOf.find((b) => b.properties && b.properties.op && b.properties.op.const === v.op);
        return branch ? keyErrors(v, branch, at) : [`${at}.op "${v.op}" is not an edit op`];
      }
      const obj = s.oneOf.find((b) => b.type === 'object' || b.properties);
      return obj ? keyErrors(v, obj, at) : [];
    }
    if (!s.properties) return [];
    const out = [];
    for (const k of Object.keys(v)) {
      if (!(k in s.properties)) { if (top || s.additionalProperties === false) out.push(`${at}.${k} is not an argument`); continue; }
      if (k === 'properties' && at.endsWith('element')) continue; // element property maps: catalogue group
      out.push(...keyErrors(v[k], s.properties[k], `${at}.${k}`));
    }
    return out;
  };
  const allArgs = (tool) => { const s = new Set(); const w = (x) => { if (!x || typeof x !== 'object') return; if (Array.isArray(x)) return x.forEach(w); if (x.properties) Object.keys(x.properties).forEach((k) => s.add(k)); Object.values(x).forEach(w); }; w(schemas.get(tool).inputSchema); return s; };
  for (const { f, line, tool, args } of toolCalls) {
    if (!check(schemas.has(tool) || BRIDGE_TOOLS.includes(tool), `${f}:${line} ${tool} is not served`) || !schemas.has(tool)) continue;
    const errs = keyErrors(args, schemas.get(tool).inputSchema, tool, true);
    check(errs.length === 0, `${f}:${line} ${errs.join('; ')}`);
  }
  for (const { f, line, tool, keys } of shorthand) {
    if (!schemas.has(tool)) { check(BRIDGE_TOOLS.includes(tool), `${f}:${line} ${tool} is not served`); continue; }
    const known = allArgs(tool);
    for (const k of keys) check(known.has(k), `${f}:${line} ${tool} shorthand names "${k}", which is not an argument of ${tool}`);
  }
  const editSchema = schemas.get('circuit_edit').inputSchema.properties.edits.items;
  for (const { f, line, edit } of edits) { const errs = keyErrors(edit, editSchema, 'edit'); check(errs.length === 0, `${f}:${line} ${errs.join('; ')}`); }
  check(toolCalls.some((c) => !c.run) && toolCalls.some((c) => c.run) && edits.length > 0 && shorthand.length > 0, `too few tool calls found (calls ${toolCalls.filter((c) => !c.run).length}, runs ${toolCalls.filter((c) => c.run).length}, edits ${edits.length}, shorthand ${shorthand.length})`);
  console.log(`  tools: ${tools.length} served + ${BRIDGE_TOOLS.length} bridge; ${toolCalls.length} calls (${toolCalls.filter((c) => c.run).length} runs), ${shorthand.length} shorthand, ${edits.length} edits; toolsVersion ${toolsVersion.join('.')}`);
}

// ------------------------------------------------------------------ group: catalogue (SP_AGS_03_02, SP_AGS_05_03)
group('catalogue');
if (!live) current.skip = 'offline';
else {
  // elements.md table: canonical name, aliases, pins in post order, geometry, property keys
  const el = text['reference/elements.md'] || '';
  const tableRows = el.split('\n').filter((l) => /^\| `[A-Za-z0-9]+`/.test(l));
  check(tableRows.length > 0, 'reference/elements.md has no type table');
  const ticks = (c) => [...c.matchAll(/`([^`]+)`/g)].map((m) => m[1]);
  for (const r of tableRows) {
    const cols = r.split(' | ');
    const [type, ...aliases] = ticks(cols[0]);
    if (!check(byName.get(type) === type, `elements.md: ${type} is not a canonical catalogue name${byName.has(type) ? ` (canonical: ${byName.get(type)})` : ''}`)) continue;
    const ti = typeInfos.get(type);
    for (const a of aliases) check(ti.aliases.includes(a), `elements.md: ${a} is not an alias of ${type}`);
    const pins = ticks(cols[1]);
    check(JSON.stringify(pins) === JSON.stringify(ti.pins), `elements.md: ${type} pins ${pins.join(',')} differ from the catalogue ${ti.pins.join(',')}`);
    check(cols[2].trim() === ti.geometry, `elements.md: ${type} geometry ${cols[2].trim()} differs from ${ti.geometry}`);
    const keys = new Set(ti.properties.map((p) => p.key));
    for (const k of ticks(cols[3]).filter((k) => /^[a-z][a-z0-9_]*$/.test(k))) check(keys.has(k), `elements.md: ${k} is not a property of ${type}`);
  }
  // spec-named per-type reads through the bridge CLI, for the table types
  for (const r of tableRows.slice(0, 3)) {
    const t = ticks(r.split(' | ')[0])[0];
    if (byName.get(t) !== t) continue;
    let viaCli = null;
    try { viaCli = JSON.parse(cli(['read', `circuitjs://catalogue/${t}`])[0].text); } catch (e) {
      if (e instanceof Unreachable && !(e instanceof BridgeRejected)) { console.error(`check-consistency: ${e.message}`); process.exit(2); }
    }
    check(viaCli && JSON.stringify(viaCli.pins) === JSON.stringify(typeInfos.get(t).pins), `circuitjs-mcp read circuitjs://catalogue/${t} failed or differs from the direct read`);
  }
  // element specs in examples and edits: type and property keys
  for (const { f, line, spec } of elementSpecs) {
    if (!check(byName.has(spec.type), `${f}:${line} ${spec.id || '?'} has unknown type ${spec.type}`)) continue;
    const keys = keysOf(spec.type);
    for (const k of Object.keys(spec.properties || {})) check(keys.has(k), `${f}:${line} ${spec.id || spec.type}: ${k} is not a property of ${byName.get(spec.type)}`);
  }
  // `set` edits: the id resolves through the elements of the AgentCircuit block of the same section
  let resolved = 0;
  for (const { f, line, sec, edit } of edits.filter((e) => e.edit.op === 'set' && e.edit.properties)) {
    const b = blocks.find((x) => x.f === f && x.sec === sec);
    const el2 = b && b.c.elements.find((e) => e.id === edit.id);
    if (el2 && byName.has(el2.type)) {
      resolved++;
      const keys = keysOf(el2.type);
      for (const k of Object.keys(edit.properties)) check(keys.has(k), `${f}:${line} set ${edit.id}: ${k} is not a property of ${byName.get(el2.type)}`);
    } else {
      for (const k of Object.keys(edit.properties)) check(allKeys.has(k), `${f}:${line} set ${edit.id}: ${k} is no property key of any type`);
    }
  }
  check(resolved > 0, 'no set edit resolved through a pattern block');
  // type names in prose: backticked capitalised words of 3+ characters; ID prefixes, pins and example IDs are not types
  const exampleIds = new Set(blocks.flatMap((b) => b.c.elements.map((e) => e.id)));
  for (const [f, t] of Object.entries(text)) {
    for (const m of t.matchAll(/`([A-Z][A-Za-z0-9]{2,})`/g)) {
      const w = m[1];
      check(byName.has(w) || typeWords.has(w) || exampleIds.has(w), `${f}:${lineAt(t, m.index)} \`${w}\` is not a catalogue type, alias, pin or ID prefix`);
    }
  }
  console.log(`  catalogue: ${catalogue.length} types; ${tableRows.length} table rows, ${elementSpecs.length} element specs, ${resolved} set edits resolved`);
}

// ------------------------------------------------------------------ group: examples (SP_AGS_03_02, SP_AGS_05_03)
group('examples');
if (!live) current.skip = 'offline';
else {
  let unreachable = null; // set on a lost instance: the finally still closes the document, then exit 2
  for (const b of blocks) {
    if (unreachable) break;
    let doc = null;
    try {
      const cr = cli(['call', 'circuit_documents', JSON.stringify({ action: 'create', title: 'skill check' })]);
      if (!check(cr && cr.ok && cr.data && cr.data.doc, `${b.f}:${b.line} could not create a scratch document: ${JSON.stringify(cr && cr.issues)}`)) continue;
      doc = cr.data.doc;
      const r = cli(['call', 'circuit_import', '-'], JSON.stringify({ doc, circuit: b.c }));
      const errs = r.ok ? ((r.connectivity && r.connectivity.added) || []).filter((i) => i.severity === 'error').map((i) => i.code) : (r.issues || []).map((i) => `${i.code}: ${i.message}`);
      check(r.ok && r.connectivity && r.connectivity.errorCount === 0, `${b.f}:${b.line} import ok=${r.ok}, errors=${r.ok && r.connectivity ? r.connectivity.errorCount : '-'} ${errs.join('; ')}`);
    } catch (e) {
      if (e instanceof BridgeRejected) check(false, `${b.f}:${b.line} the import request was rejected: ${e.message}`);
      else if (e instanceof Unreachable) unreachable = e;
      else check(false, `${b.f}:${b.line} ${e.message}`);
    } finally {
      if (doc) {
        let cl;
        try { cl = cli(['call', 'circuit_documents', JSON.stringify({ action: 'close', doc, discardChanges: true })]); } catch (e) { cl = null; }
        check(cl && cl.ok, `scratch document ${doc} was not closed (left open in the app)`);
      }
    }
  }
  if (unreachable) { console.error(`check-consistency: ${unreachable.message}`); process.exit(2); }
  console.log(`  examples: ${blocks.length} AgentCircuit blocks`);
}

// ------------------------------------------------------------------ summary
let failed = 0;
for (const g of groups) {
  if (g.skip) console.log(`SKIP ${g.name} (${g.skip})`);
  else { console.log(`${g.fail ? 'FAIL' : 'PASS'} ${g.name}: ${g.pass} passed, ${g.fail} failed`); failed += g.fail; }
}
console.log(failed ? `FAIL: ${failed} check(s) failed` : 'PASS: all checks passed');
process.exit(failed ? 1 : 0);
