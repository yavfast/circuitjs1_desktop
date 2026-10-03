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
//   --max-total-usd   stop before a run that could take the total over this amount (default none);
//                     each run is counted at its --max-budget-usd until its real cost is known
//   --results         results file (default: results.md next to this script)
//   --keep            keep each run's temporary directory (transcript, state, config)
//   --keep-docs       leave each run's documents open in the app (default: closed after the check,
//                     changes discarded, so the next run never sees them)
//   --accept-bundled  run with a CLI version that has no pinned bundle (see below)
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
//   - the stream-json `init` message is verified: tools within that set, only the circuitjs server
//     and it connected, circuitjs-circuits loaded, and every other skill, slash command and plugin in
//     the CLI's bundle pinned for that CLI version (BUNDLED below). The CLI always lists its own bundled
//     skills, commands and plugins, which cannot be turned off without turning off this skill; they are
//     the only exception. A run that fails this is INVALID (an infrastructure failure, recorded as
//     such), not a failed scenario. A CLI version without a pinned bundle is refused before any run
//     (re-pin from its first transcript, or pass --accept-bundled to waive the bundle check).
// Pass rule (SP_AGS_05_02): a scenario passes when all its checks pass; a skill version is releasable
// when every scenario of evals.json ran at least 3 times on each of at least two models and passed in
// at least 2 of 3 (two thirds) of its runs on each model; invalid runs count as not passed.

import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { execFileSync, spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const SKILL_DIR = path.resolve(HERE, '..');
const CHECK = path.join(HERE, 'check.mjs');
const RUN_TIMEOUT_MS = 20 * 60 * 1000;
const KNOWN_TOOLS = ['circuit_types', 'circuit_documents', 'circuit_import', 'circuit_edit', 'circuit_get', 'circuit_connectivity', 'circuit_read', 'circuit_render', 'circuit_sim', 'circuit_run', 'circuit_diagnostics', 'circuit_checkpoint', 'circuit_history', 'circuit_file'];
const DENIED_TOOLS = ['circuit_file']; // no file access in evals
const BUILTIN_TOOLS = ['Read', 'Skill', 'ListMcpResourcesTool', 'ReadMcpResourceTool'];
const SKILL_NAME = 'circuitjs-circuits';

// [SP_AGS_05_02] Bundled skills, slash commands and plugins of the Claude Code CLI, pinned per CLI
// version from a real init message (2026-10-03 smoke and eval runs; every run reported 2.1.288).
// Anything else in `skills`, `slash_commands` or `plugins`, except SKILL_NAME, is a leak. A CLI
// version without an entry is refused unless --accept-bundled is given (then any name the init lists
// counts as bundled, which re-pins nothing): re-pin from the new version's first transcript.
const BUNDLED = {
  '2.1.288': {
    skills: [
      "deep-research", "design", "slides", "design-sync", "dataviz", "artifact-diagramming", "artifact-capabilities", "update-config",
      "verify", "debug", "code-review", "simplify", "batch", "fewer-permission-prompts", "doctor", "loop",
      "schedule", "claude-api", "workflow-authoring", "run", "run-skill-generator", "plugin-authoring",
    ],
    slashCommands: [
      "deep-research", "design", "slides", "design-sync", "dataviz", "artifact-diagramming", "artifact-capabilities", "update-config",
      "verify", "debug", "code-review", "simplify", "batch", "fewer-permission-prompts", "doctor", "loop",
      "schedule", "claude-api", "workflow-authoring", "run", "run-skill-generator", "plugin-authoring", "advisor", "agents",
      "auto-mode-setup", "autocompact", "clear", "color", "compact", "config", "output-style", "context",
      "effort", "fast", "focus", "heapdump", "init", "mcp", "import", "model",
      "__remote-workflow", "workflow-launch-exec", "reload-plugins", "reload-skills", "rename", "security-review", "usage-credits", "extra-usage",
      "usage", "insights", "recap", "skill-doctor", "goal", "design-consent", "design-revoke", "list-agents",
      "team-onboarding",
    ],
    plugins: [
      "cc-plugin-sec-default", "cc-plugin-agents-md", "cc-plugin-telemetry", "cc-plugin-plugin-authoring",
    ],
  },
};

function usage(msg) { console.error(`run: ${msg}`); process.exit(2); }
const argv = process.argv.slice(2);
const opt = { models: ['haiku', 'sonnet'], reps: 3, scenarios: [], budget: '2', total: null, acceptBundled: false, claude: 'claude', results: path.join(HERE, 'results.md'), keep: false, keepDocs: false, dry: false, pass: [], bridge: process.env.CIRCUITJS_MCP_BIN || path.resolve(SKILL_DIR, '..', '..', 'bridge', 'bin', 'circuitjs-mcp.js') };
for (let i = 0; i < argv.length; i++) {
  const a = argv[i];
  const val = () => { if (i + 1 >= argv.length) usage(`${a} needs a value`); return argv[++i]; };
  if (a === '--models') opt.models = val().split(',').map((s) => s.trim()).filter(Boolean);
  else if (a === '--reps') opt.reps = Number(val());
  else if (a === '--scenario') opt.scenarios.push(val());
  else if (a === '--max-budget-usd') { const v = val(); if (v !== 'none' && !(Number(v) > 0)) usage(`--max-budget-usd needs a positive amount or "none" (got ${v})`); opt.budget = v === 'none' ? null : v; }
  else if (a === '--max-total-usd') { const v = Number(val()); if (!(v > 0)) usage('--max-total-usd needs a positive amount'); opt.total = v; }
  else if (a === '--accept-bundled') opt.acceptBundled = true;
  else if (a === '--claude') opt.claude = val();
  else if (a === '--results') opt.results = path.resolve(val());
  else if (a === '--keep') opt.keep = true;
  else if (a === '--keep-docs') opt.keepDocs = true;
  else if (a === '--dry-run') opt.dry = true;
  else if (a === '--bridge') opt.bridge = path.resolve(val());
  else if (['--url', '--instance', '--registry', '--timeout'].includes(a)) opt.pass.push(a, val());
  else if (a === '--help' || a === '-h') { console.log(fs.readFileSync(fileURLToPath(import.meta.url), 'utf8').split('\n').slice(1).filter((l, i, a) => a.slice(0, i + 1).every((x) => x.startsWith('//'))).map((l) => l.replace(/^\/\/ ?/, '')).join('\n')); process.exit(0); }
  else usage(`unknown argument ${a}`);
}
if (!Number.isInteger(opt.reps) || opt.reps < 1) usage('--reps must be a positive integer');
if (opt.total !== null && !opt.budget) usage('--max-total-usd needs a per-run cap: it cannot be combined with --max-budget-usd none');
const evals = JSON.parse(fs.readFileSync(path.join(HERE, 'evals.json'), 'utf8'));
// Hash of the skill text (SKILL.md and references): wording changes keep the version (SP_AGS_04_01)
// but are told apart in results.md
const skillHash = (() => {
  const h = createHash('sha256');
  const files = ['SKILL.md', ...fs.readdirSync(path.join(SKILL_DIR, 'reference')).sort().map((f) => path.join('reference', f))];
  for (const f of files) h.update(f + '\0' + fs.readFileSync(path.join(SKILL_DIR, f)));
  return h.digest('hex').slice(0, 8);
})();
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

// ------------------------------------------------------------------ CLI version against the pinned bundle
let cliVersion = null;
try { cliVersion = (execFileSync(opt.claude, ['--version'], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).match(/\d+\.\d+\.\d+/) || [])[0] || null; } catch (e) { /* reported below */ }
if (!Object.keys(BUNDLED).includes(cliVersion)) {
  const msg = `claude ${cliVersion || '(version unknown)'} has no pinned bundle (pinned: ${Object.keys(BUNDLED).join(', ')})`;
  if (opt.acceptBundled || opt.dry) console.error(`run: WARNING ${msg}${opt.acceptBundled ? '; --accept-bundled: the bundle check is waived' : ''}`);
  else usage(`${msg}: re-pin BUNDLED from a transcript of this version, or pass --accept-bundled`);
}

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

/** The pinned bundle of a CLI version, or null when it is not pinned. */
function bundleOf(version) { return BUNDLED[version] || null; }

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
  // Allowlist: the pinned bundle of this CLI version plus SKILL_NAME
  const names = (l) => (l || []).map((s) => (typeof s === 'string' ? s : s.name));
  const skills = names(init.skills), cmds = names(init.slash_commands), plugins = names(init.plugins);
  if (!skills.includes(SKILL_NAME)) p.push(`${SKILL_NAME} not loaded`);
  const bundle = bundleOf(init.claude_code_version);
  if (!bundle) {
    if (!opt.acceptBundled) p.push(`CLI version ${init.claude_code_version} has no pinned bundle (re-pin BUNDLED, or pass --accept-bundled)`);
    return p;
  }
  const outside = (list, pinned) => list.filter((n) => n !== SKILL_NAME && !pinned.includes(n));
  const xs = outside(skills, bundle.skills), xc = outside(cmds, [...bundle.skills, ...bundle.slashCommands]), xp = outside(plugins, bundle.plugins);
  if (xs.length) p.push(`skills outside the bundle: ${xs.join(', ')}`);
  if (xc.length) p.push(`commands outside the bundle: ${xc.join(', ')}`);
  if (xp.length) p.push(`plugins outside the bundle: ${xp.join(', ')}`);
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
  const chk = spawnSync(process.execPath, checkerArgs(['check', '--state', ctx.state, ...(opt.keepDocs ? [] : ['--close'])]), { encoding: 'utf8' });
  let checked = null;
  try { checked = JSON.parse(chk.stdout); } catch (e) { /* reported below */ }
  const r = {
    s, model, rep, ctx,
    invalid: isolation.length ? isolation.join('; ') : null,
    pass: !isolation.length && chk.status === 0 && checked && checked.pass,
    failed: checked ? checked.scenarios[0].checks.filter((c) => !c.pass).map((c) => `${c.type}: ${c.detail}`) : [],
    error: isolation.length ? `INVALID run (isolation): ${isolation.join('; ')}` : agent.error ? String(agent.error.message) : (chk.status === 2 ? `checker: ${chk.stderr.trim()}` : (final.is_error ? `agent: ${final.subtype}` : null)),
    skillUsed: msgs.some((m) => m.type === 'assistant' && ((m.message || {}).content || []).some((c) => c.type === 'tool_use' && c.name === 'Skill' && JSON.stringify(c.input || {}).includes(SKILL_NAME))),
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
    console.log(`3. ${[process.execPath, ...checkerArgs(['check', '--state', dctx.state, ...(opt.keepDocs ? [] : ['--close'])])].map(q).join(' ')}`);
  }
  console.log(`\nResults would be appended to ${opt.results}.`);
  process.exit(0);
}

// ------------------------------------------------------------------ all runs and results
const results = [];
let spent = 0, stopped = null;
for (const s of scenarios) for (const model of opt.models) for (let rep = 1; rep <= opt.reps; rep++) {
  if (stopped) continue;
  if (opt.total !== null && spent + Number(opt.budget || 0) > opt.total) { stopped = `cost ceiling: $${spent.toFixed(2)} spent, the next run could exceed $${opt.total}`; process.stderr.write(`STOP: ${stopped}\n`); continue; }
  process.stderr.write(`run ${s.id} / ${model} / ${rep} ... `);
  const r = runOne(s, model, rep);
  results.push(r);
  spent += typeof r.cost === 'number' ? r.cost : Number(opt.budget || 0);
  process.stderr.write(`${r.invalid ? 'INVALID' : r.pass ? 'PASS' : 'FAIL'}${r.error ? ` (${r.error}; kept ${r.ctx.dir})` : ''}\n`);
}
const date = new Date().toISOString().slice(0, 10);
const fmt = (v, d) => (typeof v === 'number' ? v.toFixed(d) : '—');
const lines = [`## Skill ${evals.version} (text ${skillHash}) — ${date}`, '',
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
if (stopped) releasable = false;
lines.push('', `Total cost: $${spent.toFixed(2)} over ${results.length} run(s)${stopped ? `; STOPPED (${stopped})` : ''}.`);
lines.push('', `**Releasable:** ${releasable ? 'yes' : 'no'} (every scenario ≥ 2 of 3 on each of ≥ 2 models${fullSet ? '' : '; not a full set: needs all scenarios, ≥ 2 models and ≥ 3 reps'}).`, '',
  '| Scenario | Model | Run | Result | Skill used | Turns | Tokens | Cost (USD) | Wall (s) | Failed checks / error |', '|---|---|---|---|---|---|---|---|---|---|');
for (const r of results) lines.push(`| ${r.s.id} | ${r.model} | ${r.rep} | ${r.invalid ? 'INVALID' : r.pass ? 'PASS' : 'FAIL'} | ${r.skillUsed ? 'yes' : 'no'} | ${r.turns ?? '—'} | ${r.tokens ?? '—'} | ${fmt(r.cost, 3)} | ${r.wallS} | ${[r.error, ...r.failed].filter(Boolean).join('; ').replace(/\|/g, '\\|') || '—'} |`);
const header = '# Eval results\n\nRecorded per skill version by `evals/run.mjs` (SP_AGS_05_02). Newest first.\n\n';
const old = fs.existsSync(opt.results) ? fs.readFileSync(opt.results, 'utf8').replace(header, '') : '';
fs.writeFileSync(opt.results, header + lines.join('\n') + '\n\n' + old);
console.log(lines.join('\n'));
process.exit(releasable ? 0 : 1);
