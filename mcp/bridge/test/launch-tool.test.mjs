// [SP_MCB_02_02] bridge_launch through the stdio server: start a (fake) app, wait for its
// record, open a file with circuit_file; use the running instance instead of a second start.

import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { tempDir, makeRecord, writeRecord } from './helpers.mjs';
import { fakeAppExe, killRecorded } from './fake-app.mjs';
import { startFakeServer } from './fake-server.mjs';
import { startBridge, until } from './bridge-client.mjs';

test('bridge_launch with file: app started, target launched, file opened in a new active document', async (t) => {
  const registry = tempDir();
  const app = fakeAppExe(registry);
  t.after(() => killRecorded(registry));
  const b = await startBridge({ args: ['--app', app.exe], env: { CIRCUITJS_MCP_REGISTRY: registry } });
  try {
    assert.deepEqual((await b.client.listTools()).tools.length, 3);
    await assert.rejects(b.raw('tools/call', { name: 'bridge_launch', arguments: { file: 'circuits/rc.txt' } }), (e) => e.code === -32602 && /file must be an absolute path/.test(e.message));
    assert.equal(app.starts(), 0);
    const file = path.join(registry, 'circuits', 'rc.txt');
    const r = await b.client.callTool({ name: 'bridge_launch', arguments: { file } });
    assert.equal(r.isError, undefined);
    assert.equal(r.structuredContent.target.source, 'launched');
    assert.match(r.structuredContent.target.instanceId, /^\d+-\d+$/);
    assert.deepEqual(r.structuredContent.opened, { doc: 'D2' });
    assert.deepEqual(app.calls(), [{ action: 'open', path: file, into: 'new', activate: true }]);
    await until(() => b.notes.tools === 1);
    // A second bridge_launch uses the running instance: no second start (still one record).
    const again = await b.client.callTool({ name: 'bridge_launch', arguments: {} });
    assert.deepEqual(again.structuredContent.target, r.structuredContent.target);
    assert.equal(fs.readdirSync(registry).length, 1);
  } finally {
    await b.close();
  }
});

test('bridge_launch with a live instance: no start (the app path is not even needed)', async (t) => {
  const registry = tempDir();
  const srv = await startFakeServer({ instructions: 'live' });
  t.after(() => srv.close());
  const rec = makeRecord({ pid: process.pid, startedAtMs: Date.now(), urls: [srv.url] });
  writeRecord(registry, rec);
  const b = await startBridge({ args: ['--url', srv.url], env: { CIRCUITJS_MCP_REGISTRY: registry } });
  try {
    // The explicit target is replaced by the live registry instance.
    const r = await b.client.callTool({ name: 'bridge_launch', arguments: {} });
    assert.deepEqual(r.structuredContent, { target: { url: srv.url, instanceId: rec.instanceId, source: 'registry' } });
  } finally {
    await b.close();
  }
});

test('bridge_launch: open failure is an error result carrying the circuit_file text; the target stays', async (t) => {
  const registry = tempDir();
  const app = fakeAppExe(registry, { openError: true });
  t.after(() => killRecorded(registry));
  const b = await startBridge({ args: ['--app', app.exe], env: { CIRCUITJS_MCP_REGISTRY: registry } });
  try {
    const r = await b.client.callTool({ name: 'bridge_launch', arguments: { file: '/abs/missing.txt' } });
    assert.equal(r.isError, true);
    assert.equal(r.content.length, 1);
    assert.match(r.content[0].text, /^The instance \d+-\d+ is running and selected, but opening \/abs\/missing.txt failed: \{"ok":false,"error":\{"code":"file_not_found"\}\}$/);
    const i = await b.client.callTool({ name: 'bridge_instances', arguments: {} });
    assert.equal(i.structuredContent.target.source, 'launched');
  } finally {
    await b.close();
  }
});

test('bridge_launch timeout text; spawn failure text', async () => {
  const registry = tempDir();
  const dir = tempDir();
  const silent = path.join(dir, 'silent');
  fs.writeFileSync(silent, `#!${process.execPath}\nsetTimeout(() => {}, 1500);\n`, { mode: 0o755 });
  let b = await startBridge({ args: ['--app', silent, '--launch-timeout', '500'], env: { CIRCUITJS_MCP_REGISTRY: registry } });
  try {
    const r = await b.client.callTool({ name: 'bridge_launch', arguments: {} });
    assert.deepEqual(r.content, [{ type: 'text', text: 'The app started but no instance record appeared within 500 ms.' }]);
    assert.equal(r.isError, true);
  } finally {
    await b.close();
  }
  const noexec = path.join(dir, 'noexec');
  fs.writeFileSync(noexec, 'x', { mode: 0o644 });
  b = await startBridge({ args: ['--app', noexec], env: { CIRCUITJS_MCP_REGISTRY: registry } });
  try {
    const r = await b.client.callTool({ name: 'bridge_launch', arguments: {} });
    assert.equal(r.content[0].text, `Cannot start the app ${noexec}: EACCES.`);
  } finally {
    await b.close();
  }
});

test('--launch: the stdio handshake starts the app and forwards its instructions', async (t) => {
  const registry = tempDir();
  const app = fakeAppExe(registry);
  t.after(() => killRecorded(registry));
  const b = await startBridge({ args: ['--launch', '--app', app.exe], env: { CIRCUITJS_MCP_REGISTRY: registry } });
  try {
    assert.match(b.client.getInstructions(), /^Launched fake\. toolsVersion 1\.0\.\n\n/);
    const r = await b.client.callTool({ name: 'bridge_instances', arguments: {} });
    assert.equal(r.structuredContent.target.source, 'launched');
  } finally {
    await b.close();
  }
});

test('bridge_launch: a JSON-RPC error of circuit_file gives the same text with the error message', async (t) => {
  const registry = tempDir();
  const app = fakeAppExe(registry, { openRpcError: true });
  t.after(() => killRecorded(registry));
  const b = await startBridge({ args: ['--app', app.exe], env: { CIRCUITJS_MCP_REGISTRY: registry } });
  try {
    const r = await b.client.callTool({ name: 'bridge_launch', arguments: { file: '/abs/x.txt' } });
    assert.equal(r.isError, true);
    assert.match(r.content[0].text, /^The instance \d+-\d+ is running and selected, but opening \/abs\/x.txt failed: Invalid arguments for tool circuit_file: path$/);
  } finally {
    await b.close();
  }
});

test('concurrent bridge_launch calls share one start', async (t) => {
  const registry = tempDir();
  const app = fakeAppExe(registry, { registerAfterMs: 400 });
  t.after(() => killRecorded(registry));
  const b = await startBridge({ args: ['--app', app.exe], env: { CIRCUITJS_MCP_REGISTRY: registry } });
  try {
    const [a, c] = await Promise.all([
      b.client.callTool({ name: 'bridge_launch', arguments: {} }),
      b.client.callTool({ name: 'bridge_launch', arguments: {} }),
    ]);
    assert.equal(app.starts(), 1);
    assert.equal(a.structuredContent.target.source, 'launched');
    assert.deepEqual(c.structuredContent.target, a.structuredContent.target);
  } finally {
    await b.close();
  }
});
