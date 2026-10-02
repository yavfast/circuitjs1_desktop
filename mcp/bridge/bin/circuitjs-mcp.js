#!/usr/bin/env node
// circuitjs-mcp entry point (SP_MCB_02_01, SP_MCB_02_03). Without a subcommand it is a stdio MCP
// server forwarding to a CircuitJS1 instance; with one it is a command-line client.
//
// Stdout carries data only (the MCP stream in stdio mode, one JSON document in CLI mode);
// every diagnostic goes to stderr.
//

import { runCli } from '../src/cli.js';
import { parseArgs, UsageError } from '../src/options.js';
import { runStdio } from '../src/stdio-server.js';
import { NAME, VERSION } from '../src/version.js';

const USAGE = `Usage:
  ${NAME} [options]                                  stdio MCP server mode
  ${NAME} instances [options]
  ${NAME} tools [options]
  ${NAME} call <tool> [<json-args> | -] [options]    "-" reads arguments from stdin
  ${NAME} read <uri> [options]
  ${NAME} launch [<file>] [options]
  ${NAME} --help | --version

Options (flag wins over environment):
  --url <url>              CIRCUITJS_MCP_URL        explicit endpoint; skips the registry
  --instance <id>          CIRCUITJS_MCP_INSTANCE   instance ID from the registry
  --launch                 CIRCUITJS_MCP_LAUNCH=1   start the app when no instance is live
  --app <path>             CIRCUITJS_APP            packaged app executable (needed to launch)
  --registry <dir>         CIRCUITJS_MCP_REGISTRY   instance directory (default ~/.circuitjs1/instances)
  --timeout <ms>           CIRCUITJS_MCP_TIMEOUT    per-request forward timeout (default 130000)
  --launch-timeout <ms>                             wait for a launched instance (default 30000)

Environment: CIRCUITJS_MCP_DEBUG=1 adds the stack trace to an unexpected error.

Output: one JSON document on stdout; errors on stderr (one line).
Exit codes: 0 success, 1 the tool returned an error result, 2 usage error (bad subcommand or
JSON arguments, request rejected as invalid), 3 no reachable instance, connection error or timeout.
`;

/** One stderr line (SP_MCB_01_04: exit 2/3 leave stdout empty and write one line to stderr). */
function fail(message, code) {
  process.stderr.write(`${NAME}: ${String(message).replace(/\s*\n\s*/g, ' ')}\n`);
  process.exitCode = code;
}

/** "unexpected error: <message>"; the stack too when CIRCUITJS_MCP_DEBUG=1 (then more lines). */
function unexpected(prefix, e) {
  fail(`${prefix}: ${(e && e.message) || e}`, 3);
  if (process.env.CIRCUITJS_MCP_DEBUG === '1' && e && e.stack) process.stderr.write(`${e.stack}\n`);
}

function main(argv) {
  let parsed;
  try {
    parsed = parseArgs(argv);
  } catch (e) {
    if (e instanceof UsageError) {
      fail(`${e.message}; run "${NAME} --help" for usage`, e.exitCode);
      return;
    }
    throw e;
  }
  if (parsed.help) {
    process.stdout.write(USAGE);
    return;
  }
  if (parsed.version) {
    process.stdout.write(`${VERSION}\n`);
    return;
  }
  const [command] = parsed.positionals;
  if (command === undefined) {
    runStdio(parsed.options).catch((e) => unexpected('stdio server failed', e));
    return;
  }
  runCli(parsed, {
    stdin: process.stdin,
    writeOut: (text) => process.stdout.write(text),
    writeErr: (text) => process.stderr.write(text),
    prog: NAME,
  }).then(
    // Exit once stdout is flushed: an idle socket of the HTTP client must not keep the process.
    (code) => process.stdout.write('', () => process.exit(code)),
    (e) => {
      unexpected('unexpected error', e);
      process.exit(3);
    },
  );
}

main(process.argv.slice(2));
