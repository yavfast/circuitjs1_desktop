// [SP_MCB_02_03] [SP_MCB_01_04] CLI subcommands: the real program as a child process against fake
// app endpoints; stdout JSON, stderr text, exit codes.

import test from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { tempDir, makeRecord, writeRecord, listDir } from './helpers.mjs';
import { startFakeServer } from './fake-server.mjs';
import { fakeAppExe, killRecorded } from './fake-app.mjs';
import { BIN } from './bridge-client.mjs';
import { Readable } from 'node:stream';
import { runCli } from '../src/cli.js';
import { parseArgs } from '../src/options.js';

/** Runs the program; resolves {code, stdout, stderr}. */
function run(args, { registry, env = {}, stdin = null, cwd } = {}) {
  const e = { ...process.env };
  for (const k of Object.keys(e)) if (k.startsWith('CIRCUITJS_')) delete e[k];
  Object.assign(e, env);
  if (registry) e.CIRCUITJS_MCP_REGISTRY = registry;
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [BIN, ...args], { env: e, cwd, stdio: ['pipe', 'pipe', 'pipe'] });
    let stdout = '';
    let stderr = '';
    child.stdout.setEncoding('utf8').on('data', (d) => (stdout += d));
    child.stderr.setEncoding('utf8').on('data', (d) => (stderr += d));
    child.on('error', reject);
    child.on('close', (code) => resolve({ code, stdout, stderr }));
    if (stdin !== null) child.stdin.end(stdin);
    else child.stdin.end();
  });
}

/** stdout is exactly one JSON document and a newline; @returns it parsed */
function json(r) {
  assert.ok(r.stdout.endsWith('\n'), 'newline at the end');
  assert.equal(r.stdout.indexOf('\n'), r.stdout.length - 1, 'one line');
  return JSON.parse(r.stdout);
}

const APP_TOOLS = [
  { name: 'circuit_types', title: 'Types', inputSchema: { type: 'object' }, annotations: { readOnlyHint: true, idempotentHint: true } },
  { name: 'circuit_edit', inputSchema: { type: 'object' }, annotations: { title: 'Edit', readOnlyHint: false } },
];

let stamp = Date.now() - 100000;
async function fakeApp(registry, o = {}) {
  const srv = await startFakeServer({ tools: APP_TOOLS, ...o });
  const rec = makeRecord({ pid: process.pid, startedAtMs: (stamp += 1000), urls: [srv.url] });
  const file = path.join(registry, writeRecord(registry, rec));
  return { srv, rec, close: async () => { fs.rmSync(file, { force: true }); await srv.close(); } };
}

const appHandle = (m) => {
  if (m.method !== 'tools/call') return undefined;
  const { name } = m.params;
  if (name === 'circuit_edit') {
    const sc = { ok: false, error: { code: 'not_found', message: 'No element X9' }, issues: [{ severity: 'error', code: 'not_found' }] };
    return { content: [{ type: 'text', text: JSON.stringify(sc) }], structuredContent: sc, isError: true };
  }
  if (name === 'circuit_plain') return { content: [{ type: 'text', text: 'plain' }] };
  if (name === 'circuit_bad') return { error: { code: -32602, message: 'Invalid arguments for tool circuit_bad: x' } };
  if (name === 'circuit_crash') return { error: { code: -32603, message: 'Internal failure' } };
  if (name === 'circuit_slow') return new Promise((r) => setTimeout(() => r({ content: [] }), 1500));
  if (name === 'circuit_die') return { destroy: true };
  if (name === 'circuit_invalid') return { error: { code: -32600, message: 'Invalid request' } };
  if (name === 'circuit_nomethod') return { error: { code: -32601, message: 'Method not found' } };
  return undefined; // echo
};

test('CLI call: valid → exit 0, stdout = structuredContent', async (t) => {
  const registry = tempDir();
  const app = await fakeApp(registry, { handle: appHandle });
  t.after(() => app.close());
  const r = await run(['call', 'circuit_types', '{"type":"Resistor"}'], { registry });
  assert.equal(r.code, 0, r.stderr);
  assert.deepEqual(json(r), { ok: true, data: { name: 'circuit_types', arguments: { type: 'Resistor' } } });
  assert.equal(r.stderr, '');
  // No arguments: {}.
  assert.deepEqual(json(await run(['call', 'circuit_types'], { registry })).data.arguments, {});
});

test('CLI call: domain error → exit 1, issues on stdout; no structuredContent → {content}', async (t) => {
  const registry = tempDir();
  const app = await fakeApp(registry, { handle: appHandle });
  t.after(() => app.close());
  let r = await run(['call', 'circuit_edit', '{"edits":[{"op":"delete","id":"X9"}]}'], { registry });
  assert.equal(r.code, 1);
  assert.equal(json(r).issues[0].code, 'not_found');
  assert.match(r.stderr, /circuit_edit returned an error result/);
  r = await run(['call', 'circuit_plain'], { registry });
  assert.equal(r.code, 0);
  assert.deepEqual(json(r), { content: [{ type: 'text', text: 'plain' }] });
});

test('CLI call: bad JSON, non-object arguments, missing tool → exit 2, nothing on stdout', async () => {
  for (const args of [['call', 'circuit_get', '{'], ['call', 'circuit_get', '[1]'], ['call', 'circuit_get', '"x"'], ['call'], ['call', 'a', '{}', 'extra'], ['frob'], ['read']]) {
    const r = await run(args, { registry: tempDir() });
    assert.equal(r.code, 2, args.join(' '));
    assert.equal(r.stdout, '');
    assert.notEqual(r.stderr, '');
  }
});

test('CLI call: JSON-RPC errors of the target → 2 (invalid request) or 3 (failure); timeout → 3', async (t) => {
  const registry = tempDir();
  const app = await fakeApp(registry, { handle: appHandle });
  t.after(() => app.close());
  let r = await run(['call', 'circuit_bad', '{}'], { registry });
  assert.equal(r.code, 2);
  assert.equal(r.stderr, 'circuitjs-mcp: circuit_bad: Invalid arguments for tool circuit_bad: x (JSON-RPC -32602)\n');
  r = await run(['call', 'circuit_crash'], { registry });
  assert.equal(r.code, 3);
  r = await run(['call', 'circuit_slow', '--timeout', '200'], { registry });
  assert.equal(r.code, 3);
  assert.equal(r.stderr, 'circuitjs-mcp: Timed out after 200 ms\n');
  assert.equal(r.stdout, '');
});

test('SP_MCB_05_04 arguments from stdin: a large circuit, same as inline', async (t) => {
  const registry = tempDir();
  const app = await fakeApp(registry, { handle: appHandle });
  t.after(() => app.close());
  const circuit = '$ 1 0.000005 10.2 50 5 50 5e-11\n' + 'r 0 0 64 0 0 1000\n'.repeat(60000); // ~1.2 MB
  const args = JSON.stringify({ circuit });
  const viaStdin = await run(['call', 'circuit_import', '-'], { registry, stdin: args });
  assert.equal(viaStdin.code, 0, viaStdin.stderr);
  assert.equal(json(viaStdin).data.arguments.circuit, circuit);
  const inline = await run(['call', 'circuit_import', JSON.stringify({ circuit: 'r 0 0 64 0 0 1000\n' })], { registry });
  assert.equal(inline.code, 0);
  const smallStdin = await run(['call', 'circuit_import', '-'], { registry, stdin: JSON.stringify({ circuit: 'r 0 0 64 0 0 1000\n' }) });
  assert.equal(smallStdin.stdout, inline.stdout);
  const bad = await run(['call', 'circuit_import', '-'], { registry, stdin: '{"circuit":' });
  assert.equal(bad.code, 2);
  assert.match(bad.stderr, /arguments on stdin are not valid JSON/);
});

test('CLI tools: no instance → 3; with an app → {name, title, annotations}[] over all pages', async (t) => {
  let r = await run(['tools'], { registry: tempDir() });
  assert.equal(r.code, 3);
  assert.equal(r.stdout, '');
  assert.match(r.stderr, /No CircuitJS1 instance/);
  const registry = tempDir();
  const app = await fakeApp(registry, {
    handle: (m) => {
      if (m.method !== 'tools/list') return undefined;
      return m.params && m.params.cursor === 'p2' ? { tools: [APP_TOOLS[1]] } : { tools: [APP_TOOLS[0]], nextCursor: 'p2' };
    },
  });
  t.after(() => app.close());
  r = await run(['tools'], { registry });
  assert.equal(r.code, 0, r.stderr);
  assert.deepEqual(json(r), [
    { name: 'circuit_types', title: 'Types', annotations: { readOnlyHint: true, idempotentHint: true } },
    { name: 'circuit_edit', title: 'Edit', annotations: { title: 'Edit', readOnlyHint: false } },
  ]);
});

test('SP_MCB_05_01 CLI instances: two live, one stale → 2 entries, stale deleted', async (t) => {
  const registry = tempDir();
  const a = await fakeApp(registry);
  const b = await fakeApp(registry);
  t.after(async () => { await a.close(); await b.close(); });
  const dead = makeRecord({ pid: 2 ** 22 + 12345, startedAtMs: stamp + 5000 });
  writeRecord(registry, dead);
  const r = await run(['instances'], { registry });
  assert.equal(r.code, 0, r.stderr);
  const list = json(r);
  assert.deepEqual(list.map((i) => [i.instanceId, i.selected]), [[a.rec.instanceId, false], [b.rec.instanceId, true]]);
  assert.ok(!listDir(registry).includes(`${dead.instanceId}.json`));
  // --instance marks the named one.
  const named = json(await run(['instances', '--instance', a.rec.instanceId], { registry }));
  assert.deepEqual(named.map((i) => i.selected), [true, false]);
  assert.deepEqual(json(await run(['instances'], { registry: tempDir() })), []);
});

test('CLI read: catalogue → contents; unknown URI → 2; --url unreachable → 3', async (t) => {
  const registry = tempDir();
  const app = await fakeApp(registry);
  t.after(() => app.close());
  let r = await run(['read', 'circuitjs://catalogue'], { registry });
  assert.equal(r.code, 0, r.stderr);
  assert.deepEqual(json(r), [{ uri: 'circuitjs://catalogue', mimeType: 'application/json', text: '{"types":[]}' }]);
  r = await run(['read', 'circuitjs://nope'], { registry });
  assert.equal(r.code, 2);
  assert.match(r.stderr, /Resource not found: circuitjs:\/\/nope \(JSON-RPC -32002\)/);
  r = await run(['read', 'circuitjs://catalogue', '--url', 'http://127.0.0.1:9/mcp'], { registry });
  assert.equal(r.code, 3);
  assert.equal(r.stderr, `circuitjs-mcp: Cannot reach http://127.0.0.1:9/mcp: bad port. Live instances: ${app.rec.instanceId}\n`);
});

test('CLI call of a bridge tool works in-process', async (t) => {
  const registry = tempDir();
  const app = await fakeApp(registry);
  t.after(() => app.close());
  const r = await run(['call', 'bridge_instances'], { registry });
  assert.equal(r.code, 0, r.stderr);
  assert.equal(json(r).instances.length, 1);
});

test('SP_MCB_05_01 CLI launch: with file (relative to the cwd) → Target and {doc}; no app → 3', async (t) => {
  const registry = tempDir();
  const app = fakeAppExe(registry);
  t.after(() => killRecorded(registry));
  const cwd = tempDir();
  const r = await run(['launch', 'rc.txt', '--app', app.exe], { registry, cwd });
  assert.equal(r.code, 0, r.stderr);
  const out = json(r);
  assert.equal(out.target.source, 'launched');
  assert.deepEqual(out.opened, { doc: 'D2' });
  assert.deepEqual(app.calls(), [{ action: 'open', path: path.join(fs.realpathSync(cwd), 'rc.txt'), into: 'new', activate: true }]);
  // The launched app outlives the CLI; a second launch uses it.
  const again = await run(['launch', '--app', app.exe], { registry });
  assert.equal(again.code, 0);
  assert.deepEqual(json(again).target.instanceId, out.target.instanceId);
  assert.equal(app.starts(), 1);
  const none = await run(['launch'], { registry: tempDir() });
  assert.equal(none.code, 3);
  assert.equal(none.stderr, 'circuitjs-mcp: No app executable configured. Set --app or CIRCUITJS_APP.\n');
});

test('CLI launch: the app rejects the file → exit 1, its result on stdout, the message on stderr', async (t) => {
  const registry = tempDir();
  const app = fakeAppExe(registry, { openError: true });
  t.after(() => killRecorded(registry));
  const r = await run(['launch', '/abs/missing.txt', '--app', app.exe], { registry });
  assert.equal(r.code, 1);
  assert.deepEqual(json(r), { ok: false, error: { code: 'file_not_found' } });
  assert.match(r.stderr, /is running and selected, but opening \/abs\/missing.txt failed: /);
});

test('call with --launch starts the app first', async (t) => {
  const registry = tempDir();
  const app = fakeAppExe(registry);
  t.after(() => killRecorded(registry));
  const r = await run(['call', 'circuit_types', '{}', '--launch', '--app', app.exe], { registry });
  assert.equal(r.code, 0, r.stderr);
  assert.equal(json(r).data.name, 'circuit_types');
  assert.equal(app.starts(), 1);
});

/** stderr is exactly one line */
function oneLine(r) {
  assert.ok(r.stderr.endsWith('\n'), r.stderr);
  assert.equal(r.stderr.indexOf('\n'), r.stderr.length - 1, r.stderr);
}

test('exit 2/3 write one stderr line and nothing on stdout (option errors included)', async () => {
  for (const args of [['--bogus'], ['tools', '--timeout', 'x'], ['frob'], ['call', 'circuit_get', '{'], ['tools']]) {
    const r = await run(args, { registry: tempDir() });
    assert.ok(r.code === 2 || r.code === 3, args.join(' '));
    assert.equal(r.stdout, '');
    oneLine(r);
  }
  const r = await run(['--bogus']);
  assert.equal(r.stderr, 'circuitjs-mcp: Unknown option --bogus; run "circuitjs-mcp --help" for usage\n');
});

test('connection lost mid-call → exit 3 "Instance gone"; -32600 and -32601 → exit 2', async (t) => {
  const registry = tempDir();
  const app = await fakeApp(registry, { handle: appHandle });
  t.after(() => app.close());
  let r = await run(['call', 'circuit_die'], { registry });
  assert.equal(r.code, 3);
  assert.equal(r.stdout, '');
  assert.equal(r.stderr, `circuitjs-mcp: Instance gone: ${app.srv.url}\n`);
  r = await run(['call', 'circuit_invalid'], { registry });
  assert.equal(r.code, 2);
  assert.equal(r.stderr, 'circuitjs-mcp: circuit_invalid: Invalid request (JSON-RPC -32600)\n');
  r = await run(['call', 'circuit_nomethod'], { registry });
  assert.equal(r.code, 2);
  oneLine(r);
});

test('a multi-MB structuredContent arrives complete', async (t) => {
  const registry = tempDir();
  const app = await fakeApp(registry, { handle: appHandle });
  t.after(() => app.close());
  const blob = 'x'.repeat(4 * 1024 * 1024) + 'END';
  const r = await run(['call', 'circuit_types', '-'], { registry, stdin: JSON.stringify({ blob }) });
  assert.equal(r.code, 0, r.stderr);
  const got = json(r).data.arguments.blob;
  assert.equal(got.length, blob.length);
  assert.equal(got, blob);
});

test('call bridge_launch with a relative file → exit 2, the tool named once', async () => {
  const r = await run(['call', 'bridge_launch', '{"file":"rc.txt"}'], { registry: tempDir() });
  assert.equal(r.code, 2);
  assert.equal(r.stderr, 'circuitjs-mcp: bridge_launch: file must be an absolute path (JSON-RPC -32602)\n');
});

test('read: a result without a contents array → exit 3', async (t) => {
  const registry = tempDir();
  const app = await fakeApp(registry, { handle: (m) => (m.method === 'resources/read' ? { other: 1 } : undefined) });
  t.after(() => app.close());
  const r = await run(['read', 'circuitjs://x'], { registry });
  assert.equal(r.code, 3);
  assert.equal(r.stdout, '');
  assert.equal(r.stderr, 'circuitjs-mcp: circuitjs://x: malformed resources/read result\n');
});

test('tools paging: a repeated cursor or an endless chain → exit 3', async (t) => {
  const registry = tempDir();
  let n = 0;
  const loop = await fakeApp(registry, { handle: (m) => (m.method === 'tools/list' ? { tools: [], nextCursor: 'same' } : undefined) });
  t.after(() => loop.close());
  let r = await run(['tools'], { registry });
  assert.equal(r.code, 3);
  assert.equal(r.stderr, 'circuitjs-mcp: tools/list repeated the cursor "same"\n');
  const registry2 = tempDir();
  const endless = await fakeApp(registry2, { handle: (m) => (m.method === 'tools/list' ? { tools: [], nextCursor: `c${++n}` } : undefined) });
  t.after(() => endless.close());
  r = await run(['tools'], { registry: registry2 });
  assert.equal(r.code, 3);
  assert.equal(r.stderr, 'circuitjs-mcp: tools/list did not end within 100 pages\n');
  assert.equal(endless.srv.requests.filter((m) => m.method === 'tools/list').length, 100);
});

test('arguments from an interactive stdin: a hint on stderr first', async (t) => {
  const registry = tempDir();
  const app = await fakeApp(registry, { handle: appHandle });
  t.after(() => app.close());
  const stdin = Readable.from(['{"type":"Resistor"}']);
  stdin.isTTY = true;
  stdin.setEncoding = () => stdin;
  let out = '';
  let err = '';
  const code = await runCli(parseArgs(['call', 'circuit_types', '-', '--registry', registry], {}), {
    stdin,
    writeOut: (x) => (out += x),
    writeErr: (x) => (err += x),
  });
  assert.equal(code, 0);
  assert.equal(err, 'circuitjs-mcp: reading arguments from stdin (end with Ctrl-D)\n');
  assert.deepEqual(JSON.parse(out).data.arguments, { type: 'Resistor' });
});
