#!/usr/bin/env node
// Headless live-verification harness for CircuitJS1 Desktop (GWT build).
//
// Serves SITE_DIR over a local static HTTP server, launches headless Chromium,
// drives the app through the Chrome DevTools Protocol and the window.CircuitJS1
// automation API, and runs verification scenarios.
//
// Usage:  node tests/live/harness.mjs [scenario ...]      (after `npm run buildgwt`)
// Scenarios: undo | paste | sliders | loadstate | textfid | roundtrip | synth | agent_docs | eval | all (default: all but eval)
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
      KeyZ: [90, 'z'], KeyY: [89, 'y'], KeyA: [65, 'a'], KeyC: [67, 'c'], KeyV: [86, 'v'], KeyD: [68, 'd'],
      Delete: [46, 'Delete'], Escape: [27, 'Escape'],
    };
    const [vk, key] = map[code];
    const modifiers = (ctrl ? 2 : 0) | (shift ? 8 : 0);
    const base = { modifiers, windowsVirtualKeyCode: vk, nativeVirtualKeyCode: vk, code, key: ctrl && key.length === 1 ? key : key };
    await this.cdp.send('Input.dispatchKeyEvent', { type: 'rawKeyDown', ...base });
    await this.cdp.send('Input.dispatchKeyEvent', { type: 'keyUp', ...base });
    await sleep(150);
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
    agentCallAsync(op, args) {
      return new Promise((resolve) => {
        let sync = true;
        CircuitJS1Agent.callAsync(op, JSON.stringify(args || {}), (r) => resolve({ sync, type: typeof r, result: JSON.parse(r) }));
        sync = false;
        setTimeout(() => resolve({ timeout: true }), 3000);
      });
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
    && out.sliderRefs[0] !== '' && out.sliderRefs[0] === out.sliderRefs[1] && out.sliderRefs[1] === out.sliderRefs[2];
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
      const jd3 = jsonElementDiff(safeParse(J1s), safeParse(J3s));
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

// ---------------------------------------------------------------- main
async function main() {
  const wanted = process.argv.slice(2);
  const scen = wanted.length && !wanted.includes('all') ? wanted : ['undo', 'paste', 'sliders', 'loadstate', 'textfid', 'roundtrip', 'synth', 'agent_docs'];
  fs.mkdirSync(OUT_DIR, { recursive: true });
  if (!fs.existsSync(path.join(SITE_DIR, 'circuitjs.html'))) throw new Error('SITE_DIR has no circuitjs.html: ' + SITE_DIR);
  log(`SITE_DIR=${SITE_DIR}\nOUT_DIR=${OUT_DIR}\nscenarios=${scen.join(',')}`);

  const httpPort = +(process.env.HTTP_PORT || (await freePort()));
  const cdpPort = +(process.env.CDP_PORT || (await freePort()));
  startChild('python3', ['-m', 'http.server', String(httpPort), '--directory', SITE_DIR, '--bind', '127.0.0.1'], 'http');
  const profile = path.join(OUT_DIR, 'profile');
  fs.rmSync(profile, { recursive: true, force: true });
  startChild(CHROMIUM, ['--headless=new', `--remote-debugging-port=${cdpPort}`, '--remote-debugging-address=127.0.0.1', `--user-data-dir=${profile}`,
    '--no-first-run', '--no-default-browser-check', '--disable-gpu', '--window-size=1400,900', 'about:blank'], 'chromium');

  const base = `http://127.0.0.1:${httpPort}`;
  await waitFor(async () => (await fetch(base + '/circuitjs.html')).ok, 15000, 'http server');
  await waitFor(async () => (await fetch(`http://127.0.0.1:${cdpPort}/json/version`)).ok, 20000, 'chromium CDP');
  const targets = await (await fetch(`http://127.0.0.1:${cdpPort}/json/list`)).json();
  const page = targets.find((t) => t.type === 'page');
  const cdp = new CDP(page.webSocketDebuggerUrl); await cdp.open();
  const s = new Session(cdp, base);
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
  const table = { undo: scenarioUndo, paste: scenarioPaste, sliders: scenarioSliders, loadstate: scenarioLoadState, roundtrip: scenarioRoundtrip, synth: scenarioSynth, textfid: scenarioTextFidelity, agent_docs: scenarioAgentDocs, eval: scenarioEval };
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
