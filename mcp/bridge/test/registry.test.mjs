// [SP_MCB_03_01] [SP_MCB_01_03] Registry reader: liveness, stale-record deletion, latest-instance
// selection, InstanceInfo.

import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { readRegistry, latestRecord, findRecord, instanceInfo, liveIdsText, pidAlive } from '../src/registry.js';
import { tempDir, makeRecord, writeRecord, listDir } from './helpers.mjs';

const LIVE = new Set([101, 102, 103]);
const isAlive = (pid) => LIVE.has(pid);

test('pidAlive: this process yes, nonsense pids no', () => {
  assert.equal(pidAlive(process.pid), true);
  assert.equal(pidAlive(0), false);
  assert.equal(pidAlive(-5), false);
  assert.equal(pidAlive(1.5), false);
  assert.equal(pidAlive(2 ** 22 + 12345), false); // above Linux pid_max
});

test('missing directory: no records, nothing created', () => {
  const dir = path.join(tempDir(), 'absent');
  assert.deepEqual(readRegistry(dir, { isAlive }), { records: [], removed: [] });
  assert.equal(fs.existsSync(dir), false);
});

test('live records are listed oldest first; dead ones are deleted', () => {
  const dir = tempDir();
  const a = makeRecord({ pid: 101, startedAtMs: 2000, port: 7312 });
  const b = makeRecord({ pid: 102, startedAtMs: 1000, port: 7311 });
  const dead = makeRecord({ pid: 999, startedAtMs: 3000, port: 7313 });
  writeRecord(dir, a);
  writeRecord(dir, b);
  const deadName = writeRecord(dir, dead);
  const { records, removed } = readRegistry(dir, { isAlive });
  assert.deepEqual(records.map((r) => r.instanceId), [b.instanceId, a.instanceId]);
  assert.deepEqual(removed, [deadName]);
  assert.deepEqual(listDir(dir), [`${a.instanceId}.json`, `${b.instanceId}.json`].sort());
});

test('leftover temp files of dead processes go; unreadable and foreign files stay', () => {
  const dir = tempDir();
  fs.writeFileSync(path.join(dir, '999-5.json.tmp'), '{');
  fs.writeFileSync(path.join(dir, '101-5.json.tmp'), '{'); // owner alive: mid-write
  fs.writeFileSync(path.join(dir, 'broken.json'), '{not json');
  fs.writeFileSync(path.join(dir, 'notes.txt'), 'x');
  fs.writeFileSync(path.join(dir, 'nopid.json'), JSON.stringify({ instanceId: 'x' }));
  // Alive but unusable (no urls): skipped, kept.
  fs.writeFileSync(path.join(dir, '102-1.json'), JSON.stringify({ instanceId: '102-1', pid: 102, startedAt: new Date(1).toISOString() }));
  const { records, removed } = readRegistry(dir, { isAlive });
  assert.deepEqual(records, []);
  assert.deepEqual(removed, ['999-5.json.tmp']);
  assert.deepEqual(listDir(dir), ['101-5.json.tmp', '102-1.json', 'broken.json', 'nopid.json', 'notes.txt']);
});

test('SP_MCB_05_04 stale records only: all deleted, no instance', () => {
  const dir = tempDir();
  writeRecord(dir, makeRecord({ pid: 997, startedAtMs: 1000 }));
  writeRecord(dir, makeRecord({ pid: 998, startedAtMs: 2000 }));
  const { records, removed } = readRegistry(dir, { isAlive });
  assert.deepEqual(records, []);
  assert.equal(removed.length, 2);
  assert.deepEqual(listDir(dir), []);
});

test('the real liveness check keeps a record of this process', () => {
  const dir = tempDir();
  const me = makeRecord({ pid: process.pid, startedAtMs: Date.now() });
  writeRecord(dir, me);
  assert.deepEqual(readRegistry(dir).records.map((r) => r.instanceId), [me.instanceId]);
});

test('latestRecord picks the most recent startedAt, ties by instanceId', () => {
  assert.equal(latestRecord([]), null);
  const old = makeRecord({ pid: 101, startedAtMs: 1000 });
  const mid = makeRecord({ pid: 102, startedAtMs: 5000 });
  const recent = makeRecord({ pid: 103, startedAtMs: 9000 });
  assert.equal(latestRecord([mid, recent, old]), recent);
  assert.equal(latestRecord([recent, old, mid]), recent);
  const tieA = makeRecord({ pid: 101, startedAtMs: 7000 });
  const tieB = makeRecord({ pid: 102, startedAtMs: 7000 });
  assert.equal(latestRecord([tieB, tieA]), tieB);
  assert.equal(latestRecord([tieA, tieB]), tieB);
});

test('findRecord and liveIdsText', () => {
  const r1 = makeRecord({ pid: 101, startedAtMs: 1 });
  const r2 = makeRecord({ pid: 102, startedAtMs: 2 });
  assert.equal(findRecord([r1, r2], '102-2'), r2);
  assert.equal(findRecord([r1, r2], 'x'), null);
  assert.equal(liveIdsText([r1, r2]), '101-1, 102-2');
  assert.equal(liveIdsText([]), 'none');
});

test('instanceInfo: SP_MCB_01_03 fields, first URL, selected flag', () => {
  const rec = makeRecord({ pid: 101, startedAtMs: 1000, urls: ['http://127.0.0.1:7311/mcp', 'http://192.168.1.5:7311/mcp'] });
  const info = instanceInfo(rec, null);
  assert.deepEqual(info, {
    instanceId: '101-1000',
    url: 'http://127.0.0.1:7311/mcp',
    title: 'CircuitJS1',
    appVersion: '3.2.6',
    toolsVersion: '1.0',
    startedAt: new Date(1000).toISOString(),
    selected: false,
  });
  assert.equal(instanceInfo(rec, { url: 'x', instanceId: '101-1000', source: 'registry' }).selected, true);
  assert.equal(instanceInfo(rec, { url: 'x', instanceId: '102-1', source: 'registry' }).selected, false);
  assert.equal(instanceInfo(rec, { url: 'http://127.0.0.1:7311/mcp', source: 'explicit' }).selected, true);
});

test('only files named <pid>-<ms>.json[.tmp] are touched: other names stay whatever their content', () => {
  const dir = tempDir();
  const dead = JSON.stringify({ instanceId: '999-1', pid: 999, urls: ['http://x/mcp'], startedAt: new Date(1).toISOString() });
  for (const name of ['settings.json', '999-1.JSON', 'x999-1.json', '999-1.json.bak', '999-1.json.tmp.old', '999.json', '999-1-2.json']) {
    fs.writeFileSync(path.join(dir, name), dead);
  }
  fs.writeFileSync(path.join(dir, 'tmp.json.tmp'), '{');
  const before = listDir(dir);
  assert.deepEqual(readRegistry(dir, { isAlive }), { records: [], removed: [] });
  assert.deepEqual(listDir(dir), before);
});

test('a record whose content does not match its name stays and is not listed', () => {
  const dir = tempDir();
  // Name pid alive, content pid dead.
  fs.writeFileSync(path.join(dir, '101-5.json'), JSON.stringify({ ...makeRecord({ pid: 3999998, startedAtMs: 5 }), instanceId: '101-5' }));
  // Name pid dead, content pid differs.
  fs.writeFileSync(path.join(dir, '999-6.json'), JSON.stringify({ ...makeRecord({ pid: 998, startedAtMs: 6 }), instanceId: '999-6' }));
  // Pids agree, instanceId does not match the name.
  fs.writeFileSync(path.join(dir, '997-7.json'), JSON.stringify(makeRecord({ pid: 997, startedAtMs: 8 })));
  // Live, pids agree, instanceId differs: not listed.
  fs.writeFileSync(path.join(dir, '102-9.json'), JSON.stringify(makeRecord({ pid: 102, startedAtMs: 10 })));
  const before = listDir(dir);
  assert.deepEqual(readRegistry(dir, { isAlive }), { records: [], removed: [] });
  assert.deepEqual(listDir(dir), before);
});

test('symlinks and other non-regular files stay, also with a record name of a dead pid', () => {
  const dir = tempDir();
  const elsewhere = tempDir();
  const rec = makeRecord({ pid: 999, startedAtMs: 1 });
  const target = path.join(elsewhere, 'precious.json');
  fs.writeFileSync(target, JSON.stringify(rec));
  fs.symlinkSync(target, path.join(dir, `${rec.instanceId}.json`));
  fs.symlinkSync(target, path.join(dir, '999-2.json.tmp'));
  fs.mkdirSync(path.join(dir, '999-3.json'));
  assert.deepEqual(readRegistry(dir, { isAlive }), { records: [], removed: [] });
  assert.deepEqual(listDir(dir), ['999-1.json', '999-2.json.tmp', '999-3.json']);
  assert.ok(fs.existsSync(target));
  // A live record behind a symlink is not listed either.
  const live = makeRecord({ pid: 101, startedAtMs: 4 });
  const liveTarget = path.join(elsewhere, 'live.json');
  fs.writeFileSync(liveTarget, JSON.stringify(live));
  fs.symlinkSync(liveTarget, path.join(dir, `${live.instanceId}.json`));
  assert.deepEqual(readRegistry(dir, { isAlive }).records, []);
});
