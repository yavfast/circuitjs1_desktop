'use strict';
// [SP_MCP_01_02] [SP_MCP_04_02] Instance registry: one JSON record per listening instance in
// <user home>/.circuitjs1/instances/<instanceId>.json (mode 0600), written atomically (temp file +
// rename) and removed by its owner on exit. A starting instance deletes the records of dead
// processes. All file access is synchronous: removal runs inside the window `unload` handler,
// where no later task is guaranteed to run.
//
// Node modules are required lazily (inside functions): the bundle is also loaded by a plain
// browser build, which has no `require`.

const RECORD_SUFFIX = '.json';
const TEMP_SUFFIX = '.json.tmp';

function node(name) {
  return require(name);
}

/** @returns the instance directory of the current user */
function instanceDir() {
  const path = node('path');
  const os = node('os');
  return path.join(os.homedir(), '.circuitjs1', 'instances');
}

/** @returns true when a process with this pid exists (EPERM: it exists, owned by another user) */
function pidAlive(pid) {
  if (!Number.isInteger(pid) || pid <= 0) return false;
  try {
    process.kill(pid, 0);
    return true;
  } catch (e) {
    return e && e.code === 'EPERM';
  }
}

// Only files named like an instance record are ever read or deleted: `<pid>-<startedAtMs>.json`
// and its temp file `<pid>-<startedAtMs>.json.tmp`.
const RECORD_NAME = /^(\d+)-(\d+)\.json(\.tmp)?$/;

/** Parses an instance file name: {stem, pid, temp}, or null for any other name. */
function parseRecordName(name) {
  const m = RECORD_NAME.exec(name);
  if (!m) return null;
  return { stem: m[1] + '-' + m[2], pid: Number(m[1]), temp: m[3] !== undefined };
}

/**
 * Deletes the records (and leftover temp files) of processes that no longer exist. Only regular
 * files (no symlinks) named `<pid>-<startedAtMs>.json[.tmp]` are considered. A record is deleted
 * only when its content matches its name (`instanceId` is the name stem, `pid` the name's pid)
 * and that pid is dead; a temp file when the pid in its name is dead. A record that cannot be
 * read or parsed, or that does not match its name, is left alone: it is not ours to judge.
 * @returns the file names removed
 */
function removeDeadRecords(dir) {
  const fs = node('fs');
  const path = node('path');
  const removed = [];
  let names;
  try {
    names = fs.readdirSync(dir);
  } catch (e) {
    return removed;
  }
  for (const name of names) {
    const parsed = parseRecordName(name);
    if (!parsed) continue;
    const file = path.join(dir, name);
    try {
      if (!fs.lstatSync(file).isFile()) continue;
    } catch (e) {
      continue;
    }
    if (!parsed.temp) {
      let rec;
      try {
        rec = JSON.parse(fs.readFileSync(file, 'utf8'));
      } catch (e) {
        continue;
      }
      if (!rec || rec.instanceId !== parsed.stem || rec.pid !== parsed.pid) continue;
    }
    if (pidAlive(parsed.pid)) continue;
    try {
      fs.unlinkSync(file);
      removed.push(name);
    } catch (e) {
      // another instance removed it first
    }
  }
  return removed;
}

/** Writes `record` as <dir>/<record.instanceId>.json atomically, user-only. @returns the path */
function writeRecord(dir, record) {
  const fs = node('fs');
  const path = node('path');
  fs.mkdirSync(dir, { recursive: true, mode: 0o700 });
  try {
    fs.chmodSync(dir, 0o700); // the mkdir mode is masked by the umask; an older directory may be wider
  } catch (e) {
    // not supported on this file system
  }
  const file = path.join(dir, record.instanceId + RECORD_SUFFIX);
  const temp = path.join(dir, record.instanceId + TEMP_SUFFIX);
  fs.writeFileSync(temp, JSON.stringify(record, null, 2) + '\n', { mode: 0o600 });
  try {
    fs.chmodSync(temp, 0o600); // the mode of writeFileSync is masked by the umask
  } catch (e) {
    // not supported on this file system
  }
  fs.renameSync(temp, file);
  return file;
}

/** Deletes a record file; a missing file is not an error. */
function removeRecord(file) {
  if (!file) return;
  try {
    node('fs').unlinkSync(file);
  } catch (e) {
    // already gone
  }
}

module.exports = { instanceDir, pidAlive, parseRecordName, removeDeadRecords, writeRecord, removeRecord };
