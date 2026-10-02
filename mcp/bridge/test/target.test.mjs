// [SP_MCB_03_01] Target resolution: explicit URL with probe and no fallback, named instance,
// latest instance, launch, none.

import test from 'node:test';
import assert from 'node:assert/strict';
import { resolveTarget, probe, PROBE_TIMEOUT_MS, reasonOf } from '../src/target.js';
import { BridgeError } from '../src/errors.js';
import { tempDir, makeRecord, writeRecord, listDir } from './helpers.mjs';
import { startFakeServer } from './fake-server.mjs';
import net from 'node:net';

/** A loopback URL whose port was just freed: connecting is refused. */
async function closedPortUrl() {
  const srv = net.createServer();
  await new Promise((resolve) => srv.listen(0, '127.0.0.1', resolve));
  const { port } = srv.address();
  await new Promise((resolve) => srv.close(resolve));
  return `http://127.0.0.1:${port}/mcp`;
}

const LIVE = new Set([101, 102, 103]);
const isAlive = (pid) => LIVE.has(pid);

function opts(over = {}) {
  return { url: null, instance: null, launch: false, app: null, registry: tempDir(), launchTimeout: 1000, ...over };
}

function registryWith(...records) {
  const dir = tempDir();
  for (const r of records) writeRecord(dir, r);
  return dir;
}

test('probe bound is 3 s', () => {
  assert.equal(PROBE_TIMEOUT_MS, 3000);
});

test('probe: MCP handshake against the fake server', async () => {
  const srv = await startFakeServer({ instructions: 'hello toolsVersion 1.0' });
  try {
    const h = await probe(srv.url);
    assert.equal(h.protocolVersion, '2025-11-25');
    assert.equal(h.serverInfo.name, 'fake-circuitjs1');
    assert.equal(h.instructions, 'hello toolsVersion 1.0');
    assert.ok(srv.requests.some((m) => m.method === 'initialize'));
    assert.ok(srv.requests.some((m) => m.method === 'notifications/initialized'));
  } finally {
    await srv.close();
  }
});

test('explicit URL reachable: source explicit, registry ignored', async () => {
  const srv = await startFakeServer();
  try {
    const registry = registryWith(makeRecord({ pid: 101, startedAtMs: 1000 }));
    const r = await resolveTarget(opts({ url: srv.url, registry }), { isAlive });
    assert.deepEqual(r.target, { url: srv.url, source: 'explicit' });
    assert.equal(r.record, null);
    assert.equal(r.handshake.protocolVersion, '2025-11-25');
  } finally {
    await srv.close();
  }
});

test('SP_MCB_05_04 explicit URL unreachable: error names the URL and the live instances, no fallback', async () => {
  const live = makeRecord({ pid: 101, startedAtMs: 1000 });
  const registry = registryWith(live, makeRecord({ pid: 999, startedAtMs: 2000 }));
  const url = await closedPortUrl();
  await assert.rejects(resolveTarget(opts({ url, registry }), { isAlive }), (e) => {
    assert.ok(e instanceof BridgeError);
    assert.equal(e.code, 'unreachable');
    assert.equal(e.message, `Cannot reach ${url}: ECONNREFUSED. Live instances: ${live.instanceId}`);
    return true;
  });
  assert.deepEqual(listDir(registry), [`${live.instanceId}.json`]); // stale one cleaned on the way
});

test('explicit URL on a port fetch refuses (SP_MCB_05_01 example http://127.0.0.1:9/mcp)', async () => {
  const url = 'http://127.0.0.1:9/mcp';
  await assert.rejects(
    resolveTarget(opts({ url }), { isAlive }),
    (e) => e.code === 'unreachable' && e.message === `Cannot reach ${url}: bad port. Live instances: none`,
  );
});

test('explicit URL that never answers: bounded by the probe timeout', async () => {
  const srv = await startFakeServer({ hang: true });
  try {
    const t0 = Date.now();
    await assert.rejects(
      resolveTarget(opts({ url: srv.url }), { isAlive, probeTimeoutMs: 300 }),
      (e) => e.code === 'unreachable' && e.message === `Cannot reach ${srv.url}: no MCP answer within 300 ms. Live instances: none`,
    );
    const took = Date.now() - t0;
    assert.ok(took >= 290 && took < 2000, `took ${took} ms`);
  } finally {
    await srv.close();
  }
});

test('explicit URL answering HTTP 404: "Cannot reach" with the live instances', async () => {
  const srv = await startFakeServer({ postStatus: 404 });
  try {
    const live = makeRecord({ pid: 101, startedAtMs: 1000 });
    const registry = registryWith(live);
    await assert.rejects(resolveTarget(opts({ url: srv.url, registry }), { isAlive }), (e) => {
      assert.equal(e.code, 'unreachable');
      assert.equal(e.message, `Cannot reach ${srv.url}: HTTP 404 (Error POSTing to endpoint: Not Found). Live instances: ${live.instanceId}`);
      return true;
    });
    await assert.rejects(probe(srv.url), (e) => e.code === 404);
  } finally {
    await srv.close();
  }
});

test('reasonOf: transport messages, socket codes, inner causes', async () => {
  const r = reasonOf(new Error('Streamable HTTP error: Error POSTing to endpoint: \n  404 Not Found'));
  assert.equal(r, 'Streamable HTTP error: Error POSTing to endpoint: 404 Not Found');
  assert.equal(reasonOf(Object.assign(new TypeError('fetch failed'), { cause: { code: 'ENOTFOUND' } })), 'ENOTFOUND');
  assert.equal(reasonOf(Object.assign(new TypeError('fetch failed'), { cause: new Error('bad port') })), 'bad port');
  const agg = Object.assign(new AggregateError([Object.assign(new Error('x'), { code: 'ECONNREFUSED' })]), {});
  assert.equal(reasonOf(Object.assign(new TypeError('fetch failed'), { cause: agg })), 'ECONNREFUSED');
});

test('named instance: found, or "Unknown instance" with the live IDs', async () => {
  const a = makeRecord({ pid: 101, startedAtMs: 1000 });
  const b = makeRecord({ pid: 102, startedAtMs: 2000 });
  const registry = registryWith(a, b);
  const r = await resolveTarget(opts({ instance: a.instanceId, registry }), { isAlive });
  assert.deepEqual(r.target, { url: a.urls[0], instanceId: a.instanceId, source: 'registry' });
  assert.equal(r.record.instanceId, a.instanceId);
  await assert.rejects(
    resolveTarget(opts({ instance: 'x', registry }), { isAlive }),
    (e) => e.code === 'unknown_instance' && e.message === `Unknown instance x. Live instances: ${a.instanceId}, ${b.instanceId}`,
  );
});

test('a named instance whose process died is unknown (and its record is deleted)', async () => {
  const dead = makeRecord({ pid: 999, startedAtMs: 1000 });
  const registry = registryWith(dead);
  await assert.rejects(
    resolveTarget(opts({ instance: dead.instanceId, registry }), { isAlive }),
    (e) => e.code === 'unknown_instance' && /Live instances: none$/.test(e.message),
  );
  assert.deepEqual(listDir(registry), []);
});

test('several live instances: the most recently started one', async () => {
  const old = makeRecord({ pid: 101, startedAtMs: 1000, port: 7311 });
  const recent = makeRecord({ pid: 102, startedAtMs: 9000, port: 7312 });
  const mid = makeRecord({ pid: 103, startedAtMs: 5000, port: 7313 });
  const deadNewest = makeRecord({ pid: 999, startedAtMs: 99999, port: 7314 });
  const registry = registryWith(old, recent, mid, deadNewest);
  const r = await resolveTarget(opts({ registry }), { isAlive });
  assert.deepEqual(r.target, { url: 'http://127.0.0.1:7312/mcp', instanceId: recent.instanceId, source: 'registry' });
});

test('SP_MCB_05_04 stale records only: deleted, no target', async () => {
  const registry = registryWith(makeRecord({ pid: 998, startedAtMs: 1000 }), makeRecord({ pid: 999, startedAtMs: 2000 }));
  assert.equal(await resolveTarget(opts({ registry }), { isAlive }), null);
  assert.deepEqual(listDir(registry), []);
});

test('no live instance: launch only when requested', async () => {
  const calls = [];
  const launch = async (o) => {
    calls.push(o);
    return { target: { url: 'http://127.0.0.1:7311/mcp', instanceId: '5-5', source: 'launched' }, record: null };
  };
  assert.equal(await resolveTarget(opts(), { isAlive, launch }), null);
  assert.equal(calls.length, 0);
  const r = await resolveTarget(opts({ launch: true, app: '/x' }), { isAlive, launch });
  assert.equal(r.target.source, 'launched');
  assert.equal(calls.length, 1);
  // A live instance wins over launch.
  const registry = registryWith(makeRecord({ pid: 101, startedAtMs: 1 }));
  assert.equal((await resolveTarget(opts({ launch: true, registry }), { isAlive, launch })).target.source, 'registry');
  assert.equal(calls.length, 1);
});
