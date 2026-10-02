// [SP_MCB_03_02] Launch: configuration errors, detached spawn, registry poll, timeout.
// The "app" is a small Node script that writes an instance record like the real app.

import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { launch, LAUNCH_POLL_MS } from '../src/launch.js';
import { BridgeError } from '../src/errors.js';
import { tempDir, makeRecord, writeRecord } from './helpers.mjs';

/** Writes an executable fake app; it registers after `delayMs` (never when null), lives `lifeMs`. */
function fakeApp(registry, { delayMs = 100, lifeMs = 3000, port = 7399 } = {}) {
  const dir = tempDir('circuitjs-mcp-app-');
  const file = path.join(dir, 'CircuitSimulator');
  const register = delayMs === null ? '' : `
setTimeout(() => {
  const ms = Date.now();
  const rec = { instanceId: process.pid + '-' + ms, pid: process.pid, port: ${port}, host: '127.0.0.1',
    urls: ['http://127.0.0.1:${port}/mcp'], appVersion: '3.2.6', startedAt: new Date(ms).toISOString(),
    title: 'fake', protocolRevisions: ['2025-11-25', '2025-06-18'], toolsVersion: '1.0',
    argv: process.argv.slice(2) };
  fs.writeFileSync(path.join(${JSON.stringify(registry)}, rec.instanceId + '.json'), JSON.stringify(rec));
}, ${delayMs});`;
  fs.writeFileSync(
    file,
    `#!${process.execPath}
const fs = require('fs');
const path = require('path');
${register}
setTimeout(() => process.exit(0), ${lifeMs});
`,
    { mode: 0o755 },
  );
  fs.writeFileSync(path.join(dir, 'package.json'), '{"type":"commonjs"}');
  return file;
}

const base = (over) => ({ app: null, registry: tempDir(), launchTimeout: 5000, ...over });

test('poll interval is 250 ms', () => {
  assert.equal(LAUNCH_POLL_MS, 250);
});

test('no app configured', async () => {
  await assert.rejects(launch(base()), (e) => {
    assert.ok(e instanceof BridgeError);
    assert.equal(e.code, 'no_app');
    assert.equal(e.message, 'No app executable configured. Set --app or CIRCUITJS_APP.');
    return true;
  });
});

test('app not found (missing file or a directory)', async () => {
  const missing = path.join(tempDir(), 'nope');
  await assert.rejects(launch(base({ app: missing })), (e) => e.code === 'app_not_found' && e.message === `App executable not found: ${missing}.`);
  const dir = tempDir();
  await assert.rejects(launch(base({ app: dir })), (e) => e.code === 'app_not_found');
});

test('app that cannot be executed', async () => {
  const file = path.join(tempDir(), 'app');
  fs.writeFileSync(file, 'x', { mode: 0o644 });
  await assert.rejects(launch(base({ app: file })), (e) => e.code === 'app_spawn_failed' && /EACCES/.test(e.message));
});

test('launch: detached spawn without arguments, waits for the new record; older records do not count', async () => {
  const registry = tempDir();
  writeRecord(registry, makeRecord({ pid: process.pid, startedAtMs: Date.now() - 60000, port: 7311 })); // live but older
  const app = fakeApp(registry, { delayMs: 400 });
  const t0 = Date.now();
  const r = await launch(base({ app, registry }));
  const took = Date.now() - t0;
  assert.equal(r.target.source, 'launched');
  assert.equal(r.target.url, 'http://127.0.0.1:7399/mcp');
  assert.match(r.target.instanceId, /^\d+-\d+$/);
  assert.deepEqual(r.record.argv, []);
  assert.ok(took >= 400 && took < 3000, `took ${took} ms`);
  // Still running after launch returned: detached, not killed.
  assert.doesNotThrow(() => process.kill(r.record.pid, 0));
});

test('launch timeout: error text; the started process is left running', async () => {
  const registry = tempDir();
  const app = fakeApp(registry, { delayMs: null, lifeMs: 3000 });
  const t0 = Date.now();
  await assert.rejects(
    launch(base({ app, registry, launchTimeout: 700 })),
    (e) => e.code === 'launch_timeout' && e.message === 'The app started but no instance record appeared within 700 ms.',
  );
  const took = Date.now() - t0;
  assert.ok(took >= 700 && took < 1500, `took ${took} ms`);
});
