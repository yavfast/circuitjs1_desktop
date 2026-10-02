// [SP_MCB_02_02] Bridge-owned tools: bridge_instances, bridge_select, bridge_launch. They are
// the only tools the bridge answers itself; every other tool call is forwarded. Rejected
// operations are `isError` results with the spec's text; malformed arguments are JSON-RPC
// -32602 (InvalidParams).

import path from 'node:path';
import { ErrorCode, McpError } from '@modelcontextprotocol/sdk/types.js';
import { BridgeError } from './errors.js';
import { ForwardError } from './forward.js';
import { findRecord, instanceInfo, latestRecord, liveIdsText } from './registry.js';
import { targetOf } from './target.js';

/** Bridge tool names start with this prefix; app tools start with `circuit_` (SP_MCB_03_03). */
export const BRIDGE_PREFIX = 'bridge_';

/** The sentence appended to the forwarded instructions (SP_MCB_02_01). */
export const BRIDGE_SENTENCE =
  'This connection runs through the circuitjs-mcp bridge, which adds bridge_instances, ' +
  'bridge_select and bridge_launch to list the running CircuitJS1 instances, switch to another ' +
  'one, or start the app (optionally opening a circuit file).';

// Output schemas stay open (nothing required inside, no additionalProperties: false): hosts
// validate structuredContent against them.
const TARGET_SCHEMA = {
  type: 'object',
  properties: {
    url: { type: 'string' },
    instanceId: { type: 'string' },
    source: { type: 'string', enum: ['explicit', 'registry', 'launched'] },
  },
};

const INSTANCE_SCHEMA = {
  type: 'object',
  properties: {
    instanceId: { type: 'string' },
    url: { type: 'string' },
    title: { type: 'string' },
    appVersion: { type: 'string' },
    toolsVersion: { type: 'string' },
    startedAt: { type: 'string' },
    selected: { type: 'boolean' },
  },
};

/** Tool descriptors, in tools/list order. */
export const BRIDGE_TOOLS = [
  {
    name: 'bridge_instances',
    title: 'List CircuitJS1 instances',
    description:
      'List the CircuitJS1 app instances running on this machine (from the instance registry) and ' +
      'the one this bridge currently forwards to. Each entry has instanceId, url, title, ' +
      'appVersion, toolsVersion, startedAt and selected. Use bridge_select to switch.',
    inputSchema: { type: 'object', properties: {}, additionalProperties: false },
    outputSchema: {
      type: 'object',
      properties: { instances: { type: 'array', items: INSTANCE_SCHEMA }, target: TARGET_SCHEMA },
    },
    annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false },
  },
  {
    name: 'bridge_select',
    title: 'Select a CircuitJS1 instance',
    description:
      'Forward all circuit_* tools to another CircuitJS1 instance from now on. Give exactly one of ' +
      'instanceId (from bridge_instances) or url (an instance endpoint, e.g. on another machine of ' +
      'the private network: http://<host>:7311/mcp). The tool list may change afterwards.',
    inputSchema: {
      type: 'object',
      properties: {
        instanceId: { type: 'string', description: 'Instance ID from bridge_instances' },
        url: { type: 'string', description: 'Endpoint URL, http://<host>:<port>/mcp' },
      },
      additionalProperties: false,
    },
    outputSchema: { type: 'object', properties: { target: TARGET_SCHEMA } },
    annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: true, openWorldHint: false },
  },
  {
    name: 'bridge_launch',
    title: 'Start CircuitJS1',
    description:
      'Start the CircuitJS1 app (configured with --app or CIRCUITJS_APP) and forward to it; when an ' +
      'instance is already running, use that one instead. With file (an absolute .txt or .json path), ' +
      'open it in a new document and make it the visible tab; opened.doc is its document handle.',
    inputSchema: {
      type: 'object',
      properties: { file: { type: 'string', description: 'Absolute path of a .txt or .json circuit file to open' } },
      additionalProperties: false,
    },
    outputSchema: {
      type: 'object',
      properties: { target: TARGET_SCHEMA, opened: { type: 'object', properties: { doc: {} } } },
    },
    annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: false, openWorldHint: false },
  },
];

const NAMES = new Set(BRIDGE_TOOLS.map((t) => t.name));

/** True for the name of a bridge-owned tool. */
export function isBridgeTool(name) {
  return NAMES.has(name);
}

function ok(structured) {
  return { content: [{ type: 'text', text: JSON.stringify(structured) }], structuredContent: structured };
}

function fail(text) {
  return { content: [{ type: 'text', text }], isError: true };
}

function invalid(message) {
  return new McpError(ErrorCode.InvalidParams, message);
}

/** Checks the arguments against the descriptor: an object of known string-valued keys. */
function checkArgs(tool, args) {
  if (args === undefined || args === null) return {};
  if (typeof args !== 'object' || Array.isArray(args)) throw invalid(`${tool.name}: arguments must be an object`);
  const known = tool.inputSchema.properties;
  for (const [k, v] of Object.entries(args)) {
    if (!(k in known)) throw invalid(`${tool.name}: unknown argument "${k}"`);
    if (typeof v !== 'string' || v === '') throw invalid(`${tool.name}: argument "${k}" must be a non-empty string`);
  }
  return args;
}

/** Target value for structuredContent: the Target without undefined members. */
function targetValue(t) {
  const v = { url: t.url };
  if (t.instanceId) v.instanceId = t.instanceId;
  v.source = t.source;
  return v;
}

/** [SP_MCB_01_03] bridge_instances. */
function instances(session) {
  const target = session.target;
  const list = session.liveRecords().map((r) => instanceInfo(r, target));
  const out = { instances: list };
  if (target) out.target = targetValue(target);
  return ok(out);
}

async function select(session, args) {
  const keys = Object.keys(args);
  if (keys.length !== 1) throw invalid('bridge_select: give exactly one of instanceId or url');
  let resolution;
  if (args.instanceId !== undefined) {
    const records = session.liveRecords();
    const rec = findRecord(records, args.instanceId);
    if (!rec) return fail(`Unknown instance ${args.instanceId}. Live instances: ${liveIdsText(records)}`);
    resolution = { target: targetOf(rec, 'registry'), record: rec };
  } else {
    let u;
    try {
      u = new URL(args.url);
    } catch (e) {
      throw invalid(`bridge_select: url is not a URL: ${args.url}`);
    }
    if (u.protocol !== 'http:' && u.protocol !== 'https:') throw invalid('bridge_select: url must be http:// or https://');
    resolution = { target: { url: args.url, source: 'explicit' }, record: null };
  }
  try {
    const conn = await session.connect(resolution);
    await session.adopt(conn);
  } catch (e) {
    if (e instanceof BridgeError) return fail(e.message);
    throw e;
  }
  return ok({ target: targetValue(session.target) });
}

/**
 * Starts the app, or uses the instance already running, and opens `file` there. Shared by
 * bridge_launch and the CLI `launch` subcommand.
 * @param {import('./forward.js').BridgeSession} session
 * @param {string|undefined} file
 * @returns {Promise<{target: object, opened?: {doc: unknown}}>}
 * @throws {BridgeError} the launch errors and "Cannot reach"
 * @throws {OpenFailed} circuit_file rejected the file (the target stays set)
 */
export async function launchAndOpen(session, file) {
  // An instance that is already running is used as it is: a second start of the app would be
  // handed to the running NW.js process (single-instance) and register nothing.
  const live = session.liveRecords();
  const current = session.target;
  if (!(current && current.instanceId && live.some((r) => r.instanceId === current.instanceId))) {
    let resolution;
    if (live.length > 0) {
      const rec = latestRecord(live);
      resolution = { target: targetOf(rec, 'registry'), record: rec };
    } else {
      resolution = await session.launchOnce();
    }
    await session.adopt(await session.connect(resolution));
  }
  const conn = session.conn;
  const out = { target: targetValue(conn.target) };
  if (file === undefined) return out;

  const abs = path.resolve(file);
  let result;
  try {
    result = await session.forward(conn, 'tools/call', {
      name: 'circuit_file',
      arguments: { action: 'open', path: abs, into: 'new', activate: true },
    });
  } catch (e) {
    if (e instanceof ForwardError || !Number.isInteger(e && e.code)) throw e;
    throw new OpenFailed(abs, null, e.message); // the target's JSON-RPC error
  }
  const data = result && result.structuredContent && result.structuredContent.data;
  if (result.isError || !data || data.doc === undefined) throw new OpenFailed(abs, result);
  out.opened = { doc: data.doc };
  return out;
}

/**
 * circuit_file did not open the file. `result`: its tool result, unchanged (null for a JSON-RPC
 * error); `detail`: the app's text.
 */
export class OpenFailed extends Error {
  constructor(file, result, detail) {
    super(`Opening ${file} failed`);
    this.name = 'OpenFailed';
    this.file = file;
    this.result = result;
    const parts = result && Array.isArray(result.content) ? result.content.filter((p) => p && p.type === 'text') : [];
    this.detail = detail ?? parts.map((p) => p.text).join('\n');
  }
}

async function launchTool(session, args) {
  if (args.file !== undefined && !path.isAbsolute(args.file)) throw invalid('bridge_launch: file must be an absolute path');
  try {
    return ok(await launchAndOpen(session, args.file));
  } catch (e) {
    if (e instanceof BridgeError || e instanceof ForwardError) return fail(e.message);
    if (e instanceof OpenFailed) {
      const t = session.target;
      return fail(`The instance ${t ? t.instanceId || t.url : ''} is running and selected, but opening ${e.file} failed: ${e.detail}`);
    }
    throw e;
  }
}

/**
 * Handles a tools/call of a bridge tool.
 * @param {import('./forward.js').BridgeSession} session
 * @param {string} name
 * @param {unknown} rawArgs
 */
export async function callBridgeTool(session, name, rawArgs) {
  const tool = BRIDGE_TOOLS.find((t) => t.name === name);
  if (!tool) throw invalid(`Unknown tool: ${name}`);
  const args = checkArgs(tool, rawArgs);
  switch (name) {
    case 'bridge_instances':
      return instances(session);
    case 'bridge_select':
      return select(session, args);
    case 'bridge_launch':
      return launchTool(session, args);
    default:
      throw invalid(`Unknown tool: ${name}`);
  }
}
