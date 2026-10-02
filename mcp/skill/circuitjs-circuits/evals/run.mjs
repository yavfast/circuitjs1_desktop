#!/usr/bin/env node
// [SP_AGS_05_02] Eval run driver: one Claude Code non-interactive run (`claude -p`) per
// scenario × model × repetition, each followed by the checker (check.mjs); appends the results of
// this skill version to evals/results.md.
//
// SPENDS MODEL USAGE: run it only with the developer's go-ahead. `--dry-run` prints what it would do.
//
// Usage:
//   node evals/run.mjs [--models haiku,sonnet] [--reps 3] [--scenario <id>]... [--max-budget-usd <n>]
//        [--claude <bin>] [--results <file>] [--keep] [--dry-run] [bridge options]
//
//   --models          comma-separated `claude --model` values (default haiku,sonnet: two model sizes)
//   --reps            repetitions per scenario and model (default 3)
//   --max-budget-usd  cost cap of every `claude -p` run (default 2; "none" removes it). The CLI has no
//                     turn-cap flag (claude 2.1.287 --help lists none): budget and a 20 min wall timeout
//                     bound each run
//   --results         results file (default: results.md next to this script)
//   --keep            keep each run's temporary directory (transcript, state, config) and leave the
//                     run's documents open in the app (default: closed after the check, changes discarded)
//   --dry-run         create the temporary config of the first run and print every command; no agent
//                     run, no document created, no results written
//   --url, --instance, --registry, --timeout, --bridge   passed to the bridge and the checker
//
// Isolation per run (the user's Claude Code configuration files are not read or changed; the CLI may
// still write its own caches and logs under ~/.claude, and uses the user's login):
//   - a temporary project directory is the working directory; the skill (without evals/) is copied
//     into its .claude/skills/circuitjs-circuits/, the project skills location of hosts/claude-code.md;
//   - --setting-sources project: no user or local settings; --no-session-persistence: no saved session;
//   - --strict-mcp-config --mcp-config <tmp>/mcp.json: only the `circuitjs` HTTP server of the
//     instance the bridge selects;
//   - --tools Read,Skill,ListMcpResourcesTool,ReadMcpResourceTool: no other built-in tool (no Bash,
//     Write, Edit, Glob, WebFetch); --allowedTools those plus the circuit tools except circuit_file
//     (also in --disallowedTools); --permission-mode dontAsk: anything else is denied, never prompted
//     (the CLI has no "default" mode in -p; its choices are acceptEdits, auto, bypassPermissions,
//     manual, dontAsk, plan);
//   - the stream-json `init` message is verified: tools within that set, the circuitjs server
//     connected, no skill, plugin or user command other than circuitjs-circuits. A run that fails this
//     is INVALID (an infrastructure failure, recorded as such), not a failed scenario.
// Pass rule (SP_AGS_05_02): a scenario passes when all its checks pass; a skill version is releasable
// when every scenario of evals.json ran at least 3 times on each of at least two models and passed in
// at least 2 of 3 (two thirds) of its runs on each model; invalid runs count as not passed.

import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { execFileSync, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const SKILL_DIR = path.resolve(HERE, '..');
const CHECK = path.join(HERE, 'check.mjs');
const RUN_TIMEOUT_MS = 20 * 60 * 1000;
const KNOWN_TOOLS = ['circuit_types', 'circuit_documents', 'circuit_import', 'circuit_edit', 'circuit_get', 'circuit_connectivity', 'circuit_read', 'circuit_render', 'circuit_sim', 'circuit_run', 'circuit_diagnostics', 'circuit_checkpoint', 'circuit_history', 'circuit_file'];
const DENIED_TOOLS = ['circuit_file']; // no file access in evals
const BUILTIN_TOOLS = ['Read', 'Skill', 'ListMcpResourcesTool', 'ReadMcpResourceTool'];
const SKILL_NAME = 'circuitjs-circuits';

function usage(msg) { console.error(`run: ${msg}`); process.exit(2); }
const argv = process.argv.slice(2);
const opt = { models: ['haiku', 'sonnet'], reps: 3, scenarios: [], budget: '2', claude: 'claude', results: path.join(HERE, 'results.md'), keep: false, dry: false, pass: [], bridge: process.env.CIRCUITJS_MCP_BIN || path.resolve(SKILL_DIR, '..', '..', 'bridge', 'bin', 'circuitjs-mcp.js') };
for (let i = 0; i < argv.length; i++) {
  const a = argv[i];
  const val = () => { if (i + 1 >= argv.length) usage(`${a} needs a value`); return argv[++i]; };
  if (a === '--models') opt.models = val().split(',').map((s) => s.trim()).filter(Boolean);
  else if (a === '--reps') opt.reps = Number(val());
  else if (a === '--scenario') opt.scenarios.push(val());
  else if (a === '--max-budget-usd') { const v = val(); opt.budget = v === 'none' ? null : v; }
  else if (a === '--claude') opt.claude = val();
  else if (a === '--results') opt.results = path.resolve(val());
  else if (a === '--keep') opt.keep = true;
  else if (a === '--dry-run') opt.dry = true;
  else if (a === '--bridge') opt.bridge = path.resolve(val());
  else if (['--url', '--instance', '--registry', '--timeout'].includes(a)) opt.pass.push(a, val());
  else if (a === '--help' || a === '-h') { console.log(fs.readFileSync(fileURLToPath(import.meta.url), 'utf8').split('\n').slice(1).filter((l, i, a) => a.slice(0, i + 1).every((x) => x.startsWith('//'))).map((l) => l.replace(/^\/\/ ?/, '')).join('\n')); process.exit(0); }
  else usage(`unknown argument ${a}`);
}
if (!Number.isInteger(opt.reps) || opt.reps < 1) usage('--reps must be a positive integer');
const evals = JSON.parse(fs.readFileSync(path.join(HERE, 'evals.json'), 'utf8'));
const scenarios = opt.scenarios.length ? opt.scenarios.map((id) => evals.scenarios.find((s) => s.id === id) || usage(`unknown scenario ${id}`)) : evals.scenarios;

// ------------------------------------------------------------------ instance (through the bridge)
function bridge(args) {
  return JSON.parse(execFileSync(process.execPath, [opt.bridge, ...args, ...opt.pass], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }));
}
let url = null, tools = KNOWN_TOOLS, toolsVersion = null, appVersion = null;
try {
  const urlArg = opt.pass.indexOf('--url');
  const inst = bridge(['instances']);
  const sel = inst.find((i) => i.selected);
  url = urlArg >= 0 ? opt.pass[urlArg + 1] : sel && sel.url;
  if (sel) { toolsVersion = sel.toolsVersion; appVersion = sel.appVersion; }
  tools = bridge(['tools']).map((t) => t.name);
} catch (e) {
  if (!opt.dry) usage(`no reachable CircuitJS1 instance: ${String(e.stderr || e.message).trim()}`);
}
if (!url) { if (!opt.dry) usage('the bridge selects no instance'); url = '<URL of the selected instance>'; }

// ------------------------------------------------------------------ one run
const q = (s) => (/^[\w@%+=:,./-]+$/.test(s) ? s : `'${String(s).replace(/'/g, `'\\''`)}'`);
const checkerArgs = (extra) => [CHECK, ...extra, '--bridge', opt.bridge, ...opt.pass];

function setup(tag) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), `circuitjs-eval-${tag}-`));
  const project = path.join(dir, 'project');
  const skillDest = path.join(project, '.claude', 'skills', 'circuitjs-circuits');
  fs.mkdirSync(path.dirname(skillDest), { recursive: true });
  fs.cpSync(SKILL_DIR, skillDest, { recursive: true, filter: (src) => !path.relative(SKILL_DIR, src).split(path.sep).includes('evals') });
  const mcpConfig = path.join(dir, 'mcp.json');
  fs.writeFileSync(mcpConfig, JSON.stringify({ mcpServers: { circuitjs: { type: 'http', url } } }, null, 2));
  return { dir, project, skillDest, mcpConfig, state: path.join(dir, 'state.json'), transcript: path.join(dir, 'transcript.jsonl') };
}
const mcpAllowed = () => tools.filter((t) => !DENIED_TOOLS.includes(t)).map((t) => `mcp__circuitjs__${t}`);
function claudeArgs(ctx, model, prompt) {
  const a = ['-p', prompt, '--model', model, '--output-format', 'stream-json', '--verbose',
    '--setting-sources', 'project', '--no-session-persistence',
    '--strict-mcp-config', '--mcp-config', ctx.mcpConfig,
    '--tools', BUILTIN_TOOLS.join(','),
    '--allowedTools', ...BUILTIN_TOOLS, ...mcpAllowed(),
    '--disallowedTools', ...DENIED_TOOLS.map((t) => `mcp__circuitjs__${t}`),
    '--permission-mode', 'dontAsk'];
  if (opt.budget) a.push('--max-budget-usd', String(opt.budget));
  return a;
}

// Names of the user's own skills and commands (only their names are listed, to detect a leak)
const userItems = (() => {
  const out = new Set();
  for (const d of ['skills', 'commands']) {
    try { for (const n of fs.readdirSync(path.join(os.homedir(), '.claude', d))) out.add(n.replace(/\.md$/, '')); } catch (e) { /* none */ }
  }
  out.delete(SKILL_NAME);
  return out;
})();
/** Isolation problems seen in the stream-json init message (empty: the run is valid). */
function isolationProblems(init) {
  if (!init || init.type !== 'system') return ['no init message'];
  const p = [];
  const allowed = new Set([...BUILTIN_TOOLS, ...mcpAllowed()]);
  const extra = (init.tools || []).filter((t) => !allowed.has(t));
  if (extra.length) p.push(`extra tools: ${extra.join(', ')}`);
  const missing = mcpAllowed().filter((t) => !(init.tools || []).includes(t));
  if (missing.length) p.push(`missing tools: ${missing.join(', ')}`);
  const servers = init.mcp_servers || [];
  const cj = servers.find((m) => m.name === 'circuitjs');
  if (!cj || cj.status !== 'connected') p.push(`circuitjs server ${cj ? cj.status : 'missing'}`);
  const others = servers.filter((m) => m.name !== 'circuitjs').map((m) => m.name);
  if (others.length) p.push(`other MCP servers: ${others.join(', ')}`);
  const skillNames = (init.skills || []).map((s) => (typeof s === 'string' ? s : s.name));
  if (init.skills !== undefined && !(skillNames.length === 1 && skillNames[0] === SKILL_NAME)) p.push(`skills: ${skillNames.join(', ') || 'none'} (expected only ${SKILL_NAME})`);
  const cmds = (init.slash_commands || []).map((s) => (typeof s === 'string' ? s : s.name));
  if (init.skills === undefined && !cmds.includes(SKILL_NAME)) p.push(`${SKILL_NAME} not loaded`);
  const leaked = cmds.filter((c) => c.includes(':') || userItems.has(c));
  if (leaked.length) p.push(`user or plugin commands/skills: ${leaked.join(', ')}`);
  if ((init.plugins || []).length) p.push(`plugins: ${init.plugins.map((x) => x.name || x).join(', ')}`);
  return p;
}

function runOne(s, model, rep) {
  const ctx = setup(`${s.id}-${model}-${rep}`);
  const t0 = Date.now();
  const prep = spawnSync(process.execPath, checkerArgs(['prepare', '--scenario', s.id, '--state', ctx.state]), { encoding: 'utf8' });
  if (prep.status !== 0) return { s, model, rep, pass: false, error: `prepare failed: ${prep.stderr.trim()}`, ctx };
  const prompt = JSON.parse(fs.readFileSync(ctx.state, 'utf8')).scenarios[s.id].prompt;
  const agent = spawnSync(opt.claude, claudeArgs(ctx, model, prompt), { cwd: ctx.project, encoding: 'utf8', timeout: RUN_TIMEOUT_MS, maxBuffer: 1 << 28 });
  fs.writeFileSync(ctx.transcript, agent.stdout || '');
  const msgs = (agent.stdout || '').split('\n').filter(Boolean).map((l) => { try { return JSON.parse(l); } catch (e) { return null; } }).filter(Boolean);
  const init = msgs.find((m) => m.type === 'system' && m.subtype === 'init') || {};
  const final = msgs.filter((m) => m.type === 'result').pop() || {};
  const isolation = isolationProblems(msgs.find((m) => m.type === 'system' && m.subtype === 'init'));
  const chk = spawnSync(process.execPath, checkerArgs(['check', '--state', ctx.state, ...(opt.keep ? [] : ['--close'])]), { encoding: 'utf8' });
  let checked = null;
  try { checked = JSON.parse(chk.stdout); } catch (e) { /* reported below */ }
  const r = {
    s, model, rep, ctx,
    invalid: isolation.length ? isolation.join('; ') : null,
    pass: !isolation.length && chk.status === 0 && checked && checked.pass,
    failed: checked ? checked.scenarios[0].checks.filter((c) => !c.pass).map((c) => `${c.type}: ${c.detail}`) : [],
    error: isolation.length ? `INVALID run (isolation): ${isolation.join('; ')}` : agent.error ? String(agent.error.message) : (chk.status === 2 ? `checker: ${chk.stderr.trim()}` : (final.is_error ? `agent: ${final.subtype}` : null)),
    turns: final.num_turns, cost: final.total_cost_usd, wallS: Math.round((Date.now() - t0) / 1000),
    tokens: final.usage ? (final.usage.input_tokens || 0) + (final.usage.cache_read_input_tokens || 0) + (final.usage.cache_creation_input_tokens || 0) + (final.usage.output_tokens || 0) : undefined,
  };
  if (!opt.keep && !r.error) fs.rmSync(ctx.dir, { recursive: true, force: true });
  return r;
}

// ------------------------------------------------------------------ dry run
if (opt.dry) {
  const s = scenarios[0];
  const ctx = setup(`dry-${s.id}`);
  console.log('DRY RUN — no agent run, no document created, no results written.\n');
  console.log(`Instance: ${url} (toolsVersion ${toolsVersion || '?'}, app ${appVersion || '?'}); skill version ${evals.version}`);
  console.log(`Runs: ${scenarios.length} scenario(s) × ${opt.models.length} model(s) [${opt.models.join(', ')}] × ${opt.reps} rep(s) = ${scenarios.length * opt.models.length * opt.reps}\n`);
  console.log(`Temporary directory of the first run (kept for inspection): ${ctx.dir}`);
  console.log(`  skill copy: ${ctx.skillDest} (${fs.readdirSync(ctx.skillDest).join(', ')})`);
  console.log(`  ${ctx.mcpConfig}:\n${fs.readFileSync(ctx.mcpConfig, 'utf8').replace(/^/gm, '    ')}`);
  for (const sc of scenarios) for (const model of opt.models) for (let rep = 1; rep <= opt.reps; rep++) {
    const tag = `${sc.id} / ${model} / ${rep}`;
    const dctx = sc === s && model === opt.models[0] && rep === 1 ? ctx : { dir: '<tmp>', project: '<tmp>/project', mcpConfig: '<tmp>/mcp.json', state: '<tmp>/state.json' };
    const prompt = sc.fixture ? `${sc.prompt}\n\nThe circuit is open in document <doc from prepare>.` : sc.prompt;
    console.log(`\n# ${tag}`);
    console.log(`1. ${[process.execPath, ...checkerArgs(['prepare', '--scenario', sc.id, '--state', dctx.state])].map(q).join(' ')}`);
    console.log(`2. (cwd ${dctx.project}) ${[opt.claude, ...claudeArgs(dctx, model, prompt)].map(q).join(' ')}`);
    console.log(`3. ${[process.execPath, ...checkerArgs(['check', '--state', dctx.state, ...(opt.keep ? [] : ['--close'])])].map(q).join(' ')}`);
  }
  console.log(`\nResults would be appended to ${opt.results}.`);
  process.exit(0);
}

// ------------------------------------------------------------------ all runs and results
const results = [];
for (const s of scenarios) for (const model of opt.models) for (let rep = 1; rep <= opt.reps; rep++) {
  process.stderr.write(`run ${s.id} / ${model} / ${rep} ... `);
  const r = runOne(s, model, rep);
  results.push(r);
  process.stderr.write(`${r.invalid ? 'INVALID' : r.pass ? 'PASS' : 'FAIL'}${r.error ? ` (${r.error}; kept ${r.ctx.dir})` : ''}\n`);
}
const date = new Date().toISOString().slice(0, 10);
const fmt = (v, d) => (typeof v === 'number' ? v.toFixed(d) : '—');
const lines = [`## Skill ${evals.version} — ${date}`, '',
  `toolsVersion ${toolsVersion || '?'}, app ${appVersion || '?'}; models ${opt.models.join(', ')}; ${opt.reps} rep(s) each.`, '',
  '| Scenario | Model | Passed | Rule (≥ 2 of 3) |', '|---|---|---|---|'];
// releasable only for a full set: every scenario of evals.json, >= 3 runs on each of >= 2 models
const fullSet = opt.models.length >= 2 && opt.reps >= 3 && evals.scenarios.every((s) => scenarios.includes(s));
let releasable = fullSet;
for (const s of scenarios) for (const model of opt.models) {
  const rs = results.filter((r) => r.s === s && r.model === model);
  const n = rs.filter((r) => r.pass).length, need = Math.ceil((2 / 3) * rs.length), inv = rs.filter((r) => r.invalid).length;
  if (n < need) releasable = false;
  lines.push(`| ${s.id} | ${model} | ${n}/${rs.length}${inv ? ` (${inv} invalid)` : ''} | ${n >= need ? 'pass' : 'FAIL'} |`);
}
lines.push('', `**Releasable:** ${releasable ? 'yes' : 'no'} (every scenario ≥ 2 of 3 on each of ≥ 2 models${fullSet ? '' : '; not a full set: needs all scenarios, ≥ 2 models and ≥ 3 reps'}).`, '',
  '| Scenario | Model | Run | Result | Turns | Tokens | Cost (USD) | Wall (s) | Failed checks / error |', '|---|---|---|---|---|---|---|---|---|');
for (const r of results) lines.push(`| ${r.s.id} | ${r.model} | ${r.rep} | ${r.invalid ? 'INVALID' : r.pass ? 'PASS' : 'FAIL'} | ${r.turns ?? '—'} | ${r.tokens ?? '—'} | ${fmt(r.cost, 3)} | ${r.wallS} | ${[r.error, ...r.failed].filter(Boolean).join('; ').replace(/\|/g, '\\|') || '—'} |`);
const header = '# Eval results\n\nRecorded per skill version by `evals/run.mjs` (SP_AGS_05_02). Newest first.\n\n';
const old = fs.existsSync(opt.results) ? fs.readFileSync(opt.results, 'utf8').replace(header, '') : '';
fs.writeFileSync(opt.results, header + lines.join('\n') + '\n\n' + old);
console.log(lines.join('\n'));
process.exit(releasable ? 0 : 1);
