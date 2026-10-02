// [SP_MCB_02_01] Stdio server mode: an MCP server on stdin/stdout for the host, forwarding to
// the session's target. The host side runs the SDK `Server` over `StdioServerTransport` and
// serves the newest revision that SDK supports; the target side is a BridgeSession.
//
// Stdout carries the MCP stream only. Diagnostics go to stderr (the session's log).

import { Server } from '@modelcontextprotocol/sdk/server/index.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import {
  CallToolRequestSchema,
  ErrorCode,
  InitializeRequestSchema,
  ListResourcesRequestSchema,
  ListResourceTemplatesRequestSchema,
  ListToolsRequestSchema,
  McpError,
  ReadResourceRequestSchema,
} from '@modelcontextprotocol/sdk/types.js';
import { BRIDGE_SENTENCE, BRIDGE_TOOLS, callBridgeTool, isBridgeTool } from './bridge-tools.js';
import { BridgeError } from './errors.js';
import { BridgeSession, ForwardError, NO_INSTANCE_TEXT } from './forward.js';
import { NAME, VERSION } from './version.js';

/** JSON-RPC error code for an unknown resource URI (SP_MCP_02_03). */
const RESOURCE_NOT_FOUND = -32002;

// The base Protocol's handler registration. The SDK `Server` wraps a tools/call handler with a
// zod parse of the result, which rebuilds content blocks from their schemas; forwarded results
// must reach the host unchanged (SP_MCB_03_03), so tools/call is registered on the base.
const registerRaw = Object.getPrototypeOf(Server.prototype).setRequestHandler;

function toolError(text) {
  return { content: [{ type: 'text', text }], isError: true };
}

/**
 * Builds the host-facing server over a session (not yet connected to a transport).
 * @param {BridgeSession} session
 * @returns {Server}
 */
export function createBridgeServer(session) {
  const server = new Server(
    { name: NAME, version: VERSION },
    { capabilities: { tools: { listChanged: true }, resources: { listChanged: true } } },
  );
  let initialized = false;
  server.oninitialized = () => {
    initialized = true;
  };

  session.onChange(() => {
    if (!initialized) return;
    server.sendToolListChanged().catch(() => {});
    server.sendResourceListChanged().catch(() => {});
  });

  // initialize: resolve first (never failing the handshake), then answer with the target's
  // instructions followed by the bridge sentence.
  server.setRequestHandler(InitializeRequestSchema, async (request) => {
    let conn = null;
    try {
      conn = await session.ensure();
    } catch (e) {
      session.log(e instanceof BridgeError ? e.message : `target resolution failed: ${e && e.message}`);
    }
    if (!conn) session.log('no CircuitJS1 instance yet; only the bridge tools are listed');
    const forwarded = conn && conn.handshake && conn.handshake.instructions;
    const result = await server._oninitialize(request);
    return { ...result, instructions: forwarded ? `${forwarded}\n\n${BRIDGE_SENTENCE}` : BRIDGE_SENTENCE };
  });

  /** The connection for a list/read request: resolves without launching; null when none. */
  async function listConn() {
    try {
      return await session.ensure({ launch: false });
    } catch (e) {
      return null;
    }
  }

  server.setRequestHandler(ListToolsRequestSchema, async (request, extra) => {
    const conn = await listConn();
    let page = { tools: [] };
    if (conn) {
      try {
        page = await session.forward(conn, 'tools/list', request.params, { signal: extra.signal });
      } catch (e) {
        if (!(e instanceof ForwardError)) throw e;
        session.log(`tools/list: ${e.message}`);
        page = { tools: [] };
      }
    }
    // The bridge tools follow the target's last page.
    if (page.nextCursor) return page;
    return { ...page, tools: [...(Array.isArray(page.tools) ? page.tools : []), ...BRIDGE_TOOLS] };
  });

  registerRaw.call(server, CallToolRequestSchema, async (request, extra) => {
    const { name } = request.params;
    if (isBridgeTool(name)) return callBridgeTool(session, name, request.params.arguments);
    let conn;
    try {
      conn = await session.ensure();
    } catch (e) {
      if (e instanceof BridgeError) return toolError(e.message);
      throw e;
    }
    if (!conn) return toolError(NO_INSTANCE_TEXT);
    try {
      return await session.forward(conn, 'tools/call', request.params, { signal: extra.signal });
    } catch (e) {
      if (e instanceof ForwardError) return toolError(e.message);
      throw e; // the target's JSON-RPC error, passed through
    }
  });

  /** Forwards a resource request; `empty` answers when there is no target or it is gone. */
  function forwardResources(method, empty) {
    return async (request, extra) => {
      const conn = await listConn();
      if (conn) {
        try {
          return await session.forward(conn, method, request.params, { signal: extra.signal });
        } catch (e) {
          if (!(e instanceof ForwardError)) throw e;
          if (e.kind === 'timeout') throw new McpError(ErrorCode.RequestTimeout, e.message);
          if (e.kind === 'http') throw new McpError(ErrorCode.InternalError, e.message);
          session.log(`${method}: ${e.message}`);
          if (!empty) throw new McpError(ErrorCode.InternalError, e.message);
        }
      }
      if (empty) return empty;
      throw new McpError(RESOURCE_NOT_FOUND, `${NO_INSTANCE_TEXT} (resource ${request.params.uri})`);
    };
  }

  server.setRequestHandler(ListResourcesRequestSchema, forwardResources('resources/list', { resources: [] }));
  server.setRequestHandler(
    ListResourceTemplatesRequestSchema,
    forwardResources('resources/templates/list', { resourceTemplates: [] }),
  );
  server.setRequestHandler(ReadResourceRequestSchema, forwardResources('resources/read', null));

  return server;
}

/**
 * Runs the stdio server until stdin closes or a termination signal arrives.
 * @param {import('./options.js').Options} opts
 * @param {object} [deps]
 */
export async function runStdio(opts, deps = {}) {
  const session = new BridgeSession(opts, deps);
  const server = createBridgeServer(session);
  const transport = new StdioServerTransport();
  let closing = false;
  const shutdown = async () => {
    if (closing) return;
    closing = true;
    await session.close().catch(() => {});
    await server.close().catch(() => {});
    process.exit(0);
  };
  transport.onclose = shutdown;
  process.stdin.on('end', shutdown);
  process.on('SIGINT', shutdown);
  process.on('SIGTERM', shutdown);
  process.on('unhandledRejection', (e) => session.log(`unhandled: ${(e && e.stack) || e}`));
  server.onerror = (e) => session.log(`protocol: ${(e && e.message) || e}`);
  await server.connect(transport);
}
