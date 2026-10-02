// [SP_MCB_01_01] Options: command-line flags and environment variables. When both set the same
// option, the flag wins. Arguments that are not options (the subcommand and its operands, "-"
// for stdin) are returned in order as positionals.
//
// Nothing here prints: errors are thrown as UsageError (exit code 2, SP_MCB_01_04) and the
// entry point writes them to stderr.

import os from 'node:os';
import path from 'node:path';

/** Per-request forward timeout: above the 120 s run budget cap (SP_MCB_05_04). */
export const DEFAULT_TIMEOUT_MS = 130000;
/** Wait for a launched instance's registry record. */
export const DEFAULT_LAUNCH_TIMEOUT_MS = 30000;

/** A bad command line or option value; the CLI exits with code 2. */
export class UsageError extends Error {
  constructor(message) {
    super(message);
    this.name = 'UsageError';
    this.exitCode = 2;
  }
}

/**
 * @typedef {object} Options
 * @property {string|null} url            explicit endpoint; skips the registry
 * @property {string|null} instance       instance ID from the registry
 * @property {boolean} launch             start the app when no instance is live
 * @property {string|null} app            path of the packaged app executable
 * @property {string} registry            absolute instance directory
 * @property {number} timeout             per-request forward timeout, ms
 * @property {number} launchTimeout       wait for a launched instance's record, ms
 */

/**
 * @typedef {object} ParsedArgs
 * @property {Options} options
 * @property {string[]} positionals       subcommand and operands, in order
 * @property {boolean} help               --help given
 * @property {boolean} version            --version given
 */

// Option table: flag name → option key, value kind and environment variable.
const OPTIONS = [
  { flag: 'url', key: 'url', kind: 'url', env: 'CIRCUITJS_MCP_URL' },
  { flag: 'instance', key: 'instance', kind: 'string', env: 'CIRCUITJS_MCP_INSTANCE' },
  { flag: 'launch', key: 'launch', kind: 'bool', env: 'CIRCUITJS_MCP_LAUNCH' },
  { flag: 'app', key: 'app', kind: 'string', env: 'CIRCUITJS_APP' },
  { flag: 'registry', key: 'registry', kind: 'string', env: 'CIRCUITJS_MCP_REGISTRY' },
  { flag: 'timeout', key: 'timeout', kind: 'int', env: 'CIRCUITJS_MCP_TIMEOUT' },
  { flag: 'launch-timeout', key: 'launchTimeout', kind: 'int', env: null },
];
const BY_FLAG = new Map(OPTIONS.map((o) => [o.flag, o]));

// The two target selectors. A selector given as a flag hides both selector environment
// variables, so `--instance X` is not overridden by an inherited CIRCUITJS_MCP_URL
// (resolution checks the URL first, SP_MCB_03_01).
const SELECTOR_KEYS = new Set(['url', 'instance']);

/** @returns the instance directory of the current user (SP_MCP_01_02) */
export function defaultRegistryDir(homedir = os.homedir()) {
  return path.join(homedir, '.circuitjs1', 'instances');
}

// Largest delay setTimeout honours; a longer one overflows and fires at once.
const MAX_TIMEOUT_MS = 2 ** 31 - 1;

function parseInt10(text, where) {
  if (!/^\d+$/.test(text) || Number(text) < 1 || Number(text) > MAX_TIMEOUT_MS) {
    throw new UsageError(`${where} must be an integer number of milliseconds from 1 to ${MAX_TIMEOUT_MS}, got "${text}"`);
  }
  return Number(text);
}

function parseUrl(text, where) {
  let u;
  try {
    u = new URL(text);
  } catch (e) {
    throw new UsageError(`${where} is not a URL: "${text}"`);
  }
  if (u.protocol !== 'http:' && u.protocol !== 'https:') {
    throw new UsageError(`${where} must be an http:// or https:// URL, got "${text}"`);
  }
  return text;
}

function convert(opt, text, where) {
  switch (opt.kind) {
    case 'int':
      return parseInt10(text, where);
    case 'url':
      return parseUrl(text, where);
    default:
      if (text === '') throw new UsageError(`${where} must not be empty`);
      return text;
  }
}

/** CIRCUITJS_MCP_LAUNCH=1 (also "true"/"yes", any case) turns launch on; anything else is off. */
function envBool(text) {
  return /^(1|true|yes)$/i.test(text.trim());
}

/**
 * Parses the command line and the environment.
 * @param {string[]} argv     arguments after the program name (process.argv.slice(2))
 * @param {Record<string, string|undefined>} [env]
 * @param {{homedir?: string, cwd?: string}} [ctx]
 * @returns {ParsedArgs}
 * @throws {UsageError}
 */
export function parseArgs(argv, env = process.env, ctx = {}) {
  const cwd = ctx.cwd || process.cwd();
  const fromFlags = {};
  const positionals = [];
  let help = false;
  let version = false;

  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === '--') {
      positionals.push(...argv.slice(i + 1));
      break;
    }
    if (!arg.startsWith('--')) {
      positionals.push(arg); // includes "-" (arguments from stdin)
      continue;
    }
    const eq = arg.indexOf('=');
    const name = arg.slice(2, eq < 0 ? undefined : eq);
    const inline = eq < 0 ? null : arg.slice(eq + 1);
    if (name === 'help' || name === 'version') {
      if (inline !== null) throw new UsageError(`--${name} takes no value`);
      if (name === 'help') help = true;
      else version = true;
      continue;
    }
    const opt = BY_FLAG.get(name);
    if (!opt) throw new UsageError(`Unknown option --${name}`);
    if (opt.kind === 'bool') {
      if (inline !== null) throw new UsageError(`--${name} takes no value`);
      fromFlags[opt.key] = true;
      continue;
    }
    let text = inline;
    if (text === null) {
      // A following option is a forgotten value, not the value ("--app --launch").
      if (i + 1 >= argv.length || argv[i + 1].startsWith('--')) {
        throw new UsageError(`--${name} needs a value`);
      }
      text = argv[++i];
    }
    fromFlags[opt.key] = convert(opt, text, `--${name}`);
  }

  const selectorFlagGiven = [...SELECTOR_KEYS].some((k) => k in fromFlags);
  const fromEnv = {};
  for (const opt of OPTIONS) {
    if (!opt.env) continue;
    const text = env[opt.env];
    if (text === undefined || text === '') continue;
    if (opt.key in fromFlags) continue; // the flag wins; a bad value in the variable does not matter
    if (selectorFlagGiven && SELECTOR_KEYS.has(opt.key)) continue;
    fromEnv[opt.key] = opt.kind === 'bool' ? envBool(text) : convert(opt, text, opt.env);
  }

  const merged = { ...fromEnv, ...fromFlags };
  /** @type {Options} */
  const options = {
    url: merged.url ?? null,
    instance: merged.instance ?? null,
    launch: merged.launch ?? false,
    app: merged.app ?? null,
    registry: merged.registry ? path.resolve(cwd, merged.registry) : defaultRegistryDir(ctx.homedir),
    timeout: merged.timeout ?? DEFAULT_TIMEOUT_MS,
    launchTimeout: merged.launchTimeout ?? DEFAULT_LAUNCH_TIMEOUT_MS,
  };
  return { options, positionals, help, version };
}
