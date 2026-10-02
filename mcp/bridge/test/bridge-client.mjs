// Runs the real `circuitjs-mcp` program in stdio server mode as a child process and talks to it
// with the SDK client, like a stdio host (Claude Desktop) does.

import { fileURLToPath } from 'node:url';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';
import { ResourceListChangedNotificationSchema, ToolListChangedNotificationSchema } from '@modelcontextprotocol/sdk/types.js';
import * as z from 'zod';

export const BIN = fileURLToPath(new URL('../bin/circuitjs-mcp.js', import.meta.url));
const AnyResult = z.looseObject({});

/**
 * @param {{args?: string[], env?: Record<string, string>}} o
 * @returns {Promise<{client: Client, raw: (method: string, params?: object, timeoutMs?: number) => Promise<object>,
 *   notes: {tools: number, resources: number}, stderr: () => string, errors: Error[], close: () => Promise<void>}>}
 */
export async function startBridge(o = {}) {
  const env = { ...process.env };
  for (const k of Object.keys(env)) if (k.startsWith('CIRCUITJS_')) delete env[k];
  Object.assign(env, o.env || {});
  const transport = new StdioClientTransport({ command: process.execPath, args: [BIN, ...(o.args || [])], env, stderr: 'pipe' });
  let err = '';
  transport.stderr.on('data', (d) => (err += d));
  const errors = [];
  const client = new Client({ name: 'test-host', version: '0.0.0' }, { capabilities: {} });
  client.onerror = (e) => errors.push(e); // e.g. a non-JSON line on stdout
  const notes = { tools: 0, resources: 0 };
  client.setNotificationHandler(ToolListChangedNotificationSchema, () => notes.tools++);
  client.setNotificationHandler(ResourceListChangedNotificationSchema, () => notes.resources++);
  await client.connect(transport);
  return {
    client,
    raw: (method, params, timeout = 20000) => client.request(params === undefined ? { method } : { method, params }, AnyResult, { timeout }),
    notes,
    stderr: () => err,
    errors,
    close: () => client.close(),
  };
}

/** Waits until `fn()` is truthy (polling), at most `ms`. */
export async function until(fn, ms = 3000) {
  const end = Date.now() + ms;
  while (!fn()) {
    if (Date.now() > end) throw new Error('condition not met in time');
    await new Promise((r) => setTimeout(r, 20));
  }
}
