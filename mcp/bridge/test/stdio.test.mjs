// [SP_MCB_02_01] [SP_MCB_02_02] [SP_MCB_04_01] The stdio server mode end to end: the real
// program as a child process, fake app endpoints behind a temporary registry.

import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js';
import * as z from 'zod';
import { BRIDGE_SENTENCE, BRIDGE_TOOLS } from '../src/bridge-tools.js';
import { NO_INSTANCE_TEXT } from '../src/forward.js';
import { tempDir, makeRecord, writeRecord } from './helpers.mjs';
import { startFakeServer } from './fake-server.mjs';
import { startBridge, until } from './bridge-client.mjs';

const BRIDGE_NAMES = BRIDGE_TOOLS.map((t) => t.name);
const APP_TOOLS = [
  { name: 'circuit_types', title: 'Types', inputSchema: { type: 'object', properties: { type: { type: 'string' } } }, annotations: { readOnlyHint: true } },
  { name: 'circuit_get', title: 'Get', inputSchema: { type: 'object' } },
];

let startedAt = Date.now() - 100000;
/** A fake app: endpoint + registry record (pid = this test process, so it is alive). */
async function fakeApp(registry, o = {}) {
  const srv = await startFakeServer({ tools: APP_TOOLS, instructions: o.instructions || 'Fake CircuitJS1. toolsVersion 1.0.', ...o });
  const rec = makeRecord({ pid: process.pid, startedAtMs: (startedAt += 1000), urls: [srv.url], title: o.title || 'fake' });
  const file = path.join(registry, writeRecord(registry, rec));
  return { srv, rec, file, close: async () => { fs.rmSync(file, { force: true }); await srv.close(); } };
}

function text(result) {
  return result.content.map((p) => p.text).join('\n');
}

test('no app, no --launch: handshake ok, only bridge tools, target tools say "No CircuitJS1 instance"', async () => {
  const registry = tempDir();
  const b = await startBridge({ env: { CIRCUITJS_MCP_REGISTRY: registry } });
  try {
    assert.deepEqual(b.client.getServerVersion().name, 'circuitjs-mcp');
    assert.equal(b.client.getInstructions(), BRIDGE_SENTENCE);
    const caps = b.client.getServerCapabilities();
    assert.equal(caps.tools.listChanged, true);
    assert.equal(caps.resources.listChanged, true);
    assert.deepEqual((await b.client.listTools()).tools.map((t) => t.name), BRIDGE_NAMES);
    const r = await b.raw('tools/call', { name: 'circuit_get', arguments: {} });
    assert.deepEqual(r, { content: [{ type: 'text', text: NO_INSTANCE_TEXT }], isError: true });
    assert.deepEqual((await b.client.listResources()).resources, []);
    assert.deepEqual((await b.client.listResourceTemplates()).resourceTemplates, []);
    await assert.rejects(b.client.readResource({ uri: 'circuitjs://catalogue' }), (e) => e.code === -32002);
    assert.match(b.stderr(), /no CircuitJS1 instance yet/);
    assert.deepEqual(b.errors, []);
  } finally {
    await b.close();
  }
});

test('with an app: instructions, tools = app tools + bridge tools, transparent calls, resources, errors', async () => {
  const registry = tempDir();
  const app = await fakeApp(registry, {
    handle: (m) => {
      if (m.method === 'tools/call' && m.params.name === 'circuit_bad') return { error: { code: -32602, message: 'Unknown argument "zz" for circuit_bad', data: { arg: 'zz' } } };
      return undefined;
    },
  });
  const b = await startBridge({ env: { CIRCUITJS_MCP_REGISTRY: registry } });
  try {
    assert.equal(b.client.getInstructions(), `Fake CircuitJS1. toolsVersion 1.0.\n\n${BRIDGE_SENTENCE}`);
    const tools = (await b.raw('tools/list')).tools;
    assert.deepEqual(tools.slice(0, 2), APP_TOOLS); // descriptors unchanged
    assert.deepEqual(tools.map((t) => t.name), ['circuit_types', 'circuit_get', ...BRIDGE_NAMES]);
    // The same call directly and through the bridge.
    const direct = new Client({ name: 'direct', version: '0' });
    await direct.connect(new StreamableHTTPClientTransport(new URL(app.srv.url)));
    const params = { name: 'circuit_types', arguments: { type: 'Resistor' } };
    const viaDirect = await direct.request({ method: 'tools/call', params }, z.looseObject({}));
    await direct.close();
    assert.deepEqual(await b.raw('tools/call', params), viaDirect);
    const read = await b.client.readResource({ uri: 'circuitjs://catalogue' });
    assert.deepEqual(read.contents, [{ uri: 'circuitjs://catalogue', mimeType: 'application/json', text: '{"types":[]}' }]);
    await assert.rejects(b.client.readResource({ uri: 'circuitjs://nope' }), (e) => e.code === -32002 && /Resource not found: circuitjs:\/\/nope/.test(e.message));
    await assert.rejects(b.raw('tools/call', { name: 'circuit_bad', arguments: {} }), (e) => {
      assert.equal(e.code, -32602);
      assert.equal(e.message, 'MCP error -32602: Unknown argument "zz" for circuit_bad');
      assert.deepEqual(e.data, { arg: 'zz' });
      return true;
    });
    assert.deepEqual(b.errors, []);
  } finally {
    await b.close();
    await app.close();
  }
});

test('the target revision is pinned from the record', async () => {
  const registry = tempDir();
  const app = await fakeApp(registry, { revisions: ['2025-06-18'] });
  fs.writeFileSync(app.file, JSON.stringify({ ...app.rec, protocolRevisions: ['2025-06-18'] }));
  const b = await startBridge({ env: { CIRCUITJS_MCP_REGISTRY: registry } });
  try {
    await b.client.listTools();
    const inits = app.srv.requests.filter((m) => m.method === 'initialize');
    assert.ok(inits.length >= 1);
    for (const m of inits) assert.equal(m.params.protocolVersion, '2025-06-18');
  } finally {
    await b.close();
    await app.close();
  }
});

test('bridge_instances and bridge_select: switch, list_changed, errors', async () => {
  const registry = tempDir();
  const older = await fakeApp(registry, { title: 'older' });
  const newer = await fakeApp(registry, { title: 'newer' });
  const b = await startBridge({ env: { CIRCUITJS_MCP_REGISTRY: registry } });
  try {
    let r = await b.client.callTool({ name: 'bridge_instances', arguments: {} });
    assert.deepEqual(r.structuredContent.instances.map((i) => [i.instanceId, i.title, i.selected]), [
      [older.rec.instanceId, 'older', false],
      [newer.rec.instanceId, 'newer', true],
    ]);
    assert.deepEqual(r.structuredContent.target, { url: newer.srv.url, instanceId: newer.rec.instanceId, source: 'registry' });

    r = await b.client.callTool({ name: 'bridge_select', arguments: { instanceId: older.rec.instanceId } });
    assert.deepEqual(r.structuredContent, { target: { url: older.srv.url, instanceId: older.rec.instanceId, source: 'registry' } });
    await until(() => b.notes.tools === 1 && b.notes.resources === 1);
    const before = older.srv.requests.length;
    await b.raw('tools/call', { name: 'circuit_get', arguments: {} });
    assert.ok(older.srv.requests.slice(before).some((m) => m.method === 'tools/call'));
    // Selecting the current target again: idempotent, no notification.
    await b.client.callTool({ name: 'bridge_select', arguments: { instanceId: older.rec.instanceId } });

    r = await b.raw('tools/call', { name: 'bridge_select', arguments: { instanceId: 'x' } });
    assert.equal(r.isError, true);
    assert.equal(text(r), `Unknown instance x. Live instances: ${older.rec.instanceId}, ${newer.rec.instanceId}`);
    r = await b.raw('tools/call', { name: 'bridge_select', arguments: { url: 'http://127.0.0.1:9/mcp' } });
    assert.equal(r.isError, true);
    assert.equal(text(r), `Cannot reach http://127.0.0.1:9/mcp: bad port. Live instances: ${older.rec.instanceId}, ${newer.rec.instanceId}`);
    await assert.rejects(b.raw('tools/call', { name: 'bridge_select', arguments: { instanceId: 'x', url: 'http://h/mcp' } }), (e) => e.code === -32602);
    await assert.rejects(b.raw('tools/call', { name: 'bridge_select', arguments: {} }), (e) => e.code === -32602);
    await assert.rejects(b.raw('tools/call', { name: 'bridge_select', arguments: { url: 'nope' } }), (e) => e.code === -32602);
    // Failed selections keep the target.
    r = await b.client.callTool({ name: 'bridge_instances', arguments: {} });
    assert.equal(r.structuredContent.target.instanceId, older.rec.instanceId);

    // Select by URL: explicit target, no instanceId; bridge_instances marks it by URL.
    r = await b.client.callTool({ name: 'bridge_select', arguments: { url: newer.srv.url } });
    assert.deepEqual(r.structuredContent, { target: { url: newer.srv.url, source: 'explicit' } });
    await until(() => b.notes.tools === 2);
    r = await b.client.callTool({ name: 'bridge_instances', arguments: {} });
    assert.deepEqual(r.structuredContent.instances.map((i) => i.selected), [false, true]);
    assert.equal(b.notes.tools, 2);
  } finally {
    await b.close();
    await older.close();
    await newer.close();
  }
});

test('bridge_launch without a configured app, and with a missing one', async () => {
  const registry = tempDir();
  let b = await startBridge({ env: { CIRCUITJS_MCP_REGISTRY: registry } });
  try {
    const r = await b.raw('tools/call', { name: 'bridge_launch', arguments: {} });
    assert.deepEqual(r, { content: [{ type: 'text', text: 'No app executable configured. Set --app or CIRCUITJS_APP.' }], isError: true });
  } finally {
    await b.close();
  }
  const missing = path.join(registry, 'nope');
  b = await startBridge({ args: ['--app', missing], env: { CIRCUITJS_MCP_REGISTRY: registry } });
  try {
    const r = await b.raw('tools/call', { name: 'bridge_launch', arguments: {} });
    assert.equal(text(r), `App executable not found: ${missing}.`);
    await assert.rejects(b.raw('tools/call', { name: 'bridge_launch', arguments: { file: 1 } }), (e) => e.code === -32602);
  } finally {
    await b.close();
  }
});

test('forward timeout: "Timed out after <ms> ms", target kept', async () => {
  const registry = tempDir();
  const app = await fakeApp(registry, {
    handle: (m) => (m.method === 'tools/call' && m.params.name === 'circuit_run' ? new Promise((r) => setTimeout(() => r({ content: [] }), 1500)) : undefined),
  });
  const b = await startBridge({ args: ['--timeout', '300'], env: { CIRCUITJS_MCP_REGISTRY: registry } });
  try {
    const r = await b.raw('tools/call', { name: 'circuit_run', arguments: {} });
    assert.deepEqual(r, { content: [{ type: 'text', text: 'Timed out after 300 ms' }], isError: true });
    const ok = await b.raw('tools/call', { name: 'circuit_get', arguments: {} });
    assert.equal(ok.structuredContent.ok, true);
    assert.equal(b.notes.tools, 0);
  } finally {
    await b.close();
    await app.close();
  }
});

test('SP_MCB_05_03 app closed mid-session: "Instance gone", then the new instance', async () => {
  const registry = tempDir();
  const first = await fakeApp(registry);
  const b = await startBridge({ env: { CIRCUITJS_MCP_REGISTRY: registry } });
  try {
    await b.raw('tools/call', { name: 'circuit_get', arguments: {} });
    await first.close(); // record removed, endpoint closed
    let r = await b.raw('tools/call', { name: 'circuit_get', arguments: {} });
    assert.deepEqual(r, { content: [{ type: 'text', text: `Instance gone: ${first.srv.url}` }], isError: true });
    await until(() => b.notes.tools === 1);
    assert.deepEqual((await b.client.listTools()).tools.map((t) => t.name), BRIDGE_NAMES);
    r = await b.raw('tools/call', { name: 'circuit_get', arguments: {} });
    assert.equal(r.content[0].text, NO_INSTANCE_TEXT);
    const second = await fakeApp(registry);
    try {
      r = await b.raw('tools/call', { name: 'circuit_get', arguments: {} });
      assert.equal(r.structuredContent.ok, true);
      assert.ok(second.srv.requests.some((m) => m.method === 'tools/call'));
      await until(() => b.notes.tools === 2);
    } finally {
      await second.close();
    }
  } finally {
    await b.close();
  }
});

test('explicit --url unreachable at start: handshake ok; calls return the "Cannot reach" text', async () => {
  const registry = tempDir();
  const b = await startBridge({ args: ['--url', 'http://127.0.0.1:9/mcp'], env: { CIRCUITJS_MCP_REGISTRY: registry } });
  try {
    assert.equal(b.client.getInstructions(), BRIDGE_SENTENCE);
    const r = await b.raw('tools/call', { name: 'circuit_get', arguments: {} });
    assert.equal(text(r), 'Cannot reach http://127.0.0.1:9/mcp: bad port. Live instances: none');
    assert.match(b.stderr(), /Cannot reach/);
  } finally {
    await b.close();
  }
});

test('the stdio side forwards results unchanged: extra keys in content blocks, top-level _meta', async (t) => {
  const odd = {
    _meta: { 'io.example/trace': 'abc' },
    content: [{ type: 'text', text: 'x', extra: { kept: true }, annotations: { priority: 0.5 } }, { type: 'image', data: 'AAAA', mimeType: 'image/png', other: 1 }],
    structuredContent: { ok: true, data: { deep: { z: 1, a: [null] } } },
    custom: 7,
  };
  const registry = tempDir();
  const app = await fakeApp(registry, { handle: (m) => (m.method === 'tools/call' ? odd : undefined) });
  t.after(() => app.close());
  const b = await startBridge({ env: { CIRCUITJS_MCP_REGISTRY: registry } });
  try {
    assert.deepEqual(await b.raw('tools/call', { name: 'circuit_get', arguments: {} }), odd);
  } finally {
    await b.close();
  }
});

test('HTTP 413 with a JSON-RPC error body reaches the host as that error; HTTP 500 is an error result', async (t) => {
  const registry = tempDir();
  const app = await fakeApp(registry, {
    handle: (m) => {
      if (m.method !== 'tools/call') return undefined;
      if (m.params.name === 'circuit_import') return { http: { status: 413, body: JSON.stringify({ jsonrpc: '2.0', id: null, error: { code: -32600, message: 'Request body too large' } }) } };
      if (m.params.name === 'circuit_get') return { http: { status: 500, body: 'boom' } };
      return undefined;
    },
  });
  t.after(() => app.close());
  const b = await startBridge({ env: { CIRCUITJS_MCP_REGISTRY: registry } });
  try {
    await assert.rejects(b.raw('tools/call', { name: 'circuit_import', arguments: {} }), (e) => e.code === -32600 && e.message === 'MCP error -32600: Request body too large');
    const r = await b.raw('tools/call', { name: 'circuit_get', arguments: {} });
    assert.deepEqual(r, { content: [{ type: 'text', text: `${app.srv.url} answered HTTP 500: boom` }], isError: true });
    // The target is kept.
    assert.equal((await b.raw('tools/call', { name: 'circuit_types', arguments: {} })).structuredContent.ok, true);
    assert.equal(b.notes.tools, 0);
  } finally {
    await b.close();
  }
});
