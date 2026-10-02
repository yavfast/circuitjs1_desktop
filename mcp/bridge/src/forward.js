// [SP_MCB_03_03] [SP_MCB_04_01] Forwarding to the target instance, and the session's target state.
//
// TargetConnection is one MCP client session (SDK Client over Streamable HTTP) to the target's
// endpoint. Requests go through `Client.request` with a pass-through result schema, so results
// come back as the JSON values the target sent: no output-schema validation, no field
// stripping. The initialize request is pinned to the newest revision of the target record's
// `protocolRevisions` that the SDK supports; SDK 1.31 `Client.connect` would otherwise always
// ask for its own latest revision.
//
// BridgeSession holds the session's target ([no target] ⇄ [target], SP_MCB_04_01): it resolves
// on demand (SP_MCB_03_01), switches on bridge_select / bridge_launch, clears the target on a
// connection failure, and reports every change to its listener (list_changed notifications).
// The stdio server and the CLI both use it. Nothing here writes to stdout.

import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js';
import { ErrorCode, McpError, SUPPORTED_PROTOCOL_VERSIONS } from '@modelcontextprotocol/sdk/types.js';
import * as z from 'zod';
import { BridgeError } from './errors.js';
import { launch as launchApp } from './launch.js';
import { readRegistry, liveIdsText } from './registry.js';
import { PROBE_TIMEOUT_MS, reasonOf, resolveTarget } from './target.js';
import { NAME, VERSION } from './version.js';

/** Accepts any result object unchanged (unknown keys are kept as they are). */
const AnyResult = z.looseObject({});

/** The text of the "no target" tool error (SP_MCB_02_01). */
export const NO_INSTANCE_TEXT = 'No CircuitJS1 instance. Start the app or call bridge_launch.';

/** Why a forwarded request failed. */
export class ForwardError extends Error {
  /**
   * @param {'timeout'|'gone'|'http'} kind
   * @param {string} message  spec text: `Timed out after <ms> ms` / `Instance gone: <url>`;
   *   `http`: an HTTP error status without a JSON-RPC error body (the target is kept)
   */
  constructor(kind, message) {
    super(message);
    this.name = 'ForwardError';
    this.kind = kind;
  }
}

// Socket-level failures: the endpoint is not there any more (refused, reset, closed mid-answer).
const GONE_CODES = new Set([
  'ECONNREFUSED', 'ECONNRESET', 'EPIPE', 'EHOSTUNREACH', 'ENETUNREACH', 'EHOSTDOWN', 'ENOTFOUND',
  'EAI_AGAIN', 'ETIMEDOUT', 'UND_ERR_SOCKET', 'UND_ERR_CLOSED', 'UND_ERR_CONNECT_TIMEOUT',
]);

/** True when `error` is a connection failure rather than an answer of the target. */
export function isConnectionFailure(error) {
  for (let e = error, depth = 0; e && depth < 5; e = e.cause, depth++) {
    if (typeof e.code === 'string' && GONE_CODES.has(e.code)) return true;
    if (Array.isArray(e.errors) && e.errors.some((x) => x && GONE_CODES.has(x.code))) return true;
    if (e instanceof TypeError && /fetch failed/i.test(e.message)) return true;
  }
  return false;
}

const HTTP_ERROR_PREFIX = /^Streamable HTTP error: Error POSTing to endpoint: ?/;

/**
 * An HTTP error status from the target (SDK StreamableHTTPError: status in `code`, response
 * body in the message). The app answers some JSON-RPC errors with HTTP 4xx and a JSON-RPC
 * error body (-32600 with 400, a too-large body with 413): that error passes through as the
 * target's own; any other body becomes a ForwardError.
 * @returns {Error|null} null when `error` is not an HTTP status error
 */
export function httpStatusError(error, url) {
  if (!error || !Number.isInteger(error.code) || error.code < 100 || error.code > 599) return null;
  const body = String(error.message || '').replace(HTTP_ERROR_PREFIX, '');
  try {
    const msg = JSON.parse(body);
    const rpc = msg && msg.error;
    if (rpc && Number.isInteger(rpc.code) && typeof rpc.message === 'string') {
      const e = new Error(rpc.message);
      e.code = rpc.code;
      if (rpc.data !== undefined) e.data = rpc.data;
      return e;
    }
  } catch (e) {
    // not JSON
  }
  const detail = body.replace(/\s+/g, ' ').trim().slice(0, 200);
  return new ForwardError('http', `${url} answered HTTP ${error.code}${detail ? `: ${detail}` : ''}`);
}

/** The target's own error message, without the "MCP error <code>: " prefix McpError adds. */
function rawMessage(error) {
  const m = String(error.message || '');
  const prefix = `MCP error ${error.code}: `;
  return m.startsWith(prefix) ? m.slice(prefix.length) : m;
}

/**
 * An error to throw from a host request handler that reaches the host as the target's own
 * JSON-RPC error: same code, message and data (the SDK sends `error.code/message/data`).
 */
export function passThroughError(error) {
  const e = new Error(rawMessage(error));
  e.code = error.code;
  if (error.data !== undefined) e.data = error.data;
  return e;
}

/**
 * The revision to ask the target for: the newest of the record's `protocolRevisions` (newest
 * first, SP_MCP_01_02) that the SDK supports; null without a record (the SDK's latest).
 * @throws {Error} when the record lists revisions but none is supported
 */
export function pinnedRevision(record) {
  const listed = record && Array.isArray(record.protocolRevisions) ? record.protocolRevisions : null;
  if (!listed || listed.length === 0) return null;
  const rev = listed.find((r) => SUPPORTED_PROTOCOL_VERSIONS.includes(r));
  if (!rev) throw new Error(`no common protocol revision (the instance serves ${listed.join(', ')})`);
  return rev;
}

/** Streamable HTTP client transport whose initialize request asks for a fixed revision. */
class PinnedTransport extends StreamableHTTPClientTransport {
  constructor(url, revision) {
    super(new URL(url));
    this._pinnedRevision = revision;
  }

  async send(message, options) {
    if (this._pinnedRevision && message && message.method === 'initialize' && message.params) {
      message = { ...message, params: { ...message.params, protocolVersion: this._pinnedRevision } };
    }
    return super.send(message, options);
  }
}

/** One MCP client session to a target endpoint. */
export class TargetConnection {
  /**
   * @param {import('./target.js').Resolution} resolution
   * @param {{timeoutMs: number}} o  forward timeout per request
   */
  constructor(resolution, o) {
    this.target = resolution.target;
    this.record = resolution.record || null;
    this.url = resolution.target.url;
    this.timeoutMs = o.timeoutMs;
    /** @type {import('./target.js').Handshake|null} */
    this.handshake = null;
    this._client = null;
    this._transport = null;
    this._inflight = 0;
    this._closeWhenIdle = false;
  }

  /**
   * Handshake, bounded at `timeoutMs` (3 s by default, SP_MCB_03_01).
   * @returns {Promise<import('./target.js').Handshake>}
   */
  async connect(timeoutMs = PROBE_TIMEOUT_MS) {
    const revision = pinnedRevision(this.record);
    const client = new Client({ name: NAME, version: VERSION }, { capabilities: {} });
    const transport = new PinnedTransport(this.url, revision);
    let timer;
    try {
      await Promise.race([
        client.connect(transport, { timeout: timeoutMs }),
        new Promise((_, reject) => {
          timer = setTimeout(() => reject(new Error(`no MCP answer within ${timeoutMs} ms`)), timeoutMs);
        }),
      ]);
    } catch (e) {
      await client.close().catch(() => {});
      if (e && e.code === ErrorCode.RequestTimeout) throw new Error(`no MCP answer within ${timeoutMs} ms`);
      throw e;
    } finally {
      clearTimeout(timer);
    }
    this._client = client;
    this._transport = transport;
    this.handshake = {
      protocolVersion: transport.protocolVersion,
      serverInfo: client.getServerVersion(),
      instructions: client.getInstructions(),
      capabilities: client.getServerCapabilities(),
    };
    return this.handshake;
  }

  /**
   * Forwards one request; the result is the target's JSON value, unchanged.
   * @param {string} method
   * @param {object|undefined} params
   * @param {{signal?: AbortSignal, timeoutMs?: number}} [o]
   * @throws {ForwardError} timeout | gone
   * @throws {Error} the target's JSON-RPC error, as passThroughError
   */
  async request(method, params, o = {}) {
    const timeoutMs = o.timeoutMs ?? this.timeoutMs;
    const req = params === undefined ? { method } : { method, params };
    this._inflight++;
    try {
      return await this._client.request(req, AnyResult, { timeout: timeoutMs, signal: o.signal });
    } catch (e) {
      if (e instanceof McpError && e.code === ErrorCode.RequestTimeout) {
        throw new ForwardError('timeout', `Timed out after ${timeoutMs} ms`);
      }
      if (isConnectionFailure(e)) throw new ForwardError('gone', `Instance gone: ${this.url}`);
      if (e instanceof McpError) throw passThroughError(e);
      const http = httpStatusError(e, this.url);
      if (http) throw http;
      throw e;
    } finally {
      this._inflight--;
      if (this._inflight === 0 && this._closeWhenIdle) this.close();
    }
  }

  /** Closes now: requests still in flight fail. */
  async close() {
    const client = this._client;
    this._client = null;
    if (client) await client.close().catch(() => {});
  }

  /** Closes once the requests in flight have settled (a switched-away target). */
  closeWhenIdle() {
    if (this._inflight === 0) return this.close();
    this._closeWhenIdle = true;
    return Promise.resolve();
  }
}

function sameTarget(a, b) {
  return Boolean(a && b) && a.url === b.url && (a.instanceId || null) === (b.instanceId || null);
}

/** The session's target: resolution, selection, connection failure (SP_MCB_04_01). */
export class BridgeSession {
  /**
   * @param {import('./options.js').Options} opts
   * @param {object} [deps]  test seams, passed on to resolveTarget / launch
   * @param {(line: string) => void} [deps.log]  diagnostics (stderr by default)
   * @param {number} [deps.connectTimeoutMs]
   */
  constructor(opts, deps = {}) {
    this.opts = opts;
    this.deps = deps;
    this.log = deps.log || ((line) => process.stderr.write(`${NAME}: ${line}\n`));
    /** @type {TargetConnection|null} */
    this.conn = null;
    this._pending = null;
    this._launching = null;
    this._listeners = [];
  }

  /** @returns {import('./target.js').Target|null} */
  get target() {
    return this.conn ? this.conn.target : null;
  }

  /** Registers `fn(target|null)`, called after every target change. */
  onChange(fn) {
    this._listeners.push(fn);
  }

  _changed() {
    const t = this.target;
    this.log(t ? `target ${t.url} (${t.source}${t.instanceId ? `, instance ${t.instanceId}` : ''})` : 'no target');
    for (const fn of this._listeners) {
      try {
        fn(t);
      } catch (e) {
        this.log(`listener failed: ${e && e.message}`);
      }
    }
  }

  /** Live registry records (stale ones are deleted on the way). */
  liveRecords() {
    return readRegistry(this.opts.registry, { isAlive: this.deps.isAlive }).records;
  }

  /**
   * Connects to a resolution's endpoint. A failed handshake is the "Cannot reach" error.
   * @returns {Promise<TargetConnection>}
   */
  async connect(resolution) {
    const conn = new TargetConnection(resolution, { timeoutMs: this.opts.timeout });
    try {
      await conn.connect(this.deps.connectTimeoutMs ?? PROBE_TIMEOUT_MS);
    } catch (e) {
      throw new BridgeError(
        'unreachable',
        `Cannot reach ${resolution.target.url}: ${reasonOf(e)}. Live instances: ${liveIdsText(this.liveRecords())}`,
      );
    }
    return conn;
  }

  /** Makes `conn` the target (closing the previous one); notifies when the target changed. */
  async adopt(conn) {
    const old = this.conn;
    this.conn = conn;
    // Calls still running on the old target finish there (the target is switched, not gone).
    if (old && old !== conn) await old.closeWhenIdle();
    if (!sameTarget(old && old.target, conn.target)) this._changed();
    return conn;
  }

  /**
   * The current connection, resolving one when there is none (SP_MCB_03_01). Concurrent callers
   * share one resolution; a caller allowed to launch that finds a resolution without launch in
   * flight resolves again, with launch, when that one found nothing.
   * @param {{launch?: boolean}} [o]  launch: allow the --launch step (default: the option)
   * @returns {Promise<TargetConnection|null>} null: no instance
   * @throws {BridgeError}
   */
  async ensure(o = {}) {
    const launch = o.launch ?? this.opts.launch;
    if (this.conn) return this.conn;
    const pending = this._pending;
    if (pending) {
      const conn = await pending.promise;
      if (conn || !launch || pending.launch) return conn;
      return this.ensure(o);
    }
    const entry = { launch, promise: null };
    entry.promise = this._resolve(launch).finally(() => {
      if (this._pending === entry) this._pending = null;
    });
    this._pending = entry;
    return entry.promise;
  }

  /**
   * Starts the app (SP_MCB_03_02). One launch at a time per session: concurrent callers (the
   * --launch resolution, bridge_launch) share it, so the app is started once.
   * @returns {Promise<import('./target.js').Resolution>}
   */
  launchOnce() {
    if (!this._launching) {
      this._launching = Promise.resolve()
        .then(() => (this.deps.launch || launchApp)(this.opts, this.deps))
        .finally(() => {
          this._launching = null;
        });
    }
    return this._launching;
  }

  async _resolve(launch) {
    let probed = null;
    // The explicit-URL probe is this connection's own handshake, so it is not done twice.
    const probe = async (url) => {
      const c = new TargetConnection({ target: { url, source: 'explicit' }, record: null }, { timeoutMs: this.opts.timeout });
      await c.connect(this.deps.probeTimeoutMs ?? PROBE_TIMEOUT_MS);
      probed = c;
      return c.handshake;
    };
    const resolution = await resolveTarget(
      { ...this.opts, launch },
      { ...this.deps, probe, launch: () => this.launchOnce() },
    );
    if (!resolution) return null;
    const conn = probed || (await this.connect(resolution));
    if (this.conn) {
      // Another path (bridge_select, bridge_launch) set a target meanwhile: keep that one.
      if (conn !== this.conn) await conn.close();
      return this.conn;
    }
    return this.adopt(conn);
  }

  /** Clears the target after a connection failure of `conn` (if it is still the target). */
  async drop(conn) {
    if (!conn || this.conn !== conn) return;
    this.conn = null;
    await conn.close();
    this._changed();
  }

  /**
   * Forwards a request to the current connection; a connection failure clears the target.
   * @throws {ForwardError|Error}
   */
  async forward(conn, method, params, o) {
    try {
      return await conn.request(method, params, o);
    } catch (e) {
      if (e instanceof ForwardError && e.kind === 'gone') await this.drop(conn);
      throw e;
    }
  }

  async close() {
    const conn = this.conn;
    this.conn = null;
    if (conn) await conn.close();
  }
}
