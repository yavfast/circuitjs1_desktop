'use strict';
// [PL_MCP_P1] In-app MCP server entry (C_MCP, SP_MCP). Bundled by mcp/server/build.js into
// war/scripts/mcp-server.js, which circuitjs.html loads with a <script> tag in the page context
// (PL_MCP_DEC_01). The bundle only registers `window.CircuitJS1Mcp`; the app's start-up calls
// `start` right after the Agent API export (AgentJsBridge.startMcpServer, SP_MCP_02_05).
//
//   CircuitJS1Mcp.start(agentBridge, prefs, onStatus)  prefs: {enabled, port, portRange, host}
//   CircuitJS1Mcp.stop()                                closes the listener, deletes the record
//   CircuitJS1Mcp.status()                              the status object passed to onStatus
//
// Node modules are required lazily: a plain browser build loads the same bundle and must get
// `disabled` without an error. No `require('crypto')` anywhere: the release runtime's Node has no
// OpenSSL (Phase 0); the server needs no random ids (no protocol sessions).

const { createProtocol, PROTOCOL_REVISIONS } = require('./protocol.js');
const { createRequestHandler, MCP_PATH } = require('./http.js');
const { createAgentClient } = require('./agent.js');
const { createTools } = require('./tools.js');
const { createResources } = require('./resources.js');
const registry = require('./registry.js');

// [SP_MCP_06_01] Version of the tool and resource contract; the skill names the one it supports.
const TOOLS_VERSION = '1.0';

// Receive timeouts of the HTTP server (incomplete headers or body)
const HEADERS_TIMEOUT_MS = 10000;
const REQUEST_TIMEOUT_MS = 60000;

// [SP_MCP_01_01] defaults
const DEFAULT_PREFS = { enabled: true, port: 7311, portRange: 20, host: '127.0.0.1' };

// [SP_MCP_02_01] server instructions: one paragraph for the agent host.
const INSTRUCTIONS =
  'CircuitJS1 circuit simulator (one running app window). Coordinates are grid cells ' +
  '(1 cell = 16 px, on a half-cell lattice); build a circuit with circuit_import or circuit_edit, ' +
  'then verify it in this loop: read the connectivity report, run the simulation (circuit_run), ' +
  'measure (circuit_read or the run probes) and fix what the issues name. Tools act on the ' +
  'active document unless `doc` names another. The agent skill for this server is ' +
  `circuitjs-circuits; toolsVersion ${TOOLS_VERSION}.`;

let state = { state: 'stopped', reason: null };
let onStatusCb = null;
let httpServer = null;
let protocol = null;
let recordFile = null;
let unloadHandler = null;
const sockets = new Set();

// The page context of NW.js has Node globals on the window. (A bare `typeof require` would always
// be true here: esbuild replaces `require` by its own shim.)
function isDesktopRuntime() {
  const g = typeof globalThis !== 'undefined' ? globalThis : window;
  const proc = g.process;
  return typeof g.require === 'function' && !!(proc && proc.versions && proc.versions.node);
}

function emit(patch) {
  state = Object.assign({}, state, patch);
  if (onStatusCb) {
    try {
      onStatusCb(status());
    } catch (e) {
      // the app side failed to take the status; the server itself is unaffected
    }
  }
}

/** A copy of the current status: {state, reason, instanceId, host, port, urls, toolCalls, ...}. */
function status() {
  return JSON.parse(JSON.stringify(state));
}

function intIn(v, lo, hi) {
  const n = typeof v === 'string' && v.trim() !== '' ? Number(v) : v;
  return Number.isInteger(n) && n >= lo && n <= hi ? n : undefined;
}

/** Applies the defaults of SP_MCP_01_01 to missing or out-of-range values (the app validates first). */
function normalisePrefs(prefs) {
  const p = prefs || {};
  const port = intIn(p.port, 1024, 65535) ?? DEFAULT_PREFS.port;
  let portRange = intIn(p.portRange, 1, 100) ?? DEFAULT_PREFS.portRange;
  if (port + portRange - 1 > 65535) portRange = 65535 - port + 1;
  const host = typeof p.host === 'string' && p.host.trim() !== '' ? p.host.trim() : DEFAULT_PREFS.host;
  return { enabled: p.enabled !== false && p.enabled !== 'false', port, portRange, host };
}

function isLoopbackHost(host) {
  return host === 'localhost' || host === '::1' || /^127\./.test(host);
}

function isWildcardHost(host) {
  return host === '0.0.0.0' || host === '::';
}

/**
 * [SP_MCP_01_02] `http://127.0.0.1:<port>/mcp` first, then one URL per non-internal IPv4
 * address when the host is not loopback. A host bound to one specific address is reachable only
 * there, so its own URL replaces both.
 */
function urlsFor(host, port) {
  const url = (h) => `http://${h.includes(':') ? '[' + h + ']' : h}:${port}${MCP_PATH}`;
  if (isLoopbackHost(host)) {
    return [host === '::1' ? url('::1') : url('127.0.0.1')];
  }
  if (!isWildcardHost(host)) {
    return [url(host)];
  }
  const urls = [url('127.0.0.1')];
  const ifaces = require('os').networkInterfaces();
  for (const name of Object.keys(ifaces)) {
    for (const a of ifaces[name] || []) {
      // Node 18.0–18.3 report family as a number
      if ((a.family === 'IPv4' || a.family === 4) && !a.internal && !urls.includes(url(a.address))) {
        urls.push(url(a.address));
      }
    }
  }
  return urls;
}

function appVersion() {
  try {
    const v = nw.App.manifest.version; // eslint-disable-line no-undef
    if (typeof v === 'string' && v) return v;
  } catch (e) {
    // no NW manifest (devmode package without a version)
  }
  return 'dev';
}

function windowTitle() {
  try {
    return String(document.title || '');
  } catch (e) {
    return '';
  }
}

/** Tries to listen on one port: resolves the server, or rejects with the listen error. */
function listenOn(handler, host, port) {
  return new Promise((resolve, reject) => {
    const server = require('http').createServer(handler);
    // incomplete requests must not hold a socket for ever: headers within 10 s, the whole body
    // (at most 16 MB) within 60 s; a long-running response is not limited by these
    server.headersTimeout = HEADERS_TIMEOUT_MS;
    server.requestTimeout = REQUEST_TIMEOUT_MS;
    const onError = (e) => {
      server.removeListener('listening', onListening);
      reject(e);
    };
    const onListening = () => {
      server.removeListener('error', onError);
      resolve(server);
    };
    server.once('error', onError);
    server.once('listening', onListening);
    server.listen(port, host);
  });
}

/**
 * [SP_MCP_02_05] startServer: disabled without the desktop runtime or when the preference is
 * off; otherwise the first free port of the range, then the instance record, then `listening`.
 */
async function start(agentBridge, prefs, onStatus) {
  if (state.state === 'starting' || state.state === 'listening') return status();
  onStatusCb = typeof onStatus === 'function' ? onStatus : null;
  const p = normalisePrefs(prefs);
  const base = { protocolRevisions: PROTOCOL_REVISIONS.slice(), toolsVersion: TOOLS_VERSION, toolCalls: 0 };
  if (!isDesktopRuntime()) {
    emit(Object.assign(base, { state: 'disabled', reason: 'no desktop runtime' }));
    return status();
  }
  if (!p.enabled) {
    emit(Object.assign(base, { state: 'disabled', reason: 'disabled in preferences' }));
    return status();
  }
  emit(Object.assign(base, { state: 'starting', reason: null, host: p.host, port: null, urls: [], instanceId: null }));

  // [SP_MCP_02_02] [SP_MCP_02_03] the tool table and the resources over the Agent API client
  const agent = createAgentClient(agentBridge);
  protocol = createProtocol({
    appVersion: appVersion(),
    instructions: INSTRUCTIONS,
    tools: createTools(agent),
    resources: createResources({ agent }),
    onToolCall: () => emit({ toolCalls: (state.toolCalls || 0) + 1 }),
  });
  const handler = createRequestHandler({
    dispatch: (msg, info) => protocol.dispatch(msg, info),
    onError: (e) => agent.reportError('MCP server: ' + (e && e.stack ? e.stack : e)),
  });

  let server = null;
  let lastError = null;
  for (let port = p.port; port <= p.port + p.portRange - 1; port++) {
    try {
      server = await listenOn(handler, p.host, port);
      break;
    } catch (e) {
      lastError = e;
      // a busy (or reserved) port: try the next one; any other error ends the attempt
      if (!e || (e.code !== 'EADDRINUSE' && e.code !== 'EACCES')) break;
    }
  }
  if (!server) {
    const busy = lastError && (lastError.code === 'EADDRINUSE' || lastError.code === 'EACCES');
    const reason = busy
      ? `no free port in range ${p.port}..${p.port + p.portRange - 1}`
      : `listen on ${p.host} failed: ${lastError && lastError.message ? lastError.message : lastError}`;
    await protocol.close().catch(() => {});
    protocol = null;
    emit({ state: 'failed', reason });
    return status();
  }
  httpServer = server;
  server.on('connection', (socket) => {
    sockets.add(socket);
    socket.on('close', () => sockets.delete(socket));
  });

  const port = server.address().port;
  const startedAtMs = Date.now();
  const record = {
    instanceId: `${process.pid}-${startedAtMs}`,
    pid: process.pid,
    port,
    host: p.host,
    urls: urlsFor(p.host, port),
    appVersion: appVersion(),
    startedAt: new Date(startedAtMs).toISOString(),
    title: windowTitle(),
    protocolRevisions: PROTOCOL_REVISIONS.slice(),
    toolsVersion: TOOLS_VERSION,
  };
  let registryError = null;
  let removedRecords = [];
  try {
    const dir = registry.instanceDir();
    removedRecords = registry.removeDeadRecords(dir);
    recordFile = registry.writeRecord(dir, record);
  } catch (e) {
    registryError = e && e.message ? e.message : String(e);
  }

  // [SP_MCP_04_01] listening -> stopped on window unload (window-manager close and File -> Exit
  // both fire it; Node's process 'exit' fires on neither — Phase 0)
  if (!unloadHandler && typeof window !== 'undefined') {
    unloadHandler = () => stop();
    window.addEventListener('unload', unloadHandler);
  }
  emit({
    state: 'listening', reason: null, instanceId: record.instanceId, port, urls: record.urls,
    recordFile, registryError, removedRecords,
  });
  return status();
}

/**
 * [SP_MCP_02_05] stopServer: closes the listener and deletes the own instance record
 * (synchronously, so it completes inside an unload handler). Safe to call more than once.
 */
function stop() {
  const wasRunning = !!httpServer || !!recordFile;
  if (httpServer) {
    try {
      httpServer.close();
    } catch (e) {
      // already closed
    }
    for (const s of sockets) {
      try {
        s.destroy();
      } catch (e) {
        // already gone
      }
    }
    sockets.clear();
    httpServer = null;
  }
  if (recordFile) {
    registry.removeRecord(recordFile);
    recordFile = null;
  }
  if (protocol) {
    protocol.close().catch(() => {});
    protocol = null;
  }
  if (unloadHandler && typeof window !== 'undefined') {
    window.removeEventListener('unload', unloadHandler);
    unloadHandler = null;
  }
  if (wasRunning) emit({ state: 'stopped', reason: null, recordFile: null });
  return status();
}

const api = { start, stop, status, TOOLS_VERSION, PROTOCOL_REVISIONS: PROTOCOL_REVISIONS.slice() };
if (typeof window !== 'undefined') {
  window.CircuitJS1Mcp = api;
}
module.exports = api;
