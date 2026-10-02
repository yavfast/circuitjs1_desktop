// [SP_MCB_01_01] Option parsing: flags, environment, precedence (flag over env), defaults.

import test from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { parseArgs, UsageError, DEFAULT_TIMEOUT_MS, DEFAULT_LAUNCH_TIMEOUT_MS, defaultRegistryDir } from '../src/options.js';

const CTX = { homedir: '/home/u', cwd: '/work' };
const parse = (argv, env = {}) => parseArgs(argv, env, CTX);

test('defaults with no flags and no environment', () => {
  const { options, positionals, help, version } = parse([]);
  assert.deepEqual(options, {
    url: null,
    instance: null,
    launch: false,
    app: null,
    registry: path.join('/home/u', '.circuitjs1', 'instances'),
    timeout: DEFAULT_TIMEOUT_MS,
    launchTimeout: DEFAULT_LAUNCH_TIMEOUT_MS,
  });
  assert.equal(DEFAULT_TIMEOUT_MS, 130000);
  assert.equal(DEFAULT_LAUNCH_TIMEOUT_MS, 30000);
  assert.deepEqual(positionals, []);
  assert.equal(help, false);
  assert.equal(version, false);
  assert.equal(defaultRegistryDir('/h'), path.join('/h', '.circuitjs1', 'instances'));
});

test('environment alone sets every option that has a variable', () => {
  const { options } = parse([], {
    CIRCUITJS_MCP_INSTANCE: '42-1',
    CIRCUITJS_MCP_LAUNCH: '1',
    CIRCUITJS_APP: '/opt/cjs/CircuitSimulator',
    CIRCUITJS_MCP_REGISTRY: 'reg',
    CIRCUITJS_MCP_TIMEOUT: '5000',
  });
  assert.equal(options.instance, '42-1');
  assert.equal(options.launch, true);
  assert.equal(options.app, '/opt/cjs/CircuitSimulator');
  assert.equal(options.registry, path.resolve('/work', 'reg'));
  assert.equal(options.timeout, 5000);
  assert.equal(parse([], { CIRCUITJS_MCP_URL: 'http://10.0.0.9:7311/mcp' }).options.url, 'http://10.0.0.9:7311/mcp');
});

test('flag wins over environment, option by option', () => {
  const env = {
    CIRCUITJS_MCP_URL: 'http://env:1/mcp',
    CIRCUITJS_APP: '/env/app',
    CIRCUITJS_MCP_REGISTRY: '/env/reg',
    CIRCUITJS_MCP_TIMEOUT: '1000',
  };
  const { options } = parse(
    ['--url', 'http://flag:2/mcp', '--app=/flag/app', '--registry', '/flag/reg', '--timeout', '2000'],
    env,
  );
  assert.equal(options.url, 'http://flag:2/mcp');
  assert.equal(options.app, '/flag/app');
  assert.equal(options.registry, '/flag/reg');
  assert.equal(options.timeout, 2000);
  assert.equal(parse(['--instance', 'flag-1'], { CIRCUITJS_MCP_INSTANCE: 'env-1' }).options.instance, 'flag-1');
  assert.equal(parse(['--launch'], { CIRCUITJS_MCP_LAUNCH: '0' }).options.launch, true);
});

test('a target selector given as a flag hides both selector variables', () => {
  const env = { CIRCUITJS_MCP_URL: 'http://env:1/mcp', CIRCUITJS_MCP_INSTANCE: 'env-1' };
  const byInstance = parse(['--instance', 'flag-1'], env).options;
  assert.equal(byInstance.instance, 'flag-1');
  assert.equal(byInstance.url, null);
  const byUrl = parse(['--url', 'http://flag:2/mcp'], env).options;
  assert.equal(byUrl.url, 'http://flag:2/mcp');
  assert.equal(byUrl.instance, null);
  // Without a selector flag both variables apply (resolution then checks the URL first).
  const none = parse([], env).options;
  assert.equal(none.url, 'http://env:1/mcp');
  assert.equal(none.instance, 'env-1');
});

test('CIRCUITJS_MCP_LAUNCH: 1/true/yes are on, anything else is off', () => {
  for (const v of ['1', 'true', 'TRUE', 'yes']) assert.equal(parse([], { CIRCUITJS_MCP_LAUNCH: v }).options.launch, true, v);
  for (const v of ['0', 'false', 'no', 'x']) assert.equal(parse([], { CIRCUITJS_MCP_LAUNCH: v }).options.launch, false, v);
  assert.equal(parse([], { CIRCUITJS_MCP_LAUNCH: '' }).options.launch, false);
});

test('positionals keep their order, "-" included; "--" ends the options', () => {
  const r = parse(['call', 'circuit_import', '-', '--timeout', '9000']);
  assert.deepEqual(r.positionals, ['call', 'circuit_import', '-']);
  assert.equal(r.options.timeout, 9000);
  assert.deepEqual(parse(['call', 'x', '--', '--url']).positionals, ['call', 'x', '--url']);
  assert.deepEqual(parse(['call', 'circuit_get', '{"id":"R1"}']).positionals, ['call', 'circuit_get', '{"id":"R1"}']);
});

test('--help and --version', () => {
  assert.equal(parse(['--help']).help, true);
  assert.equal(parse(['--version']).version, true);
  assert.throws(() => parse(['--help=1']), UsageError);
});

test('usage errors: unknown flag, missing value, bad numbers, bad URL', () => {
  const cases = [
    [['--bogus'], {}, /Unknown option --bogus/],
    [['--app'], {}, /--app needs a value/],
    [['--app', '--launch'], {}, /--app needs a value/],
    [['--launch=1'], {}, /takes no value/],
    [['--timeout', '0'], {}, /--timeout must be an integer number of milliseconds/],
    [['--timeout', '1.5'], {}, /--timeout must be an integer number of milliseconds/],
    [['--launch-timeout', 'abc'], {}, /--launch-timeout must be an integer number of milliseconds/],
    [[], { CIRCUITJS_MCP_TIMEOUT: '-1' }, /CIRCUITJS_MCP_TIMEOUT must be an integer number of milliseconds/],
    [['--url', 'not a url'], {}, /--url is not a URL/],
    [['--url', 'ftp://h/mcp'], {}, /--url must be an http/],
    [[], { CIRCUITJS_MCP_URL: 'nope' }, /CIRCUITJS_MCP_URL is not a URL/],
    [['--instance='], {}, /--instance must not be empty/],
  ];
  for (const [argv, env, re] of cases) {
    assert.throws(() => parse(argv, env), (e) => e instanceof UsageError && e.exitCode === 2 && re.test(e.message), argv.join(' '));
  }
});

test('a flag hides a bad value of its variable', () => {
  assert.equal(parse(['--timeout', '5000'], { CIRCUITJS_MCP_TIMEOUT: 'abc' }).options.timeout, 5000);
  assert.equal(parse(['--url', 'http://h:1/mcp'], { CIRCUITJS_MCP_URL: 'nope' }).options.url, 'http://h:1/mcp');
  assert.throws(() => parse([], { CIRCUITJS_MCP_TIMEOUT: 'abc' }), UsageError);
});

test('timeouts are bounded by the setTimeout limit 2^31-1', () => {
  assert.equal(parse(['--timeout', String(2 ** 31 - 1)]).options.timeout, 2 ** 31 - 1);
  assert.throws(() => parse(['--timeout', String(2 ** 31)]), /from 1 to 2147483647/);
  assert.throws(() => parse(['--launch-timeout', '99999999999999999999']), UsageError);
  assert.throws(() => parse([], { CIRCUITJS_MCP_TIMEOUT: String(2 ** 31) }), /CIRCUITJS_MCP_TIMEOUT/);
});

test('an empty environment variable counts as unset', () => {
  assert.equal(parse([], { CIRCUITJS_MCP_URL: '', CIRCUITJS_MCP_TIMEOUT: '' }).options.timeout, DEFAULT_TIMEOUT_MS);
});
