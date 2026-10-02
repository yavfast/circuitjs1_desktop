// [SP_MCB_02_03] [SP_MCB_01_04] Command-line client: one subcommand per invocation, one JSON
// document on stdout, human-readable errors on stderr, exit codes 0–3.
//
//   0  success (`call`: result not isError)
//   1  `call` (or `launch` opening a file) returned isError
//   2  usage error: unknown subcommand, bad JSON arguments, a request the target rejects as
//      invalid (JSON-RPC -32600/-32601/-32602, unknown resource -32002)
//   3  no reachable instance, connection error, timeout, any other target failure
//
// runCli() takes its streams as parameters, so tests can drive it in-process; the entry point
// passes the process streams.

import path from 'node:path';
import { BridgeError } from './errors.js';
import { BridgeSession, ForwardError } from './forward.js';
import { callBridgeTool, isBridgeTool, launchAndOpen, OpenFailed } from './bridge-tools.js';
import { UsageError } from './options.js';
import { findRecord, instanceInfo, latestRecord } from './registry.js';

export const EXIT_OK = 0;
export const EXIT_TOOL_ERROR = 1;
export const EXIT_USAGE = 2;
export const EXIT_UNREACHABLE = 3;

/** JSON-RPC errors that mean "the request was wrong" (exit 2) rather than "the target failed". */
const REQUEST_ERRORS = new Set([-32600, -32601, -32602, -32002]);

/** Upper bound of tools/list pages (a target that never ends its cursor chain). */
const MAX_PAGES = 100;

const NO_INSTANCE_CLI = 'No CircuitJS1 instance. Start the app, or pass --launch (with --app), or run "circuitjs-mcp launch".';

const SUBCOMMANDS = {
  instances: { min: 0, max: 0, usage: 'instances [options]' },
  tools: { min: 0, max: 0, usage: 'tools [options]' },
  call: { min: 1, max: 2, usage: 'call <tool> [<json-args> | -] [options]' },
  read: { min: 1, max: 1, usage: 'read <uri> [options]' },
  launch: { min: 0, max: 1, usage: 'launch [<file>] [options]' },
};

/** An error that ends the command with an exit code and a stderr message. */
class CliExit extends Error {
  constructor(code, message) {
    super(message);
    this.exitCode = code;
  }
}

/** Reads all of a readable stream as UTF-8 text. */
export async function readAll(stream) {
  stream.setEncoding('utf8');
  let text = '';
  for await (const chunk of stream) text += chunk;
  return text;
}

/** Parses `call` arguments: a JSON object, from the operand or (for "-") from stdin. */
async function callArguments(operand, stdin, warn) {
  if (operand === undefined) return {};
  if (operand === '-' && stdin.isTTY && warn) warn('reading arguments from stdin (end with Ctrl-D)');
  const text = operand === '-' ? await readAll(stdin) : operand;
  const where = operand === '-' ? 'arguments on stdin' : 'arguments';
  let value;
  try {
    value = JSON.parse(text);
  } catch (e) {
    throw new UsageError(`${where} are not valid JSON: ${e.message}`);
  }
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    throw new UsageError(`${where} must be a JSON object`);
  }
  return value;
}

/** Exit code and text for a failure of the target side. */
function targetFailure(e, what) {
  if (e instanceof CliExit || e instanceof UsageError) return e;
  if (e instanceof BridgeError || e instanceof ForwardError) return new CliExit(EXIT_UNREACHABLE, e.message);
  if (e && Number.isInteger(e.code)) {
    const code = REQUEST_ERRORS.has(e.code) ? EXIT_USAGE : EXIT_UNREACHABLE;
    const message = String(e.message || '').replace(/^MCP error -?\d+: /, '');
    // Bridge tool errors already name the tool ("bridge_launch: file must be ...").
    const text = message.startsWith(`${what}: `) ? message : `${what}: ${message}`;
    return new CliExit(code, `${text} (JSON-RPC ${e.code})`);
  }
  return null;
}

/** §01_04 `call` output: the structured content, or the content parts when there is none. */
function callOutput(result) {
  if (result && result.structuredContent !== undefined) return result.structuredContent;
  return { content: (result && result.content) || [] };
}

async function connection(session, launch) {
  const conn = await session.ensure({ launch });
  if (!conn) throw new CliExit(EXIT_UNREACHABLE, NO_INSTANCE_CLI);
  return conn;
}

/** The record the CLI would resolve to without probing or launching (for `selected`). */
function wouldSelect(opts, records) {
  if (opts.url) return { url: opts.url };
  const rec = opts.instance ? findRecord(records, opts.instance) : latestRecord(records);
  return rec ? { url: rec.urls[0], instanceId: rec.instanceId } : null;
}

const COMMANDS = {
  async instances(session) {
    const records = session.liveRecords();
    const target = wouldSelect(session.opts, records);
    return { out: records.map((r) => instanceInfo(r, target)) };
  },

  async tools(session) {
    const conn = await connection(session, false);
    const tools = [];
    const seen = new Set();
    let cursor;
    do {
      if (cursor !== undefined) {
        if (seen.has(cursor)) throw new CliExit(EXIT_UNREACHABLE, `tools/list repeated the cursor ${JSON.stringify(cursor)}`);
        if (seen.size >= MAX_PAGES - 1) throw new CliExit(EXIT_UNREACHABLE, `tools/list did not end within ${MAX_PAGES} pages`);
        seen.add(cursor);
      }
      const page = await session.forward(conn, 'tools/list', cursor ? { cursor } : {});
      for (const t of page.tools || []) {
        tools.push({ name: t.name, title: t.title ?? (t.annotations && t.annotations.title) ?? null, annotations: t.annotations ?? {} });
      }
      cursor = page.nextCursor;
    } while (cursor);
    return { out: tools };
  },

  async call(session, [name, operand], io) {
    const args = await callArguments(operand, io.stdin, io.warn);
    let result;
    if (isBridgeTool(name)) {
      result = await callBridgeTool(session, name, args);
    } else {
      const conn = await connection(session, undefined); // honours --launch
      result = await session.forward(conn, 'tools/call', { name, arguments: args });
    }
    if (result && result.isError) {
      return { out: callOutput(result), code: EXIT_TOOL_ERROR, note: `${name} returned an error result` };
    }
    return { out: callOutput(result) };
  },

  async read(session, [uri]) {
    const conn = await connection(session, false);
    const result = await session.forward(conn, 'resources/read', { uri });
    if (!result || !Array.isArray(result.contents)) throw new CliExit(EXIT_UNREACHABLE, `${uri}: malformed resources/read result`);
    return { out: result.contents };
  },

  async launch(session, [file]) {
    try {
      return { out: await launchAndOpen(session, file === undefined ? undefined : path.resolve(file)) };
    } catch (e) {
      if (!(e instanceof OpenFailed)) throw e;
      const t = session.target;
      const message = `The instance ${t ? t.instanceId || t.url : ''} is running and selected, but opening ${e.file} failed: ${e.detail}`;
      return { out: e.result ? callOutput(e.result) : { content: [{ type: 'text', text: e.detail }] }, code: EXIT_TOOL_ERROR, note: message };
    }
  },
};

/**
 * Runs one subcommand.
 * @param {{options: import('./options.js').Options, positionals: string[]}} parsed
 * @param {{stdin: NodeJS.ReadableStream, writeOut: (text: string) => void, writeErr: (text: string) => void, prog?: string}} io
 * @param {object} [deps]  BridgeSession test seams
 * @returns {Promise<number>} exit code
 */
export async function runCli(parsed, io, deps = {}) {
  const prog = io.prog || 'circuitjs-mcp';
  const [command, ...operands] = parsed.positionals;
  const spec = SUBCOMMANDS[command];
  // Every diagnostic is one line.
  const err = (text) => io.writeErr(`${prog}: ${String(text).replace(/\s*\n\s*/g, ' ')}\n`);
  io = { ...io, warn: err };
  if (!spec) {
    err(`unknown subcommand "${command}". Run "${prog} --help" for usage.`);
    return EXIT_USAGE;
  }
  if (operands.length < spec.min || operands.length > spec.max) {
    err(`usage: ${prog} ${spec.usage}`);
    return EXIT_USAGE;
  }
  const session = new BridgeSession(parsed.options, { log: () => {}, ...deps });
  try {
    const { out, code = EXIT_OK, note } = await COMMANDS[command](session, operands, io);
    if (note) err(note);
    io.writeOut(JSON.stringify(out) + '\n');
    return code;
  } catch (e) {
    const what = command === 'call' ? operands[0] : command === 'read' ? operands[0] : command;
    const failure = targetFailure(e, what);
    if (!failure) throw e;
    err(failure.message);
    return failure.exitCode;
  } finally {
    await session.close();
  }
}
