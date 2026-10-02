// [SP_MCB_03_01] [SP_MCB_01_03] Instance registry reader. The app writes one JSON record per
// listening instance as <dir>/<pid>-<startedAtMs>.json (SP_MCP_01_02, mcp/server/src/registry.js)
// and removes it on exit; a crashed instance leaves its record behind. The bridge lists the
// records whose process is alive and deletes the records (and leftover temp files) of dead
// processes. It never writes records and never creates the directory.
//
// The record format is restated here rather than imported: the bridge is a separate package
// running on the host's Node, and the app bundle is a CommonJS IIFE.

import fs from 'node:fs';
import path from 'node:path';

// Only files named like an instance record are ever read or deleted: `<pid>-<startedAtMs>.json`
// and its temp file `<pid>-<startedAtMs>.json.tmp`. `--registry` may point at any directory
// (a mistyped ~/.circuitjs1 holds the app's other files), so a file of another name is never
// touched, whatever its content.
const RECORD_NAME = /^(\d+)-(\d+)\.json(\.tmp)?$/;

/**
 * @typedef {object} InstanceRecord        SP_MCP_01_02
 * @property {string} instanceId          `<pid>-<startedAtMs>`
 * @property {number} pid
 * @property {number} port
 * @property {string} host
 * @property {string[]} urls              first one is the URL the bridge uses
 * @property {string} appVersion
 * @property {string} startedAt           ISO-8601 UTC
 * @property {string} title
 * @property {string[]} protocolRevisions served revisions, newest first
 * @property {string} toolsVersion        MAJOR.MINOR
 */

/**
 * @typedef {object} InstanceInfo          SP_MCB_01_03
 * @property {string} instanceId
 * @property {string} url
 * @property {string} title
 * @property {string} appVersion
 * @property {string} toolsVersion
 * @property {string} startedAt
 * @property {boolean} selected
 */

/** @returns true when a process with this pid exists (EPERM: it exists, owned by another user) */
export function pidAlive(pid) {
  if (!Number.isInteger(pid) || pid <= 0) return false;
  try {
    process.kill(pid, 0);
    return true;
  } catch (e) {
    return Boolean(e) && e.code === 'EPERM';
  }
}

/**
 * Parses an instance file name.
 * @returns {{stem: string, pid: number, temp: boolean}|null} null for any other name
 */
export function parseRecordName(name) {
  const m = RECORD_NAME.exec(name);
  if (!m) return null;
  return { stem: `${m[1]}-${m[2]}`, pid: Number(m[1]), temp: m[3] !== undefined };
}

/** True for a regular file; false for a symlink, FIFO, directory or a vanished path. */
function isRegularFile(file) {
  try {
    return fs.lstatSync(file).isFile();
  } catch (e) {
    return false;
  }
}

/** @returns the record's start time in ms since the epoch, or NaN */
export function startedAtMs(record) {
  return Date.parse(record.startedAt);
}

/** True when a parsed record has the fields the bridge relies on. */
function usable(rec) {
  return (
    rec !== null &&
    typeof rec === 'object' &&
    typeof rec.instanceId === 'string' &&
    rec.instanceId !== '' &&
    Number.isInteger(rec.pid) &&
    Array.isArray(rec.urls) &&
    rec.urls.length > 0 &&
    rec.urls.every((u) => typeof u === 'string' && u !== '') &&
    typeof rec.startedAt === 'string' &&
    !Number.isNaN(Date.parse(rec.startedAt))
  );
}

function unlinkQuietly(file) {
  try {
    fs.unlinkSync(file);
    return true;
  } catch (e) {
    return false; // the owner or another reader removed it first
  }
}

/**
 * Reads the registry: live, usable records, oldest first; deletes the records and temp files
 * of dead processes.
 *
 * Only regular files (no symlinks) named `<pid>-<startedAtMs>.json[.tmp]` are considered. A
 * record counts, and may be deleted, only when its content matches its name (`instanceId` is
 * the name stem, `pid` the name's pid); a temp file is deleted when the pid in its name is
 * dead. Anything else is skipped and left alone: a file that cannot be read or parsed may be
 * mid-write by its owner, and a mismatching one is not ours to judge. A matching record of a
 * live process without the other required fields is skipped too.
 * @param {string} dir
 * @param {{isAlive?: (pid: number) => boolean}} [deps]
 * @returns {{records: InstanceRecord[], removed: string[]}} `removed`: deleted file names
 */
export function readRegistry(dir, deps = {}) {
  const isAlive = deps.isAlive || pidAlive;
  const records = [];
  const removed = [];
  let names;
  try {
    names = fs.readdirSync(dir).sort();
  } catch (e) {
    return { records, removed }; // no directory: no instance has ever run for this user
  }
  for (const name of names) {
    const parsed = parseRecordName(name);
    if (!parsed) continue;
    const file = path.join(dir, name);
    if (!isRegularFile(file)) continue;
    if (parsed.temp) {
      if (!isAlive(parsed.pid) && unlinkQuietly(file)) removed.push(name);
      continue;
    }
    let rec;
    try {
      rec = JSON.parse(fs.readFileSync(file, 'utf8'));
    } catch (e) {
      continue;
    }
    if (!rec || typeof rec !== 'object' || rec.instanceId !== parsed.stem || rec.pid !== parsed.pid) continue;
    if (!isAlive(parsed.pid)) {
      if (unlinkQuietly(file)) removed.push(name);
      continue;
    }
    if (usable(rec)) records.push(rec);
  }
  records.sort((a, b) => startedAtMs(a) - startedAtMs(b) || a.instanceId.localeCompare(b.instanceId));
  return { records, removed };
}

/** @returns the most recently started record (SP_MCB_03_01), or null for an empty list */
export function latestRecord(records) {
  let best = null;
  for (const rec of records) {
    if (best === null) {
      best = rec;
      continue;
    }
    const d = startedAtMs(rec) - startedAtMs(best);
    if (d > 0 || (d === 0 && rec.instanceId.localeCompare(best.instanceId) > 0)) best = rec;
  }
  return best;
}

/** @returns the record with this instance ID, or null */
export function findRecord(records, instanceId) {
  return records.find((r) => r.instanceId === instanceId) || null;
}

/**
 * [SP_MCB_01_03] Bridge-tool view of a live record.
 * @param {InstanceRecord} record
 * @param {{url: string, instanceId?: string|null}|null} target  the session's current target
 * @returns {InstanceInfo}
 */
export function instanceInfo(record, target) {
  const url = record.urls[0];
  const selected = Boolean(target) && (target.instanceId ? target.instanceId === record.instanceId : target.url === url);
  return {
    instanceId: record.instanceId,
    url,
    title: String(record.title ?? ''),
    appVersion: String(record.appVersion ?? ''),
    toolsVersion: String(record.toolsVersion ?? ''),
    startedAt: record.startedAt,
    selected,
  };
}

/** "a, b" — or "none" — for the "Live instances: <ids>" part of error texts (SP_MCB_02_02). */
export function liveIdsText(records) {
  return records.length ? records.map((r) => r.instanceId).join(', ') : 'none';
}
