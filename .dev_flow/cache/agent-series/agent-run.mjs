// Scratch driver: one isolated `claude -p` agent run against the running app's MCP server.
// Usage: node agent-run.mjs <id> <prompt-file> [--model sonnet] [--budget 4]
// Isolation mirrors mcp/skill/circuitjs-circuits/evals/run.mjs: temp project with the skill only,
// strict MCP config (circuitjs http), tools Read/Skill/MCP resources + circuit_* (no circuit_file).
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { spawn } from 'node:child_process';

const REPO = new URL('../../..', import.meta.url).pathname.replace(/\/$/, '');
const SKILL = path.join(REPO, 'mcp/skill/circuitjs-circuits');
const [id, promptFile, ...rest] = process.argv.slice(2);
const opt = (k, d) => { const i = rest.indexOf(k); return i >= 0 ? rest[i + 1] : d; };
const model = opt('--model', 'sonnet'), budget = opt('--budget', '4');
const HERE = path.dirname(new URL(import.meta.url).pathname);
const dir = path.join(process.env.AGT_RUNS || path.join(os.tmpdir(), 'circuitjs-agent-series'), id);
fs.rmSync(dir, { recursive: true, force: true });
const project = path.join(dir, 'project');
fs.mkdirSync(path.join(project, '.claude', 'skills'), { recursive: true });
fs.cpSync(SKILL, path.join(project, '.claude', 'skills', 'circuitjs-circuits'), { recursive: true, filter: (s) => !path.relative(SKILL, s).split(path.sep).includes('evals') });
const mcpConfig = path.join(dir, 'mcp.json');
fs.writeFileSync(mcpConfig, JSON.stringify({ mcpServers: { circuitjs: { type: 'http', url: process.env.CJS_URL || 'http://127.0.0.1:7311/mcp' } } }));
const TOOLS = ['circuit_types', 'circuit_documents', 'circuit_import', 'circuit_edit', 'circuit_get', 'circuit_connectivity', 'circuit_read', 'circuit_render', 'circuit_sim', 'circuit_run', 'circuit_diagnostics', 'circuit_checkpoint', 'circuit_history'];
const BUILTIN = ['Read', 'Skill', 'ListMcpResourcesTool', 'ReadMcpResourceTool'];
const prompt = fs.readFileSync(promptFile, 'utf8');
fs.writeFileSync(path.join(dir, 'prompt.txt'), prompt);
const args = ['-p', prompt, '--model', model, '--output-format', 'stream-json', '--verbose',
  '--setting-sources', 'project', '--no-session-persistence', '--strict-mcp-config', '--mcp-config', mcpConfig,
  '--tools', BUILTIN.join(','), '--allowedTools', ...BUILTIN, ...TOOLS.map((t) => `mcp__circuitjs__${t}`),
  '--disallowedTools', 'mcp__circuitjs__circuit_file', '--permission-mode', 'dontAsk', '--max-budget-usd', budget];
const tr = fs.createWriteStream(path.join(dir, 'transcript.jsonl'));
const t0 = Date.now();
const child = spawn('claude', args, { cwd: project, stdio: ['ignore', 'pipe', 'pipe'] });
child.stdout.pipe(tr);
let err = ''; child.stderr.on('data', (d) => { err += d; });
const timer = setTimeout(() => child.kill('SIGTERM'), 40 * 60 * 1000);
child.on('close', (code) => {
  clearTimeout(timer);
  tr.end(() => {
    const lines = fs.readFileSync(path.join(dir, 'transcript.jsonl'), 'utf8').split('\n').filter(Boolean).map((l) => { try { return JSON.parse(l); } catch { return null; } }).filter(Boolean);
    const init = lines.find((l) => l.type === 'system' && l.subtype === 'init');
    const res = lines.filter((l) => l.type === 'result').pop();
    const calls = {}; const docs = new Set(); const toolErrors = [];
    const pending = {};
    for (const l of lines) {
      if (l.type === 'assistant') for (const c of l.message.content || []) if (c.type === 'tool_use') { const n = c.name.replace('mcp__circuitjs__', ''); calls[n] = (calls[n] || 0) + 1; pending[c.id] = { n, input: c.input }; }
      if (l.type === 'user') for (const c of (l.message.content || [])) if (c.type === 'tool_result') {
        const p = pending[c.tool_use_id]; const txt = Array.isArray(c.content) ? c.content.map((x) => x.text || '').join('') : String(c.content || '');
        if (p && p.n === 'circuit_documents' && p.input.action === 'create') { const m = txt.match(/"doc":"(d\d+)"/); if (m) docs.add(m[1]); }
        if (c.is_error) toolErrors.push(`${p ? p.n : '?'}: ${txt.slice(0, 200)}`);
      }
    }
    const summary = { id, model, exit: code, wallS: Math.round((Date.now() - t0) / 1000), costUsd: res && res.total_cost_usd, turns: res && res.num_turns, resultSubtype: res && res.subtype,
      mcp: init && (init.mcp_servers || []).map((m) => `${m.name}:${m.status}`), skillLoaded: !!(init && (init.skills || []).includes('circuitjs-circuits')), calls, docs: [...docs], toolErrors: toolErrors.slice(0, 20), stderr: err.slice(-500) };
    fs.writeFileSync(path.join(dir, 'summary.json'), JSON.stringify(summary, null, 1));
    fs.writeFileSync(path.join(dir, 'answer.md'), (res && res.result) || '(no result)');
    console.log(JSON.stringify(summary, null, 1));
    console.log('--- answer ---\n' + ((res && res.result) || '(no result)'));
  });
});
