#!/usr/bin/env node
// End-to-end harness for the in-app MCP server (PL_MCP Phase 4, SP_MCP_05) and the path-based
// file contracts of the Agent API (PL_AGA Phase 9: SP_AGA_05_01 file rows, SP_AGA_05_02 R1/R2
// openFile step). Headless Chromium has no Node, so only a real NW.js run exercises the server and
// the file system: every scenario launches the NW.js SDK binary on `target/site` with its own
// scratch HOME (instance registry) and --user-data-dir (preferences), drives the endpoint with raw
// JSON-RPC / the MCP SDK client and the page through CDP, and tears the process group down.
// The bridge scenarios (PL_MCB Phase 4, SP_MCB_05) run the stdio bridge mcp/bridge against
// instances that the bridge itself starts through an app wrapper.
//
// Usage:  node tests/mcp/e2e.mjs [group|scenario ...] [--list]     (after `npm run buildgwt`)
// Groups: default (no argument) | slow | clients | all.  See tests/mcp/README.md.
// Output: one `PASS|FAIL|SKIP <ID> <title> {json}` line per row, then a SUMMARY line.
// Exit code: 0 when no row FAILs, 1 when any row FAILs, 2 on a harness error.

import { spawn, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import net from 'node:net';
import os from 'node:os';
import { fileURLToPath, pathToFileURL } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const PROJECT = process.env.PROJECT_DIR || path.resolve(HERE, '..', '..');
const SITE_DIR = path.resolve(process.env.SITE_DIR || path.join(PROJECT, 'target/site'));
const NW_BIN = process.env.NW_BIN || path.join(PROJECT, 'node_modules/nw/nwjs/nw');
// Artifacts and every scratch HOME / profile go outside the repository (overwritten on every run)
const OUT_DIR = path.resolve(process.env.OUT_DIR || path.join(os.tmpdir(), 'circuitjs-mcp-e2e'));
const VERBOSE = !!process.env.VERBOSE;
const INSPECTOR = process.env.INSPECTOR_PKG || '@modelcontextprotocol/inspector@latest';
const CLAUDE_MODEL = process.env.CLAUDE_MODEL || 'haiku';
const BASE = 7311; // default mcpServerPort
const RANGE = 20; // default mcpServerPortRange
const ALT_PORT = 7400; // the re-enable check of the settings scenario
const SERVER_PORTS = [...Array.from({ length: RANGE }, (_, i) => BASE + i), ALT_PORT];
const LIMIT = 60000; // SP_MCP_01_04 text part limit

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const log = (...a) => console.log(...a);
const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
const clip = (s, n) => (typeof s === 'string' && s.length > n ? s.slice(0, n) + '...' : s);

// ---------------------------------------------------------------- rows and reporting
const results = [];
function emit(status, id, title, summary) {
  results.push({ status, id, title, summary });
  log(`${status} ${id} ${title}${summary === undefined ? '' : ' ' + clip(JSON.stringify(summary), VERBOSE ? 20000 : 1500)}`);
}

/** One verification row (one spec row or one group of related checks); emitted at scenario end. */
class Row {
  constructor(id, title) { this.id = id; this.title = title; this.checks = []; this.notes = {}; this.complete = false; this.skipped = null; }
  ck(name, cond, info) { this.checks.push({ name, pass: !!cond, info: cond && !VERBOSE ? undefined : info }); return !!cond; }
  note(k, v) { this.notes[k] = v; }
  done() { this.complete = true; }
  skip(reason) { this.skipped = reason; }
  emit(error) {
    if (this.emitted) return;
    this.emitted = true;
    if (this.skipped) return emit('SKIP', this.id, this.title, { reason: this.skipped });
    const failed = this.checks.filter((c) => !c.pass);
    const summary = { checks: this.checks.length };
    if (failed.length) summary.failed = failed.map((f) => (f.info === undefined ? f.name : { [f.name]: clipInfo(f.info) }));
    if (VERBOSE) summary.passed = this.checks.filter((c) => c.pass).map((c) => c.name);
    if (Object.keys(this.notes).length) summary.notes = this.notes;
    let ok = failed.length === 0 && this.checks.length > 0 && this.complete;
    if (!this.complete) summary.error = error || 'row not completed';
    else if (!this.checks.length) summary.error = 'no check evaluated';
    emit(ok ? 'PASS' : 'FAIL', this.id, this.title, summary);
  }
}
const clipInfo = (v) => { const s = JSON.stringify(v === undefined ? null : v); return s.length > 500 ? s.slice(0, 500) + '...' : v; };

// ---------------------------------------------------------------- processes, display, ports
const liveGroups = new Set();
let xvfb = null;
function signalGroup(pid, sig) { try { process.kill(-pid, sig); } catch (e) {} }
function groupAlive(pid) { try { process.kill(-pid, 0); return true; } catch (e) { return false; } }
process.on('exit', () => {
  for (const pid of liveGroups) signalGroup(pid, 'SIGKILL');
  if (xvfb) signalGroup(xvfb.pid, 'SIGTERM'); // Xvfb removes its own lock file on SIGTERM
});
for (const sig of ['SIGINT', 'SIGTERM', 'SIGHUP']) process.on(sig, () => process.exit(130));

function which(cmd) {
  for (const d of (process.env.PATH || '').split(path.delimiter)) {
    const p = path.join(d, cmd);
    try { fs.accessSync(p, fs.constants.X_OK); return p; } catch (e) {}
  }
  return null;
}

/** The X display for NW: an own Xvfb (default), or $DISPLAY with NW_DISPLAY=1 / without Xvfb. */
async function ensureDisplay() {
  if (process.env.NW_DISPLAY === '1') {
    if (!process.env.DISPLAY) throw new Error('NW_DISPLAY=1 but DISPLAY is not set');
    return process.env.DISPLAY;
  }
  if (!which('Xvfb')) {
    if (process.env.DISPLAY) { log(`NOTE Xvfb not found: NW windows open on DISPLAY=${process.env.DISPLAY}`); return process.env.DISPLAY; }
    throw new Error('no display: install Xvfb (Arch: xorg-server-xvfb, Debian/Ubuntu: xvfb) or set DISPLAY and NW_DISPLAY=1');
  }
  // -displayfd: Xvfb picks a free display number and writes it once it accepts connections
  const p = spawn('Xvfb', ['-displayfd', '3', '-screen', '0', '1400x900x24', '-nolisten', 'tcp'], { stdio: ['ignore', 'ignore', 'pipe', 'pipe'], detached: true });
  let err = '';
  p.stderr.on('data', (d) => { err += d; });
  const n = await new Promise((resolve, reject) => {
    let buf = '';
    const to = setTimeout(() => { signalGroup(p.pid, 'SIGTERM'); reject(new Error('Xvfb did not start: ' + err.slice(-300))); }, 15000);
    p.stdio[3].on('data', (d) => { buf += d; const m = /(\d+)\s/.exec(buf); if (m) { clearTimeout(to); resolve(m[1]); } });
    p.on('exit', (c) => { clearTimeout(to); reject(new Error(`Xvfb exited (${c}): ` + err.slice(-300))); });
  });
  p.stdio[3].destroy();
  p.stderr.destroy();
  p.unref();
  xvfb = p;
  return ':' + n;
}

const freePort = () => new Promise((res, rej) => { const s = net.createServer(); s.unref(); s.on('error', rej); s.listen(0, '127.0.0.1', () => { const p = s.address().port; s.close(() => res(p)); }); });
const portFree = (port, host = '0.0.0.0') => new Promise((res) => { const s = net.createServer(); s.once('error', () => res(false)); s.listen(port, host, () => s.close(() => res(true))); });
async function busyPorts(ports) { const b = []; for (const p of ports) if (!(await portFree(p))) b.push(p); return b; }
async function waitPortsFree(ports, ms) {
  const t0 = Date.now();
  for (;;) {
    const b = await busyPorts(ports);
    if (!b.length || Date.now() - t0 > ms) return b;
    await sleep(250);
  }
}
// Non-internal IPv4 addresses of this machine (the LAN addresses the app lists for a wildcard host)
const lanIPv4 = () => [...new Set(Object.values(os.networkInterfaces()).flat().filter((a) => a && (a.family === 'IPv4' || a.family === 4) && !a.internal).map((a) => a.address))];
/** Resolves 'open' when a TCP connection to host:port succeeds, else the error code (ECONNREFUSED, ...). */
const tcpProbe = (port, host, ms = 2000) => new Promise((res) => {
  const sock = net.connect(port, host);
  const t = setTimeout(() => { sock.destroy(); res('timeout'); }, ms);
  sock.once('connect', () => { clearTimeout(t); sock.destroy(); res('open'); });
  sock.once('error', (e) => { clearTimeout(t); res(e.code || String(e)); });
});
// Wildcard helpers: on Linux a wildcard bind conflicts with a 127.0.0.1 listener on the same port
// (and the other way round), so these detect and occupy the app's port whatever its listening address.
async function occupy(ports, host = '0.0.0.0') {
  const servers = [];
  for (const p of ports) await new Promise((res, rej) => { const s = net.createServer(); s.once('error', rej); s.listen(p, host, () => { servers.push(s); res(); }); });
  return { close: () => Promise.all(servers.map((s) => new Promise((r) => s.close(r)))) };
}

function rmrf(dir) {
  try { fs.rmSync(dir, { recursive: true, force: true }); } catch (e) { spawnSync('chmod', ['-R', 'u+rwx', dir]); fs.rmSync(dir, { recursive: true, force: true }); }
}

/** Runs a command to completion; {missing} when it is not installed. */
function runProc(cmd, args, { timeout = 180000, cwd, env } = {}) {
  return new Promise((resolve) => {
    let out = ''; let err = ''; let done = false;
    const p = spawn(cmd, args, { cwd: cwd || OUT_DIR, env: env || process.env, stdio: ['ignore', 'pipe', 'pipe'], detached: true });
    if (p.pid) liveGroups.add(p.pid);
    const finish = (r) => { if (done) return; done = true; clearTimeout(to); if (p.pid) liveGroups.delete(p.pid); resolve({ stdout: out, stderr: err, ...r }); };
    const to = setTimeout(() => { signalGroup(p.pid, 'SIGKILL'); finish({ status: null, timedOut: true }); }, timeout);
    p.stdout.on('data', (d) => { out += d; });
    p.stderr.on('data', (d) => { err += d; });
    p.on('error', (e) => finish({ status: null, missing: e.code === 'ENOENT', error: e.message }));
    p.on('close', (c) => finish({ status: c }));
  });
}

// ---------------------------------------------------------------- HTTP / MCP helpers
async function mcpPost(url, body, headers = {}) {
  const r = await fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json', Accept: 'application/json, text/event-stream', ...headers }, body: typeof body === 'string' ? body : JSON.stringify(body) });
  const text = await r.text();
  let json; try { json = JSON.parse(text); } catch (e) {}
  return { status: r.status, text, json, headers: Object.fromEntries(r.headers) };
}

// raw HTTP over a socket: resolves {status, closed, ms} once the server answers and/or closes
function rawHttp(port, chunks, { waitClose = true, timeoutMs = 120000 } = {}) {
  return new Promise((resolve) => {
    const t0 = Date.now();
    const sock = net.connect(port, '127.0.0.1');
    let data = ''; let done = false;
    const finish = (closed) => { if (done) return; done = true; clearTimeout(timer); const m = /^HTTP\/1\.1 (\d+)/.exec(data); resolve({ status: m ? +m[1] : null, closed, ms: Date.now() - t0, head: data.slice(0, 200) }); sock.destroy(); };
    const timer = setTimeout(() => finish(false), timeoutMs);
    sock.on('connect', async () => { for (const c of chunks) { if (typeof c === 'number') await sleep(c); else if (!sock.destroyed) sock.write(c); } });
    sock.on('data', (d) => { data += d.toString('latin1'); if (!waitClose && /\r\n\r\n/.test(data)) finish(false); });
    sock.on('close', () => finish(true));
    sock.on('error', () => {});
  });
}

const instDir = (home) => path.join(home, '.circuitjs1', 'instances');
function records(home) {
  const d = instDir(home);
  if (!fs.existsSync(d)) return [];
  return fs.readdirSync(d).filter((n) => n.endsWith('.json')).map((n) => {
    const p = path.join(d, n);
    let rec = null; try { rec = JSON.parse(fs.readFileSync(p, 'utf8')); } catch (e) {}
    return { name: n, mode: (fs.statSync(p).mode & 0o777).toString(8), rec };
  });
}

let SDK = null;
async function sdkClient(url, name = 'e2e') {
  if (!SDK) {
    const base = path.join(PROJECT, 'node_modules/@modelcontextprotocol/sdk/dist/esm/client');
    const { Client } = await import(pathToFileURL(path.join(base, 'index.js')).href);
    const { StreamableHTTPClientTransport } = await import(pathToFileURL(path.join(base, 'streamableHttp.js')).href);
    SDK = { Client, StreamableHTTPClientTransport };
  }
  const client = new SDK.Client({ name, version: '1' });
  const transport = new SDK.StreamableHTTPClientTransport(new URL(url));
  await client.connect(transport);
  client.transportRef = transport;
  return client;
}

// ---------------------------------------------------------------- CDP and NW launch
class Cdp {
  constructor(ws) {
    this.ws = ws; this.seq = 0; this.pend = new Map();
    ws.addEventListener('message', (ev) => { const m = JSON.parse(ev.data); if (m.id && this.pend.has(m.id)) { this.pend.get(m.id).resolve(m); this.pend.delete(m.id); } });
    ws.addEventListener('close', () => { for (const p of this.pend.values()) p.reject(new Error('CDP connection closed')); this.pend.clear(); });
  }
  static async open(url) { const ws = new WebSocket(url); await new Promise((r, j) => { ws.addEventListener('open', r); ws.addEventListener('error', j); }); return new Cdp(ws); }
  send(method, params, timeoutMs = 240000) {
    return new Promise((resolve, reject) => {
      const id = ++this.seq;
      const to = setTimeout(() => { this.pend.delete(id); reject(new Error(`CDP ${method} timed out`)); }, timeoutMs);
      this.pend.set(id, { resolve: (m) => { clearTimeout(to); resolve(m); }, reject: (e) => { clearTimeout(to); reject(e); } });
      try { this.ws.send(JSON.stringify({ id, method, params })); } catch (e) { clearTimeout(to); this.pend.delete(id); reject(e); }
    });
  }
  async ev(expr, timeoutMs) {
    const m = await this.send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true }, timeoutMs);
    if (!m.result) throw new Error(JSON.stringify(m).slice(0, 300));
    if (m.result.exceptionDetails) throw new Error(JSON.stringify(m.result.exceptionDetails).slice(0, 500));
    return m.result.result.value;
  }
  close() { try { this.ws.close(); } catch (e) {} }
}

let DISPLAY = null;

/**
 * Launches NW.js on SITE_DIR with a scratch HOME and profile (both under OUT_DIR/run/<name> unless
 * given); resolves when the page's CircuitJS1Agent answers and the server left its start state.
 */
async function launchNw(name, { home, udd, env = {}, waitServer = true } = {}) {
  const dir = path.join(OUT_DIR, 'run', name);
  rmrf(dir);
  fs.mkdirSync(dir, { recursive: true });
  home = home || path.join(dir, 'home');
  udd = udd || path.join(dir, 'udd');
  fs.mkdirSync(home, { recursive: true });
  const cdpPort = await freePort();
  const childEnv = { ...process.env, HOME: home, XDG_CONFIG_HOME: path.join(home, '.config'), XDG_CACHE_HOME: path.join(home, '.cache'), XDG_DATA_HOME: path.join(home, '.local/share'), DISPLAY, ...env };
  delete childEnv.WAYLAND_DISPLAY;
  const proc = spawn(NW_BIN, [`--remote-debugging-port=${cdpPort}`, `--user-data-dir=${udd}`, SITE_DIR],
    { stdio: ['ignore', fs.openSync(path.join(dir, 'nw.log'), 'w'), fs.openSync(path.join(dir, 'nw.err'), 'w')], detached: true, env: childEnv, cwd: dir });
  liveGroups.add(proc.pid);
  const waitExit = async (ms) => { const t0 = Date.now(); while (groupAlive(proc.pid)) { if (Date.now() - t0 > ms) return false; await sleep(100); } liveGroups.delete(proc.pid); return true; };
  let cdp = null;
  // SIGTERM, then SIGKILL; resolves once the whole process group is gone (its ports released)
  const kill = async () => {
    if (cdp) cdp.close();
    signalGroup(proc.pid, 'SIGTERM');
    if (!(await waitExit(3000))) { signalGroup(proc.pid, 'SIGKILL'); await waitExit(5000); }
    await sleep(200);
  };
  // graceful quit (localStorage flushed, unload handlers run), then wait for the group to exit
  const quit = async () => {
    try { await Promise.race([cdp.ev('setTimeout(() => nw.App.quit(), 50), true'), sleep(2000)]); } catch (e) {}
    const ok = await waitExit(8000);
    if (!ok) await kill();
    if (cdp) cdp.close();
    await sleep(200);
    return ok;
  };
  const targets = async () => (await (await fetch(`http://127.0.0.1:${cdpPort}/json/list`)).json());
  const status = async () => JSON.parse(await cdp.ev('CircuitJS1Agent.debugMcpStatus()'));
  // any failure of the set-up below ends the process group, so no NW keeps holding the ports
  try {
  let page;
  for (let i = 0; i < 120 && !page; i++) {
    await sleep(500);
    if (!groupAlive(proc.pid)) break;
    try { page = (await targets()).find((t) => t.type === 'page' && /circuitjs\.html/.test(t.url)); } catch (e) {}
  }
  if (!page) throw new Error(`NW page not found (${name}); see ${dir}/nw.err`);
  cdp = await Cdp.open(page.webSocketDebuggerUrl);
  await cdp.send('Runtime.enable');
  let ready = false;
  for (let i = 0; i < 120 && !ready; i++) {
    try { ready = await cdp.ev(`typeof CircuitJS1Agent !== 'undefined' && JSON.parse(CircuitJS1Agent.call('listDocuments','{}')).ok`); } catch (e) {}
    if (!ready) await sleep(500);
  }
  if (!ready) throw new Error(`CircuitJS1Agent not ready (${name})`);
  if (waitServer) {
    for (let i = 0; i < 60; i++) { const s = await status(); if (s.state !== 'starting' && s.state !== 'stopped') break; await sleep(250); }
  }
  } catch (e) { await kill(); throw e; }
  const ev = (expr, t) => cdp.ev(expr, t);
  const A = async (op, args) => JSON.parse(await cdp.ev(`CircuitJS1Agent.call(${JSON.stringify(op)}, ${JSON.stringify(JSON.stringify(args || {}))})`));
  return { name, dir, home, udd, proc, get cdp() { return cdp; }, cdpPort, kill, quit, waitExit, status, targets, ev, A };
}

// ---------------------------------------------------------------- page helpers
// English key plus its translations in the bundled locale files (menu labels follow the system locale)
function texts(key) {
  const out = new Set([key]);
  const dir = path.join(SITE_DIR, 'circuitjs1');
  for (const f of fs.readdirSync(dir).filter((x) => /^locale_.*\.txt$/.test(x))) {
    for (const line of fs.readFileSync(path.join(dir, f), 'utf8').split('\n')) {
      const m = /^"(.*)"="(.*)"\s*$/.exec(line);
      if (m && m[1] === key) out.add(m[2].replace(/<[^>]*>/g, '').replace(/&nbsp;/g, ' ').trim());
    }
  }
  return [...out];
}
let T = null; // menu/dialog texts, read once the build is known to exist

// Options -> "MCP Server..." menu and the info dialog (#mcpServerInfo); injected as window.__M3
function dialogHelpers() {
  const fire = (el, type) => el.dispatchEvent(new MouseEvent(type, { bubbles: true, cancelable: true, view: window }));
  const vis = () => Array.from(document.querySelectorAll('.gwt-MenuItem')).filter((e) => e.offsetWidth > 0);
  const norm = (t) => t.replace(/\s+/g, ' ').trim();
  window.__M3 = {
    // text of the first item of a top menu that starts with one of the given texts (the menu is closed again)
    async menuItemText(top, prefixes) {
      const a = vis().find((e) => top.includes(norm(e.textContent)));
      if (!a) return 'no top';
      fire(a, 'mouseover'); fire(a, 'click');
      await new Promise((r) => setTimeout(r, 300));
      const el = vis().find((e) => prefixes.some((p) => norm(e.textContent).startsWith(p)));
      const t = el ? norm(el.textContent) : null;
      fire(a, 'click');
      await new Promise((r) => setTimeout(r, 200));
      return t;
    },
    async openMenu(top, item) {
      const a = vis().find((e) => top.includes(norm(e.textContent)));
      if (!a) return 'no top';
      fire(a, 'mouseover'); fire(a, 'click');
      await new Promise((r) => setTimeout(r, 300));
      const b = vis().find((e) => item.some((x) => norm(e.textContent) === x || norm(e.textContent).startsWith(x + ' ')));
      if (!b) return 'no item';
      fire(b, 'mouseover'); fire(b, 'click');
      await new Promise((r) => setTimeout(r, 300));
      return 'ok';
    },
    dlg() { return Array.from(document.querySelectorAll('.gwt-DialogBox')).find((d) => d.offsetWidth > 0 && d.querySelector('#mcpServerInfo')) || null; },
    read() {
      const d = this.dlg();
      if (!d) return null;
      const rows = Array.from(d.querySelectorAll('#mcpServerInfo > tbody > tr')).map((tr) => Array.from(tr.children).map((td) => (td.innerText || '').trim().split(/\n+/).join('\n')));
      const inputs = Array.from(d.querySelectorAll('input'));
      const cmd = d.querySelector('#mcpServerCommand');
      const cb = inputs.find((i) => i.type === 'checkbox');
      const tbs = inputs.filter((i) => i.type === 'text' && i.id !== 'mcpServerCommand');
      const buttons = Array.from(d.querySelectorAll('button')).map((b) => ({ text: b.innerText.trim(), disabled: b.disabled }));
      return { rows, command: cmd.value, commandDisabled: cmd.disabled, enabled: cb.checked, port: tbs[0].value, host: tbs[1].value,
        message: (d.querySelector('#mcpServerMessage') || {}).innerText || '', buttons };
    },
    set(enabled, port, host) {
      const d = this.dlg();
      const inputs = Array.from(d.querySelectorAll('input'));
      const cb = inputs.find((i) => i.type === 'checkbox');
      if (enabled !== null && cb.checked !== enabled) cb.click();
      const tbs = inputs.filter((i) => i.type === 'text' && i.id !== 'mcpServerCommand');
      if (port !== null) tbs[0].value = port;
      if (host !== null) tbs[1].value = host;
      return true;
    },
    focusPort() { Array.from(this.dlg().querySelectorAll('input')).filter((i) => i.type === 'text' && i.id !== 'mcpServerCommand')[0].focus(); return true; },
    click(labels) {
      const b = Array.from(this.dlg().querySelectorAll('button')).find((x) => labels.includes(x.innerText.trim()));
      if (!b) return false;
      b.click();
      return true;
    },
    buttonRect(labels) {
      const b = Array.from(this.dlg().querySelectorAll('button')).find((x) => labels.includes(x.innerText.trim()));
      const r = b.getBoundingClientRect();
      return { x: r.x + r.width / 2, y: r.y + r.height / 2 };
    },
    store() { return { enabled: localStorage.getItem('mcpServerEnabled'), port: localStorage.getItem('mcpServerPort'), host: localStorage.getItem('mcpServerHost'), range: localStorage.getItem('mcpServerPortRange') }; },
  };
  return true;
}
const prepDialog = (nw) => nw.ev(`(${dialogHelpers.toString()})()`);
const openDialog = async (nw) => { const r = await nw.ev(`__M3.openMenu(${JSON.stringify(T.options)}, ${JSON.stringify(T.mcp)})`); await sleep(300); return r; };
const readDlg = (nw) => nw.ev('__M3.read()');
const dlgRow = (d, i) => (d && d.rows[i] ? d.rows[i][1] : null);
const menuItem = (nw) => nw.ev(`__M3.menuItemText(${JSON.stringify(T.options)}, ${JSON.stringify(T.mcp)})`);
async function pressKey(nw, code, key, vk) {
  await nw.cdp.send('Input.dispatchKeyEvent', { type: 'rawKeyDown', windowsVirtualKeyCode: vk, nativeVirtualKeyCode: vk, code, key });
  await nw.cdp.send('Input.dispatchKeyEvent', { type: 'keyUp', windowsVirtualKeyCode: vk, nativeVirtualKeyCode: vk, code, key });
}

// [SP_AGA_05_02] R1/R2 helpers: a minimal copy of the tests/live agent_bg page helpers (r1Sample,
// slice probe, Sliders-dialog observer, debugDocState, debugClosedTabs); injected as window.__E
function bgHelpers() {
  const H = {
    slidersDialog() {
      const d = Array.from(document.querySelectorAll('.gwt-DialogBox')).find((x) => x.textContent.includes('Adjustable Sliders'));
      if (!d || d.offsetWidth === 0) return { visible: false, sliders: 0, buttons: [] };
      const buttons = Array.from(d.querySelectorAll('button')).map((b) => b.textContent).filter((t) => !/^[⚙✎]$/.test(t));
      return { visible: true, sliders: d.querySelectorAll('canvas').length, buttons };
    },
    visibleTab() {
      const act = document.querySelector('.tabWidget.activeTab .tabTitle');
      return { tabCount: document.querySelectorAll('.tabWidget').length, activeTitle: act ? act.textContent : null, windowTitle: document.title,
        count: CircuitJS1.getElementCount(), ids: CircuitJS1.getElementIds().join(','), options: String(CircuitJS1.exportCircuit()).split('\n')[0],
        sliders: H.slidersDialog(), view: JSON.parse(CircuitJS1Agent.debugViewState()) };
    },
    r1Sample() {
      const btn = document.querySelector('.icon-stop, .icon-play');
      const info = CircuitJS1.getSimInfo();
      return { vis: H.visibleTab(), session: JSON.parse(CircuitJS1Agent.debugSessionState()), runButton: btn ? (btn.classList.contains('icon-stop') ? 'stop' : 'play') : null,
        running: info.running, stopMessage: info.stopMessage || '' };
    },
    simTime() { return CircuitJS1.getSimInfo().time; },
    startSliceProbe() {
      const st = window.__slices = { list: [], begin: 0 };
      CircuitJS1Agent.debugSetSliceProbe((op, doc, phase) => {
        if (phase === 'begin') { st.begin = performance.now(); return; }
        st.list.push({ op, doc, ms: performance.now() - st.begin, sample: H.r1Sample() });
      });
      return true;
    },
    stopSliceProbe() { CircuitJS1Agent.debugSetSliceProbe(null); return (window.__slices || { list: [] }).list; },
    startSlidersObserver() {
      const d = Array.from(document.querySelectorAll('.gwt-DialogBox')).find((x) => x.textContent.includes('Adjustable Sliders')) || document.body;
      const st = window.__sliderMut = { count: 0, found: d !== document.body };
      st.obs = new MutationObserver((recs) => { st.count += recs.length; });
      st.obs.observe(d, { subtree: true, childList: true, attributes: true, characterData: true });
      return st.found;
    },
    stopSlidersObserver() { const st = window.__sliderMut; if (!st) return null; st.obs.disconnect(); return { count: st.count, found: st.found }; },
    docState(handle) { const r = CircuitJS1Agent.debugDocState(handle); return r ? JSON.parse(r) : null; },
    closedTabs() { return JSON.parse(CircuitJS1Agent.debugClosedTabs()); },
    agentAsync(op, args, timeoutMs) {
      return new Promise((resolve) => {
        const to = setTimeout(() => resolve({ timeout: true }), timeoutMs || 30000);
        CircuitJS1Agent.callAsync(op, JSON.stringify(args || {}), (r) => { clearTimeout(to); resolve(JSON.parse(r)); });
      });
    },
  };
  window.__E = H;
  return true;
}

// ---------------------------------------------------------------- fixtures
const RC = (src = 'VoltageSourceDC', props = { max_voltage: 5 }) => ({ elements: [
  { id: 'V1', type: src, start: { x: 0, y: 4 }, end: { x: 0, y: 0 }, properties: props },
  { id: 'R1', type: 'Resistor', start: { x: 0, y: 0 }, end: { x: 4, y: 0 } },
  { id: 'C1', type: 'Capacitor', start: { x: 4, y: 0 }, end: { x: 4, y: 4 }, properties: { capacitance: '1 uF' } },
  { type: 'Wire', start: { x: 0, y: 4 }, end: { x: 4, y: 4 } },
  { type: 'Ground', start: { x: 0, y: 4 }, end: { x: 0, y: 5 } },
  { id: 'OUT', type: 'LabeledNode', start: { x: 4, y: 0 }, end: { x: 6, y: 0 }, properties: { label: 'out' } }] });
// the tests/live agent_bg fixtures (R1 reference circuit of the visible tab, background circuit)
const R1_REF_FIXTURE = '$ 3 0.000005 14.235633750745258 37 7 43 5e-11\n' +
  'r 176 80 384 80 0 10\ns 384 80 448 80 0 1 false\nw 176 80 176 352 0\nc 384 352 176 352 0 0.000015 -9.86 -10\n' +
  'l 384 80 384 352 0 1 0.03 0\nv 448 352 448 80 0 0 40 5 0 0 0.5\nr 384 352 448 352 0 100\n' +
  'r 448 80 528 80 0 220\nc 528 80 528 352 0 0.00001 0 0\nw 528 352 448 352 0\n' +
  'o 4 64 0 4099 20 0.05 0 2 4 3\no 3 64 0 4099 20 0.05 1 2 3 3\n' +
  '38 3 0 0.000001 0.000101 Capacitance\n38 4 0 0.01 1.01 Inductance\n38 0 0 1 101 Resistance\nh 1 4 3\n';
const R1_BG_FIXTURE = '$ 12 0.00001 5.0 60 3 70 1e-10\n' +
  '174 320 352 384 96 1 1000.0 0.5 Resistance\nv 240 352 240 96 0 0 40.0 5.0 0.0 0.0 0.5\nw 240 96 320 96 0\nw 240 352 320 352 0\n' +
  'r 384 224 480 224 0 470\nc 480 224 480 352 0 0.000001 0 0\nw 480 352 320 352 0\ng 320 352 320 400 0 0\n' +
  'o 5 64 0 4099 5 0.05 0 2 5 3\n38 4 0 10 1000 Rload\nh 2 4 5\n';
const FILE_OPTS = '$ 1 0.000005 10.20027730826997 50 5 50 5e-11';
const appVersion = () => JSON.parse(fs.readFileSync(path.join(SITE_DIR, 'package.json'), 'utf8')).version;
const logsOf = async (nw) => await nw.ev('CircuitJS1.getLogs()');

// ================================================================ scenarios
// SP_MCP_05_01 endpoint and Origin rows, SP_MCP_01_02 record, SP_MCP_02_01 transport rules, File -> Exit
async function scenEndpoint(R) {
  const nw = await launchNw('endpoint');
  try {
    const st = await nw.status();
    const recs = records(nw.home);
    const pid = await nw.ev('process.pid');
    R.record.note('runtime', await nw.ev('({node: process.versions.node, nw: process.versions.nw, flavor: process.versions["nw-flavor"]})'));
    R.record.ck('listening', st.state === 'listening' && st.port === BASE && st.urls[0] === `http://127.0.0.1:${st.port}/mcp`, st);
    const r = recs[0] && recs[0].rec;
    R.record.ck('oneRecord0600', recs.length === 1 && recs[0].mode === '600' && r.pid === pid && r.instanceId.startsWith(pid + '-') && r.port === st.port && r.host === '127.0.0.1'
      && r.urls[0] === st.urls[0] && r.appVersion === appVersion() && typeof r.title === 'string' && /^\d{4}-\d\d-\d\dT.*Z$/.test(r.startedAt)
      && same(r.protocolRevisions, ['2025-11-25', '2025-06-18']) && r.toolsVersion === '1.0' && recs[0].name === r.instanceId + '.json', recs);
    // [SP_MCP_01_01] default listening address 127.0.0.1: loopback URL only, and the port does not
    // answer on the machine's LAN addresses (C_MCP_DEC_02 as amended 2026-10-02)
    const L = R.loopbackOnly;
    L.ck('statusHost', st.host === '127.0.0.1' && same(st.urls, [`http://127.0.0.1:${st.port}/mcp`]), st);
    L.ck('recordUrls', r && r.host === '127.0.0.1' && same(r.urls, [`http://127.0.0.1:${st.port}/mcp`]), r);
    L.ck('loopbackOpen', (await tcpProbe(st.port, '127.0.0.1')) === 'open');
    const lan = lanIPv4();
    L.note('lan', lan);
    for (const ip of lan) L.ck(`lanRefused_${ip}`, (await tcpProbe(st.port, ip)) === 'ECONNREFUSED');
    if (!lan.length) L.note('lanRefused', 'NOT PROVEN: no LAN IPv4 address on this machine (only the record/status host checks ran)');
    L.done();
    R.record.ck('dir0700', (fs.statSync(instDir(nw.home)).mode & 0o777).toString(8) === '700');
    const logs = await logsOf(nw);
    R.record.ck('loggedListening', logs.some((l) => l.includes('MCP server listening on ' + st.urls[0])), logs.slice(-5));
    R.record.done();

    const url = st.urls[0];
    const H = R.handshake;
    const init = (v) => ({ jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: v, capabilities: {}, clientInfo: { name: 'e2e', version: '1' } } });
    let x = await mcpPost(url, init('2025-11-25'));
    H.ck('init2025-11-25', x.status === 200 && x.json.result.protocolVersion === '2025-11-25' && x.json.result.serverInfo.name === 'circuitjs1' && x.json.result.serverInfo.version === appVersion()
      && x.json.id === 1 && !x.headers['mcp-session-id'] && /grid cells/.test(x.json.result.instructions) && /circuitjs-circuits/.test(x.json.result.instructions) && /toolsVersion 1\.0/.test(x.json.result.instructions)
      && x.json.result.capabilities.tools.listChanged === false && x.json.result.capabilities.resources.subscribe === false && !x.json.result.capabilities.prompts, x.json);
    x = await mcpPost(url, init('2025-06-18'));
    H.ck('init2025-06-18', x.json && x.json.result.protocolVersion === '2025-06-18', x.json);
    x = await mcpPost(url, init('2024-11-05'));
    H.ck('initOldGetsLatest', x.json && x.json.result.protocolVersion === '2025-11-25', x.json);
    x = await mcpPost(url, { jsonrpc: '2.0', id: 'd', method: 'server/discover', params: {} }, { 'MCP-Protocol-Version': '2026-07-28' });
    H.ck('discover400', x.status === 400 && x.json && x.json.error, x);
    x = await fetch(url, { method: 'DELETE' });
    H.ck('delete405', x.status === 405);
    x = await mcpPost(url, 'not json');
    H.ck('parse-32700', x.status === 400 && x.json.error.code === -32700, x);
    x = await mcpPost(url, '{"foo":1}');
    H.ck('invalid-32600', x.json && x.json.error.code === -32600, x);
    x = await mcpPost(url, '[{"jsonrpc":"2.0","id":1,"method":"ping"}]');
    H.ck('batch-32600', x.json && x.json.error.code === -32600, x);
    x = await mcpPost(url, { jsonrpc: '2.0', id: 7, method: 'nope/nope' });
    H.ck('unknownMethod-32601', x.status === 200 && x.json.error.code === -32601, x);
    x = await mcpPost(url, { jsonrpc: '2.0', id: 9, method: 'tools/call', params: { name: 'circuit_nope', arguments: {} } });
    H.ck('unknownTool-32602', x.json && x.json.error.code === -32602, x);
    const st2 = await nw.status();
    H.ck('toolCallCounted', st2.toolCalls === 1, st2);
    const [a, b] = await Promise.all([mcpPost(url, { jsonrpc: '2.0', id: 1, method: 'tools/list' }), mcpPost(url, { jsonrpc: '2.0', id: 1, method: 'resources/list' })]);
    H.ck('equalIdsConcurrent', a.json.result.tools && b.json.result.resources && a.json.id === 1 && b.json.id === 1, { a: a.json, b: b.json });
    x = await fetch(url.replace('/mcp', '/other'), { method: 'POST', body: '{}' });
    H.ck('otherPath404', x.status === 404);
    H.done();

    x = await mcpPost(url, { jsonrpc: '2.0', id: 4, method: 'ping' }, { Origin: 'http://example.com' });
    R.originForeign.ck('example.com403', x.status === 403 && x.text === '', x);
    R.originForeign.done();
    x = await mcpPost(url, { jsonrpc: '2.0', id: 6, method: 'ping' });
    R.originNone.ck('noOrigin200', x.status === 200 && x.json && x.json.id === 6, x);
    R.originNone.done();
    x = await mcpPost(url, { jsonrpc: '2.0', id: 4, method: 'ping' }, { Origin: 'null' });
    R.originLocal.ck('nullOrigin403', x.status === 403, x);
    for (const o of ['http://localhost:6274', 'http://127.0.0.1', 'http://[::1]:8080']) {
      x = await mcpPost(url, { jsonrpc: '2.0', id: 5, method: 'ping' }, { Origin: o });
      R.originLocal.ck('local ' + o, x.status === 200 && x.json.id === 5, x);
    }
    R.originLocal.done();
    x = await fetch(url);
    R.get.ck('get405', x.status === 405 && /POST/.test(x.headers.get('allow') || ''), { status: x.status, allow: x.headers.get('allow') });
    R.get.done();
    x = await mcpPost(url, { jsonrpc: '2.0', id: 2, method: 'tools/list' }, { 'MCP-Protocol-Version': '1999-01-01' });
    R.revision.ck('badRevision400', x.status === 400 && x.json && x.json.jsonrpc === '2.0' && x.json.error && typeof x.json.error.code === 'number', x);
    R.revision.done();
    x = await mcpPost(url, { jsonrpc: '2.0', id: 3, method: 'tools/list' }, { 'Mcp-Session-Id': 'arbitrary-123', 'MCP-Protocol-Version': '2025-11-25' });
    R.session.ck('sessionHeaderIgnored', x.status === 200 && Array.isArray(x.json.result.tools) && x.json.result.tools.length === 14 && !x.headers['mcp-session-id'], x);
    R.session.done();
    x = await mcpPost(url, { jsonrpc: '2.0', method: 'notifications/initialized' });
    R.notification.ck('notification202', x.status === 202 && x.text === '', x);
    R.notification.done();
    x = await mcpPost(url, { jsonrpc: '2.0', id: 8, method: 'resources/read', params: { uri: 'circuitjs://nope' } });
    R.unknownUri.ck('unknownUri-32002', x.json && x.json.error.code === -32002, x);
    R.unknownUri.done();

    // File -> Exit (CirSim.stopMcpServer, then close(true)): the File menu is the first item of the
    // menu bar (labels are localised); Exit carries the exit icon
    const clicked = await nw.ev(`(() => { const top = document.querySelector('.gwt-MenuBar-horizontal .gwt-MenuItem'); if (!top) return 'no menu bar'; top.click(); return top.innerText.trim(); })()`);
    await sleep(500);
    const clicked2 = await nw.ev(`(() => { const items = Array.from(document.querySelectorAll('.gwt-MenuItem')).filter((e) => e.querySelector('.cirjsicon-exit')); if (!items.length) return 'no exit item'; setTimeout(() => items[0].click(), 50); return 'ok'; })()`).catch((e) => 'closed: ' + e.message);
    const exited = await nw.waitExit(10000);
    let gone = false; try { await fetch(url); } catch (e) { gone = true; }
    R.exit.ck('fileExitRemovesRecord', exited && records(nw.home).length === 0 && gone, { clicked, clicked2, exited, recs: records(nw.home).length, gone });
    R.exit.done();
  } finally { await nw.kill(); }
}

// Hostile input (PL_MCP Phase 1 review): strict schemas, params, cancellation, oversized bodies
async function scenHostile(R) {
  const nw = await launchNw('hostile');
  try {
    const st = await nw.status();
    const url = st.urls[0];
    const quick = (body, headers) => Promise.race([mcpPost(url, body, headers), sleep(3000).then(() => ({ status: 'hang' }))]);
    for (const [n, body] of [['extraKey', '{"jsonrpc":"2.0","id":11,"method":"ping","extra":1}'], ['paramsArray', '{"jsonrpc":"2.0","id":12,"method":"ping","params":[]}'],
      ['metaString', '{"jsonrpc":"2.0","id":13,"method":"ping","params":{"_meta":"x"}}'], ['idObject', '{"jsonrpc":"2.0","id":{},"method":"ping"}'], ['idNull', '{"jsonrpc":"2.0","id":null,"method":"ping"}']]) {
      const x = await quick(body);
      R.strict.ck(n, x.status === 400 && x.json && x.json.error.code === -32600, x);
    }
    const bad3 = await Promise.all([1, 2, 3].map((i) => quick(`{"jsonrpc":"2.0","id":${i},"method":"tools/list","x":${i}}`)));
    R.strict.ck('concurrent3', bad3.every((x) => x.status === 400), bad3.map((x) => x.status));
    let x = await quick('{"jsonrpc":"2.0","method":"notifications/initialized","extra":1}');
    R.strict.ck('badNotification202', x.status === 202, x);
    R.strict.done();
    x = await quick({ jsonrpc: '2.0', id: 20, method: 'initialize' });
    R.params.ck('initNoParams', x.json && x.json.error.code === -32602 && /params/.test(x.json.error.message) && !/[[{]\s*"/.test(x.json.error.message), x.json);
    x = await quick({ jsonrpc: '2.0', id: 21, method: 'initialize', params: { protocolVersion: 123, capabilities: {}, clientInfo: { name: 'a', version: '1' } } });
    R.params.ck('initBadVersion', x.json && x.json.error.code === -32602 && /protocolVersion/.test(x.json.error.message), x.json);
    x = await quick({ jsonrpc: '2.0', id: 22, method: 'resources/read', params: {} });
    R.params.ck('readNoUri', x.json && x.json.error.code === -32602 && /uri/.test(x.json.error.message), x.json);
    x = await quick({ jsonrpc: '2.0', id: 23, method: 'tools/call', params: { arguments: {} } });
    R.params.ck('callNoName', x.json && x.json.error.code === -32602 && /name/.test(x.json.error.message), x.json);
    R.params.done();
    const pings = Array.from({ length: 30 }, (_, i) => quick({ jsonrpc: '2.0', id: 100 + i, method: 'tools/list' }));
    const cancels = [];
    for (let k = 1; k <= 400; k++) cancels.push(quick({ jsonrpc: '2.0', method: 'notifications/cancelled', params: { requestId: 'mcp-' + k, reason: 'x' } }));
    const pr = await Promise.all(pings); const cr = await Promise.all(cancels);
    R.cancel.ck('cancelDropped', pr.every((r) => r.status === 200 && r.json.result) && cr.every((r) => r.status === 202), { pings: pr.filter((r) => r.status !== 200).length, cancels: cr.filter((r) => r.status !== 202).length });
    R.cancel.done();
    const r1 = await rawHttp(st.port, ['POST /mcp HTTP/1.1\r\nHost: x\r\nContent-Type: application/json\r\nContent-Length: 20000000\r\n\r\n'], { timeoutMs: 10000 });
    R.size.ck('declared413Closed', r1.status === 413 && r1.closed && r1.ms < 3000, r1);
    const big = Buffer.alloc(1024 * 1024, 0x20);
    const streamed = ['POST /mcp HTTP/1.1\r\nHost: x\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\n\r\n'];
    for (let i = 0; i < 18; i++) streamed.push(big.length.toString(16) + '\r\n', big, '\r\n');
    streamed.push('0\r\n\r\n');
    const r2 = await rawHttp(st.port, streamed, { timeoutMs: 20000 });
    R.size.ck('streamed413Closed', r2.status === 413 && r2.closed, r2);
    x = await quick({ jsonrpc: '2.0', id: 30, method: 'ping' });
    R.size.ck('servesAfterHostile', x.status === 200, x);
    R.size.done();
  } finally { await nw.kill(); }
}

// slow: incomplete requests are closed by the server (headersTimeout 10 s, requestTimeout 60 s; Node checks every 30 s)
async function scenHostileSlow(R) {
  const nw = await launchNw('hostile-slow');
  try {
    const st = await nw.status();
    const [h, b] = await Promise.all([
      rawHttp(st.port, ['POST /mcp HTTP/1.1\r\nHost: x\r\n'], { timeoutMs: 110000 }),
      rawHttp(st.port, ['POST /mcp HTTP/1.1\r\nHost: x\r\nContent-Length: 100\r\n\r\n{"jsonrpc"'], { timeoutMs: 110000 }),
    ]);
    R.incomplete.ck('incompleteHeadersClosed', h.closed && h.ms < 50000, h);
    R.incomplete.ck('incompleteBodyClosed', b.closed && b.ms < 100000, b);
    R.incomplete.note('ms', { headers: h.ms, body: b.ms });
    const x = await mcpPost(st.urls[0], { jsonrpc: '2.0', id: 31, method: 'ping' });
    R.incomplete.ck('servesAfterSlow', x.status === 200, x);
    R.incomplete.done();
  } finally { await nw.kill(); }
}

async function scenPortBusy(R) {
  const occ = await occupy([BASE]);
  try {
    const nw = await launchNw('portbusy');
    try {
      const st = await nw.status();
      const recs = records(nw.home);
      R.busy.ck('nextPort', st.state === 'listening' && st.port === BASE + 1 && st.urls[0] === `http://127.0.0.1:${BASE + 1}/mcp`, st);
      R.busy.ck('recordSaysNextPort', recs.length === 1 && recs[0].rec.port === BASE + 1, recs);
      R.busy.done();
    } finally { await nw.kill(); }
  } finally { await occ.close(); }
}

async function scenAllBusy(R) {
  const occ = await occupy(SERVER_PORTS.slice(0, RANGE));
  try {
    const nw = await launchNw('allbusy');
    try {
      await prepDialog(nw);
      const st = await nw.status();
      const B = R.allBusy;
      B.ck('statusFailed', st.state === 'failed' && /no free port in range 7311\.\.7330/.test(st.reason || ''), st);
      const logs = await logsOf(nw);
      B.ck('logged', logs.some((l) => /MCP server failed: no free port in range 7311\.\.7330/.test(l)), logs.slice(-5));
      B.ck('noRecord', records(nw.home).length === 0, records(nw.home));
      const usable = await nw.A('createDocument', {});
      B.ck('appUsable', usable.ok === true, usable);
      const item = await menuItem(nw);
      B.ck('menuWithoutOff', item && T.mcp.includes(item), item);
      await openDialog(nw);
      const d = await readDlg(nw);
      B.ck('dialogShowsReason', dlgRow(d, 0) === 'failed: no free port in range 7311..7330' && d.command === '' && dlgRow(d, 2) === '—', d && d.rows);
      B.done();
    } finally { await nw.kill(); }
  } finally { await occ.close(); }
}

// SP_MCP_05_02 one record per live instance; the close paths of SP_MCP_02_05
async function scenInstances(R) {
  const nw = await launchNw('instances');
  try {
    const st1 = await nw.status();
    // "New window" = ActionManager's nw.Window.open(..., {new_instance: true})
    await nw.ev(`nw.Window.open('circuitjs.html', {new_instance: true, mixed_context: false}); true`);
    let recs = [];
    for (let i = 0; i < 80 && recs.length < 2; i++) { await sleep(500); recs = records(nw.home); }
    const O = R.oneRecord;
    const ports = recs.map((r) => r.rec && r.rec.port).sort();
    O.ck('twoRecords', recs.length === 2 && ports[0] !== ports[1] && new Set(recs.map((r) => r.rec.pid)).size === 2, recs.map((r) => r.rec));
    const second = recs.find((r) => r.rec && r.rec.port !== st1.port);
    // the second server may still be starting when its record appears: retry the ping briefly
    let x = null;
    for (let i = 0; second && i < 20; i++) { try { x = await mcpPost(second.rec.urls[0], { jsonrpc: '2.0', id: 1, method: 'ping' }); break; } catch (e) { await sleep(250); } }
    O.ck('secondServes', x && x.status === 200, x);
    // close the second window like a window-manager close: nw.Window.get().close() (no force)
    let closed = 'no second page';
    for (const p of (await nw.targets()).filter((t) => t.type === 'page' && /circuitjs\.html/.test(t.url))) {
      const c = await Cdp.open(p.webSocketDebuggerUrl);
      const pid = await c.ev('process.pid').catch(() => null);
      if (second && pid === second.rec.pid) closed = await c.ev(`setTimeout(() => nw.Window.get().close(), 50); 'ok'`).catch((e) => 'err ' + e.message);
      c.close();
    }
    for (let i = 0; i < 40 && records(nw.home).length !== 1; i++) await sleep(250);
    recs = records(nw.home);
    O.ck('closeOneLeavesOne', recs.length === 1 && recs[0].rec.port === st1.port, { closed, recs: recs.map((r) => r.rec && r.rec.port) });
    let refused = false; try { await mcpPost(second.rec.urls[0], { jsonrpc: '2.0', id: 1, method: 'ping' }); } catch (e) { refused = true; }
    O.ck('closedPortReleased', refused);
    O.done();
    // close(true) of the first (the File -> Exit call without the menu) removes its record
    await nw.ev(`setTimeout(() => nw.Window.get().close(true), 50); 'ok'`).catch(() => {});
    for (let i = 0; i < 40 && records(nw.home).length; i++) await sleep(250);
    R.closeTrue.ck('closeTrueRemovesRecord', records(nw.home).length === 0, records(nw.home));
    R.closeTrue.done();
  } finally { await nw.kill(); }
}

async function scenStale(R) {
  const dir = path.join(OUT_DIR, 'run', 'stale');
  rmrf(dir);
  const home = path.join(dir, 'home');
  const idir = instDir(home);
  fs.mkdirSync(idir, { recursive: true });
  // a dead pid: a shell that has exited
  const dead = spawnSync('sh', ['-c', 'echo $$'], { cwd: dir }).stdout.toString().trim() | 0;
  const rec = (pid, port) => ({ instanceId: `${pid}-1700000000000`, pid, port, host: '127.0.0.1', urls: [`http://127.0.0.1:${port}/mcp`], appVersion: '1.3.2', startedAt: '2023-11-14T22:13:20.000Z', title: 't', protocolRevisions: ['2025-11-25'], toolsVersion: '1.0' });
  fs.writeFileSync(path.join(idir, `${dead}-1700000000000.json`), JSON.stringify(rec(dead, 7399)));
  fs.writeFileSync(path.join(idir, `${dead}-1700000000001.json.tmp`), '{');
  fs.writeFileSync(path.join(idir, `${process.pid}-1700000000000.json`), JSON.stringify(rec(process.pid, 7398))); // live: this harness
  fs.writeFileSync(path.join(idir, 'garbage.json'), 'not json');
  fs.writeFileSync(path.join(idir, 'README.txt'), 'x');
  const nw = await launchNw('stale-nw', { home });
  try {
    const names = fs.readdirSync(idir).sort();
    const st = await nw.status();
    const S = R.crash;
    S.ck('deadRecordAndTempDeleted', !names.includes(`${dead}-1700000000000.json`) && !names.includes(`${dead}-1700000000001.json.tmp`), names);
    S.ck('liveForeignOwnKept', names.includes(`${process.pid}-1700000000000.json`) && names.includes('garbage.json') && names.includes('README.txt') && names.includes(st.instanceId + '.json'), names);
    const logs = await logsOf(nw);
    S.ck('logged', logs.some((l) => /removed 2 stale instance record/.test(l)), logs.slice(-5));
    S.done();
  } finally { await nw.kill(); }
}

// SP_MCP_05_01 tool/resource/sizing rows and the SP_MCP_05_02 invariants. A page recorder wraps
// CircuitJS1Agent.callAsync (the server looks the method up per call), so each tool result can be
// compared with the OperationResult of the Agent API call the server actually made.
const RECORDER = `(() => { const A = window.CircuitJS1Agent; if (A.__rec) return 'already'; const orig = A.callAsync; A.__rec = [];
  A.callAsync = function (op, args, cb) { return orig.call(A, op, args, function (r) { A.__rec.push({ op, args: JSON.parse(args), res: r }); cb(r); }); }; return 'ok'; })()`;
const textOf = (r) => r.content[0].text;
const noteOf = (r) => (textOf(r).startsWith('[') ? textOf(r).split('\n')[0] : '');

async function scenTools(R) {
  const nw = await launchNw('tools');
  const all = [];
  const recorded = async () => JSON.parse(await nw.ev(`JSON.stringify(CircuitJS1Agent.__rec.splice(0))`)).map((r) => ({ op: r.op, args: r.args, res: JSON.parse(r.res) }));
  let c = null;
  const tool = async (name, args) => {
    let r = null; let err = null;
    try { r = await c.callTool({ name, arguments: args }); } catch (e) { err = { code: e.code, message: e.message }; }
    const rec = await recorded();
    if (r) all.push({ name, args, r, rec });
    return { r, err, rec };
  };
  const rejectOf = async (fn) => { try { await fn(); return null; } catch (e) { return e; } };
  try {
    const st = await nw.status();
    const url = st.urls[0];
    await nw.ev(RECORDER);
    c = await sdkClient(url, 'e2e-tools');
    const S = R.sdk;
    S.ck('handshake', c.getServerVersion().name === 'circuitjs1' && c.transportRef.protocolVersion === '2025-11-25', { v: c.getServerVersion(), rev: c.transportRef.protocolVersion });
    const tl = await c.listTools();
    S.ck('tools14', tl.tools.length === 14 && tl.tools.every((t) => /^circuit_/.test(t.name) && t.outputSchema && t.annotations), tl.tools.map((t) => t.name));
    const rl = await c.listResources();
    S.ck('resources4', rl.resources.length === 4, rl.resources.map((x) => x.uri));
    await c.ping();
    S.done();
    const tpl = await c.listResourceTemplates();
    R.templates.ck('threeTemplates', tpl.resourceTemplates.map((t) => t.uriTemplate).join() === 'circuitjs://catalogue/{type},circuitjs://documents/{doc}/circuit,circuitjs://examples/{path}', tpl);
    R.templates.done();

    const K = R.coverage;
    let x = await tool('circuit_import', { circuit: RC() });
    K.ck('import', x.r && !x.r.isError && x.r.structuredContent.data.elements === 6, x.r && x.r.structuredContent);
    x = await tool('circuit_edit', { edits: [{ op: 'set', id: 'R1', properties: { nope: 1 } }] });
    R.editDomain.ck('isErrorUnknownProperty', x.r && x.r.isError === true && x.r.structuredContent.issues[0].code === 'unknown_property', x.r && x.r.structuredContent);
    R.editDomain.done();
    x = await tool('circuit_edit', { edits: { op: 'add' } });
    R.editSchema.ck('jsonRpc-32602', x.err && x.err.code === -32602 && /edits/.test(x.err.message), x.err);
    R.editSchema.ck('noAgentCall', x.rec.length === 0, x.rec);
    R.editSchema.done();
    x = await tool('circuit_edit', { edits: [{ op: 'set', id: 'R1', properties: { resistance: '4.7k' } }, { op: 'move', id: 'OUT', by: { dx: 0, dy: -0.5 } }, { op: 'move', id: 'OUT', by: { dx: 0, dy: 0.5 } }] });
    K.ck('edit', x.r && !x.r.isError && x.r.structuredContent.data.applied === 3, x.r && x.r.structuredContent);
    x = await tool('circuit_render', { format: 'png' });
    const img = x.r && x.r.content[1];
    R.renderPng.ck('imagePart', img && img.type === 'image' && img.mimeType === 'image/png' && Buffer.from(img.data, 'base64').subarray(1, 4).toString() === 'PNG', x.r && x.r.content.map((p) => p.type));
    R.renderPng.ck('textCarriesImageMarker', x.r && /"content":"<image>"/.test(textOf(x.r)) && x.r.structuredContent.data.content === '<image>', x.r && textOf(x.r).slice(0, 300));
    R.renderPng.done();
    x = await tool('circuit_render', { format: 'svg' });
    K.ck('renderSvg', x.r && !x.r.isError && /^<\?xml|<svg/.test(x.r.structuredContent.data.content) && x.r.content.length === 1, x.r && textOf(x.r).slice(0, 200));

    let rr = await c.readResource({ uri: 'circuitjs://catalogue/Resistor' });
    const ti = JSON.parse(rr.contents[0].text);
    R.catalogue.ck('typeInfo', ti.type === 'Resistor' && ti.geometry === 'two_point' && ti.pins.length === 2 && rr.contents[0].mimeType === 'application/json', ti);
    rr = await c.readResource({ uri: 'circuitjs://catalogue' });
    R.catalogue.ck('index', JSON.parse(rr.contents[0].text).types.length > 100);
    R.catalogue.done();
    const U = R.resOther;
    let e = await rejectOf(() => c.readResource({ uri: 'circuitjs://nope' }));
    U.ck('unknownUri-32002', e && e.code === -32002, e && e.message);
    e = await rejectOf(() => c.readResource({ uri: 'circuitjs://catalogue/Resistr' }));
    U.ck('unknownType-32002', e && e.code === -32002, e && e.message);
    e = await rejectOf(() => c.readResource({ uri: 'circuitjs://examples/..%2Fcircuitjs.html' }));
    U.ck('outsideIndex-32002', e && e.code === -32002, e && e.message);
    rr = await c.readResource({ uri: 'circuitjs://docs/agent-format' });
    U.ck('agentFormat', rr.contents[0].mimeType === 'text/markdown' && /grid cells/.test(rr.contents[0].text));
    rr = await c.readResource({ uri: 'circuitjs://documents' });
    U.ck('documents', JSON.parse(rr.contents[0].text).documents.length === 1);
    U.done();
    rr = await c.readResource({ uri: 'circuitjs://examples' });
    const idx = JSON.parse(rr.contents[0].text);
    rr = await c.readResource({ uri: 'circuitjs://examples/' + encodeURIComponent(idx[0].path) });
    R.examples.ck('indexAndText', idx.length > 300 && idx[0].path === 'ohms.txt' && rr.contents[0].mimeType === 'text/plain' && /^\$ /.test(rr.contents[0].text), { n: idx.length, first: idx[0], text: rr.contents[0].text.slice(0, 80) });
    R.examples.done();
    await recorded(); // resource reads are not tool results

    // circuit round trip d1 -> d2
    rr = await c.readResource({ uri: 'circuitjs://documents/d1/circuit' });
    const d1c = JSON.parse(rr.contents[0].text);
    x = await tool('circuit_documents', { action: 'create', title: 'copy' });
    const d2 = x.r.structuredContent.data.doc;
    x = await tool('circuit_import', { doc: d2, circuit: d1c });
    R.roundtrip.ck('import', x.r && !x.r.isError, x.r && x.r.structuredContent);
    const g1 = await tool('circuit_get', { doc: 'd1', detail: 'full' });
    const g2 = await tool('circuit_get', { doc: d2, detail: 'full' });
    R.roundtrip.ck('d2EqualsD1', same(g1.r.structuredContent.data, g2.r.structuredContent.data), { a: JSON.stringify(g1.r.structuredContent.data).slice(0, 300), b: JSON.stringify(g2.r.structuredContent.data).slice(0, 300) });
    R.roundtrip.done();

    // the other tools, for the coverage and the invariants
    await tool('circuit_types', { type: 'Capacitor' });
    await tool('circuit_types', { filter: 'mosfet' });
    await tool('circuit_connectivity', {});
    await tool('circuit_read', { targets: [{ net: 'out' }, { element: 'R1', quantity: 'current' }] });
    x = await tool('circuit_read', { targets: [{ net: 'vout' }] });
    K.ck('readUnknownNet', x.r.isError && x.r.structuredContent.issues[0].code === 'unknown_net');
    await tool('circuit_sim', { action: 'configure', settings: { maxTimeStep: '2 us' } });
    x = await tool('circuit_run', { span: '5 ms', reset: true, probes: [{ net: 'out' }] });
    K.ck('run', x.r && !x.r.isError && x.r.structuredContent.data.reason === 'span_reached', x.r && textOf(x.r).slice(0, 300));
    await tool('circuit_diagnostics', { log: { limit: 5 } });
    x = await tool('circuit_checkpoint', { comment: 'rc built' });
    K.ck('checkpoint', x.r.structuredContent.data.checkpointId === 'cp1', x.r.structuredContent);
    await tool('circuit_history', { action: 'list' });
    await tool('circuit_history', { action: 'undo' });
    await tool('circuit_history', { action: 'redo' });
    await tool('circuit_file', { action: 'export', format: 'text' });
    x = await tool('circuit_file', { action: 'save', path: '/etc/x.conf' });
    R.fileRule.ck('fileNotAllowed', x.r.isError && x.r.structuredContent.issues[0].code === 'file_not_allowed' && !fs.existsSync('/etc/x.conf'), x.r.structuredContent);
    const saved = path.join(nw.dir, 'rc.json');
    x = await tool('circuit_file', { action: 'save', path: saved });
    R.fileRule.ck('saveJsonPath', !x.r.isError && fs.existsSync(saved), x.r.structuredContent);
    x = await tool('circuit_file', { action: 'open', path: saved });
    R.fileRule.ck('openJsonPath', !x.r.isError && /^d\d+$/.test(x.r.structuredContent.data.doc), x.r.structuredContent);
    R.fileRule.done();
    await tool('circuit_documents', { action: 'list' });
    await tool('circuit_documents', { action: 'close', doc: x.r.structuredContent.data.doc });
    await tool('circuit_documents', { action: 'activate', doc: 'd1' });
    await tool('circuit_sim', { action: 'stop' });

    // 16 probes at Σ maxPoints = 2000 on an AC source, at the largest series the decimation gives.
    // SP_AGA_03_07: B = ⌊125/2⌋ = 62 buckets of min+max, so a probe emits at most 124 points (16 × 124 =
    // 1984; no even split of 2000 over 16 probes fills every probe at once, as the bucket counts differ).
    // A fixed 5 µs step and a reset run give one sample per step; 3920 samples fill all 62 buckets
    // (bucket width 64 samples after the merges: 61 × 64 < 3920 ≤ 62 × 64).
    await tool('circuit_import', { doc: d2, circuit: RC('VoltageSourceAC', { max_voltage: 5, frequency: '1 kHz' }) });
    await tool('circuit_sim', { doc: d2, action: 'configure', settings: { maxTimeStep: '5 us', autoTimeStep: false } });
    const probes = Array.from({ length: 16 }, (_, i) => (i % 2 ? { name: 'i' + i, element: 'R1', quantity: 'current' } : { name: 'v' + i, net: 'out' }));
    x = await tool('circuit_run', { doc: d2, span: '19.6 ms', reset: true, probes, maxPoints: 125 });
    const perProbe = x.r.structuredContent.data.probes.map((p) => p.series.t.length);
    const pts = perProbe.reduce((n, k) => n + k, 0);
    const run16 = { chars: textOf(x.r).length, points: pts, perProbe: [...new Set(perProbe)], steps: x.r.structuredContent.data.steps };
    R.textLimit.ck('run16ProbesFull', !x.r.isError && perProbe.length === 16 && perProbe.every((k) => k === 124) && pts === 1984, run16);
    R.textLimit.ck('run16ProbesText', !x.r.isError && textOf(x.r).length <= LIMIT && !noteOf(x.r), run16);
    R.textLimit.note('run16', run16);

    // the largest bundled example: oversized circuit_get, connectivity, non-reducible export
    const alu = fs.readFileSync(path.join(SITE_DIR, 'circuitjs1/circuits/alu74181.txt'), 'utf8');
    await tool('circuit_import', { doc: d2, circuit: alu });
    x = await tool('circuit_get', { doc: d2, detail: 'full', limit: 500 });
    const note = noteOf(x.r);
    const sizing = { chars: textOf(x.r).length, note, nextOffset: x.r.structuredContent.data.nextOffset, calls: x.rec.map((r) => ({ detail: r.args.detail, limit: r.args.limit })) };
    R.sizing.ck('textWithinLimit', !x.r.isError && textOf(x.r).length <= LIMIT, sizing);
    R.sizing.ck('noteNamesReducedArgs', /detail="concise" instead of "full"/.test(note) && /limit=\d+ instead of 500/.test(note), sizing);
    R.sizing.ck('nextOffset', x.r.structuredContent.data.nextOffset !== undefined, sizing);
    R.sizing.note('get', sizing);
    R.sizing.done();
    R.huge.ck('conciseRetry', x.rec.length >= 2 && x.rec[x.rec.length - 1].args.detail === 'concise' && x.r.structuredContent.data.nextOffset !== undefined, sizing);
    x = await tool('circuit_connectivity', { doc: d2 });
    R.textLimit.ck('connectivityAlu', textOf(x.r).length <= LIMIT, { chars: textOf(x.r).length, note: noteOf(x.r) });
    x = await tool('circuit_get', { doc: d2 });
    R.textLimit.ck('getDefaultAlu', textOf(x.r).length <= LIMIT, { chars: textOf(x.r).length, note: noteOf(x.r) });
    x = await tool('circuit_file', { doc: d2, action: 'export', format: 'json' });
    R.huge.ck('nonReducibleTooLarge', x.r.isError && x.r.structuredContent.issues[0].code === 'result_too_large' && textOf(x.r).length <= LIMIT, x.r.structuredContent.issues[0]);
    R.huge.done();
    x = await tool('circuit_render', { doc: d2, format: 'png', scale: 1 });
    R.textLimit.ck('renderPngAlu', !x.r.isError && x.r.content[1] && textOf(x.r).length <= LIMIT, { chars: textOf(x.r).length });

    // invariants over every tool result of this scenario
    const bad = { logic: [], isError: [], text: [] };
    for (const a of all) {
      const eff = a.rec[a.rec.length - 1];
      if (!eff) { bad.logic.push(a.name + ': no agent call'); continue; }
      let expected = eff.res;
      const sc = a.r.structuredContent;
      const serverReject = sc.issues && sc.issues[0] && sc.issues[0].code === 'result_too_large';
      if (a.name === 'circuit_render' && expected.ok && expected.data.format === 'png') expected = { ...expected, data: { ...expected.data, content: '<image>' } };
      if (!serverReject && !same(sc, expected)) bad.logic.push(a.name + ' ' + JSON.stringify(a.args).slice(0, 80));
      if (a.r.isError !== (sc.ok === false)) bad.isError.push(a.name);
      if (textOf(a.r).length > LIMIT) bad.text.push(a.name);
    }
    R.noLogic.ck('structuredContentEqualsAgentResult', bad.logic.length === 0, bad.logic);
    R.noLogic.note('results', all.length);
    R.noLogic.done();
    R.isError.ck('isErrorIffNotOk', bad.isError.length === 0, bad.isError);
    R.isError.note('results', all.length);
    R.isError.done();
    R.textLimit.ck('everyResult', bad.text.length === 0, bad.text);
    R.textLimit.done();
    K.ck('all14ToolsCalled', new Set(all.map((a) => a.name)).size === 14, [...new Set(all.map((a) => a.name))]);
    K.ck('counterCountsCalls', (await nw.status()).toolCalls >= all.length, { toolCalls: (await nw.status()).toolCalls, made: all.length });
    K.done();
  } finally { if (c) await c.close().catch(() => {}); await nw.kill(); }
}

// SP_MCP_05_03 long run while reading (5 s budget; the 120 s budget in the slow group)
async function scenLong(R, budgetMs) {
  const nw = await launchNw('long' + budgetMs);
  let c1 = null; let c2 = null;
  try {
    const st = await nw.status();
    c1 = await sdkClient(st.urls[0], 'runner');
    c2 = await sdkClient(st.urls[0], 'reader');
    await c1.callTool({ name: 'circuit_import', arguments: { circuit: RC() } });
    const t0 = Date.now();
    let runEnd = 0;
    const run = c1.callTool({ name: 'circuit_run', arguments: { span: 1000, budgetMs, reset: true, probes: [{ net: 'out' }] } }, undefined, { timeout: 250000 })
      .then((r) => { runEnd = Date.now() - t0; return r; });
    run.catch(() => {}); // handled by the await below; keeps a failure during the reads from being unhandled
    await sleep(500);
    const reads = [];
    for (let i = 0; i < 3; i++) {
      const s = Date.now();
      const g = await c2.callTool({ name: 'circuit_get', arguments: {} });
      reads.push({ ms: Date.now() - s, at: Date.now() - t0, ok: !g.isError });
      await sleep(budgetMs > 10000 ? 20000 : 1000);
    }
    const r = await run;
    const d = r.structuredContent.data;
    R.long.ck('readsAnswerBeforeRunEnds', reads.every((x) => x.ok && x.at < runEnd) && reads[0].ms < 2000, { reads, runEnd });
    R.long.ck('runAnswers', !r.isError && d.reason === 'budget_exhausted' && d.wallMs >= budgetMs && runEnd < budgetMs + 10000, { reason: d.reason, wallMs: d.wallMs, runEnd });
    R.long.note('timing', { readsMs: reads.map((x) => x.ms), runEndMs: runEnd, wallMs: d.wallMs });
    R.long.done();
  } finally {
    if (c1) await c1.close().catch(() => {});
    if (c2) await c2.close().catch(() => {});
    await nw.kill();
  }
}

// SP_MCP_05_01 Info dialog rows: listening, counter; Copy / Close / Escape
async function scenDialog(R) {
  const nw = await launchNw('dialog');
  try {
    await prepDialog(nw);
    const st = await nw.status();
    const L = R.listening;
    L.ck('statusListening', st.state === 'listening', st);
    const item = await menuItem(nw);
    L.ck('menuItemWithoutOff', item && T.mcp.includes(item), item);
    L.ck('opens', (await openDialog(nw)) === 'ok');
    let d = await readDlg(nw);
    const cmd = `claude mcp add --transport http circuitjs ${st.urls[0]}`;
    L.ck('status', dlgRow(d, 0) === 'listening', d && d.rows);
    L.ck('instanceId', dlgRow(d, 1) === st.instanceId, [dlgRow(d, 1), st.instanceId]);
    L.ck('urls', dlgRow(d, 2) === st.urls.join('\n') && st.urls.length === 1 && /^http:\/\/127\.0\.0\.1:\d+\/mcp$/.test(st.urls[0]), [dlgRow(d, 2), st.urls]); // default host: loopback URL only
    L.ck('commandLine', d && d.command === cmd && !d.commandDisabled, d && d.command);
    L.ck('settingsDefaults', d && d.enabled === true && d.port === String(BASE) && d.host === '127.0.0.1', d);
    L.done();
    const C = R.counter;
    C.ck('counter0', dlgRow(d, 3) === '0', dlgRow(d, 3));
    const url = st.urls[0];
    await mcpPost(url, { jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: '2025-11-25', capabilities: {}, clientInfo: { name: 'e2e', version: '0' } } });
    for (let i = 0; i < 3; i++) await mcpPost(url, { jsonrpc: '2.0', id: 10 + i, method: 'tools/call', params: { name: 'circuit_types', arguments: {} } });
    await sleep(200);
    d = await readDlg(nw);
    C.ck('counter3Live', dlgRow(d, 3) === '3', dlgRow(d, 3));
    // Copy: a real click through CDP input (a user gesture), then the system clipboard
    const P = R.copyClose;
    await nw.ev(`nw.Clipboard.get().set('before', 'text'), true`);
    const rect = await nw.ev(`__M3.buttonRect(${JSON.stringify(T.copy)})`);
    for (const type of ['mouseMoved', 'mousePressed', 'mouseReleased']) await nw.cdp.send('Input.dispatchMouseEvent', { type, x: rect.x, y: rect.y, button: 'left', clickCount: 1 });
    await sleep(400);
    const clip = await nw.ev(`nw.Clipboard.get().get('text')`);
    P.ck('copyToClipboard', clip === cmd, clip);
    P.ck('closeButton', await nw.ev(`__M3.click(${JSON.stringify(T.close)})`));
    await sleep(200);
    P.ck('closed', (await readDlg(nw)) === null);
    await mcpPost(url, { jsonrpc: '2.0', id: 20, method: 'tools/call', params: { name: 'circuit_types', arguments: {} } });
    await openDialog(nw);
    d = await readDlg(nw);
    C.ck('reopenCounter4', dlgRow(d, 3) === '4', dlgRow(d, 3));
    C.done();
    await pressKey(nw, 'Escape', 'Escape', 27);
    await sleep(300);
    P.ck('escapeCloses', (await readDlg(nw)) === null);
    await mcpPost(url, { jsonrpc: '2.0', id: 21, method: 'tools/call', params: { name: 'circuit_types', arguments: {} } });
    P.ck('listenerRemovedNoError', (await nw.status()).toolCalls === 5);
    P.done();
  } finally { await nw.kill(); }
}

// Info dialog settings: rejects, disable -> restart -> disabled -> re-enable -> restart, one profile
async function scenSettings(R) {
  const base = path.join(OUT_DIR, 'run', 'settings_shared');
  rmrf(base);
  const home = path.join(base, 'home');
  const udd = path.join(base, 'udd');
  fs.mkdirSync(home, { recursive: true });
  // 1) invalid settings are rejected; then untick Enabled and Save
  let nw = await launchNw('settings1', { home, udd });
  try {
    await prepDialog(nw);
    const st = await nw.status();
    R.reject.ck('listening', st.state === 'listening', st);
    await openDialog(nw);
    const before = await nw.ev('__M3.store()');
    for (const [port, host, re] of [['80', '0.0.0.0', /1024/], ['abc', '0.0.0.0', /1024/], ['65530', '0.0.0.0', /65535/], ['7311', '999.1.1.1', /IPv4/], ['7311', 'example.com', /IPv4/]]) {
      await nw.ev(`__M3.set(null, ${JSON.stringify(port)}, ${JSON.stringify(host)})`);
      await nw.ev(`__M3.click(${JSON.stringify(T.save)})`);
      const d = await readDlg(nw);
      const stored = await nw.ev('__M3.store()');
      R.reject.ck(`${port}/${host}`, d && d.message && same(stored, before) && (re.test(d.message) || /[А-Яа-яІіЇїЄє]/.test(d.message)), { msg: d && d.message, stored });
    }
    R.reject.done();
    await nw.ev(`__M3.set(false, '7311', '127.0.0.1')`);
    await nw.ev(`__M3.click(${JSON.stringify(T.save)})`);
    const d = await readDlg(nw);
    const stored = await nw.ev('__M3.store()');
    const D = R.disable;
    D.ck('savedMessage', d && (/next start/.test(d.message) || /наступного запуску/.test(d.message)), d && d.message);
    D.ck('savedStorage', stored.enabled === 'false' && stored.port === '7311' && stored.host === '127.0.0.1', stored);
    D.ck('stillListeningUntilRestart', (await nw.status()).state === 'listening' && dlgRow(d, 0) === 'listening');
    await nw.ev(`__M3.click(${JSON.stringify(T.close)})`);
  } finally { R.disable.ck('gracefulQuit', await nw.quit()); }
  R.disable.ck('recordRemovedOnQuit', records(home).length === 0, records(home));
  // 2) restart: disabled, no port bound, no record, menu "(off)", dialog shows disabled
  nw = await launchNw('settings2', { home, udd });
  try {
    await prepDialog(nw);
    const st = await nw.status();
    const D = R.disable;
    D.ck('statusDisabled', st.state === 'disabled' && st.reason === 'disabled in preferences', st);
    D.ck('noRecord', records(home).length === 0, records(home));
    D.ck('noPortBound', (await busyPorts(SERVER_PORTS)).length === 0);
    const item = await menuItem(nw);
    D.ck('menuOff', item && T.off.some((o) => item.endsWith(' ' + o)), item);
    await openDialog(nw);
    const d = await readDlg(nw);
    D.ck('dialogDisabled', dlgRow(d, 0) === 'disabled' && dlgRow(d, 1) === '—' && dlgRow(d, 2) === '—' && d.command === '' && d.commandDisabled
      && d.buttons.some((b) => T.copy.includes(b.text) && b.disabled) && d.enabled === false, d);
    D.done();
    // re-enable on another base port, opened to the network (0.0.0.0); Enter in a settings field saves
    await nw.ev(`__M3.set(true, '7400', '0.0.0.0')`);
    await nw.ev('__M3.focusPort()');
    await pressKey(nw, 'Enter', 'Enter', 13);
    await sleep(300);
    const d2 = await readDlg(nw);
    const stored = await nw.ev('__M3.store()');
    R.reenable.ck('enterSaves', d2 && stored.enabled === 'true' && stored.port === '7400' && stored.host === '0.0.0.0', { stored, msg: d2 && d2.message });
    if (d2) await nw.ev(`__M3.click(${JSON.stringify(T.close)})`);
  } finally { await nw.quit(); }
  // 3) restart: listening on 0.0.0.0:7400 (loopback URL first, then one per LAN address; a LAN
  // URL answers), menu without "(off)"
  nw = await launchNw('settings3', { home, udd });
  try {
    await prepDialog(nw);
    const st = await nw.status();
    const E = R.reenable;
    const wantUrls = [`http://127.0.0.1:${ALT_PORT}/mcp`].concat(lanIPv4().map((ip) => `http://${ip}:${ALT_PORT}/mcp`));
    E.ck('listening7400', st.state === 'listening' && st.port === ALT_PORT && st.host === '0.0.0.0' && same(st.urls, wantUrls), { st, wantUrls });
    const rec = records(home);
    E.ck('recordWildcard', rec.length === 1 && rec[0].rec.host === '0.0.0.0' && same(rec[0].rec.urls, wantUrls), rec);
    if (st.urls[1]) { const x = await mcpPost(st.urls[1], { jsonrpc: '2.0', id: 10, method: 'ping' }); E.ck('lanUrl', x.status === 200, x); } else E.note('lanUrl', 'no LAN address');
    const item = await menuItem(nw);
    E.ck('menuOn', item && T.mcp.includes(item), item);
    await openDialog(nw);
    const d = await readDlg(nw);
    E.ck('dialog', dlgRow(d, 0) === 'listening' && d.command === `claude mcp add --transport http circuitjs http://127.0.0.1:${ALT_PORT}/mcp` && d.port === '7400' && d.host === '0.0.0.0' && dlgRow(d, 2) === wantUrls.join('\n'), d);
    E.done();
  } finally { await nw.quit(); }
}

// PL_AGA Phase 9: real openFile/saveFile (SP_AGA_05_01 file rows, SP_AGA_02_14, §03_09)
function makeFileFixtures(FIX) {
  rmrf(FIX);
  fs.mkdirSync(FIX, { recursive: true });
  const W = (n, t) => fs.writeFileSync(path.join(FIX, n), t);
  W('notes.txt', 'Meeting notes for Tuesday\nTalk to the team about the circuit simulator\nremember: buy resistors\n');
  W('a.txt', 'Hello world\nthis file is not a circuit\n');
  W('empty.txt', '');
  W('blank.txt', '\n  \n');
  W('c.txt', FILE_OPTS + '\nr 0 0 64 0 0 100\n');
  W('secret.md', 'secret notes\n');
  fs.symlinkSync(path.join(FIX, 'secret.md'), path.join(FIX, 'link.txt'));
  W('c2.txt', FILE_OPTS + '\nr 0 0 64 0 0 220\n');
  fs.symlinkSync(path.join(FIX, 'c2.txt'), path.join(FIX, 'link2.txt'));
  fs.symlinkSync(path.join(FIX, 'gone.txt'), path.join(FIX, 'dangling.txt'));
  fs.mkdirSync(path.join(FIX, 'dir.txt'));
  W('big.txt', FILE_OPTS + '\n' + 'r 0 0 64 0 0 100\n'.repeat(700000)); // > 10 MB
  W('throws.txt', FILE_OPTS + '\n174 0 0 64 0 1\n'); // a known code whose parse fails
  W('bad.json', JSON.stringify({ schema: { format: 'circuitjs', version: '2.0' }, elements: { SecretKey1: { type: 'Resistor', pins: { a: { position: { x: 0.5, y: 0 } }, b: { position: { x: 64, y: 0 } } } } } }));
  W('bom.txt', '﻿' + FILE_OPTS + '\nr 0 0 64 0 0 100\n');
  fs.mkdirSync(path.join(FIX, 'ro'));
  W('ro/r.txt', FILE_OPTS + '\nr 0 0 64 0 0 100\n');
  fs.chmodSync(path.join(FIX, 'ro'), 0o555);
}

async function scenFiles(R) {
  const nw = await launchNw('files', { waitServer: false });
  const FIX = path.join(nw.dir, 'fixtures');
  try {
    makeFileFixtures(FIX);
    const P = (n) => path.join(FIX, n);
    const snap = (n) => { const p = P(n); return { t: fs.readFileSync(p, 'utf8'), m: fs.statSync(p).mtimeMs }; };
    const untouched = { a: snap('a.txt'), notes: snap('notes.txt'), secret: snap('secret.md') };
    const A = nw.A;
    const ds = async (d) => JSON.parse(await nw.ev(`CircuitJS1Agent.debugDocState(${JSON.stringify(d)})`));
    const vis = () => nw.ev(`({ title: document.title, tabs: Array.from(document.querySelectorAll('.tabWidget')).map(t => t.innerText.trim()).join('|'), active: (document.querySelector('.tabWidget.activeTab .tabTitle')||{}).innerText, text: CircuitJS1.exportCircuit() })`);
    const code0 = (r) => r && r.issues && r.issues[0] && r.issues[0].code;
    const activeDoc = async () => (await A('listDocuments')).data.documents.find((d) => d.active).doc;
    await sleep(1000);
    const V = await activeDoc();
    await nw.ev(`CircuitJS1.importCircuit(${JSON.stringify(FILE_OPTS + '\nr 0 0 64 0 0 100\nw 64 0 64 64 0\n')}, false); true`);
    await sleep(300);
    const vis0 = await vis();

    // saveFile json -> openFile with identical IDs
    const SJ = R.saveJson;
    const X = (await A('createDocument', {})).data.doc;
    const imp = await A('importCircuit', { doc: X, circuit: { elements: [
      { id: 'Rload', type: 'Resistor', start: { x: 0, y: 0 }, end: { x: 4, y: 0 }, properties: { resistance: 470 } },
      { id: 'Wtop', type: 'Wire', start: { x: 4, y: 0 }, end: { x: 4, y: 4 } }] } });
    SJ.ck('import', imp.ok, imp);
    const idsX = (await A('getCircuit', { doc: X })).data.elements.map((e) => e.id);
    const sj = await A('saveFile', { doc: X, path: P('x.json') });
    SJ.ck('saved', sj.ok && sj.data.path === P('x.json') && sj.data.bytes === fs.statSync(P('x.json')).size, sj);
    const onDisk = JSON.parse(fs.readFileSync(P('x.json'), 'utf8'));
    SJ.ck('jsonCircuitOnDisk', onDisk.schema && onDisk.schema.format === 'circuitjs' && Object.keys(onDisk.elements).join() === idsX.join(), Object.keys(onDisk.elements || {}));
    const stX = await ds(X);
    SJ.ck('fileStateAndSeal', stX.modified === false && /x\.json/.test(stX.title) && JSON.stringify(stX.ui).includes(P('x.json')) && stX.transaction.open === false, { title: stX.title, modified: stX.modified, tr: stX.transaction });
    const oj = await A('openFile', { path: P('x.json') });
    const Y = oj.data && oj.data.doc;
    const idsY = Y && (await A('getCircuit', { doc: Y })).data.elements.map((e) => e.id);
    SJ.ck('openSameIds', oj.ok && same(idsY, idsX), { oj, idsX, idsY });
    const stY = Y && await ds(Y);
    SJ.ck('openedFileState', stY && stY.modified === false && /x\.json/.test(stY.title) && stY.undo === 1 && stY.transaction.open === false, stY && { title: stY.title, modified: stY.modified, undo: stY.undo });
    const vNow = await vis();
    SJ.ck('openNewNoTabSwitch', same(vNow, { ...vis0, tabs: vNow.tabs }) && (await activeDoc()) === V, { vis0, vNow });
    SJ.done();

    R.saveMd.ck('fileNotAllowed', code0(await A('saveFile', { doc: X, path: P('notes.md') })) === 'file_not_allowed' && !fs.existsSync(P('notes.md')));
    R.saveMd.done();
    R.saveForeign.ck('fileNotAllowedUntouched', code0(await A('saveFile', { doc: X, path: P('a.txt') })) === 'file_not_allowed' && same(snap('a.txt'), untouched.a));
    R.saveForeign.done();
    R.saveProse.ck('fileNotAllowedUntouched', code0(await A('saveFile', { doc: X, path: P('notes.txt') })) === 'file_not_allowed' && same(snap('notes.txt'), untouched.notes));
    R.saveProse.done();

    const SR = R.saveRules;
    SR.ck('relativePath', code0(await A('saveFile', { doc: X, path: 'rel.txt' })) === 'file_not_allowed');
    SR.ck('linkToMd', code0(await A('saveFile', { doc: X, path: P('link.txt') })) === 'file_not_allowed' && same(snap('secret.md'), untouched.secret));
    const se = await A('saveFile', { doc: X, path: P('empty.txt') });
    SR.ck('overwriteEmpty', se.ok && fs.readFileSync(P('empty.txt'), 'utf8').startsWith('$'), se);
    SR.ck('overwriteBlank', (await A('saveFile', { doc: X, path: P('blank.txt') })).ok);
    const sc = await A('saveFile', { doc: X, path: P('c.txt') });
    SR.ck('overwriteCircuit', sc.ok && /\br 0 0 64 0 0 470\b/.test(fs.readFileSync(P('c.txt'), 'utf8')), sc);
    const sl = await A('saveFile', { doc: X, path: P('link2.txt') });
    SR.ck('throughSymlinkKeepsLink', sl.ok && fs.lstatSync(P('link2.txt')).isSymbolicLink() && /470/.test(fs.readFileSync(P('c2.txt'), 'utf8')), sl);
    SR.ck('danglingLink', code0(await A('saveFile', { doc: X, path: P('dangling.txt') })) === 'file_error' && !fs.existsSync(P('gone.txt')));
    SR.ck('directoryTarget', code0(await A('saveFile', { doc: X, path: P('dir.txt') })) === 'file_not_allowed');
    const smd = await A('saveFile', { doc: X, path: P('nodir/x.txt') });
    SR.ck('missingParentNotCreated', code0(smd) === 'file_error' && !fs.existsSync(P('nodir')), smd);
    const sro = await A('saveFile', { doc: X, path: P('ro/new.txt') });
    SR.ck('readOnlyDirEacces', code0(sro) === 'file_error' && /EACCES|permission/i.test(sro.issues[0].message), sro);
    SR.ck('noStagingLeft', fs.readdirSync(FIX).filter((f) => f.endsWith('.tmp')).length === 0, fs.readdirSync(FIX));
    const Z = (await A('createDocument', {})).data.doc;
    SR.ck('noPath', code0(await A('saveFile', { doc: Z })) === 'no_path');
    SR.ck('formatOverridesExtension', (await A('saveFile', { doc: X, path: P('t.json'), format: 'text' })).ok && fs.readFileSync(P('t.json'), 'utf8').startsWith('$'));
    await A('applyEdits', { doc: X, edits: [{ op: 'set', id: 'Rload', properties: { resistance: 1000 } }] });
    const stOpen = await ds(X);
    SR.ck('editOpensTransaction', stOpen.transaction.open === true && stOpen.modified === true, stOpen.transaction);
    const sp = await A('saveFile', { doc: X });
    const stX2 = await ds(X);
    SR.ck('saveToDocumentPathSeals', sp.ok && sp.data.path === P('t.json') && stX2.transaction.open === false && stX2.modified === false && /1 kOhm/.test(fs.readFileSync(P('t.json'), 'utf8')), { sp, tr: stX2.transaction });
    const hist = await A('getHistory', { doc: X });
    SR.ck('sealedAuto', hist.ok && hist.data.undo[0] && hist.data.undo[0].auto === true, hist.data && hist.data.undo && hist.data.undo[0]);
    SR.done();

    // openFile rejections
    const nDocs = async () => (await A('listDocuments')).data.documents.length;
    const n0 = await nDocs();
    const closed0 = JSON.parse(await nw.ev('CircuitJS1Agent.debugClosedTabs()')).length;
    R.openMissing.ck('fileNotFound', code0(await A('openFile', { path: P('missing.txt') })) === 'file_not_found');
    R.openMissing.ck('missingDirectory', code0(await A('openFile', { path: P('nodir/missing.txt') })) === 'file_not_found');
    R.openMissing.ck('danglingLink', code0(await A('openFile', { path: P('dangling.txt') })) === 'file_not_found');
    R.openMissing.done();
    const OR = R.openRules;
    OR.ck('md', code0(await A('openFile', { path: P('secret.md') })) === 'file_not_allowed');
    OR.ck('linkToMd', code0(await A('openFile', { path: P('link.txt') })) === 'file_not_allowed');
    OR.ck('directory', code0(await A('openFile', { path: P('dir.txt') })) === 'file_not_allowed');
    OR.ck('over10MB', code0(await A('openFile', { path: P('big.txt') })) === 'file_not_allowed');
    const op = await A('openFile', { path: P('notes.txt') });
    OR.ck('proseNoDisclosure', code0(op) === 'file_not_allowed' && !/Meeting|Talk|remember/.test(JSON.stringify(op)), op);
    const ot = await A('openFile', { path: P('throws.txt') });
    OR.ck('loadErrorNoDisclosure', ot.ok === false && !/174 0 0/.test(JSON.stringify(ot)), ot);
    const ob = await A('openFile', { path: P('bad.json') });
    OR.ck('jsonOffLatticeNoKeys', ob.ok === false && code0(ob) === 'off_lattice' && !/SecretKey1/.test(JSON.stringify(ob)), ob);
    OR.ck('noDocumentCreated', (await nDocs()) === n0);
    OR.ck('noClosedTabEntry', JSON.parse(await nw.ev('CircuitJS1Agent.debugClosedTabs()')).length === closed0);
    const obom = await A('openFile', { path: P('bom.txt') });
    OR.ck('bomOpens', obom.ok && obom.data.elements === 1, obom);
    OR.done();

    // into a background handle: transaction, file state, visible tab unchanged; rejected; activate
    const OI = R.openInto;
    const vis1 = await vis();
    const oh = await A('openFile', { path: P('c2.txt'), into: Z });
    const stZ = await ds(Z);
    OI.ck('intoHandle', oh.ok && oh.data.doc === Z && oh.transaction && oh.transaction.open === true && oh.connectivity && stZ.modified === false && /c2\.txt/.test(stZ.title), { oh, title: stZ.title, modified: stZ.modified });
    const vis2 = await vis();
    OI.ck('visibleUnchanged', vis2.title === vis1.title && vis2.text === vis1.text && vis2.active === vis1.active, { vis1, vis2 });
    const ohr = await A('openFile', { path: P('throws.txt'), into: Z });
    const zText = (await A('exportCircuit', { doc: Z, format: 'text' })).data.content;
    OI.ck('rejectedIntoHandleUnchanged', ohr.ok === false && ohr.transaction && /470/.test(zText), { ohr, zText });
    const oa = await A('openFile', { path: P('c.txt'), activate: true });
    const title = await nw.ev('document.title');
    OI.ck('activate', oa.ok && (await activeDoc()) === oa.data.doc && /c\.txt/.test(title), { oa, title });
    OI.done();

    // a staging name that already exists (EEXIST from 'wx') is not ours: refused and left in place
    const SG = R.staging;
    fs.writeFileSync(P('.e.txt.rs0.tmp'), 'not ours\n');
    const ee = JSON.parse(await nw.ev(`(() => { const ws = [window, ...Array.from(document.querySelectorAll('iframe')).map((f) => { try { return f.contentWindow; } catch (e) { return null; } }).filter(Boolean)];
      const saved = ws.map((w) => [w.Date.now, w.Math.random]); ws.forEach((w) => { w.Date.now = () => 1000; w.Math.random = () => 0; });
      try { return CircuitJS1Agent.call('saveFile', JSON.stringify({ doc: ${JSON.stringify(X)}, path: ${JSON.stringify(P('e.txt'))} })); } finally { ws.forEach((w, i) => { w.Date.now = saved[i][0]; w.Math.random = saved[i][1]; }); } })()`));
    SG.ck('eexistRefusedLeftInPlace', code0(ee) === 'file_error' && /EEXIST/.test(ee.issues[0].message) && fs.readFileSync(P('.e.txt.rs0.tmp'), 'utf8') === 'not ours\n' && !fs.existsSync(P('e.txt')), ee);
    fs.unlinkSync(P('.e.txt.rs0.tmp'));
    const fr = await A('saveFile', { doc: X, path: P('fresh.txt') });
    SG.ck('freshSaveBytes', fr.ok && fr.data.bytes === fs.statSync(P('fresh.txt')).size, fr);
    SG.done();

    // busy policy during a run (class table): saveFile served, openFile into the busy handle busy, into new served
    const BU = R.busy;
    await nw.ev(`window.__runDone = null; CircuitJS1Agent.callAsync('run', JSON.stringify({ doc: ${JSON.stringify(X)}, span: '10 s', budgetMs: 1500 }), (r) => { window.__runDone = JSON.parse(r); }); true`);
    await sleep(200);
    const busyList = (await A('listDocuments')).data.documents.find((d) => d.doc === X);
    const bs = await A('saveFile', { doc: X, path: P('busy.txt') });
    const bo = await A('openFile', { path: P('c.txt'), into: X });
    const bn = await A('openFile', { path: P('c.txt') });
    BU.ck('saveServed', busyList.busy === true && bs.ok && fs.existsSync(P('busy.txt')), { busy: busyList.busy, bs });
    BU.ck('openIntoBusy', code0(bo) === 'busy' && bo.transaction, bo);
    BU.ck('openNewServed', bn.ok, bn);
    for (let i = 0; i < 40 && !(await nw.ev('window.__runDone')); i++) await sleep(100);
    BU.ck('runCompleted', !!(await nw.ev('window.__runDone')));
    BU.done();
  } finally {
    try { fs.chmodSync(path.join(FIX, 'ro'), 0o755); } catch (e) {}
    await nw.kill();
  }
}

// [SP_AGA_05_02] R1 and R2 with the openFile step on a background document (PL_AGA Phase 9).
// The other R1/R2 steps run headless in tests/live agent_bg; this repeats the sequence in NW.js,
// where openFile reads a real file.
async function scenBgFiles(R) {
  const nw = await launchNw('bg_files', { waitServer: false });
  try {
    await nw.ev(`(${bgHelpers.toString()})()`);
    const E = (fn, ...args) => nw.ev(`__E.${fn}(${args.map((a) => JSON.stringify(a)).join(', ')})`);
    const A = nw.A;
    const AA = (op, args, t) => E('agentAsync', op, args, t || 30000);
    const codes = (r) => ((r && r.issues) || []).map((i) => (r.ok ? i.code : `${i.code}: ${clip(i.message, 160)}`));
    const firstDiff = (a, b, p = '') => {
      if (same(a, b)) return null;
      if (a && b && typeof a === 'object' && typeof b === 'object') {
        for (const k of new Set([...Object.keys(a), ...Object.keys(b)])) { const d = firstDiff(a[k], b[k], p + '.' + k); if (d) return d; }
      }
      return { path: p, a: JSON.stringify(a).slice(0, 200), b: JSON.stringify(b).slice(0, 200) };
    };
    const fixDir = path.join(nw.dir, 'fixtures');
    fs.mkdirSync(fixDir, { recursive: true });
    const r1File = path.join(fixDir, 'r1.txt');
    const r2File = path.join(fixDir, 'r2.txt');
    fs.writeFileSync(r1File, R1_BG_FIXTURE.replace('r 384 224 480 224 0 470', 'r 384 224 480 224 0 820'));
    fs.writeFileSync(r2File, R1_BG_FIXTURE.replace('r 384 224 480 224 0 470', 'r 384 224 480 224 0 1500'));
    await sleep(1000);

    // ------------------------------------------------------------ R1: the visible tab free-runs
    const R1 = R.r1;
    await nw.ev(`CircuitJS1.setSimRunning(false); true`);
    await nw.ev(`CircuitJS1.importCircuit(${JSON.stringify(R1_REF_FIXTURE)}, false); true`);
    await sleep(300);
    const V = (await A('listDocuments', {})).data.documents.find((d) => d.active).doc;
    await nw.ev(`CircuitJS1.setSimRunning(true); true`);
    await sleep(800);
    const base = await E('r1Sample');
    R1.ck('refFixture', base.vis.count === 10 && base.session.checks.smallGrid === true && base.vis.sliders.sliders === 3 && base.vis.view.hint === '1 4 3' && base.running === true,
      { count: base.vis.count, smallGrid: base.session.checks.smallGrid, sliders: base.vis.sliders, hint: base.vis.view.hint, running: base.running });
    await sleep(500);
    R1.ck('baselineStable', same(await E('r1Sample'), base), firstDiff(await E('r1Sample'), base));
    const samples = [];
    const sample = async (label) => { samples.push({ label, sample: await E('r1Sample') }); };
    const observed = await E('startSlidersObserver');
    const rate = async (ms) => { const t0 = await E('simTime'); const w0 = Date.now(); await sleep(ms); return ((await E('simTime')) - t0) / ((Date.now() - w0) / 1000); };
    const idleRate = await rate(2000);
    // the SP_AGA_05_02 R1 sequence on background document X, with openFile into it
    const X = (await A('createDocument', { title: 'R1 X' })).data.doc;
    await sample('createDocument');
    const imp = await A('importCircuit', { doc: X, format: 'text', circuit: R1_BG_FIXTURE });
    await sample('importCircuit');
    // a checkpoint seals the import, so the undo below reverts the edit only (as in tests/live agent_bg)
    const cp = await A('checkpoint', { doc: X, comment: 'imported' });
    await sample('checkpoint');
    const ed = await A('applyEdits', { doc: X, edits: [{ op: 'add', element: { id: 'R9', type: 'Resistor', start: { x: 40, y: 0 }, end: { x: 44, y: 0 } } }] });
    await sample('applyEdits');
    const un = await A('undo', { doc: X });
    await sample('undo');
    await E('startSliceProbe');
    const tRun0 = await E('simTime'); const wRun0 = Date.now();
    const run = await AA('run', { doc: X, span: 1000, budgetMs: 2000, probes: [{ element: 'C1' }] });
    const runRate = ((await E('simTime')) - tRun0) / ((Date.now() - wRun0) / 1000);
    await sample('run');
    const png = await AA('render', { doc: X });
    await sample('render');
    const slices = await E('stopSliceProbe');
    const exT = await A('exportCircuit', { doc: X, format: 'text' });
    await sample('exportCircuit');
    const of = await A('openFile', { path: r1File, into: X });
    await sample('openFile');
    const stOf = await E('docState', X);
    const xText = (await A('exportCircuit', { doc: X, format: 'text' })).data.content;
    const cl = await A('closeDocument', { doc: X, discardChanges: true });
    await sample('closeDocument');
    const mut = await E('stopSlidersObserver');
    R1.ck('sequenceOk', imp.ok && cp.ok && ed.ok && un.ok && run.ok && png.ok && exT.ok && of.ok && cl.ok,
      { imp: codes(imp), cp: codes(cp), ed: codes(ed), un: codes(un), run: codes(run), png: codes(png), exT: codes(exT), of: codes(of), cl: codes(cl) });
    R1.ck('openFileLoadedIntoX', of.ok && of.data.doc === X && /\b820\b/.test(xText) && stOf && stOf.modified === false && /r1\.txt/.test(stOf.title), { of, title: stOf && stOf.title, modified: stOf && stOf.modified });
    const norm = (x) => ({ ...x, vis: { ...x.vis, tabCount: 0 } });
    const tabs = (label) => base.vis.tabCount + (label === 'closeDocument' ? 0 : 1);
    const bad = [];
    for (const x of samples) if (!same(norm(x.sample), norm(base)) || x.sample.vis.tabCount !== tabs(x.label)) bad.push({ label: x.label, diff: firstDiff(norm(x.sample), norm(base)) || { tabCount: x.sample.vis.tabCount, want: tabs(x.label) } });
    for (const x of slices) if (!same(norm(x.sample), norm(base)) || x.sample.vis.tabCount !== base.vis.tabCount + 1) bad.push({ label: x.op + ' slice', diff: firstDiff(norm(x.sample), norm(base)) });
    R1.ck('everySampleUnchanged', bad.length === 0 && samples.length === 10 && slices.length > 10, { bad: bad.slice(0, 5), samples: samples.length, slices: slices.length });
    R1.ck('openFileSampleUnchanged', !bad.some((b) => b.label === 'openFile'), bad.filter((b) => b.label === 'openFile'));
    R1.ck('slidersNotRebuilt', observed && mut && mut.count === 0, mut);
    R1.ck('rateAtLeastHalf', idleRate > 0 && runRate / idleRate >= 0.5, { idleRate, runRate });
    R1.ck('neverSwitchesTabs', samples.every((x) => x.sample.vis.activeTitle === base.vis.activeTitle) && (await A('listDocuments', {})).data.documents.find((d) => d.active).doc === V);
    R1.note('timing', { rateRatio: +(runRate / idleRate).toFixed(3), slices: slices.length, maxSliceMs: +Math.max(...slices.map((x) => x.ms)).toFixed(1), steps: run.data && run.data.steps });
    R1.done();
    await nw.ev(`CircuitJS1.setSimRunning(false); true`);

    // ------------------------------------------------------------ R2: X in the background vs Y active
    const R2 = R.r2;
    const seq = async (d) => {
      const r = [];
      r.push(await A('importCircuit', { doc: d, format: 'text', circuit: R1_BG_FIXTURE }));
      r.push(await A('checkpoint', { doc: d, comment: 'imported' }));
      r.push(await A('applyEdits', { doc: d, edits: [{ op: 'add', element: { id: 'R9', type: 'Resistor', start: { x: 40, y: 0 }, end: { x: 44, y: 0 } } }, { op: 'set', id: 'R1', properties: { resistance: '680' } }] }));
      r.push(await A('undo', { doc: d }));
      r.push(await A('applyEdits', { doc: d, edits: [{ op: 'set', id: 'R1', properties: { resistance: '330' } }] }));
      r.push(await AA('run', { doc: d, span: '20 ms', probes: [{ element: 'C1' }] }));
      r.push(await AA('render', { doc: d }));
      r.push(await A('exportCircuit', { doc: d, format: 'text' }));
      r.push(await A('openFile', { path: r2File, into: d }));
      return r;
    };
    const r2State = async (d) => {
      const st = await E('docState', d);
      const text = (await A('exportCircuit', { doc: d, format: 'text' })).data.content;
      const listed = (await A('listDocuments', {})).data.documents.find((x) => x.doc === d);
      return { text, ui: st.ui, title: st.title, modified: st.modified, undo: st.undo, redo: st.redo, listedTitle: listed && listed.title, listedModified: listed && listed.modified, logs: st.logs };
    };
    const RX = (await A('createDocument', { title: 'R2' })).data.doc;
    const seqX = await seq(RX);
    const stX = await r2State(RX);
    const RY = (await A('createDocument', { title: 'R2' })).data.doc;
    await A('activateDocument', { doc: RY });
    await sleep(200);
    const seqY = await seq(RY);
    const stY = await r2State(RY);
    R2.ck('sequencesOk', seqX.every((r) => r.ok) && seqY.every((r) => r.ok), { x: seqX.map((r) => r.ok || codes(r)), y: seqY.map((r) => r.ok || codes(r)) });
    const maskScopeScales = (t) => t.split('\n').map((l) => { const f = l.split(' '); if (f[0] === 'o' && f.length > 6) { f[5] = f[6] = '*'; } return f.join(' '); }).join('\n');
    R2.ck('circuitText', maskScopeScales(stX.text) === maskScopeScales(stY.text) && /\b1500\b/.test(stX.text), { x: stX.text.slice(0, 300), y: stY.text.slice(0, 300) });
    R2.ck('uiState', same(stX.ui, stY.ui), firstDiff(stX.ui, stY.ui));
    R2.ck('titleModifiedPath', stX.title === stY.title && stX.modified === stY.modified && stX.modified === false && stX.ui.filePath === stY.ui.filePath && stX.ui.filePath === r2File
      && /r2\.txt/.test(stX.title) && stX.listedTitle === stY.listedTitle, { x: [stX.title, stX.modified, stX.ui.filePath, stX.listedTitle], y: [stY.title, stY.modified, stY.ui.filePath, stY.listedTitle] });
    R2.ck('undoDepth', stX.undo === stY.undo && stX.redo === stY.redo, { x: [stX.undo, stX.redo], y: [stY.undo, stY.redo] });
    const domainLogs = (l) => (l || []).filter((x) => !/^Save option: SlidersDialog\./.test(x));
    R2.ck('logBuffer', same(domainLogs(stX.logs), domainLogs(stY.logs)) && domainLogs(stX.logs).length > 0, firstDiff(domainLogs(stX.logs), domainLogs(stY.logs)));
    const closedBefore = (await E('closedTabs')).length;
    await A('closeDocument', { doc: RX, discardChanges: true });
    await A('closeDocument', { doc: RY, discardChanges: true });
    const closed = await E('closedTabs');
    R2.ck('closedTabDump', closed.length === Math.min(20, closedBefore + 2) && maskScopeScales(closed[closed.length - 1]) === maskScopeScales(closed[closed.length - 2]), { n: closed.length, closedBefore });
    await A('activateDocument', { doc: V });
    R2.done();
  } finally { await nw.kill(); }
}

// optional real clients: MCP Inspector CLI (npx, network) and Claude Code (credentials, a cheap model)
async function scenClients(R) {
  const nw = await launchNw('clients');
  try {
    const st = await nw.status();
    const url = st.urls[0];
    if (!which('npx')) R.inspector.skip('npx not found');
    else {
      const out = {};
      for (const m of ['tools/list', 'resources/list', 'resources/templates/list']) {
        const p = await runProc('npx', ['-y', INSPECTOR, '--cli', url, '--transport', 'http', '--method', m], { timeout: 180000, cwd: nw.dir });
        let j = null; try { j = JSON.parse(p.stdout); } catch (e) {}
        out[m] = { j, p };
      }
      const failedRun = Object.values(out).find((o) => !o.j);
      if (failedRun) R.inspector.skip(`Inspector CLI did not run (rc ${failedRun.p.status}${failedRun.p.timedOut ? ', timeout' : ''}): ${(failedRun.p.stderr || failedRun.p.error || '').slice(-300)}`);
      else {
        R.inspector.ck('toolsList14', out['tools/list'].j.tools && out['tools/list'].j.tools.length === 14, out['tools/list'].j);
        R.inspector.ck('resourcesList4', out['resources/list'].j.resources && out['resources/list'].j.resources.length === 4, out['resources/list'].j);
        R.inspector.ck('templates3', out['resources/templates/list'].j.resourceTemplates && out['resources/templates/list'].j.resourceTemplates.length === 3, out['resources/templates/list'].j);
        R.inspector.done();
      }
    }
    if (!which('claude')) { R.claude.skip('claude not found'); R.claudeCall.skip('claude not found'); return; }
    // a temporary MCP config only: --strict-mcp-config ignores every configured server, nothing is added to the user's configuration
    const cfg = path.join(nw.dir, 'mcp.json');
    fs.writeFileSync(cfg, JSON.stringify({ mcpServers: { circuitjs: { type: 'http', url } } }));
    const cc = await runProc('claude', ['-p', 'Call the circuit_types tool with type "Resistor" and reply with the default value of its resistance property only.',
      '--mcp-config', cfg, '--strict-mcp-config', '--no-session-persistence', '--output-format', 'stream-json', '--verbose', '--max-turns', '6', '--model', CLAUDE_MODEL,
      '--allowedTools', 'mcp__circuitjs__circuit_types'], { timeout: 240000, cwd: nw.dir });
    fs.writeFileSync(path.join(nw.dir, 'claude.jsonl'), cc.stdout || '');
    const lines = (cc.stdout || '').split('\n').filter(Boolean).map((l) => { try { return JSON.parse(l); } catch (e) { return null; } }).filter(Boolean);
    const init = lines.find((m) => m.type === 'system' && m.subtype === 'init');
    if (!init) {
      const why = `claude -p gave no init message (rc ${cc.status}${cc.timedOut ? ', timeout' : ''}): ${(cc.stderr || cc.stdout || '').slice(-300)}`;
      R.claude.skip(why); R.claudeCall.skip(why);
      return;
    }
    const srv = (init.mcp_servers || []).find((s) => s.name === 'circuitjs');
    const tools = (init.tools || []).filter((t) => t.startsWith('mcp__circuitjs__'));
    R.claude.ck('connected', srv && srv.status === 'connected', { srv });
    R.claude.ck('lists14Tools', tools.length === 14, tools);
    R.claude.note('version', init.claude_code_version);
    R.claude.done();
    const result = lines.find((m) => m.type === 'result');
    const used = lines.some((m) => m.type === 'assistant' && JSON.stringify(m.message.content).includes('mcp__circuitjs__circuit_types'));
    // the tool result the model received (a deferred-tool search may take one turn first)
    const toolResult = lines.some((m) => m.type === 'user' && /\\?"ok\\?":\s*true/.test(JSON.stringify(m.message && m.message.content)) && /\\?"type\\?":\s*\\?"Resistor/.test(JSON.stringify(m.message && m.message.content)));
    if (!used && (!result || result.is_error)) R.claudeCall.skip('the model call did not run: ' + clip(String(result && (result.result || result.subtype)), 300));
    else {
      R.claudeCall.ck('calledTool', used, { used });
      R.claudeCall.ck('toolResultOk', toolResult);
      if (result && result.subtype === 'success') R.claudeCall.ck('answer', /1\s*k|1000/i.test(result.result || ''), { result: result.result });
      else R.claudeCall.note('answer', 'model did not finish: ' + (result && result.subtype));
      R.claudeCall.done();
    }
  } finally { await nw.kill(); }
}

// ---------------------------------------------------------------- stdio bridge (PL_MCB Phase 4)
// The bridge rows run the real `circuitjs-mcp` program (mcp/bridge, its own node_modules) as a
// stdio server (SDK client of mcp/bridge/test/bridge-client.mjs) and as a CLI, against real NW.js
// instances. The bridge starts the app itself through a wrapper executable: a scratch HOME for the
// registry and its own profile per start, so NW.js never hands a second start to a running
// instance (single instance per profile).
const BRIDGE_DIR = path.join(PROJECT, 'mcp/bridge');
const BRIDGE_BIN = path.join(BRIDGE_DIR, 'bin/circuitjs-mcp.js');
function bridgeMissing() {
  if (!fs.existsSync(BRIDGE_BIN)) return 'the stdio bridge (mcp/bridge, PL_MCB) is not built';
  if (!fs.existsSync(path.join(BRIDGE_DIR, 'node_modules/@modelcontextprotocol/sdk/package.json'))) return 'bridge dependencies missing: run npm install in mcp/bridge';
  return null;
}
const skipAll = (R, why) => { for (const r of Object.values(R)) r.skip(why); };

/** An executable that starts NW.js like the packaged app (no arguments), with a scratch HOME. */
function appWrapper(dir, home) {
  const file = path.join(dir, 'CircuitSimulator.sh');
  const q = (v) => `'${String(v).replace(/'/g, `'\\''`)}'`;
  fs.writeFileSync(file, ['#!/bin/sh',
    `export HOME=${q(home)} XDG_CONFIG_HOME=${q(path.join(home, '.config'))} XDG_CACHE_HOME=${q(path.join(home, '.cache'))} XDG_DATA_HOME=${q(path.join(home, '.local/share'))} DISPLAY=${q(DISPLAY)}`,
    'unset WAYLAND_DISPLAY',
    // started detached (bridge launch, the harness), the wrapper leads its own process group: register it
    `echo $$ >>${q(path.join(dir, 'groups'))}`,
    `exec ${q(NW_BIN)} --user-data-dir=${q(dir)}/udd-$$ ${q(SITE_DIR)} >>${q(dir)}/nw-$$.log 2>&1`, ''].join('\n'), { mode: 0o755 });
  GROUP_DIRS.add(dir);
  return file;
}

// Process groups of the instances started through an app wrapper: only the groups the wrapper
// registered in <dir>/groups are ever signalled, never the group of whatever process owns a
// recorded pid now (pids are reused).
const GROUP_DIRS = new Set();
/** The registered groups of `dir` that still exist and are ours. */
function wrapperGroups(dir) {
  let ids = [];
  try { ids = fs.readFileSync(path.join(dir, 'groups'), 'utf8').split('\n').map((x) => parseInt(x, 10)).filter((g) => g > 0); } catch (e) {}
  return [...new Set(ids)].filter((g) => {
    if (!groupAlive(g)) return false;
    // While a group exists its id is not reused; when its leader is alive, its command line names the scratch dir.
    let cmd = null;
    try { cmd = fs.readFileSync(`/proc/${g}/cmdline`, 'utf8'); } catch (e) { return true; }
    return cmd.includes(dir);
  });
}
/** Process group of a pid (from /proc), or null. */
function pgrpOf(pid) {
  try { const st = fs.readFileSync(`/proc/${pid}/stat`, 'utf8'); return parseInt(st.slice(st.lastIndexOf(')') + 2).split(' ')[2], 10) || null; } catch (e) { return null; }
}
async function stopGroup(g) {
  signalGroup(g, 'SIGTERM');
  for (let i = 0; i < 60 && groupAlive(g); i++) await sleep(100);
  if (groupAlive(g)) { signalGroup(g, 'SIGKILL'); for (let i = 0; i < 50 && groupAlive(g); i++) await sleep(100); }
}
/** Ends the registered group that runs this instance record (no-op for any other process). */
async function stopInstance(dir, rec) {
  const g = pgrpOf(rec.pid);
  if (g && wrapperGroups(dir).includes(g)) await stopGroup(g);
}
/** Ends every registered group of `dir`, then drops the records they left behind. */
async function stopInstances(dir, home) {
  for (const g of wrapperGroups(dir)) await stopGroup(g);
  for (const r of records(home)) { if (r.rec && !pidAlive(r.rec.pid)) fs.rmSync(path.join(instDir(home), r.name), { force: true }); }
}
function pidAlive(pid) { try { process.kill(pid, 0); return true; } catch (e) { return e.code === 'EPERM'; } }
// harness exit (also abortRun and signals): end the registered groups too
process.on('exit', () => { for (const d of GROUP_DIRS) for (const g of wrapperGroups(d)) signalGroup(g, 'SIGKILL'); });

/** Runs the CLI; {code, out, err, json, ms}. */
function bridgeCli(args, { home, stdin = '', timeout = 60000, extraEnv = {} } = {}) {
  const env = { ...process.env, ...extraEnv };
  for (const k of Object.keys(env)) if (k.startsWith('CIRCUITJS_') && !(k in extraEnv)) delete env[k];
  return new Promise((resolve) => {
    const t0 = Date.now();
    const p = spawn(process.execPath, [BRIDGE_BIN, ...args, '--registry', instDir(home)], { env, stdio: ['pipe', 'pipe', 'pipe'], detached: true, cwd: OUT_DIR });
    liveGroups.add(p.pid);
    let out = ''; let err = '';
    const to = setTimeout(() => signalGroup(p.pid, 'SIGKILL'), timeout);
    p.stdout.on('data', (d) => { out += d; });
    p.stderr.on('data', (d) => { err += d; });
    p.on('close', (code) => { clearTimeout(to); liveGroups.delete(p.pid); let json; try { json = JSON.parse(out); } catch (e) {} resolve({ code, out, err, json, ms: Date.now() - t0 }); });
    p.stdin.end(stdin);
  });
}

let BRIDGE_CLIENT = null;
async function bridgeClient() {
  if (!BRIDGE_CLIENT) BRIDGE_CLIENT = await import(pathToFileURL(path.join(BRIDGE_DIR, 'test/bridge-client.mjs')).href);
  return BRIDGE_CLIENT;
}
const toolText = (r) => (r && Array.isArray(r.content) ? r.content.map((c) => c.text).join('\n') : '');
// raw JSON-RPC through an SDK client: a pass-through result schema (no validation, no stripping)
let PASS_SCHEMA = null;
async function rawOf(client) {
  if (!PASS_SCHEMA) PASS_SCHEMA = (await import(pathToFileURL(path.join(PROJECT, 'node_modules/zod/index.js')).href)).looseObject({});
  return (method, params, timeout = 60000) => client.request({ method, params }, PASS_SCHEMA, { timeout });
}
async function catchRpc(fn) { try { await fn(); return null; } catch (e) { return e; } }

// SP_MCB_05: stdio mode, bridge tools, CLI, invariants, integration and edge rows; SP_MCP_05_03 bridge forwarding
async function scenBridge(R) {
  const miss = bridgeMissing();
  if (miss) return skipAll(R, miss);
  const { startBridge, until } = await bridgeClient();
  const dir = path.join(OUT_DIR, 'run', 'bridge');
  rmrf(dir);
  const home = path.join(dir, 'home');
  fs.mkdirSync(instDir(home), { recursive: true, mode: 0o700 });
  const app = appWrapper(dir, home);
  const lrc = path.join(SITE_DIR, 'circuitjs1/circuits/lrc.txt');
  const docsOf = async (raw) => { const r = await raw('tools/call', { name: 'circuit_documents', arguments: { action: 'list' } }); return (r.structuredContent && r.structuredContent.data && r.structuredContent.data.documents) || []; };
  const hasLrc = (docs) => docs.some((d) => /lrc\.txt$/.test(d.filePath || ''));
  const b = await startBridge({ args: ['--app', app, '--registry', instDir(home)] });
  const raw = b.raw;
  let direct = null;
  try {
    // --- no app yet
    let r;
    const tools0 = (await raw('tools/list')).tools.map((t) => t.name);
    R.stdioNoApp.ck('onlyBridgeTools', same(tools0, ['bridge_instances', 'bridge_select', 'bridge_launch']), tools0);
    R.stdioNoApp.ck('instructionsBridgeSentence', /circuitjs-mcp bridge/.test(b.client.getInstructions() || ''), b.client.getInstructions());
    r = await raw('tools/call', { name: 'circuit_types', arguments: { type: 'Resistor' } });
    R.stdioNoApp.ck('noInstanceError', r.isError === true && toolText(r) === 'No CircuitJS1 instance. Start the app or call bridge_launch.', r);
    R.stdioNoApp.done();

    r = await raw('tools/call', { name: 'bridge_select', arguments: { instanceId: 'x' } });
    R.selectErrors.ck('unknownInstance', r.isError && toolText(r) === 'Unknown instance x. Live instances: none', r);
    r = await raw('tools/call', { name: 'bridge_select', arguments: { url: 'http://127.0.0.1:9/mcp' } });
    R.selectErrors.ck('unreachableUrl', r.isError && /^Cannot reach http:\/\/127\.0\.0\.1:9\/mcp: .+\. Live instances: none$/.test(toolText(r)), r);
    const both = await catchRpc(() => raw('tools/call', { name: 'bridge_select', arguments: { instanceId: 'x', url: 'http://127.0.0.1:9/mcp' } }));
    R.selectErrors.ck('bothGiven32602', both && both.code === -32602, both && both.message);
    const noApp = await startBridge({ args: ['--registry', instDir(home)] });
    try {
      r = await noApp.raw('tools/call', { name: 'bridge_launch', arguments: {} });
      R.selectErrors.ck('launchNoApp', r.isError && toolText(r) === 'No app executable configured. Set --app or CIRCUITJS_APP.', r);
    } finally { await noApp.close(); }
    R.selectErrors.done();

    // --- edge rows without an instance (CLI)
    const dead = spawnSync('sh', ['-c', 'echo $$']).stdout.toString().trim() | 0;
    const deadRec = { instanceId: `${dead}-1700000000000`, pid: dead, port: 7399, host: '127.0.0.1', urls: ['http://127.0.0.1:7399/mcp'], appVersion: '1', startedAt: '2023-11-14T22:13:20.000Z', title: 't', protocolRevisions: ['2025-11-25'], toolsVersion: '1.0' };
    fs.writeFileSync(path.join(instDir(home), `${deadRec.instanceId}.json`), JSON.stringify(deadRec));
    r = await bridgeCli(['tools'], { home });
    R.edges.ck('staleOnlyIsNoInstance', r.code === 3 && r.out === '' && /No CircuitJS1 instance/.test(r.err), r);
    R.edges.ck('staleDeleted', records(home).length === 0, records(home));
    R.cli.ck('toolsNoInstanceExit3', r.code === 3, r);
    r = await bridgeCli(['tools', '--url', 'http://10.255.255.1:7311/mcp'], { home });
    R.edges.ck('explicitUnreachable', r.code === 3 && r.out === '' && /^circuitjs-mcp: Cannot reach http:\/\/10\.255\.255\.1:7311\/mcp: .+\. Live instances: none\n$/.test(r.err) && r.ms < 10000, r);

    // --- bridge_launch with a file
    let t0 = Date.now();
    r = await raw('tools/call', { name: 'bridge_launch', arguments: { file: lrc } }, 90000);
    const first = r.structuredContent && r.structuredContent.target;
    R.launch.ck('launched', !r.isError && first && first.source === 'launched' && /^\d+-\d+$/.test(first.instanceId), r);
    R.launch.ck('openedDoc', r.structuredContent && r.structuredContent.opened && typeof r.structuredContent.opened.doc === 'string', r.structuredContent);
    R.launch.note('launchMs', Date.now() - t0);
    let docs = await docsOf(raw);
    R.launch.ck('newActiveDocumentHoldsFile', docs.some((d) => d.active && d.filePath === lrc), docs);
    await until(() => b.notes.tools >= 1, 3000).catch(() => {});
    R.launch.ck('listChanged', b.notes.tools >= 1 && b.notes.resources >= 1, b.notes);
    R.launch.done();

    // --- with the app: catalogue and transparency
    direct = await sdkClient(first.url, 'e2e-direct');
    const draw = await rawOf(direct);
    const dtools = (await draw('tools/list', {})).tools;
    const btools = (await raw('tools/list')).tools;
    R.stdioApp.ck('appToolsThenBridgeTools', btools.length === dtools.length + 3 && same(btools.slice(0, dtools.length), dtools) && same(btools.slice(dtools.length).map((t) => t.name), ['bridge_instances', 'bridge_select', 'bridge_launch']), btools.map((t) => t.name));
    R.stdioApp.ck('fourteenAppTools', dtools.length === 14, dtools.length);
    // a host that connects while the app runs gets the app's instructions, then the bridge sentence
    const b2 = await startBridge({ args: ['--registry', instDir(home)] });
    try {
      const ins = b2.client.getInstructions() || '';
      const dins = direct.getInstructions() || '';
      R.stdioApp.ck('instructionsForwarded', dins.length > 0 && ins.startsWith(dins + '\n\n') && /toolsVersion 1\.0/.test(ins) && /circuitjs-mcp bridge/.test(ins.slice(dins.length)), clip(ins, 300));
      R.stdioApp.ck('serverInfo', (b2.client.getServerVersion() || {}).name === 'circuitjs-mcp', b2.client.getServerVersion());
    } finally { await b2.close(); }
    R.stdioApp.done();
    const cases = [
      ['circuit_types Resistor', 'tools/call', { name: 'circuit_types', arguments: { type: 'Resistor' } }],
      ['circuit_types list', 'tools/call', { name: 'circuit_types', arguments: {} }],
      ['circuit_edit domain error', 'tools/call', { name: 'circuit_edit', arguments: { edits: [{ op: 'delete', id: 'X9' }] } }],
      ['circuit_get', 'tools/call', { name: 'circuit_get', arguments: {} }],
      ['circuit_connectivity', 'tools/call', { name: 'circuit_connectivity', arguments: {} }],
      ['circuit_read', 'tools/call', { name: 'circuit_read', arguments: { what: 'nets' } }],
      ['circuit_documents list', 'tools/call', { name: 'circuit_documents', arguments: { action: 'list' } }],
      ['resources/list', 'resources/list', {}],
      ['resources/templates/list', 'resources/templates/list', {}],
      ['resources/read catalogue', 'resources/read', { uri: 'circuitjs://catalogue' }],
    ];
    for (const [label, method, params] of cases) {
      const viaBridge = await catchRpcValue(() => raw(method, params));
      const viaDirect = await catchRpcValue(() => draw(method, params));
      const eq = same(viaBridge, viaDirect);
      R.bridge.ck(label, eq, eq ? undefined : { viaBridge: clip(JSON.stringify(viaBridge), 300), viaDirect: clip(JSON.stringify(viaDirect), 300) });
      R.transparent.ck(label, eq);
    }
    const eb = await catchRpc(() => raw('tools/call', { name: 'circuit_get', arguments: { nonsense: 1 } }));
    const ed = await catchRpc(() => draw('tools/call', { name: 'circuit_get', arguments: { nonsense: 1 } }));
    R.bridge.ck('jsonRpcErrorPassedThrough', eb && ed && eb.code === ed.code && eb.message === ed.message && same(eb.data, ed.data), { eb: eb && eb.message, ed: ed && ed.message });
    R.bridge.done();
    R.transparent.ck('noCircuitToolsDefined', same(btools.filter((t) => !t.name.startsWith('bridge_')), dtools));

    // --- CLI rows against the instance
    r = await bridgeCli(['call', 'circuit_types', '{"type":"Resistor"}'], { home });
    R.cli.ck('callValidExit0', r.code === 0 && r.json && r.json.ok === true && r.json.data && r.json.data.type === 'Resistor' && r.err === '', r);
    r = await bridgeCli(['call', 'circuit_edit', '{"edits":[{"op":"delete","id":"X9"}]}'], { home });
    R.cli.ck('callDomainErrorExit1', r.code === 1 && r.json && r.json.ok === false && Array.isArray(r.json.issues) && r.json.issues.length > 0, r);
    r = await bridgeCli(['call', 'circuit_get', '{'], { home });
    R.cli.ck('callBadJsonExit2', r.code === 2 && r.out === '' && r.err.split('\n').length === 2, r);
    r = await bridgeCli(['read', 'circuitjs://catalogue'], { home });
    let catalogue = null; try { catalogue = JSON.parse(r.json[0].text); } catch (e) {}
    R.cli.ck('readCatalogueExit0', r.code === 0 && catalogue && Array.isArray(catalogue.types), clip(r.out, 300));
    r = await bridgeCli(['tools'], { home });
    const proj = dtools.map((t) => ({ name: t.name, title: t.title ?? (t.annotations && t.annotations.title) ?? null, annotations: t.annotations ?? {} }));
    R.transparent.ck('cliToolsEqualTargetList', r.code === 0 && same((r.json || []).filter((t) => !t.name.startsWith('bridge_')), proj), clip(r.out, 300));
    R.transparent.done();
    r = await bridgeCli(['call', 'circuit_connectivity'], { home });
    R.evalRow.ck('connectivityExit0Parsed', r.code === 0 && r.json && r.json.ok === true && r.json.data !== undefined, clip(r.out, 300));
    R.evalRow.done();
    // stdin arguments: the same circuit into two fresh background documents
    const grid = (n) => FILE_OPTS + '\n' + Array.from({ length: n }, (_, i) => `r ${16 * (i % 40)} ${32 * Math.floor(i / 40)} ${16 * (i % 40 + 1)} ${32 * Math.floor(i / 40)} 0 1000`).join('\n') + '\n';
    const circuit = grid(400);
    const mkDoc = async () => (await bridgeCli(['call', 'circuit_documents', '{"action":"create"}'], { home })).json.data.doc;
    const dA = await mkDoc(); const dB = await mkDoc();
    const inline = await bridgeCli(['call', 'circuit_import', JSON.stringify({ doc: dA, circuit })], { home });
    const viaStdin = await bridgeCli(['call', 'circuit_import', '-'], { home, stdin: JSON.stringify({ doc: dB, circuit }) });
    const norm = (j) => JSON.stringify(j).split(dA).join('D').split(dB).join('D');
    R.edges.ck('stdinEqualsInline', inline.code === 0 && viaStdin.code === 0 && norm(inline.json) === norm(viaStdin.json) && inline.json.data.elements === 400, { inline: clip(inline.out, 200), viaStdin: clip(viaStdin.out, 200) });
    R.edges.done();

    // --- two windows: a second start of the wrapper (its own profile)
    const startWrapper = () => spawn(app, [], { detached: true, stdio: 'ignore' }).unref();
    startWrapper();
    for (let i = 0; i < 120 && records(home).filter((x) => x.rec).length < 2; i++) await sleep(500);
    fs.writeFileSync(path.join(instDir(home), `${deadRec.instanceId}.json`), JSON.stringify(deadRec));
    r = await bridgeCli(['instances'], { home });
    R.cli.ck('instancesTwoLiveOneStale', r.code === 0 && Array.isArray(r.json) && r.json.length === 2 && !fs.existsSync(path.join(instDir(home), `${deadRec.instanceId}.json`)), r);
    r = await raw('tools/call', { name: 'bridge_instances', arguments: {} });
    const inst = (r.structuredContent && r.structuredContent.instances) || [];
    const second = inst.find((i) => i.instanceId !== first.instanceId);
    R.twoWindows.ck('twoInstancesFirstSelected', inst.length === 2 && inst.filter((i) => i.selected).map((i) => i.instanceId).join() === first.instanceId, inst);
    const ids = records(home).filter((x) => x.rec).map((x) => x.rec).sort((a, c) => Date.parse(a.startedAt) - Date.parse(c.startedAt) || a.instanceId.localeCompare(c.instanceId)).map((x) => x.instanceId).join(', ');
    r = await raw('tools/call', { name: 'bridge_select', arguments: { instanceId: 'x' } });
    R.twoWindows.ck('unknownListsBothLive', r.isError && toolText(r) === `Unknown instance x. Live instances: ${ids}`, { text: toolText(r), ids });
    r = await bridgeCli(['tools', '--url', 'http://127.0.0.1:9/mcp'], { home });
    R.twoWindows.ck('unreachableListsBothLive', r.code === 3 && r.err.trim().endsWith(`. Live instances: ${ids}`), { err: r.err, ids });
    const n0 = b.notes.tools;
    r = await raw('tools/call', { name: 'bridge_select', arguments: { instanceId: second && second.instanceId } });
    R.twoWindows.ck('selectNewer', !r.isError && r.structuredContent.target.instanceId === (second && second.instanceId), r);
    R.twoWindows.ck('newerHasNoLrc', !hasLrc(await docsOf(raw)));
    r = await raw('tools/call', { name: 'bridge_select', arguments: { instanceId: first.instanceId } });
    R.twoWindows.ck('selectOlderDocuments', !r.isError && hasLrc(await docsOf(raw)));
    await until(() => b.notes.tools >= n0 + 2, 3000).catch(() => {});
    R.twoWindows.ck('listChangedPerSwitch', b.notes.tools >= n0 + 2, b.notes);
    R.twoWindows.done();

    // --- app closed mid-session
    await direct.close().catch(() => {}); direct = null;
    // the target first (the other window keeps the registry non-empty for the re-resolution check below)
    await stopInstance(dir, records(home).find((x) => x.rec && x.rec.instanceId === first.instanceId).rec);
    r = await raw('tools/call', { name: 'circuit_documents', arguments: { action: 'list' } });
    R.closed.ck('instanceGone', r.isError && toolText(r) === `Instance gone: ${first.url}`, r);
    r = await raw('tools/call', { name: 'circuit_documents', arguments: { action: 'list' } });
    R.closed.ck('reResolvesToTheOtherWindow', !r.isError && r.structuredContent && r.structuredContent.ok === true, r);
    // then every instance; restart the app; the next calls reach the new instance
    await stopInstances(dir, home);
    r = await raw('tools/call', { name: 'circuit_documents', arguments: { action: 'list' } });
    R.closed.ck('allClosedInstanceGone', r.isError && /^Instance gone: /.test(toolText(r)), r);
    const before = new Set([first.instanceId, second && second.instanceId]);
    startWrapper();
    for (let i = 0; i < 120 && !records(home).some((x) => x.rec && !before.has(x.rec.instanceId)); i++) await sleep(500);
    r = await raw('tools/call', { name: 'circuit_documents', arguments: { action: 'list' } });
    R.closed.ck('restartedCallSucceeds', !r.isError && r.structuredContent && r.structuredContent.ok === true, r);
    r = await raw('tools/call', { name: 'bridge_instances', arguments: {} });
    const t3 = r.structuredContent && r.structuredContent.target;
    R.closed.ck('targetIsTheNewInstance', t3 && t3.instanceId && !before.has(t3.instanceId), r.structuredContent);
    R.closed.done();

    // --- CLI launch with a file, no instance running
    await stopInstances(dir, home);
    t0 = Date.now();
    r = await bridgeCli(['launch', lrc, '--app', app], { home, timeout: 90000 });
    R.cli.ck('launchWithFileExit0', r.code === 0 && r.json && r.json.target && r.json.target.source === 'launched' && r.json.opened && typeof r.json.opened.doc === 'string', r);
    r = await bridgeCli(['call', 'circuit_documents', '{"action":"list"}'], { home });
    R.cli.ck('launchShowsCircuit', r.code === 0 && r.json.data.documents.some((d) => d.active && d.filePath === lrc), clip(r.out, 300));
    R.cli.note('cliLaunchMs', Date.now() - t0);
    R.cli.done();
    R.stdioClean.ck('noStdoutNoise', b.errors.length === 0, b.errors.map((e) => e.message));
    R.stdioClean.ck('diagnosticsOnStderr', /circuitjs-mcp: target /.test(b.stderr()), clip(b.stderr(), 300));
    R.stdioClean.done();
    fs.writeFileSync(path.join(dir, 'bridge.stderr'), b.stderr());
  } finally {
    if (direct) await direct.close().catch(() => {});
    await b.close().catch(() => {});
    await stopInstances(dir, home);
  }
}
async function catchRpcValue(fn) { try { return await fn(); } catch (e) { return { rpcError: { code: e.code, message: e.message, data: e.data } }; } }

// SP_MCB_05_04 run longer than the default timeout, through the CLI
async function scenBridgeLong(R) {
  const miss = bridgeMissing();
  if (miss) return skipAll(R, miss);
  const nw = await launchNw('bridge-long');
  try {
    const r = await bridgeCli(['call', 'circuit_run', JSON.stringify({ span: 100000, budgetMs: 120000, reset: true })], { home: nw.home, timeout: 180000 });
    R.long.ck('exit0', r.code === 0, { code: r.code, err: r.err });
    R.long.ck('budgetExhausted', r.json && r.json.ok === true && r.json.data && r.json.data.reason === 'budget_exhausted', clip(r.out, 300));
    R.long.ck('longerThan120s', r.ms >= 119000, r.ms);
    R.long.note('ms', r.ms);
    R.long.done();
  } finally { await nw.kill(); }
}

// SP_MCB_05_01 stdio mode in Claude Code (temporary --mcp-config, --strict-mcp-config)
async function scenBridgeClients(R) {
  const miss = bridgeMissing();
  if (miss) return skipAll(R, miss);
  if (!which('claude')) return skipAll(R, 'claude not found');
  const nw = await launchNw('bridge-clients');
  try {
    const cfg = path.join(nw.dir, 'mcp.json');
    fs.writeFileSync(cfg, JSON.stringify({ mcpServers: { circuitjs: { type: 'stdio', command: process.execPath, args: [BRIDGE_BIN, '--registry', instDir(nw.home)] } } }));
    const cc = await runProc('claude', ['-p', 'Call the circuit_types tool with type "Resistor" and reply with the default value of its resistance property only.',
      '--mcp-config', cfg, '--strict-mcp-config', '--no-session-persistence', '--output-format', 'stream-json', '--verbose', '--max-turns', '6', '--model', CLAUDE_MODEL,
      '--allowedTools', 'mcp__circuitjs__circuit_types'], { timeout: 240000, cwd: nw.dir, env: Object.fromEntries(Object.entries(process.env).filter(([k]) => !k.startsWith('CIRCUITJS_'))) });
    fs.writeFileSync(path.join(nw.dir, 'claude.jsonl'), cc.stdout || '');
    const lines = (cc.stdout || '').split('\n').filter(Boolean).map((l) => { try { return JSON.parse(l); } catch (e) { return null; } }).filter(Boolean);
    const init = lines.find((m) => m.type === 'system' && m.subtype === 'init');
    if (!init) return skipAll(R, `claude -p gave no init message (rc ${cc.status}${cc.timedOut ? ', timeout' : ''}): ${(cc.stderr || cc.stdout || '').slice(-300)}`);
    const srv = (init.mcp_servers || []).find((x) => x.name === 'circuitjs');
    const tools = (init.tools || []).filter((t) => t.startsWith('mcp__circuitjs__'));
    R.claude.ck('connected', srv && srv.status === 'connected', { srv });
    R.claude.ck('appToolsPlusBridgeTools', tools.length === 17 && ['bridge_instances', 'bridge_select', 'bridge_launch'].every((n) => tools.includes('mcp__circuitjs__' + n)), tools);
    R.claude.note('version', init.claude_code_version);
    R.claude.done();
    const result = lines.find((m) => m.type === 'result');
    const used = lines.some((m) => m.type === 'assistant' && JSON.stringify(m.message.content).includes('mcp__circuitjs__circuit_types'));
    const toolResult = lines.some((m) => m.type === 'user' && /\\?"ok\\?":\s*true/.test(JSON.stringify(m.message && m.message.content)) && /\\?"type\\?":\s*\\?"Resistor/.test(JSON.stringify(m.message && m.message.content)));
    if (!used && (!result || result.is_error)) R.claudeCall.skip('the model call did not run: ' + clip(String(result && (result.result || result.subtype)), 300));
    else {
      R.claudeCall.ck('calledTool', used, { used });
      R.claudeCall.ck('toolResultOk', toolResult);
      R.claudeCall.done();
    }
  } finally { await nw.kill(); }
}

// rows that this harness does not automate
async function scenManual(R) {
  R.hidden.skip('MANUAL: run by the developer, record `observed` + date in tests/mcp/README.md');
  R.lan.skip('MANUAL: run by the developer, record `observed` + date in tests/mcp/README.md');
  R.desktop.skip('MANUAL: run by the developer, record `observed` + date in mcp/bridge/README.md');
  R.browser.skip('covered by tests/live scenario mcp_browser (npm run test:live -- mcp_browser)');
}

// ================================================================ scenario table
const SCENARIOS = [
  { name: 'endpoint', group: 'default', run: scenEndpoint, rows: {
    record: ['SP_MCP_01_02', 'Instance record: one 0600 record in a 0700 directory, listening logged'],
    handshake: ['SP_MCP_02_01', 'Endpoint: initialize revisions, discover fallback, error codes, equal ids, paths'],
    loopbackOnly: ['SP_MCP_01_01', 'Default listening address 127.0.0.1: status/record host and URLs loopback only; the port refuses connections on the LAN IPv4 addresses'],
    originForeign: ['SP_MCP_05_01', 'Origin rule: foreign web page -> 403'],
    originNone: ['SP_MCP_05_01', 'Origin rule: no origin -> 200'],
    originLocal: ['SP_MCP_03_01', 'Origin rule: "null" -> 403; localhost, 127.0.0.1, [::1] origins -> 200'],
    get: ['SP_MCP_05_01', 'Endpoint: GET -> 405'],
    revision: ['SP_MCP_05_01', 'Endpoint: unsupported revision header -> 400 with JSON-RPC error body'],
    session: ['SP_MCP_05_01', 'Endpoint: arbitrary Mcp-Session-Id -> processed normally'],
    notification: ['SP_MCP_05_01', 'Endpoint: notification -> 202, empty body'],
    unknownUri: ['SP_MCP_05_01', 'Resources: unknown URI -> -32002'],
    exit: ['SP_MCP_02_05', 'Shutdown: File -> Exit removes the record and releases the port'],
  } },
  { name: 'hostile', group: 'default', run: scenHostile, rows: {
    strict: ['SP_MCP_02_01', 'Hostile input: non-strict requests -> -32600 within 3 s; invalid notification -> 202'],
    params: ['SP_MCP_03_03', 'Hostile input: bad params -> -32602 naming the field'],
    cancel: ['SP_MCP_02_01', 'Hostile input: notifications/cancelled dropped, 30 concurrent requests answered'],
    size: ['SP_MCP_02_01', 'Hostile input: body over 16 MB -> 413 and connection closed (declared, streamed)'],
  } },
  { name: 'portbusy', group: 'default', run: scenPortBusy, rows: {
    busy: ['SP_MCP_05_01', 'Start-up: port busy -> server on 7312; record says 7312'],
  } },
  { name: 'allbusy', group: 'default', run: scenAllBusy, rows: {
    allBusy: ['SP_MCP_05_04', 'All ports busy: status failed (logged), no record, app usable, info dialog shows the reason'],
  } },
  { name: 'instances', group: 'default', run: scenInstances, rows: {
    oneRecord: ['SP_MCP_05_02', 'One record per live instance: two windows -> two records, distinct ports; close one -> one'],
    closeTrue: ['SP_MCP_02_05', 'Shutdown: close(true) removes the record'],
  } },
  { name: 'stale', group: 'default', run: scenStale, rows: {
    crash: ['SP_MCP_05_04', 'Crash record: stale record of a dead pid deleted by the next starting instance'],
  } },
  { name: 'tools', group: 'default', run: scenTools, rows: {
    sdk: ['SP_MCP_02_02', 'SDK client: handshake 2025-11-25, 14 tools with output schemas, 4 resources'],
    editDomain: ['SP_MCP_05_01', 'circuit_edit: domain error -> isError, issues[0].code = unknown_property'],
    editSchema: ['SP_MCP_05_01', 'circuit_edit: schema error -> JSON-RPC -32602, no Agent API call'],
    renderPng: ['SP_MCP_05_01', 'circuit_render: png -> image content part; text part carries "<image>"'],
    catalogue: ['SP_MCP_05_01', 'Resources: circuitjs://catalogue/Resistor -> TypeInfo JSON'],
    resOther: ['SP_MCP_02_03', 'Resources: unknown URI/type, path outside the index -> -32002; agent-format, documents'],
    roundtrip: ['SP_MCP_05_01', 'Resources: circuit round trip documents/d1/circuit -> circuit_import d2 -> d2 equals d1'],
    fileRule: ['SP_MCP_05_01', 'Tool: file rule -> circuit_file save /etc/x.conf -> isError file_not_allowed; save/open .json'],
    templates: ['SP_MCP_05_01', 'Resources: templates -> the three templates of 02_03'],
    examples: ['SP_MCP_05_01', 'Resources: examples index, then one listed path -> the example text'],
    sizing: ['SP_MCP_05_01', 'Sizing: circuit_get full/500 on alu74181 -> text <= 60 000, note names reduced arguments, nextOffset'],
    huge: ['SP_MCP_05_04', 'Huge result: concise retry with nextOffset; a non-reducible export -> result_too_large'],
    noLogic: ['SP_MCP_05_02', 'Tools add no circuit logic: structuredContent = the effective Agent API result'],
    isError: ['SP_MCP_05_02', 'isError <=> ok = false over every tool result'],
    textLimit: ['SP_MCP_05_02', 'Text part <= 60 000 chars: alu74181 get/connectivity, run with 16 probes at sum maxPoints 2000'],
    coverage: ['SP_MCP_02_02', 'All 14 tools called (import, edit, render svg, read, run, checkpoint, history, documents, sim)'],
  } },
  { name: 'long', group: 'default', run: (R) => scenLong(R, 5000), rows: {
    long: ['SP_MCP_05_03', 'Long run while reading: circuit_get answers during a 5 s circuit_run'],
  } },
  { name: 'dialog', group: 'default', run: scenDialog, rows: {
    listening: ['SP_MCP_05_01', 'Info dialog: listening -> status, instance ID, URLs and command line shown'],
    counter: ['SP_MCP_05_01', 'Info dialog: counter -> 3 tool calls show 3 (live while open), 4 after reopening'],
    copyClose: ['SP_MCP_02_04', 'Info dialog: Copy puts the command line on the clipboard; Close and Escape close it'],
  } },
  { name: 'settings', group: 'default', run: scenSettings, rows: {
    reject: ['SP_MCP_02_04', 'Info dialog: invalid port/host rejected with a message, nothing stored'],
    disable: ['SP_MCP_05_01', 'Info dialog: disable -> untick Enabled, Save, restart -> disabled; no port bound; no instance record'],
    reenable: ['SP_MCP_02_04', 'Info dialog: re-enable on 0.0.0.0:7400 (Enter saves), restart -> 127.0.0.1 URL then the LAN URLs (record and dialog); a LAN URL answers'],
  } },
  { name: 'files', group: 'default', run: scenFiles, rows: {
    saveJson: ['SP_AGA_05_01', 'saveFile: json -> file loads back via openFile with identical element IDs'],
    saveMd: ['SP_AGA_05_01', 'saveFile: not allowed (…/notes.md) -> file_not_allowed'],
    saveForeign: ['SP_AGA_05_01', 'saveFile: overwrite foreign (…/a.txt) -> file_not_allowed; file untouched'],
    saveProse: ['SP_AGA_05_01', 'saveFile: overwrite prose text (…/notes.txt) -> file_not_allowed; file untouched'],
    openMissing: ['SP_AGA_05_01', 'openFile: missing -> file_not_found (also missing directory, dangling link)'],
    saveRules: ['SP_AGA_03_09', 'saveFile: overwrite rules, symlinks, write errors, no staging file left, no_path, format, seal'],
    openRules: ['SP_AGA_03_09', 'openFile: rejections without disclosure, no document or closed-tab entry; BOM file opens'],
    openInto: ['SP_AGA_02_14', 'openFile: into a background handle (visible tab unchanged), rejected into handle, activate'],
    staging: ['SP_AGA_02_14', 'saveFile: a foreign staging name (EEXIST) refused and left; fresh save reports its bytes'],
    busy: ['SP_AGA_02_14', 'Busy policy during a run: saveFile served, openFile into it busy, into new served'],
  } },
  { name: 'bg_files', group: 'default', run: scenBgFiles, rows: {
    r1: ['SP_AGA_05_02', 'R1 no disturbance: background sequence with the openFile step, every sample unchanged'],
    r2: ['SP_AGA_05_02', 'R2 target as if active: the sequence with the openFile step on background X and active Y'],
  } },
  { name: 'bridge', group: 'default', run: scenBridge, rows: {
    stdioNoApp: ['SP_MCB_05_01', 'Stdio mode, no app: handshake ok, only the 3 bridge tools, target tools -> "No CircuitJS1 instance"'],
    selectErrors: ['SP_MCB_05_01', 'bridge_select unknown / unreachable / both given (-32602); bridge_launch without an app'],
    launch: ['SP_MCB_05_01', 'bridge_launch with file: app started, target set, a new active document holds the file, list_changed'],
    stdioApp: ['SP_MCB_05_01', 'Stdio mode with the app: its 14 tools (unchanged) followed by the 3 bridge tools'],
    bridge: ['SP_MCP_05_03', 'Bridge forwarding: any tool via the bridge = direct result'],
    transparent: ['SP_MCB_05_02', 'Invariants: forwarding transparent; tools (stdio and CLI) minus bridge_* = the target tools/list'],
    cli: ['SP_MCB_05_01', 'CLI: call valid 0 / domain error 1 / bad JSON 2, tools without app 3, instances 2 live + 1 stale, read catalogue, launch with file'],
    evalRow: ['SP_MCB_05_03', 'Eval harness: call circuit_connectivity -> exit 0, JSON parsed'],
    edges: ['SP_MCB_05_04', 'Edges: stale records only, explicit URL unreachable (no fallback), arguments from stdin = inline'],
    twoWindows: ['SP_MCB_05_03', 'Two windows: bridge_instances -> select each -> circuit_documents of the selected instance'],
    closed: ['SP_MCB_05_03', 'App closed mid-session: "Instance gone", the next call re-resolves'],
    stdioClean: ['SP_MCB_02_01', 'Stdio mode: stdout carries the protocol only, diagnostics on stderr'],
  } },
  { name: 'manual', group: 'default', always: true, run: scenManual, rows: {
    hidden: ['SP_MCP_05_01', 'Runtime settings: hidden window -> wallMs within 25 % of the visible run'],
    lan: ['SP_MCP_05_03', 'Private-network agent: LAN URL from the info dialog, circuit_types, no token'],
    desktop: ['SP_MCB_05_01', 'Stdio mode: Claude Desktop config with circuitjs-mcp -> app tools + 3 bridge tools'],
    browser: ['SP_MCP_05_04', 'Browser build: status disabled, no listen attempt'],
  } },
  { name: 'hostile_slow', group: 'slow', run: scenHostileSlow, rows: {
    incomplete: ['SP_MCP_02_01', 'Hostile input (slow): incomplete headers and body closed by the server'],
  } },
  { name: 'long120', group: 'slow', run: (R) => scenLong(R, 120000), rows: {
    long: ['SP_MCP_05_03', 'Long run while reading (slow): circuit_run budgetMs 120000 answers, reads served meanwhile'],
  } },
  { name: 'bridge_long', group: 'slow', run: scenBridgeLong, rows: {
    long: ['SP_MCB_05_04', 'Bridge CLI (slow): circuit_run budgetMs 120000 completes within the 130 s default timeout'],
  } },
  { name: 'clients', group: 'clients', run: scenClients, rows: {
    inspector: ['SP_MCP_05_01', 'Endpoint: MCP Inspector CLI -> tools/list 14, resources/list 4, templates 3'],
    claude: ['SP_MCP_05_01', 'Endpoint: Claude Code connects (claude -p, temporary --mcp-config) -> connected, 14 tools'],
    claudeCall: ['SP_MCP_05_01', 'Endpoint: Claude Code calls circuit_types through the server'],
  } },
  { name: 'bridge_clients', group: 'clients', run: scenBridgeClients, rows: {
    claude: ['SP_MCB_05_01', 'Bridge: Claude Code over stdio (temporary --mcp-config) -> connected, 14 app tools + 3 bridge tools'],
    claudeCall: ['SP_MCB_05_01', 'Bridge: Claude Code calls circuit_types through the bridge'],
  } },
];
const GROUPS = ['default', 'slow', 'clients'];

// ================================================================ main
// The scenario plan of this run: every row is printed exactly once, also after a harness error.
const plan = []; // {sc, rows, selected}
function summarize() {
  const count = (st) => results.filter((r) => r.status === st).length;
  try { fs.writeFileSync(path.join(OUT_DIR, 'results.json'), JSON.stringify(results, null, 2)); } catch (e) {}
  log(`SUMMARY ${JSON.stringify({ pass: count('PASS'), fail: count('FAIL'), skip: count('SKIP'), details: path.join(OUT_DIR, 'results.json') })}`);
  return count('FAIL');
}
// a harness error outside a scenario's own error handling (an unhandled rejection, an exception
// in a timer): print it, finish every pending row as FAIL, print the summary and exit 2
let aborting = false;
function abortRun(e) {
  if (aborting) return;
  aborting = true;
  const msg = (e && (e.stack || e.message)) || String(e);
  console.error('HARNESS ERROR: ' + msg);
  for (const { sc, rows, selected } of plan) {
    for (const r of Object.values(rows)) {
      if (!selected && !r.emitted) { r.emitted = true; emit('SKIP', r.id, r.title, { reason: `not selected (group ${sc.group})` }); } else r.emit('harness error: ' + clip(msg, 300));
    }
  }
  summarize();
  process.exit(2);
}
process.on('unhandledRejection', abortRun);
process.on('uncaughtException', abortRun);

async function main() {
  const major = +process.versions.node.split('.')[0];
  if (major < 22) { console.error(`HARNESS ERROR: Node ${process.versions.node}: the harness needs Node 22 or later (built-in WebSocket and fetch)`); return 2; }
  const args = process.argv.slice(2);
  if (args.includes('--list')) {
    for (const s of SCENARIOS) for (const [id, title] of Object.values(s.rows)) log(`${s.group.padEnd(8)} ${s.name.padEnd(13)} ${id} ${title}`);
    return 0;
  }
  const want = args.length ? args : ['default'];
  const selected = new Set();
  for (const a of want) {
    if (a === 'all') SCENARIOS.forEach((s) => selected.add(s.name));
    else if (GROUPS.includes(a)) SCENARIOS.filter((s) => s.group === a).forEach((s) => selected.add(s.name));
    else if (SCENARIOS.some((s) => s.name === a)) selected.add(a);
    else { console.error(`unknown group or scenario: ${a}\ngroups: ${GROUPS.join(', ')}, all; scenarios: ${SCENARIOS.map((s) => s.name).join(', ')}`); return 2; }
  }
  SCENARIOS.filter((s) => s.always).forEach((s) => selected.add(s.name));

  for (const f of ['circuitjs.html', 'scripts/mcp-server.js', 'package.json']) {
    if (!fs.existsSync(path.join(SITE_DIR, f))) { console.error(`HARNESS ERROR: ${path.join(SITE_DIR, f)} missing: run npm run buildgwt`); return 2; }
  }
  if (!fs.existsSync(NW_BIN)) { console.error(`HARNESS ERROR: NW.js binary ${NW_BIN} missing: run npm install`); return 2; }
  const busy = await busyPorts(SERVER_PORTS);
  if (busy.length) { console.error(`HARNESS ERROR: ports ${busy.join(', ')} are in use (another CircuitJS1 instance?); the rows need ${BASE}..${BASE + RANGE - 1} and ${ALT_PORT} free`); return 2; }
  T = { options: texts('Options'), mcp: texts('MCP Server...'), off: texts('(off)'), save: texts('Save'), close: texts('Close'), copy: texts('Copy') };
  fs.mkdirSync(OUT_DIR, { recursive: true });
  DISPLAY = await ensureDisplay();
  log(`MCP e2e: site ${SITE_DIR}, NW ${NW_BIN}, display ${DISPLAY}, out ${OUT_DIR}, scenarios ${[...selected].join(' ')}`);

  for (const sc of SCENARIOS) plan.push({ sc, rows: Object.fromEntries(Object.entries(sc.rows).map(([k, [id, title]]) => [k, new Row(id, title)])), selected: selected.has(sc.name) });
  for (const { sc, rows } of plan) {
    if (!selected.has(sc.name)) {
      for (const r of Object.values(rows)) { r.emitted = true; emit('SKIP', r.id, r.title, { reason: `not selected (group ${sc.group}: npm run test:mcp -- ${sc.group === 'default' ? sc.name : sc.group})` }); }
      continue;
    }
    const t0 = Date.now();
    let error = null;
    const stillBusy = await waitPortsFree(SERVER_PORTS, 15000);
    if (stillBusy.length) error = `ports still in use before the scenario: ${stillBusy.join(', ')}`;
    else {
      try { await sc.run(rows); } catch (e) { error = (e && (e.stack || e.message)) || String(e); }
    }
    if (VERBOSE || error) log(`[scenario ${sc.name}: ${((Date.now() - t0) / 1000).toFixed(1)} s${error ? ', error: ' + clip(error, 600) : ''}]`);
    for (const r of Object.values(rows)) r.emit(error && clip(error, 300));
  }
  return summarize() ? 1 : 0;
}

main().then((code) => process.exit(code)).catch(abortRun);
