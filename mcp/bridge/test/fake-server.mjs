// A fake CircuitJS1 MCP endpoint for the bridge's unit tests. Like the app's own transport
// (mcp/server/src/http.js, SP_MCP_02_01) it answers each POSTed JSON-RPC message with one JSON
// response, a notification with 202, and GET with 405; it keeps no session.
//
// Options let a test shape the server: its instructions and tools, the revisions it serves,
// a delay before answering, never answering at all (`hang`), or a fixed HTTP error (`postStatus`).

import http from 'node:http';

/**
 * @param {object} [o]
 * @param {string} [o.instructions]
 * @param {string[]} [o.revisions]   served revisions, newest first
 * @param {object[]} [o.tools]       tool descriptors for tools/list
 * @param {number} [o.delayMs]       delay before each answer
 * @param {boolean} [o.hang]         accept requests but never answer
 * @param {number} [o.postStatus]    answer every POST with this HTTP status and no body (e.g. 404)
 * @param {(msg: object) => (object|undefined|Promise<object|undefined>)} [o.handle]
 *        answers a request first: a result object, `{error: {code, message, data?}}` for a
 *        JSON-RPC error, `{http: {status, body}}` for a raw HTTP answer, or undefined for the
 *        default answer
 * @param {number} [o.port]          listen on this port (default: any free one)
 * @returns {Promise<{url: string, port: number, requests: object[], close: () => Promise<void>}>}
 */
export async function startFakeServer(o = {}) {
  const revisions = o.revisions || ['2025-11-25', '2025-06-18'];
  const requests = [];
  const sockets = new Set();

  function answer(msg) {
    switch (msg.method) {
      case 'initialize': {
        const asked = msg.params && msg.params.protocolVersion;
        return {
          protocolVersion: revisions.includes(asked) ? asked : revisions[0],
          capabilities: { tools: { listChanged: false }, resources: { listChanged: false } },
          serverInfo: { name: 'fake-circuitjs1', version: '0.0.0' },
          instructions: o.instructions ?? 'Fake CircuitJS1 instance. toolsVersion 1.0.',
        };
      }
      case 'ping':
        return {};
      case 'tools/list':
        return { tools: o.tools || [] };
      case 'tools/call':
        // Echo: the call as structured content, in the app's result shape.
        return {
          content: [{ type: 'text', text: JSON.stringify({ ok: true, data: msg.params }) }],
          structuredContent: { ok: true, data: { name: msg.params.name, arguments: msg.params.arguments ?? null } },
        };
      case 'resources/list':
        return { resources: o.resources || [] };
      case 'resources/read':
        if (msg.params && msg.params.uri === 'circuitjs://catalogue') {
          return { contents: [{ uri: 'circuitjs://catalogue', mimeType: 'application/json', text: '{"types":[]}' }] };
        }
        return { error: { code: -32002, message: `Resource not found: ${msg.params && msg.params.uri}` } };
      case 'resources/templates/list':
        return { resourceTemplates: [] };
      default:
        return null;
    }
  }

  const server = http.createServer((req, res) => {
    if (req.method !== 'POST') {
      res.writeHead(405, { Allow: 'POST' }).end();
      return;
    }
    if (o.postStatus) {
      req.resume();
      req.on('end', () => res.writeHead(o.postStatus, { 'Content-Type': 'text/plain' }).end('Not Found'));
      return;
    }
    let body = '';
    req.setEncoding('utf8');
    req.on('data', (chunk) => (body += chunk));
    req.on('end', () => {
      let msg;
      try {
        msg = JSON.parse(body);
      } catch (e) {
        res.writeHead(400).end();
        return;
      }
      requests.push(msg);
      if (o.hang) return;
      const reply = async () => {
        if (msg.id === undefined) {
          res.writeHead(202).end();
          return;
        }
        let result = o.handle ? await o.handle(msg) : undefined;
        if (result === undefined) result = answer(msg);
        if (result && result.http) {
          res.writeHead(result.http.status, { 'Content-Type': 'application/json' }).end(result.http.body);
          return;
        }
        let out;
        if (!result) out = { jsonrpc: '2.0', id: msg.id, error: { code: -32601, message: `Method not found: ${msg.method}` } };
        else if (result.error) out = { jsonrpc: '2.0', id: msg.id, error: result.error };
        else out = { jsonrpc: '2.0', id: msg.id, result };
        if (!res.destroyed) res.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify(out));
      };
      if (o.delayMs) setTimeout(reply, o.delayMs);
      else reply();
    });
  });
  server.on('connection', (s) => {
    sockets.add(s);
    s.on('close', () => sockets.delete(s));
  });
  await new Promise((resolve) => server.listen(o.port || 0, '127.0.0.1', resolve));
  const port = server.address().port;
  return {
    url: `http://127.0.0.1:${port}/mcp`,
    port,
    requests,
    close: () =>
      new Promise((resolve) => {
        for (const s of sockets) s.destroy();
        server.close(() => resolve());
      }),
  };
}
