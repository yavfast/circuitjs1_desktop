// Shared fixtures for the bridge's unit tests: temporary registry directories and records in
// the app's format (SP_MCP_01_02).

import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { after } from 'node:test';

const made = [];
// Each test file runs in its own process: remove its temp dirs when its tests are done.
after(() => {
  for (const dir of made) fs.rmSync(dir, { recursive: true, force: true });
});

/** @returns a fresh empty directory under the system temp dir, removed after the file's tests */
export function tempDir(prefix = 'circuitjs-mcp-test-') {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), prefix));
  made.push(dir);
  return dir;
}

/** An instance record as the app writes it. */
export function makeRecord({ pid, startedAtMs, port = 7311, title = 'CircuitJS1', urls } = {}) {
  return {
    instanceId: `${pid}-${startedAtMs}`,
    pid,
    port,
    host: '127.0.0.1',
    urls: urls || [`http://127.0.0.1:${port}/mcp`],
    appVersion: '3.2.6',
    startedAt: new Date(startedAtMs).toISOString(),
    title,
    protocolRevisions: ['2025-11-25', '2025-06-18'],
    toolsVersion: '1.0',
  };
}

/** Writes `record` as <dir>/<instanceId>.json. @returns the file name */
export function writeRecord(dir, record) {
  const name = `${record.instanceId}.json`;
  fs.writeFileSync(path.join(dir, name), JSON.stringify(record, null, 2) + '\n', { mode: 0o600 });
  return name;
}

export function listDir(dir) {
  return fs.readdirSync(dir).sort();
}
