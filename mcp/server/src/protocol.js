'use strict';
// [PL_MCP_DEC_01] Protocol layer: the MCP SDK 1.x core `Server` behind our own transport, which
// turns one HTTP request into one JSON-RPC message and its single JSON response (SP_MCP_02_01).
// The server keeps no protocol session: every HTTP client shares the one `Server`, and the
// request ids of concurrent clients are replaced by internal ids so that equal client ids
// cannot collide inside the SDK. Every message is checked against the SDK's own JSON-RPC and
// request schemas before it reaches the SDK, because the SDK drops a message it cannot parse
// without answering, and every pending request has a backstop timeout, so each request gets
// exactly one reply.

const { Server } = require('@modelcontextprotocol/sdk/server/index.js');
const {
  isJSONRPCRequest,
  isJSONRPCNotification,
  InitializeRequestSchema,
  PingRequestSchema,
  ListToolsRequestSchema,
  CallToolRequestSchema,
  ListResourcesRequestSchema,
  ListResourceTemplatesRequestSchema,
  ReadResourceRequestSchema,
  McpError,
  ErrorCode,
} = require('@modelcontextprotocol/sdk/types.js');

// [SP_MCP_02_01] Served initialize-based revisions, newest first. The stateless 2026-07-28
// revision is not served in-app (plan backlog).
const PROTOCOL_REVISIONS = ['2025-11-25', '2025-06-18'];
const LATEST_REVISION = PROTOCOL_REVISIONS[0];
// A request without the MCP-Protocol-Version header is treated as this revision.
const DEFAULT_HEADER_REVISION = '2025-06-18';

// The prefix McpError puts in front of its message (stripped once from outgoing errors).
const SDK_ERROR_PREFIX = /^MCP error -?\d+: /;

// JSON-RPC error code for an unknown resource URI (SP_MCP_02_03).
const RESOURCE_NOT_FOUND = -32002;

// Backstop for a request the SDK never answers: longer than the longest legitimate call
// (circuit_run with its 120 s budget cap, queued behind other slices) and than the agent
// client's own asynchronous timeout (agent.js ASYNC_TIMEOUT_MS).
const PENDING_TIMEOUT_MS = 200000;

// Notifications not passed to the SDK. Cancellation is not supported (single JSON responses,
// SP_MCP_DEC_02): forwarding it would let one client abort another client's request, which
// then would never be answered.
const DROPPED_NOTIFICATIONS = new Set(['notifications/cancelled']);

function errorResponse(id, code, message) {
  return { jsonrpc: '2.0', id: id === undefined ? null : id, error: { code, message } };
}

/** Cuts client-supplied text echoed in error messages (object keys, tool names, URIs). */
function clip(text, max) {
  const s = String(text);
  return s.length > max ? s.slice(0, max) + '…' : s;
}

/** "params.protocolVersion (expected string, received undefined); ..." for the first issues. */
function describeIssues(error) {
  const issues = (error && error.issues) || [];
  const text = issues.slice(0, 3).map((i) => {
    const where = ['params'].concat((i.path || []).slice(1).map((p) => clip(p, 64))).join('.');
    return `${(i.path || []).length ? where : 'params'} (${clip(String(i.message || 'invalid').replace(/^Invalid input: /, ''), 120)})`;
  }).join('; ') || 'invalid params';
  return clip(text, 300);
}

/**
 * The SDK transport. The HTTP layer calls `dispatch(message, requestInfo)`; the SDK answers
 * through `send`, which resolves the pending HTTP request of that message.
 */
class JsonResponseTransport {
  constructor() {
    this.pending = new Map(); // internal id -> {clientId, resolve}
    this.nextId = 1;
    this.closed = false;
    this.onmessage = undefined;
    this.onclose = undefined;
    this.onerror = undefined;
  }

  async start() {}

  async close() {
    if (this.closed) return;
    this.closed = true;
    for (const p of this.pending.values()) p.resolve(null);
    this.pending.clear();
    if (this.onclose) this.onclose();
  }

  async send(message) {
    // Only responses reach the client: the server sends no requests or notifications of its own
    // (no stream; list-changed is false; logging and progress are not used).
    if (!message || (message.result === undefined && message.error === undefined)) return;
    const p = this.pending.get(message.id);
    if (!p) return;
    this.pending.delete(message.id);
    const { jsonrpc, id, ...rest } = message; // eslint-disable-line no-unused-vars
    if (rest.error && typeof rest.error.message === 'string') {
      // An McpError thrown by a handler carries "MCP error <code>: " in its message, and SDK
      // clients add that prefix again when they show the error: send the bare message.
      rest.error = { ...rest.error, message: rest.error.message.replace(SDK_ERROR_PREFIX, '') };
    }
    p.resolve({ jsonrpc: '2.0', id: p.clientId, ...rest });
  }

  /**
   * @param message a JSON-RPC request or notification object
   * @param requestInfo {headers} of the HTTP request
   * @returns Promise of the JSON-RPC response for a request, or null for a notification
   */
  dispatch(message, requestInfo) {
    const isRequest = message && message.id !== undefined;
    if (this.closed || !this.onmessage) {
      return Promise.resolve(isRequest ? errorResponse(message.id, ErrorCode.InternalError, 'Server is shutting down') : null);
    }
    const extra = { requestInfo };
    if (!isRequest) {
      // the HTTP layer answers 202 whatever happens; an invalid or dropped notification is ignored
      if (isJSONRPCNotification(message) && !DROPPED_NOTIFICATIONS.has(message.method)) {
        this.onmessage(message, extra);
      }
      return Promise.resolve(null);
    }
    if (!isJSONRPCRequest(message)) {
      // the SDK would drop it without an answer (extra top-level keys, params not an object, ...)
      return Promise.resolve(errorResponse(message.id, ErrorCode.InvalidRequest,
        'Invalid Request: not a JSON-RPC 2.0 request object (jsonrpc, id, method, optional object params; no other keys)'));
    }
    const internalId = 'mcp-' + this.nextId++;
    return new Promise((resolve) => {
      const timer = setTimeout(() => {
        if (!this.pending.has(internalId)) return;
        this.pending.delete(internalId);
        resolve(errorResponse(message.id, ErrorCode.InternalError, `Internal error: no response within ${PENDING_TIMEOUT_MS / 1000} s`));
      }, PENDING_TIMEOUT_MS);
      this.pending.set(internalId, {
        clientId: message.id,
        resolve: (response) => {
          clearTimeout(timer);
          resolve(response);
        },
      });
      this.onmessage({ ...message, id: internalId }, extra);
    });
  }
}

/** The empty catalogue of Phase 1; Phase 2 passes the tool table and the resources. */
const EMPTY_TOOLS = {
  list: () => [],
  call: (name) => {
    throw new McpError(ErrorCode.InvalidParams, `Unknown tool: ${clip(name, 64)}`);
  },
};
const EMPTY_RESOURCES = {
  list: () => [],
  templates: () => [],
  read: (uri) => {
    throw new McpError(RESOURCE_NOT_FOUND, `Resource not found: ${clip(uri, 200)}`);
  },
};

/**
 * Creates the SDK server and its transport.
 * @param opts {appVersion, instructions, tools?, resources?, onToolCall?}
 *   tools: {list(): Tool[], call(name, args, extra): Promise<CallToolResult>}
 *   resources: {list(): Resource[], templates(): ResourceTemplate[], read(uri): Promise<ReadResourceResult>}
 *   onToolCall(): called once per handled tools/call request (the info dialog's counter)
 * @returns {{server, transport, dispatch(message, requestInfo): Promise<object|null>, close(): Promise<void>}}
 */
function createProtocol(opts) {
  const tools = opts.tools || EMPTY_TOOLS;
  const resources = opts.resources || EMPTY_RESOURCES;
  const server = new Server(
    { name: 'circuitjs1', version: opts.appVersion },
    {
      capabilities: {
        tools: { listChanged: false },
        resources: { listChanged: false, subscribe: false },
      },
      instructions: opts.instructions,
    },
  );
  const transport = new JsonResponseTransport();

  server.setRequestHandler(ListToolsRequestSchema, async () => ({ tools: await tools.list() }));
  server.setRequestHandler(CallToolRequestSchema, async (request, extra) => {
    try {
      return await tools.call(request.params.name, request.params.arguments || {}, extra);
    } finally {
      if (opts.onToolCall) opts.onToolCall();
    }
  });
  server.setRequestHandler(ListResourcesRequestSchema, async () => ({ resources: await resources.list() }));
  server.setRequestHandler(ListResourceTemplatesRequestSchema, async () => ({ resourceTemplates: await resources.templates() }));
  server.setRequestHandler(ReadResourceRequestSchema, async (request) => resources.read(request.params.uri));

  // Request schemas of the methods served, checked before the SDK sees a request: the SDK
  // answers a failed parse with -32603 and the raw validation text (SP_MCP_03_03 wants -32602).
  const paramSchemas = new Map([
    ['initialize', InitializeRequestSchema],
    ['ping', PingRequestSchema],
    ['tools/list', ListToolsRequestSchema],
    ['tools/call', CallToolRequestSchema],
    ['resources/list', ListResourcesRequestSchema],
    ['resources/templates/list', ListResourceTemplatesRequestSchema],
    ['resources/read', ReadResourceRequestSchema],
  ]);
  const checkParams = (message) => {
    const schema = paramSchemas.get(message.method);
    if (!schema) return null; // unknown methods: the SDK answers -32601
    const parsed = schema.safeParse({ method: message.method, params: message.params });
    if (parsed.success) return null;
    return errorResponse(message.id, ErrorCode.InvalidParams, `Invalid params for ${message.method}: ${describeIssues(parsed.error)}`);
  };

  // [SP_MCP_02_01] initialize answers with the requested revision when it is served, else with
  // the latest. The SDK itself would also accept its older revisions, so the (validated) request
  // is normalised before it reaches the SDK's initialize handler.
  const normaliseInitialize = (message) => {
    if (message.method !== 'initialize' || !message.params) return message;
    const requested = message.params.protocolVersion;
    if (PROTOCOL_REVISIONS.includes(requested)) return message;
    return { ...message, params: { ...message.params, protocolVersion: LATEST_REVISION } };
  };

  const connected = server.connect(transport);

  return {
    server,
    transport,
    async dispatch(message, requestInfo) {
      await connected;
      if (message && message.id !== undefined && isJSONRPCRequest(message)) {
        const invalid = checkParams(message);
        if (invalid) return invalid;
        message = normaliseInitialize(message);
      }
      return transport.dispatch(message, requestInfo);
    },
    async close() {
      await connected;
      await server.close();
    },
  };
}

module.exports = {
  createProtocol,
  PROTOCOL_REVISIONS,
  LATEST_REVISION,
  DEFAULT_HEADER_REVISION,
  RESOURCE_NOT_FOUND,
  PENDING_TIMEOUT_MS,
  McpError,
  ErrorCode,
  clip,
};
