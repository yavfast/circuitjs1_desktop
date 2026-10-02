// [SP_MCB_03_03] Forwarder pieces: revision pin, connection-failure classification, transparent
// results, timeout and instance-gone handling (against the fake server).

import test from 'node:test';
import assert from 'node:assert/strict';
import { launchAndOpen } from '../src/bridge-tools.js';
import { pinnedRevision, isConnectionFailure, passThroughError, TargetConnection, BridgeSession, ForwardError } from '../src/forward.js';
import { tempDir, makeRecord, writeRecord } from './helpers.mjs';
import { startFakeServer } from './fake-server.mjs';

test('pinnedRevision: newest supported entry of the record, null without a record', () => {
  assert.equal(pinnedRevision(null), null);
  assert.equal(pinnedRevision({ protocolRevisions: [] }), null);
  assert.equal(pinnedRevision({ protocolRevisions: ['2025-11-25', '2025-06-18'] }), '2025-11-25');
  assert.equal(pinnedRevision({ protocolRevisions: ['2026-07-28', '2025-06-18'] }), '2025-06-18');
  assert.throws(() => pinnedRevision({ protocolRevisions: ['2099-01-01'] }), /no common protocol revision/);
});

test('isConnectionFailure: socket errors yes, answers no', () => {
  assert.equal(isConnectionFailure(Object.assign(new TypeError('fetch failed'), { cause: { code: 'ECONNREFUSED' } })), true);
  assert.equal(isConnectionFailure({ cause: { code: 'UND_ERR_SOCKET' } }), true);
  assert.equal(isConnectionFailure(new Error('MCP error -32602: bad')), false);
  assert.equal(isConnectionFailure(Object.assign(new Error('Streamable HTTP error: x'), { code: 404 })), false);
});

test('passThroughError keeps code, message and data of the target error', () => {
  const e = passThroughError(Object.assign(new Error('MCP error -32602: Unknown tool: x'), { code: -32602, data: { a: 1 } }));
  assert.equal(e.code, -32602);
  assert.equal(e.message, 'Unknown tool: x');
  assert.deepEqual(e.data, { a: 1 });
});

test('the initialize request asks for the pinned revision', async () => {
  const srv = await startFakeServer({ revisions: ['2025-06-18'] });
  try {
    const rec = makeRecord({ pid: process.pid, startedAtMs: 1, urls: [srv.url] });
    rec.protocolRevisions = ['2025-06-18'];
    const c = new TargetConnection({ target: { url: srv.url, source: 'registry' }, record: rec }, { timeoutMs: 1000 });
    const h = await c.connect();
    assert.equal(h.protocolVersion, '2025-06-18');
    assert.equal(srv.requests.find((m) => m.method === 'initialize').params.protocolVersion, '2025-06-18');
    await c.close();
  } finally {
    await srv.close();
  }
});

test('results are forwarded unchanged (unknown keys, nested values)', async () => {
  const odd = {
    content: [{ type: 'text', text: 'x', extra: { kept: true } }, { type: 'image', data: 'AAAA', mimeType: 'image/png' }],
    structuredContent: { ok: true, data: { n: 1.5, list: [null, 'a'], deep: { z: 1, a: 2 } } },
    custom: 7,
  };
  const srv = await startFakeServer({ handle: (m) => (m.method === 'tools/call' ? odd : undefined) });
  try {
    const c = new TargetConnection({ target: { url: srv.url, source: 'explicit' }, record: null }, { timeoutMs: 1000 });
    await c.connect();
    assert.deepEqual(await c.request('tools/call', { name: 'circuit_x', arguments: {} }), odd);
    await c.close();
  } finally {
    await srv.close();
  }
});

test('timeout keeps the target; a closed endpoint is "Instance gone" and clears it', async () => {
  const srv = await startFakeServer({
    handle: (m) => (m.method === 'tools/call' && m.params.name === 'circuit_slow' ? new Promise((r) => setTimeout(() => r({ content: [] }), 600)) : undefined),
  });
  const registry = tempDir();
  writeRecord(registry, makeRecord({ pid: process.pid, startedAtMs: 1000, urls: [srv.url] }));
  const changes = [];
  const session = new BridgeSession({ url: null, instance: null, launch: false, app: null, registry, timeout: 200, launchTimeout: 1000 }, { log: () => {} });
  session.onChange((t) => changes.push(t && t.url));
  const conn = await session.ensure();
  assert.equal(conn.target.source, 'registry');
  assert.deepEqual(changes, [srv.url]);
  await assert.rejects(session.forward(conn, 'tools/call', { name: 'circuit_slow', arguments: {} }), (e) => e instanceof ForwardError && e.kind === 'timeout' && e.message === 'Timed out after 200 ms');
  assert.equal(session.conn, conn);
  await srv.close();
  await assert.rejects(session.forward(conn, 'tools/call', { name: 'circuit_x', arguments: {} }), (e) => e.kind === 'gone' && e.message === `Instance gone: ${srv.url}`);
  assert.equal(session.conn, null);
  assert.deepEqual(changes, [srv.url, null]);
});

function sessionOver(registry, deps = {}) {
  return new BridgeSession({ url: null, instance: null, launch: false, app: '/x', registry, timeout: 2000, launchTimeout: 1000 }, { log: () => {}, ...deps });
}

test('one launch at a time: the --launch resolution and bridge_launch share it', async (t) => {
  const srv = await startFakeServer();
  t.after(() => srv.close());
  let launches = 0;
  const launch = async () => {
    launches++;
    await new Promise((r) => setTimeout(r, 200));
    return { target: { url: srv.url, instanceId: '1-1', source: 'launched' }, record: null };
  };
  const session = sessionOver(tempDir(), { launch });
  const [conn, out] = await Promise.all([session.ensure({ launch: true }), launchAndOpen(session)]);
  assert.equal(launches, 1);
  assert.equal(conn.target.source, 'launched');
  assert.deepEqual(out.target, { url: srv.url, instanceId: '1-1', source: 'launched' });
  await session.close();
});

test('a launching ensure() that meets a resolution without launch resolves again with launch', async (t) => {
  const srv = await startFakeServer();
  t.after(() => srv.close());
  let launches = 0;
  const launch = async () => {
    launches++;
    return { target: { url: srv.url, instanceId: '1-1', source: 'launched' }, record: null };
  };
  const session = sessionOver(tempDir(), { launch });
  const [plain, launching] = await Promise.all([session.ensure({ launch: false }), session.ensure({ launch: true })]);
  assert.equal(plain, null);
  assert.equal(launching.target.source, 'launched');
  assert.equal(launches, 1);
  await session.close();
});

test('switching the target lets calls in flight on the old one finish, then closes it', async (t) => {
  const slow = await startFakeServer({
    handle: (m) => (m.method === 'tools/call' ? new Promise((r) => setTimeout(() => r({ content: [{ type: 'text', text: 'late' }] }), 300)) : undefined),
  });
  const other = await startFakeServer();
  t.after(async () => {
    await slow.close();
    await other.close();
  });
  const registry = tempDir();
  writeRecord(registry, makeRecord({ pid: process.pid, startedAtMs: 1000, urls: [slow.url] }));
  const session = sessionOver(registry);
  const a = await session.ensure();
  const call = session.forward(a, 'tools/call', { name: 'circuit_run', arguments: {} });
  await new Promise((r) => setTimeout(r, 50));
  const b = await session.connect({ target: { url: other.url, source: 'explicit' }, record: null });
  await session.adopt(b);
  assert.equal(session.conn, b);
  assert.deepEqual(await call, { content: [{ type: 'text', text: 'late' }] });
  assert.equal(a._client, null); // closed once idle
  await session.close();
});

test('HTTP error status: a JSON-RPC error body passes through, any other body is a ForwardError', async (t) => {
  const srv = await startFakeServer({
    handle: (m) => {
      if (m.method !== 'tools/call') return undefined;
      if (m.params.name === 'circuit_big') return { http: { status: 413, body: JSON.stringify({ jsonrpc: '2.0', id: null, error: { code: -32600, message: 'Request body too large', data: { limit: 1 } } }) } };
      return { http: { status: 500, body: 'boom' } };
    },
  });
  t.after(() => srv.close());
  const c = new TargetConnection({ target: { url: srv.url, source: 'explicit' }, record: null }, { timeoutMs: 1000 });
  await c.connect();
  await assert.rejects(c.request('tools/call', { name: 'circuit_big', arguments: {} }), (e) => {
    assert.ok(!(e instanceof ForwardError));
    assert.equal(e.code, -32600);
    assert.equal(e.message, 'Request body too large');
    assert.deepEqual(e.data, { limit: 1 });
    return true;
  });
  await assert.rejects(c.request('tools/call', { name: 'circuit_x', arguments: {} }), (e) => e instanceof ForwardError && e.kind === 'http' && e.message === `${srv.url} answered HTTP 500: boom`);
  await c.close();
});
