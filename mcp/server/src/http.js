'use strict';
// [SP_MCP_02_01] [SP_MCP_03_01] [SP_MCP_03_03] Streamable HTTP endpoint with JSON responses:
// POST /mcp carries one JSON-RPC message and gets one application/json reply; there is no
// server-initiated stream (GET and DELETE answer 405); notifications answer 202 with no body.
// Before anything else a foreign Origin is rejected with 403 (the only access rule —
// C_MCP_DEC_02), then an unserved MCP-Protocol-Version header with 400.

const { PROTOCOL_REVISIONS, DEFAULT_HEADER_REVISION } = require('./protocol.js');

const MCP_PATH = '/mcp';
// Largest accepted request body (an AgentCircuit import of a big circuit stays far below).
const MAX_BODY_BYTES = 16 * 1024 * 1024;

const LOCAL_ORIGIN_HOSTS = new Set(['localhost', '127.0.0.1', '[::1]']);

/**
 * [SP_MCP_03_01] No Origin, or an Origin whose host is localhost, 127.0.0.1 or [::1] (any port)
 * is accepted; any other value (also "null" and unparseable ones) is foreign.
 */
function originAllowed(origin) {
  if (origin === undefined) return true;
  try {
    return LOCAL_ORIGIN_HOSTS.has(new URL(origin).hostname);
  } catch (e) {
    return false;
  }
}

// Node's Buffer, required lazily: the bundle also loads in a plain browser build.
function nodeBuffer() {
  return require('buffer').Buffer;
}

function jsonRpcError(id, code, message) {
  return { jsonrpc: '2.0', id: id === undefined ? null : id, error: { code, message } };
}

function isObject(v) {
  return v !== null && typeof v === 'object' && !Array.isArray(v);
}

/** Request: method + id; notification: method, no id; response: id + result/error, no method. */
function classify(msg) {
  if (!isObject(msg) || msg.jsonrpc !== '2.0') return 'invalid';
  const hasId = msg.id !== undefined;
  if (hasId && msg.id !== null && typeof msg.id !== 'string' && typeof msg.id !== 'number') return 'invalid';
  if (typeof msg.method === 'string') {
    if (msg.params !== undefined && (msg.params === null || typeof msg.params !== 'object')) return 'invalid';
    return hasId && msg.id !== null ? 'request' : hasId ? 'invalid' : 'notification';
  }
  if (msg.method === undefined && hasId && (msg.result !== undefined || msg.error !== undefined)) return 'response';
  return 'invalid';
}

/**
 * @param opts {dispatch(message, requestInfo): Promise<object|null>, onError?(error)}
 * @returns the `request` listener of an http.Server
 */
function createRequestHandler(opts) {
  const corsHeaders = (origin) => (origin === undefined ? {} : {
    'Access-Control-Allow-Origin': origin,
    'Access-Control-Allow-Methods': 'POST, GET, DELETE, OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type, Accept, Authorization, MCP-Protocol-Version, Mcp-Session-Id, Last-Event-ID',
    Vary: 'Origin',
  });

  const reply = (res, status, body, headers) => {
    if (res.writableEnded) return;
    const h = Object.assign({}, headers || {});
    let payload = '';
    if (body !== undefined) {
      payload = JSON.stringify(body);
      h['Content-Type'] = 'application/json';
    }
    h['Content-Length'] = nodeBuffer().byteLength(payload);
    res.writeHead(status, h);
    res.end(payload);
  };

  return function onRequest(req, res) {
    const origin = req.headers.origin;
    // [SP_MCP_03_01] foreign Origin: 403, empty body, before any other check
    if (!originAllowed(origin)) {
      reply(res, 403);
      req.resume();
      return;
    }
    const cors = corsHeaders(origin);
    const url = (req.url || '').split('?')[0];
    if (url !== MCP_PATH) {
      reply(res, 404, jsonRpcError(null, -32000, `Not found: use ${MCP_PATH}`), cors);
      req.resume();
      return;
    }
    if (req.method === 'OPTIONS') {
      reply(res, 204, undefined, cors);
      req.resume();
      return;
    }
    if (req.method !== 'POST') {
      // [SP_MCP_02_01] no server-initiated stream and no session to delete
      reply(res, 405, jsonRpcError(null, -32000, 'Method not allowed: this endpoint answers POST with JSON only'),
        Object.assign({ Allow: 'POST, OPTIONS' }, cors));
      req.resume();
      return;
    }
    // [SP_MCP_02_01] version header: absent means 2025-06-18; an unserved revision is 400 so
    // that dual-era clients (Claude Code probing server/discover) fall back to initialize
    const revisionHeader = req.headers['mcp-protocol-version'];
    const revision = revisionHeader === undefined ? DEFAULT_HEADER_REVISION : String(revisionHeader).trim();
    if (!PROTOCOL_REVISIONS.includes(revision)) {
      reply(res, 400, jsonRpcError(null, -32600,
        `Unsupported protocol version: ${revision} (supported: ${PROTOCOL_REVISIONS.join(', ')})`), cors);
      req.resume();
      return;
    }

    // over the limit: 413, then the connection is closed instead of reading the rest
    const rejectTooLarge = () => {
      res.on('finish', () => req.destroy());
      reply(res, 413, jsonRpcError(null, -32600, `Request body over ${MAX_BODY_BYTES} bytes`),
        Object.assign({ Connection: 'close' }, cors));
    };
    const declared = Number(req.headers['content-length']);
    if (Number.isFinite(declared) && declared > MAX_BODY_BYTES) {
      rejectTooLarge();
      return;
    }
    const chunks = [];
    let size = 0;
    let tooLarge = false;
    req.on('data', (chunk) => {
      if (tooLarge) return;
      size += chunk.length;
      if (size > MAX_BODY_BYTES) {
        tooLarge = true;
        chunks.length = 0;
        rejectTooLarge();
        return;
      }
      chunks.push(chunk);
    });
    req.on('error', () => {});
    req.on('end', () => {
      if (tooLarge) return;
      let msg;
      try {
        msg = JSON.parse(nodeBuffer().concat(chunks).toString('utf8'));
      } catch (e) {
        reply(res, 400, jsonRpcError(null, -32700, 'Parse error: the body is not JSON'), cors);
        return;
      }
      const kind = classify(msg);
      if (kind === 'invalid') {
        // one JSON-RPC message per request: a batch array is not a request object either
        // JSON-RPC: the id is null when it cannot be read as a string or number
        const id = isObject(msg) && (typeof msg.id === 'string' || typeof msg.id === 'number') ? msg.id : null;
        reply(res, 400, jsonRpcError(id, -32600,
          'Invalid Request: expected one JSON-RPC 2.0 request or notification object'), cors);
        return;
      }
      if (kind === 'response') {
        // the server sends no requests, so a client response has nothing to answer
        reply(res, 202, undefined, cors);
        return;
      }
      // [SP_MCP_02_01] session headers are ignored; no Mcp-Session-Id is issued
      const requestInfo = { headers: req.headers };
      let result;
      try {
        result = opts.dispatch(msg, requestInfo);
      } catch (e) {
        result = Promise.reject(e);
      }
      if (kind === 'notification') {
        Promise.resolve(result).catch((e) => opts.onError && opts.onError(e));
        reply(res, 202, undefined, cors);
        return;
      }
      Promise.resolve(result).then((response) => {
        if (response) {
          // a message that is not a valid request object (as the SDK parses it) is a 400 too
          const invalid = response.error && response.error.code === -32600;
          reply(res, invalid ? 400 : 200, response, cors);
        } else {
          reply(res, 503, jsonRpcError(msg.id, -32603, 'Server is shutting down'), cors);
        }
      }, (e) => {
        if (opts.onError) opts.onError(e);
        reply(res, 500, jsonRpcError(msg.id, -32603, 'Internal error: ' + (e && e.message ? e.message : e)), cors);
      });
    });
  };
}

module.exports = { createRequestHandler, originAllowed, classify, MCP_PATH };
