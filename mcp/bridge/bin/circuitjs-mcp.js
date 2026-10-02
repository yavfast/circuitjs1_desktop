#!/usr/bin/env node
// circuitjs-mcp entry point (SP_MCB_02_01, SP_MCB_02_03). Without a subcommand it is a stdio MCP
// server forwarding to a CircuitJS1 instance; with one it is a command-line client.
//
// Stdout carries data only (the MCP stream in stdio mode, one JSON document in CLI mode);
// every diagnostic goes to stderr.
//
// [PL_MCB_P2] Options, version, help and the stdio server mode are wired. The subcommands
// arrive with PL_MCB Phase 3; until then they report a usage error.

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
`;

function fail(message, code) {
  process.stderr.write(`${NAME}: ${message}\n`);
  process.exitCode = code;
}

function main(argv) {
  let parsed;
  try {
    parsed = parseArgs(argv);
  } catch (e) {
    if (e instanceof UsageError) {
      fail(`${e.message}\nRun "${NAME} --help" for usage.`, e.exitCode);
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
    runStdio(parsed.options).catch((e) => fail(`stdio server failed: ${(e && e.stack) || e}`, 3));
  } else {
    fail(`the "${command}" subcommand is not implemented yet (PL_MCB Phase 3).`, 2);
  }
}

main(process.argv.slice(2));
