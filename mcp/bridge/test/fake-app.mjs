// An executable fake CircuitJS1 app for launch tests: started like the real app (no arguments),
// it runs fake-server.mjs and writes an instance record like the real one.

import fs from 'node:fs';
import path from 'node:path';
import { tempDir } from './helpers.mjs';

const FAKE_SERVER_URL = new URL('./fake-server.mjs', import.meta.url).href;

/**
 * An executable fake app: starts a fake endpoint, registers it, logs circuit_file arguments to
 * <dir>/calls.json and exits after `lifeMs`. `openError` makes circuit_file fail.
 */
export function fakeAppExe(registry, { lifeMs = 8000, openError = false, openRpcError = false, registerAfterMs = 0 } = {}) {
  const dir = tempDir('circuitjs-mcp-app-');
  const exe = path.join(dir, 'CircuitSimulator');
  const calls = path.join(dir, 'calls.json');
  const starts = path.join(dir, 'starts');
  fs.writeFileSync(path.join(dir, 'package.json'), '{"type":"commonjs"}');
  fs.writeFileSync(exe, `#!${process.execPath}
const fs = require('fs');
const path = require('path');
fs.appendFileSync(${JSON.stringify(starts)}, process.pid + '\\n');
import(${JSON.stringify(FAKE_SERVER_URL)}).then(async ({ startFakeServer }) => {
  await new Promise((r) => setTimeout(r, ${registerAfterMs}));
  const srv = await startFakeServer({
    instructions: 'Launched fake. toolsVersion 1.0.',
    handle: (m) => {
      if (m.method !== 'tools/call' || m.params.name !== 'circuit_file') return undefined;
      fs.appendFileSync(${JSON.stringify(calls)}, JSON.stringify(m.params.arguments) + '\\n');
      if (${openRpcError}) return { error: { code: -32602, message: 'Invalid arguments for tool circuit_file: path' } };
      return ${openError}
        ? { content: [{ type: 'text', text: '{"ok":false,"error":{"code":"file_not_found"}}' }], structuredContent: { ok: false, error: { code: 'file_not_found' } }, isError: true }
        : { content: [{ type: 'text', text: '{"ok":true}' }], structuredContent: { ok: true, data: { doc: 'D2', elements: 3 } } };
    },
  });
  const ms = Date.now();
  const rec = { instanceId: process.pid + '-' + ms, pid: process.pid, port: srv.port, host: '127.0.0.1', urls: [srv.url],
    appVersion: '3.2.6', startedAt: new Date(ms).toISOString(), title: 'launched', protocolRevisions: ['2025-11-25', '2025-06-18'], toolsVersion: '1.0' };
  fs.writeFileSync(path.join(${JSON.stringify(registry)}, rec.instanceId + '.json'), JSON.stringify(rec));
  setTimeout(() => process.exit(0), ${lifeMs});
});
`, { mode: 0o755 });
  return {
    exe,
    calls: () => (fs.existsSync(calls) ? fs.readFileSync(calls, 'utf8').trim().split('\n').map((l) => JSON.parse(l)) : []),
    starts: () => (fs.existsSync(starts) ? fs.readFileSync(starts, 'utf8').trim().split('\n').length : 0),
  };
}

/** Ends every recorded fake app process (not this test process). */
export function killRecorded(registry) {
  for (const name of fs.readdirSync(registry)) {
    try {
      const rec = JSON.parse(fs.readFileSync(path.join(registry, name), 'utf8'));
      if (rec.pid !== process.pid) process.kill(rec.pid, 'SIGTERM');
    } catch (e) {
      // gone already
    }
  }
}

