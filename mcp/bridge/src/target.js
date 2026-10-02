// [SP_MCB_03_01] [SP_MCB_01_02] Target resolution. Order: explicit URL (probed, no fallback) →
// named registry instance → most recently started live instance → launch (when enabled) →
// none. Stale records are deleted on every registry read. Resolution never prints; errors are
// BridgeErrors carrying the spec's text.

import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js';
import { BridgeError } from './errors.js';
import { launch as launchApp } from './launch.js';
import { readRegistry, latestRecord, findRecord, liveIdsText } from './registry.js';
import { NAME, VERSION } from './version.js';

/** Bound of the MCP handshake that probes an explicit URL. */
export const PROBE_TIMEOUT_MS = 3000;

/**
 * @typedef {object} Target               SP_MCB_01_02
 * @property {string} url
 * @property {string} [instanceId]        absent for an explicit URL
 * @property {'explicit'|'registry'|'launched'} source
 */

/**
 * @typedef {object} Handshake
 * @property {string|undefined} protocolVersion  negotiated revision
 * @property {{name: string, version: string}|undefined} serverInfo
 * @property {string|undefined} instructions
 * @property {object|undefined} capabilities
 */

/**
 * @typedef {object} Resolution
 * @property {Target} target
 * @property {import('./registry.js').InstanceRecord|null} record  null for an explicit URL
 * @property {Handshake} [handshake]     the probe's result, for an explicit URL
 */

class ProbeTimeout extends Error {
  constructor(ms) {
    super(`no MCP answer within ${ms} ms`);
    this.name = 'ProbeTimeout';
  }
}

/** Short, human-readable cause of a failed probe: a socket error code, an HTTP status, or the innermost message. */
export function reasonOf(error) {
  if (error instanceof ProbeTimeout) return error.message;
  // fetch wraps the socket error: TypeError "fetch failed" → cause {code: ECONNREFUSED}, or a
  // cause without code ("bad port" for the ports fetch refuses, e.g. 9).
  let innermost = error;
  for (let e = error, depth = 0; e && depth < 5; e = e.cause, depth++) {
    if (typeof e.code === 'string' && /^E[A-Z_]+$/.test(e.code)) return e.code; // ECONNREFUSED, ENOTFOUND, ...
    if (Number.isInteger(e.code) && e.code >= 100 && e.code < 600) {
      // SDK StreamableHTTPError: the HTTP status in `code`, the response body in the message.
      const detail = clipText(String(e.message || '').replace(/^Streamable HTTP error:\s*/, ''));
      return detail ? `HTTP ${e.code} (${detail})` : `HTTP ${e.code}`;
    }
    if (Array.isArray(e.errors) && e.errors[0] && typeof e.errors[0].code === 'string') return e.errors[0].code; // AggregateError
    innermost = e;
  }
  return clipText(String((innermost && innermost.message) || innermost || 'unknown error'));
}

/** One line, at most 200 characters. */
function clipText(text) {
  const t = text.replace(/\s+/g, ' ').trim();
  return t.length > 200 ? t.slice(0, 200) + '…' : t;
}

/**
 * MCP handshake (initialize + initialized) against `url`, bounded at `timeoutMs`; the
 * connection is closed afterwards.
 * @param {string} url
 * @param {{timeoutMs?: number}} [o]
 * @returns {Promise<Handshake>}
 */
export async function probe(url, o = {}) {
  const timeoutMs = o.timeoutMs ?? PROBE_TIMEOUT_MS;
  const client = new Client({ name: NAME, version: VERSION }, { capabilities: {} });
  const transport = new StreamableHTTPClientTransport(new URL(url));
  let timer;
  try {
    // The request timeout covers the initialize answer; the race also bounds a transport that
    // never settles (e.g. a TCP connect to a silent address).
    await Promise.race([
      client.connect(transport, { timeout: timeoutMs }),
      new Promise((_, reject) => {
        timer = setTimeout(() => reject(new ProbeTimeout(timeoutMs)), timeoutMs);
      }),
    ]);
    return {
      protocolVersion: transport.protocolVersion,
      serverInfo: client.getServerVersion(),
      instructions: client.getInstructions(),
      capabilities: client.getServerCapabilities(),
    };
  } catch (e) {
    if (e && e.code === -32001) throw new ProbeTimeout(timeoutMs); // SDK RequestTimeout
    throw e;
  } finally {
    clearTimeout(timer);
    await client.close().catch(() => {});
  }
}

/** @returns {Target} for a live registry record */
export function targetOf(record, source) {
  return { url: record.urls[0], instanceId: record.instanceId, source };
}

/**
 * Resolves the session's target from the options (SP_MCB_03_01).
 * @param {{url: string|null, instance: string|null, launch: boolean, app: string|null, registry: string, launchTimeout: number}} opts
 * @param {object} [deps]  test seams
 * @param {(url: string, o: {timeoutMs: number}) => Promise<Handshake>} [deps.probe]
 * @param {number} [deps.probeTimeoutMs]
 * @param {(pid: number) => boolean} [deps.isAlive]
 * @param {(opts: object, deps: object) => Promise<Resolution>} [deps.launch]
 * @returns {Promise<Resolution|null>} null: no instance (and launch not requested)
 * @throws {BridgeError} unreachable | unknown_instance | the launch errors
 */
export async function resolveTarget(opts, deps = {}) {
  const read = () => readRegistry(opts.registry, { isAlive: deps.isAlive }).records;

  if (opts.url) {
    try {
      const handshake = await (deps.probe || probe)(opts.url, {
        timeoutMs: deps.probeTimeoutMs ?? PROBE_TIMEOUT_MS,
      });
      return { target: { url: opts.url, source: 'explicit' }, record: null, handshake };
    } catch (e) {
      // The registry is read for the message only: no automatic fallback (C_MCB_03_05).
      throw new BridgeError('unreachable', `Cannot reach ${opts.url}: ${reasonOf(e)}. Live instances: ${liveIdsText(read())}`);
    }
  }

  const records = read();
  if (opts.instance) {
    const rec = findRecord(records, opts.instance);
    if (!rec) {
      throw new BridgeError('unknown_instance', `Unknown instance ${opts.instance}. Live instances: ${liveIdsText(records)}`);
    }
    return { target: targetOf(rec, 'registry'), record: rec };
  }
  const latest = latestRecord(records);
  if (latest) return { target: targetOf(latest, 'registry'), record: latest };
  if (opts.launch) return (deps.launch || launchApp)(opts, deps);
  return null;
}
