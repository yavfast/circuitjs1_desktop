// Scratch MCP client for verification: call(tool, args) -> {json, images}; CLI: node mcp.mjs <tool> '<json>' [--png out.png]
import fs from 'node:fs';
const SDK = new URL('../../../mcp/bridge/node_modules/@modelcontextprotocol/sdk/dist/esm/client/', import.meta.url).href;
const { Client } = await import(SDK + 'index.js');
const { StreamableHTTPClientTransport } = await import(SDK + 'streamableHttp.js');

export const URL_ = process.env.CJS_URL || 'http://127.0.0.1:7311/mcp';
let client = null;
export async function connect() {
  if (client) return client;
  client = new Client({ name: 'agt-verify', version: '0.1' }, { capabilities: {} });
  await client.connect(new StreamableHTTPClientTransport(new URL(URL_)));
  return client;
}
export async function call(tool, args = {}) {
  const c = await connect();
  const r = await c.callTool({ name: tool, arguments: args }, undefined, { timeout: 180000 });
  const images = (r.content || []).filter((p) => p.type === 'image');
  let json = r.structuredContent;
  if (!json) { const t = (r.content || []).find((p) => p.type === 'text'); try { json = JSON.parse(t.text); } catch { json = t && t.text; } }
  return { json, images, isError: r.isError };
}
export async function readRes(uri) { const c = await connect(); return c.readResource({ uri }); }
export async function close() { if (client) await client.close(); client = null; }

if (process.argv[1] && process.argv[1].endsWith('mcp.mjs')) {
  const [tool, a = '{}', ...rest] = process.argv.slice(2);
  const r = await call(tool, JSON.parse(a));
  const pi = rest.indexOf('--png');
  if (pi >= 0 && r.images[0]) fs.writeFileSync(rest[pi + 1], Buffer.from(r.images[0].data, 'base64'));
  console.log(JSON.stringify(r.json));
  await close();
}
