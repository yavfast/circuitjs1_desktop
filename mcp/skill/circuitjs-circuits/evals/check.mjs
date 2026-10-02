#!/usr/bin/env node
// [SP_AGS_01_03] [SP_AGS_05_01] [SP_AGS_05_02] Eval checker of the circuitjs-circuits skill.
// It reads the app's state through the circuitjs-mcp bridge CLI and never trusts the agent's claims.
//
// Usage:
//   node evals/check.mjs prepare [--scenario <id>]... [--state <file>] [bridge options]
//       Loads each selected scenario's fixture into a new document (sealed with the checkpoint
//       "eval fixture <id>", which the checkpoint check does not count), records the newest document
//       handle for the scenarios without a fixture, and writes the state file. Prints
//       {scenarios: {<id>: {doc?, prompt, ...}}}; `prompt` already ends with
//       "The circuit is open in document <doc>." for fixture scenarios.
//   node evals/check.mjs [check] [--scenario <id>]... [--state <file>] [--doc <handle>] [--close] [bridge options]
//       Evaluates the checks of the selected scenarios (default: those in the state file, or all with
//       --doc). Document: --doc; else the fixture document of the state file; else the most recently
//       created document after the ones `prepare` created (fixture documents excluded). Every measure
//       runs with reset: true. Prints {pass, scenarios: [{id, doc, pass, checks: [{type, pass, detail}]}]}.
//       --close closes the checked documents and the fixture documents afterwards (discarding changes).
//
//   --state     state file (default: <tmpdir>/circuitjs-evals-state.json)
//   --evals     scenario file (default: evals.json next to this script)
//   --bridge    bridge entry script (default: <repo>/mcp/bridge/bin/circuitjs-mcp.js; when the skill is
//               installed outside the repository, pass it, or set CIRCUITJS_MCP_BIN)
//   --url, --instance, --registry, --timeout   passed to the bridge unchanged
//
// Checker extension of ProbeSpec: {elementType, quantity?} probes the only element of that type
// (the agent chooses its IDs); zero or several such elements fail the check.
//
// Exit code: 0 every check of every selected scenario passes; 1 a check failed; 2 usage error or no
// reachable instance.

import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const MEASURE_BUDGET_MS = 60000;

// ------------------------------------------------------------------ options
function usage(msg) { console.error(`check: ${msg}`); process.exit(2); }
const argv = process.argv.slice(2);
const opt = {
  mode: 'check', close: false, scenarios: [], state: path.join(os.tmpdir(), 'circuitjs-evals-state.json'), doc: null,
  evals: path.join(HERE, 'evals.json'),
  bridge: process.env.CIRCUITJS_MCP_BIN || path.resolve(HERE, '..', '..', '..', 'bridge', 'bin', 'circuitjs-mcp.js'),
  pass: [],
};
if (argv[0] === 'prepare' || argv[0] === 'check') opt.mode = argv.shift();
for (let i = 0; i < argv.length; i++) {
  const a = argv[i];
  const val = () => { if (i + 1 >= argv.length) usage(`${a} needs a value`); return argv[++i]; };
  if (a === '--scenario') opt.scenarios.push(val());
  else if (a === '--state') opt.state = path.resolve(val());
  else if (a === '--doc') opt.doc = val();
  else if (a === '--close') opt.close = true;
  else if (a === '--evals') opt.evals = path.resolve(val());
  else if (a === '--bridge') opt.bridge = path.resolve(val());
  else if (['--url', '--instance', '--registry', '--timeout'].includes(a)) opt.pass.push(a, val());
  else if (a === '--help' || a === '-h') { console.log(fs.readFileSync(fileURLToPath(import.meta.url), 'utf8').split('\n').slice(1).filter((l, i, a) => a.slice(0, i + 1).every((x) => x.startsWith('//'))).map((l) => l.replace(/^\/\/ ?/, '')).join('\n')); process.exit(0); }
  else usage(`unknown argument ${a}`);
}
const evals = JSON.parse(fs.readFileSync(opt.evals, 'utf8'));
const byId = new Map(evals.scenarios.map((s) => [s.id, s]));
for (const id of opt.scenarios) if (!byId.has(id)) usage(`unknown scenario ${id}`);
if (opt.doc && opt.scenarios.length !== 1) usage('--doc needs exactly one --scenario');
if (!fs.existsSync(opt.bridge)) usage(`bridge not found: ${opt.bridge} (pass --bridge)`);

// ------------------------------------------------------------------ bridge CLI
class Unreachable extends Error {}
/** One CLI call: the OperationResult (an isError result, exit 1, is returned too). */
function call(tool, args) {
  let out;
  try {
    out = execFileSync(process.execPath, [opt.bridge, 'call', tool, '-', ...opt.pass], { input: JSON.stringify(args), encoding: 'utf8', maxBuffer: 1 << 28, stdio: ['pipe', 'pipe', 'pipe'] });
  } catch (e) {
    if (e.status === 1 && e.stdout) out = e.stdout;
    else if (e.status === 2) return { ok: false, issues: [{ code: 'request_rejected', message: String(e.stderr).trim() }] };
    else throw new Unreachable(`circuitjs-mcp call ${tool}: ${String(e.stderr || e.message).trim()}`);
  }
  return JSON.parse(out);
}
const why = (r) => (r.issues || []).map((i) => `${i.code}: ${i.message}`).join('; ');
const handleNo = (doc) => Number(String(doc).slice(1));
function documents() {
  const r = call('circuit_documents', { action: 'list' });
  if (!r.ok) throw new Unreachable(`circuit_documents list: ${why(r)}`);
  return r.data.documents;
}
const newestHandle = () => Math.max(0, ...documents().map((d) => handleNo(d.doc)));

// ------------------------------------------------------------------ prepare
function prepare() {
  const ids = opt.scenarios.length ? opt.scenarios : [...byId.keys()];
  const state = { createdAt: new Date().toISOString(), evalsVersion: evals.version, scenarios: {}, fixtureDocs: [] };
  for (const id of ids) {
    const s = byId.get(id);
    const entry = { prompt: s.prompt };
    if (s.fixture) {
      const content = fs.readFileSync(path.join(path.dirname(opt.evals), 'fixtures', s.fixture), 'utf8');
      const cr = call('circuit_documents', { action: 'create', title: `eval ${id}` });
      if (!cr.ok) throw new Unreachable(`create: ${why(cr)}`);
      const doc = cr.data.doc;
      const imp = call('circuit_import', { doc, circuit: content });
      if (!imp.ok) { console.error(`check: fixture ${s.fixture} does not import: ${why(imp)}`); process.exit(2); }
      const cp = call('circuit_checkpoint', { doc, comment: `eval fixture ${id}` });
      entry.doc = doc;
      state.fixtureDocs.push(doc);
      entry.fixtureCheckpoint = cp.ok ? cp.data.checkpointId : null;
      entry.prompt = `${s.prompt}\n\nThe circuit is open in document ${doc}.`;
    }
    state.scenarios[id] = entry;
  }
  // one baseline after every fixture document exists: an agent's document is newer than all of them
  state.baselineHandle = newestHandle();
  fs.writeFileSync(opt.state, JSON.stringify(state, null, 2));
  return state;
}

// ------------------------------------------------------------------ checks
function allElements(doc) {
  const els = [];
  for (let offset = 0; ;) {
    const r = call('circuit_get', { doc, offset, limit: 500 });
    if (!r.ok) throw new Error(`circuit_get: ${why(r)}`);
    els.push(...r.data.elements);
    if (r.data.nextOffset === undefined) return els;
    offset = r.data.nextOffset;
  }
}
const canonical = new Map();
function canonicalType(name) {
  if (!canonical.has(name)) { const r = call('circuit_types', { type: name }); canonical.set(name, r.ok ? r.data.type : null); }
  return canonical.get(name);
}
function compare(c, value) {
  const mode = c.compare || 'approx';
  if (mode === 'atLeast') return value >= c.target;
  if (mode === 'atMost') return value <= c.target;
  return Math.abs(value - c.target) <= c.tolerance * Math.abs(c.target);
}
const evaluators = {
  connectivity_clean(doc) {
    const r = call('circuit_connectivity', { doc, includeNets: false });
    if (!r.ok) return [false, `circuit_connectivity: ${why(r)}`];
    const errs = r.data.issues.filter((i) => i.severity === 'error');
    return [errs.length === 0, errs.length ? `${errs.length} error issue(s): ${errs.slice(0, 5).map((i) => i.code + (i.elements ? ' ' + i.elements.join(',') : '')).join('; ')}` : '0 error issues'];
  },
  element_count(doc, c) {
    const type = canonicalType(c.elementType);
    if (!type) return [false, `unknown type ${c.elementType}`];
    const n = allElements(doc).filter((e) => e.type === type).length;
    return [n >= c.min && (c.max === undefined || n <= c.max), `${n} ${type} (min ${c.min}${c.max !== undefined ? `, max ${c.max}` : ''})`];
  },
  measure(doc, c) {
    let probe = Object.assign({}, c.probe);
    if (probe.elementType) {
      const type = canonicalType(probe.elementType);
      const found = type ? allElements(doc).filter((e) => e.type === type) : [];
      if (found.length !== 1) return [false, `expected exactly one ${probe.elementType}, found ${found.length}`];
      probe = { element: found[0].id, quantity: probe.quantity || 'voltage' };
    }
    const args = { doc, reset: true, span: c.run.span, maxPoints: c.run.maxPoints || 200, budgetMs: MEASURE_BUDGET_MS, probes: [Object.assign(probe, { name: 'm' })] };
    if (c.run.recordFrom !== undefined) args.recordFrom = c.run.recordFrom;
    const r = call('circuit_run', args);
    if (!r.ok) return [false, `circuit_run: ${why(r)}`];
    if (r.data.reason !== 'span_reached') return [false, `run ended with ${r.data.reason}`];
    const value = r.data.probes[0].stats[c.stat];
    if (typeof value !== 'number') return [false, `stat ${c.stat} absent (samples ${r.data.probes[0].stats.samples})`];
    const target = (c.compare || 'approx') === 'approx' ? `${c.target} ± ${c.tolerance * 100} %` : `${c.compare} ${c.target}`;
    return [compare(c, value), `${JSON.stringify(c.probe)} ${c.stat} = ${value} (${target})`, value];
  },
  checkpoint_exists(doc, c, entry) {
    const r = call('circuit_history', { doc, action: 'list', limit: 150 });
    if (!r.ok) return [false, `circuit_history: ${why(r)}`];
    const cps = r.data.undo.filter((e) => e.checkpointId && !e.auto && e.kind === 'agent' && e.checkpointId !== (entry && entry.fixtureCheckpoint));
    return [cps.length >= c.minCount, `${cps.length} agent checkpoint(s)${cps.length ? ': ' + cps.slice(0, 3).map((e) => JSON.stringify(e.comment)).join(', ') : ''} (min ${c.minCount})`];
  },
  no_solver_stop(doc) {
    const r = call('circuit_diagnostics', { doc });
    if (!r.ok) return [false, `circuit_diagnostics: ${why(r)}`];
    return [r.data.stopped === false, r.data.stopped ? `stopped: ${r.data.stop ? r.data.stop.code + ' ' + r.data.stop.message : '?'}` : 'not stopped'];
  },
};

function checkScenario(s, entry, state) {
  let doc = opt.doc || (entry && entry.doc) || null;
  if (!doc && entry) {
    const newer = documents().map((d) => d.doc).filter((d) => handleNo(d) > state.baselineHandle && !state.fixtureDocs.includes(d)).sort((a, b) => handleNo(b) - handleNo(a));
    doc = newer[0] || null;
  }
  if (!doc) return { id: s.id, doc: null, pass: false, checks: [{ type: 'document', pass: false, detail: 'no document: the agent created none after prepare' }] };
  if (!documents().some((d) => d.doc === doc)) return { id: s.id, doc, pass: false, checks: [{ type: 'document', pass: false, detail: `document ${doc} is not open` }] };
  // no_solver_stop reads the state after the measure runs: evaluate it last
  const ordered = [...s.checks.filter((c) => c.type !== 'no_solver_stop'), ...s.checks.filter((c) => c.type === 'no_solver_stop')];
  const checks = [];
  for (const c of ordered) {
    const ev = evaluators[c.type];
    let res;
    try { res = ev ? ev(doc, c, entry) : [false, `unknown check type ${c.type}`]; } catch (e) {
      if (e instanceof Unreachable) throw e;
      res = [false, e.message];
    }
    const out = { type: c.type, pass: res[0], detail: res[1] };
    if (res.length > 2) out.value = res[2];
    checks.push(out);
  }
  return { id: s.id, doc, pass: checks.every((c) => c.pass), checks };
}

// ------------------------------------------------------------------ main
try {
  if (opt.mode === 'prepare') {
    const st = prepare();
    console.log(JSON.stringify(st, null, 2));
    process.exit(0);
  }
  let state = null;
  if (!opt.doc) {
    if (!fs.existsSync(opt.state)) usage(`no state file ${opt.state}: run "check.mjs prepare" first, or pass --scenario and --doc`);
    state = JSON.parse(fs.readFileSync(opt.state, 'utf8'));
  }
  const ids = opt.scenarios.length ? opt.scenarios : Object.keys(state.scenarios);
  const results = ids.map((id) => checkScenario(byId.get(id), state ? state.scenarios[id] : null, state));
  const pass = results.length > 0 && results.every((r) => r.pass);
  const closed = [];
  if (opt.close) {
    const open = new Set(documents().map((d) => d.doc));
    for (const doc of new Set([...results.map((r) => r.doc), ...(state ? state.fixtureDocs : [])].filter((d) => d && open.has(d)))) {
      const r = call('circuit_documents', { action: 'close', doc, discardChanges: true });
      closed.push({ doc, ok: r.ok });
    }
  }
  console.log(JSON.stringify(Object.assign({ pass, evalsVersion: evals.version, scenarios: results }, opt.close ? { closed } : {}), null, 2));
  process.exit(pass ? 0 : 1);
} catch (e) {
  if (e instanceof Unreachable) { console.error(`check: ${e.message}`); process.exit(2); }
  throw e;
}
