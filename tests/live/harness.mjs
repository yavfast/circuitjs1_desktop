#!/usr/bin/env node
// Headless live-verification harness for CircuitJS1 Desktop (GWT build).
//
// Serves SITE_DIR over a local static HTTP server, launches headless Chromium,
// drives the app through the Chrome DevTools Protocol and the window.CircuitJS1
// automation API, and runs verification scenarios.
//
// Usage:  node tests/live/harness.mjs [scenario ...]      (after `npm run buildgwt`)
// Scenarios: undo | paste | sliders | loadstate | textfid | roundtrip | synth | agent_docs | agent_ids | agent_catalogue | agent_edit | agent_connect | agent_connect_all | agent_freerun | geom_posts | agent_history | agent_run | agent_bg | agent_files | pin_names | mcp_browser | mcp_dialog | eval | all (default: all but eval)
// See tests/live/README.md.
// Exit code: 0 if every scenario PASSes, 1 if any FAIL, 2 on harness error.

import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import net from 'node:net';
import os from 'node:os';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const PROJECT = process.env.PROJECT_DIR || path.resolve(HERE, '..', '..');
const SITE_DIR = path.resolve(process.env.SITE_DIR || path.join(PROJECT, 'target/site'));
// Artifacts go outside the repository by default (overwritten on every run)
const OUT_DIR = path.resolve(process.env.OUT_DIR || path.join(os.tmpdir(), 'circuitjs-live-harness'));
const CHROMIUM = process.env.CHROMIUM || 'chromium';
// tolerance of the R1 slice-bound checks for GC pauses and timer jitter (ms)
const SLICE_JITTER_MS = 2;
const VERBOSE = !!process.env.VERBOSE;
const LOAD_TIMEOUT_MS = +(process.env.LOAD_TIMEOUT_MS || 60000);

// Default roundtrip corpus: greedy cover of dump-type codes across the bundled
// examples, plus a few hand-picked ones (sliders, subcircuit/chips, AM, TL...).
const DEFAULT_CIRCUITS = [
  'qam-256.txt', 'alu74181.txt', 'conv-boost.txt', 'ledarray.txt', 'ota-gain.txt',
  'relays.txt', 'traffic.txt', 'triacdimmer.txt', 'tlfreq.txt', 'freqdouble.txt',
  'cc2.txt', 'bandnoise.txt', 'tesla.txt', 'counter.txt', 'relayctr.txt',
  'mr-sine.txt', 'tdiode.txt', 'zeneriv.txt', 'triode.txt', 'jfetfollower.txt',
  'scr.txt', '3motor.txt', 'avr8js-logic.txt', 'wheatstone.txt', 'ujtosc.txt',
  'varactor.txt', 'cube.txt', 'motorprotect.txt', 'early.txt', 'crystalosc2.txt',
  'amdetect.txt', 'ota-ringmod.txt', 'lissa.txt', 'delta-pwm.txt', 'piso-sr.txt',
  'ringmod.txt', '555int.txt', '7segdecoder.txt',
  // extra coverage: sliders/adjustables, labeled nodes, transmission line, VCVS, counters
  'lrc.txt', 'volume.txt', 'filt-vcvs-lopass.txt', 'tl.txt', 'deccounter.txt', 'jsinterface.txt',
];

const JSON_STATE = !!process.env.JSON_STATE; // use exportAsJsonWithState for J1/J2
const UNDO_CIRCUIT = process.env.UNDO_CIRCUIT || 'lrc.txt';
const PASTE_CIRCUIT = process.env.PASTE_CIRCUIT || 'lrc.txt';

// ---------------------------------------------------------------- utilities
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const log = (...a) => console.log(...a);
const vlog = (...a) => { if (VERBOSE) console.log('[v]', ...a); };

function freePort() {
  return new Promise((resolve, reject) => {
    const s = net.createServer();
    s.unref();
    s.on('error', reject);
    s.listen(0, '127.0.0.1', () => { const p = s.address().port; s.close(() => resolve(p)); });
  });
}

async function waitFor(fn, timeoutMs, what) {
  const t0 = Date.now();
  let lastErr;
  while (Date.now() - t0 < timeoutMs) {
    try { const v = await fn(); if (v) return v; } catch (e) { lastErr = e; }
    await sleep(200);
  }
  throw new Error(`timeout waiting for ${what}${lastErr ? ': ' + lastErr.message : ''}`);
}

const children = [];
function killChildren() {
  for (const c of children) {
    try { if (c.exitCode === null) process.kill(-c.pid, 'SIGKILL'); } catch { try { c.kill('SIGKILL'); } catch {} }
  }
}
process.on('exit', killChildren);
for (const sig of ['SIGINT', 'SIGTERM']) process.on(sig, () => { killChildren(); process.exit(130); });

function startChild(cmd, args, name) {
  const c = spawn(cmd, args, { detached: true, stdio: ['ignore', 'pipe', 'pipe'] });
  children.push(c);
  const lf = fs.createWriteStream(path.join(OUT_DIR, `${name}.log`));
  c.stdout.pipe(lf); c.stderr.pipe(lf);
  return c;
}

// ---------------------------------------------------------------- CDP client
class CDP {
  constructor(wsUrl) {
    this.ws = new WebSocket(wsUrl);
    this.id = 0; this.pending = new Map(); this.listeners = [];
    this.ws.onmessage = (ev) => {
      const msg = JSON.parse(ev.data);
      if (msg.id && this.pending.has(msg.id)) {
        const { resolve, reject } = this.pending.get(msg.id); this.pending.delete(msg.id);
        if (msg.error) reject(new Error(msg.error.message + (msg.error.data ? ' ' + msg.error.data : ''))); else resolve(msg.result);
      } else if (msg.method) {
        for (const l of this.listeners) l(msg.method, msg.params);
      }
    };
  }
  open() { return new Promise((res, rej) => { this.ws.onopen = res; this.ws.onerror = (e) => rej(new Error('ws error')); }); }
  send(method, params = {}) {
    const id = ++this.id;
    return new Promise((resolve, reject) => { this.pending.set(id, { resolve, reject }); this.ws.send(JSON.stringify({ id, method, params })); });
  }
  on(fn) { this.listeners.push(fn); }
  close() { try { this.ws.close(); } catch {} }
}

// ---------------------------------------------------------------- session
class Session {
  constructor(cdp, baseUrl) {
    this.cdp = cdp; this.baseUrl = baseUrl; this.console = []; this.exceptions = []; this.dialogs = [];
    cdp.on((m, p) => {
      if (m === 'Runtime.consoleAPICalled') {
        const text = p.args.map((a) => (a.value !== undefined ? String(a.value) : a.description || a.type)).join(' ');
        this.console.push({ type: p.type, text });
        vlog('console', p.type, text.slice(0, 200));
      } else if (m === 'Log.entryAdded') {
        this.console.push({ type: 'log:' + p.entry.level, text: p.entry.text + (p.entry.url ? ' @' + p.entry.url : '') });
      } else if (m === 'Runtime.exceptionThrown') {
        const d = p.exceptionDetails;
        this.exceptions.push((d.exception && d.exception.description) || d.text);
      } else if (m === 'Page.javascriptDialogOpening') {
        this.dialogs.push({ type: p.type, message: p.message });
        cdp.send('Page.handleJavaScriptDialog', { accept: true }).catch(() => {});
      }
    });
  }
  // Evaluate an expression in page; awaits promises; returns by value.
  async eval(expr) {
    const r = await this.cdp.send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true, userGesture: true });
    if (r.exceptionDetails) {
      const d = r.exceptionDetails;
      throw new Error('page exception: ' + ((d.exception && d.exception.description) || d.text));
    }
    return r.result.value;
  }
  // Call a page-side helper: H.name(...args)
  call(name, ...args) { return this.eval(`window.__H.${name}(...${JSON.stringify(args)})`); }

  async key(code, { ctrl = false, shift = false } = {}) {
    const map = {
      KeyZ: [90, 'z'], KeyY: [89, 'y'], KeyS: [83, 's'], KeyA: [65, 'a'], KeyC: [67, 'c'], KeyV: [86, 'v'], KeyD: [68, 'd'],
      Delete: [46, 'Delete'], Escape: [27, 'Escape'], Enter: [13, 'Enter'],
    };
    const [vk, key] = map[code];
    const modifiers = (ctrl ? 2 : 0) | (shift ? 8 : 0);
    const base = { modifiers, windowsVirtualKeyCode: vk, nativeVirtualKeyCode: vk, code, key: ctrl && key.length === 1 ? key : key };
    await this.cdp.send('Input.dispatchKeyEvent', { type: 'rawKeyDown', ...base });
    await this.cdp.send('Input.dispatchKeyEvent', { type: 'keyUp', ...base });
    await sleep(150);
  }
  // Printable key with a keypress (element shortcuts are read from ONKEYPRESS).
  async typeChar(ch) {
    const vk = ch.toUpperCase().charCodeAt(0); const code = 'Key' + ch.toUpperCase();
    await this.cdp.send('Input.dispatchKeyEvent', { type: 'keyDown', key: ch, code, text: ch, unmodifiedText: ch, windowsVirtualKeyCode: vk, nativeVirtualKeyCode: vk });
    await this.cdp.send('Input.dispatchKeyEvent', { type: 'keyUp', key: ch, code, windowsVirtualKeyCode: vk, nativeVirtualKeyCode: vk });
    await sleep(150);
  }
  // Left-button double click in viewport coordinates (hover first, so the editor picks the element).
  async mouseDoubleClick(x, y) {
    const m = (type, extra = {}) => this.cdp.send('Input.dispatchMouseEvent', { type, x, y, button: 'left', ...extra });
    await m('mouseMoved', { button: 'none' });
    await sleep(100);
    for (const clickCount of [1, 2]) {
      await m('mousePressed', { clickCount, buttons: 1 });
      await m('mouseReleased', { clickCount });
    }
    await sleep(300);
  }
  // Left-button drag in viewport coordinates (press, a few moves, release).
  async mouseDrag(x1, y1, x2, y2) {
    const m = (type, x, y, extra = {}) => this.cdp.send('Input.dispatchMouseEvent', { type, x, y, button: 'left', ...extra });
    await m('mouseMoved', x1, y1, { button: 'none' });
    await m('mousePressed', x1, y1, { clickCount: 1, buttons: 1 });
    for (let k = 1; k <= 4; k++) await m('mouseMoved', x1 + ((x2 - x1) * k) / 4, y1 + ((y2 - y1) * k) / 4, { buttons: 1 });
    await m('mouseReleased', x2, y2, { clickCount: 1 });
    await sleep(200);
  }
  markConsole() { return this.console.length; }
  consoleSince(mark) { return this.console.slice(mark); }
}

// Page-side helpers (serialized and injected into the page).
function pageHelpers() {
  const H = {
    async fetchText(url) { const r = await fetch(url, { cache: 'no-store' }); if (!r.ok) throw new Error(url + ' HTTP ' + r.status); return r.text(); },
    ready() {
      try { return typeof CircuitJS1 !== 'undefined' && typeof CircuitJS1.getElementCount === 'function' && CircuitJS1.getElementCount() >= 0; } catch (e) { return false; }
    },
    count() { return CircuitJS1.getElementCount(); },
    types() { return CircuitJS1.getElements().map((e) => e.getType()); },
    ids() { return CircuitJS1.getElementIds(); },
    jsonTypes() { return CircuitJS1.getElements().map((e) => e.getTypeName()); },
    // Viewport rect of the main circuit canvas (the largest canvas on the page).
    canvasRect() {
      const cs = Array.from(document.querySelectorAll('canvas')).sort((a, b) => b.width * b.height - a.width * a.height);
      const r = cs[0].getBoundingClientRect(); return { x: r.x, y: r.y, w: r.width, h: r.height };
    },
    exportText() { return CircuitJS1.exportCircuit(); },
    exportJson() { return CircuitJS1.exportAsJson(); },
    exportJsonState() { return CircuitJS1.exportAsJsonWithState(); },
    logCount() { return CircuitJS1.getLogCount(); },
    logsSince(n) { const all = CircuitJS1.getLogs() || []; return Array.from(all).slice(n).map(String); },
    clearLogs() { try { CircuitJS1.clearLogs(); } catch (e) {} },
    importText(t) { CircuitJS1.importCircuit(t, false); return CircuitJS1.getElementCount(); },
    importJson(j) { CircuitJS1.importFromJson(j); return CircuitJS1.getElementCount(); },
    async loadExample(name) { const t = await H.fetchText('/circuitjs1/circuits/' + name); CircuitJS1.importCircuit(t, false); return { count: CircuitJS1.getElementCount(), srcLines: t.split('\n').filter((l) => l.trim()).length }; },
    select(id, add) { return CircuitJS1.selectElementById(id, !!add); },
    focus() {
      const c = document.querySelector('canvas'); if (c) { c.setAttribute('tabindex', '0'); c.focus(); }
      window.focus();
      return !!c;
    },
    dialogShowing() {
      // Visible GWT popups/dialogs (these block key shortcuts in ActionManager).
      return Array.from(document.querySelectorAll('.gwt-DialogBox')).filter((d) => d.offsetWidth > 0 && getComputedStyle(d).visibility !== 'hidden').map((d) => (d.innerText || '').trim().slice(0, 120));
    },
    closeDialogs() {
      // Best effort: click any "OK"/"Close"/"Cancel" button in visible dialogs.
      let n = 0;
      for (const d of document.querySelectorAll('.gwt-DialogBox')) {
        if (d.offsetWidth === 0) continue;
        for (const b of d.querySelectorAll('button')) { if (/^(ok|close|cancel)$/i.test(b.innerText.trim())) { b.click(); n++; break; } }
      }
      return n;
    },
    selectedCount() { return CircuitJS1.getElements().filter((e) => e.isSelected()).length; },
    slidersDialog() {
      // Rows of the "Adjustable Sliders" dialog: slider count and plain (non gear/pencil) buttons.
      const d = Array.from(document.querySelectorAll('.gwt-DialogBox')).find((x) => x.textContent.includes('Adjustable Sliders'));
      if (!d || d.offsetWidth === 0) return { visible: false, sliders: 0, buttons: [] };
      const buttons = Array.from(d.querySelectorAll('button')).map((b) => b.textContent).filter((t) => !/^[\u2699\u270E]$/.test(t));
      return { visible: true, sliders: d.querySelectorAll('canvas').length, buttons };
    },
    simInfo() { const i = CircuitJS1.getSimInfo(); return i ? { running: i.running, stopMessage: i.stopMessage, elementCount: i.elementCount, time: i.time } : null; },
    // Agent API (window.CircuitJS1Agent): parsed OperationResult, or {__undefined: true} when the
    // call returned no string (a Java exception went to the uncaught handler).
    agentCall(op, args) {
      const r = CircuitJS1Agent.call(op, JSON.stringify(args || {}));
      return typeof r === 'string' ? JSON.parse(r) : { __undefined: true, type: typeof r };
    },
    agentRaw(op, argsJson) { const r = CircuitJS1Agent.call(op, argsJson); return { type: typeof r, text: r }; },
    // Asynchronous contract (run): parsed result when the callback fires, {timeout: true} otherwise.
    agentAsync(op, args, timeoutMs) {
      return new Promise((resolve) => {
        const to = setTimeout(() => resolve({ timeout: true }), timeoutMs || 30000);
        CircuitJS1Agent.callAsync(op, JSON.stringify(args || {}), (r) => { clearTimeout(to); resolve(JSON.parse(r)); });
      });
    },
    // Starts an asynchronous call without waiting; agentStarted(key) reports its callbacks.
    agentStart(key, op, args) {
      window.__agentRuns = window.__agentRuns || {};
      const st = window.__agentRuns[key] = { calls: 0, result: null, startedAt: performance.now(), doneAt: null };
      CircuitJS1Agent.callAsync(op, JSON.stringify(args || {}), (r) => { st.calls++; st.result = JSON.parse(r); st.doneAt = performance.now(); });
      return st.calls;
    },
    agentStarted(key) { return (window.__agentRuns || {})[key] || null; },
    agentCallAsync(op, args) {
      return new Promise((resolve) => {
        let sync = true;
        CircuitJS1Agent.callAsync(op, JSON.stringify(args || {}), (r) => resolve({ sync, type: typeof r, result: JSON.parse(r) }));
        sync = false;
        setTimeout(() => resolve({ timeout: true }), 3000);
      });
    },
    // Opens a menu path by DOM events, as a user click does; each step is a list of accepted
    // item texts (English and translations). Returns the number of steps found.
    async clickMenuPath(steps) {
      const visible = () => Array.from(document.querySelectorAll('.gwt-MenuItem')).filter((e) => e.offsetWidth > 0);
      const fire = (el, type) => el.dispatchEvent(new MouseEvent(type, { bubbles: true, cancelable: true, view: window }));
      let found = 0;
      for (const texts of steps) {
        const el = visible().find((e) => { const t = e.textContent.replace(/\s+/g, ' ').trim(); return texts.some((x) => t === x || t.startsWith(x + ' ')); });
        if (!el) break;
        fire(el, 'mouseover'); fire(el, 'click'); found++;
        await new Promise((r) => setTimeout(r, 300));
      }
      return found;
    },
    // Text of the visible dialog that has checkboxes (an element's edit dialog), or null.
    editDialogText() {
      const d = Array.from(document.querySelectorAll('.gwt-DialogBox')).find((x) => x.offsetWidth > 0 && x.querySelector('input[type=checkbox]'));
      return d ? (d.innerText || '').replace(/\s+/g, ' ').trim() : null;
    },
    // Clicks the n-th checkbox of the visible dialog (a user edit); returns false if there is none.
    clickDialogCheckbox(n) {
      const d = Array.from(document.querySelectorAll('.gwt-DialogBox')).find((x) => x.offsetWidth > 0 && x.querySelector('input[type=checkbox]'));
      const cb = d && d.querySelectorAll('input[type=checkbox]')[n];
      if (!cb) return false;
      cb.click();
      return true;
    },
    // What the user sees of the visible tab: tab bar, window title, circuit, sliders dialog and view.
    visibleTab() {
      const act = document.querySelector('.tabWidget.activeTab .tabTitle');
      return {
        tabCount: document.querySelectorAll('.tabWidget').length,
        activeTitle: act ? act.textContent : null,
        windowTitle: document.title,
        count: CircuitJS1.getElementCount(),
        ids: CircuitJS1.getElementIds().join(','),
        options: String(CircuitJS1.exportCircuit()).split('\n')[0],
        sliders: H.slidersDialog(),
        // renderer transform, canvas, circuit area, hint and the visible document's scope rects
        view: JSON.parse(CircuitJS1Agent.debugViewState()),
      };
    },
    // [PL_AGA_P8] SP_AGA_03_08 R1 sample: what the user sees of the visible tab plus the session UI
    // (menu check items, bars, voltage range, colours, Edit/Save items, mouse mode), the toolbar
    // Run/Stop button and the visible document's run state.
    r1Sample() {
      const btn = document.querySelector('.icon-stop, .icon-play');
      const info = CircuitJS1.getSimInfo();
      return { vis: H.visibleTab(), session: JSON.parse(CircuitJS1Agent.debugSessionState()), runButton: btn ? (btn.classList.contains('icon-stop') ? 'stop' : 'play') : null,
        running: info.running, stopMessage: info.stopMessage || '' };
    },
    simTime() { return CircuitJS1.getSimInfo().time; },
    // Slice probe (CircuitJS1Agent.debugSetSliceProbe): per slice its op, wall time from before the
    // scope entry to after its exit, the visible tab's simulated time at its start and an R1 sample
    // at its end.
    startSliceProbe() {
      const st = window.__slices = { list: [], begin: 0, visT: 0 };
      CircuitJS1Agent.debugSetSliceProbe((op, doc, phase) => {
        const now = performance.now();
        if (phase === 'begin') { st.begin = now; st.visT = CircuitJS1.getSimInfo().time; return; }
        st.list.push({ op, doc, ms: now - st.begin, at: st.begin, visT: st.visT, sample: H.r1Sample() });
      });
      return true;
    },
    stopSliceProbe() { CircuitJS1Agent.debugSetSliceProbe(null); return (window.__slices || { list: [] }).list; },
    // DOM mutations inside the Sliders dialog (a rebuild or a visible redraw of its rows)
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
    canvasPixels() { return CircuitJS1Agent.debugCanvasPixels(); },
  };
  window.__H = H;
  return true;
}

// ---------------------------------------------------------------- diff helpers
function textElementLines(t) {
  // Everything except the options header ($) — keep all other non-empty lines.
  return t.split('\n').map((l) => l.trimEnd()).filter((l) => l.trim() && !l.startsWith('$ '));
}
function lineDiff(a, b) {
  const n = Math.max(a.length, b.length); const out = [];
  for (let i = 0; i < n; i++) if (a[i] !== b[i]) out.push({ i, a: a[i] ?? null, b: b[i] ?? null });
  return out;
}
function multisetDiff(a, b) {
  const m = new Map();
  for (const l of a) m.set(l, (m.get(l) || 0) + 1);
  for (const l of b) m.set(l, (m.get(l) || 0) - 1);
  const onlyA = [], onlyB = [];
  for (const [l, c] of m) { for (let k = 0; k < c; k++) onlyA.push(l); for (let k = 0; k < -c; k++) onlyB.push(l); }
  return { onlyA, onlyB };
}
function flatten(obj, prefix = '', out = {}) {
  if (obj === null || typeof obj !== 'object') { out[prefix] = obj; return out; }
  if (Array.isArray(obj)) { if (!obj.length) out[prefix] = '[]'; obj.forEach((v, i) => flatten(v, `${prefix}[${i}]`, out)); return out; }
  const ks = Object.keys(obj); if (!ks.length && prefix) out[prefix] = '{}';
  for (const k of ks) flatten(obj[k], prefix ? `${prefix}.${k}` : k, out);
  return out;
}
function jsonElementDiff(j1, j2) {
  const e1 = (j1 && j1.elements) || {}, e2 = (j2 && j2.elements) || {};
  const res = { missing: [], added: [], typeChanged: [], changed: {} };
  for (const id of Object.keys(e1)) {
    if (!(id in e2)) { res.missing.push(`${id}:${e1[id].type}`); continue; }
    if (e1[id].type !== e2[id].type) res.typeChanged.push({ id, from: e1[id].type, to: e2[id].type });
    const f1 = flatten(e1[id]), f2 = flatten(e2[id]);
    const keys = new Set([...Object.keys(f1), ...Object.keys(f2)]);
    const d = [];
    for (const k of keys) if (JSON.stringify(f1[k]) !== JSON.stringify(f2[k])) d.push({ key: k, a: f1[k], b: f2[k] });
    if (d.length) res.changed[id] = { type: e1[id].type, diffs: d };
  }
  for (const id of Object.keys(e2)) if (!(id in e1)) res.added.push(`${id}:${e2[id].type}`);
  // top-level non-element sections
  const top = [];
  const t1 = flatten({ ...j1, elements: undefined }), t2 = flatten({ ...j2, elements: undefined });
  for (const k of new Set([...Object.keys(t1), ...Object.keys(t2)])) if (JSON.stringify(t1[k]) !== JSON.stringify(t2[k])) top.push({ key: k, a: t1[k], b: t2[k] });
  res.topLevel = top;
  return res;
}
// Classify a flattened JSON key into a diff category.
function keyCategory(k) {
  if (/connected_to|^nodes\./.test(k)) return 'connectivity';
  if (/^bounds\.|\.position\.|^p[12]\.|^properties\.(x2|y2)$/.test(k)) return 'geometry';
  if (/^state\./.test(k)) return 'state';
  if (/^properties\./.test(k)) return 'properties';
  return 'other';
}
// Text dump lines that are not elements (options, scopes, hints, adjustables, models).
const NON_ELEMENT_TEXT = new Set(['$', 'o', 'h', '38', '%', '?', '&', '!', '.', '/', '34', '32']);
function stripCoords(line) {
  const t = line.split(' ');
  if (NON_ELEMENT_TEXT.has(t[0]) || t.length < 5 || !t.slice(1, 5).every((x) => /^-?\d+$/.test(x))) return line;
  return [t[0], '_', '_', '_', '_', ...t.slice(5)].join(' ');
}
const LOG_PATTERNS = { unknown: /unknown element type/i, skipping: /skipping/i, error: /error|exception/i };
function countLogPatterns(lines) {
  const c = { unknown: 0, skipping: 0, error: 0 };
  for (const l of lines) for (const [k, re] of Object.entries(LOG_PATTERNS)) if (re.test(l)) c[k]++;
  return c;
}
// Element keys replaced by their position (#0, #1, ...), for diffs across paths that assign new IDs.
// References to an element ("R1", "R1.pin1") are renamed with it.
function rekeyByOrder(j) {
  if (!j || !j.elements) return j;
  const map = new Map(Object.keys(j.elements).map((k, i) => [k, '#' + i]));
  const ren = (v) => {
    if (typeof v === 'string') { const d = v.indexOf('.'); const head = d < 0 ? v : v.slice(0, d); return map.has(head) ? map.get(head) + (d < 0 ? '' : v.slice(d)) : v; }
    if (Array.isArray(v)) return v.map(ren);
    if (v && typeof v === 'object') return Object.fromEntries(Object.entries(v).map(([k, x]) => [k, ren(x)]));
    return v;
  };
  const r = ren({ ...j, elements: undefined });
  r.elements = Object.fromEntries(Object.entries(j.elements).map(([k, e]) => [map.get(k), ren(e)]));
  return r;
}
function safeParse(s) { try { return JSON.parse(s); } catch (e) { return { __parseError: e.message }; } }

// ---------------------------------------------------------------- scenarios
const results = [];
function report(name, pass, summary) {
  results.push({ name, pass, summary });
  log(`${pass ? 'PASS' : 'FAIL'} ${name} ${JSON.stringify(summary)}`);
}

async function resetApp(s) {
  await s.call('closeDialogs');
  await s.key('Escape');
}

async function scenarioUndo(s) {
  const out = {};
  await resetApp(s);
  const ld = await s.call('loadExample', UNDO_CIRCUIT);
  out.circuit = UNDO_CIRCUIT; out.loaded = ld.count;
  const before = await s.call('exportText');
  const ids = await s.call('ids');
  // Pick a non-wire element to delete (so the change is obvious).
  const types = await s.call('types');
  let idx = types.findIndex((t) => !/WireElm/.test(t)); if (idx < 0) idx = 0;
  out.deletedId = ids[idx]; out.deletedType = types[idx];
  await s.call('focus');
  out.dialogsBefore = await s.call('dialogShowing');
  await s.call('select', ids[idx], false);
  await s.key('Delete');
  out.afterDelete = await s.call('count');
  const markLog = await s.call('logCount');
  const markCon = s.markConsole();
  await s.key('KeyZ', { ctrl: true });
  out.afterUndo = await s.call('count');
  const afterUndoText = await s.call('exportText');
  out.textEqualAfterUndo = textElementLines(before).join('\n') === textElementLines(afterUndoText).join('\n');
  await s.key('KeyY', { ctrl: true });
  out.afterRedo = await s.call('count');
  out.logs = (await s.call('logsSince', markLog)).slice(0, 20);
  out.console = s.consoleSince(markCon).map((c) => c.text).filter((t) => !/^hasSystemClipboardSupport/.test(t)).slice(0, 20);
  fs.writeFileSync(path.join(OUT_DIR, 'undo_before.txt'), before);
  fs.writeFileSync(path.join(OUT_DIR, 'undo_after_undo.txt'), afterUndoText);
  fs.writeFileSync(path.join(OUT_DIR, 'undo.json'), JSON.stringify(out, null, 2));
  const drivable = out.afterDelete === out.loaded - 1;
  out.keyboardDrivable = drivable;
  const pass = drivable && out.afterUndo === out.loaded && out.textEqualAfterUndo && out.afterRedo === out.loaded - 1;
  report('A.undo', pass, { circuit: out.circuit, loaded: out.loaded, deleted: `${out.deletedId}(${out.deletedType})`, afterDelete: out.afterDelete, afterUndo: out.afterUndo, afterRedo: out.afterRedo, textEqualAfterUndo: out.textEqualAfterUndo, keyboardDrivable: drivable });
}

async function scenarioPaste(s) {
  const out = { circuit: PASTE_CIRCUIT };
  await resetApp(s);
  // B1: select all + duplicate (Ctrl+A, Ctrl+D)
  let ld = await s.call('loadExample', PASTE_CIRCUIT); out.loaded = ld.count;
  await s.call('focus');
  await s.key('KeyA', { ctrl: true });
  out.selectedAfterCtrlA = await s.call('selectedCount');
  await s.key('KeyD', { ctrl: true });
  out.afterDuplicate = await s.call('count');
  out.typesAfterDuplicate = await s.call('types');
  out.textAfterDuplicate = await s.call('exportText');
  await s.key('KeyZ', { ctrl: true });
  out.afterDuplicateUndo = await s.call('count');
  // B2: select all + copy + paste (Ctrl+A, Ctrl+C, Ctrl+V)
  ld = await s.call('loadExample', PASTE_CIRCUIT);
  await s.call('focus');
  const markCon = s.markConsole();
  await s.key('Escape');
  await s.key('KeyA', { ctrl: true });
  await s.key('KeyC', { ctrl: true });
  await s.key('KeyV', { ctrl: true });
  await sleep(300);
  out.afterPaste = await s.call('count');
  out.console = s.consoleSince(markCon).map((c) => c.text.slice(0, 300)).slice(0, 20);
  fs.writeFileSync(path.join(OUT_DIR, 'paste.json'), JSON.stringify(out, null, 2));
  const pass = out.afterDuplicate === 2 * out.loaded && out.afterPaste === 2 * out.loaded && out.afterDuplicateUndo === out.loaded;
  report('B.paste_duplicate', pass, { circuit: out.circuit, loaded: out.loaded, selectedAfterCtrlA: out.selectedAfterCtrlA, afterDuplicate: out.afterDuplicate, afterDuplicateUndo: out.afterDuplicateUndo, afterPaste: out.afterPaste, expected: 2 * out.loaded });
}

// Built-in element sliders (Pot/LDR/NTC/VarRail) and the AudioOutput "Play" row live in the Sliders
// dialog and must follow load, duplicate, undo and delete.
const SLIDER_CIRCUIT = '$ 1 0.000005 10.2 50 5 50 5e-11\n' +
  '174 144 240 80 272 0 1000 0.3 Pot A\n' +
  '374 300 100 300 200 0 0.34 Light\n' +
  '350 400 100 400 200 0 10000 3605 -40 150 0.34 Temp\n' +
  '172 272 288 192 288 0 6 4.5 5.0 0.0 0.0 0.5 Voltage\n' +
  '211 592 272 688 272 0 1 8000 1\n';
async function scenarioSliders(s) {
  const out = {};
  await resetApp(s);
  const adj = (t) => t.split('\n').filter((l) => l.startsWith('38 ')).length;
  out.loaded = await s.call('importText', SLIDER_CIRCUIT);
  await sleep(300);
  out.onLoad = await s.call('slidersDialog');
  out.adjOnLoad = adj(await s.call('exportText'));
  await s.call('focus');
  await s.key('KeyA', { ctrl: true });
  await s.key('KeyD', { ctrl: true });
  await sleep(300);
  out.afterDuplicate = await s.call('slidersDialog');
  out.adjAfterDuplicate = adj(await s.call('exportText'));
  await s.key('KeyZ', { ctrl: true });
  await sleep(300);
  out.afterUndo = await s.call('slidersDialog');
  const ids = await s.call('ids'); const types = await s.call('types');
  await s.call('select', ids[types.indexOf('PotElm')], false);
  await s.call('select', ids[types.indexOf('AudioOutputElm')], true);
  await s.key('Delete');
  await sleep(300);
  out.afterDelete = await s.call('slidersDialog');
  fs.writeFileSync(path.join(OUT_DIR, 'sliders.json'), JSON.stringify(out, null, 2));
  const n = (r) => r.sliders, b = (r) => r.buttons.length;
  const pass = n(out.onLoad) === 4 && b(out.onLoad) === 1 && out.adjOnLoad === 4
    && n(out.afterDuplicate) === 8 && b(out.afterDuplicate) === 2 && out.adjAfterDuplicate === 8
    && n(out.afterUndo) === 4 && b(out.afterUndo) === 1
    && n(out.afterDelete) === 3 && b(out.afterDelete) === 0;
  report('D.element_sliders', pass, { sliders: [n(out.onLoad), n(out.afterDuplicate), n(out.afterUndo), n(out.afterDelete)], buttons: [b(out.onLoad), b(out.afterDuplicate), b(out.afterUndo), b(out.afterDelete)], adjustableLines: [out.adjOnLoad, out.adjAfterDuplicate] });
}

// Runs in the page: state that must survive a text/JSON reload (what undo does) — chip clock
// history (no false edge, no active-low clear at load), boolean dump fields, `38` slider indices
// when a dump-less element precedes them.
async function loadStateProbe() {
  const C = window.CircuitJS1, out = {};
  const base = location.href.replace(/[^/]*$/, '') + 'circuitjs1/circuits/';
  const get = async (f) => (await fetch(base + f)).text();
  const steps = (n) => { for (let i = 0; i < n; i++) C.stepSimulation(); };
  const load = (t) => { C.setSimRunning(false); C.importCircuit(t, false); C.setSimRunning(false); };
  const volts = (type, posts) => { const e = C.getElements().find((x) => x.getType() === type); return posts.map((i) => Math.round(e.getVoltage(i))); };
  // D flip-flop, clock high at t=0, saved Q=0 / Q=5
  const d2 = await get('divideby2.txt');
  load(d2); steps(20); out.dffFreshQ = volts('DFlipFlopElm', [1])[0];
  load(d2.replace('155 272 96 320 96 0 0.0', '155 272 96 320 96 0 5.0')); steps(20); out.dffSavedQ5 = volts('DFlipFlopElm', [1])[0];
  // counter with an active-low reset held high: count kept across text and JSON reload
  load(await get('graycode.txt')); steps(1500);
  const c0 = volts('CounterElm', [2, 3, 4, 5]).join();
  load(C.exportCircuit()); steps(20); const c1 = volts('CounterElm', [2, 3, 4, 5]).join();
  C.importFromJson(C.exportAsJsonWithState()); C.setSimRunning(false); steps(20); const c2 = volts('CounterElm', [2, 3, 4, 5]).join();
  out.counter = [c0, c1, c2];
  // boolean dump fields survive two text passes
  // [line, expected flag]; `false` cases catch a reader that always returns true
  const lines = [['164 144 152 224 152 0 4 0 0 0 0 true 0', 'true'], ['164 144 152 224 152 0 4 0 0 0 0 false 0', 'false'],
    ['428 640 432 736 576 0 0.0613 6.73 true abc', 'true'], ['206 1360 272 1312 528 0 0.011 0.008 100 true', 'true'],
    ['404 100 100 200 100 0 0.05 2 0 true', 'true'], ['194 100 100 164 100 0 true 0.01', 'true'], ['s 100 100 200 100 0 1 true', 'true']];
  out.boolLost = [];
  for (const [L, flag] of lines) {
    const code = L.split(' ')[0];
    const pick = (x) => x.split('\n').find((l) => l.startsWith(code + ' '));
    load('$ 1 5e-6 10 50 5\n' + L + '\n'); load(C.exportCircuit());
    if (!new RegExp(' ' + flag + '( |$)').test(pick(C.exportCircuit()) || '')) out.boolLost.push(code + ':' + flag);
  }
  // `38` lines with a standalone CustomCompositeChip placed first
  load(await get('lrc.txt'));
  const elmLines = (t) => t.split('\n').filter((l) => l && !/^(38|o|h|\$|%|\?|!) /.test(l + ' ') && !l.startsWith('$'));
  const refTypes = (t) => { const L = elmLines(t); return t.split('\n').filter((l) => l.startsWith('38 ')).map((l) => (L[+l.split(' ')[1]] || '?').split(' ')[0]).join(); };
  const want = refTypes(C.exportCircuit());
  const j = JSON.parse(C.exportAsJson());
  j.elements = Object.assign({ XCHIP: { type: 'CustomCompositeChip', p1: { x: 600, y: 600 }, p2: { x: 700, y: 600 } } }, j.elements);
  C.importFromJson(JSON.stringify(j)); C.setSimRunning(false);
  const t1 = C.exportCircuit(); load(t1);
  out.sliderRefs = [want, refTypes(t1), refTypes(C.exportCircuit())];
  // [SP_AGA_06_01 item 18] a file's own maximum time step is kept on a text load (the time-step bar
  // is set without running its command, which re-quantised to the 1-2-5 table capped at 10 us) and
  // survives a text and a JSON reload: [file step, after load, after text reload, after JSON reload]
  const stepOf = () => Number(C.exportCircuit().split('\n')[0].split(' ')[2]);
  out.stepKept = [];
  for (const ts of ['0.001', '0.000015625']) {
    load('$ 1 ' + ts + ' 10 50 5\nr 64 64 128 64 0 1000\n'); const a = stepOf();
    load(C.exportCircuit()); const b = stepOf();
    C.importFromJson(C.exportAsJson()); C.setSimRunning(false); const c = stepOf();
    out.stepKept.push([Number(ts), a, b, c]);
  }
  // a garbled `$` step (parsed as 0) falls back to the blank-circuit default 5 us
  load('$ 1 abc 10 50 5\nr 64 64 128 64 0 1000\n'); out.badStep = stepOf();
  return out;
}
async function scenarioLoadState(s) {
  await resetApp(s);
  const out = await s.eval(`(${loadStateProbe.toString()})()`);
  fs.writeFileSync(path.join(OUT_DIR, 'loadstate.json'), JSON.stringify(out, null, 2));
  // a zero count or no `38` lines would make the equality checks pass trivially
  const pass = out.dffFreshQ === 0 && out.dffSavedQ5 === 5
    && out.counter[0] !== '0,0,0,0' && out.counter[0] === out.counter[1] && out.counter[1] === out.counter[2]
    && out.boolLost.length === 0
    && out.sliderRefs[0] !== '' && out.sliderRefs[0] === out.sliderRefs[1] && out.sliderRefs[1] === out.sliderRefs[2]
    && out.stepKept.length === 2 && out.stepKept.every((r) => r.every((x) => Math.abs(x - r[0]) <= 1e-9 * r[0]))
    && out.badStep === 5e-6;
  report('L.load_state', pass, out);
}

// One roundtrip: start state already loaded in app. Returns detail record.
async function roundtripCurrent(s, label, detailDir, srcCount) {
  const rec = { circuit: label };
  const T1 = await s.call('exportText');
  const J1s = await s.call(JSON_STATE ? 'exportJsonState' : 'exportJson');
  const types1 = await s.call('types');
  if (typeof T1 !== 'string' || typeof J1s !== 'string') {
    return Object.assign(rec, { exportFailed: { text: typeof T1, json: typeof J1s }, count1: types1.length, recentLogs: (await s.call('logsSince', Math.max(0, (await s.call('logCount')) - 5))).map((l) => l.slice(0, 400)), recentExceptions: s.exceptions.slice(-2).map((e) => e.slice(0, 600)) });
  }
  const logMark = await s.call('logCount');
  const conMark = s.markConsole();
  let err = null;
  try { await s.call('importJson', J1s); } catch (e) { err = e.message; }
  const T2 = String(await s.call('exportText'));
  const J2s = String(await s.call(JSON_STATE ? 'exportJsonState' : 'exportJson'));
  const types2 = await s.call('types');
  const logs = await s.call('logsSince', logMark);
  const con = s.consoleSince(conMark).map((c) => `[${c.type}] ${c.text.slice(0, 500)}`);
  const J1 = safeParse(J1s), J2 = safeParse(J2s);
  const L1 = textElementLines(T1), L2 = textElementLines(T2);
  const ld = lineDiff(L1, L2), md = multisetDiff(L1, L2);
  const jd = jsonElementDiff(J1, J2);
  const ldNoGeom = lineDiff(L1.map(stripCoords), L2.map(stripCoords));
  const catCount = { geometry: 0, connectivity: 0, properties: 0, state: 0, other: 0 };
  const propChanges = [];
  for (const [id, c] of Object.entries(jd.changed)) {
    const cats = new Set(c.diffs.map((d) => keyCategory(d.key)));
    for (const k of cats) catCount[k]++;
    for (const d of c.diffs) if (['properties', 'state', 'other'].includes(keyCategory(d.key))) propChanges.push({ id, type: c.type, key: d.key, a: d.a, b: d.b });
  }
  // element class changes (by position) and class-count delta
  const cnt = (arr) => arr.reduce((m, t) => ((m[t] = (m[t] || 0) + 1), m), {});
  const c1 = cnt(types1), c2 = cnt(types2); const classDelta = {};
  for (const k of new Set([...Object.keys(c1), ...Object.keys(c2)])) if ((c1[k] || 0) !== (c2[k] || 0)) classDelta[k] = (c2[k] || 0) - (c1[k] || 0);
  Object.assign(rec, {
    srcCount, count1: types1.length, count2: types2.length, importError: err,
    textLines1: L1.length, textLines2: L2.length, posDiffLines: ld.length, nonGeomDiffLines: ldNoGeom.length, multisetOnlyT1: md.onlyA.length, multisetOnlyT2: md.onlyB.length,
    jsonElems1: Object.keys(J1.elements || {}).length, jsonElems2: Object.keys(J2.elements || {}).length,
    jsonMissing: jd.missing.length, jsonAdded: jd.added.length, jsonTypeChanged: jd.typeChanged.length,
    jsonElemsChanged: Object.keys(jd.changed).length, jsonElemsChangedByCategory: catCount, jsonTopLevelDiffs: jd.topLevel.length,
    propChanges: propChanges.slice(0, 50), topLevelNonGeom: jd.topLevel.filter((d) => !/^nodes\./.test(d.key)).slice(0, 20),
    classDelta, logCounts: countLogPatterns([...logs, ...con]),
  });
  // changed property keys aggregated by JSON type
  const byType = {};
  for (const [id, c] of Object.entries(jd.changed)) {
    byType[c.type] = byType[c.type] || new Set();
    for (const d of c.diffs) byType[c.type].add(d.key.replace(/\[\d+\]/g, '[]'));
  }
  rec.changedKeysByType = Object.fromEntries(Object.entries(byType).map(([k, v]) => [k, [...v]]));
  rec.missingTypes = [...new Set(jd.missing.map((m) => m.split(':')[1]))];
  rec.typeChanges = jd.typeChanged;
  if (detailDir) {
    const base = path.join(detailDir, label.replace(/[^A-Za-z0-9._-]/g, '_'));
    fs.writeFileSync(base + '.T1.txt', T1); fs.writeFileSync(base + '.T2.txt', T2);
    fs.writeFileSync(base + '.J1.json', J1s); fs.writeFileSync(base + '.J2.json', J2s);
    fs.writeFileSync(base + '.diff.json', JSON.stringify({ summary: rec, lineDiff: ld, lineDiffIgnoringCoords: ldNoGeom, multiset: md, json: jd, logs, console: con }, null, 2));
  }
  return rec;
}

function listAllCircuits() {
  return fs.readdirSync(path.join(SITE_DIR, 'circuitjs1/circuits')).filter((f) => f.endsWith('.txt')).sort();
}

function printTable(recs) {
  const cols = ['circuit', 'count1', 'count2', 'posDiffLines', 'nonGeomDiffLines', 'jsonMissing', 'jsonTypeChanged', 'jGeom', 'jConn', 'jProp', 'classDelta', 'unk', 'skip', 'err'];
  const rows = recs.map((r) => ({ ...r, jGeom: r.jsonElemsChangedByCategory?.geometry ?? '', jConn: r.jsonElemsChangedByCategory?.connectivity ?? '', jProp: (r.jsonElemsChangedByCategory?.properties ?? 0) + (r.jsonElemsChangedByCategory?.other ?? 0) || (r.jsonElemsChangedByCategory ? 0 : ''),
    classDelta: r.harnessError ? 'HARNESS:' + r.harnessError.slice(0, 40) : r.exportFailed ? 'EXPORT_FAILED' : Object.entries(r.classDelta || {}).map(([k, v]) => `${k.replace(/Elm$/, '')}${v > 0 ? '+' : ''}${v}`).join(' '),
    unk: r.logCounts?.unknown ?? '', skip: r.logCounts?.skipping ?? '', err: r.logCounts?.error ?? '' }));
  const w = cols.map((c) => Math.max(c.length, ...rows.map((r) => String(r[c] ?? '').length)));
  log(cols.map((c, i) => c.padEnd(w[i])).join(' | '));
  for (const r of rows) log(cols.map((c, i) => String(r[c] ?? '').padEnd(w[i])).join(' | '));
}

function aggregate(recs) {
  const agg = { circuits: recs.length, withNonGeomLineDiffs: 0, withPropChanges: 0, withGeomChanges: 0, withClassDelta: 0, classDeltas: {}, propChangesByType: {}, withCountChange: 0, withLineDiffs: 0, withJsonDiffs: 0, withMissing: 0, withTypeChange: 0, changedKeysByType: {}, missingTypes: {}, typeChanges: {}, logTotals: { unknown: 0, skipping: 0, error: 0 } };
  for (const r of recs) {
    if (r.count1 !== r.count2) agg.withCountChange++;
    if (r.nonGeomDiffLines) agg.withNonGeomLineDiffs++;
    if (r.jsonElemsChangedByCategory?.geometry) agg.withGeomChanges++;
    if ((r.jsonElemsChangedByCategory?.properties || 0) + (r.jsonElemsChangedByCategory?.other || 0)) agg.withPropChanges++;
    if (Object.keys(r.classDelta || {}).length) { agg.withClassDelta++; agg.classDeltas[r.circuit] = r.classDelta; }
    for (const pc of r.propChanges || []) { const k = `${pc.type}.${pc.key}`; (agg.propChangesByType[k] = agg.propChangesByType[k] || []).push(`${r.circuit}:${pc.id}: ${JSON.stringify(pc.a)} -> ${JSON.stringify(pc.b)}`); }
    if (r.posDiffLines) agg.withLineDiffs++;
    if (r.jsonElemsChanged || r.jsonMissing || r.jsonAdded) agg.withJsonDiffs++;
    if (r.jsonMissing) agg.withMissing++;
    if (r.jsonTypeChanged) agg.withTypeChange++;
    for (const [t, ks] of Object.entries(r.changedKeysByType || {})) { agg.changedKeysByType[t] = agg.changedKeysByType[t] || {}; for (const k of ks) agg.changedKeysByType[t][k] = (agg.changedKeysByType[t][k] || []).concat(r.circuit).slice(0, 5); }
    for (const t of r.missingTypes || []) agg.missingTypes[t] = (agg.missingTypes[t] || []).concat(r.circuit);
    for (const tc of r.typeChanges || []) { const k = `${tc.from}->${tc.to}`; agg.typeChanges[k] = (agg.typeChanges[k] || []).concat(r.circuit); }
    for (const k of Object.keys(agg.logTotals)) agg.logTotals[k] += r.logCounts?.[k] || 0;
  }
  return agg;
}

async function scenarioRoundtrip(s) {
  const list = process.env.CIRCUITS === 'all' ? listAllCircuits() : (process.env.CIRCUITS ? process.env.CIRCUITS.split(',') : DEFAULT_CIRCUITS);
  const dir = path.join(OUT_DIR, 'roundtrip'); fs.mkdirSync(dir, { recursive: true });
  const recs = [];
  for (const f of list) {
    await resetApp(s);
    let rec;
    try {
      const logMark = await s.call('logCount');
      const conMark = s.markConsole();
      const ld = await s.call('loadExample', f);
      const loadLogs = [...(await s.call('logsSince', logMark)), ...s.consoleSince(conMark).map((c) => c.text)];
      rec = await roundtripCurrent(s, f, dir, ld.srcLines);
      rec.loadLogCounts = countLogPatterns(loadLogs);
    } catch (e) { rec = { circuit: f, harnessError: e.message }; }
    recs.push(rec);
    vlog(JSON.stringify(rec));
  }
  const agg = aggregate(recs);
  // Human-readable grouping of coordinate-insensitive text diffs by dump type code.
  const byType = {};
  for (const f of fs.readdirSync(dir).filter((x) => x.endsWith('.diff.json'))) {
    const d = JSON.parse(fs.readFileSync(path.join(dir, f), 'utf8'));
    for (const x of d.lineDiffIgnoringCoords || []) { const t = String(x.a ?? x.b ?? '').split(' ')[0]; (byType[t] = byType[t] || []).push([f.replace('.diff.json', ''), x.a, x.b]); }
  }
  const txt = Object.entries(byType).sort((a, b) => b[1].length - a[1].length).map(([t, v]) =>
    `== ${t}: ${v.length} lines, circuits=${[...new Set(v.map((x) => x[0]))].join(',')}\n` + v.slice(0, 3).map(([c, a, b]) => `   ${c}\n     T1: ${a}\n     T2: ${b}`).join('\n')).join('\n');
  fs.writeFileSync(path.join(OUT_DIR, 'roundtrip_textdiff_by_type.txt'), txt + '\n');
  fs.writeFileSync(path.join(OUT_DIR, 'roundtrip_summary.json'), JSON.stringify({ aggregate: agg, circuits: recs }, null, 2));
  printTable(recs);
  const pass = recs.every((r) => !r.harnessError && !r.exportFailed && !Object.keys(r.classDelta || {}).length && r.count1 === r.count2 && r.posDiffLines === 0 && r.jsonElemsChanged === 0 && r.jsonMissing === 0 && r.jsonTypeChanged === 0);
  report('C.json_roundtrip', pass, { circuits: agg.circuits, countChanged: agg.withCountChange, lineDiffs: agg.withLineDiffs, nonGeomLineDiffs: agg.withNonGeomLineDiffs, geomChanged: agg.withGeomChanges, propChanged: agg.withPropChanges, classChanged: agg.withClassDelta, jsonDiffs: agg.withJsonDiffs, missing: agg.withMissing, typeChanged: agg.withTypeChange, jsonTypesWithChangedKeys: Object.keys(agg.changedKeysByType).length, logTotals: agg.logTotals, details: path.join(OUT_DIR, 'roundtrip_summary.json') });
}

// Synthetic: one default-constructed element per registered JSON type name
// (names extracted from the compiled build), JSON -> T1/J1 -> import J1 -> T2/J2,
// plus the text leg: import T1 -> J3 (text-format fidelity).
function buildTypeNames() {
  const dir = path.join(SITE_DIR, 'circuitjs1');
  const f = fs.readdirSync(dir).find((x) => x.endsWith('.cache.js'));
  const src = fs.readFileSync(path.join(dir, f), 'utf8');
  const names = new Set();
  for (const m of src.matchAll(/ElementConstructor_2V\('([A-Za-z0-9_]+)'/g)) names.add(m[1]);
  return [...names].sort();
}

async function scenarioSynth(s) {
  const names = buildTypeNames();
  const dir = path.join(OUT_DIR, 'synth'); fs.mkdirSync(dir, { recursive: true });
  const recs = [];
  for (const t of names) {
    await resetApp(s);
    const circ = { schema: { format: 'circuitjs', version: '2.0' }, elements: { X1: { type: t, p1: { x: 208, y: 208 }, p2: { x: 304, y: 208 } } } };
    let rec;
    try {
      const logMark = await s.call('logCount');
      const n0 = await s.call('importJson', JSON.stringify(circ));
      const createLogs = await s.call('logsSince', logMark);
      if (n0 === 0) { rec = { circuit: t, created: 0, createLogs: createLogs.slice(0, 5) }; recs.push(rec); continue; }
      const created = await s.call('types');
      const T1 = await s.call('exportText');
      rec = await roundtripCurrent(s, t, dir, n0);
      rec.created = n0; rec.createdClass = created.join(',');
      if (rec.exportFailed) { recs.push(rec); continue; }
      // text leg
      const J1s = fs.readFileSync(path.join(dir, t + '.J1.json'), 'utf8');
      await s.call('importText', T1);
      const J3s = await s.call('exportJson');
      // A text load gives elements generated IDs in file order (SP_AGA_03_02), while J1 keeps the
      // JSON key X1: compare the text leg by element order, not by key.
      const jd3 = jsonElementDiff(rekeyByOrder(safeParse(J1s)), rekeyByOrder(safeParse(J3s)));
      rec.textLeg = { count: Object.keys(safeParse(J3s).elements || {}).length, changed: Object.values(jd3.changed).flatMap((c) => c.diffs.map((d) => d.key)), typeChanged: jd3.typeChanged };
      fs.writeFileSync(path.join(dir, t + '.J3.json'), J3s);
    } catch (e) { rec = { circuit: t, harnessError: e.message }; }
    recs.push(rec);
  }
  const notCreated = recs.filter((r) => r.created === 0).map((r) => r.circuit);
  const errs = recs.filter((r) => r.harnessError).map((r) => `${r.circuit}: ${r.harnessError.slice(0, 120)}`);
  const ok = recs.filter((r) => r.created && !r.exportFailed);
  const exportFailed = recs.filter((r) => r.exportFailed).map((r) => ({ type: r.circuit, logs: r.recentLogs, exc: r.recentExceptions }));
  const classChanged = Object.fromEntries(ok.filter((r) => Object.keys(r.classDelta || {}).length).map((r) => [r.circuit, r.classDelta]));
  const propLoss = Object.fromEntries(ok.filter((r) => (r.propChanges || []).length).map((r) => [r.circuit, r.propChanges.map((p) => `${p.key}: ${JSON.stringify(p.a)} -> ${JSON.stringify(p.b)}`)]));
  const nonGeomText = Object.fromEntries(ok.filter((r) => r.nonGeomDiffLines).map((r) => [r.circuit, r.nonGeomDiffLines]));
  const jsonLoss = ok.filter((r) => r.count1 !== r.count2 || r.jsonElemsChanged || r.jsonMissing || r.jsonTypeChanged);
  const textLoss = ok.filter((r) => r.textLeg && (r.textLeg.count !== 1 || r.textLeg.changed.length || r.textLeg.typeChanged.length));
  const sum = {
    typeNames: names.length, created: ok.length + exportFailed.length, notCreated, harnessErrors: errs, exportFailed, classChanged, propLoss, nonGeomTextDiff: nonGeomText,
    geometryChanged: ok.filter((r) => r.jsonElemsChangedByCategory?.geometry).length,
    jsonLegLoss: Object.fromEntries(jsonLoss.map((r) => [r.circuit, { count2: r.count2, missing: r.jsonMissing, typeChanges: r.typeChanges, keys: Object.values(r.changedKeysByType).flat() }])),
    textLegLoss: Object.fromEntries(textLoss.map((r) => [r.circuit, { count: r.textLeg.count, keys: r.textLeg.changed, typeChanged: r.textLeg.typeChanged }])),
  };
  fs.writeFileSync(path.join(OUT_DIR, 'synth_summary.json'), JSON.stringify({ summary: sum, records: recs }, null, 2));
  const pass = !notCreated.length && !errs.length && !exportFailed.length && !jsonLoss.length && !textLoss.length;
  report('S.synthetic_all_types', pass, { typeNames: names.length, created: sum.created, notCreated, harnessErrors: errs.length, exportFailed: exportFailed.map((e) => e.type), classChanged: Object.keys(classChanged), propLoss: Object.keys(propLoss), geometryChanged: sum.geometryChanged, jsonLegLoss: jsonLoss.length, textLegLoss: Object.keys(sum.textLegLoss), details: path.join(OUT_DIR, 'synth_summary.json') });
}

// Text-format fidelity: raw example file -> text import -> text export (T1), compared number by
// number per element line. Catches lossy number formatting in the text writer (precision/sign),
// which a T1-vs-T2 JSON roundtrip cannot see because both sides use the same writer.
async function scenarioTextFidelity(s) {
  const list = process.env.CIRCUITS === 'all' ? listAllCircuits() : (process.env.CIRCUITS ? process.env.CIRCUITS.split(',') : DEFAULT_CIRCUITS);
  const num = (t) => (/^[-+]?(\d+\.?\d*|\.\d+)([eE][-+]?\d+)?$/.test(t) ? Number(t) : null);
  const elmKey = (toks) => toks.slice(0, 5).join(' ');
  const isElm = (toks) => toks.length >= 6 && !['$', 'o', 'h', '38', '!', '%', '?', 'B', '34', '32', '.'].includes(toks[0]) && toks.slice(1, 5).every((t) => num(t) !== null);
  const agg = { circuits: 0, linesCompared: 0, lossy: 0, signFlips: 0, byType: {}, samples: [] };
  for (const name of list) {
    const raw = fs.readFileSync(path.join(SITE_DIR, 'circuitjs1/circuits', name), 'utf8');
    await s.call('loadExample', name);
    const T1 = String(await s.call('exportText'));
    const t1 = new Map();
    for (const line of T1.split('\n')) { const toks = line.trim().split(/\s+/); if (isElm(toks)) { const k = elmKey(toks); if (!t1.has(k)) t1.set(k, toks); } }
    agg.circuits++;
    for (const line of raw.split('\n')) {
      const a = line.trim().split(/\s+/); if (!isElm(a)) continue;
      const b = t1.get(elmKey(a)); if (!b) continue;
      agg.linesCompared++;
      // field 5 = flags: the importer legitimately normalizes flag bits, so start at 6
      for (let i = 6; i < Math.min(a.length, b.length); i++) {
        if (a[i] === '#' || b[i] === '#') break;
        const x = num(a[i]), y = num(b[i]); if (x === null || y === null) continue;
        const tol = 1e-9 * Math.max(Math.abs(x), Math.abs(y), 1e-300);
        if (Math.abs(x - y) > tol) {
          const flip = x !== 0 && y !== 0 && Math.sign(x) !== Math.sign(y) && Math.abs(Math.abs(x) - Math.abs(y)) <= tol;
          agg.lossy++; if (flip) agg.signFlips++;
          agg.byType[a[0]] = (agg.byType[a[0]] || 0) + 1;
          if (agg.samples.length < 400) agg.samples.push(`${name}: ${a[0]} field ${i}: ${a[i]} -> ${b[i]}${flip ? ' (SIGN)' : ''}`);
        }
      }
    }
  }
  fs.writeFileSync(path.join(OUT_DIR, 'textfidelity.json'), JSON.stringify(agg, null, 2));
  report('T.text_fidelity', agg.lossy === 0, { circuits: agg.circuits, linesCompared: agg.linesCompared, lossyFields: agg.lossy, signFlips: agg.signFlips, byType: agg.byType, sample: agg.samples.slice(0, 5) });
}

// Agent API documents (PL_AGA Phase 1, SP_AGA_02_02 / SP_AGA_05_01): listDocuments, createDocument
// in the background, activateDocument, closeDocument (unsaved, background, last document),
// unknown handle, invalid arguments, callAsync — and the visible tab never changes except on
// activateDocument.
async function scenarioAgentDocs(s) {
  const out = { checks: {} };
  const ck = (name, cond) => { out.checks[name] = !!cond; return !!cond; };
  const A = (op, args) => s.call('agentCall', op, args);
  const docsOf = (r) => (r && r.data && r.data.documents) || [];
  const code0 = (r) => (r && r.issues && r.issues[0] && r.issues[0].code) || null;
  const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
  await resetApp(s);
  const exMark = s.exceptions.length;

  // listDocuments returns a valid JSON string
  const raw = await s.call('agentRaw', 'listDocuments', '{}');
  let parsed = null; try { parsed = JSON.parse(raw.text); } catch (e) { /* checked below */ }
  ck('listJsonString', raw.type === 'string' && parsed && parsed.ok === true && Array.isArray(parsed.data.documents) && parsed.truncatedIssues === 0);
  const actives = docsOf(parsed).filter((d) => d.active);
  ck('oneActive', actives.length === 1 && /^d[1-9][0-9]*$/.test(actives[0].doc));
  const A0 = actives[0] && actives[0].doc;

  // The visible tab gets a circuit with sliders (lrc.txt)
  await s.call('loadExample', 'lrc.txt');
  await sleep(300);
  const vis0 = await s.call('visibleTab');
  out.visible = vis0;
  out.hintA0 = String(await s.call('exportText')).split('\n').filter((l) => l.startsWith('h ')).join('|');

  // createDocument in the background: new handle, active tab unchanged, listed active=false
  const c1 = await A('createDocument', {});
  const B = c1.data && c1.data.doc;
  await sleep(200);
  const l1 = await A('listDocuments', {});
  const bRec = docsOf(l1).find((d) => d.doc === B);
  ck('createOk', c1.ok && /^d[1-9][0-9]*$/.test(B) && B !== A0);
  ck('createListedInactive', bRec && bRec.active === false && bRec.elementCount === 0 && bRec.modified === false);
  ck('createActiveUnchanged', docsOf(l1).find((d) => d.active).doc === A0);
  const vis1 = await s.call('visibleTab');
  ck('createVisibleUnchanged', same({ ...vis0, tabCount: vis0.tabCount + 1 }, vis1));

  const c2 = await A('createDocument', { title: 'Agent X' });
  const C = c2.data && c2.data.doc;
  const l2 = await A('listDocuments', {});
  const cRec = docsOf(l2).find((d) => d.doc === C);
  ck('createTitled', c2.ok && cRec && cRec.title === 'Agent X' && cRec.active === false && C !== B);

  // Let the debounced session save (dumps every background document through DocumentScope) run
  await sleep(1300);
  ck('sessionSaveVisibleUnchanged', same({ ...vis0, tabCount: vis0.tabCount + 2 }, await s.call('visibleTab')));

  // Make B modified through the UI: activate it, load a circuit with sliders, delete an element
  const act = await A('activateDocument', { doc: B });
  const lAct = await A('listDocuments', {});
  ck('activateOk', act.ok && act.data.doc === B && docsOf(lAct).find((d) => d.active).doc === B);
  // Per-document hint: the blank tab B does not inherit A0's hint line (lrc.txt has one)
  const hintOf = (t) => String(t).split('\n').filter((l) => l.startsWith('h ')).join('|');
  const hintB = hintOf(await s.call('exportText'));
  ck('hintPerDocument', hintB === '' && out.hintA0 !== '');
  await s.call('importText', SLIDER_CIRCUIT);
  await s.call('focus');
  const ids = await s.call('ids');
  await s.call('select', ids[0], false);
  await s.key('Delete');
  await sleep(200);
  const lMod = await A('listDocuments', {});
  ck('bModified', (docsOf(lMod).find((d) => d.doc === B) || {}).modified === true);
  await A('activateDocument', { doc: A0 });
  await sleep(300);
  const visBack = await s.call('visibleTab');
  out.visibleBack = visBack;
  ck('backToA0', same({ ...vis0, tabCount: vis0.tabCount + 2 }, visBack) && hintOf(await s.call('exportText')) === out.hintA0);

  // closeDocument unsaved -> unsaved_changes, document still open
  const u = await A('closeDocument', { doc: B });
  ck('unsavedChanges', u.ok === false && code0(u) === 'unsaved_changes' && u.issues[0].severity === 'error' && docsOf(await A('listDocuments', {})).some((d) => d.doc === B));

  // closeDocument of a background document with discardChanges: ok, no replacement, no tab switch
  const k = await A('closeDocument', { doc: B, discardChanges: true });
  await sleep(200);
  const lK = await A('listDocuments', {});
  ck('closeBackgroundOk', k.ok && k.data.doc === B && k.data.replacement === undefined && !docsOf(lK).some((d) => d.doc === B) && docsOf(lK).find((d) => d.active).doc === A0);
  ck('closeVisibleUnchanged', same({ ...vis0, tabCount: vis0.tabCount + 1 }, await s.call('visibleTab')));

  // unknown handle -> unknown_document with the open handles as hint
  const unk = await A('closeDocument', { doc: 'd999' });
  ck('unknownDocument', unk.ok === false && code0(unk) === 'unknown_document' && unk.issues[0].hint.includes(A0));
  const unk2 = await A('activateDocument', { doc: B });
  ck('closedHandleUnknown', unk2.ok === false && code0(unk2) === 'unknown_document');

  // invalid arguments -> invalid_value naming the argument; nothing applied
  const nDocs = docsOf(await A('listDocuments', {})).length;
  const inv1 = await A('createDocument', { activate: 'yes' });
  const inv2 = await A('closeDocument', {});
  const inv3 = await A('noSuchOp', {});
  const inv4 = await s.call('agentRaw', 'listDocuments', '{not json');
  const inv4p = JSON.parse(inv4.text);
  const inv5 = await A('createDocument', { title: '' });
  ck('invalidValue', code0(inv1) === 'invalid_value' && inv1.issues[0].message.includes("'activate'")
    && code0(inv2) === 'invalid_value' && inv2.issues[0].message.includes("'doc'")
    && code0(inv3) === 'invalid_value' && inv3.issues[0].message.includes("'op'")
    && code0(inv4p) === 'invalid_value' && inv4p.issues[0].message.includes("'args'")
    && code0(inv5) === 'invalid_value' && inv5.issues[0].message.includes("'title'"));
  ck('invalidNothingApplied', docsOf(await A('listDocuments', {})).length === nDocs);
  ck('issueKey', inv1.issues[0].key === 'invalid_value|||');

  // callAsync on a synchronous contract calls back immediately with the JSON result
  const as = await s.call('agentCallAsync', 'listDocuments', {});
  ck('callAsync', as.sync === true && as.type === 'string' && as.result.ok === true);

  // Closing the last document with discardChanges: ok + replacement handle (new, never reused)
  for (const d of docsOf(await A('listDocuments', {}))) {
    if (d.doc !== A0) await A('closeDocument', { doc: d.doc, discardChanges: true });
  }
  const seen = [A0, B, C].map((h) => +h.slice(1));
  const last = await A('closeDocument', { doc: A0, discardChanges: true });
  await sleep(300);
  const lLast = await A('listDocuments', {});
  const rep = last.data && last.data.replacement;
  ck('lastReplacement', last.ok && last.data.doc === A0 && /^d[1-9][0-9]*$/.test(rep || '') && +rep.slice(1) > Math.max(...seen)
    && docsOf(lLast).length === 1 && docsOf(lLast)[0].doc === rep && docsOf(lLast)[0].active === true);
  ck('noPageExceptions', s.exceptions.length === exMark);

  out.samples = { unsaved: u.issues && u.issues[0], unknown: unk.issues && unk.issues[0], invalid: inv1.issues && inv1.issues[0], last: last.data };
  fs.writeFileSync(path.join(OUT_DIR, 'agent_docs.json'), JSON.stringify(out, null, 2));
  const failed = Object.keys(out.checks).filter((n) => !out.checks[n]);
  report('AG.agent_docs', failed.length === 0, { checks: Object.keys(out.checks).length, failed, replacement: rep, details: path.join(OUT_DIR, 'agent_docs.json') });
}

// [PL_AGA_P2] Element identity (SP_AGA_03_02 / SP_AGA_04_03) through existing user paths only.
const resistorJson = (keys) => JSON.stringify({
  schema: { format: 'circuitjs', version: '2.0' },
  elements: Object.fromEntries(keys.map((k, i) => [k, { type: 'Resistor', properties: { resistance: '1 kOhm' },
    pins: { pin1: { position: { x: 64 + 96 * i, y: 64 } }, pin2: { position: { x: 128 + 96 * i, y: 64 } } } }])),
});
async function scenarioAgentIds(s) {
  const out = { checks: {} };
  const ck = (name, cond) => { out.checks[name] = !!cond; return !!cond; };
  const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
  const unique = (a) => new Set(a).size === a.length;
  const jsonKeys = async () => Object.keys(JSON.parse(await s.call('exportJson')).elements || {});
  await resetApp(s);
  const exMark = s.exceptions.length;

  // (e) legacy text load: deterministic generated IDs in file order, same on reload
  await s.call('loadExample', 'lrc.txt');
  const ids0 = await s.call('ids');
  const perPrefixInOrder = (ids) => { const c = {}; return ids.every((id) => { const m = /^([A-Za-z]+)([0-9]+)$/.exec(id); if (!m) return false; c[m[1]] = (c[m[1]] || 0) + 1; return +m[2] === c[m[1]]; }); };
  ck('textIdsFileOrder', ids0.length > 0 && perPrefixInOrder(ids0) && unique(ids0));
  await s.call('loadExample', 'lrc.txt');
  ck('textReloadSameIds', same(ids0, await s.call('ids')));
  out.lrcIds = ids0;

  // (b) JSON export keys == CircuitJS1.getElementIds()
  ck('jsonKeysEqualIds', same(await jsonKeys(), ids0));

  // (a) user undo/redo with Ctrl+Z / Ctrl+Y keeps IDs
  await s.call('focus');
  const types0 = await s.call('types');
  const delIdx = Math.max(0, types0.findIndex((t) => !/WireElm/.test(t)));
  await s.call('select', ids0[delIdx], false);
  await s.key('Delete');
  const idsDel = await s.call('ids');
  ck('deleteRemovedOne', same(idsDel, ids0.filter((_, i) => i !== delIdx)));
  await s.key('KeyZ', { ctrl: true });
  ck('undoRestoresIds', same(await s.call('ids'), ids0));
  await s.key('KeyY', { ctrl: true });
  ck('redoRestoresIds', same(await s.call('ids'), idsDel));
  ck('jsonKeysAfterRedo', same(await jsonKeys(), idsDel));

  // (f) letters-only prefixes: types whose name contains digits (CC2, Timer555) get no digits
  await s.call('loadExample', 'cc2.txt');
  let ids = await s.call('ids'); let jt = await s.call('jsonTypes');
  const cc2 = ids.filter((_, i) => /^CC2/.test(jt[i]));
  await s.call('loadExample', '555saw.txt');
  const ids555 = await s.call('ids'); const jt555 = await s.call('jsonTypes');
  const tim = ids555.filter((_, i) => jt555[i] === 'Timer555');
  out.prefixSamples = { cc2, tim };
  ck('lettersOnlyPrefix', cc2.length > 0 && cc2.every((id) => /^CC[0-9]+$/.test(id)) && tim.length > 0 && tim.every((id) => /^TIM[0-9]+$/.test(id))
    && [...ids, ...ids555].every((id) => /^[A-Za-z]+[0-9]+$/.test(id)));
  ck('jsonKeysEqualIds555', same(await jsonKeys(), ids555));

  // (c) JSON import R1..R5 keeps keys; Ctrl+Z / Ctrl+Y restore them; a UI-placed resistor gets R6
  const logMark = await s.call('logCount');
  await s.call('importJson', resistorJson(['R1', 'R2', 'R3', 'R4', 'R5']));
  ck('jsonImportKeepsKeys', same(await s.call('ids'), ['R1', 'R2', 'R3', 'R4', 'R5']));
  await s.call('focus');
  await s.call('select', 'R5', false);
  await s.key('Delete');
  ck('jsonDeleteR5', same(await s.call('ids'), ['R1', 'R2', 'R3', 'R4']));
  await s.key('KeyZ', { ctrl: true });
  ck('jsonUndoR1R5', same(await s.call('ids'), ['R1', 'R2', 'R3', 'R4', 'R5']));
  await s.key('KeyY', { ctrl: true });
  ck('jsonRedoR1R4', same(await s.call('ids'), ['R1', 'R2', 'R3', 'R4']));
  await s.key('Escape');
  await s.typeChar('r');
  const cr = await s.call('canvasRect');
  const px = Math.round(cr.x + cr.w * 0.6), py = Math.round(cr.y + cr.h * 0.75);
  // A click without drag fails creation: it must not take a number from the counters
  await s.mouseDrag(px - 200, py, px - 200, py);
  ck('failedPlacementNoElement', (await s.call('ids')).length === 4);
  await s.mouseDrag(px, py, px + 96, py);
  await s.typeChar(' ');
  const idsPlaced = await s.call('ids');
  out.idsPlaced = idsPlaced;
  // R5 was retired by the delete: its number is never reissued in this content lifetime
  ck('placedResistorR6', idsPlaced.length === 5 && idsPlaced[4] === 'R6');
  await s.key('KeyZ', { ctrl: true });
  await s.key('KeyY', { ctrl: true });
  ck('placedUndoRedo', same(await s.call('ids'), idsPlaced));

  // Duplicate (Ctrl+A, Ctrl+D): generated IDs, all unique, originals unchanged
  await s.key('KeyA', { ctrl: true });
  await s.key('KeyD', { ctrl: true });
  const idsDup = await s.call('ids');
  out.idsDup = idsDup;
  ck('duplicateGenerated', idsDup.length === 10 && same(idsDup.slice(0, 5), idsPlaced) && same(idsDup.slice(5), ['R7', 'R8', 'R9', 'R10', 'R11']));

  // Supplied IDs raise counters before generation; invalid keys regenerated with ids_regenerated
  await s.call('importJson', resistorJson(['Rload', 'bad key', 'R7']));
  const idsMix = await s.call('ids');
  out.idsMix = idsMix;
  const logs = await s.call('logsSince', logMark);
  ck('suppliedRaiseFirst', same(idsMix, ['Rload', 'R8', 'R7']));
  ck('idsRegeneratedLogged', logs.some((l) => /ids_regenerated/.test(l) && /bad key/.test(l)));
  ck('jsonKeysEqualIdsMix', same(await jsonKeys(), idsMix));

  // Scripting global by registry ID: updateElementProperties (JSNI walk over elmList)
  const upd = await s.eval(`CircuitJS1.updateElementProperties('R7', { resistance: 470 })`);
  const r7line = String(await s.call('exportText')).split('\n').filter((l) => /^r /.test(l))[2] || '';
  out.updateElementProperties = { upd, r7line };
  ck('updateElementPropertiesById', upd === true && / 470$/.test(r7line));
  ck('noPageExceptions', s.exceptions.length === exMark);

  fs.writeFileSync(path.join(OUT_DIR, 'agent_ids.json'), JSON.stringify(out, null, 2));
  const failed = Object.keys(out.checks).filter((n) => !out.checks[n]);
  report('AG.agent_ids', failed.length === 0, { checks: Object.keys(out.checks).length, failed, details: path.join(OUT_DIR, 'agent_ids.json') });
}

// Menu label texts for a menu key in every UI language: the English key and its translations
// in the bundled locale files (markup and &nbsp; removed).
function menuTexts(key) {
  const clean = (t) => t.replace(/<[^>]*>/g, '').replace(/&nbsp;/g, ' ').replace(/\s+/g, ' ').trim();
  const out = new Set([clean(key)]);
  const dir = path.join(SITE_DIR, 'circuitjs1');
  for (const f of fs.readdirSync(dir).filter((x) => /^locale_.*\.txt$/.test(x))) {
    for (const line of fs.readFileSync(path.join(dir, f), 'utf8').split('\n')) {
      const m = /^"(.*)"="(.*)"\s*$/.exec(line);
      if (m && m[1] === key) out.add(clean(m[2]));
    }
  }
  return [...out];
}

// [PL_AGA_P3] Catalogue (SP_AGA_02_01 / SP_AGA_01_05): SP_AGA_05_01 rows listTypes/describeType,
// SP_AGA_05_02 "Pin names unique", the type count against the factory, the first-build time, and
// a first build that leaves the active document unchanged.
async function scenarioAgentCatalogue(s) {
  const out = { checks: {} };
  const ck = (name, cond) => { out.checks[name] = !!cond; return !!cond; };
  const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
  const unique = (a) => new Set(a).size === a.length;
  const A = (op, args) => s.call('agentCall', op, args);
  const OPTS16 = '$ 1 0.000005 10.20027730826997 50 5 50 5e-11';
  const OPTS8 = '$ 3 0.000005 10.20027730826997 50 5 50 5e-11'; // bit 2: Small Grid
  const elmLines = (code) => String(s.lastText || '').split('\n').filter((l) => l.split(' ')[0] === code);
  const exportLines = async (code) => { s.lastText = await s.call('exportText'); return elmLines(code); };
  await resetApp(s);
  const exMark = s.exceptions.length;
  const cr = await s.call('canvasRect');
  const px = Math.round(cr.x + cr.w * 0.45), py = Math.round(cr.y + cr.h * 0.6);

  // --- Before the first build, on a 16-grid document: user-like actions
  await s.call('importText', OPTS16 + '\n');
  await s.call('focus');
  // (1) A user edit changes a remembered last-used value: an AND gate placed with "2", opened by
  // double click, its first checkbox (Schmitt Inputs) ticked -> GateElm.lastSchmitt = true
  await s.key('Escape');
  await s.typeChar('2');
  await s.mouseDrag(px, py, px + 96, py);
  await s.mouseDoubleClick(px + 48, py);
  const ticked = await s.call('clickDialogCheckbox', 0);
  await sleep(200);
  const dialogBefore = await s.call('editDialogText');
  await s.call('closeDialogs'); await s.key('Escape');
  const gateJson = JSON.parse(await s.call('exportJson'));
  const gate0 = Object.values(gateJson.elements || {}).find((e) => /AND/i.test(e.type));
  out.userSchmitt = { ticked, gate: gate0 && gate0.properties };
  ck('userEditSetsSchmitt', ticked && gate0 && gate0.properties && gate0.properties.schmitt === true);
  // (2) An editor placement of a tapped transformer (fixed size on creation) through the menu
  await s.call('importText', OPTS16 + '\n');
  const menuOk = await s.call('clickMenuPath', [menuTexts('Draw'), menuTexts('&nbsp;</div>Passive Components'), menuTexts('Add Tapped Transformer')]);
  await s.call('focus');
  await s.mouseDrag(px, py, px + 96, py);
  await s.key('Escape');
  const tt = (await exportLines('169'))[0];
  const ttTok = tt ? tt.split(' ').map(Number) : null;
  out.tappedPlacement = { menuOk, line: tt };
  const editorTapped = ttTok ? { dx: (ttTok[3] - ttTok[1]) / 16, dy: (ttTok[4] - ttTok[2]) / 16 } : null;

  // --- Active document before the first build: Small Grid, R1..R3
  await s.call('importText', [OPTS8, 'r 64 64 128 64 0 1000', 'r 64 128 128 128 0 1000', 'r 64 192 128 192 0 1000', ''].join('\n'));
  await sleep(200);
  const snap = async () => ({ ids: await s.call('ids'), text: await s.call('exportText'), json: await s.call('exportJson'), visible: await s.call('visibleTab'), docs: (await A('listDocuments', {})).data });
  const before = await snap();
  const logMark = await s.call('logCount');

  // First call builds the catalogue (timed in the page), the second one reads the cache
  const timed = (op, args) => s.eval(`(() => { const t0 = performance.now(); const r = window.__H.agentCall(${JSON.stringify(op)}, ${JSON.stringify(args)}); return { ms: performance.now() - t0, r }; })()`);
  const first = await timed('listTypes', {});
  const second = await timed('listTypes', {});
  out.timing = { firstBuildMs: Math.round(first.ms * 10) / 10, cachedMs: Math.round(second.ms * 10) / 10 };
  const after = await snap();
  out.buildLogLines = await s.call('logsSince', logMark);
  ck('buildLeavesIds', same(before.ids, after.ids));
  ck('buildLeavesCircuit', before.text === after.text && before.json === after.json);
  ck('buildLeavesVisibleTab', same(before.visible, after.visible));
  // the scratch document is never listed and takes no handle: the next document gets the next number
  ck('buildLeavesDocuments', same(before.docs, after.docs));
  if (before.text !== after.text) out.textDiff = lineDiff(before.text.split('\n'), after.text.split('\n'));
  if (!same(before.visible, after.visible)) { const fb = flatten(before.visible), fa = flatten(after.visible); out.visibleDiff = Object.keys({ ...fb, ...fa }).filter((k) => !same(fb[k], fa[k])).map((k) => ({ key: k, before: fb[k], after: fa[k] })); }

  const types = (first.r.data && first.r.data.types) || [];
  const names = types.map((t) => t.type);
  out.typeCount = names.length;
  ck('listOk', first.r.ok === true && names.length > 0 && same(first.r, second.r));
  ck('listSortedUnique', unique(names) && same(names, [...names].sort((a, b) => (a < b ? -1 : a > b ? 1 : 0))));
  ck('indexForm', types.every((t) => typeof t.type === 'string' && Array.isArray(t.aliases) && Array.isArray(t.pins)
    && ['single', 'two_point', 'derived'].includes(t.geometry) && typeof t.summary === 'string' && t.summary.length > 0));
  const byType = Object.fromEntries(types.map((t) => [t.type, t]));
  out.summaries = { Resistor: byType.Resistor && byType.Resistor.summary, TappedTransformer: byType.TappedTransformer && byType.TappedTransformer.summary };
  // English summaries whatever the UI language (the harness browser may run another locale)
  ck('summaryEnglish', byType.Resistor && byType.Resistor.summary === 'Add Resistor' && types.every((t) => /^[\x20-\x7eµΩμ]*$/.test(t.summary)));

  // listTypes filter "mosfet": NMOS and PMOS (through their aliases), each type once
  const mos = await A('listTypes', { filter: 'mosfet' });
  const mosNames = (mos.data && mos.data.types || []).map((t) => t.type);
  out.mosfet = mosNames;
  ck('filterMosfet', mos.ok && mosNames.filter((n) => n === 'NMOS').length === 1 && mosNames.filter((n) => n === 'PMOS').length === 1 && unique(mosNames));
  const mosUpper = await A('listTypes', { filter: 'MOSFET' });
  ck('filterCaseInsensitive', mosUpper.ok && same(mosUpper.data.types, mos.data.types));

  // describeType rows
  const D = async (type) => A('describeType', { type });
  const prop = (info, key) => info && info.properties && info.properties.find((p) => p.key === key);
  const res = await D('Resistor');
  const rp = prop(res.data, 'resistance');
  out.resistor = res.data;
  ck('describeResistor', res.ok && res.data.geometry === 'two_point' && res.data.pins.length === 2 && rp && rp.kind === 'quantity' && rp.unit === 'Ohm');
  ck('labelEnglishPlain', rp && rp.label === 'Resistance (ohms)');
  const npn = await D('TransistorNPN');
  out.npn = npn.ok ? { geometry: npn.data.geometry, pins: npn.data.pins, derivedPostsAtDefault: npn.data.derivedPostsAtDefault } : npn;
  ck('describeNPN', npn.ok && npn.data.geometry === 'derived' && npn.data.pins.length === 3 && Object.keys(npn.data.derivedPostsAtDefault || {}).length === 3);
  const cap = await D('Capacitor');
  out.capacitor = cap.ok ? cap.data.properties : cap;
  const pv = (k) => { const p = prop(cap.data, k); return p ? [p.kind, p.default, p.unit || null] : null; };
  ck('describeCapacitorConditional', cap.ok && same(pv('capacitance'), ['quantity', '10 uF', 'F']) && same(pv('initial_voltage'), ['quantity', '1 mV', 'V'])
    && same(pv('series_resistance'), ['quantity', '1 mOhm', 'Ohm']) && same(pv('back_euler'), ['bool', false, null]));
  const dff = await D('DFlipFlop');
  out.dffPins = dff.ok ? dff.data.pins : dff;
  ck('describeFlipFlopPins', dff.ok && unique(dff.data.pins) && dff.data.pins.includes('Q') && dff.data.pins.indexOf('Q_2') > dff.data.pins.indexOf('Q'));
  const zen = await D('Zener');
  ck('describeAlias', zen.ok && zen.data.type === 'ZenerDiode' && zen.data.aliases.includes('Zener'));
  const unk = await D('Resistr');
  out.unknown = unk.issues;
  ck('describeUnknown', unk.ok === false && unk.issues[0].code === 'unknown_type' && /\bResistor\b/.test(unk.issues[0].hint));
  const noArg = await A('describeType', {});
  ck('describeMissingType', noArg.ok === false && noArg.issues[0].code === 'invalid_value');
  const trf = await D('Transformer'), ldr = await D('LDR'), cc2 = await D('CC2Neg'), din = await D('DataInput'), sw2 = await D('SPDTSwitch');
  const ro = (r, k) => !!(r.ok && prop(r.data, k) && prop(r.data, k).readOnly === true);
  out.readOnly = { trf: [ro(trf, 'vertical'), ro(trf, 'flip'), ro(trf, 'ratio')], ldr: [ro(ldr, 'lux'), ro(ldr, 'resistance'), ro(ldr, 'position')], cc2Neg: ro(cc2, 'type'), repeat: ro(din, 'repeat'), centerOff: ro(sw2, 'center_off') };
  ck('readOnlyKeys', same(out.readOnly, { trf: [true, true, false], ldr: [true, true, false], cc2Neg: true, repeat: false, centerOff: false }));
  // built-in defaults: the user's Schmitt choice above does not reach the catalogue
  const and = await D('AndGate');
  out.andGate = and.ok ? { schmitt: prop(and.data, 'schmitt'), defaultFlags: and.data.defaultFlags } : and;
  ck('defaultsBuiltIn', and.ok && prop(and.data, 'schmitt') && prop(and.data, 'schmitt').default === false);
  // fixed-size element: defaultSize = the editor placement
  const tap = await D('TappedTransformer');
  out.tappedPlacement.catalogue = tap.ok ? tap.data.defaultSize : tap;
  out.tappedPlacement.editor = editorTapped;
  ck('tappedSizeEqualsEditor', menuOk === 3 && editorTapped && tap.ok && same(tap.data.defaultSize, editorTapped));

  // Every type: describeType works, aliases resolve to it, pins unique (SP_AGA_05_02), index = TypeInfo,
  // labels one-to-one and without markup
  const dupPins = [], badDescribe = [], badAlias = [], dupLabels = [], markup = [], badSlider = [];
  const kinds = {};
  for (const t of types) {
    const d = await D(t.type);
    if (!d.ok || d.data.type !== t.type || !same(d.data.pins, t.pins) || !same(d.data.aliases, t.aliases) || d.data.geometry !== t.geometry) { badDescribe.push(t.type); continue; }
    if (!unique(d.data.pins)) dupPins.push(t.type);
    const labels = d.data.properties.filter((p) => p.label).map((p) => p.label);
    if (!unique(labels)) dupLabels.push(t.type);
    for (const p of d.data.properties) {
      kinds[p.kind] = (kinds[p.kind] || 0) + 1;
      if (p.label && /[<>]|&[a-z]+;/.test(p.label)) markup.push(`${t.type}.${p.key}`);
      if (p.sliderMin !== undefined && (p.sliderMin === p.sliderMax || p.kind === 'bool')) badSlider.push(`${t.type}.${p.key}`);
      if (p.kind === 'bool' && p.label) badSlider.push(`${t.type}.${p.key}(bool label)`);
    }
    for (const a of t.aliases) { const da = await D(a); if (!da.ok || da.data.type !== t.type) badAlias.push(a); }
  }
  out.propertyKinds = kinds;
  ck('pinNamesUnique', dupPins.length === 0);
  ck('describeEveryType', badDescribe.length === 0);
  ck('aliasesResolve', badAlias.length === 0);
  ck('labelsOneToOne', dupLabels.length === 0);
  ck('labelsWithoutMarkup', markup.length === 0);
  ck('sliderSeedsValid', badSlider.length === 0);
  Object.assign(out, { dupPins, badDescribe, badAlias, dupLabels, markup, badSlider });

  // After the build: the user's last-used value is back, the ID counters and the Small Grid are untouched.
  // Resistors placed by the user get R4.. on the 8 px grid.
  await s.call('focus');
  await s.key('Escape');
  const placed = [];
  for (const [k, len] of [[0, 104], [1, 120], [2, 88]]) {
    await s.typeChar('r');
    await s.mouseDrag(px + 8 * k, py + 40 * k, px + 8 * k + len, py + 40 * k);
    await s.key('Escape');
  }
  const rl = await exportLines('r');
  const coords = rl.slice(3).flatMap((l) => l.split(' ').slice(1, 5).map(Number));
  out.placedResistors = rl.slice(3);
  out.idsPlaced = await s.call('ids');
  ck('nextIdAfterBuild', same(out.idsPlaced, ['R1', 'R2', 'R3', 'R4', 'R5', 'R6']));
  ck('smallGridKept', String(s.lastText).split('\n')[0].split(' ')[1] === '3' && coords.length === 12 && coords.every((c) => c % 8 === 0) && coords.some((c) => c % 16 !== 0));
  await s.typeChar('2');
  await s.mouseDrag(px, py + 200, px + 96, py + 200);
  await s.key('Escape');
  const gj = JSON.parse(await s.call('exportJson'));
  const gate1 = Object.values(gj.elements || {}).find((e) => /AND/i.test(e.type));
  out.gateAfterBuild = gate1 && gate1.properties;
  ck('lastUsedRestored', gate1 && gate1.properties.schmitt === true);
  // UI translation still works after the build (translation is suspended only while it runs):
  // the same element's edit dialog reads the same as before the build
  await s.mouseDoubleClick(px + 48, py + 200);
  const dialogAfter = await s.call('editDialogText');
  await s.call('closeDialogs'); await s.key('Escape');
  out.editDialog = { before: dialogBefore, after: dialogAfter };
  // translated = the UI runs a non-English locale (the headless browser here: uk)
  out.editDialog.translated = !!dialogBefore && !dialogBefore.includes('Schmitt Inputs');
  ck('translationKept', !!dialogBefore && dialogBefore === dialogAfter);

  // Flag-backed options set from their key and kept by a JSON round trip (RULE_ARCH_010)
  const optJson = { schema: { format: 'circuitjs', version: '2.0' }, elements: {
    DI1: { type: 'DataInput', _flags: 0, properties: { repeat: true }, pins: { _startpoint: { position: { x: 64, y: 64 } }, _endpoint: { position: { x: 128, y: 64 } } } },
    SW1: { type: 'SPDTSwitch', _flags: 0, properties: { center_off: true }, pins: { common: { position: { x: 64, y: 160 } }, throw1: { position: { x: 128, y: 144 } } } } } };
  await s.call('importJson', JSON.stringify(optJson));
  const j1 = JSON.parse(await s.call('exportJson'));
  const t1 = await s.call('exportText');
  await s.call('importJson', JSON.stringify(j1));
  const j2 = JSON.parse(await s.call('exportJson'));
  const t2 = await s.call('exportText');
  const pp = (j, id) => j.elements && j.elements[id] && j.elements[id].properties;
  out.flagOptions = { DI1: pp(j1, 'DI1') && pp(j1, 'DI1').repeat, SW1: pp(j1, 'SW1') && pp(j1, 'SW1').center_off, textEqual: t1 === t2 };
  ck('flagOptionsFromKeys', out.flagOptions.DI1 === true && out.flagOptions.SW1 === true && same(pp(j1, 'DI1'), pp(j2, 'DI1')) && same(pp(j1, 'SW1'), pp(j2, 'SW1')) && t1 === t2);

  // Type count = distinct canonical JSON type names the factory produces: every factory key of
  // the build (synth's source) imported through JSON, canonical name = the created element's type
  const keys = buildTypeNames();
  const allJson = { schema: { format: 'circuitjs', version: '2.0' }, elements: Object.fromEntries(keys.map((k, i) => [`X${i + 1}`, { type: k, p1: { x: 64 + 96 * (i % 12), y: 64 + 96 * Math.floor(i / 12) }, p2: { x: 128 + 96 * (i % 12), y: 64 + 96 * Math.floor(i / 12) } }])) };
  await s.call('importJson', JSON.stringify(allJson));
  const produced = [...new Set(await s.call('jsonTypes'))].sort();
  const exportKeys = new Set(keys);
  out.reference = { factoryKeys: keys.length, produced: produced.length, notInCatalogue: produced.filter((n) => !names.includes(n)), notProduced: names.filter((n) => !produced.includes(n)) };
  ck('typeCountEqualsFactory', names.length === produced.length && same(names, produced));
  ck('typesAreFactoryKeys', names.every((n) => exportKeys.has(n)) && types.every((t) => t.aliases.every((a) => exportKeys.has(a))));
  ck('noPageExceptions', s.exceptions.length === exMark);

  fs.writeFileSync(path.join(OUT_DIR, 'agent_catalogue.json'), JSON.stringify(out, null, 2));
  const failed = Object.keys(out.checks).filter((n) => !out.checks[n]);
  report('AG.agent_catalogue', failed.length === 0, { checks: Object.keys(out.checks).length, failed, types: names.length, firstBuildMs: out.timing.firstBuildMs, cachedMs: out.timing.cachedMs, details: path.join(OUT_DIR, 'agent_catalogue.json') });
}

// [PL_AGA_P4] Geometry, edits and import (SP_AGA_02_03/04/05, 02_14 exportCircuit, 03_01-03_04,
// 03_10, 04_01 agent origin). Most operations run on a background document, each followed by an
// R1-style check that the visible tab is unchanged.
const RC_CELLS = [
  { type: 'DCVoltage', start: { x: 0, y: 4 }, end: { x: 0, y: 0 }, properties: { max_voltage: '10 V' } },
  { id: 'R1', type: 'Resistor', start: { x: 0, y: 0 }, end: { x: 4, y: 0 }, properties: { resistance: '1k' } },
  { type: 'Resistor', start: { x: 4, y: 0 }, end: { x: 4, y: 4 }, properties: { resistance: 2000 } },
  { type: 'Wire', start: { x: 4, y: 4 }, end: { x: 0, y: 4 } },
  { type: 'Ground', start: { x: 0, y: 4 }, end: { x: 0, y: 5 } },
];
// Legacy text with odd-pixel coordinates (an example drawn off the half-cell lattice)
const ODD_TEXT = '$ 1 0.000005 10.20027730826997 50 5 50 5e-11\n' +
  'v 101 213 101 77 0 0 40 5 0 0 0.5\n' +
  'r 101 77 213 77 0 1000\n' +
  'c 213 77 213 213 0 0.00001 0.001 0.001\n' +
  'w 213 213 101 213 0\n' +
  'g 101 213 101 229 0 0\n';
async function scenarioAgentEdit(s) {
  const out = { checks: {}, notes: {} };
  const ck = (name, cond) => { out.checks[name] = !!cond; return !!cond; };
  const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
  const A = (op, args) => s.call('agentCall', op, args);
  const codes = (r) => ((r && r.issues) || []).map((i) => i.code);
  const has = (r, code) => codes(r).includes(code);
  const recs = (r) => (r && r.data && r.data.elements) || [];
  const rec = (r, id) => recs(r).find((e) => e.id === id);
  const docState = (doc) => s.eval(`JSON.parse(CircuitJS1Agent.debugDocState(${JSON.stringify(doc)}))`);
  // circuit text, IDs and open marks of a document (SP_AGA_05_02 "ok=false => document unchanged")
  const state = async (doc) => {
    const t = await A('exportCircuit', { doc, format: 'text' });
    const g = await A('getCircuit', { doc, detail: 'full', limit: 500 });
    const d = await docState(doc);
    return { text: t.data && t.data.content, ids: recs(g).map((e) => e.id), marks: d.openMarks, undo: d.undo, redo: d.redo };
  };
  await resetApp(s);
  const exMark = s.exceptions.length;

  // Visible tab: lrc.txt with sliders and a hint; background documents B (main), G16, G8
  await s.call('loadExample', 'lrc.txt');
  await sleep(300);
  const vis0 = await s.call('visibleTab');
  const B = (await A('createDocument', { title: 'Agent B' })).data.doc;
  const A0 = (await A('listDocuments', {})).data.documents.find((d) => d.active).doc;
  const visBase = { ...vis0, tabCount: vis0.tabCount + 1 };
  const r1 = { failed: [] };
  const visibleSame = async (label) => {
    const v = await s.call('visibleTab');
    if (!same(v, visBase)) r1.failed.push({ label, v });
  };

  // --- importCircuit: RC divider in cells (AgentCircuit), supplied and generated IDs
  const imp = await A('importCircuit', { doc: B, circuit: { elements: RC_CELLS } });
  await visibleSame('importAgentCircuit');
  out.notes.importIds = imp.data && imp.data.ids;
  ck('importAgentCircuitOk', imp.ok && same(imp.data.ids, ['V1', 'R1', 'R2', 'W1', 'GND1']) && imp.data.elements === 5);
  const dB0 = await docState(B);
  // [PL_AGA_P6] the import opens the agent transaction: one entry (the pre-import state)
  ck('importOneUndoEntry', dB0.undo === 1 && dB0.redo === 0 && dB0.modified === true && dB0.agentOrigin === false
    && same(imp.transaction, { open: true, pendingEdits: 1 }));

  // --- getCircuit: ordering, cells, posts, concise vs full
  const gc = await A('getCircuit', { doc: B });
  const gcf = await A('getCircuit', { doc: B, detail: 'full' });
  const r2 = rec(gcf, 'R2');
  ck('getCircuitOrdered', gc.ok && same(recs(gc).map((e) => e.id), ['GND1', 'R1', 'R2', 'V1', 'W1']) && gc.data.total === 5 && gc.data.nextOffset === undefined);
  ck('getCircuitRecord', r2 && same(r2.start, { x: 4, y: 0 }) && same(r2.end, { x: 4, y: 4 }) && r2.posts.length === 2
    && r2.posts[1].pin === 'pin2' && same(r2.posts[1].at, { x: 4, y: 4 }) && r2.posts[1].open === false
    && r2.properties.resistance === '2 kOhm' && typeof r2.flags === 'number');
  ck('getCircuitConcise', rec(gc, 'R1') && rec(gc, 'R1').properties.resistance === undefined && rec(gc, 'R1').flags === undefined
    && rec(gc, 'R2').properties.resistance === '2 kOhm' && rec(gc, 'V1').properties.max_voltage === '10 V');
  const page = await A('getCircuit', { doc: B, offset: 1, limit: 2 });
  ck('getCircuitPaging', same(recs(page).map((e) => e.id), ['R1', 'R2']) && page.data.nextOffset === 3 && page.data.total === 5);
  const sub = await A('getCircuit', { doc: B, ids: ['W1', 'R1'] });
  const subBad = await A('getCircuit', { doc: B, ids: ['R1', 'Nope'] });
  ck('getCircuitIds', same(recs(sub).map((e) => e.id), ['R1', 'W1']) && subBad.ok === false && has(subBad, 'unknown_element'));
  ck('getCircuitSimulation', gc.data.simulation && gc.data.simulation.time_step === '5 us' && Array.isArray(gc.data.scopes));
  await visibleSame('getCircuit');

  // --- exportCircuit json keys = getCircuit IDs (background document)
  const ex = await A('exportCircuit', { doc: B });
  const exKeys = Object.keys(JSON.parse(ex.data.content).elements);
  ck('exportJsonKeys', ex.ok && same([...exKeys].sort(), recs(gcf).map((e) => e.id).sort()));
  const exText = await A('exportCircuit', { doc: B, format: 'text' });
  ck('exportText', exText.ok && /^\$ /.test(exText.data.content) && exText.data.content.split('\n').filter((l) => /^r /.test(l)).length === 2);
  await visibleSame('exportCircuit');

  // --- importCircuit round trip: getCircuit(full) re-imported -> identical records
  const rt0 = await A('getCircuit', { doc: B, detail: 'full' });
  const rtImp = await A('importCircuit', { doc: B, circuit: { elements: recs(rt0), simulation: rt0.data.simulation, scopes: rt0.data.scopes } });
  const rt1 = await A('getCircuit', { doc: B, detail: 'full' });
  ck('importRoundTrip', rtImp.ok && same(recs(rt0), recs(rt1)) && same(rt0.data.simulation, rt1.data.simulation));
  await visibleSame('importRoundTrip');

  // --- ok=false => document unchanged, for each rejected import
  const rejections = {};
  const rejectCase = async (name, args, code) => {
    const before = await state(args.doc);
    const r = await A(args.op, args.args);
    const after = await state(args.doc);
    rejections[name] = { ok: r.ok, codes: codes(r), unchanged: same(before, after) };
    if (!(r.ok === false && has(r, code) && same(before, after))) rejections[name].before = before, rejections[name].after = after;
    await visibleSame(name);
    return r.ok === false && has(r, code) && same(before, after);
  };
  const offLat = RC_CELLS.map((e, i) => (i === 1 ? { ...e, start: { x: 3.3, y: 0 } } : e));
  ck('importOffLattice', await rejectCase('importOffLattice', { doc: B, op: 'importCircuit', args: { doc: B, circuit: { elements: offLat } } }, 'off_lattice'));
  const broken = '$ 1 0.000005 10.2 50 5 50 5e-11\nr 64 64 128 64 0 1000\nqqq 0 0 16 16 0\nr 128 64 192 64 0 1000\n';
  ck('importBrokenText', await rejectCase('importBrokenText', { doc: B, op: 'importCircuit', args: { doc: B, circuit: broken } }, 'import_element_skipped'));
  const brokenJson = JSON.stringify({ schema: { format: 'circuitjs', version: '2.0' }, elements: { R1: { type: 'NoSuchType', pins: {} } } });
  ck('importJsonUnknownType', await rejectCase('importJsonUnknownType', { doc: B, op: 'importCircuit', args: { doc: B, circuit: brokenJson } }, 'import_element_skipped'));
  const fracJson = JSON.stringify({ schema: { format: 'circuitjs', version: '2.0' }, elements: { R1: { type: 'Resistor', pins: { pin1: { position: { x: 0.5, y: 0 } }, pin2: { position: { x: 64, y: 0 } } } } } });
  ck('importJsonFractionalPixel', await rejectCase('importJsonFractionalPixel', { doc: B, op: 'importCircuit', args: { doc: B, circuit: fracJson } }, 'off_lattice'));
  ck('importJsonSchema', await rejectCase('importJsonSchema', { doc: B, op: 'importCircuit', args: { doc: B, circuit: '{"elements": {}}' } }, 'import_schema_invalid'));
  ck('importDuplicateId', await rejectCase('importDuplicateId', { doc: B, op: 'importCircuit', args: { doc: B, circuit: { elements: [RC_CELLS[1], RC_CELLS[1]] } } }, 'id_taken'));
  ck('importUnknownProperty', await rejectCase('importUnknownProperty', { doc: B, op: 'importCircuit', args: { doc: B, circuit: { elements: [{ ...RC_CELLS[1], properties: { resistanse: 5 } }] } } }, 'unknown_property'));

  // Model catalogue: a rejected text import that redefines a diode model leaves the model unchanged
  const C = (await A('createDocument', {})).data.doc;
  const modelLine = '34 agentModel 0 1e-14 0 1 0 0';
  await A('importCircuit', { doc: C, circuit: '$ 1 0.000005 10.2 50 5 50 5e-11\n' + modelLine + '\nd 64 64 128 64 2 agentModel\n' });
  const cText0 = (await A('exportCircuit', { doc: C, format: 'text' })).data.content;
  const mRej = await A('importCircuit', { doc: B, circuit: '$ 1 0.000005 10.2 50 5 50 5e-11\n34 agentModel 0 5e-9 3 2 0 0\nd 64 64 128 64 2 agentModel\nqqq 1 2 3 4 0\n' });
  const cText1 = (await A('exportCircuit', { doc: C, format: 'text' })).data.content;
  out.notes.model = { c0: cText0.split('\n').filter((l) => l.startsWith('34 ')), c1: cText1.split('\n').filter((l) => l.startsWith('34 ')) };
  ck('rejectedImportRestoresModel', mRej.ok === false && has(mRej, 'import_element_skipped') && cText0 === cText1 && cText0.includes('1e-14'));
  await A('closeDocument', { doc: C, discardChanges: true });

  // --- legacy round trip + legacy coordinates off lattice (SP_AGA_05_04)
  const lg = await A('importCircuit', { doc: B, circuit: ODD_TEXT });
  const lg0 = await A('getCircuit', { doc: B, detail: 'full' });
  const r1rec = rec(lg0, 'R1');
  ck('legacyCellsFractional', lg.ok && r1rec && same(r1rec.start, { x: 101 / 16, y: 77 / 16 }) && same(r1rec.end, { x: 213 / 16, y: 77 / 16 }));
  const lgRt = await A('importCircuit', { doc: B, circuit: { elements: recs(lg0), simulation: lg0.data.simulation } });
  const lg1 = await A('getCircuit', { doc: B, detail: 'full' });
  ck('legacyRoundTrip', lgRt.ok && same(recs(lg0), recs(lg1)));
  const mvOff = await A('applyEdits', { doc: B, edits: [{ op: 'move', id: 'R1', by: { dx: 0.3, dy: 0 } }] });
  const mvHalf = await A('applyEdits', { doc: B, edits: [{ op: 'move', id: 'R1', by: { dx: 0.5, dy: 0 } }] });
  const mvRec = rec(mvHalf, 'R1');
  ck('legacyMoveOnLattice', mvOff.ok === false && has(mvOff, 'off_lattice') && mvHalf.ok && mvRec && mvRec.start.x === 101 / 16 + 0.5 && mvRec.end.x === 213 / 16 + 0.5);
  await visibleSame('legacy');

  // --- applyEdits on the RC divider
  await A('importCircuit', { doc: B, circuit: { elements: RC_CELLS } });
  // add + set in one batch
  const as = await A('applyEdits', { doc: B, edits: [
    { op: 'add', element: { id: 'R_load', type: 'Resistor', start: { x: 4, y: 0 }, end: { x: 8, y: 0 } } },
    { op: 'set', id: 'R_load', properties: { resistance: '4.7k' } }] });
  ck('addSetBatch', as.ok && as.data.applied === 2 && same(as.data.created, ['R_load']) && rec(as, 'R_load') && rec(as, 'R_load').properties.resistance === '4.7 kOhm' && as.data.truncated === 0);
  await visibleSame('addSet');
  // add applies TypeInfo defaults; a generated ID follows the counters
  const ad = await A('applyEdits', { doc: B, edits: [{ op: 'add', element: { type: 'Capacitor', start: { x: 8, y: 0 }, end: { x: 8, y: 4 }, properties: { series_resistance: 1 } } }] });
  const cid = ad.data && ad.data.created[0];
  const cRec = rec(ad, cid);
  ck('addDefaults', ad.ok && cid === 'C1' && cRec.properties.capacitance === '10 uF' && cRec.properties.series_resistance === '1 Ohm' && cRec.properties.back_euler === false);
  // partial set keeps others; back_euler can be set and cleared again
  const ps = await A('applyEdits', { doc: B, edits: [{ op: 'set', id: cid, properties: { capacitance: '22u' } }] });
  const be1 = await A('applyEdits', { doc: B, edits: [{ op: 'set', id: cid, properties: { back_euler: true } }] });
  const be0 = await A('applyEdits', { doc: B, edits: [{ op: 'set', id: cid, properties: { back_euler: false } }] });
  ck('partialSetKeepsOthers', ps.ok && rec(ps, cid).properties.series_resistance === '1 Ohm' && rec(ps, cid).properties.capacitance === '22 uF' && codes(ps).length === 0);
  ck('setBoolRoundTrip', be1.ok && rec(be1, cid).properties.back_euler === true && be0.ok && rec(be0, cid).properties.back_euler === false
    && rec(be0, cid).properties.series_resistance === '1 Ohm' && codes(be1).length === 0 && codes(be0).length === 0);
  // unit strings: Ω accepted; a wrong unit, an empty string and junk are invalid_value
  const om = await A('applyEdits', { doc: B, edits: [{ op: 'set', id: 'R2', properties: { resistance: '3.3 kΩ' } }] });
  ck('ohmSymbol', om.ok && rec(om, 'R2').properties.resistance === '3.3 kOhm');
  // set changing the canonical type keeps the ID
  const sw = await A('applyEdits', { doc: B, edits: [{ op: 'add', element: { id: 'SW5', type: 'Switch', start: { x: 12, y: 0 }, end: { x: 16, y: 0 } } },
    { op: 'set', id: 'SW5', properties: { momentary: true } }] });
  ck('setTypeChangeKeepsId', sw.ok && rec(sw, 'SW5') && rec(sw, 'SW5').type === 'PushSwitch');
  // value_adjusted: an element clamps a value (transformer coupling must be < 1)
  const tr = await A('applyEdits', { doc: B, edits: [{ op: 'add', element: { id: 'T9', type: 'Transformer', start: { x: 20, y: 0 } } },
    { op: 'set', id: 'T9', properties: { coupling: 2 } }] });
  const adj = (tr.issues || []).find((i) => i.code === 'value_adjusted');
  out.notes.valueAdjusted = adj;
  ck('valueAdjusted', tr.ok && adj && adj.severity === 'warning' && adj.elements[0] === 'T9' && /coupling/.test(adj.message));
  // move by
  const mb = await A('applyEdits', { doc: B, edits: [{ op: 'move', id: 'R_load', by: { dx: 2, dy: 0 } }] });
  ck('moveBy', mb.ok && same(rec(mb, 'R_load').start, { x: 6, y: 0 }) && same(rec(mb, 'R_load').end, { x: 10, y: 0 }));
  const ms = await A('applyEdits', { doc: B, edits: [{ op: 'move', id: 'R_load', start: { x: 4, y: 1 } }, { op: 'move', id: 'SW5', start: { x: 12, y: 2 }, end: { x: 12, y: 6 } }] });
  ck('moveStartEnd', ms.ok && same(rec(ms, 'R_load').start, { x: 4, y: 1 }) && same(rec(ms, 'R_load').end, { x: 8, y: 1 })
    && same(rec(ms, 'SW5').end, { x: 12, y: 6 }) && same(rec(ms, 'SW5').posts[1].at, { x: 12, y: 6 }));
  // describe
  const ds = await A('applyEdits', { doc: B, edits: [{ op: 'describe', id: 'R2', description: 'lower leg' }] });
  ck('describe', ds.ok && rec(await A('getCircuit', { doc: B, ids: ['R2'] }), 'R2').description === 'lower leg');
  // add/removeScope; delete in scope
  const nScopes = async () => (await A('getCircuit', { doc: B, limit: 1 })).data.scopes.length;
  const sc0 = await nScopes();
  const as1 = await A('applyEdits', { doc: B, edits: [{ op: 'addScope', element: 'R1' }] });
  const sc1 = await nScopes();
  const rs1 = await A('applyEdits', { doc: B, edits: [{ op: 'removeScope', element: 'R1' }] });
  const sc2 = await nScopes();
  ck('addRemoveScope', as1.ok && rs1.ok && sc1 === sc0 + 1 && sc2 === sc0);
  const asq = await A('applyEdits', { doc: B, edits: [{ op: 'addScope', element: 'R2', quantity: 'current' }] });
  const scq = (await A('getCircuit', { doc: B, limit: 1 })).data.scopes;
  const del = await A('applyEdits', { doc: B, edits: [{ op: 'delete', id: 'R2' }] });
  const rem = (del.issues || []).find((i) => i.code === 'scope_removed');
  ck('deleteInScope', asq.ok && same(scq[scq.length - 1], { element: 'R2', quantity: 'current' }) && del.ok && rem && rem.severity === 'info'
    && rem.elements.includes('R2') && (await nScopes()) === sc0 && !(await A('getCircuit', { doc: B })).data.elements.some((e) => e.id === 'R2'));
  // markOpen: the post record shows open=true; marks follow delete
  const mo = await A('applyEdits', { doc: B, edits: [{ op: 'markOpen', posts: ['R_load.pin2', 'SW5.#0'] }] });
  const moRec = rec(await A('getCircuit', { doc: B, ids: ['R_load'] }), 'R_load');
  const marks1 = (await docState(B)).openMarks;
  await A('applyEdits', { doc: B, edits: [{ op: 'delete', id: 'SW5' }] });
  const marks2 = (await docState(B)).openMarks;
  ck('markOpen', mo.ok && moRec.posts[1].open === true && moRec.posts[0].open === false && same(marks1, ['R_load.pin2', 'SW5.a']) && same(marks2, ['R_load.pin2']));
  // single-post orientation: Ground with end one cell below start
  const gnd = await A('applyEdits', { doc: B, edits: [{ op: 'add', element: { id: 'G2', type: 'Ground', start: { x: 2, y: 8 }, end: { x: 2, y: 9 } } }] });
  const gRec = rec(gnd, 'G2');
  ck('singlePostOrientation', gnd.ok && gRec.posts.length === 1 && same(gRec.posts[0].at, { x: 2, y: 8 }) && same(gRec.end, { x: 2, y: 9 }));
  await visibleSame('applyEdits');
  // [PL_AGA_P6] further edits continue the open transaction: still one entry
  ck('editsNoNewUndoEntry', (await docState(B)).undo === 1 && (await docState(B)).transaction.open === true);

  // --- rejected batches leave the document unchanged and add no undo entry
  const E = (edits) => ({ doc: B, op: 'applyEdits', args: { doc: B, edits } });
  ck('invalidInBatch', await rejectCase('invalidInBatch', E([
    { op: 'add', element: { id: 'R40', type: 'Resistor', start: { x: 0, y: 10 }, end: { x: 4, y: 10 } } },
    { op: 'set', id: 'R1', properties: { resistanse: '1k' } },
    { op: 'move', id: 'R1', by: { dx: 1, dy: 0 } }]), 'unknown_property'));
  const uk = await A('applyEdits', { doc: B, edits: [{ op: 'set', id: 'R1', properties: { nope: 1 } }] });
  ck('unknownPropertyHint', has(uk, 'unknown_property') && /resistance/.test(uk.issues[0].hint));
  ck('moveByOffLattice', await rejectCase('moveByOffLattice', E([{ op: 'move', id: 'R1', by: { dx: 0.3, dy: 0 } }]), 'off_lattice'));
  ck('addOffLattice', await rejectCase('addOffLattice', E([{ op: 'add', element: { type: 'Resistor', start: { x: 0.25, y: 10 } } }]), 'off_lattice'));
  ck('zeroLength', await rejectCase('zeroLength', E([{ op: 'add', element: { type: 'Resistor', start: { x: 1, y: 10 }, end: { x: 1, y: 10 } } }]), 'zero_length'));
  ck('idInvalid', await rejectCase('idInvalid', E([{ op: 'add', element: { id: '9bad', type: 'Resistor', start: { x: 1, y: 10 } } }]), 'id_invalid'));
  ck('idTaken', await rejectCase('idTaken', E([{ op: 'add', element: { id: 'R1', type: 'Resistor', start: { x: 1, y: 10 } } }]), 'id_taken'));
  ck('unknownType', await rejectCase('unknownType', E([{ op: 'add', element: { type: 'Resistr', start: { x: 1, y: 10 } } }]), 'unknown_type'));
  ck('unknownElement', await rejectCase('unknownElement', E([{ op: 'delete', id: 'R1' }, { op: 'move', id: 'R1', by: { dx: 1, dy: 0 } }]), 'unknown_element'));
  ck('unknownPost', await rejectCase('unknownPost', E([{ op: 'markOpen', posts: ['R1.pin9'] }]), 'unknown_post'));
  ck('badQuantity', await rejectCase('badQuantity', E([{ op: 'set', id: 'R1', properties: { resistance: '4.7 kF' } }]), 'invalid_value'));
  ck('emptyQuantity', await rejectCase('emptyQuantity', E([{ op: 'set', id: 'R1', properties: { resistance: '' } }]), 'invalid_value'));
  ck('readOnlySet', await rejectCase('readOnlySet', E([{ op: 'set', id: 'T9', properties: { vertical: true } }]), 'invalid_value'));
  // T9 is a horizontal transformer (axis-aligned endpoints, as the editor's own drag gives): a text
  // reload rewrites its end corner, so the rejections below also prove the snapshot puts it back
  ck('transformerHorizontal', (() => { const t = rec(tr, 'T9'); return t && t.start.y === t.end.y; })());
  ck('rejectedImportKeepsTransformer', await rejectCase('rejectedImportTransformer', { doc: B, op: 'importCircuit', args: { doc: B, circuit: broken } }, 'import_element_skipped'));
  const big = Array.from({ length: 201 }, (_, i) => ({ op: 'describe', id: 'R1', description: 'x' + i }));
  ck('batchSize201', await rejectCase('batchSize201', E(big), 'invalid_value'));
  ck('badOp', await rejectCase('badOp', E([{ op: 'rotate', id: 'R1' }]), 'invalid_value'));
  // scope slots full: 20 views, then addScope -> scope_limit
  const twenty = Array.from({ length: 20 - (await nScopes()) }, () => ({ op: 'addScope', element: 'R1' }));
  const full = await A('applyEdits', { doc: B, edits: twenty });
  ck('scopeSlotsFull', full.ok && (await nScopes()) === 20 && await rejectCase('scopeLimit', E([{ op: 'addScope', element: 'R_load' }]), 'scope_limit'));
  await A('applyEdits', { doc: B, edits: Array.from({ length: 1 }, () => ({ op: 'removeScope', element: 'R1' })) });
  ck('scopesRemoved', (await nScopes()) === 0);
  out.rejections = rejections;

  // --- forced exception inside a batch (debug hook): snapshot restored, internal_error, global handler
  const conMark = s.markConsole();
  const fb = await state(B);
  await s.eval('CircuitJS1Agent.debugFailNextMutation()');
  const fe = await A('applyEdits', { doc: B, edits: [
    { op: 'add', element: { id: 'R41', type: 'Resistor', start: { x: 0, y: 12 }, end: { x: 4, y: 12 } } },
    { op: 'add', element: { id: 'R42', type: 'Resistor', start: { x: 0, y: 14 }, end: { x: 4, y: 14 } } }] });
  const fa = await state(B);
  await sleep(200);
  const handlerDialog = (await s.call('dialogShowing')).some((d) => /debugFailNextMutation/.test(d));
  const handlerConsole = s.consoleSince(conMark).some((c) => /debugFailNextMutation/.test(c.text));
  out.notes.forced = { issues: fe.issues, handlerDialog, handlerConsole };
  if (!same(fb, fa)) out.notes.forcedDiff = { before: fb, after: fa };
  ck('forcedExceptionRestores', fe.ok === false && has(fe, 'internal_error') && /debugFailNextMutation/.test(fe.issues[0].message) && same(fb, fa));
  ck('forcedExceptionReachesHandler', handlerDialog || handlerConsole);
  await s.call('closeDialogs');
  await s.eval('CircuitJS1Agent.debugFailNextMutation()');
  const fi = await A('importCircuit', { doc: B, circuit: { elements: RC_CELLS } });
  const fa2 = await state(B);
  ck('forcedExceptionImport', fi.ok === false && has(fi, 'internal_error') && same(fb, fa2) && (await docState(B)).agentOrigin === false);
  await s.call('closeDialogs');
  await sleep(200);

  // --- supplied ID matching the generated form (SP_AGA_05_04)
  const D = (await A('createDocument', {})).data.doc;
  const s7 = await A('applyEdits', { doc: D, edits: [{ op: 'add', element: { id: 'R7', type: 'Resistor', start: { x: 0, y: 0 } } }] });
  const s8 = await A('applyEdits', { doc: D, edits: [{ op: 'add', element: { type: 'Resistor', start: { x: 0, y: 2 } } }] });
  const s9 = await A('applyEdits', { doc: D, edits: [{ op: 'add', element: { type: 'Resistor', start: { x: 0, y: 4 } } }, { op: 'add', element: { id: 'R20', type: 'Resistor', start: { x: 0, y: 6 } } }] });
  ck('suppliedIdRaisesCounter', s7.ok && s8.ok && same(s8.data.created, ['R8']) && s9.ok && same(s9.data.created, ['R21', 'R20']));
  await A('closeDocument', { doc: D, discardChanges: true });

  // --- Grid preference of other tabs has no effect (posts; SP_AGA_05_02)
  const G16 = (await A('createDocument', {})).data.doc;
  const G8 = (await A('createDocument', {})).data.doc;
  await A('importCircuit', { doc: G8, circuit: '$ 3 0.000005 10.2 50 5 50 5e-11\n' });
  const potAt = async (doc, x) => {
    const r = await A('applyEdits', { doc, edits: [{ op: 'add', element: { type: 'Potentiometer', start: { x, y: 0 }, end: { x: x + 3, y: 0 } } }] });
    const e = recs(r)[0];
    return e ? e.posts.map((p) => [p.at.x - x, p.at.y]) : null;
  };
  const OPTS_SMALL = '$ 3 0.000005 10.20027730826997 50 5 50 5e-11\n';
  const OPTS_NORMAL = '$ 1 0.000005 10.20027730826997 50 5 50 5e-11\n';
  await s.call('importText', OPTS_SMALL + 'r 64 64 128 64 0 1000\n');
  const optFlags = async () => +String(await s.call('exportText')).split(' ')[1];
  const smallOn = ((await optFlags()) & 2) !== 0;
  const pOn = await potAt(G16, 0);
  await s.call('importText', OPTS_NORMAL + 'r 64 64 128 64 0 1000\n');
  const smallOff = ((await optFlags()) & 2) === 0;
  const pOff = await potAt(G16, 10);
  const p8 = await potAt(G8, 0);
  out.notes.grid = { smallOn, smallOff, pOn, pOff, p8 };
  ck('gridOtherTabsNoEffect', smallOn && smallOff && pOn && same(pOn, pOff) && p8 && !same(p8, pOn));
  // The editor grid follows the option the imported content selects, in both directions
  const gridOf = async (doc) => (await docState(doc)).gridSize;
  const gridSeq = [];
  await A('importCircuit', { doc: G16, circuit: '$ 3 0.000005 10.2 50 5 50 5e-11\n' }); gridSeq.push(await gridOf(G16));
  await A('importCircuit', { doc: G16, circuit: '$ 1 0.000005 10.2 50 5 50 5e-11\n' }); gridSeq.push(await gridOf(G16));
  const jsonGrid = (small) => JSON.stringify({ schema: { format: 'circuitjs', version: '2.0' }, simulation: { display: { small_grid: small } }, elements: {} });
  await A('importCircuit', { doc: G16, circuit: jsonGrid(true) }); gridSeq.push(await gridOf(G16));
  await A('importCircuit', { doc: G16, circuit: jsonGrid(false) }); gridSeq.push(await gridOf(G16));
  await A('importCircuit', { doc: G16, circuit: { elements: [], simulation: { display: { small_grid: true } } } }); gridSeq.push(await gridOf(G16));
  await A('importCircuit', { doc: G16, circuit: { elements: [] } }); gridSeq.push(await gridOf(G16));
  out.notes.gridSeq = gridSeq;
  ck('gridFollowsImport', same(gridSeq, [8, 16, 8, 16, 8, 16]));
  // tab switches keep each document's own grid (8 -> 16 included)
  const actives = [];
  for (const d of [G8, G16, G8, G16]) { await A('activateDocument', { doc: d }); actives.push(await gridOf(d)); }
  out.notes.gridTabs = actives;
  ck('gridOnTabSwitch', same(actives, [8, 16, 8, 16]));
  await A('activateDocument', { doc: A0 });
  for (const d of [G16, G8, B]) await A('closeDocument', { doc: d, discardChanges: true });

  // --- Every catalogue type ([SP_AGA_02_04] add / set over the whole factory): add with the TypeInfo
  // defaults; a set of every writable key at its current value reports nothing (merge-then-apply is
  // idempotent); every writable bool key can be set and cleared again (RULE_ARCH_010). NMOS/PMOS "digital"
  // is the session-wide MOSFET display setting and is read-only.
  const sweep = await s.eval(`(() => {
    const A = (op, a) => __H.agentCall(op, a);
    const out = { types: 0, addFail: [], noopAdjusted: {}, boolFail: {} };
    const D = A('createDocument', {}).data.doc;
    let n = 0;
    for (const t of A('listTypes', {}).data.types.map((x) => x.type)) {
      out.types++;
      const info = A('describeType', { type: t }).data;
      A('importCircuit', { doc: D, circuit: { elements: [] } });
      const id = 'X' + (++n);
      const add = A('applyEdits', { doc: D, edits: [{ op: 'add', element: { id, type: t, start: { x: 10, y: 10 } } }] });
      if (!add.ok) { out.addFail.push(t); continue; }
      const cur = add.data.elements[0].properties;
      const patch = {};
      for (const p of info.properties) if (!p.readOnly && cur[p.key] !== undefined) patch[p.key] = cur[p.key];
      const ns = A('applyEdits', { doc: D, edits: [{ op: 'set', id, properties: patch }] });
      const adj = ns.ok ? ns.issues.filter((i) => i.code === 'value_adjusted').map((i) => i.message) : ['rejected'];
      if (adj.length || add.issues.some((i) => i.code === 'value_adjusted')) out.noopAdjusted[t] = adj;
      for (const p of info.properties.filter((q) => q.kind === 'bool' && !q.readOnly)) {
        const v0 = A('getCircuit', { doc: D, ids: [id], detail: 'full' }).data.elements[0].properties[p.key];
        const r1 = A('applyEdits', { doc: D, edits: [{ op: 'set', id, properties: { [p.key]: !v0 } }] });
        const r2 = A('applyEdits', { doc: D, edits: [{ op: 'set', id, properties: { [p.key]: v0 } }] });
        const v1 = r1.ok && r1.data.elements[0].properties[p.key], v2 = r2.ok && r2.data.elements[0].properties[p.key];
        if (v1 !== !v0 || v2 !== v0) (out.boolFail[t] = out.boolFail[t] || []).push(p.key);
      }
    }
    A('closeDocument', { doc: D, discardChanges: true });
    return out;
  })()`);
  out.sweep = sweep;
  ck('catalogueSweep', sweep.types >= 140 && sweep.addFail.length === 0 && Object.keys(sweep.noopAdjusted).length === 0 && Object.keys(sweep.boolFail).length === 0);

  // --- MOSFET display flags are session-wide: a background import, and a rejected import into the
  // visible tab, leave the visible tab's MOSFET unchanged
  const OPTS = '$ 1 0.000005 10.20027730826997 50 5 50 5e-11\n';
  await s.call('importText', OPTS + 'f 64 64 128 64 0 1.5 0.02\n');
  await sleep(200);
  const mosLine = async () => String(await s.call('exportText')).split('\n').find((l) => l.startsWith('f ')) || '';
  const mos0 = await mosLine();
  const M = (await A('createDocument', {})).data.doc;
  const mImp = await A('importCircuit', { doc: M, circuit: OPTS + 'f 64 64 128 64 4 1.5 0.02\n' });
  await sleep(300);
  const mos1 = await mosLine();
  const mRejA = await A('importCircuit', { circuit: OPTS + 'f 64 64 128 64 4 1.5 0.02\nqqq 1 2 3 4 0\n' });
  await sleep(300);
  const mos2 = await mosLine();
  const dig = await A('applyEdits', { doc: M, edits: [{ op: 'set', id: 'M1', properties: { digital: false } }] });
  out.notes.mosfet = { mos0, mos1, mos2 };
  ck('mosfetGlobalFlagsKept', mImp.ok && mos0 !== '' && mos1 === mos0 && mRejA.ok === false && mos2 === mos0);
  ck('mosfetDigitalReadOnly', dig.ok === false && has(dig, 'invalid_value'));
  await A('closeDocument', { doc: M, discardChanges: true });

  // --- One ID scheme (active document): exportCircuit json keys = getCircuit IDs = scripting global IDs
  await s.call('loadExample', 'lrc.txt');
  const gIds = (await A('getCircuit', { limit: 500 })).data.elements.map((e) => e.id).sort();
  const eKeys = Object.keys(JSON.parse((await A('exportCircuit', {})).data.content).elements).sort();
  const sIds = [...(await s.call('ids'))].sort();
  ck('oneIdScheme', gIds.length > 0 && same(gIds, eKeys) && same(gIds, sIds));

  // --- Legacy circuit inspection (SP_AGA_05_03): user load, every element has an ID, cells, same IDs on reload
  await s.call('loadExample', 'zenerref.txt');
  const li0 = await A('getCircuit', { detail: 'full', limit: 500 });
  await s.call('loadExample', 'zenerref.txt');
  const li1 = await A('getCircuit', { detail: 'full', limit: 500 });
  const allCells = recs(li0).every((e) => /^[A-Za-z][A-Za-z0-9_]*$/.test(e.id) && Number.isFinite(e.start.x) && Number.isFinite(e.end.y)
    && Number.isInteger(e.start.x * 16) && Number.isInteger(e.end.y * 16));
  const zr = recs(li0).find((e) => e.type === 'Resistor');
  ck('legacyInspection', li0.ok && recs(li0).length === (await s.call('count')) && allCells && same(recs(li0).map((e) => e.id), recs(li1).map((e) => e.id))
    && zr && zr.start.x === 416 / 16);

  ck('visibleTabUnchanged', r1.failed.length === 0);
  out.r1 = r1;
  ck('noPageExceptions', s.exceptions.length === exMark);
  fs.writeFileSync(path.join(OUT_DIR, 'agent_edit.json'), JSON.stringify(out, null, 2));
  const failed = Object.keys(out.checks).filter((n) => !out.checks[n]);
  report('AG.agent_edit', failed.length === 0, { checks: Object.keys(out.checks).length, failed, details: path.join(OUT_DIR, 'agent_edit.json') });
}

// ---------------------------------------------------------------- agent_connect (PL_AGA Phase 5)
// Connectivity, readings and diagnostics: SP_AGA_05_01 rows importCircuit RC divider in cells,
// applyEdits dangling wire end / markOpen / post on wire body / delta cap, getConnectivity
// netFilter / labelled ground / parallel wires / reserved label, read unknown label,
// getDiagnostics log cursor; SP_AGA_05_02 "No 0-V fallback", "Labels are per document"; the
// visible tab stays unchanged across background getConnectivity/read/getDiagnostics (R1).
const cellsLabel = (id, text, x, y, ex, ey) => ({ id, type: 'LabeledNode', start: { x, y }, end: { x: ex, y: ey }, properties: { label: text } });
const rcWith = (volts, extra) => [
  ...(extra || []),
  { id: 'V1', type: 'DCVoltage', start: { x: 0, y: 4 }, end: { x: 0, y: 0 }, properties: { max_voltage: volts } },
  { id: 'R1', type: 'Resistor', start: { x: 0, y: 0 }, end: { x: 4, y: 0 }, properties: { resistance: '1k' } },
  { id: 'R2', type: 'Resistor', start: { x: 4, y: 0 }, end: { x: 4, y: 4 }, properties: { resistance: '1k' } },
  { id: 'W1', type: 'Wire', start: { x: 4, y: 4 }, end: { x: 0, y: 4 } },
  { id: 'GND1', type: 'Ground', start: { x: 0, y: 4 }, end: { x: 0, y: 5 } },
];

async function scenarioAgentConnect(s) {
  const out = { checks: {}, notes: {} };
  const ck = (name, cond) => { out.checks[name] = !!cond; return !!cond; };
  const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
  const A = (op, args) => s.call('agentCall', op, args);
  const codes = (list) => (list || []).map((i) => i.code);
  const recs = (r) => (r && r.data && r.data.elements) || [];
  const rec = (r, id) => recs(r).find((e) => e.id === id);
  const net = (rep, name) => ((rep && rep.data && rep.data.nets) || []).find((n) => n.name === name);
  const errors = (list) => (list || []).filter((i) => i.severity === 'error');
  await resetApp(s);
  const exMark = s.exceptions.length;

  // Visible tab: lrc.txt (free-running, sliders, hint); the documents below are background ones
  await s.call('loadExample', 'lrc.txt');
  await sleep(300);
  const vis0 = await s.call('visibleTab');
  const A0 = (await A('listDocuments', {})).data.documents.find((d) => d.active).doc;
  const mk = async (title) => (await A('createDocument', { title })).data.doc;
  const B = await mk('Connect B');
  let tabs = 1;
  const r1 = { failed: [] };
  const visibleSame = async (label) => {
    const v = await s.call('visibleTab');
    if (!same(v, { ...vis0, tabCount: vis0.tabCount + tabs })) r1.failed.push({ label, v });
  };

  // --- importCircuit: RC divider in cells -> connectivity.errorCount = 0
  const imp = await A('importCircuit', { doc: B, circuit: { elements: RC_CELLS } });
  out.notes.importConnectivity = imp.connectivity;
  ck('importRcErrorCount0', imp.ok && imp.connectivity && imp.connectivity.errorCount === 0 && Array.isArray(imp.connectivity.added)
    && Array.isArray(imp.connectivity.cleared) && imp.connectivity.truncatedAdded === 0);
  await visibleSame('importRc');

  // --- PostRecord.net from the document's analysed nodes
  const gB = await A('getCircuit', { doc: B, detail: 'full' });
  const allNets = recs(gB).every((e) => e.posts.every((p) => typeof p.net === 'string' && p.net.length > 0));
  const v1 = rec(gB, 'V1'), rr1 = rec(gB, 'R1'), rr2 = rec(gB, 'R2');
  ck('postRecordNet', allNets && v1.posts[0].net === 'gnd' && rr2.posts[1].net === 'gnd' && rr1.posts[0].net === v1.posts[1].net
    && /^\$\d+$/.test(rr1.posts[0].net) && rr1.posts[1].net === rr2.posts[0].net && rr1.posts[0].net !== rr1.posts[1].net);
  await visibleSame('getCircuitNets');

  // --- getConnectivity of the RC divider: nets, posts sorted, wires counted apart, no issues
  const cB = await A('getConnectivity', { doc: B });
  out.notes.connectB = cB.data;
  const gnd = net(cB, 'gnd');
  ck('connectRc', cB.ok && cB.data.analysed === true && cB.data.implicitGround === false && cB.data.truncated === false
    && cB.data.issues.length === 0 && cB.data.nets.length === 3 && gnd && gnd.wires === 1
    && same(gnd.posts, [...gnd.posts].sort()) && gnd.posts.includes('GND1.gnd') && gnd.posts.includes('R2.pin2') && !gnd.posts.some((p) => p.startsWith('W1.'))
    && same(cB.data.nets.map((n) => n.name), [...cB.data.nets.map((n) => n.name)].sort()));
  await visibleSame('getConnectivity');

  // --- applyEdits dangling wire end: far post named although the wire's node holds R1's post
  const dw = await A('applyEdits', { doc: B, edits: [{ op: 'add', element: { id: 'W9', type: 'Wire', start: { x: 4, y: 0 }, end: { x: 4, y: -3 } } }] });
  const w9 = dw.data && dw.data.elements.find((e) => e.id === 'W9');
  const dang = dw.connectivity && dw.connectivity.added.find((i) => i.code === 'dangling_post' && same(i.posts, ['W9.b']));
  out.notes.danglingWire = dw.connectivity;
  ck('danglingWireEnd', dw.ok && dang && dang.severity === 'error' && same(dang.at, { x: 4, y: -3 }) && w9 && w9.posts[1].net === rr1.posts[1].net
    && dw.connectivity.errorCount === 1 && dw.connectivity.added.length === 1);
  // --- applyEdits markOpen: the dangling post is cleared and its record shows open=true
  const mo = await A('applyEdits', { doc: B, edits: [{ op: 'markOpen', posts: ['W9.b'] }] });
  const gB2 = await A('getCircuit', { doc: B, ids: ['W9'] });
  ck('markOpenClears', mo.ok && dang && mo.connectivity.cleared.some((i) => i.key === dang.key) && mo.connectivity.errorCount === 0
    && rec(gB2, 'W9').posts[1].open === true);
  await visibleSame('applyEdits');

  // --- applyEdits post on wire body: wire (0,0)-(4,0), resistor posted at (2,0)
  const C = await mk('Connect C'); tabs++;
  const pw = await A('applyEdits', { doc: C, edits: [
    { op: 'add', element: { id: 'W1', type: 'Wire', start: { x: 0, y: 0 }, end: { x: 4, y: 0 } } },
    { op: 'add', element: { id: 'R1', type: 'Resistor', start: { x: 2, y: 0 }, end: { x: 2, y: 4 } } }] });
  const pob = pw.connectivity && pw.connectivity.added.find((i) => i.code === 'post_on_wire_body');
  out.notes.postOnWire = pw.connectivity;
  ck('postOnWireBody', pw.ok && pob && pob.severity === 'error' && same(pob.posts, ['R1.pin1']) && pob.elements.includes('W1'));

  // --- applyEdits delta cap: 80 wires, one end on an existing post, the other end free
  const D = await mk('Connect D'); tabs++;
  const res80 = []; const wires80 = [];
  for (let k = 0; k < 80; k++) {
    res80.push({ id: 'R' + (k + 1), type: 'Resistor', start: { x: 4 * k, y: 0 }, end: { x: 4 * k, y: 4 } });
    wires80.push({ op: 'add', element: { type: 'Wire', start: { x: 4 * k, y: 0 }, end: { x: 4 * k, y: -2 } } });
  }
  const i80 = await A('importCircuit', { doc: D, circuit: { elements: res80 } });
  const e80 = await A('applyEdits', { doc: D, edits: wires80 });
  out.notes.deltaCap = e80.connectivity && { added: e80.connectivity.added.length, truncatedAdded: e80.connectivity.truncatedAdded,
    cleared: e80.connectivity.cleared.length, truncatedCleared: e80.connectivity.truncatedCleared, codes: [...new Set(codes(e80.connectivity.added))] };
  ck('deltaCap', i80.ok && e80.ok && e80.connectivity.added.length === 50 && e80.connectivity.truncatedAdded === 30
    && codes(e80.connectivity.added).every((c) => c === 'dangling_post') && e80.connectivity.truncatedCleared === 30);
  const cD = await A('getConnectivity', { doc: D });
  ck('reportCaps', cD.ok && cD.data.issues.length === 100 && cD.data.truncated === true);
  await visibleSame('deltaCap');

  // --- labelled ground, netFilter, reserved label (document E)
  const E = await mk('Connect E'); tabs++;
  const eImp = await A('importCircuit', { doc: E, circuit: { elements: [...rcWith('5 V'),
    cellsLabel('L1', 'out', 4, 0, 6, 0), cellsLabel('L2', '0V', 0, 4, -2, 4), cellsLabel('L3', 'gnd', 0, 0, -2, 0)] } });
  const cE = await A('getConnectivity', { doc: E });
  out.notes.connectE = cE.data;
  const gE = net(cE, 'gnd'), lg = net(cE, 'label:gnd'), outNet = net(cE, 'out');
  ck('labelledGround', eImp.ok && gE && gE.labels.includes('0V') && gE.posts.includes('L2.node'));
  ck('reservedLabel', lg && same(lg.labels, ['gnd']) && cE.data.issues.some((i) => i.code === 'reserved_label' && i.severity === 'warning' && same(i.elements, ['L3'])));
  const fE = await A('getConnectivity', { doc: E, netFilter: ['out'] });
  ck('netFilter', fE.ok && fE.data.nets.length === 1 && fE.data.nets[0].name === 'out' && same(fE.data.issues, cE.data.issues)
    && outNet && same(outNet.posts, ['L1.node', 'R1.pin2', 'R2.pin1']));
  const fBad = await A('getConnectivity', { doc: E, netFilter: ['out', 'nope'] });
  ck('netFilterUnknown', fBad.ok === false && codes(fBad.issues).includes('unknown_net'));
  ck('singleLabelInfo', cE.data.issues.filter((i) => i.code === 'single_label').every((i) => i.severity === 'info')
    && cE.data.issues.some((i) => i.code === 'single_label' && same(i.elements, ['L1'])) && errors(cE.data.issues).length === 0);
  await visibleSame('labels');

  // --- parallel wires: wire_loop warning at most, no error
  const F = await mk('Connect F'); tabs++;
  const pImp = await A('importCircuit', { doc: F, circuit: { elements: [
    { id: 'V1', type: 'DCVoltage', start: { x: 0, y: 4 }, end: { x: 0, y: 0 } },
    { id: 'R1', type: 'Resistor', start: { x: 0, y: 0 }, end: { x: 4, y: 0 } },
    { id: 'W1', type: 'Wire', start: { x: 4, y: 0 }, end: { x: 4, y: 4 } },
    { id: 'W2', type: 'Wire', start: { x: 4, y: 0 }, end: { x: 4, y: 4 } },
    { id: 'W3', type: 'Wire', start: { x: 4, y: 4 }, end: { x: 0, y: 4 } },
    { id: 'GND1', type: 'Ground', start: { x: 0, y: 4 }, end: { x: 0, y: 5 } }] } });
  const cF = await A('getConnectivity', { doc: F });
  out.notes.parallel = cF.data && cF.data.issues.map((i) => i.code + ':' + i.severity);
  ck('parallelWires', pImp.ok && cF.ok && errors(cF.data.issues).length === 0 && pImp.connectivity.errorCount === 0
    && cF.data.issues.every((i) => i.severity !== 'error'));

  // --- shorted source: source_or_wire_loop (error) naming the source, also in getDiagnostics.events
  const G = await mk('Connect G'); tabs++;
  await A('importCircuit', { doc: G, circuit: { elements: [
    { id: 'V1', type: 'DCVoltage', start: { x: 0, y: 4 }, end: { x: 0, y: 0 } },
    { id: 'W1', type: 'Wire', start: { x: 0, y: 0 }, end: { x: 0, y: 4 } },
    { id: 'GND1', type: 'Ground', start: { x: 0, y: 4 }, end: { x: 0, y: 5 } }] } });
  const cG = await A('getConnectivity', { doc: G });
  const dG = await A('getDiagnostics', { doc: G });
  out.notes.shorted = { issues: cG.data && cG.data.issues, events: dG.data && dG.data.events };
  ck('sourceLoop', cG.ok && cG.data.issues.some((i) => i.code === 'source_or_wire_loop' && i.severity === 'error' && same(i.elements, ['V1']))
    && dG.ok && dG.data.events.some((i) => i.code === 'source_or_wire_loop' && same(i.elements, ['V1'])));

  // --- read: unknown label -> unknown_net, no value (No 0-V fallback); valid readings
  const ru = await A('read', { doc: E, targets: [{ net: 'vout' }] });
  ck('readUnknownNet', ru.ok === false && codes(ru.issues).includes('unknown_net') && ru.data === undefined);
  const rMix = await A('read', { doc: E, targets: [{ net: 'out' }, { net: 'vout' }] });
  ck('noZeroVoltFallback', rMix.ok === false && rMix.data === undefined && codes(rMix.issues).includes('unknown_net'));
  const rOk = await A('read', { doc: E, targets: [{ net: 'out' }, { net: 'gnd' }, { post: 'R1.pin1' }, { element: 'R2', quantity: 'current', name: 'iR2' }, { element: 'R2', name: 'vR2' }, { post: 'R1.#1', name: 'p' }] });
  ck('readOk', rOk.ok && rOk.data.values.length === 6 && same(rOk.data.values.map((v) => v.unit), ['V', 'V', 'V', 'A', 'V', 'V'])
    && rOk.data.values[1].value === 0 && typeof rOk.data.t === 'number');
  const rBad = [
    await A('read', { doc: E, targets: [{ post: 'R1.pin9' }] }),
    await A('read', { doc: E, targets: [{ element: 'Nope' }] }),
    await A('read', { doc: E, targets: [{ net: 'out', quantity: 'current' }] }),
    await A('read', { doc: E, targets: [{ net: 'out' }, { net: 'out' }] }),
    await A('read', { doc: E, targets: [] }),
  ];
  const T = await mk('Connect T'); tabs++;
  await A('applyEdits', { doc: T, edits: [{ op: 'add', element: { id: 'TX1', type: 'Text', start: { x: 2, y: 2 } } }] });
  const rText = await A('read', { doc: T, targets: [{ element: 'TX1' }] });
  ck('readErrors', same(rBad.map((r) => r.ok), [false, false, false, false, false]) && codes(rBad[0].issues).includes('unknown_post')
    && codes(rBad[1].issues).includes('unknown_element') && codes(rBad[2].issues).includes('invalid_value') && codes(rBad[3].issues).includes('invalid_value')
    && rText.ok === false && codes(rText.issues).includes('invalid_value') && /Text/.test(rText.issues[0].message));
  await visibleSame('read');

  // --- getDiagnostics: fields and the log cursor
  const d1 = await A('getDiagnostics', { doc: B, log: { since: 0, limit: 500 } });
  // read up to the newest entry (earlier scenarios may have logged more than one page)
  let c1 = d1.data && d1.data.log.cursor;
  for (let page = d1, n = 0; page.ok && page.data.log.entries.length === 500 && n < 50; n++) {
    page = await A('getDiagnostics', { doc: B, log: { since: c1, limit: 500 } });
    c1 = page.data.log.cursor;
  }
  await s.eval(`CircuitJS1.addLog('agent_connect marker 1'); CircuitJS1.addLog('agent_connect marker 2'); true`);
  const d2 = await A('getDiagnostics', { doc: B, log: { since: c1 } });
  const d3 = await A('getDiagnostics', { doc: B, log: { since: d2.data.log.cursor } });
  const d4 = await A('getDiagnostics', { doc: B, log: { since: c1, limit: 1 } });
  const seq2 = d2.data.log.entries.map((e) => e.seq);
  out.notes.diag = { fields: Object.keys(d1.data || {}), c1, gap0: d1.data && d1.data.log.gap, d2: d2.data && d2.data.log, d3: d3.data && d3.data.log };
  ck('diagnosticsFields', d1.ok && d1.data.stopped === false && Array.isArray(d1.data.events) && typeof d1.data.recovering === 'boolean'
    && Array.isArray(d1.data.lastImport) && typeof d1.data.simTime === 'number' && d1.data.running === false
    && d1.data.timeStep.max === 5e-6 && typeof d1.data.timeStep.auto === 'boolean');
  ck('logCursor', d2.ok && seq2.length >= 2 && seq2.every((q, i) => q > c1 && (i === 0 || q === seq2[i - 1] + 1))
    && d2.data.log.entries.some((e) => /agent_connect marker 2/.test(e.text)) && d2.data.log.cursor === seq2[seq2.length - 1]
    && d2.data.log.gap === false && d3.data.log.entries.length === 0 && d3.data.log.cursor === d2.data.log.cursor
    && d4.data.log.entries.length === 1 && d4.data.log.cursor === c1 + 1);
  // the harness cleared the log at start-up, so entries after seq 0 are gone
  ck('logGap', d1.data.log.gap === true && d1.data.log.entries.length > 0 && d1.data.log.entries.every((e) => e.text.length <= 500));
  const dBad = await A('getDiagnostics', { doc: B, log: { limit: 501 } });
  ck('logLimitInvalid', dBad.ok === false && codes(dBad.issues).includes('invalid_value'));
  await visibleSame('getDiagnostics');

  // --- Labels are per document: same label text in two documents, nets not joined, readings differ
  const X = await mk('Labels X'); tabs++;
  const Y = await mk('Labels Y'); tabs++;
  await A('importCircuit', { doc: X, circuit: { elements: [...rcWith('5 V'), cellsLabel('L1', 'vout', 0, 0, -2, 0)] } });
  // Y: other node numbering (an extra resistor first) and another source voltage
  await A('importCircuit', { doc: Y, circuit: { elements: [...rcWith('10 V', [{ id: 'R9', type: 'Resistor', start: { x: 10, y: 0 }, end: { x: 14, y: 0 } }]),
    cellsLabel('L1', 'vout', 0, 0, -2, 0)] } });
  const cX = await A('getConnectivity', { doc: X, netFilter: ['vout'] });
  const cY = await A('getConnectivity', { doc: Y, netFilter: ['vout'] });
  const voutOk = (c) => c.ok && c.data.nets.length === 1 && c.data.nets[0].posts.length === 3 && c.data.nets[0].posts.includes('L1.node') && c.data.nets[0].posts.includes('R1.pin1');
  ck('labelsNotJoined', voutOk(cX) && voutOk(cY) && same(cX.data.nets[0].posts, cY.data.nets[0].posts));
  // solve each document once (stepSimulation acts on the active tab), then read both in the background
  for (const d of [X, Y]) {
    await A('activateDocument', { doc: d });
    await s.eval(`CircuitJS1.stepSimulation(); CircuitJS1.stepSimulation(); true`);
  }
  await A('activateDocument', { doc: X });
  // a background mutation analyses Y last: the session label registry then holds Y's labels
  const yEdit = await A('applyEdits', { doc: Y, edits: [{ op: 'describe', id: 'R2', description: 'y' }] });
  const jsX = await s.eval(`CircuitJS1.getNodeVoltage('vout')`);
  const rY = await A('read', { doc: Y, targets: [{ net: 'vout' }] });
  const rX = await A('read', { doc: X, targets: [{ net: 'vout' }] });
  out.notes.labels = { rX: rX.data, rY: rY.data, jsX };
  ck('labelsPerDocument', yEdit.ok && rX.ok && rY.ok && Math.abs(Math.abs(rX.data.values[0].value) - 5) < 1e-6 && Math.abs(Math.abs(rY.data.values[0].value) - 10) < 1e-6
    && Math.abs(jsX - rX.data.values[0].value) < 1e-9);
  await A('activateDocument', { doc: A0 });
  await sleep(200);

  for (const d of [B, C, D, E, F, G, T, X, Y]) await A('closeDocument', { doc: d, discardChanges: true });
  ck('visibleTabUnchanged', r1.failed.length === 0);
  out.r1 = r1;
  ck('noPageExceptions', s.exceptions.length === exMark);
  if (s.exceptions.length !== exMark) out.exceptions = s.exceptions.slice(exMark).map((e) => e.slice(0, 600));
  fs.writeFileSync(path.join(OUT_DIR, 'agent_connect.json'), JSON.stringify(out, null, 2));
  const failed = Object.keys(out.checks).filter((n) => !out.checks[n]);
  report('AG.agent_connect', failed.length === 0, { checks: Object.keys(out.checks).length, failed, details: path.join(OUT_DIR, 'agent_connect.json') });
}

// agent_connect_all: importCircuit + getConnectivity + getCircuit + getDiagnostics + read of the
// first net over the example corpus (DEFAULT_CIRCUITS; CIRCUITS=all for every bundled example)
// in a background document; passes when every call returns a result without page exceptions.
async function scenarioAgentConnectAll(s) {
  const list = process.env.CIRCUITS === 'all' ? listAllCircuits() : (process.env.CIRCUITS ? process.env.CIRCUITS.split(',') : DEFAULT_CIRCUITS);
  const A = (op, args) => s.call('agentCall', op, args);
  await resetApp(s);
  const exMark = s.exceptions.length;
  const vis0 = await s.call('visibleTab');
  const doc = (await A('createDocument', { title: 'Connect all' })).data.doc;
  const bad = []; const stats = { circuits: 0, importRejected: 0, nets: 0, issues: 0, notAnalysed: 0, ms: 0 };
  const byCode = {};
  for (const name of list) {
    const ex0 = s.exceptions.length;
    const text = await s.eval(`__H.fetchText('/circuitjs1/circuits/' + ${JSON.stringify(name)})`);
    const t0 = Date.now();
    const imp = await A('importCircuit', { doc, circuit: text });
    const con = await A('getConnectivity', { doc });
    const gc = await A('getCircuit', { doc, limit: 500 });
    const dg = await A('getDiagnostics', { doc });
    const first = con.data && con.data.nets[0];
    const rd = first ? await A('read', { doc, targets: [{ net: first.name }] }) : { ok: true };
    stats.ms += Date.now() - t0;
    stats.circuits++;
    if (!imp.ok) stats.importRejected++;
    const results = [imp, con, gc, dg, rd];
    const problem = results.some((r) => r.__undefined) || !con.ok || !gc.ok || !dg.ok || !rd.ok
      || (imp.ok && !imp.connectivity) || s.exceptions.length !== ex0
      || (con.ok && con.data.analysed && recs(gc).some((e) => e.posts.some((p) => typeof p.net !== 'string')));
    if (con.ok) {
      stats.nets += con.data.nets.length; stats.issues += con.data.issues.length;
      if (!con.data.analysed) stats.notAnalysed++;
      for (const i of con.data.issues) byCode[i.code] = (byCode[i.code] || 0) + 1;
    }
    if (problem) bad.push({ name, imp: imp.ok, con: con.ok, gc: gc.ok, dg: dg.ok, rd: rd.ok, conIssues: (con.issues || []).map((i) => i.code), exceptions: s.exceptions.slice(ex0).map((e) => e.slice(0, 300)) });
  }
  await A('closeDocument', { doc, discardChanges: true });
  const vis1 = await s.call('visibleTab');
  const visibleSame = JSON.stringify(vis0) === JSON.stringify(vis1);
  fs.writeFileSync(path.join(OUT_DIR, 'agent_connect_all.json'), JSON.stringify({ stats, byCode, bad }, null, 2));
  report('AG.agent_connect_all', bad.length === 0 && visibleSame && s.exceptions.length === exMark,
    { ...stats, bad: bad.length, visibleSame, details: path.join(OUT_DIR, 'agent_connect_all.json') });
  function recs(r) { return (r && r.data && r.data.elements) || []; }
}

// geom_posts: element posts stay at the positions the pre-refactor build (dde7f33^) gave them.
// Since dde7f33 ElmGeometry started the leads as the same Point objects as the posts, so elements
// that interpolate into a lead (gates, Schmitt, delay buffer, crystal, single-post labels) moved
// their own posts. Expected posts come from a reference run of the dde7f33^ build: the element
// line is loaded as text at three geometries, drawn (SVG export, for leads computed in draw()),
// reloaded from its own text export, and its JSON pin positions are compared. Elements whose
// two posts are their endpoints are also checked in examples that use them.
const GEOM_POST_CASES = [
  ["Inverter", "I", "0 0.5 5", {"H96":"208,208 304,208","V96":"208,208 208,304","D64":"208,208 272,272"}],
  ["NANDGate", "151", "0 2 0 5", {"H96":"208,224 208,192 304,208","V96":"192,208 224,208 208,304","D64":"197,219 219,197 272,272"}],
  ["Crystal", "412", "1 4\\s2.87e-11\\s0\\s0.001\\s0.001 4\\s1e-13\\s0\\s0.001\\s0.001 0\\s0.0025\\s0\\s0 0\\s6.4", {"H96":"208,208 304,208","V96":"208,208 208,304","D64":"208,208 272,272"}],
  ["Schmitt", "182", "0 0.5 1.66 3.33 5 0", {"H96":"208,208 304,208","V96":"208,208 208,304","D64":"208,208 272,272"}],
  ["InvertingSchmitt", "183", "0 0.5 1.66 3.33 5 0", {"H96":"208,208 304,208","V96":"208,208 208,304","D64":"208,208 272,272"}],
  ["DelayBuffer", "422", "0 0 2.5 5", {"H96":"208,208 304,208","V96":"208,208 208,304","D64":"208,208 272,272"}],
  ["TestPoint", "368", "0 0", {"H96":"208,208","V96":"208,208","D64":"208,208"}],
  ["StopTrigger", "408", "0 1 0 0", {"H96":"208,208","V96":"208,208","D64":"208,208"}],
  ["LabeledNode", "207", "4 label", {"H96":"208,208","V96":"208,208","D64":"208,208"}],
  ["FM", "201", "0 800 40 5 200", {"H96":"208,208","V96":"208,208","D64":"208,208"}],
  ["TriState", "180", "0 0.1 10000000000 100000000 5", {"H96":"208,208 304,208 256,224","V96":"208,208 208,304 192,256","D64":"208,208 272,272 229,251"}],
];
const GEOM_POST_GEOMS = { H96: [208, 208, 304, 208], V96: [208, 208, 208, 304], D64: [208, 208, 272, 272] };
const GEOM_POST_OPTIONS = '$ 0 0.000005 1.0312258501325766 50 5 50 5e-11';

async function scenarioGeomPosts(s) {
  const out = { checks: {}, mismatches: [] };
  const ck = (name, cond) => { out.checks[name] = !!cond; return !!cond; };
  await resetApp(s);
  const exMark = s.exceptions.length;
  // The first SVG export loads canvas2svg asynchronously; later ones draw synchronously.
  out.svgReady = await s.eval(`(async () => {
    window.__geomSvg = 0; const prev = CircuitJS1.onsvgrendered;
    CircuitJS1.onsvgrendered = function () { window.__geomSvg++; };
    CircuitJS1.getCircuitAsSVG();
    for (let i = 0; i < 100 && !window.__geomSvg; i++) await new Promise((r) => setTimeout(r, 100));
    window.__geomSvgPrev = prev; return window.__geomSvg > 0;
  })()`);
  const posts = () => s.eval(`(() => {
    CircuitJS1.getCircuitAsSVG();
    const j = JSON.parse(CircuitJS1.exportAsJson()); const els = CircuitJS1.getElements();
    return Object.values(j.elements || {}).map((e, i) => {
      const p = e.pins || {};
      return { type: e.type, ends: [els[i].getX(), els[i].getY(), els[i].getX2(), els[i].getY2()],
        pos: Object.keys(p).filter((n) => n !== '_startpoint' && n !== '_endpoint').map((n) => p[n].position.x + ',' + p[n].position.y).join(' ') };
    });
  })()`);
  for (const [type, code, rest, expected] of GEOM_POST_CASES) {
    for (const [g, c] of Object.entries(GEOM_POST_GEOMS)) {
      const text = `${GEOM_POST_OPTIONS}\n${code} ${c.join(' ')} ${rest}\n`;
      await s.call('importText', text);
      const a = await posts();
      await s.call('importText', await s.call('exportText')); // second setPoints pass from the saved text
      const b = await posts();
      const ok = a.length === 1 && a[0].pos === expected[g] && b.length === 1 && b[0].pos === expected[g];
      if (!ck(`${type}@${g}`, ok)) out.mismatches.push({ type, g, expected: expected[g], got: a.map((e) => e.pos), reloaded: b.map((e) => e.pos) });
    }
  }
  // Two-post elements whose posts are their endpoints, inside bundled examples.
  const endPostTypes = ['Inverter', 'Schmitt', 'InvertingSchmitt', 'DelayBuffer', 'Crystal'];
  out.examples = {};
  for (const name of ['inv-osc.txt', 'crystalosc.txt', '7segdecoder.txt', 'delta-pwm.txt']) {
    await s.call('loadExample', name);
    const els = (await posts()).filter((e) => endPostTypes.includes(e.type));
    const bad = els.filter((e) => e.pos !== `${e.ends[0]},${e.ends[1]} ${e.ends[2]},${e.ends[3]}`);
    out.examples[name] = { checked: els.length, bad: bad.length };
    if (!ck('example_' + name, els.length > 0 && !bad.length)) out.mismatches.push({ example: name, bad: bad.slice(0, 5) });
  }
  await s.eval(`(() => { CircuitJS1.onsvgrendered = window.__geomSvgPrev; return true; })()`);
  ck('svgReady', out.svgReady);
  ck('noPageException', s.exceptions.length === exMark);
  fs.writeFileSync(path.join(OUT_DIR, 'geom_posts.json'), JSON.stringify(out, null, 2));
  const failed = Object.entries(out.checks).filter(([, v]) => !v).map(([k]) => k);
  report('geom_posts', !failed.length, { checks: Object.keys(out.checks).length, failed, examples: out.examples, details: path.join(OUT_DIR, 'geom_posts.json') });
}

// agent_freerun: headless approximation of RULE_TEST_002 after the simulator-core changes of
// PL_AGA Phase 5. Free-runs an analog (lrc.txt), a digital (counter.txt) and a subcircuit
// (alu74181.txt) example for ~2 s each in the visible tab and asserts that simulated time
// advances without page exceptions; free-runs a voltage source shorted by a wire (recovery
// mode) and checks that getDiagnostics.events holds source_or_wire_loop exactly once; and
// checks that the user's onanalyze hook does not fire for agent calls on a background document.
async function scenarioAgentFreeRun(s) {
  const out = { checks: {}, notes: {} };
  const ck = (name, cond) => { out.checks[name] = !!cond; return !!cond; };
  const A = (op, args) => s.call('agentCall', op, args);
  await resetApp(s);
  const exMark = s.exceptions.length;
  const runFor = async (ms) => {
    const t0 = (await s.call('simInfo')).time;
    await s.eval(`CircuitJS1.setSimRunning(true); true`);
    await sleep(ms);
    const info = await s.call('simInfo');
    await s.eval(`CircuitJS1.setSimRunning(false); true`);
    return { t0, t1: info.time, running: info.running, stop: info.stopMessage };
  };
  for (const name of ['lrc.txt', 'counter.txt', 'alu74181.txt']) {
    const ex0 = s.exceptions.length;
    await s.call('loadExample', name);
    await sleep(200);
    const r = await runFor(2000);
    out.notes[name] = r;
    ck('advances_' + name, r.t1 > r.t0 && r.running === true && !r.stop && s.exceptions.length === ex0);
  }
  // voltage source shorted by a wire: recovery-mode warning, simulation continues
  await s.call('importText', '$ 1 0.000005 10.2 50 5 50 5e-11\nv 0 64 0 0 0 0 40 5 0 0 0.5\nw 0 0 0 64 0\ng 0 64 0 80 0 0\n');
  await sleep(200);
  const loop = await runFor(2000);
  const dg = await A('getDiagnostics', {});
  const evCodes = ((dg.data && dg.data.events) || []).map((i) => i.code);
  out.notes.loop = { run: loop, events: dg.data && dg.data.events, recovering: dg.data && dg.data.recovering };
  ck('loopEventOnce', dg.ok && evCodes.filter((c) => c === 'source_or_wire_loop').length === 1
    && new Set(evCodes).size === evCodes.length && dg.data.stopped === false && loop.t1 > loop.t0 && typeof dg.data.recovering === 'boolean');
  // onanalyze: silent for a background document, called for the visible one
  await s.eval(`window.__analyzeCount = 0; CircuitJS1.onanalyze = function() { window.__analyzeCount++; }; true`);
  const bg = (await A('createDocument', { title: 'Freerun bg' })).data.doc;
  await A('importCircuit', { doc: bg, circuit: { elements: RC_CELLS } });
  await A('getConnectivity', { doc: bg });
  await A('read', { doc: bg, targets: [{ net: 'gnd' }] });
  const bgCount = await s.eval(`window.__analyzeCount`);
  await A('applyEdits', { edits: [{ op: 'describe', id: 'V1', description: 'visible' }] });
  const fgCount = await s.eval(`window.__analyzeCount`);
  await s.eval(`CircuitJS1.onanalyze = null; true`);
  await A('closeDocument', { doc: bg, discardChanges: true });
  out.notes.hook = { bgCount, fgCount };
  ck('analyzeHookBackgroundSilent', bgCount === 0 && fgCount > 0);
  ck('noPageExceptions', s.exceptions.length === exMark);
  if (s.exceptions.length !== exMark) out.exceptions = s.exceptions.slice(exMark).map((e) => e.slice(0, 600));
  fs.writeFileSync(path.join(OUT_DIR, 'agent_freerun.json'), JSON.stringify(out, null, 2));
  const failed = Object.keys(out.checks).filter((n) => !out.checks[n]);
  report('AG.agent_freerun', failed.length === 0, { checks: Object.keys(out.checks).length, failed, details: path.join(OUT_DIR, 'agent_freerun.json') });
}

// [PL_AGA_P7] Runs, probes and simulation control (SP_AGA_01_09, SP_AGA_02_09, SP_AGA_02_10,
// SP_AGA_03_07, SP_AGA_04_02): SP_AGA_05_01 rows simControl (configure, invalid), run (span,
// settle, shorted source, forced non-convergence, budget, points cap); SP_AGA_05_03 "Run owns
// stepping", "User interrupts a run", "Free-running during edits"; SP_AGA_05_04 closing during
// run, settle never reached; plus a forced exception inside a slice (debugFailNextRunSlice), the
// ontimestep hook silent for background runs, busy policy, decimation caps and the visible tab
// unchanged by background runs.
const RC_TAU_1MS = [
  { id: 'V1', type: 'DCVoltage', start: { x: 0, y: 4 }, end: { x: 0, y: 0 }, properties: { max_voltage: '5 V' } },
  { id: 'R1', type: 'Resistor', start: { x: 0, y: 0 }, end: { x: 4, y: 0 }, properties: { resistance: '1k' } },
  { id: 'C1', type: 'Capacitor', start: { x: 4, y: 0 }, end: { x: 4, y: 4 }, properties: { capacitance: '1 uF', initial_voltage: 0 } },
  { id: 'W1', type: 'Wire', start: { x: 4, y: 4 }, end: { x: 0, y: 4 } },
  { id: 'G1', type: 'Ground', start: { x: 0, y: 4 }, end: { x: 0, y: 5 } },
];
async function scenarioAgentRun(s) {
  const out = { checks: {}, notes: {} };
  const ck = (name, cond) => { out.checks[name] = !!cond; return !!cond; };
  const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
  const A = (op, args) => s.call('agentCall', op, args);
  const R = (args, timeoutMs) => s.call('agentAsync', 'run', args, timeoutMs || 30000);
  const codes = (r) => ((r && r.issues) || []).map((i) => i.code);
  const issue = (r, code) => ((r && r.issues) || []).find((i) => i.code === code);
  const diag = async (doc) => (await A('getDiagnostics', doc ? { doc } : {})).data;
  const near = (a, b, rel) => Math.abs(a - b) <= rel * Math.abs(b);
  try {
  await resetApp(s);
  const exMark = s.exceptions.length;
  await s.eval(`CircuitJS1.setSimRunning(false); true`);

  // visible tab: lrc.txt, stopped; background documents are compared against it
  await s.call('loadExample', 'lrc.txt');
  await sleep(300);
  const V = (await A('listDocuments', {})).data.documents.find((d) => d.active).doc;
  const mk = async (title, elements) => {
    const d = (await A('createDocument', { title })).data.doc;
    if (elements) await A('importCircuit', { doc: d, circuit: { elements } });
    return d;
  };
  const B = await mk('Run B', RC_TAU_1MS);
  let tabs = 1;
  const vis0 = await s.call('visibleTab');
  const visFailed = [];
  // the tab count follows the background documents this scenario creates and closes
  const visibleSame = async (label) => {
    const v = await s.call('visibleTab');
    if (!same({ ...v, tabCount: 0 }, { ...vis0, tabCount: 0 }) || v.tabCount !== vis0.tabCount + tabs - 1) visFailed.push({ label, v });
  };

  // --- simControl configure: maxTimeStep "1 us" -> timeStep.max = 1e-6 after the next analysis
  // (the import's transaction is sealed first, so configure opens a transaction of its own)
  await A('checkpoint', { doc: B, comment: 'rc' });
  const cfg = await A('simControl', { doc: B, action: 'configure', settings: { maxTimeStep: '1 us' } });
  const dCfg = await diag(B);
  out.notes.configure = { data: cfg.data, transaction: cfg.transaction, connectivity: !!cfg.connectivity };
  ck('configure', cfg.ok && cfg.data.timeStep.max === 1e-6 && dCfg.timeStep.max === 1e-6 && dCfg.timeStep.current === 1e-6
    && same(cfg.transaction, { open: true, pendingEdits: 1 }) && !!cfg.connectivity);
  await visibleSame('configure');
  // configure joins the agent transaction: one agent undo restores the previous settings
  const und = await A('undo', { doc: B });
  ck('configureUndo', und.ok && (await diag(B)).timeStep.max === 5e-6);
  // --- simControl invalid: min > max, non-positive, unparseable, no setting, unknown action
  const tx0 = (await A('getHistory', { doc: B })).data;
  const inv1 = await A('simControl', { doc: B, action: 'configure', settings: { minTimeStep: '2 us', maxTimeStep: '1 us' } });
  const inv2 = await A('simControl', { doc: B, action: 'configure', settings: { maxTimeStep: 0 } });
  const inv3 = await A('simControl', { doc: B, action: 'configure', settings: { maxTimeStep: 'fast' } });
  const inv4 = await A('simControl', { doc: B, action: 'configure', settings: {} });
  const inv5 = await A('simControl', { doc: B, action: 'pause' });
  const inv6 = await A('simControl', { doc: B, action: 'configure', settings: { minTimeStep: '10 us' } });
  const dInv = await diag(B);
  out.notes.invalid = [inv1, inv2, inv3, inv4, inv5, inv6].map((r) => r.issues && r.issues[0] && r.issues[0].message);
  ck('configureInvalid', [inv1, inv2, inv3, inv4, inv5, inv6].every((r) => r.ok === false && codes(r)[0] === 'invalid_value')
    && /minTimeStep/.test(inv1.issues[0].message) && /maxTimeStep/.test(inv2.issues[0].message)
    && dInv.timeStep.max === 5e-6 && !!inv1.transaction && !inv5.transaction
    && (await A('getHistory', { doc: B })).data.undo.length === tx0.undo.length);
  // --- simControl run/stop/reset on a background document: flag only, never stepped in the background
  const sRun = await A('simControl', { doc: B, action: 'run' });
  const tBg0 = (await diag(B)).simTime;
  await sleep(300);
  const tBg1 = (await diag(B)).simTime;
  const sStop = await A('simControl', { doc: B, action: 'stop' });
  const sReset = await A('simControl', { doc: B, action: 'reset' });
  ck('simControlRunStopReset', sRun.ok && sRun.data.running === true && tBg1 === tBg0 && sStop.ok && sStop.data.running === false
    && sReset.ok && sReset.data.simTime === 0 && !sRun.transaction && (await s.call('simInfo')).running === false);
  await visibleSame('simControl');

  // --- argument checks (nothing starts; the document stays idle)
  const bad = {
    pointsCap: await R({ doc: B, span: '1 ms', probes: [{ element: 'C1' }, { element: 'R1' }], maxPoints: 1500 }),
    maxPointsLow: await R({ doc: B, span: '1 ms', maxPoints: 5 }),
    probes17: await R({ doc: B, span: '1 ms', probes: Array.from({ length: 17 }, () => ({ element: 'C1' })) }),
    budgetLow: await R({ doc: B, span: '1 ms', budgetMs: 50 }),
    spanZero: await R({ doc: B, span: 0 }),
    spanMissing: await R({ doc: B }),
    spanUnit: await R({ doc: B, span: '5 parsecs' }),
    tolerance: await R({ doc: B, mode: 'settle', settle: { tolerance: -1 } }),
    unknownNet: await R({ doc: B, span: '1 ms', probes: [{ net: 'vout' }] }),
    unknownElement: await R({ doc: B, span: '1 ms', probes: [{ element: 'R9' }] }),
  };
  out.notes.bad = Object.fromEntries(Object.entries(bad).map(([k, r]) => [k, codes(r)]));
  ck('pointsCap', bad.pointsCap.ok === false && codes(bad.pointsCap)[0] === 'invalid_value' && /maxPoints/.test(bad.pointsCap.issues[0].message));
  ck('runArgsInvalid', ['maxPointsLow', 'probes17', 'budgetLow', 'spanZero', 'spanMissing', 'spanUnit', 'tolerance'].every((k) => bad[k].ok === false && codes(bad[k])[0] === 'invalid_value')
    && codes(bad.unknownNet)[0] === 'unknown_net' && codes(bad.unknownElement)[0] === 'unknown_element'
    && (await A('listDocuments', {})).data.documents.every((d) => d.busy === false));
  const syncRun = await A('run', { doc: B, span: '1 ms' });
  ck('runSyncRejected', syncRun.ok === false && codes(syncRun)[0] === 'invalid_value' && /callAsync/.test(syncRun.issues[0].hint));

  // --- run span: RC tau = 1 ms, span 5 ms, probe on the capacitor -> final within 1 % of 0.993 x 5 V
  const span = await R({ doc: B, span: '5 ms', reset: true, probes: [{ name: 'vc', element: 'C1' }, { net: 'gnd' }] });
  const pv = span.data && span.data.probes[0];
  out.notes.span = span.ok ? { reason: span.data.reason, tStart: span.data.tStart, tEnd: span.data.tEnd, steps: span.data.steps, wallMs: span.data.wallMs, stats: pv.stats, points: pv.series.t.length } : span;
  const fin = pv && Math.abs(pv.stats.final);
  ck('runSpan', span.ok && span.data.reason === 'span_reached' && near(fin, 5 * (1 - Math.exp(-5)), 0.01)
    && span.data.tEnd - span.data.tStart >= 0.005 && span.data.tEnd - span.data.tStart < 0.005 + 1e-5 && pv.unit === 'V' && pv.name === 'vc'
    && span.data.probes[1].name === 'gnd' && span.data.probes[1].stats.max === 0);
  // stats and decimation: time-weighted mean of 5(1-e^-t) over 5 tau, rise time 10->90 % = tau ln 9
  const meanExp = 5 * (1 - (1 - Math.exp(-5)) / 5);
  ck('probeStats', pv && near(Math.abs(pv.stats.mean), meanExp, 0.01) && near(pv.stats.riseTime, 0.001 * Math.log(9), 0.03)
    && pv.stats.samples === span.data.steps && pv.series.t.length <= 200 && pv.series.t.length === pv.series.v.length
    && pv.series.t.every((t, i) => i === 0 || t > pv.series.t[i - 1]) && pv.stats.frequency === undefined
    && pv.series.v.every((v) => String(Math.abs(v)).replace(/^0\.0*|\.|e.*$/g, '').length <= 6)
    && Math.max(...pv.series.v.map(Math.abs)) === Math.abs(pv.stats.max));
  await visibleSame('run span');

  // --- run settle: DC divider -> settled before maxSpan
  const D = await mk('Run D', RC_CELLS); tabs++;
  const settle = await R({ doc: D, mode: 'settle', settle: { maxSpan: 1 }, probes: [{ element: 'R1' }] });
  out.notes.settle = settle.ok ? { reason: settle.data.reason, span: settle.data.tEnd - settle.data.tStart, steps: settle.data.steps } : settle;
  ck('runSettle', settle.ok && settle.data.reason === 'settled' && settle.data.tEnd - settle.data.tStart < 1
    && settle.data.tEnd - settle.data.tStart >= 50 * 5e-6 && settle.data.probes[0].stats.samples === settle.data.steps);
  // --- [SP_AGA_02_10 First sample] a run without reset right after an import starts on an unsolved
  // circuit: the unsolved state (0 V) is not sampled, the first sample follows the first timestep
  // (samples = steps); a run continuing from a solved state samples its start (samples = steps + 1)
  const F = await mk('Run F', RC_CELLS); tabs++;
  const f1 = await R({ doc: F, span: '0.1 ms', probes: [{ element: 'R1' }] });
  const f2 = await R({ doc: F, span: '0.1 ms', probes: [{ element: 'R1' }] });
  const fs1 = f1.data && f1.data.probes[0].stats, fs2 = f2.data && f2.data.probes[0].stats;
  out.notes.firstSample = f1.ok && f2.ok ? { steps: [f1.data.steps, f2.data.steps], stats: [fs1, fs2], t: [f1.data.tStart, f1.data.tEnd, f2.data.tStart], v0: f2.data.probes[0].series.v[0] } : [f1, f2];
  ck('firstSampleSolved', f1.ok && f1.data.reason === 'span_reached' && near(Math.abs(fs1.min), 10 / 3, 0.01) && near(Math.abs(fs1.max), 10 / 3, 0.01)
    && fs1.samples === f1.data.steps && f2.ok && fs2.samples === f2.data.steps + 1 && near(f2.data.tStart, f1.data.tEnd, 1e-6)
    && near(Math.abs(f2.data.probes[0].series.v[0]), 10 / 3, 0.01));
  // --- [SP_AGA_02_10 Stop trigger] RC tau = 1 ms with a stop trigger at 2.5 V on the capacitor:
  // the run ends at t = tau ln 2 with stop_trigger (warning naming the element), running flag cleared
  const T = await mk('Run T', [...RC_TAU_1MS, { id: 'ST1', type: 'StopTrigger', start: { x: 4, y: 0 }, end: { x: 8, y: 0 }, properties: { trigger_voltage: '2.5 V' } }]); tabs++;
  const tRun = await A('simControl', { doc: T, action: 'run' });
  const st = await R({ doc: T, span: '5 ms', reset: true, probes: [{ element: 'C1' }] });
  const stI = issue(st, 'stop_trigger');
  const dT = await diag(T);
  out.notes.stopTrigger = st.ok ? { reason: st.data.reason, tStart: st.data.tStart, tEnd: st.data.tEnd, steps: st.data.steps, issue: stI, final: st.data.probes[0].stats.final, runningBefore: tRun.data && tRun.data.running, runningAfter: dT.running } : st;
  ck('runStopTrigger', st.ok && st.data.reason === 'stop_trigger' && stI && stI.severity === 'warning' && same(stI.elements, ['ST1'])
    && near(st.data.tEnd - st.data.tStart, 0.001 * Math.LN2, 0.02) && near(Math.abs(st.data.probes[0].stats.final), 2.5, 0.02)
    && tRun.ok && tRun.data.running === true && dT.running === false);
  // --- settle never reached: AC source -> settle_timeout after maxSpan (warning with the same code)
  const O = await mk('Run O', [
    { id: 'V1', type: 'ACVoltage', start: { x: 0, y: 4 }, end: { x: 0, y: 0 }, properties: { frequency: '1 kHz' } },
    { id: 'R1', type: 'Resistor', start: { x: 0, y: 0 }, end: { x: 4, y: 0 } },
    { id: 'R2', type: 'Resistor', start: { x: 4, y: 0 }, end: { x: 4, y: 4 } },
    { id: 'W1', type: 'Wire', start: { x: 4, y: 4 }, end: { x: 0, y: 4 } },
    { id: 'G1', type: 'Ground', start: { x: 0, y: 4 }, end: { x: 0, y: 5 } }]); tabs++;
  const osc = await R({ doc: O, mode: 'settle', settle: { maxSpan: '10 ms' }, probes: [{ element: 'R2' }] });
  const os = osc.data && osc.data.probes[0].stats;
  out.notes.settleTimeout = osc.ok ? { reason: osc.data.reason, issues: codes(osc), stats: os } : osc;
  ck('settleTimeout', osc.ok && osc.data.reason === 'settle_timeout' && issue(osc, 'settle_timeout') && issue(osc, 'settle_timeout').severity === 'warning'
    && osc.data.tEnd - osc.data.tStart >= 0.01);
  // frequency / duty cycle of the 1 kHz sine (10 periods)
  ck('probeFrequency', os && near(os.frequency, 1000, 0.02) && near(os.dutyCycle, 0.5, 0.05) && near(os.rms, os.max / Math.SQRT2, 0.02));

  // --- shorted source: issues contain source_or_wire_loop (error) naming the source
  const G = await mk('Run G', [
    { id: 'V1', type: 'DCVoltage', start: { x: 0, y: 4 }, end: { x: 0, y: 0 } },
    { id: 'W1', type: 'Wire', start: { x: 0, y: 0 }, end: { x: 0, y: 4 } },
    { id: 'G1', type: 'Ground', start: { x: 0, y: 4 }, end: { x: 0, y: 5 } }]); tabs++;
  const sh = await R({ doc: G, span: '1 ms' });
  const shI = issue(sh, 'source_or_wire_loop');
  out.notes.shorted = sh.ok ? { reason: sh.data.reason, issues: sh.issues } : sh;
  ck('runShortedSource', sh.ok && ['span_reached', 'solver_stop'].includes(sh.data.reason) && shI && shI.severity === 'error' && same(shI.elements, ['V1']));

  // --- forced non-convergence: a VCVS that inverts its own output without delay
  const N = await mk('Run N', [
    { id: 'VCV1', type: 'VCVS', start: { x: 0, y: 0 }, end: { x: 4, y: 0 }, properties: { expression: 'a < 2.5 ? 5 : 0' } },
    { id: 'W1', type: 'Wire', start: { x: 6, y: 0 }, end: { x: 6, y: -2 } },
    { id: 'W2', type: 'Wire', start: { x: 6, y: -2 }, end: { x: 0, y: -2 } },
    { id: 'W3', type: 'Wire', start: { x: 0, y: -2 }, end: { x: 0, y: 0 } },
    { id: 'W4', type: 'Wire', start: { x: 0, y: 2 }, end: { x: 6, y: 2 } },
    { id: 'G1', type: 'Ground', start: { x: 6, y: 2 }, end: { x: 6, y: 3 } }]); tabs++;
  const nc = await R({ doc: N, span: '0.2 ms', budgetMs: 1000 });
  const ncI = issue(nc, 'convergence_failed');
  out.notes.nonConvergence = nc.ok ? { reason: nc.data.reason, steps: nc.data.steps, issues: nc.issues } : nc;
  ck('runForcedNonConvergence', nc.ok && ncI && ncI.severity === 'error' && same(ncI.elements, ['VCV1'])
    && codes(nc).filter((c) => c === 'convergence_failed').length === 1 && nc.data.steps > 0);

  // --- budget: huge span, budgetMs = 200 -> budget_exhausted, wallMs <= 200 + one slice
  const bud = await R({ doc: B, span: 1000, budgetMs: 200, probes: [{ element: 'C1' }] });
  out.notes.budget = bud.ok ? { reason: bud.data.reason, wallMs: bud.data.wallMs, steps: bud.data.steps, issues: codes(bud) } : bud;
  ck('runBudget', bud.ok && bud.data.reason === 'budget_exhausted' && bud.data.wallMs >= 200 && bud.data.wallMs <= 200 + 20 + 10
    && issue(bud, 'budget_exhausted') && issue(bud, 'budget_exhausted').severity === 'warning'
    && bud.data.probes[0].series.t.length <= 200);
  await visibleSame('run budget');

  // --- forced exception inside a slice: internal_error, global handler, callback fires once
  const conMark = s.markConsole();
  await s.eval('CircuitJS1Agent.debugFailNextRunSlice(); true');
  await s.call('agentStart', 'forced', 'run', { doc: B, span: '1 ms', reset: true });
  await sleep(800);
  const fr = await s.call('agentStarted', 'forced');
  const frRes = fr && fr.result;
  const handlerDialog = (await s.call('dialogShowing')).some((d) => /debugFailNextRunSlice/.test(d));
  const handlerConsole = s.consoleSince(conMark).some((c) => /debugFailNextRunSlice/.test(c.text));
  out.notes.forced = { calls: fr && fr.calls, reason: frRes && frRes.data && frRes.data.reason, issues: frRes && frRes.issues, handlerDialog, handlerConsole };
  ck('forcedSliceException', fr && fr.calls === 1 && frRes.ok === true && frRes.data.reason === 'solver_stop'
    && issue(frRes, 'internal_error') && /debugFailNextRunSlice/.test(issue(frRes, 'internal_error').message)
    && (handlerDialog || handlerConsole) && (await A('listDocuments', {})).data.documents.every((d) => d.busy === false));
  await s.call('closeDialogs');
  await sleep(200);
  // stop state without reset: solver_stop with no step; reset clears it
  const stopped = await R({ doc: B, span: '1 ms' });
  const resumed = await R({ doc: B, span: '1 ms', reset: true });
  ck('stopStateRun', stopped.ok && stopped.data.reason === 'solver_stop' && stopped.data.steps === 0 && codes(stopped).includes('solver_stop')
    && resumed.ok && resumed.data.reason === 'span_reached');

  // --- ontimestep hook: silent for a background run
  await s.eval(`window.__tsCount = 0; CircuitJS1.ontimestep = function() { window.__tsCount++; }; true`);
  const hk = await R({ doc: B, span: '0.2 ms' });
  const bgSteps = await s.eval('window.__tsCount');
  out.notes.timestepHook = { bgSteps, runSteps: hk.data && hk.data.steps };

  // --- busy: served and rejected contracts during a run; closing during the run -> cancelled
  await s.call('agentStart', 'long', 'run', { doc: B, span: 1000, budgetMs: 5000, probes: [{ element: 'C1' }] });
  await sleep(150);
  const busy = {
    edit: await A('applyEdits', { doc: B, edits: [{ op: 'describe', id: 'R1', description: 'x' }] }),
    sim: await A('simControl', { doc: B, action: 'stop' }),
    run: await R({ doc: B, span: '1 ms' }),
    undo: await A('undo', { doc: B }),
    checkpoint: await A('checkpoint', { doc: B, comment: 'x' }),
    read: await A('read', { doc: B, targets: [{ element: 'C1' }] }),
    getCircuit: await A('getCircuit', { doc: B }),
    list: await A('listDocuments', {}),
    closeNoDiscard: await A('closeDocument', { doc: B }),
  };
  out.notes.busy = Object.fromEntries(Object.entries(busy).map(([k, r]) => [k, r.ok ? 'ok' : codes(r)[0]]));
  ck('busyPolicy', ['edit', 'sim', 'run', 'undo', 'checkpoint', 'closeNoDiscard'].every((k) => busy[k].ok === false && codes(busy[k])[0] === 'busy')
    && busy.read.ok && busy.getCircuit.ok && busy.list.data.documents.find((d) => d.doc === B).busy === true);
  const midRun = await s.call('agentStarted', 'long');
  const closed = await A('closeDocument', { doc: B, discardChanges: true }); tabs--;
  await sleep(200);
  const lr = await s.call('agentStarted', 'long');
  out.notes.closeDuringRun = { calls: lr && lr.calls, reason: lr && lr.result && lr.result.data.reason, samples: lr && lr.result && lr.result.data.probes[0].stats.samples };
  ck('closeDuringRun', midRun.calls === 0 && closed.ok && lr.calls === 1 && lr.result.ok && lr.result.data.reason === 'cancelled'
    && issue(lr.result, 'cancelled') && lr.result.data.probes[0].stats.samples > 1
    && !(await A('listDocuments', {})).data.documents.some((d) => d.doc === B));
  await visibleSame('close during run');

  // ---- visible-document runs: put an RC circuit into the visible tab and let it free-run
  await A('importCircuit', { circuit: { elements: RC_TAU_1MS } });
  await A('simControl', { action: 'run' });
  await sleep(300);
  // ontimestep fires for a run of the visible document
  await s.eval(`window.__tsCount = 0; true`);
  const hv = await R({ span: '0.2 ms' });
  const fgSteps = await s.eval('window.__tsCount');
  await s.eval(`CircuitJS1.ontimestep = null; true`);
  out.notes.timestepHook.fgSteps = fgSteps;
  ck('timestepHookBackgroundSilent', hk.ok && hk.data.steps > 0 && bgSteps === 0 && hv.ok && fgSteps >= hv.data.steps);

  // --- Run owns stepping: the free-run loop does not step d1 during the run; free-running continues
  const t0 = (await diag()).simTime;
  const own = await R({ span: '20 ms' });
  const ts = (await diag()).timeStep;
  const advance = own.data.tEnd - own.data.tStart;
  await sleep(300);
  const dAfter = await diag();
  out.notes.runOwns = { t0, tStart: own.data.tStart, tEnd: own.data.tEnd, steps: own.data.steps, dt: ts.max, after: dAfter.simTime, running: dAfter.running, wallMs: own.data.wallMs };
  ck('runOwnsStepping', own.ok && own.data.reason === 'span_reached' && own.data.tStart >= t0
    && Math.abs(advance - own.data.steps * ts.max) < ts.max / 2 && dAfter.running === true && dAfter.simTime > own.data.tEnd);

  // --- Free-running during edits: applyEdits on the running visible document
  const fe = await A('applyEdits', { edits: [{ op: 'add', element: { id: 'R9', type: 'Resistor', start: { x: 8, y: 0 }, end: { x: 12, y: 0 } } }] });
  const tFe = (await diag()).simTime;
  await sleep(300);
  const dFe = await diag();
  out.notes.freeRunEdits = { added: fe.connectivity && fe.connectivity.added && fe.connectivity.added.map((i) => i.code), t: [tFe, dFe.simTime] };
  ck('freeRunningDuringEdits', fe.ok && fe.connectivity && fe.connectivity.added.some((i) => i.code === 'dangling_post' && (i.posts || []).some((p) => p.startsWith('R9')))
    && dFe.running === true && dFe.simTime > tFe);

  // --- User interrupts a run: a user Delete on the visible document ends the run cancelled; the edit applies after
  const nBefore = await s.call('count');
  await s.call('agentStart', 'user', 'run', { span: 1000, budgetMs: 8000, probes: [{ element: 'C1' }] });
  await sleep(400);
  await s.call('focus');
  await s.call('select', 'R9', false);
  await s.key('Delete');
  await sleep(300);
  const ur = await s.call('agentStarted', 'user');
  const nAfter = await s.call('count');
  out.notes.userInterrupt = { calls: ur && ur.calls, reason: ur && ur.result && ur.result.data.reason, samples: ur && ur.result && ur.result.data.probes[0].stats.samples, nBefore, nAfter, ids: await s.call('ids') };
  ck('userInterruptsRun', ur && ur.calls === 1 && ur.result.ok && ur.result.data.reason === 'cancelled' && issue(ur.result, 'cancelled')
    && ur.result.data.probes[0].stats.samples > 1 && nAfter === nBefore - 1 && !(await s.call('ids')).includes('R9')
    && (await diag()).running === true);
  // --- further cancel requests (SP_AGA_04_02): user undo, toolbar Reset, legacy stepSimulation, slider change
  const cancelBy = async (key, action) => {
    await s.call('agentStart', key, 'run', { span: 1000, budgetMs: 8000, probes: [{ element: 'C1' }] });
    await sleep(300);
    const before = await s.call('agentStarted', key);
    await action();
    await sleep(300);
    const st = await s.call('agentStarted', key);
    return { pending: before.calls === 0, calls: st.calls, reason: st.result && st.result.data && st.result.data.reason, tEnd: st.result && st.result.data && st.result.data.tEnd, busy: (await A('listDocuments', {})).data.documents.some((d) => d.busy) };
  };
  const cancelled = (r) => r.pending && r.calls === 1 && r.reason === 'cancelled' && r.busy === false;
  await s.call('focus');
  const cUndo = await cancelBy('undo', () => s.key('KeyZ', { ctrl: true }));
  const nUndo = await s.call('count');
  const cReset = await cancelBy('reset', () => s.eval(`(() => { const b = document.querySelector('.cirjsicon-back-in-time'); if (b) b.click(); return !!b; })()`));
  const tReset = (await diag()).simTime;
  // stop free-running by script first, so only the legacy step can move simTime past the run's tEnd
  const cScript = await cancelBy('script', () => s.eval(`CircuitJS1.setSimRunning(false); true`));
  const runningAfterScript = (await diag()).running;
  const cStep = await cancelBy('step', () => s.eval(`CircuitJS1.stepSimulation(); true`));
  const tAfterStep = (await diag()).simTime;
  await s.eval(`CircuitJS1.setSimRunning(true); true`);
  const runningRestored = (await diag()).running;
  out.notes.cancels = { cUndo, nUndo, cReset, tReset, cStep, tAfterStep, cScript, runningAfterScript, runningRestored };
  ck('cancelByUserUndo', cancelled(cUndo) && nUndo === nBefore);
  ck('cancelByToolbarReset', cancelled(cReset) && tReset < 0.01);
  ck('cancelByLegacyScript', cancelled(cStep) && cancelled(cScript) && runningAfterScript === false
    && typeof cStep.tEnd === 'number' && tAfterStep > cStep.tEnd && runningRestored === true);
  // slider change: lrc.txt has sliders; drag the first one during a run
  await s.call('loadExample', 'lrc.txt');
  await sleep(300);
  const sliderRect = async () => s.eval(`(() => { const d = Array.from(document.querySelectorAll('.gwt-DialogBox')).find((x) => x.textContent.includes('Adjustable Sliders'));
    const c = d && d.querySelector('canvas'); if (!c) return null; const r = c.getBoundingClientRect(); return { x: r.x, y: r.y, w: r.width, h: r.height }; })()`);
  const sr = await sliderRect();
  const cSlider = sr ? await (async () => {
    await s.call('agentStart', 'slider', 'run', { span: 1000, budgetMs: 8000 });
    await sleep(300);
    const before = await s.call('agentStarted', 'slider');
    await s.mouseDrag(sr.x + sr.w * 0.3, sr.y + sr.h / 2, sr.x + sr.w * 0.8, sr.y + sr.h / 2);
    await sleep(300);
    const st = await s.call('agentStarted', 'slider');
    return { pending: before.calls === 0, calls: st.calls, reason: st.result && st.result.data && st.result.data.reason, busy: (await A('listDocuments', {})).data.documents.some((d) => d.busy) };
  })() : null;
  out.notes.cancels.slider = { rect: sr, result: cSlider };
  ck('cancelBySlider', cSlider && cancelled(cSlider));
  // a run left over (slider drag not delivered) must not outlive the scenario
  await A('simControl', { action: 'stop' });
  await s.eval(`CircuitJS1.setSimRunning(false); true`);

  // --- [SP_AGA_02_10 Stop trigger] a trigger fired while free-running does not end a later run:
  // a divider with a 1 V trigger on its 6.67 V node stops the visible tab's free-run; the trigger is
  // raised to 100 V, then a run without reset (the record is stale) -> span_reached
  await A('importCircuit', { circuit: { elements: [...RC_CELLS, { id: 'ST1', type: 'StopTrigger', start: { x: 4, y: 0 }, end: { x: 8, y: 0 }, properties: { trigger_voltage: '1 V' } }] } });
  await A('simControl', { action: 'run' });
  let frStopped = false;
  for (let k = 0; k < 30 && !frStopped; k++) { await sleep(100); frStopped = (await diag()).running === false; }
  const raise = await A('applyEdits', { edits: [{ op: 'set', id: 'ST1', properties: { trigger_voltage: '100 V' } }] });
  const stale = await R({ span: '1 ms' });
  out.notes.staleTrigger = { frStopped, raise: raise.ok, reason: stale.data && stale.data.reason, steps: stale.data && stale.data.steps, issues: codes(stale) };
  ck('staleStopTriggerIgnored', frStopped && raise.ok && stale.ok && stale.data.reason === 'span_reached' && !issue(stale, 'stop_trigger'));
  // seal the visible document's agent transaction and put lrc.txt back, so no open transaction
  // (its undo label) leaks into the next scenario
  await A('checkpoint', { comment: 'stale trigger' });
  await s.call('loadExample', 'lrc.txt');
  await sleep(300);
  // --- a garbled `$` max step falls back to 5 us instead of 0 (time would never advance)
  const Z = await mk('Run Z'); tabs++;
  const zImp = await A('importCircuit', { doc: Z, circuit: '$ 1 abc 10 50 5\n' + ODD_TEXT.split('\n').slice(1).join('\n') });
  const zMax = (await diag(Z)).timeStep.max;
  const zRun = await R({ doc: Z, span: '1 ms' });
  out.notes.badStep = { imp: zImp.ok, issues: codes(zImp), max: zMax, reason: zRun.data && zRun.data.reason, steps: zRun.data && zRun.data.steps };
  ck('badTextStepDefaulted', zImp.ok && zMax === 5e-6 && zRun.ok && zRun.data.reason === 'span_reached' && zRun.data.steps >= 190);

  // --- [SP_AGA_06_01 item 18] a configured step survives tab activation: setting the time-step bar
  // from code no longer runs its command (which re-quantised to the 1-2-5 table capped at 10 us);
  // the bar shows the nearest position (10 us = 21); a user click on the bar still sets a table step
  const E = await mk('Run E', RC_TAU_1MS); tabs++;
  const eCfg = await A('simControl', { doc: E, action: 'configure', settings: { maxTimeStep: '1 ms' } });
  const barOf = async () => JSON.parse(await s.eval('CircuitJS1Agent.debugSessionState()')).bars.timeStep;
  const actE = await A('activateDocument', { doc: E });
  await sleep(200);
  const eAct = { max: (await diag(E)).timeStep.max, bar: await barOf() };
  const actV = await A('activateDocument', { doc: V });
  await sleep(200);
  const eBack = (await diag(E)).timeStep.max;
  await A('activateDocument', { doc: E });
  await sleep(200);
  const barRect = await s.eval(`(() => { const d = Array.from(document.querySelectorAll('.gwt-DialogBox')).find((x) => x.offsetWidth > 0 && x.querySelector('canvas') && /Time Step/.test(x.textContent));
    const c = d && d.querySelector('canvas'); if (!c) return null; const r = c.getBoundingClientRect(); return { x: r.x, y: r.y, w: r.width, h: r.height }; })()`);
  // the left arrow: one position down (21 -> 20 = 5 us)
  if (barRect) await s.mouseDrag(barRect.x + 5, barRect.y + barRect.h / 2, barRect.x + 5, barRect.y + barRect.h / 2);
  const eUser = { max: (await diag(E)).timeStep.max, bar: await barOf() };
  await A('activateDocument', { doc: V });
  await sleep(200);
  out.notes.timeStepActivation = { cfg: eCfg.ok, eAct, eBack, barRect, eUser };
  ck('timeStepKeptOnActivation', eCfg.ok && actE.ok && actV.ok && eAct.max === 1e-3 && eAct.bar === 21 && eBack === 1e-3);
  ck('timeStepBarUserSetsTableStep', !!barRect && eUser.bar === 20 && eUser.max === 5e-6);

  ck('visibleTabUnchanged', visFailed.length === 0);
  if (visFailed.length) out.notes.visFailed = visFailed.slice(0, 3);
  for (const d of [D, O, G, N, F, T, E, Z]) await A('closeDocument', { doc: d, discardChanges: true });
  ck('noPageExceptions', s.exceptions.length === exMark);
  if (s.exceptions.length !== exMark) out.exceptions = s.exceptions.slice(exMark).map((e) => e.slice(0, 600));
  } catch (e) {
    out.error = String(e.stack || e);
    ck('noHarnessError', false);
  }
  fs.writeFileSync(path.join(OUT_DIR, 'agent_run.json'), JSON.stringify(out, null, 2));
  const failed = Object.keys(out.checks).filter((n) => !out.checks[n]);
  report('AG.agent_run', failed.length === 0, { checks: Object.keys(out.checks).length, failed, details: path.join(OUT_DIR, 'agent_run.json') });
}

// [PL_AGA_P6] Transactions and history (SP_AGA_01_10, SP_AGA_02_12, SP_AGA_02_13, SP_AGA_04_01):
// SP_AGA_05_01 rows checkpoint (named, nothing), undo / redo, undo nothing, restoreCheckpoint;
// SP_AGA_05_02 "IDs survive undo/redo", "No duplicate IDs after restore", "Grid preference of other
// tabs has no effect" (undo/redo part), "ok=false => document unchanged" (no undo entry), "A user
// edit never merges into an agent entry", "Agent-origin pushes never auto-seal"; SP_AGA_05_03 "User
// undoes agent work"; SP_AGA_05_04 idle seal, checkpoint after ID-only change; plus background
// history ops leave the visible tab unchanged and undo/redo/restore set the modified flag.
async function scenarioAgentHistory(s) {
  const out = { checks: {}, notes: {} };
  const ck = (name, cond) => { out.checks[name] = !!cond; return !!cond; };
  const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
  const A = (op, args) => s.call('agentCall', op, args);
  const codes = (r) => ((r && r.issues) || []).map((i) => i.code);
  const recs = (r) => (r && r.data && r.data.elements) || [];
  const docState = (doc) => s.eval(`JSON.parse(CircuitJS1Agent.debugDocState(${JSON.stringify(doc)}))`);
  const state = async (doc) => {
    const t = await A('exportCircuit', { doc, format: 'text' });
    const g = await A('getCircuit', { doc, detail: 'full', limit: 500 });
    return { text: t.data && t.data.content, ids: recs(g).map((e) => e.id) };
  };
  const hist = async (doc, limit) => (await A('getHistory', limit ? { doc, limit } : { doc })).data;
  const tx = (r) => r && r.transaction;
  const add = (id, x, y) => ({ op: 'add', element: { ...(id ? { id } : {}), type: 'Resistor', start: { x, y }, end: { x: x + 4, y } } });
  await resetApp(s);
  const exMark = s.exceptions.length;
  await s.cdp.send('Browser.setDownloadBehavior', { behavior: 'deny' }).catch(() => {});
  await s.eval(`CircuitJS1Agent.debugSetIdleSealMs(0); true`);

  // Visible tab for the R1-style checks: lrc.txt; background documents never switch tabs
  await s.call('loadExample', 'lrc.txt');
  await sleep(300);
  const A0 = (await A('listDocuments', {})).data.documents.find((d) => d.active).doc;
  const H = (await A('createDocument', { title: 'History H' })).data.doc;
  const visBase = await s.call('visibleTab');
  const visFailed = [];
  // the tab count follows the background documents this scenario creates and closes
  const noCount = (v) => ({ ...v, tabCount: 0 });
  const visibleSame = async (label) => {
    const v = await s.call('visibleTab');
    if (!same(noCount(v), noCount(visBase))) visFailed.push({ label, v });
  };

  // --- checkpoint named: 3 edits, comment "add filter" -> cp1
  const e1 = await A('applyEdits', { doc: H, edits: [add('R1', 0, 0)] });
  const e2 = await A('applyEdits', { doc: H, edits: [add('R2', 0, 2)] });
  const e3 = await A('applyEdits', { doc: H, edits: [{ op: 'set', id: 'R1', properties: { resistance: '2k' } }] });
  ck('transactionCounts', same(tx(e1), { open: true, pendingEdits: 1 }) && same(tx(e2), { open: true, pendingEdits: 2 })
    && same(tx(e3), { open: true, pendingEdits: 3 }) && (await docState(H)).undo === 1);
  const h0 = await hist(H);
  ck('historyOpenTransaction', h0.openTransaction === true && h0.undo.length === 1 && h0.undo[0].kind === 'agent' && h0.undo[0].position === 0);
  const S1 = await state(H);
  const cp1 = await A('checkpoint', { doc: H, comment: 'add filter' });
  await visibleSame('checkpoint');
  const h1 = await hist(H);
  out.notes.h1 = h1;
  ck('checkpointNamed', cp1.ok && cp1.data.checkpointId === 'cp1' && cp1.data.noChanges === false && h1.openTransaction === false
    && h1.undo[0].comment === 'add filter' && h1.undo[0].checkpointId === 'cp1' && h1.undo[0].auto === false && h1.undo[0].kind === 'agent');
  ck('checkpointNotMutating', cp1.transaction === undefined && cp1.connectivity === undefined);
  // --- checkpoint nothing: no open transaction -> noChanges, stack unchanged
  const d0 = await docState(H);
  const cpN = await A('checkpoint', { doc: H, comment: 'nothing' });
  const d1 = await docState(H);
  ck('checkpointNothing', cpN.ok && cpN.data.noChanges === true && cpN.data.checkpointId === undefined && d0.undo === d1.undo && d0.redo === d1.redo);
  // invalid comments
  const bad = [await A('checkpoint', { doc: H, comment: '' }), await A('checkpoint', { doc: H, comment: 'x'.repeat(121) }),
    await A('checkpoint', { doc: H, comment: 'a\nb' }), await A('checkpoint', { doc: H })];
  ck('checkpointInvalidComment', bad.every((r) => !r.ok && codes(r)[0] === 'invalid_value'));

  // --- undo / redo round trip: states and IDs equal; redo entry carries the comment
  const u1 = await A('undo', { doc: H });
  await visibleSame('undo');
  const S0 = await state(H);
  const hu = await hist(H);
  const r1 = await A('redo', { doc: H });
  await visibleSame('redo');
  const S1b = await state(H);
  const hr = await hist(H);
  out.notes.roundTrip = { hu, hr, S0ids: S0.ids, S1, S1b };
  ck('undoRedoRoundTrip', u1.ok && u1.data.undone === 1 && r1.ok && r1.data.redone === 1 && same(S0.ids, []) && same(S1b, S1)
    && hu.redo[0].comment === 'add filter' && hu.redo[0].checkpointId === 'cp1' && hu.undo.length === 0
    && hr.undo[0].comment === 'add filter' && hr.undo[0].checkpointId === 'cp1' && hr.redo.length === 0);

  // --- undo nothing / redo nothing / steps range
  const E = (await A('createDocument', {})).data.doc;
  const un = await A('undo', { doc: E });
  const rn = await A('redo', { doc: E });
  const u51 = await A('undo', { doc: H, steps: 51 });
  const uMany = await A('undo', { doc: H, steps: 5 });
  ck('undoNothing', !un.ok && codes(un)[0] === 'nothing_to_undo' && !rn.ok && codes(rn)[0] === 'nothing_to_redo'
    && !u51.ok && codes(u51)[0] === 'invalid_value' && !uMany.ok && codes(uMany)[0] === 'nothing_to_undo' && same(await state(H), S1));
  await A('closeDocument', { doc: E, discardChanges: true });

  // --- ok=false adds no undo entry and never opens/changes a transaction
  const dBefore = await docState(H);
  const rej1 = await A('applyEdits', { doc: H, edits: [add('R9', 0, 8), { op: 'set', id: 'R1', properties: { resistanse: '1k' } }] });
  const dAfter1 = await docState(H);
  await A('applyEdits', { doc: H, edits: [add('R3', 0, 4)] }); // opens
  const dOpen = await docState(H);
  const rej2 = await A('applyEdits', { doc: H, edits: [{ op: 'delete', id: 'NOPE' }] });
  await s.eval(`CircuitJS1Agent.debugFailNextMutation(); true`);
  const rej3 = await A('applyEdits', { doc: H, edits: [add('R10', 0, 10), add('R11', 0, 12)] });
  const dAfter2 = await docState(H);
  out.notes.rejected = { rej1: tx(rej1), rej2: tx(rej2), rej3: codes(rej3), dBefore, dAfter1, dOpen, dAfter2 };
  ck('rejectedNoUndoEntry', !rej1.ok && same(tx(rej1), { open: false, pendingEdits: 0 }) && dAfter1.undo === dBefore.undo && dAfter1.redo === dBefore.redo
    && dOpen.undo === dBefore.undo + 1 && !rej2.ok && same(tx(rej2), { open: true, pendingEdits: 1 })
    && !rej3.ok && codes(rej3)[0] === 'internal_error' && same(tx(rej3), { open: true, pendingEdits: 1 }) && dAfter2.undo === dOpen.undo);

  // --- Agent-origin pushes never auto-seal: an agent delete keeps the transaction open
  const del = await A('applyEdits', { doc: H, edits: [{ op: 'delete', id: 'R3' }] });
  ck('agentDeleteKeepsTransaction', del.ok && same(tx(del), { open: true, pendingEdits: 2 }) && (await hist(H)).openTransaction === true);
  // an editor undo push under agent origin neither pushes nor seals (diagnostic reuses CircuitEditor.pushUndo)
  const dop0 = await docState(H);
  const dop = await s.eval(`CircuitJS1Agent.debugAgentOriginPush(${JSON.stringify(H)})`);
  const dop1 = await docState(H);
  ck('agentOriginPushNeverSeals', dop === true && same(dop1.transaction, { open: true, pendingEdits: 2 }) && dop1.undo === dop0.undo && dop1.redo === dop0.redo);
  // undo seals the open transaction automatically first
  const au = await A('undo', { doc: H });
  const hau = await hist(H);
  ck('undoAutoSeals', au.ok && hau.openTransaction === false && hau.redo[0].comment === 'agent edits (auto)' && hau.redo[0].auto === true
    && hau.redo[0].checkpointId === 'cp2' && same(await state(H), S1));

  // --- checkpoint after an ID-only change is not noChanges; a no-op transaction is
  const K = (await A('createDocument', {})).data.doc;
  await A('applyEdits', { doc: K, edits: [add('R1', 0, 0)] });
  await A('checkpoint', { doc: K, comment: 'base' });
  const kd0 = await docState(K);
  await A('applyEdits', { doc: K, edits: [{ op: 'delete', id: 'R1' }] });
  const readd = await A('applyEdits', { doc: K, edits: [add(null, 0, 0)] });
  const cpId = await A('checkpoint', { doc: K, comment: 'renumber' });
  await A('applyEdits', { doc: K, edits: [{ op: 'set', id: readd.data.created[0], properties: { resistance: '1k' } }] });
  const kd1 = await docState(K);
  const cpNoop = await A('checkpoint', { doc: K, comment: 'noop' });
  const kd2 = await docState(K);
  out.notes.idOnly = { created: readd.data.created, cpId: cpId.data, cpNoop: cpNoop.data, kd0, kd1, kd2 };
  ck('checkpointIdOnlyChange', readd.ok && readd.data.created[0] === 'R2' && cpId.ok && cpId.data.noChanges === false && cpId.data.checkpointId === 'cp2'
    && kd1.undo === kd0.undo + 2 && cpNoop.ok && cpNoop.data.noChanges === true && kd2.undo === kd0.undo + 1 && kd2.transaction.open === false);
  await A('closeDocument', { doc: K, discardChanges: true });

  // --- restoreCheckpoint with cp1, cp2 (and cp3) of document R
  const R = (await A('createDocument', {})).data.doc;
  await A('importCircuit', { doc: R, circuit: { elements: RC_CELLS } });
  const R0 = await state(R);
  await A('checkpoint', { doc: R, comment: 'import' }); // cp1 (pre-state: empty)
  await A('applyEdits', { doc: R, edits: [add('RA', 8, 0)] });
  const R1s = await state(R);
  await A('checkpoint', { doc: R, comment: 'stage 1' }); // cp2 (pre-state R0)
  await A('applyEdits', { doc: R, edits: [add('RB', 8, 2)] });
  await A('checkpoint', { doc: R, comment: 'stage 2' }); // cp3 (pre-state R1s)
  const R2s = await state(R);
  const unk = await A('restoreCheckpoint', { doc: R, checkpointId: 'cp99' });
  const rc2 = await A('restoreCheckpoint', { doc: R, checkpointId: 'cp2' });
  await visibleSame('restoreCheckpoint');
  const afterCp2 = await state(R);
  const hR = await hist(R);
  const rc1 = await A('restoreCheckpoint', { doc: R, checkpointId: 'cp1' });
  const afterCp1 = await state(R);
  const rc3 = await A('restoreCheckpoint', { doc: R, checkpointId: 'cp3' }); // now on the redo stack only
  const rd = await A('redo', { doc: R, steps: 3 });
  const afterRedo = await state(R);
  out.notes.restore = { unk: codes(unk), rc2: rc2.data, rc1: rc1.data, rc3: codes(rc3), hR, R0, afterCp2, afterCp1: afterCp1.ids, rd: rd.data, afterRedo: afterRedo.ids };
  ck('restoreCheckpoint', !unk.ok && codes(unk)[0] === 'unknown_checkpoint' && rc2.ok && rc2.data.undone === 2 && same(afterCp2, R0)
    && same(hR.undo.map((e) => e.checkpointId), ['cp1']) && same(hR.redo.map((e) => e.checkpointId), ['cp2', 'cp3'])
    && rc1.ok && rc1.data.undone === 1 && same(afterCp1.ids, []) && !rc3.ok && codes(rc3)[0] === 'unknown_checkpoint'
    && rd.ok && rd.data.redone === 3 && same(afterRedo, R2s) && R2s.ids.length === 7);
  void R1s;
  await A('closeDocument', { doc: R, discardChanges: true });

  // --- IDs survive undo/redo; no duplicate IDs after restore (R1..R5, undo, redo, add -> R6)
  const J = (await A('createDocument', {})).data.doc;
  await A('importCircuit', { doc: J, circuit: resistorJson(['R1', 'R2', 'R3', 'R4', 'R5']) });
  const J1 = await state(J);
  await A('checkpoint', { doc: J, comment: 'import' });
  await A('applyEdits', { doc: J, edits: [{ op: 'set', id: 'R2', properties: { resistance: '3k' } }] });
  const J2 = await state(J);
  await A('checkpoint', { doc: J, comment: 'set' });
  // a load that throws inside undo/redo puts the stacks and the document back
  const jd0 = await docState(J);
  await s.eval(`CircuitJS1Agent.debugFailNextUndoLoad(); true`);
  const fu = await A('undo', { doc: J });
  const jd1 = await docState(J);
  const J2f = await state(J);
  await s.call('closeDialogs');
  ck('failedUndoLoadRestores', !fu.ok && codes(fu)[0] === 'internal_error' && /debugFailNextUndoLoad/.test(fu.issues[0].message)
    && same(J2f, J2) && jd1.undo === jd0.undo && jd1.redo === jd0.redo && (await hist(J)).undo[0].comment === 'set');
  const idSeq = [];
  await A('undo', { doc: J }); idSeq.push((await state(J)).ids);
  await A('redo', { doc: J }); idSeq.push((await state(J)).ids);
  await A('undo', { doc: J, steps: 2 }); idSeq.push((await state(J)).ids);
  await A('redo', { doc: J, steps: 2 }); const J2b = await state(J); idSeq.push(J2b.ids);
  const addR = await A('applyEdits', { doc: J, edits: [add(null, 0, 8)] });
  const five = ['R1', 'R2', 'R3', 'R4', 'R5'];
  out.notes.ids = { idSeq, created: addR.data && addR.data.created };
  ck('idsSurviveUndoRedo', same(J1.ids, five) && same(J2.ids, five) && same(idSeq, [five, five, [], five]) && same(J2b, J2));
  ck('noDuplicateIdsAfterRestore', addR.ok && same(addR.data.created, ['R6']));
  await A('closeDocument', { doc: J, discardChanges: true });

  // --- Grid preference of other tabs has no effect (undo/redo part): Small Grid on in the visible tab
  const OPTS_SMALL = '$ 3 0.000005 10.20027730826997 50 5 50 5e-11\n';
  const G = (await A('createDocument', {})).data.doc;
  await s.call('importText', OPTS_SMALL + 'r 64 64 128 64 0 1000\n');
  const visSmall = await s.call('visibleTab');
  const potRec = async () => recs(await A('getCircuit', { doc: G, detail: 'full' })).find((e) => e.type === 'Potentiometer');
  const potAdd = await A('applyEdits', { doc: G, edits: [{ op: 'add', element: { id: 'P1', type: 'Potentiometer', start: { x: 0, y: 0 }, end: { x: 3, y: 0 } } }] });
  const postsAdd = recs(potAdd)[0].posts.map((p) => p.at);
  await A('checkpoint', { doc: G, comment: 'pot' });
  await A('applyEdits', { doc: G, edits: [add('R1', 0, 6)] });
  await A('undo', { doc: G });
  const postsUndo = (await potRec()).posts.map((p) => p.at);
  await A('redo', { doc: G });
  const postsRedo = (await potRec()).posts.map((p) => p.at);
  out.notes.grid = { postsAdd, postsUndo, postsRedo };
  ck('gridUndoRedoKeepsPosts', potAdd.ok && same(postsUndo, postsAdd) && same(postsRedo, postsAdd)
    && same(await s.call('visibleTab'), visSmall));
  await A('closeDocument', { doc: G, discardChanges: true });
  await s.call('loadExample', 'lrc.txt');

  // --- Transformer endpoints survive a text reload by undo/redo (plan backlog item, agent undo)
  const T = (await A('createDocument', {})).data.doc;
  const trRec = async (doc) => { const e = recs(await A('getCircuit', { doc, detail: 'full' })).find((x) => x.id === 'T1'); return e && { start: e.start, end: e.end, posts: e.posts.map((p) => p.at) }; };
  await A('applyEdits', { doc: T, edits: [{ op: 'add', element: { id: 'T1', type: 'Transformer', start: { x: 0, y: 0 } } }] });
  const t0 = await trRec(T);
  await A('checkpoint', { doc: T, comment: 'transformer' });
  await A('applyEdits', { doc: T, edits: [add('R1', 0, 10)] });
  await A('undo', { doc: T });
  const tU = await trRec(T);
  await A('redo', { doc: T });
  const tR = await trRec(T);
  out.notes.transformer = { t0, tU, tR };
  ck('transformerAgentUndoExact', t0 && same(tU, t0) && same(tR, t0));
  await A('closeDocument', { doc: T, discardChanges: true });

  // --- idle seal (injectable timeout), and a closed document's idle timer is harmless
  await s.eval(`CircuitJS1Agent.debugSetIdleSealMs(400); true`);
  const I = (await A('createDocument', {})).data.doc;
  await A('applyEdits', { doc: I, edits: [add('R1', 0, 0)] });
  const iOpen = (await hist(I)).openTransaction;
  await sleep(1000);
  const hi = await hist(I);
  const Z = (await A('createDocument', {})).data.doc;
  await A('applyEdits', { doc: Z, edits: [add('R1', 0, 0)] });
  await A('closeDocument', { doc: Z, discardChanges: true });
  await sleep(700);
  await s.eval(`CircuitJS1Agent.debugSetIdleSealMs(0); true`);
  out.notes.idle = hi;
  ck('idleSeal', iOpen === true && hi.openTransaction === false && hi.undo[0].comment === 'agent edits (auto)' && hi.undo[0].auto === true
    && hi.undo[0].checkpointId === 'cp1');
  await A('closeDocument', { doc: I, discardChanges: true });

  // --- visible document V: user undoes agent work, user edit vs agent entry, saves, modified flag
  const V = (await A('createDocument', { title: 'History V', activate: true })).data.doc;
  await sleep(300);
  const words = { Undo: menuTexts('Undo'), Redo: menuTexts('Redo') };
  const labelWords = [...words.Undo, ...words.Redo];
  const menuLabels = async () => {
    await s.call('clickMenuPath', [menuTexts('Edit')]);
    const t0 = await s.eval(`Array.from(document.querySelectorAll('.gwt-MenuItem')).filter((e) => e.offsetWidth > 0)
      .map((e) => e.textContent.replace(/\\s+/g, ' ').trim()).filter((t) => ${JSON.stringify(labelWords)}.some((w) => t.startsWith(w)))`);
    // normalise the translated action word to English for the checks
    const labels = t0.map((x) => { for (const [en, list] of Object.entries(words)) for (const w of list) if (x.startsWith(w)) return en + x.slice(w.length); return x; });
    await s.key('Escape');
    await s.eval(`document.body.dispatchEvent(new MouseEvent('mousedown', { bubbles: true })); document.body.dispatchEvent(new MouseEvent('mouseup', { bubbles: true })); true`);
    await sleep(200);
    return labels;
  };
  await A('importCircuit', { doc: V, circuit: { elements: RC_CELLS } });
  await A('checkpoint', { doc: V, comment: 'add stage 1' });
  const VS1 = await state(V);
  await A('applyEdits', { doc: V, edits: [add('R3', 8, 0)] });
  await A('checkpoint', { doc: V, comment: 'add stage 2' });
  const VS2 = await state(V);
  const lab1 = await menuLabels();
  await s.call('focus');
  await s.key('KeyZ', { ctrl: true });
  const afterUserUndo = await state(V);
  const hv = await hist(V);
  const lab2 = await menuLabels();
  out.notes.userUndo = { lab1, lab2, hv };
  ck('userUndoesAgentWork', lab1.some((t) => t.startsWith('Undo: add stage 2')) && same(afterUserUndo, VS1)
    && hv.undo[0].checkpointId === 'cp1' && hv.redo[0].comment === 'add stage 2' && hv.redo[0].checkpointId === 'cp2'
    && lab2.some((t) => t.startsWith('Undo: add stage 1')) && lab2.some((t) => t.startsWith('Redo: add stage 2')));
  await s.call('focus');
  await s.key('KeyY', { ctrl: true });
  ck('userRedoAgentWork', same(await state(V), VS2));

  // A user edit never merges into an agent entry: agent edit -> user delete (real key) -> one Ctrl+Z
  await A('applyEdits', { doc: V, edits: [add('R4', 8, 4)] });
  const VSA = await state(V);
  await s.call('focus');
  await s.call('select', 'R4', false);
  await s.key('Delete');
  const afterUserDelete = await state(V);
  const hd = await hist(V);
  await s.call('focus');
  await s.key('KeyZ', { ctrl: true });
  const afterOneUndo = await state(V);
  out.notes.userEdit = { hd, afterUserDelete: afterUserDelete.ids };
  ck('userEditNeverMerges', afterUserDelete.ids.length === VSA.ids.length - 1 && hd.openTransaction === false
    && hd.undo[0].kind === 'user' && hd.undo[0].comment === undefined && hd.undo[1].comment === 'agent edits (auto)' && hd.undo[1].auto === true
    && same(afterOneUndo, VSA));

  // A press that changes nothing (select click) does not seal: one checkpoint covers both agent edits
  const cr = await s.call('canvasRect');
  const cx = Math.round(cr.x + cr.w * 0.8), cy = Math.round(cr.y + cr.h * 0.3);
  const VB = await state(V);
  await A('applyEdits', { doc: V, edits: [add('RX1', 30, 0)] });
  await s.call('focus');
  await s.key('Escape');
  await s.mouseDrag(cx, cy, cx, cy);
  const hClick = await hist(V);
  const a2 = await A('applyEdits', { doc: V, edits: [add('RX2', 30, 2)] });
  const cpTwo = await A('checkpoint', { doc: V, comment: 'two edits' });
  const rcTwo = await A('restoreCheckpoint', { doc: V, checkpointId: cpTwo.data && cpTwo.data.checkpointId });
  const afterTwo = await state(V);
  out.notes.selectClick = { hClick, a2: tx(a2), cpTwo: cpTwo.data, rcTwo: rcTwo.data };
  ck('selectClickKeepsTransaction', hClick.openTransaction === true && same(tx(a2), { open: true, pendingEdits: 2 })
    && rcTwo.ok && rcTwo.data.undone === 1 && same(afterTwo, VB));
  await A('redo', { doc: V });
  // A real drag (placing a resistor) seals: the agent entry sits below the user's entries
  await A('applyEdits', { doc: V, edits: [add('RX3', 30, 4)] });
  const VC = await state(V);
  const placeResistor = async (y) => {
    await s.call('focus'); await s.key('Escape'); await s.typeChar('r');
    await s.mouseDrag(cx, y, cx + 96, y);
    await s.typeChar(' ');
  };
  await placeResistor(cy);
  const hDrag = await hist(V);
  const placed = await state(V);
  await s.call('focus');
  await s.key('KeyZ', { ctrl: true });
  await s.key('KeyZ', { ctrl: true });
  const afterDragUndo = await state(V);
  const autoAt = (h) => h.undo.findIndex((e) => e.comment === 'agent edits (auto)');
  out.notes.drag = { hDrag, placed: placed.ids, afterDragUndo: afterDragUndo.ids };
  ck('realDragSeals', placed.ids.length === VC.ids.length + 1 && hDrag.openTransaction === false && autoAt(hDrag) >= 1
    && hDrag.undo.slice(0, autoAt(hDrag)).every((e) => e.kind === 'user' && e.comment === undefined) && same(afterDragUndo, VC));
  // A transaction with no net change, then a user edit: the user edit gets its own entry and label
  const rx1 = recs(await A('getCircuit', { doc: V, ids: ['RX1'], detail: 'full' }))[0];
  await A('applyEdits', { doc: V, edits: [{ op: 'set', id: 'RX1', properties: { resistance: '2k' } }] });
  await A('applyEdits', { doc: V, edits: [{ op: 'set', id: 'RX1', properties: { resistance: rx1.properties.resistance } }] });
  const SN = await state(V);
  const autoCount = (h) => h.undo.filter((e) => e.comment === 'agent edits (auto)').length;
  const autoBefore = autoCount(await hist(V, 150));
  await placeResistor(cy + 64);
  const hNil = await hist(V, 150);
  const labNil = await menuLabels();
  await s.call('focus');
  await s.key('KeyZ', { ctrl: true });
  await s.key('KeyZ', { ctrl: true });
  const afterNil = await state(V);
  out.notes.nil = { labNil, autoBefore, autoAfter: autoCount(hNil), top: hNil.undo.slice(0, 3) };
  ck('nilTransactionUserEditOwnEntry', same(SN, VC) && hNil.openTransaction === false && autoCount(hNil) === autoBefore
    && hNil.undo[0].kind === 'user' && hNil.undo[1].kind === 'user' && labNil.some((t) => /^Undo(?!:)/.test(t)) && same(afterNil, SN));
  // The same when the nil transaction was sealed earlier (idle timer, user save), not by the user's push
  const rx1r = rx1.properties.resistance;
  const nilSealedThen = async (seal, userEdit) => {
    const base = await state(V);
    const autoB = autoCount(await hist(V, 150));
    await A('applyEdits', { doc: V, edits: [{ op: 'set', id: 'RX1', properties: { resistance: '2k' } }] });
    await A('applyEdits', { doc: V, edits: [{ op: 'set', id: 'RX1', properties: { resistance: rx1r } }] });
    await seal();
    const hSealed = await hist(V, 150);
    await userEdit();
    const edited = await state(V);
    const hAfter = await hist(V, 150);
    const lab = await menuLabels();
    await s.call('focus');
    await s.key('KeyZ', { ctrl: true });
    const after = await state(V);
    const hUndone = await hist(V, 150);
    const r = { autoB, sealed: hSealed.undo.slice(0, 2), sealedOpen: hSealed.openTransaction, after: hAfter.undo.slice(0, 2), autoAfter: autoCount(hAfter), lab, redo0: hUndone.redo[0] };
    // the seal happened before the user edit and named the nil entry
    r.ok = hSealed.openTransaction === false && hSealed.undo[0].comment === 'agent edits (auto)' && autoCount(hSealed) === autoB + 1
      // the user edit changed the circuit, got its own entry and label, and the nil entry is gone
      && edited.text !== base.text && hAfter.undo[0].kind === 'user' && hAfter.undo[0].comment === undefined && autoCount(hAfter) === autoB
      && lab.some((t) => /^Undo(?!:)/.test(t)) && !lab.some((t) => t.startsWith('Undo: agent edits'))
      // one Ctrl+Z reverts only the user edit, and the redo entry is the user's
      && same(after, base) && hUndone.redo[0] && hUndone.redo[0].kind === 'user';
    return r;
  };
  const nilIdle = await nilSealedThen(async () => {
    await s.eval(`CircuitJS1Agent.debugSetIdleSealMs(300); true`);
    // the idle time applies to transactions that continue afterwards: one more nil edit
    await A('applyEdits', { doc: V, edits: [{ op: 'set', id: 'RX1', properties: { resistance: rx1r } }] });
    await sleep(900);
    await s.eval(`CircuitJS1Agent.debugSetIdleSealMs(0); true`);
  }, async () => { await s.call('focus'); await s.call('select', 'RX2', false); await s.key('Delete'); });
  const nilSave = await nilSealedThen(async () => {
    await s.call('focus'); await s.key('KeyS', { ctrl: true }); await sleep(200); await s.call('closeDialogs');
  }, async () => { await s.call('focus'); await s.call('select', 'RX2', false); await s.key('Delete'); });
  out.notes.nilSealedEarlier = { nilIdle, nilSave };
  ck('nilSealedEarlierUserEditOwnEntry', nilIdle.ok && nilSave.ok);


  // Transformer endpoints survive a user undo (entry pushed by the user's delete)
  await A('applyEdits', { doc: V, edits: [{ op: 'add', element: { id: 'T1', type: 'Transformer', start: { x: 16, y: 0 } } }] });
  const vt0 = await trRec(V);
  await s.call('focus');
  await s.call('select', 'R1', false);
  await s.key('Delete');
  await s.call('focus');
  await s.key('KeyZ', { ctrl: true });
  const vtU = await trRec(V);
  out.notes.userTransformer = { vt0, vtU };
  ck('transformerUserUndoExact', vt0 && same(vtU, vt0) && (await state(V)).ids.includes('R1'));

  // A user save seals; agent undo/redo/restore set the modified flag
  const save = async () => { await s.call('focus'); await s.key('KeyS', { ctrl: true }); await sleep(200); return (await docState(V)).modified; };
  await A('applyEdits', { doc: V, edits: [add('R5', 8, 8)] });
  const m0 = await save();
  const hs = await hist(V);
  const mu = (await A('undo', { doc: V })).ok && (await docState(V)).modified;
  const m1 = await save();
  const mr = (await A('redo', { doc: V })).ok && (await docState(V)).modified;
  const m2 = await save();
  const mc = (await A('restoreCheckpoint', { doc: V, checkpointId: 'cp1' })).ok && (await docState(V)).modified;
  out.notes.modified = { m0, m1, m2, mu, mr, mc, hs };
  ck('userSaveSeals', m0 === false && hs.openTransaction === false && hs.undo[0].comment === 'agent edits (auto)');
  ck('modifiedAfterUndoRedoRestore', m1 === false && m2 === false && mu === true && mr === true && mc === true);
  await A('closeDocument', { doc: V, discardChanges: true });

  // An agent call while the user holds the mouse button in a move-drag: the drag before the call,
  // the agent edit and the drag after the call are three undo entries (with and without an agent
  // transaction open at the press). A fresh visible document; screen points from its transform.
  const D = (await A('createDocument', { title: 'History D', activate: true })).data.doc;
  await sleep(300);
  const raw = (type, x, y, extra = {}) => s.cdp.send('Input.dispatchMouseEvent', { type, x, y, button: 'left', ...extra });
  const dcr = await s.call('canvasRect');
  const view = (await s.call('visibleTab')).view;
  const k = dcr.w / view.canvas.width, t = view.transform;
  const toScreen = (gx, gy) => ({ x: dcr.x + (t[0] * gx + t[4]) * k, y: dcr.y + (t[3] * gy + t[5]) * k });
  const toCell = (sx, sy) => ({ x: Math.round(((sx - dcr.x) / k - t[4]) / t[0] / 16), y: Math.round(((sy - dcr.y) / k - t[5]) / t[3] / 16) });
  const heldDrag = async (fx, withTx, moveId, agentId) => {
    const c = toCell(dcr.x + dcr.w * fx, dcr.y + dcr.h * 0.35);
    await A('applyEdits', { doc: D, edits: [add(moveId, c.x, c.y)] });
    if (!withTx) await A('checkpoint', { doc: D, comment: 'place ' + moveId });
    const tx0 = (await hist(D)).openTransaction;
    const P = await state(D);
    const at = toScreen((c.x + 2) * 16, c.y * 16); // the body of the resistor
    await s.call('focus'); await s.key('Escape'); await s.typeChar(' ');
    await raw('mouseMoved', at.x, at.y, { button: 'none' }); await sleep(100);
    await raw('mousePressed', at.x, at.y, { clickCount: 1, buttons: 1 });
    // move until the element has moved (the editor snaps to the grid)
    let dy = 0, P1 = P;
    for (let n = 0; n < 8 && (n < 3 || P1.text === P.text); n++) { dy += 16; await raw('mouseMoved', at.x, at.y + dy, { buttons: 1 }); await sleep(80); P1 = await state(D); }
    const ag = await A('applyEdits', { doc: D, edits: [add(agentId, c.x, c.y - 6)] });
    const Q = await state(D);
    let F = Q;
    for (let n = 0; n < 8 && (n < 3 || F.text === Q.text); n++) { dy += 16; await raw('mouseMoved', at.x, at.y + dy, { buttons: 1 }); await sleep(80); F = await state(D); }
    await raw('mouseReleased', at.x, at.y + dy, { clickCount: 1 });
    await sleep(200);
    const hF = await hist(D);
    await s.call('focus');
    await s.key('KeyZ', { ctrl: true });
    const U1 = await state(D);
    const hU1 = await hist(D);
    await s.key('KeyZ', { ctrl: true });
    const U2 = await state(D);
    const r = { withTx, cell: c, at, dy, tx0, ag: tx(ag), hF: hF.undo.slice(0, 3), hFopen: hF.openTransaction, hU1: hU1.undo.slice(0, 2) };
    r.conds = {
      setup: tx0 === withTx && ag.ok,
      // the drag moved the element before and after the agent call
      dragBefore: P1.text !== P.text, agentEdit: Q.ids.includes(agentId) && !P1.ids.includes(agentId), dragAfter: F.text !== Q.text,
      // the drag after the call sealed the agent's transaction and got its own user entry
      ownEntry: hF.openTransaction === false && hF.undo[0].kind === 'user' && hF.undo[1].comment === 'agent edits (auto)',
      // Ctrl+Z reverts only the rest of the drag (agent edit kept), the next one only the agent edit
      undo1: same(U1, Q) && hU1.undo[0].comment === 'agent edits (auto)', undo2: same(U2, P1),
    };
    r.ok = Object.values(r.conds).every(Boolean);
    if (!r.ok) r.texts = { P: P.text, P1: P1.text, Q: Q.text, F: F.text, U1: U1.text, U2: U2.text };
    return r;
  };
  const dragTx = await heldDrag(0.3, true, 'RM1', 'RY1');
  const dragNoTx = await heldDrag(0.6, false, 'RM2', 'RY2');
  out.notes.heldDrag = { dragTx, dragNoTx };
  ck('agentCallDuringDragSplitsGesture', dragTx.ok && dragNoTx.ok);
  // A rejected agent call during a drag with no transaction open leaves the drag one undo step
  {
    const c = toCell(dcr.x + dcr.w * 0.45, dcr.y + dcr.h * 0.7);
    await A('applyEdits', { doc: D, edits: [add('RM3', c.x, c.y)] });
    await A('checkpoint', { doc: D, comment: 'place RM3' });
    const tx0 = (await hist(D)).openTransaction;
    const P = await state(D);
    const at = toScreen((c.x + 2) * 16, c.y * 16);
    await s.call('focus'); await s.key('Escape'); await s.typeChar(' ');
    await raw('mouseMoved', at.x, at.y, { button: 'none' }); await sleep(100);
    await raw('mousePressed', at.x, at.y, { clickCount: 1, buttons: 1 });
    let dy = 0, P1 = P;
    for (let n = 0; n < 8 && (n < 3 || P1.text === P.text); n++) { dy += 16; await raw('mouseMoved', at.x, at.y + dy, { buttons: 1 }); await sleep(80); P1 = await state(D); }
    const rej = await A('applyEdits', { doc: D, edits: [{ op: 'delete', id: 'NOPE' }] });
    let F = P1;
    for (let n = 0; n < 8 && (n < 3 || F.text === P1.text); n++) { dy += 16; await raw('mouseMoved', at.x, at.y + dy, { buttons: 1 }); await sleep(80); F = await state(D); }
    await raw('mouseReleased', at.x, at.y + dy, { clickCount: 1 });
    await sleep(200);
    const hF = await hist(D);
    await s.call('focus');
    await s.key('KeyZ', { ctrl: true });
    const U1 = await state(D);
    const conds = { setup: tx0 === false && !rej.ok && same(tx(rej), { open: false, pendingEdits: 0 }),
      moved: P1.text !== P.text && F.text !== P1.text, userTop: hF.openTransaction === false && hF.undo[0].kind === 'user',
      oneUndo: same(U1, P) };
    out.notes.rejectedDuringDrag = { conds, hF: hF.undo.slice(0, 3) };
    ck('rejectedCallDuringDragOneStep', Object.values(conds).every(Boolean));
  }
  await A('activateDocument', { doc: A0 });
  await A('closeDocument', { doc: D, discardChanges: true });
  await A('closeDocument', { doc: H, discardChanges: true });

  out.notes.visibleFailed = visFailed;
  ck('backgroundHistoryKeepsVisibleTab', visFailed.length === 0);
  ck('noPageExceptions', s.exceptions.length === exMark);
  if (s.exceptions.length !== exMark) out.exceptions = s.exceptions.slice(exMark).map((e) => e.slice(0, 600));
  fs.writeFileSync(path.join(OUT_DIR, 'agent_history.json'), JSON.stringify(out, null, 2));
  const failed = Object.keys(out.checks).filter((n) => !out.checks[n]);
  report('AG.agent_history', failed.length === 0, { checks: Object.keys(out.checks).length, failed, details: path.join(OUT_DIR, 'agent_history.json') });
}

// ---------------------------------------------------------------- main
// [PL_AGA_P8] Background-document completion and render (SP_AGA_03_08, SP_AGA_02_08):
// SP_AGA_05_02 R1 and R2 (without the openFile step, Phase 9), "Background operations never switch
// tabs"; SP_AGA_05_01 rows `run` background document and `render` png background; SP_AGA_05_03
// "Agent builds while user watches"; plus render arguments, render_failed without an alert, a
// render served while busy, and a pixel comparison of the visible tab around offscreen renders.
// Reference fixture of the visible tab: a 10-element RC circuit with its own small grid, sliders,
// hint, scopes and bar settings.
const R1_REF_FIXTURE = '$ 3 0.000005 14.235633750745258 37 7 43 5e-11\n' +
  'r 176 80 384 80 0 10\ns 384 80 448 80 0 1 false\nw 176 80 176 352 0\nc 384 352 176 352 0 0.000015 -9.86 -10\n' +
  'l 384 80 384 352 0 1 0.03 0\nv 448 352 448 80 0 0 40 5 0 0 0.5\nr 384 352 448 352 0 100\n' +
  'r 448 80 528 80 0 220\nc 528 80 528 352 0 0.00001 0 0\nw 528 352 448 352 0\n' +
  'o 4 64 0 4099 20 0.05 0 2 4 3\no 3 64 0 4099 20 0.05 1 2 3 3\n' +
  '38 3 0 0.000001 0.000101 Capacitance\n38 4 0 0.01 1.01 Inductance\n38 0 0 1 101 Resistance\nh 1 4 3\n';
// Background fixture: a header that differs from the reference in every option, its own hint,
// sliders (a pot and an adjustable) and a scope.
const R1_BG_FIXTURE = '$ 12 0.00001 5.0 60 3 70 1e-10\n' +
  '174 320 352 384 96 1 1000.0 0.5 Resistance\nv 240 352 240 96 0 0 40.0 5.0 0.0 0.0 0.5\nw 240 96 320 96 0\nw 240 352 320 352 0\n' +
  'r 384 224 480 224 0 470\nc 480 224 480 352 0 0.000001 0 0\nw 480 352 320 352 0\ng 320 352 320 400 0 0\n' +
  'o 5 64 0 4099 5 0.05 0 2 5 3\n38 4 0 10 1000 Rload\nh 2 4 5\n';
async function scenarioAgentBackground(s) {
  const out = { checks: {}, notes: {} };
  const ck = (name, cond) => { out.checks[name] = !!cond; return !!cond; };
  const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
  const A = (op, args) => s.call('agentCall', op, args);
  const AA = (op, args, timeoutMs) => s.call('agentAsync', op, args, timeoutMs || 30000);
  const codes = (r) => ((r && r.issues) || []).map((i) => i.code);
  // first differing path of two values (for the notes)
  const firstDiff = (a, b, p = '') => {
    if (same(a, b)) return null;
    if (a && b && typeof a === 'object' && typeof b === 'object') {
      for (const k of new Set([...Object.keys(a), ...Object.keys(b)])) { const d = firstDiff(a[k], b[k], p + '.' + k); if (d) return d; }
    }
    return { path: p, a: JSON.stringify(a).slice(0, 200), b: JSON.stringify(b).slice(0, 200) };
  };
  try {
  await resetApp(s);
  const exMark = s.exceptions.length;
  const alertMark = s.dialogs.length;
  await s.eval(`CircuitJS1.setSimRunning(false); true`);
  // ---------------------------------------------------------------- R1 (active tab free-running)
  await s.call('importText', R1_REF_FIXTURE);
  await sleep(300);
  const V = (await A('listDocuments', {})).data.documents.find((d) => d.active).doc;
  await s.eval(`CircuitJS1.setSimRunning(true); true`);
  await sleep(600);
  const base = await s.call('r1Sample');
  out.notes.base = { options: base.vis.options, sliders: base.vis.sliders, hint: base.vis.view.hint, smallGrid: base.session.checks.smallGrid, bars: base.session.bars };
  ck('refFixture', base.vis.count === 10 && base.session.checks.smallGrid === true && base.vis.sliders.sliders === 3 && base.vis.view.hint === '1 4 3' && base.running === true);
  await sleep(500);
  ck('baselineStable', same(await s.call('r1Sample'), base));
  const samples = [];
  const sample = async (label) => { samples.push({ label, sample: await s.call('r1Sample') }); };
  const observed = await s.call('startSlidersObserver');
  // idle baseline of the visible tab's simulated time per wall second
  const rate = async (ms) => { const t0 = await s.call('simTime'); const w0 = Date.now(); await sleep(ms); return ((await s.call('simTime')) - t0) / ((Date.now() - w0) / 1000); };
  const idleRate = await rate(2000);
  // the sequence on background document X
  const X = (await A('createDocument', { title: 'R1 X' })).data.doc;
  await sample('createDocument');
  const imp = await A('importCircuit', { doc: X, format: 'text', circuit: R1_BG_FIXTURE });
  await sample('importCircuit');
  const cp = await A('checkpoint', { doc: X, comment: 'imported' });
  await sample('checkpoint');
  const ed = await A('applyEdits', { doc: X, edits: [{ op: 'add', element: { id: 'R9', type: 'Resistor', start: { x: 40, y: 0 }, end: { x: 44, y: 0 } } }] });
  await sample('applyEdits');
  const un = await A('undo', { doc: X });
  await sample('undo');
  ck('sequenceOk', imp.ok && cp.ok && ed.ok && un.ok);
  await s.call('startSliceProbe');
  const tRun0 = await s.call('simTime'); const wRun0 = Date.now();
  const run = await AA('run', { doc: X, span: 1000, budgetMs: 2000, probes: [{ element: 'C1' }] });
  const runRate = ((await s.call('simTime')) - tRun0) / ((Date.now() - wRun0) / 1000);
  out.notes.run = { ok: run.ok, reason: run.data && run.data.reason, steps: run.data && run.data.steps, wallMs: run.data && run.data.wallMs, issues: codes(run) };
  await sample('run');
  const png = await AA('render', { doc: X });
  await sample('render png');
  const svg = await AA('render', { doc: X, format: 'svg', includeScopes: true });
  await sample('render svg');
  const slices = await s.call('stopSliceProbe');
  const exT = await A('exportCircuit', { doc: X, format: 'text' });
  await sample('exportCircuit text');
  const exJ = await A('exportCircuit', { doc: X, format: 'json' });
  await sample('exportCircuit json');
  // "Agent builds while user watches": X holds the circuit and its run results
  const xCirc = await A('getCircuit', { doc: X });
  ck('agentBuildsWhileUserWatches', run.ok && run.data && run.data.reason === 'budget_exhausted' && run.data.steps > 0
    && run.data.probes && run.data.probes[0].stats && run.data.probes[0].stats.samples > 1 && xCirc.ok && xCirc.data.elements.length === 8);
  const cl = await A('closeDocument', { doc: X, discardChanges: true });
  await sample('closeDocument');
  const mut = await s.call('stopSlidersObserver');
  ck('asyncOk', png.ok && svg.ok && exT.ok && exJ.ok && cl.ok);
  // every sample equals the pre-call state (synchronous calls and every slice boundary)
  // the tab count follows X (one more tab from createDocument to closeDocument); all else is equal
  const bad = [];
  const norm = (x) => ({ ...x, vis: { ...x.vis, tabCount: 0 } });
  const tabs = (label) => base.vis.tabCount + (label === 'closeDocument' ? 0 : 1);
  // the tab count differs by design: report the first other difference (or the tab count)
  for (const x of samples) if (!same(norm(x.sample), norm(base)) || x.sample.vis.tabCount !== tabs(x.label)) bad.push({ label: x.label, diff: firstDiff(norm(x.sample), norm(base)) || { tabCount: x.sample.vis.tabCount, want: tabs(x.label) } });
  for (const x of slices) if (!same(norm(x.sample), norm(base)) || x.sample.vis.tabCount !== base.vis.tabCount + 1) bad.push({ label: x.op + ' slice', diff: firstDiff(x.sample, base) });
  out.notes.r1Disturbed = bad.slice(0, 8);
  ck('r1EverySampleUnchanged', bad.length === 0 && samples.length === 11);
  ck('r1SlidersNotRebuilt', observed && mut && mut.count === 0);
  out.notes.sliderMutations = mut;
  // timing: slices, frames between slices, rate
  const runSlices = slices.filter((x) => x.op === 'run');
  const renderSlices = slices.filter((x) => x.op === 'render');
  const ms = slices.map((x) => x.ms);
  const stepMs = run.data && run.data.steps ? (runSlices.reduce((a, x) => a + x.ms, 0) / run.data.steps) : 0;
  let noFrame = 0;
  for (let i = 1; i < slices.length; i++) if (!(slices[i].visT > slices[i - 1].visT)) noFrame++;
  out.notes.timing = {
    idleRate, runRate, rateRatio: runRate / idleRate, slices: slices.length, runSlices: runSlices.length, renderSlices: renderSlices.length,
    maxSliceMs: Math.max(...ms), meanSliceMs: ms.reduce((a, b) => a + b, 0) / ms.length, maxRenderSliceMs: Math.max(...renderSlices.map((x) => x.ms)),
    over20: slices.filter((x) => x.ms > 20).map((x) => ({ op: x.op, ms: x.ms, index: slices.indexOf(x) })), stepMs, msList: ms.map((x) => +x.toFixed(1)), steps: run.data && run.data.steps, slicesWithoutFrameBefore: noFrame,
  };
  ck('r1RateAtLeastHalf', idleRate > 0 && runRate / idleRate >= 0.5);
  // R1 bound: 20 ms plus one indivisible unit (a timestep for runs); SLICE_JITTER_MS absorbs GC pauses and
  // timer jitter of the headless measurement (slices aim at 15 ms of work for runs, 10 ms for renders)
  ck('r1SliceBound', slices.length > 10 && Math.max(...ms) <= 20 + stepMs + SLICE_JITTER_MS);
  ck('r1FrameBetweenSlices', noFrame === 0);
  // SP_AGA_05_01 run background document: completes, active tab unchanged (also every slice)
  ck('runBackgroundDocument', run.ok && run.data.reason === 'budget_exhausted' && bad.filter((b) => /run/.test(b.label)).length === 0);
  // Background operations never switch tabs (edit, run, render): the visible tab stayed V
  ck('backgroundNeverSwitchesTabs', samples.every((x) => x.sample.vis.activeTitle === base.vis.activeTitle)
    && (await A('listDocuments', {})).data.documents.find((d) => d.active).doc === V);
  // SP_AGA_05_01 render png background: width/height cover the circuit bounds + 1-cell margin
  const ext = (els) => {
    const pts = els.flatMap((e) => [e.start, e.end].filter(Boolean));
    const xs = pts.map((p) => p.x); const ys = pts.map((p) => p.y);
    return { w: Math.max(...xs) - Math.min(...xs), h: Math.max(...ys) - Math.min(...ys) };
  };
  const e = ext(xCirc.data.elements);
  out.notes.renderPng = png.data && { width: png.data.width, height: png.data.height, cellsW: e.w, cellsH: e.h, bytes: png.data.content.length };
  ck('renderPngBackground', png.ok && png.data.format === 'png' && png.data.width >= (e.w + 2) * 16 && png.data.height >= (e.h + 2) * 16
    && png.data.width <= (e.w + 2) * 16 + 160 && png.data.height <= (e.h + 2) * 16 + 160 && /^iVBORw0KGgo/.test(png.data.content));
  ck('renderSvgBackground', svg.ok && svg.data.format === 'svg' && /^<svg/.test(svg.data.content) && svg.data.height > png.data.height);
  // ---------------------------------------------------------------- R1 with concurrent operations
  // Two background runs and renders at the same time: the session-wide slice queue releases at most
  // one slice per active-tab frame, round robin.
  {
    const C1 = (await A('createDocument', { title: 'R1 C1' })).data.doc;
    const C2 = (await A('createDocument', { title: 'R1 C2' })).data.doc;
    await A('importCircuit', { doc: C1, format: 'text', circuit: R1_BG_FIXTURE });
    await A('importCircuit', { doc: C2, format: 'text', circuit: R1_BG_FIXTURE });
    const baseC = await s.call('r1Sample');
    await s.call('startSliceProbe');
    const t0 = await s.call('simTime'); const w0 = Date.now();
    await s.call('agentStart', 'cc1', 'run', { doc: C1, span: 1000, budgetMs: 1500, probes: [{ element: 'C1' }] });
    await s.call('agentStart', 'cc2', 'run', { doc: C2, span: 1000, budgetMs: 1500 });
    const cr = [await AA('render', { doc: C1 }), await AA('render', { doc: C2, format: 'svg', includeScopes: true }), await AA('render', { doc: C1, scale: 2 })];
    await waitFor(async () => (await s.call('agentStarted', 'cc1')).calls === 1 && (await s.call('agentStarted', 'cc2')).calls === 1, 10000, 'concurrent runs');
    const ccRate = ((await s.call('simTime')) - t0) / ((Date.now() - w0) / 1000);
    const cs = await s.call('stopSliceProbe');
    const r1c = (await s.call('agentStarted', 'cc1')).result; const r2c = (await s.call('agentStarted', 'cc2')).result;
    let ccNoFrame = 0;
    for (let i = 1; i < cs.length; i++) if (!(cs[i].visT > cs[i - 1].visT)) ccNoFrame++;
    const ccStep = Math.max(r1c.data.steps ? cs.filter((x) => x.op === 'run' && x.doc === C1).reduce((a, x) => a + x.ms, 0) / r1c.data.steps : 0,
      r2c.data.steps ? cs.filter((x) => x.op === 'run' && x.doc === C2).reduce((a, x) => a + x.ms, 0) / r2c.data.steps : 0);
    const ccBad = cs.filter((x) => !same(norm(x.sample), norm(baseC)) || x.sample.vis.tabCount !== baseC.vis.tabCount);
    // interleaving: switches between operations (doc+op) in the slice order
    let switches = 0;
    for (let i = 1; i < cs.length; i++) if (cs[i].doc + cs[i].op !== cs[i - 1].doc + cs[i - 1].op) switches++;
    const ccMs = cs.map((x) => x.ms);
    out.notes.concurrent = { slices: cs.length, run1: cs.filter((x) => x.op === 'run' && x.doc === C1).length, run2: cs.filter((x) => x.op === 'run' && x.doc === C2).length,
      render: cs.filter((x) => x.op === 'render').length, switches, noFrame: ccNoFrame, maxSliceMs: Math.max(...ccMs), stepMs: ccStep, rate: ccRate, rateRatio: ccRate / idleRate,
      runs: [r1c.data.reason, r2c.data.reason], disturbed: ccBad.slice(0, 3).map((x) => firstDiff(x.sample, baseC)) };
    ck('r1ConcurrentFrameBetweenSlices', cs.length > 20 && ccNoFrame === 0 && switches > 10);
    out.notes.concurrent.over20 = cs.filter((x) => x.ms > 20).map((x) => ({ op: x.op, doc: x.doc, ms: x.ms, index: cs.indexOf(x) }));
  ck('r1ConcurrentSliceBound', Math.max(...ccMs) <= 20 + ccStep + SLICE_JITTER_MS);
    ck('r1ConcurrentRate', ccRate / idleRate >= 0.5);
    ck('r1ConcurrentUnchanged', ccBad.length === 0 && cr.every((r) => r.ok) && r1c.ok && r2c.ok);
    await A('closeDocument', { doc: C1, discardChanges: true });
    await A('closeDocument', { doc: C2, discardChanges: true });
  }

  await s.eval(`CircuitJS1.setSimRunning(false); true`);

  // ---------------------------------------------------------------- R2 (X in background vs Y active)
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
    return r;
  };
  const r2State = async (d) => {
    const st = await s.call('docState', d);
    const text = (await A('exportCircuit', { doc: d, format: 'text' })).data.content;
    const listed = (await A('listDocuments', {})).data.documents.find((x) => x.doc === d);
    return { text, ui: st.ui, title: st.title, modified: st.modified, undo: st.undo, redo: st.redo, logCount: st.logCount, listedTitle: listed && listed.title, listedModified: listed && listed.modified };
  };
  const RX = (await A('createDocument', { title: 'R2' })).data.doc;
  const seqX = await seq(RX);
  const stX = await r2State(RX);
  const RY = (await A('createDocument', { title: 'R2' })).data.doc;
  await A('activateDocument', { doc: RY });
  await sleep(200);
  const seqY = await seq(RY);
  const stY = await r2State(RY);
  ck('r2SequencesOk', seqX.every((r) => r.ok) && seqY.every((r) => r.ok));
  out.notes.r2Seq = { x: seqX.map((r) => r.ok || codes(r)), y: seqY.map((r) => r.ok || codes(r)) };
  out.notes.r2Diff = firstDiff({ ...stX, text: null }, { ...stY, text: null });
  if (stX.text !== stY.text) out.notes.r2TextDiff = lineDiff(stX.text.split('\n'), stY.text.split('\n'));
  out.notes.r2X = { ui: stX.ui, title: stX.title, modified: stX.modified };
  // Known difference: the auto-range scales of a scope (fields 5 and 6 of an `o` line) are written by
  // the draws of the visible tab, so Y's change with its frames; a background document's scopes keep
  // theirs until drawn. Every other token must be equal.
  const maskScopeScales = (t) => t.split('\n').map((l) => { const f = l.split(' '); if (f[0] === 'o' && f.length > 6) { f[5] = f[6] = '*'; } return f.join(' '); }).join('\n');
  ck('r2CircuitText', maskScopeScales(stX.text) === maskScopeScales(stY.text));
  out.notes.r2ScopeScalesEqual = stX.text === stY.text;
  ck('r2UiState', same(stX.ui, stY.ui));
  ck('r2TitleModifiedPath', stX.title === stY.title && stX.modified === stY.modified && stX.modified === true && stX.ui.filePath === stY.ui.filePath);
  ck('r2UndoDepth', stX.undo === stY.undo && stX.redo === stY.redo);
  // The per-document log buffer is in the R2 list. Known difference (Phase 0): lines of the session
  // UI ("Save option: SlidersDialog.*", written when the visible tab's Sliders dialog is rebuilt)
  // land in the bound document, so only Y has them; every other line must be equal.
  const domainLogs = (l) => l.filter((x) => !/^Save option: SlidersDialog\./.test(x));
  const logsX = (await s.call('docState', RX)).logs; const logsY = (await s.call('docState', RY)).logs;
  ck('r2LogBuffer', same(domainLogs(logsX), domainLogs(logsY)) && domainLogs(logsX).length > 0);
  if (!same(logsX, logsY)) out.notes.r2Logs = { x: logsX, y: logsY };
  const closedBefore = (await s.call('closedTabs')).length;
  await A('closeDocument', { doc: RX, discardChanges: true });
  await A('closeDocument', { doc: RY, discardChanges: true });
  const closed = await s.call('closedTabs');
  // the closed-tab history keeps the newest 20 dumps (DocumentManager.MAX_CLOSED_TABS)
  ck('r2ClosedTabDump', closed.length === Math.min(20, closedBefore + 2) && maskScopeScales(closed[closed.length - 1]) === maskScopeScales(closed[closed.length - 2]));
  if (closed[closed.length - 1] !== closed[closed.length - 2]) out.notes.closedDiff = lineDiff(closed[closed.length - 2].split('\n'), closed[closed.length - 1].split('\n'));
  await A('activateDocument', { doc: V });
  await sleep(300);

  // ---------------------------------------------------------------- pixels of the visible tab
  // The visible tab is stopped, has a selection and a hint; offscreen renders of a background and
  // of the visible document itself must not change one pixel of its next frame.
  await s.call('select', 'R1', false);
  // background P first: its first scope exit re-derives the Edit items from the visible tab's
  // selection (the scripting select call leaves them stale), so the baseline below is current
  const P = (await A('createDocument', { title: 'Pixels' })).data.doc;
  await A('importCircuit', { doc: P, format: 'text', circuit: R1_BG_FIXTURE });
  await AA('run', { doc: P, span: '5 ms' });
  await sleep(200);
  const pixA = await s.call('canvasPixels');
  const pixB = await s.call('canvasPixels');
  const visSess0 = await s.call('r1Sample');
  const text0 = await s.call('exportText');
  const pr = [];
  pr.push(await AA('render', { doc: P, includeScopes: true }));
  pr.push(await AA('render', { doc: P, format: 'svg', includeScopes: true, scale: 0.5 }));
  pr.push(await AA('render', { includeScopes: true, scale: 2 }));
  pr.push(await AA('render', { format: 'svg', includeScopes: true }));
  const vis1 = await AA('render', {});
  const vis2 = await AA('render', {});
  await sleep(200);
  const pixC = await s.call('canvasPixels');
  ck('pixelControlStable', pixA === pixB);
  ck('pixelsUnchangedAfterRenders', pr.every((r) => r.ok) && pixC === pixA);
  const visSess1 = await s.call('r1Sample');
  const text1 = await s.call('exportText');
  const tabless = (x) => x;
  out.notes.sessionAfterRenders = { diff: firstDiff(tabless(visSess1), visSess0), text: text1 === text0 ? null : lineDiff(text0.split('\n'), text1.split('\n')) };
  ck('sessionUnchangedAfterRenders', same(tabless(visSess1), visSess0) && text1 === text0);
  ck('renderDeterministic', vis1.ok && vis2.ok && vis1.data.content === vis2.data.content);
  // the same with an in-circuit scope element (ScopeElm) and a running history in the visible tab
  await s.call('loadExample', 'multivib-a.txt');
  await s.eval(`CircuitJS1.setSimRunning(true); true`);
  await sleep(1500);
  await s.eval(`CircuitJS1.setSimRunning(false); true`);
  await sleep(200);
  const pixD = await s.call('canvasPixels');
  const pixE = await s.call('canvasPixels');
  const sr = [await AA('render', { includeScopes: true }), await AA('render', { format: 'svg', scale: 3 }), await AA('render', { doc: P })];
  await sleep(200);
  const pixF = await s.call('canvasPixels');
  ck('pixelsScopeElmUnchanged', pixD === pixE && sr.every((r) => r.ok) && pixF === pixD);
  out.notes.pixels = { control: pixA === pixB, after: pixC === pixA, scopeElmControl: pixD === pixE, scopeElmAfter: pixF === pixD };

  // ---------------------------------------------------------------- render contract details
  ck('renderSyncRejected', codes(await A('render', {})).includes('invalid_value'));
  const badArgs = [await AA('render', { format: 'jpg' }), await AA('render', { scale: 0.1 }), await AA('render', { scale: 5 }), await AA('render', { includeScopes: 'yes' }), await AA('render', { doc: 'd999' })];
  out.notes.badArgs = badArgs.map((r) => codes(r));
  ck('renderBadArgs', badArgs.slice(0, 4).every((r) => !r.ok && codes(r)[0] === 'invalid_value') && codes(badArgs[4])[0] === 'unknown_document');
  // scale changes the size proportionally
  const s1 = await AA('render', { doc: P }); const s2 = await AA('render', { doc: P, scale: 2 });
  ck('renderScale', s1.ok && s2.ok && Math.abs(s2.data.width - 2 * s1.data.width) <= 2 && Math.abs(s2.data.height - 2 * s1.data.height) <= 2);
  // an empty document: a blank 2 x 2 cell image
  const E = (await A('createDocument', { title: 'Empty' })).data.doc;
  const re = await AA('render', { doc: E });
  ck('renderEmpty', re.ok && re.data.width === 32 && re.data.height === 32);
  // too large: a 300-cell wire at scale 4
  await A('importCircuit', { doc: E, circuit: { elements: [{ id: 'W1', type: 'Wire', start: { x: 0, y: 0 }, end: { x: 300, y: 0 } }] } });
  const big = await AA('render', { doc: E, scale: 4 });
  ck('renderTooLarge', !big.ok && codes(big)[0] === 'invalid_value' && /scale/.test(big.issues[0].message));
  // render_failed: the vector exporter cannot load; no alert; png and a later svg still work
  await s.eval(`CircuitJS1Agent.debugFailNextSvgLoad(); true`);
  const rf = await AA('render', { doc: P, format: 'svg' });
  const rfPng = await AA('render', { doc: P });
  const rfSvg = await AA('render', { doc: P, format: 'svg' });
  out.notes.renderFailed = { codes: codes(rf), hint: rf.issues && rf.issues[0] && rf.issues[0].hint };
  ck('renderFailedNoAlert', !rf.ok && codes(rf)[0] === 'render_failed' && /png/.test(rf.issues[0].hint) && rfPng.ok && rfSvg.ok && s.dialogs.length === alertMark);
  // served while the document is busy with a run
  await s.call('agentStart', 'busyRun', 'run', { doc: P, span: 1000, budgetMs: 1500 });
  await sleep(100);
  const busyRender = await AA('render', { doc: P });
  const busyEdit = await A('applyEdits', { doc: P, edits: [{ op: 'describe', id: 'R1', description: 'x' }] });
  await waitFor(async () => (await s.call('agentStarted', 'busyRun')).calls === 1, 10000, 'busy run end');
  out.notes.busy = { render: busyRender.ok || codes(busyRender), edit: codes(busyEdit), run: (await s.call('agentStarted', 'busyRun')).result };
  ck('renderServedWhileBusy', busyRender.ok && codes(busyEdit).includes('busy'));
  // a document closed before its draw: unknown_document, one callback
  const closedRender = await s.eval(`new Promise((resolve) => { let n = 0; let res = null;
    CircuitJS1Agent.callAsync('render', JSON.stringify({ doc: ${JSON.stringify(P)} }), (r) => { n++; res = JSON.parse(r); });
    CircuitJS1Agent.call('closeDocument', JSON.stringify({ doc: ${JSON.stringify(P)}, discardChanges: true }));
    setTimeout(() => resolve({ n, codes: res && res.issues.map((i) => i.code) }), 500); })`);
  ck('renderClosedDocument', closedRender.n === 1 && closedRender.codes && closedRender.codes[0] === 'unknown_document');
  await A('closeDocument', { doc: E, discardChanges: true });
  ck('noPageExceptions', s.exceptions.length === exMark);
  if (s.exceptions.length !== exMark) out.exceptions = s.exceptions.slice(exMark).map((x) => x.slice(0, 600));
  } catch (e) {
    out.error = e.stack || e.message;
    ck('harnessError', false);
  }
  fs.writeFileSync(path.join(OUT_DIR, 'agent_bg.json'), JSON.stringify(out, null, 2));
  const failed = Object.keys(out.checks).filter((n) => !out.checks[n]);
  report('AG.agent_bg', failed.length === 0, { checks: Object.keys(out.checks).length, failed, timing: out.notes.timing && {
    rateRatio: +out.notes.timing.rateRatio.toFixed(3), maxSliceMs: +out.notes.timing.maxSliceMs.toFixed(2), slices: out.notes.timing.slices }, details: path.join(OUT_DIR, 'agent_bg.json') });
}

// agent_files: path-based files in the browser build (PL_AGA Phase 9, SP_AGA_02_14 / §03_09).
// Headless Chromium has no Node file system, so (a) every file contract (openFile, saveFile)
// returns file_unavailable for every argument form, before any argument check, without creating
// a document or touching the visible tab; (b) the side-effect-free circuit-content test, through
// the diagnostic CircuitJS1Agent.debugCircuitTest(text): prose, empty, JSON circuit and JSON
// non-circuits, every bundled example (all circuits), options-only, model-only, scope/hint/
// adjustable-only and ignored-only lines, unknown and delimiter-only lines, numeric aliases of
// the model prefixes; it changes no document and no model catalogue; (c) the dump-type predicate
// CircuitElmCreator.isKnownDumpType agrees with CircuitElmCreator.createCe for every code 0..1023:
// an importCircuit of each code's line reports "unknown element type" exactly for the codes the
// predicate rejects. The real file rows run in the NW.js harness of PL_MCP Phase 4.
async function scenarioAgentFiles(s) {
  const out = { checks: {}, notes: {} };
  const ck = (name, cond) => { out.checks[name] = !!cond; return !!cond; };
  const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
  const A = (op, args) => s.call('agentCall', op, args);
  const CT = async (text) => JSON.parse(await s.eval(`CircuitJS1Agent.debugCircuitTest(${JSON.stringify(text)})`));
  try {
  await resetApp(s);
  const exMark = s.exceptions.length;
  const alertMark = s.dialogs.length;
  await s.call('loadExample', 'lrc.txt');
  await sleep(300);
  const docs0 = (await A('listDocuments', {})).data.documents;
  const V = docs0.find((d) => d.active).doc;
  const B = (await A('createDocument', {})).data.doc;
  await A('importCircuit', { doc: B, format: 'text', circuit: '$ 1 0.000005 10 50 5 50 5e-11\nr 0 0 64 0 0 100\n' });
  const vis0 = await s.call('visibleTab');
  const text0 = await s.call('exportText');
  const docsBefore = (await A('listDocuments', {})).data.documents;

  // ---------------------------------------------------------------- (a) file_unavailable
  const fileCalls = [
    ['openFile', { path: '/tmp/a.txt' }],
    ['openFile', { path: '/tmp/a.json', into: 'new', activate: true }],
    ['openFile', { path: '/tmp/a.txt', into: B }],
    ['openFile', { path: '/tmp/a.txt', into: V, activate: true }],
    ['openFile', {}],
    ['openFile', { path: 'relative.txt' }],
    ['openFile', { path: '/tmp/notes.md' }],
    ['openFile', { path: 42, into: 7 }],
    ['saveFile', {}],
    ['saveFile', { doc: B }],
    ['saveFile', { path: '/tmp/x.json' }],
    ['saveFile', { doc: B, path: '/tmp/x.txt', format: 'json' }],
    ['saveFile', { path: '/tmp/notes.md', format: 'xml' }],
  ];
  out.notes.unavailable = [];
  let allUnavailable = true;
  for (const [op, args] of fileCalls) {
    const r = await A(op, args);
    const ok = r && r.ok === false && r.issues.length === 1 && r.issues[0].code === 'file_unavailable' && r.issues[0].severity === 'error';
    if (!ok) { allUnavailable = false; out.notes.unavailable.push({ op, args, r }); }
  }
  ck('everyFileContractUnavailable', allUnavailable);
  // the common rules still come first: an unknown doc is unknown_document
  const ud = await A('saveFile', { doc: 'd999' });
  ck('saveFileUnknownDocFirst', ud.ok === false && ud.issues[0].code === 'unknown_document');
  const docsAfter = (await A('listDocuments', {})).data.documents;
  ck('unavailableNoDocument', same(docsAfter, docsBefore));
  ck('unavailableVisibleUnchanged', same(await s.call('visibleTab'), vis0) && (await s.call('exportText')) === text0);
  const stB = JSON.parse(await s.eval(`CircuitJS1Agent.debugDocState(${JSON.stringify(B)})`));
  ck('unavailableTransactionUnchanged', stB.transaction && stB.transaction.open === true && stB.modified === true);

  // ---------------------------------------------------------------- (b) circuit test
  const opts = '$ 1 0.000005 10.20027730826997 50 5 50 5e-11';
  const cases = {
    empty: ['', false],
    blank: ['  \n\t\n\r\n', false],
    prose: ['Meeting notes for Tuesday\nTalk to the team about the circuit simulator\nremember: buy resistors and a soldering iron\n', false],
    proseElementLetters: ['This is my note\nso it starts with letters\nthat are element types\n', false],
    optionsOnly: [opts + '\n', true],
    optionsCrLf: [opts + '\r\nr 0 0 64 0 0 100\r\nw 64 0 64 64 0\r\n', true],
    elementOnly: ['r 0 0 64 0 0 100\n', true],
    numericElement: ['174 0 0 64 0 1 1000 0.5 Resistance\n', true],
    modelOnly: ['32 early 0 1e-13 0 0 1.5 0 0 2 1 1 0.02 0 1\n34 mydiode 0 1e-14 1 0 0 0 1e-9 0 0 0 0\n! black 0 G1,P1 Go Operator 11\\q11\n. mysub 0 1 2 3\n', false],
    modelAliasOnly: ['" mydiode 0 1e-14 1\n& 14 5 0 100 Duty\n', false],
    modelsWithOptions: [opts + '\n32 early 0 1e-13 0 0 1.5 0 0 2 1 1 0.02 0 1\n34 mydiode 0 1e-14 1\n', true],
    auxOnly: ['o 2 32 0 4102 5 0.000390625 0 2 2 3\nh 2 7 5\n38 14 5 0 100 Duty\\sCycle\n', false],
    ignoredOnly: ['% 1 2 3\n? 4 5\nB 6 7\n', false],
    ignoredWithElement: ['% 1 2 3\n? 4 5\nB 6 7\nr 0 0 64 0 0 100\n', true],
    unknownLine: [opts + '\nr 0 0 64 0 0 100\nHello there\n', false],
    unknownCode: [opts + '\n999 0 0 64 0 0\n', false],
    elementShortFields: [opts + '\nr 0 0 64\n', false],
    delimiterOnlyLine: [opts + '\n+ +\n', false],
    jsonNonCircuit: ['{"a": 1, "elements": {}}', false],
    jsonOtherFormat: ['{"schema": {"format": "other", "version": "2.0"}, "elements": {}}', false],
    jsonOldVersion: ['{"schema": {"format": "circuitjs", "version": "1.0"}, "elements": {}}', false],
    jsonArray: ['[1, 2, 3]', false],
    jsonBroken: ['{"schema": {"format": "circuitjs", "version": "2.0"}, "elements": {', false],
  };
  out.notes.cases = {};
  for (const [name, [text, expect]] of Object.entries(cases)) {
    const r = await CT(text);
    out.notes.cases[name] = r;
    ck('ct_' + name, r.circuit === expect);
  }
  ck('ct_kinds', out.notes.cases.optionsOnly.kind === 'text' && out.notes.cases.prose.kind === null);
  ck('ct_unknownLineNumber', out.notes.cases.unknownLine.firstUnknownLine === 3 && out.notes.cases.unknownLine.unknownLines === 1);
  ck('ct_modelCounts', out.notes.cases.modelOnly.modelLines === 4 && out.notes.cases.modelAliasOnly.modelLines === 1
    && out.notes.cases.modelAliasOnly.auxLines === 1 && out.notes.cases.auxOnly.auxLines === 3 && out.notes.cases.ignoredOnly.ignoredLines === 3);
  // JSON circuits: the export of a loaded example, and a minimal schema-only document
  const jsonExport = await s.call('exportJson');
  const rj = await CT(jsonExport);
  ck('ct_jsonExport', rj.circuit === true && rj.kind === 'json');
  const rjMin = await CT('{"schema": {"format": "circuitjs", "version": "2.1"}}');
  ck('ct_jsonSchemaOnly', rjMin.circuit === true && rjMin.kind === 'json');
  const agentJson = await A('exportCircuit', { doc: B, format: 'json' });
  ck('ct_jsonAgentExport', (await CT(agentJson.data.content)).kind === 'json');
  // every bundled example is a circuit (raw file text as fetched)
  const names = listAllCircuits();
  const corpus = await s.eval(`(async () => {
    const names = ${JSON.stringify(names)}; const bad = [];
    for (const n of names) {
      const t = await (await fetch('/circuitjs1/circuits/' + n, { cache: 'no-store' })).text();
      const r = JSON.parse(CircuitJS1Agent.debugCircuitTest(t));
      if (!r.circuit || r.kind !== 'text') bad.push({ n, r });
    }
    return { count: names.length, bad: bad.slice(0, 10), badCount: bad.length };
  })()`);
  out.notes.corpus = corpus;
  ck('ct_corpusAllCircuits', corpus.count > 300 && corpus.badCount === 0);
  // side-effect free: no document, element, undo entry or model catalogue changed
  const modelProbe = '34 p9probe 0 7.77e-7 0.5 1 0 0.01\n' + opts + '\nd 0 0 64 0 2 p9probe\n';
  await CT(modelProbe);
  ck('ct_noSideEffects', same((await A('listDocuments', {})).data.documents, docsBefore)
    && (await s.call('exportText')) === text0
    && same(JSON.parse(await s.eval(`CircuitJS1Agent.debugDocState(${JSON.stringify(B)})`)).undo, stB.undo));
  // the probe's diode model was not registered: a document using its name gets other parameters
  const C = (await A('createDocument', {})).data.doc;
  await A('importCircuit', { doc: C, format: 'text', circuit: opts + '\nd 0 0 64 0 2 p9probe\n' });
  const cText = (await A('exportCircuit', { doc: C, format: 'text' })).data.content;
  ck('ct_noModelRegistered', !/7\.77e-7/i.test(cText));
  // positive control: a real import of the probe registers the model and exports it
  await A('importCircuit', { doc: C, format: 'text', circuit: modelProbe });
  ck('ct_modelProbeControl', /7\.77e-7/i.test((await A('exportCircuit', { doc: C, format: 'text' })).data.content));
  await A('closeDocument', { doc: C, discardChanges: true });

  // ---------------------------------------------------------------- (c) dump-type predicate vs createCe
  const SPECIAL = new Set([36, 111, 104, 38, 37, 63, 66, 34, 32, 33, 46]); // $ o h & % ? B " space ! .
  const known = [], unknown = [];
  const sweep = await s.eval(`(() => {
    const SPECIAL = new Set(${JSON.stringify([...SPECIAL])}); const known = [], unknown = [];
    for (let c = 0; c <= 1023; c++) {
      if (SPECIAL.has(c)) continue;
      const r = JSON.parse(CircuitJS1Agent.debugCircuitTest(c + ' 0 0 64 0 0'));
      (r.elementLines === 1 ? known : unknown).push(c);
    }
    return { known, unknown };
  })()`);
  known.push(...sweep.known); unknown.push(...sweep.unknown);
  out.notes.sweep = { known: known.length, unknown: unknown.length };
  // char-token form gives the same classification as the numeric form
  const charForm = await s.eval(`(() => {
    const bad = [];
    for (let c = 33; c < 127; c++) {
      const ch = String.fromCharCode(c); if (/[0-9+]/.test(ch) || ${JSON.stringify([...SPECIAL])}.includes(c)) continue;
      const a = JSON.parse(CircuitJS1Agent.debugCircuitTest(ch + ' 0 0 64 0 0')).elementLines;
      const b = JSON.parse(CircuitJS1Agent.debugCircuitTest(c + ' 0 0 64 0 0')).elementLines;
      if (a !== b) bad.push(c);
    }
    return bad;
  })()`);
  ck('sweep_charFormEqualsNumeric', charForm.length === 0);
  const S = (await A('createDocument', {})).data.doc;
  const unknownMsgLines = (r) => (r.issues || []).filter((i) => /unknown element type/.test(i.message)).map((i) => +/^Line (\d+):/.exec(i.message)[1]);
  const mismatches = [];
  const runChunks = async (codes, expectUnknown) => {
    for (let i = 0; i < codes.length; i += 40) {
      const chunk = codes.slice(i, i + 40);
      const text = opts + '\n' + chunk.map((c) => c + ' 0 0 64 0 0').join('\n') + '\n';
      const r = await A('importCircuit', { doc: S, format: 'text', circuit: text });
      const lines = new Set(unknownMsgLines(r));
      chunk.forEach((c, k) => { const isUnknown = lines.has(k + 2); if (isUnknown !== expectUnknown) mismatches.push({ code: c, predicate: expectUnknown ? 'unknown' : 'known', importer: isUnknown ? 'unknown' : 'created' }); });
      if (r.truncatedIssues) mismatches.push({ chunk: i, truncated: r.truncatedIssues });
    }
  };
  await runChunks(known, false);
  await runChunks(unknown, true);
  await s.call('closeDialogs');
  out.notes.sweep.mismatches = mismatches.slice(0, 20);
  ck('sweep_predicateEqualsCreateCe', known.length > 100 && mismatches.length === 0);
  await A('closeDocument', { doc: S, discardChanges: true });
  await A('closeDocument', { doc: B, discardChanges: true });

  ck('visibleUnchangedAtEnd', (await s.call('exportText')) === text0);
  ck('noPageException', s.exceptions.length === exMark);
  ck('noAlert', s.dialogs.length === alertMark);
  } catch (e) {
    out.notes.error = e.stack || e.message;
    ck('noHarnessError', false);
  }
  fs.writeFileSync(path.join(OUT_DIR, 'agent_files.json'), JSON.stringify(out, null, 2));
  const failed = Object.entries(out.checks).filter(([, v]) => !v).map(([k]) => k);
  report('AG.agent_files', failed.length === 0, { checks: Object.keys(out.checks).length, failed, corpus: out.notes.corpus && out.notes.corpus.count,
    sweep: out.notes.sweep && { known: out.notes.sweep.known, unknown: out.notes.sweep.unknown }, details: path.join(OUT_DIR, 'agent_files.json') });
}

// pin_names: polar pin names state the real polarity (SP_AGA_DEC_06, SP_AGA_03_02 "Polar names",
// §01_09 source voltage sign; io-framework "Pin-name aliases"), on a background document. Each
// source drives a 1 kOhm load from `start` (grounded) to `end`: a DC source reads +5 V at `plus`
// and 0 V at `minus`, a 10 mA current source leaves at `out` (+10 V), the ohmmeter's `probe` is
// the high post; an op-amp's `in-` is the inverting input with and without `swap_inputs`. A JSON
// 2.0 file written with the superseded names (`positive`/`negative`, `probe+`/`probe-`, pin keys
// in reverse order so the key-order fallback cannot hide a missing alias) loads with the same
// polarity as before; a new export is version 2.1 with the new names; superseded names in
// PostRefs give `unknown_post`; the catalogue lists the new names. The 2.0 file's `state.pins`
// under the old names restores the right post voltages (read before any run), and a file with
// both old and new pin keys is placed by the new names.
async function scenarioPinNames(s) {
  const out = { checks: {}, notes: {} };
  const ck = (name, cond) => { out.checks[name] = !!cond; return !!cond; };
  const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
  const A = (op, args) => s.call('agentCall', op, args);
  const R = (args) => s.call('agentAsync', 'run', args, 30000);
  const codes = (list) => (list || []).map((i) => i.code);
  const near = (v, x, tol) => typeof v === 'number' && Math.abs(v - x) <= (tol || 1e-3);
  await resetApp(s);
  const exMark = s.exceptions.length;
  const doc = (await A('createDocument', { title: 'Pin names' })).data.doc;
  const values = async (targets) => {
    const r = await A('read', { doc, targets });
    return r.ok ? Object.fromEntries(r.data.values.map((v) => [v.name, v.value])) : { __issues: codes(r.issues) };
  };
  // X1 from start (0,4) (grounded) to end (0,0); R1 from end back to ground
  const loop = (spec) => [
    { id: 'X1', start: { x: 0, y: 4 }, end: { x: 0, y: 0 }, ...spec },
    { id: 'R1', type: 'Resistor', start: { x: 0, y: 0 }, end: { x: 4, y: 0 }, properties: { resistance: '1 kOhm' } },
    { id: 'W1', type: 'Wire', start: { x: 4, y: 0 }, end: { x: 4, y: 4 } },
    { id: 'W2', type: 'Wire', start: { x: 4, y: 4 }, end: { x: 0, y: 4 } },
    { id: 'G1', type: 'Ground', start: { x: 0, y: 4 }, end: { x: 0, y: 5 } },
  ];
  const cases = {
    dc: { spec: { type: 'VoltageSourceDC', properties: { max_voltage: '5 V' } }, names: ['minus', 'plus'], old: ['positive', 'negative'], high: 5 },
    cur: { spec: { type: 'CurrentSource', properties: { current: '10 mA' } }, names: ['in', 'out'], old: ['positive', 'negative'], high: 10 },
    ohm: { spec: { type: 'OhmMeter' }, names: ['com', 'probe'], old: ['probe+', 'probe-'], high: 10 },
  };
  for (const [k, c] of Object.entries(cases)) {
    const imp = await A('importCircuit', { doc, circuit: { elements: loop(c.spec) } });
    const run = await R({ doc, span: '1 ms', reset: true, maxPoints: 10 });
    const g = await A('getCircuit', { doc });
    const x1 = ((g.data && g.data.elements) || []).find((e) => e.id === 'X1');
    const v = await values([{ name: 'p0', post: 'X1.' + c.names[0] }, { name: 'p1', post: 'X1.' + c.names[1] },
      { name: 'vElm', element: 'X1', quantity: 'voltage' }, { name: 'load', post: 'R1.pin1' }]);
    out.notes[k] = { posts: x1 && x1.posts.map((p) => p.pin + '@' + p.at.x + ',' + p.at.y), v, run: run.ok };
    ck(k + '_names', imp.ok && x1 && same(x1.posts.map((p) => p.pin), c.names));
    ck(k + '_polarity', near(v.p0, 0) && near(v.p1, c.high) && near(v.load, c.high));
    if (k === 'dc') ck('dc_voltageSign', near(v.vElm, 5));
    if (k === 'cur') ck('cur_voltageSign', near(v.vElm, 10));
    // superseded names are not PostRefs
    const oldRef = await A('read', { doc, targets: [{ post: 'X1.' + c.old[0] }] });
    ck(k + '_oldPostRef_unknown', !oldRef.ok && codes(oldRef.issues).includes('unknown_post'));
    // a new export: version 2.1, new names
    const ex = await A('exportCircuit', { doc, format: 'json' });
    const j = JSON.parse(ex.data.content);
    out.notes[k].exportPins = Object.keys(j.elements.X1.pins);
    ck(k + '_export21', j.schema.version === '2.1' && same(Object.keys(j.elements.X1.pins).filter((p) => !p.startsWith('_')), c.names));
    // the same circuit as a JSON 2.0 file with the superseded names, X1 pin keys in reverse order
    const ren = { [c.names[0]]: c.old[0], [c.names[1]]: c.old[1] };
    const oldJ = JSON.parse(ex.data.content);
    oldJ.schema.version = '2.0';
    const p = oldJ.elements.X1.pins;
    oldJ.elements.X1.pins = Object.fromEntries(Object.keys(p).reverse().map((n) => [ren[n] || n, p[n]]));
    const fixRef = (r) => { const d = r.indexOf('.'); return d > 0 && r.slice(0, d) === 'X1' && ren[r.slice(d + 1)] ? 'X1.' + ren[r.slice(d + 1)] : r; };
    for (const e of Object.values(oldJ.elements)) for (const pin of Object.values(e.pins || {})) if (Array.isArray(pin.connected_to)) pin.connected_to = pin.connected_to.map(fixRef);
    // saved state under the old names: a marker voltage on post 1 (not the solved value)
    const mark = c.high / 2 + 0.123;
    oldJ.elements.X1.state = { pins: { [c.old[0]]: { v: 0 }, [c.old[1]]: { v: mark } } };
    const imp2 = await A('importCircuit', { doc, circuit: JSON.stringify(oldJ) });
    const vs = await values([{ name: 'p0', post: 'X1.' + c.names[0] }, { name: 'p1', post: 'X1.' + c.names[1] }]);
    out.notes[k].old20state = vs;
    ck(k + '_json20_stateAlias', imp2.ok && near(vs.p1, mark) && near(vs.p0, 0));
    await R({ doc, span: '1 ms', reset: true, maxPoints: 10 });
    const v2 = await values([{ name: 'p1', post: 'X1.' + c.names[1] }, { name: 'load', post: 'R1.pin1' }]);
    out.notes[k].old20 = { pins: Object.keys(oldJ.elements.X1.pins), v: v2, ok: imp2.ok };
    ck(k + '_json20_oldNames', imp2.ok && near(v2.p1, c.high) && near(v2.load, c.high));
    // both name sets in one file, the old ones first and pointing the other way: new names win
    const mixJ = JSON.parse(ex.data.content);
    const q = mixJ.elements.X1.pins;
    mixJ.elements.X1.pins = { [c.old[0]]: { position: q[c.names[1]].position }, [c.old[1]]: { position: q[c.names[0]].position }, ...q };
    const imp3 = await A('importCircuit', { doc, circuit: JSON.stringify(mixJ) });
    await R({ doc, span: '1 ms', reset: true, maxPoints: 10 });
    const v3 = await values([{ name: 'p1', post: 'X1.' + c.names[1] }, { name: 'load', post: 'R1.pin1' }]);
    out.notes[k].mixed = { pins: Object.keys(mixJ.elements.X1.pins), v: v3 };
    ck(k + '_mixedNames_newWin', imp3.ok && near(v3.p1, c.high) && near(v3.load, c.high));
  }
  // op-amp: +1 V on `in-`, `in+` grounded -> output at the negative rail, with and without swap_inputs
  for (const swap of [false, true]) {
    const k = 'opamp_swap_' + swap;
    const oa = { id: 'OA1', type: 'OpAmp', start: { x: 0, y: 0 }, end: { x: 8, y: 0 }, properties: { swap_inputs: swap } };
    await A('importCircuit', { doc, circuit: { elements: [oa] } });
    const posts = Object.fromEntries((((await A('getCircuit', { doc })).data.elements || [])[0].posts).map((p) => [p.pin, p.at]));
    const o = posts.out;
    const imp = await A('importCircuit', { doc, circuit: { elements: [oa,
      { id: 'VP', type: 'Rail', start: posts['in-'], end: { x: posts['in-'].x - 2, y: posts['in-'].y }, properties: { max_voltage: '1 V' } },
      { id: 'G1', type: 'Ground', start: posts['in+'], end: { x: posts['in+'].x - 2, y: posts['in+'].y } },
      { id: 'RL', type: 'Resistor', start: o, end: { x: o.x + 4, y: o.y }, properties: { resistance: '10 kOhm' } },
      { id: 'G2', type: 'Ground', start: { x: o.x + 4, y: o.y }, end: { x: o.x + 4, y: o.y + 1 } }] } });
    await R({ doc, span: '10 ms', reset: true, maxPoints: 10 });
    const v = await values([{ name: 'out', post: 'OA1.out' }, { name: 'inm', post: 'OA1.in-' }]);
    out.notes[k] = { posts, v };
    ck(k + '_inMinusInverting', imp.ok && near(v.inm, 1) && v.out < -10);
  }
  // catalogue
  const want = { VoltageSourceDC: ['minus', 'plus'], VoltageSourceAC: ['minus', 'plus'], VoltageSourceSquare: ['minus', 'plus'],
    CurrentSource: ['in', 'out'], OhmMeter: ['com', 'probe'], OpAmp: ['in-', 'in+', 'out'], PolarCapacitor: ['positive', 'negative'], Rail: ['output'] };
  const cat = {};
  for (const t of Object.keys(want)) { const d = await A('describeType', { type: t }); cat[t] = d.ok ? d.data.pins : codes(d.issues); }
  out.notes.catalogue = cat;
  ck('catalogue', Object.keys(want).every((t) => same(cat[t], want[t])));
  await A('closeDocument', { doc, discardChanges: true });
  ck('noExceptions', s.exceptions.length === exMark);
  const failed = Object.keys(out.checks).filter((k) => !out.checks[k]);
  fs.writeFileSync(path.join(OUT_DIR, 'pin_names.json'), JSON.stringify(out, null, 2));
  report('AG.pin_names', failed.length === 0, { checks: Object.keys(out.checks).length, failed });
}

// mcp_browser: the in-app MCP server in the browser build (PL_MCP Phase 1, SP_MCP_05_04 "Browser
// build"). circuitjs.html loads scripts/mcp-server.js in every build; without the desktop runtime
// the server must report `disabled` and attempt no listen, with no page exception or console error
// from the bundle, and the app's own status (McpServerStatus, through the diagnostic
// CircuitJS1Agent.debugMcpStatus()) must agree. The listening rows run in NW.js (PL_MCP Phase 4).
async function scenarioMcpBrowser(s) {
  const out = { checks: {}, notes: {} };
  const ck = (name, cond) => { out.checks[name] = !!cond; return !!cond; };
  const page = await s.eval(`(() => ({
    loaded: typeof window.CircuitJS1Mcp === 'object' && typeof window.CircuitJS1Mcp.start === 'function',
    server: window.CircuitJS1Mcp ? window.CircuitJS1Mcp.status() : null,
    app: JSON.parse(CircuitJS1Agent.debugMcpStatus()),
    script: !!document.querySelector('script[src="scripts/mcp-server.js"]'),
    hasNode: typeof window.require === 'function' || typeof window.process === 'object',
  }))()`);
  out.notes.page = page;
  ck('script_tag', page.script);
  ck('bundle_loaded', page.loaded);
  ck('no_node_runtime', !page.hasNode);
  ck('server_disabled', page.server && page.server.state === 'disabled' && page.server.reason === 'no desktop runtime'
    && !page.server.port && !(page.server.urls && page.server.urls.length));
  ck('app_status_disabled', page.app.state === 'disabled' && page.app.reason === 'no desktop runtime' && page.app.port === 0 && page.app.urls.length === 0);
  ck('toolsVersion', page.server && page.server.toolsVersion === '1.0');
  const bad = (t) => /mcp-server|CircuitJS1Mcp/.test(t);
  const errs = s.console.filter((c) => (c.type === 'error' || c.type === 'log:error') && bad(c.text)).map((c) => c.text.slice(0, 300));
  const exc = s.exceptions.filter(bad).map((e) => e.slice(0, 300));
  out.notes.errors = errs; out.notes.exceptions = exc;
  ck('no_errors', errs.length === 0 && exc.length === 0);
  // a second start in the browser build stays disabled and still attempts nothing
  const again = await s.eval(`CircuitJS1Mcp.start(CircuitJS1Agent, {enabled: true, port: 7311, portRange: 20, host: '0.0.0.0'}, null).then((st) => st)`);
  ck('restart_disabled', again && again.state === 'disabled');
  const failed = Object.keys(out.checks).filter((k) => !out.checks[k]);
  fs.writeFileSync(path.join(OUT_DIR, 'mcp_browser.json'), JSON.stringify(out, null, 2));
  report('MCP.mcp_browser', failed.length === 0, { checks: Object.keys(out.checks).length, failed, state: page.app.state });
}

// mcp_dialog: Options → "MCP Server..." and the info dialog (PL_MCP Phase 3, SP_MCP_02_04) in the
// browser build: the item reads "MCP Server... (off)" (the server is disabled without the desktop
// runtime), the dialog opens through the dialog router and shows `disabled`, no instance ID, no
// URL, no command line and 0 calls, the default settings; an invalid base port and an invalid
// address are rejected with a message and nothing stored; a valid Save stores the three keys and
// says they apply at the next start; Escape closes. Then the dialog strings in English and
// Ukrainian: two side pages (`?lang=en`, `?lang=uk`) open the dialog and compare every label with
// its key and with its locale_uk.txt translation (RULE_STYLE_003). The listening, counter,
// disable/restart and all-ports-busy rows run in NW.js (PL_MCP Phase 3 Result, Phase 4 e2e).
const MCP_DIALOG_KEYS = ['MCP Server', 'Status:', 'Instance ID:', 'URLs:', 'Tool calls in this run:', 'Connect Claude Code with:', 'Copy',
  'Settings (apply at the next start)', 'Enabled', 'Base port:', 'Listening address:', 'Save', 'Close'];
const MCP_DIALOG_MESSAGES = ['MCP Server...', '(off)', 'Base port must be a whole number from 1024 to 65535.',
  'Base port is too high for the port range: the last port must not exceed 65535.',
  'Listening address must be an IPv4 or IPv6 address, or localhost.', 'Saved. The settings apply at the next start of the app.',
  'Nothing to save: the settings are unchanged.'];

// Page-side reader/driver of the MCP Server dialog (installed as window.__mcpDlg).
function mcpDialogHelpers() {
  const fire = (el, type) => el.dispatchEvent(new MouseEvent(type, { bubbles: true, cancelable: true, view: window }));
  const norm = (t) => (t || '').replace(/\s+/g, ' ').trim();
  const vis = () => Array.from(document.querySelectorAll('.gwt-MenuItem')).filter((e) => e.offsetWidth > 0);
  window.__mcpDlg = {
    // Opens the top menu, returns the text of its item starting with one of `prefixes`, clicks it when `open`
    async menuItem(top, prefixes, open) {
      const a = vis().find((e) => top.includes(norm(e.textContent)));
      if (!a) return { error: 'no top menu' };
      fire(a, 'mouseover'); fire(a, 'click');
      await new Promise((r) => setTimeout(r, 300));
      const el = vis().find((e) => prefixes.some((p) => norm(e.textContent) === p || norm(e.textContent).startsWith(p + ' ')));
      const text = el ? norm(el.textContent) : null;
      if (el && open) { fire(el, 'mouseover'); fire(el, 'click'); } else fire(a, 'click');
      await new Promise((r) => setTimeout(r, 300));
      return { text };
    },
    dlg() { return Array.from(document.querySelectorAll('.gwt-DialogBox')).find((d) => d.offsetWidth > 0 && d.querySelector('#mcpServerInfo')) || null; },
    read() {
      const d = this.dlg();
      if (!d) return null;
      const rows = Array.from(d.querySelectorAll('#mcpServerInfo > tbody > tr')).map((tr) => Array.from(tr.children).map((td) => norm(td.innerText)));
      const inputs = Array.from(d.querySelectorAll('input'));
      const cmd = d.querySelector('#mcpServerCommand');
      const cb = inputs.find((i) => i.type === 'checkbox');
      const tbs = inputs.filter((i) => i.type === 'text' && i.id !== 'mcpServerCommand');
      const labels = Array.from(d.querySelectorAll('.gwt-Label, td, label')).map((e) => norm(e.childElementCount ? '' : e.textContent)).filter(Boolean);
      return { caption: norm((d.querySelector('.Caption') || {}).textContent).replace(/[-+]$/, '').trim(), rows, labels,
        buttons: Array.from(d.querySelectorAll('button')).map((b) => ({ text: norm(b.textContent), disabled: b.disabled })),
        command: cmd.value, commandDisabled: cmd.disabled, enabled: cb.checked, checkboxLabel: norm((d.querySelector('label') || {}).textContent),
        port: tbs[0].value, host: tbs[1].value, message: norm((d.querySelector('#mcpServerMessage') || {}).textContent) };
    },
    set(port, host) {
      const tbs = Array.from(this.dlg().querySelectorAll('input')).filter((i) => i.type === 'text' && i.id !== 'mcpServerCommand');
      tbs[0].value = port; tbs[1].value = host;
      return true;
    },
    click(n) { const b = this.dlg().querySelectorAll('button')[n]; b.click(); return true; },
    // focus a button (n) or a settings text field (-1 port, -2 address); returns the focused tag
    focus(n) {
      const d = this.dlg();
      const tbs = Array.from(d.querySelectorAll('input')).filter((i) => i.type === 'text' && i.id !== 'mcpServerCommand');
      const el = n >= 0 ? d.querySelectorAll('button')[n] : tbs[-n - 1];
      el.focus();
      return document.activeElement === el;
    },
    store() { const g = (k) => localStorage.getItem(k); return { enabled: g('mcpServerEnabled'), port: g('mcpServerPort'), host: g('mcpServerHost'), range: g('mcpServerPortRange') }; },
    clearStore() { for (const k of ['mcpServerEnabled', 'mcpServerPort', 'mcpServerHost', 'mcpServerPortRange']) localStorage.removeItem(k); return true; },
  };
  return true;
}

// Translations of `keys` in a bundled locale file (`lang`), or the keys themselves for English.
function localeTexts(lang, keys) {
  const map = {};
  if (lang !== 'en') {
    for (const line of fs.readFileSync(path.join(SITE_DIR, 'circuitjs1', `locale_${lang}.txt`), 'utf8').split('\n')) {
      const m = /^"(.*)"="(.*)"\s*$/.exec(line);
      if (m) map[m[1]] = m[2];
    }
  }
  return Object.fromEntries(keys.map((k) => [k, lang === 'en' ? k : map[k]]));
}

async function scenarioMcpDialog(s) {
  const out = { checks: {}, notes: {} };
  const ck = (name, cond) => { out.checks[name] = !!cond; return !!cond; };
  const exMark = s.exceptions.length;
  await s.call('closeDialogs');
  await s.eval(`(${mcpDialogHelpers.toString()})()`);
  const options = menuTexts('Options');
  const item = await s.eval(`__mcpDlg.menuItem(${JSON.stringify(options)}, ${JSON.stringify(menuTexts('MCP Server...'))}, false)`);
  out.notes.item = item;
  const offTexts = menuTexts('(off)');
  ck('menu_item_off', item.text && menuTexts('MCP Server...').some((a) => offTexts.some((b) => item.text === a + ' ' + b)));
  const before = await s.eval('__mcpDlg.store()');
  await s.eval(`__mcpDlg.menuItem(${JSON.stringify(options)}, ${JSON.stringify(menuTexts('MCP Server...'))}, true)`);
  await sleep(300);
  const d = await s.eval('__mcpDlg.read()');
  out.notes.dialog = d;
  ck('dialog_opens', !!d);
  const row = (i) => (d && d.rows[i] ? d.rows[i][1] : null);
  ck('status_disabled', row(0) === 'disabled');
  ck('no_instance', row(1) === '—');
  ck('no_urls', row(2) === '—');
  ck('counter_0', row(3) === '0');
  ck('no_command', d && d.command === '' && d.commandDisabled && d.buttons[0].disabled);
  ck('default_settings', d && d.enabled === true && d.port === '7311' && d.host === '127.0.0.1' && before.enabled === null);
  // Enter writes only from a settings field: not right after opening, not on a focused button,
  // and an unchanged form is not written ("nothing to save")
  await s.key('Enter');
  const e0 = await s.eval('__mcpDlg.read()');
  ck('enter_after_open_writes_nothing', e0 && e0.message === '' && JSON.stringify(await s.eval('__mcpDlg.store()')) === JSON.stringify(before));
  ck('focus_close', await s.eval('__mcpDlg.focus(2)'));
  await s.key('Enter');
  const e1 = await s.eval('__mcpDlg.read()');
  ck('enter_on_close_writes_nothing', e1 && e1.message === '' && JSON.stringify(await s.eval('__mcpDlg.store()')) === JSON.stringify(before));
  ck('focus_port', await s.eval('__mcpDlg.focus(-1)'));
  await s.key('Enter');
  const e2 = await s.eval('__mcpDlg.read()');
  out.notes.unchanged = e2 && e2.message;
  ck('enter_unchanged_writes_nothing', e2 && e2.message !== '' && JSON.stringify(await s.eval('__mcpDlg.store()')) === JSON.stringify(before));
  // Save: invalid values are rejected (message, nothing stored); valid values are stored
  const msgs = [];
  for (const [port, host] of [['80', '0.0.0.0'], ['65530', '0.0.0.0'], ['7311', 'example.com']]) {
    await s.eval(`__mcpDlg.set(${JSON.stringify(port)}, ${JSON.stringify(host)})`);
    await s.eval('__mcpDlg.click(1)'); // Save
    const r = await s.eval('__mcpDlg.read()');
    msgs.push(r && r.message);
    ck(`reject_${port}_${host}`, r && r.message && JSON.stringify(await s.eval('__mcpDlg.store()')) === JSON.stringify(before));
  }
  ck('reject_messages_distinct', new Set(msgs).size === 3 && !msgs.includes(e2 && e2.message));
  await s.eval(`__mcpDlg.set('7400', '0.0.0.0')`);
  await s.eval('__mcpDlg.click(1)');
  const saved = await s.eval('__mcpDlg.read()');
  const stored = await s.eval('__mcpDlg.store()');
  out.notes.saved = { message: saved && saved.message, stored };
  ck('save_stores', stored.enabled === 'true' && stored.port === '7400' && stored.host === '0.0.0.0' && stored.range === null);
  ck('save_message', saved && saved.message && !msgs.includes(saved.message));
  await s.eval('__mcpDlg.clearStore()');
  await s.key('Escape');
  // Escape reaches the dialog only through the dialog router (DialogManager.getShowingDialog)
  ck('escape_closes', (await s.eval('__mcpDlg.read()')) === null);
  ck('no_page_exceptions', s.exceptions.length === exMark);

  // English and Ukrainian strings, each in a side page with ?lang=<lang>
  for (const lang of ['en', 'uk']) {
    const tr = localeTexts(lang, MCP_DIALOG_KEYS.concat(MCP_DIALOG_MESSAGES));
    ck(`${lang}_keys_translated`, Object.values(tr).every((v) => typeof v === 'string' && v.length > 0));
    let target = null; let cdp2 = null;
    try {
      target = await (await fetch(`http://127.0.0.1:${s.cdpPort}/json/new?${s.baseUrl}/circuitjs.html?lang=${lang}`, { method: 'PUT' })).json();
      cdp2 = new CDP(target.webSocketDebuggerUrl); await cdp2.open();
      const s2 = new Session(cdp2, s.baseUrl);
      await cdp2.send('Runtime.enable');
      await waitFor(() => s2.eval(`typeof CircuitJS1Agent !== 'undefined' && document.querySelectorAll('.gwt-MenuItem').length > 0`), LOAD_TIMEOUT_MS, `side page ${lang}`);
      await sleep(1000);
      await s2.eval(`(${mcpDialogHelpers.toString()})()`);
      const it = await s2.eval(`__mcpDlg.menuItem(${JSON.stringify([localeTexts(lang, ['Options']).Options || 'Options'])}, ${JSON.stringify([tr['MCP Server...']])}, true)`);
      await sleep(300);
      const d2 = await s2.eval('__mcpDlg.read()');
      await s2.eval(`__mcpDlg.set('80', '0.0.0.0'), __mcpDlg.click(1)`);
      const bad = await s2.eval('__mcpDlg.read()');
      out.notes[lang] = { item: it, caption: d2 && d2.caption, labels: d2 && d2.labels, buttons: d2 && d2.buttons, checkbox: d2 && d2.checkboxLabel, message: bad && bad.message };
      ck(`${lang}_menu_item`, it.text === tr['MCP Server...'] + ' ' + tr['(off)']);
      ck(`${lang}_caption`, d2 && d2.caption === tr['MCP Server']);
      ck(`${lang}_row_labels`, d2 && ['Status:', 'Instance ID:', 'URLs:', 'Tool calls in this run:'].every((k, i) => d2.rows[i][0] === tr[k]));
      ck(`${lang}_labels`, d2 && ['Connect Claude Code with:', 'Settings (apply at the next start)', 'Base port:', 'Listening address:'].every((k) => d2.labels.includes(tr[k])));
      ck(`${lang}_checkbox`, d2 && d2.checkboxLabel === tr.Enabled);
      ck(`${lang}_buttons`, d2 && d2.buttons.map((b) => b.text).join('|') === [tr.Copy, tr.Save, tr.Close].join('|'));
      ck(`${lang}_message`, bad && bad.message === tr['Base port must be a whole number from 1024 to 65535.']);
      ck(`${lang}_no_exceptions`, s2.exceptions.length === 0);
    } catch (e) {
      out.notes[lang + '_error'] = e.message;
      ck(`${lang}_side_page`, false);
    } finally {
      if (cdp2) cdp2.close();
      if (target) await fetch(`http://127.0.0.1:${s.cdpPort}/json/close/${target.id}`).catch(() => {});
    }
  }
  await s.cdp.send('Page.bringToFront').catch(() => {});
  const failed = Object.keys(out.checks).filter((k) => !out.checks[k]);
  fs.writeFileSync(path.join(OUT_DIR, 'mcp_dialog.json'), JSON.stringify(out, null, 2));
  report('MCP.mcp_dialog', failed.length === 0, { checks: Object.keys(out.checks).length, failed, item: item.text });
}

async function main() {
  const wanted = process.argv.slice(2);
  const scen = wanted.length && !wanted.includes('all') ? wanted : ['undo', 'paste', 'sliders', 'loadstate', 'textfid', 'roundtrip', 'synth', 'agent_docs', 'agent_ids', 'agent_catalogue', 'agent_edit', 'agent_connect', 'agent_connect_all', 'agent_freerun', 'geom_posts', 'agent_history', 'agent_run', 'agent_bg', 'agent_files', 'pin_names', 'mcp_browser', 'mcp_dialog'];
  fs.mkdirSync(OUT_DIR, { recursive: true });
  if (!fs.existsSync(path.join(SITE_DIR, 'circuitjs.html'))) throw new Error('SITE_DIR has no circuitjs.html: ' + SITE_DIR);
  log(`SITE_DIR=${SITE_DIR}\nOUT_DIR=${OUT_DIR}\nscenarios=${scen.join(',')}`);

  const httpPort = +(process.env.HTTP_PORT || (await freePort()));
  const cdpPort = +(process.env.CDP_PORT || (await freePort()));
  startChild('python3', ['-m', 'http.server', String(httpPort), '--directory', SITE_DIR, '--bind', '127.0.0.1'], 'http');
  const profile = path.join(OUT_DIR, 'profile');
  fs.rmSync(profile, { recursive: true, force: true });
  // --disable-extensions: a fresh profile would auto-install system-provided external extensions
  // (e.g. KDE Plasma Integration, whose native host then reports a lost connection after each run)
  startChild(CHROMIUM, ['--headless=new', `--remote-debugging-port=${cdpPort}`, '--remote-debugging-address=127.0.0.1', `--user-data-dir=${profile}`,
    '--no-first-run', '--no-default-browser-check', '--disable-gpu', '--disable-extensions', '--window-size=1400,900', 'about:blank'], 'chromium');

  const base = `http://127.0.0.1:${httpPort}`;
  await waitFor(async () => (await fetch(base + '/circuitjs.html')).ok, 15000, 'http server');
  await waitFor(async () => (await fetch(`http://127.0.0.1:${cdpPort}/json/version`)).ok, 20000, 'chromium CDP');
  const targets = await (await fetch(`http://127.0.0.1:${cdpPort}/json/list`)).json();
  const page = targets.find((t) => t.type === 'page');
  const cdp = new CDP(page.webSocketDebuggerUrl); await cdp.open();
  const s = new Session(cdp, base);
  s.cdpPort = cdpPort; // side pages (mcp_dialog locale checks)
  await cdp.send('Runtime.enable'); await cdp.send('Log.enable'); await cdp.send('Page.enable');
  await cdp.send('Browser.grantPermissions', { permissions: ['clipboardReadWrite', 'clipboardSanitizedWrite'], origin: base }).catch(() => {});
  await cdp.send('Page.navigate', { url: base + '/circuitjs.html' });
  await waitFor(() => s.eval(`typeof CircuitJS1 !== 'undefined' && typeof CircuitJS1.getElementCount === 'function'`), LOAD_TIMEOUT_MS, 'window.CircuitJS1');
  await s.eval(`(${pageHelpers.toString()})()`);
  await waitFor(() => s.call('ready'), 20000, 'app ready');
  await sleep(1500); // let startup circuit / deferred init settle
  await s.cdp.send('Page.bringToFront').catch(() => {});
  const startup = { count: await s.call('count'), dialogs: await s.call('dialogShowing'), simInfo: await s.call('simInfo') };
  log('app ready: ' + JSON.stringify(startup));
  try { await s.call('clearLogs'); } catch {}

  const scenarioEval = async (s) => {
    // Ad-hoc probe: EVAL='<js expression>' node harness.mjs eval  (window.__H helpers available)
    const expr = process.env.EVAL || 'CircuitJS1.getSimInfo()';
    const mark = s.markConsole(); const exMark = s.exceptions.length;
    let value, error;
    try { value = await s.eval(expr); } catch (e) { error = e.message; }
    log(JSON.stringify({ expr, value, error, console: s.consoleSince(mark).map((c) => c.text.slice(0, 400)), exceptions: s.exceptions.slice(exMark).map((e) => e.slice(0, 800)) }, null, 2));
    results.push({ name: 'eval', pass: !error, summary: {} });
  };
  const table = { undo: scenarioUndo, paste: scenarioPaste, sliders: scenarioSliders, loadstate: scenarioLoadState, roundtrip: scenarioRoundtrip, synth: scenarioSynth, textfid: scenarioTextFidelity, agent_docs: scenarioAgentDocs, agent_ids: scenarioAgentIds, agent_catalogue: scenarioAgentCatalogue, agent_edit: scenarioAgentEdit, agent_connect: scenarioAgentConnect, agent_connect_all: scenarioAgentConnectAll, agent_freerun: scenarioAgentFreeRun, geom_posts: scenarioGeomPosts, agent_history: scenarioAgentHistory, agent_run: scenarioAgentRun, agent_bg: scenarioAgentBackground, agent_files: scenarioAgentFiles, pin_names: scenarioPinNames, mcp_browser: scenarioMcpBrowser, mcp_dialog: scenarioMcpDialog, eval: scenarioEval };
  for (const name of scen) {
    if (!table[name]) { log(`unknown scenario ${name}`); continue; }
    try { await table[name](s); } catch (e) { report(name, false, { harnessError: e.message }); }
  }
  fs.writeFileSync(path.join(OUT_DIR, 'console.log'), s.console.map((c) => `[${c.type}] ${c.text}`).join('\n'));
  fs.writeFileSync(path.join(OUT_DIR, 'exceptions.log'), s.exceptions.join('\n---\n'));
  fs.writeFileSync(path.join(OUT_DIR, 'results.json'), JSON.stringify({ siteDir: SITE_DIR, startup, dialogs: s.dialogs, results }, null, 2));
  log(`uncaught page exceptions: ${s.exceptions.length}; alerts: ${s.dialogs.length}; results: ${path.join(OUT_DIR, 'results.json')}`);
  cdp.close();
  return results.every((r) => r.pass) ? 0 : 1;
}

main().then((code) => { killChildren(); process.exit(code); }).catch((e) => { console.error('HARNESS ERROR:', e.stack || e.message); killChildren(); process.exit(2); });
