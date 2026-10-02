// [SP_MCB_03_02] Launch: start the packaged app detached and wait for its instance record.
// The app gets no arguments and its output is discarded; it keeps running when the bridge
// exits, also when the wait times out (C_MCB_03_05). Opening a file in the launched instance
// is the caller's job (bridge_launch / CLI `launch`, SP_MCB_02_02).

import { spawn as nodeSpawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { BridgeError } from './errors.js';
import { readRegistry, latestRecord, startedAtMs } from './registry.js';

/** Registry poll interval while waiting for the launched instance. */
export const LAUNCH_POLL_MS = 250;

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * Spawns the executable detached, with no arguments and stdio ignored. Resolves once the
 * process has started; rejects with the spawn error (ENOENT, EACCES, ...).
 * @param {string} exe absolute path
 * @param {typeof nodeSpawn} spawnFn
 */
function startDetached(exe, spawnFn) {
  return new Promise((resolve, reject) => {
    let child;
    try {
      child = spawnFn(exe, [], { detached: true, stdio: 'ignore', windowsHide: false });
    } catch (e) {
      reject(e);
      return;
    }
    child.once('error', reject);
    child.once('spawn', () => {
      child.removeListener('error', reject);
      child.on('error', () => {}); // a later error must not crash the bridge
      child.unref();
      resolve(child);
    });
  });
}

/**
 * Starts the app and waits for an instance record with `startedAt` at or after the spawn.
 * @param {{app: string|null, registry: string, launchTimeout: number}} opts
 * @param {object} [deps]  test seams
 * @param {typeof nodeSpawn} [deps.spawn]
 * @param {(pid: number) => boolean} [deps.isAlive]
 * @param {number} [deps.pollMs]
 * @param {() => number} [deps.now]
 * @returns {Promise<{target: {url: string, instanceId: string, source: 'launched'}, record: import('./registry.js').InstanceRecord}>}
 * @throws {BridgeError} no_app | app_not_found | app_spawn_failed | launch_timeout
 */
export async function launch(opts, deps = {}) {
  if (!opts.app) {
    throw new BridgeError('no_app', 'No app executable configured. Set --app or CIRCUITJS_APP.');
  }
  const exe = path.resolve(opts.app);
  let stat = null;
  try {
    stat = fs.statSync(exe);
  } catch (e) {
    // missing, or a path component is not a directory
  }
  if (!stat || !stat.isFile()) {
    throw new BridgeError('app_not_found', `App executable not found: ${exe}.`);
  }

  const now = deps.now || Date.now;
  const pollMs = deps.pollMs || LAUNCH_POLL_MS;
  const spawnedAt = now();
  try {
    await startDetached(exe, deps.spawn || nodeSpawn);
  } catch (e) {
    throw new BridgeError('app_spawn_failed', `Cannot start the app ${exe}: ${(e && (e.code || e.message)) || e}.`);
  }

  const deadline = spawnedAt + opts.launchTimeout;
  for (;;) {
    const remaining = deadline - now();
    await sleep(Math.max(0, Math.min(pollMs, remaining)));
    const { records } = readRegistry(opts.registry, { isAlive: deps.isAlive });
    const fresh = latestRecord(records.filter((r) => startedAtMs(r) >= spawnedAt));
    if (fresh) {
      return { target: { url: fresh.urls[0], instanceId: fresh.instanceId, source: 'launched' }, record: fresh };
    }
    if (now() >= deadline) {
      throw new BridgeError(
        'launch_timeout',
        `The app started but no instance record appeared within ${opts.launchTimeout} ms.`,
      );
    }
  }
}
