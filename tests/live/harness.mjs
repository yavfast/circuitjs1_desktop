#!/usr/bin/env node
// Headless live-verification harness for CircuitJS1 Desktop (GWT build).
//
// Serves SITE_DIR over a local static HTTP server, launches headless Chromium,
// drives the app through the Chrome DevTools Protocol and the window.CircuitJS1
// automation API, and runs verification scenarios.
//
// Usage:  node tests/live/harness.mjs [scenario ...]      (after `npm run buildgwt`)
// Scenarios: undo | paste | sliders | loadstate | textfid | scope_float | roundtrip | synth | agent_docs | agent_ids | agent_catalogue | agent_edit | agent_connect | agent_connect_all | agent_overlap | agent_layout | render_text | agent_freerun | geom_posts | xfmr_draw | agent_axis | agent_history | agent_run | agent_bg | agent_files | pin_names | agent_defects | verify_defects | solver_defects | agent_models | agent_models_logic | agent_models_sub | json_models | agent_echo | mcp_browser | mcp_dialog | text_sites | render_pixels | layout_cost | import_cost | frame_cost | agent_equiv | eval | all (default: all but text_sites, render_pixels, layout_cost, import_cost, frame_cost, agent_equiv and eval)
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
  // Right-button click in viewport coordinates (hover first, so the editor picks the element);
  // opens the element's or scope's context menu.
  async mouseRightClick(x, y) {
    const m = (type, extra = {}) => this.cdp.send('Input.dispatchMouseEvent', { type, x, y, ...extra });
    await m('mouseMoved', { button: 'none' });
    await sleep(150);
    await m('mousePressed', { button: 'right', buttons: 2, clickCount: 1 });
    await m('mouseReleased', { button: 'right', clickCount: 1 });
    await sleep(300);
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
    // [PL_AGA_P16A] pixel compare of two PNGs (base64, no data: prefix). masks: image-pixel rects
    // [x1, y1, x2, y2] left out of the comparison. Returns the size, the number of differing
    // pixels, their bounding box and a PNG (base64) that shows the differences in red over a faded
    // copy of the first image.
    async pngDiff(a, b, masks) {
      const load = async (b64) => {
        const img = new Image(); img.src = 'data:image/png;base64,' + b64; await img.decode();
        const c = document.createElement('canvas'); c.width = img.width; c.height = img.height;
        const x = c.getContext('2d'); x.drawImage(img, 0, 0);
        return { w: img.width, h: img.height, d: x.getImageData(0, 0, img.width, img.height).data };
      };
      const A = await load(a), B = await load(b);
      if (A.w !== B.w || A.h !== B.h) return { sameSize: false, a: [A.w, A.h], b: [B.w, B.h], diff: -1 };
      const masked = (x, y) => (masks || []).some((m) => x >= m[0] && x <= m[2] && y >= m[1] && y <= m[3]);
      const c = document.createElement('canvas'); c.width = A.w; c.height = A.h;
      const ctx = c.getContext('2d'); const out = ctx.createImageData(A.w, A.h);
      let diff = 0, bx1 = Infinity, by1 = Infinity, bx2 = -1, by2 = -1;
      for (let y = 0; y < A.h; y++) {
        for (let x = 0; x < A.w; x++) {
          const i = (y * A.w + x) * 4;
          const same = A.d[i] === B.d[i] && A.d[i + 1] === B.d[i + 1] && A.d[i + 2] === B.d[i + 2] && A.d[i + 3] === B.d[i + 3];
          if (!same && !masked(x, y)) {
            diff++; bx1 = Math.min(bx1, x); by1 = Math.min(by1, y); bx2 = Math.max(bx2, x); by2 = Math.max(by2, y);
            out.data[i] = 255; out.data[i + 1] = 0; out.data[i + 2] = 0; out.data[i + 3] = 255;
          } else {
            const g = 255 - (255 - (A.d[i] + A.d[i + 1] + A.d[i + 2]) / 3) * 0.25;
            out.data[i] = out.data[i + 1] = out.data[i + 2] = g; out.data[i + 3] = 255;
          }
        }
      }
      let png = null;
      if (diff > 0) { ctx.putImageData(out, 0, 0); png = c.toDataURL('image/png').replace(/^data:image\/png;base64,/, ''); }
      return { sameSize: true, size: [A.w, A.h], diff, bbox: diff ? [bx1, by1, bx2, by2] : null, png };
    },
  };
  window.__H = H;
  return true;
}

// ---------------------------------------------------------------- diff helpers
// kind:name of every model line of a text export (34 diode, 32 transistor, ! logic, . subcircuit)
function modelLineKeys(t) {
  const kinds = { 34: 'diode', 32: 'transistor', '!': 'logic', '.': 'subcircuit' };
  const unescape = (x) => (x === '\\0' ? '' : x.replace(/\\(.)/g, (m, c) => ({ n: '\n', r: '\r', s: ' ', p: '+', q: '=', h: '#', a: '&' })[c] ?? c));
  return String(t).split('\n').map((l) => l.split(' ')).filter((w) => w.length > 1 && kinds[w[0]]).map((w) => kinds[w[0]] + ':' + unescape(w[1]));
}
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
  // [SP_AGA_03_12] the JSON models section carries every model the text writes as a model line,
  // and every in-circuit scope (403) line survives text -> JSON -> text
  const t1Models = modelLineKeys(T1);
  const j1Models = new Set(((J1 && J1.models) || []).map((m) => m.kind + ':' + m.name));
  const modelsLost = t1Models.filter((k) => !j1Models.has(k));
  const scope1 = L1.filter((l) => l.startsWith('403 ')), scope2 = new Set(L2.filter((l) => l.startsWith('403 ')));
  const scopeElmLost = scope1.filter((l) => !scope2.has(l));
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
    textModels: t1Models.length, modelsLost, jsonVersion: J1 && J1.schema && J1.schema.version, scopeElmLines: scope1.length, scopeElmLost: scopeElmLost.length,
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
  const agg = { circuits: recs.length, withModels: 0, withModelsLost: 0, modelsLost: {}, scopeElmLines: 0, scopeElmLost: 0, notVersion22: 0, withNonGeomLineDiffs: 0, withPropChanges: 0, withGeomChanges: 0, withClassDelta: 0, classDeltas: {}, propChangesByType: {}, withCountChange: 0, withLineDiffs: 0, withJsonDiffs: 0, withMissing: 0, withTypeChange: 0, changedKeysByType: {}, missingTypes: {}, typeChanges: {}, logTotals: { unknown: 0, skipping: 0, error: 0 } };
  for (const r of recs) {
    if (r.textModels) agg.withModels++;
    if ((r.modelsLost || []).length) { agg.withModelsLost++; agg.modelsLost[r.circuit] = r.modelsLost; }
    agg.scopeElmLines += r.scopeElmLines || 0; agg.scopeElmLost += r.scopeElmLost || 0;
    if (r.jsonVersion !== undefined && r.jsonVersion !== '2.2') agg.notVersion22++;
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
  report('C.json_roundtrip', pass, { circuits: agg.circuits, modelCircuits: agg.withModels, modelsLost: agg.withModelsLost, scopeElmLines: agg.scopeElmLines, scopeElmLost: agg.scopeElmLost, notVersion22: agg.notVersion22, countChanged: agg.withCountChange, lineDiffs: agg.withLineDiffs, nonGeomLineDiffs: agg.withNonGeomLineDiffs, geomChanged: agg.withGeomChanges, propChanged: agg.withPropChanges, classChanged: agg.withClassDelta, jsonDiffs: agg.withJsonDiffs, missing: agg.withMissing, typeChanged: agg.withTypeChange, jsonTypesWithChangedKeys: Object.keys(agg.changedKeysByType).length, logTotals: agg.logTotals, details: path.join(OUT_DIR, 'roundtrip_summary.json') });
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
  const agg = { circuits: 0, linesCompared: 0, lossy: 0, signFlips: 0, byType: {}, samples: [], scopeElmLines: 0, scopeElmMissing: [] };
  for (const name of list) {
    const raw = fs.readFileSync(path.join(SITE_DIR, 'circuitjs1/circuits', name), 'utf8');
    await s.call('loadExample', name);
    // a drawn in-circuit scope has stack position -1 (ScopeElm.draw): let the canvas draw it before
    // the export, so the 403 check covers the file's own position field (ScopeElm.dumpPosition)
    if (/^403\s/m.test(raw)) await sleep(800);
    const T1 = String(await s.call('exportText'));
    const t1 = new Map();
    for (const line of T1.split('\n')) { const toks = line.trim().split(/\s+/); if (isElm(toks)) { const k = elmKey(toks); if (!t1.has(k)) t1.set(k, toks); } }
    agg.circuits++;
    // In-circuit scope (403) lines are kept byte for byte: a scope whose load throws is dropped
    // silently by the user load path (fixed 2026-10-04: ScopeElm's scope had no document)
    const t1Lines = new Set(T1.split('\n').map((l) => l.trim()));
    for (const line of raw.split('\n')) {
      if (!/^403\s/.test(line.trim())) continue;
      agg.scopeElmLines++;
      if (!t1Lines.has(line.trim())) agg.scopeElmMissing.push(`${name}: ${line.trim()}`);
    }
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
  // own result line: text_fidelity already fails on accepted differences, which would hide this
  // a circuit list without a 403 line has nothing to check: PASS with skipped
  report('T.text_fidelity_scope', agg.scopeElmMissing.length === 0, { scopeElmLines: agg.scopeElmLines, scopeElmMissing: agg.scopeElmMissing.slice(0, 10), ...(agg.scopeElmLines === 0 ? { skipped: 'no 403 line in the circuit list' } : {}) });
}

// scope_float: an in-circuit scope made by the user (2026-10-04). lrc.txt -> element context menu
// "View in New Undocked Scope" on R1 -> the export has one 403 line with stack position -1 -> Ctrl+A,
// then its scope context menu "Dock Scope" -> exactly one element fewer, no 403 line, one more 'o'
// line -> Ctrl+Z brings the 403 line
// back unchanged; no page exception. Before the fix the undocked scope had no document and its
// first plot threw.
async function scenarioScopeFloat(s) {
  const out = { checks: {} };
  const ck = (name, cond) => { out.checks[name] = !!cond; return !!cond; };
  await resetApp(s);
  const exMark = s.exceptions.length;
  await s.call('loadExample', 'lrc.txt');
  await sleep(300);
  const lines = async () => String(await s.call('exportText')).split('\n').map((l) => l.trim()).filter(Boolean);
  const of = (ls, t) => ls.filter((l) => l.split(' ')[0] === t);
  // circuit coordinates -> viewport coordinates of the visible canvas
  const toView = async (x, y) => {
    const cr = await s.call('canvasRect');
    const v = (await s.call('visibleTab')).view;
    const k = cr.w / v.canvas.width; const t = v.transform;
    return [Math.round(cr.x + (t[0] * x + t[2] * y + t[4]) * k), Math.round(cr.y + (t[1] * x + t[3] * y + t[5]) * k)];
  };
  const l0 = await lines();
  const r1 = of(l0, 'r')[0].split(' ').map(Number);
  await s.call('focus');
  await s.mouseRightClick(...(await toView((r1[1] + r1[3]) / 2, (r1[2] + r1[4]) / 2)));
  out.floatMenu = await s.call('clickMenuPath', [menuTexts('View in New Undocked Scope')]);
  await sleep(800); // drawn at least once: a drawn embedded scope has position -1
  const l1 = await lines();
  const sc = of(l1, '403');
  out.floatLine = sc[0] || null;
  ck('floatMenuFound', out.floatMenu === 1);
  ck('oneScopeLine', sc.length === 1);
  // 403 x1 y1 x2 y2 flags elm_speed_value_flags_scaleV_scaleA_position_...
  ck('positionMinus1', sc.length === 1 && sc[0].split(' ')[6].split('_')[6] === '-1');
  ck('othersUnchanged', l1.length === l0.length + 1);
  if (sc.length === 1) {
    const g = sc[0].split(' ').map(Number);
    const logMark = await s.call('logCount');
    // with everything selected, Dock still removes only the scope element (not the selection)
    await s.call('focus');
    await s.key('KeyA', { ctrl: true });
    const n1 = await s.call('count');
    out.selectedBeforeDock = await s.call('selectedCount');
    await s.mouseRightClick(...(await toView((g[1] + g[3]) / 2, (g[2] + g[4]) / 2)));
    out.dockMenu = await s.call('clickMenuPath', [menuTexts('Dock Scope')]);
    await sleep(300);
    const l2 = await lines();
    out.countBeforeAfterDock = [n1, await s.call('count')];
    out.dockLogs = (await s.call('logsSince', logMark)).slice(-8);
    ck('selectAllBeforeDock', out.selectedBeforeDock > 1);
    ck('dockRemovesOnlyScope', out.countBeforeAfterDock[1] === n1 - 1);
    ck('dockMenuFound', out.dockMenu === 1);
    ck('dockedNoScopeLine', of(l2, '403').length === 0);
    ck('dockedOneMoreScope', of(l2, 'o').length === of(l1, 'o').length + 1);
    await s.call('focus');
    await s.key('KeyZ', { ctrl: true });
    await sleep(300);
    const l3 = await lines();
    out.undoLine = of(l3, '403')[0] || null;
    ck('undoRestoresScopeLine', of(l3, '403').length === 1 && of(l3, '403')[0] === sc[0] && of(l3, 'o').length === of(l1, 'o').length);
  }
  ck('noPageExceptions', s.exceptions.length === exMark);
  if (s.exceptions.length !== exMark) out.exceptions = s.exceptions.slice(exMark).map((e) => e.slice(0, 600));
  await resetApp(s);
  fs.writeFileSync(path.join(OUT_DIR, 'scope_float.json'), JSON.stringify(out, null, 2));
  const failed = Object.keys(out.checks).filter((n) => !out.checks[n]);
  report('SF.scope_float', failed.length === 0, { checks: Object.keys(out.checks).length, failed, details: path.join(OUT_DIR, 'scope_float.json') });
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

  // --- no_ground only without any ground reference (live series T9): logic elements, rails and
  // gates are referenced to ground internally; a battery circuit without Ground still raises it
  const ngCodes = async (elements) => {
    const imp = await A('importCircuit', { doc: B, circuit: { elements } });
    const c = await A('getConnectivity', { doc: B, includeNets: false });
    return { ok: imp.ok && c.ok, codes: codes(c.data && c.data.issues), implicitGround: c.data && c.data.implicitGround };
  };
  const ngLogic = await ngCodes([
    { id: 'IN1', type: 'LogicInput', start: { x: 0, y: 0 }, end: { x: -2, y: 0 } },
    { id: 'I1', type: 'Inverter', start: { x: 0, y: 0 }, end: { x: 4, y: 0 } },
    { id: 'O1', type: 'LogicOutput', start: { x: 4, y: 0 }, end: { x: 6, y: 0 } }]);
  const ngBattery = await ngCodes([
    { id: 'V1', type: 'DCVoltage', start: { x: 0, y: 4 }, end: { x: 0, y: 0 } },
    { id: 'R1', type: 'Resistor', start: { x: 0, y: 0 }, end: { x: 4, y: 0 } },
    { id: 'R2', type: 'Resistor', start: { x: 4, y: 0 }, end: { x: 4, y: 4 } },
    { id: 'W1', type: 'Wire', start: { x: 4, y: 4 }, end: { x: 0, y: 4 } }]);
  const ngRail = await ngCodes([
    { id: 'RL1', type: 'Rail', start: { x: 0, y: 0 }, end: { x: 0, y: -2 } },
    { id: 'R1', type: 'Resistor', start: { x: 0, y: 0 }, end: { x: 4, y: 0 } }]);
  // an op-amp output references ground internally, but the battery makes the simulator assume one
  const ngOpAmp = await ngCodes([
    { id: 'V1', type: 'DCVoltage', start: { x: -3, y: 4 }, end: { x: -3, y: 1 } },
    { id: 'W1', type: 'Wire', start: { x: -3, y: 1 }, end: { x: 0, y: 1 } },
    { id: 'OA1', type: 'OpAmp', start: { x: 0, y: 0 }, end: { x: 4, y: 0 } },
    { id: 'R1', type: 'Resistor', start: { x: 0, y: -1 }, end: { x: 4, y: -1 } },
    { id: 'W2', type: 'Wire', start: { x: 4, y: -1 }, end: { x: 4, y: 0 } },
    { id: 'R2', type: 'Resistor', start: { x: 4, y: 0 }, end: { x: 4, y: 4 } },
    { id: 'W3', type: 'Wire', start: { x: 4, y: 4 }, end: { x: -3, y: 4 } }]);
  out.notes.noGround = { ngLogic, ngBattery, ngRail, ngOpAmp };
  ck('noGroundLogicOnly', ngLogic.ok && !ngLogic.codes.includes('no_ground'));
  ck('noGroundBattery', ngBattery.ok && ngBattery.codes.includes('no_ground') && ngBattery.implicitGround === true);
  ck('noGroundRail', ngRail.ok && !ngRail.codes.includes('no_ground') && ngRail.codes.includes('dangling_post'));
  ck('noGroundOpAmpBattery', ngOpAmp.ok && ngOpAmp.codes.includes('no_ground') && ngOpAmp.implicitGround === true);

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
  const symbolOverlap = {};
  // [PL_AGA_P16A] checkLayout per example: text_overlap messages (the calibration input) and
  // the classes that are not covered yet
  const textOverlap = {};
  const textNotCovered = {};
  stats.layoutTexts = 0; stats.layoutMs = 0;
  const noGround = [];
  const rejected = [];
  const scopeElmMissing = [];
  stats.scopeElmLines = 0;
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
    const tl0 = Date.now();
    const lay = await A('checkLayout', { doc });
    stats.layoutMs += Date.now() - tl0;
    if (lay.ok) {
      stats.layoutTexts += lay.data.texts;
      const to = lay.data.issues.filter((i) => i.code === 'text_overlap');
      if (to.length) textOverlap[name] = to.map((i) => ({ pair: i.elements.join('|'), at: i.at, message: i.message }));
      // LAYOUT_RENDERS=1: the render of each flagged example (PNG, scale 2) with the issue points
      // in image pixels, for the calibration review (OUT_DIR/layout_calibration/)
      if (to.length && process.env.LAYOUT_RENDERS) {
        const dir = path.join(OUT_DIR, 'layout_calibration');
        fs.mkdirSync(dir, { recursive: true });
        const sv = await s.call('agentAsync', 'render', { doc, format: 'svg' }, 60000);
        const png = await s.call('agentAsync', 'render', { doc, format: 'png', scale: 2 }, 60000);
        const area = sv.data ? svgArea(sv.data.content) : null;
        if (png.data && area) {
          fs.writeFileSync(path.join(dir, name.replace(/\.txt$/, '.png')), Buffer.from(png.data.content, 'base64'));
          fs.writeFileSync(path.join(dir, name.replace(/\.txt$/, '.json')), JSON.stringify(to.map((i) => ({ ...i,
            px: [(i.at.x * 16 - area[0]) * 2, (i.at.y * 16 - area[1]) * 2] })), null, 2));
        }
      }
      for (const i of lay.data.issues.filter((x) => x.code === 'text_not_covered')) {
        const cls = (i.message.match(/\(class (\w+)\)/) || [])[1] || '?';
        textNotCovered[cls] = (textNotCovered[cls] || 0) + 1;
      }
    }
    stats.ms += Date.now() - t0;
    stats.circuits++;
    if (!imp.ok) { stats.importRejected++; rejected.push({ name, issues: (imp.issues || []).map((i) => i.code + ': ' + i.message) }); }
    // In-circuit scopes (403) load into the background document and export byte for byte: their
    // plotted elements resolve in that document, not in the visible tab (2026-10-04, qam-256.txt)
    const scopeLines = text.split('\n').map((l) => l.trim()).filter((l) => /^403\s/.test(l));
    if (scopeLines.length && imp.ok) {
      const ex = await A('exportCircuit', { doc, format: 'text' });
      const got = new Set(String((ex.data && ex.data.content) || '').split('\n').map((l) => l.trim()));
      stats.scopeElmLines += scopeLines.length;
      for (const l of scopeLines) if (!got.has(l)) scopeElmMissing.push(`${name}: ${l}`);
    }
    const results = [imp, con, gc, dg, rd, lay];
    const problem = results.some((r) => r.__undefined) || !con.ok || !gc.ok || !dg.ok || !rd.ok || !lay.ok
      || (imp.ok && !imp.connectivity) || s.exceptions.length !== ex0
      || (con.ok && con.data.analysed && recs(gc).some((e) => e.posts.some((p) => typeof p.net !== 'string')));
    if (con.ok) {
      stats.nets += con.data.nets.length; stats.issues += con.data.issues.length;
      if (!con.data.analysed) stats.notAnalysed++;
      for (const i of con.data.issues) byCode[i.code] = (byCode[i.code] || 0) + 1;
      if (con.data.issues.some((i) => i.code === 'no_ground')) noGround.push(name);
      const ov = con.data.issues.filter((i) => i.code === 'symbol_overlap');
      if (ov.length) symbolOverlap[name] = ov.map((i) => i.message);
    }
    if (problem) bad.push({ name, imp: imp.ok, con: con.ok, gc: gc.ok, dg: dg.ok, rd: rd.ok, conIssues: (con.issues || []).map((i) => i.code), exceptions: s.exceptions.slice(ex0).map((e) => e.slice(0, 300)) });
  }
  await A('closeDocument', { doc, discardChanges: true });
  const vis1 = await s.call('visibleTab');
  const visibleSame = JSON.stringify(vis0) === JSON.stringify(vis1);
  // [SP_AGA_05_01] example corpus: each example reporting text_overlap is in the calibration list
  // with the same pairs (LAYOUT_CALIBRATION); no other example reports one
  const uncalibrated = Object.keys(textOverlap).filter((n) => JSON.stringify(textOverlap[n].map((i) => i.pair).sort())
    !== JSON.stringify((LAYOUT_CALIBRATION[n] || []).map((c) => c.pair).sort()));
  const missingCalibrated = list.filter((n) => LAYOUT_CALIBRATION[n] && !textOverlap[n]);
  fs.writeFileSync(path.join(OUT_DIR, 'agent_connect_all.json'), JSON.stringify({ stats, byCode, bad, rejected, scopeElmMissing, symbolOverlap, textOverlap, uncalibrated, missingCalibrated, textNotCovered, noGround }, null, 2));
  report('AG.agent_connect_all', bad.length === 0 && rejected.length === 0 && scopeElmMissing.length === 0 && visibleSame && s.exceptions.length === exMark
    && uncalibrated.length === 0 && missingCalibrated.length === 0,
    { ...stats, bad: bad.length, rejected: rejected.map((r) => r.name), scopeElmMissing: scopeElmMissing.length, visibleSame, textOverlap: Object.keys(textOverlap).length,
      uncalibrated: uncalibrated.slice(0, 10), missingCalibrated, details: path.join(OUT_DIR, 'agent_connect_all.json') });
  function recs(r) { return (r && r.data && r.data.elements) || []; }
}

// ---------------------------------------------------------------- agent_overlap (live agent series 2026-10-04)
// SP_AGA_03_05 symbol_overlap: a wire through a symbol (the T2 ground lying on a rail wire, a wire
// across a resistor), overlapping symbols, a junction post on a resistor body; clean drawings
// (leads meeting at posts, transistor and op-amp pins, a diamond bridge, hanging labels, parallel
// resistors, leads crossed by a wire); the mutation delta; the same issues in a background and in
// the visible document; bundled examples drawn without overlaps stay clean.
const ovW = (id, x1, y1, x2, y2) => ({ id, type: 'Wire', start: { x: x1, y: y1 }, end: { x: x2, y: y2 } });
const ovE = (id, type, x1, y1, x2, y2, properties) => ({ id, type, start: { x: x1, y: y1 }, end: { x: x2, y: y2 }, ...(properties ? { properties } : {}) });
const OVERLAP_CASES = {
  // live series T2: Ground G1 drawn horizontally along the bottom rail wire
  groundOnRail: [ovE('G1', 'Ground', 8, 8, 12, 8), ovW('W4', 8, 8, 16, 8), ovE('R1', 'Resistor', 16, 8, 16, 4), ovW('W5', 16, 4, 8, 4), ovW('W6', 8, 4, 8, 8)],
  wireThroughResistor: [ovE('R1', 'Resistor', 0, 0, 4, 0), ovW('W1', 2, -2, 2, 2)],
  resistorsCrossing: [ovE('R1', 'Resistor', 0, 0, 4, 0), ovE('R2', 'Resistor', 2, -2, 2, 2)],
  junctionOnResistor: [ovE('R1', 'Resistor', 0, 0, 4, 0), ovW('W1', 2, 3, 2, 0)],
  // a default pot: its wiper post one cell off the body leaves a 16 px dimension
  wireAcrossPot: [ovE('P1', 'Potentiometer', 0, 0, 4, 0), ovW('W1', 1, -2, 1, 2)],
};
const OVERLAP_CLEAN = {
  endToEndAndRightAngle: [ovW('W1', -3, 0, 0, 0), ovE('R1', 'Resistor', 0, 0, 4, 0), ovW('W2', 4, 0, 7, 0), ovE('C1', 'Capacitor', 4, 0, 4, 4)],
  transistorPins: [ovE('Q1', 'TransistorNPN', 0, 0, 4, 0), ovW('W1', -3, 0, 0, 0), ovW('W2', 4, -1, 4, -4), ovW('W3', 4, 1, 4, 4)],
  opAmpPins: [ovE('OA1', 'OpAmp', 0, 0, 4, 0), ovW('W1', -3, -1, 0, -1), ovW('W2', -3, 1, 0, 1), ovW('W3', 4, 0, 5, 0), ovW('W4', 5, 0, 7, 0),
    ovW('W5', 0, -1, 0, -3), ovW('W6', 0, -3, 5, -3), ovW('W7', 5, -3, 5, 0)],
  diamondBridge: [ovE('D1', 'Diode', 0, 4, 4, 0), ovE('D2', 'Diode', 4, 8, 8, 4), ovE('D3', 'Diode', 4, 8, 0, 4), ovE('D4', 'Diode', 8, 4, 4, 0),
    ovW('W1', 4, 0, 4, -3), ovW('W2', 4, 8, 4, 11), ovW('W3', 0, 4, -3, 4), ovW('W4', 8, 4, 11, 4)],
  hangingLabelAndGround: [ovW('W1', 0, 0, 4, 0), ovE('G1', 'Ground', 4, 0, 4, 1), ovE('L1', 'LabeledNode', 0, 0, -2, 0, { label: 'in' }),
    ovW('W2', 0, 0, 0, -3), ovE('L2', 'LabeledNode', 0, -3, 0, -4, { label: 'top' })],
  potWiring: [ovE('P1', 'Potentiometer', 0, 0, 4, 0), ovW('W1', -3, 0, 0, 0), ovW('W2', 4, 0, 7, 0), ovW('W3', 2, -1, 2, -4)],
  parallelResistors: [ovE('R1', 'Resistor', 0, 0, 4, 0), ovE('R2', 'Resistor', 0, 2, 4, 2), ovW('W1', 0, 0, 0, 2), ovW('W2', 4, 0, 4, 2)],
  // a wire crossing a long resistor's lead and a long label's stem: crossing leads, like crossing wires
  leadsCrossed: [ovE('R1', 'Resistor', 0, 0, 0, 8), ovW('W1', -2, 1, 2, 1), ovE('L1', 'LabeledNode', 6, 0, 6, 8, { label: 'x' }), ovW('W2', 4, 2, 8, 2)],
};
const OVERLAP_CLEAN_EXAMPLES = ['fullrectf.txt', 'voltdivide.txt', 'amp-invert.txt', 'npn.txt', 'filt-lopass.txt'];

async function scenarioAgentOverlap(s) {
  const out = { checks: {}, notes: {} };
  const ck = (name, cond) => { out.checks[name] = !!cond; return !!cond; };
  const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
  const A = (op, args) => s.call('agentCall', op, args);
  const overlaps = (list) => (list || []).filter((i) => i.code === 'symbol_overlap');
  await resetApp(s);
  const exMark = s.exceptions.length;
  const A0 = (await A('listDocuments', {})).data.documents.find((d) => d.active).doc;
  const visibleText = await s.eval('CircuitJS1.exportCircuit()');
  const B = (await A('createDocument', { title: 'Overlap B' })).data.doc;
  const run = async (doc, elements) => {
    const imp = await A('importCircuit', { doc, circuit: { elements } });
    const con = await A('getConnectivity', { doc, includeNets: false });
    return { imp, con, ov: overlaps(con.data && con.data.issues) };
  };

  // --- reported overlaps: one issue per pair, ids sorted, warning, a cell point and a hint
  const wellFormed = (i, ids) => i.severity === 'warning' && same(i.elements, ids) && i.at && typeof i.at.x === 'number'
    && typeof i.hint === 'string' && /only meet at posts/.test(i.hint) && ids.every((id) => i.message.includes(id));
  for (const [name, elements] of Object.entries(OVERLAP_CASES)) {
    const r = await run(B, elements);
    out.notes[name] = r.ov.map((i) => i.message);
    const ids = { groundOnRail: ['G1', 'W4'], wireThroughResistor: ['R1', 'W1'], resistorsCrossing: ['R1', 'R2'], junctionOnResistor: ['R1', 'W1'], wireAcrossPot: ['P1', 'W1'] }[name];
    const hit = r.ov.filter((i) => same(i.elements, ids));
    ck(name, r.imp.ok && r.con.ok && r.ov.length === 1 && hit.length === 1 && wellFormed(hit[0], ids)
      && (name !== 'groundOnRail' || (/Ground/.test(hit[0].message) && same(hit[0].at, { x: 12, y: 8 })))
      && (name !== 'wireThroughResistor' || same(hit[0].at, { x: 2, y: 0 }))
      && (name !== 'junctionOnResistor' || /Post W1\.b/.test(hit[0].message)));
  }

  // --- clean drawings
  for (const [name, elements] of Object.entries(OVERLAP_CLEAN)) {
    const r = await run(B, elements);
    if (r.ov.length) out.notes[name] = r.ov.map((i) => i.message);
    ck('clean_' + name, r.imp.ok && r.con.ok && r.ov.length === 0);
  }

  // --- delta: moving the wire off the resistor clears the issue; moving it back adds it again
  await run(B, OVERLAP_CASES.wireThroughResistor);
  const mvOff = await A('applyEdits', { doc: B, edits: [{ op: 'move', id: 'W1', by: { dx: 4, dy: 0 } }] });
  const mvBack = await A('applyEdits', { doc: B, edits: [{ op: 'move', id: 'W1', by: { dx: -4, dy: 0 } }] });
  const keyOf = (l) => overlaps(l).map((i) => i.key);
  ck('deltaCleared', mvOff.ok && keyOf(mvOff.connectivity.cleared).length === 1 && overlaps(mvOff.connectivity.added).length === 0
    && same(overlaps(mvOff.connectivity.cleared)[0].elements, ['R1', 'W1']));
  ck('deltaAdded', mvBack.ok && same(keyOf(mvBack.connectivity.added), keyOf(mvOff.connectivity.cleared)) && mvBack.connectivity.warningCount >= 1);
  // moving the crossing wire along the symbol keeps the issue key (at = centre of the crossed symbol)
  const mvAlong = await A('applyEdits', { doc: B, edits: [{ op: 'move', id: 'W1', by: { dx: 0.5, dy: 0 } }] });
  ck('deltaStableWhileOverlapping', mvAlong.ok && overlaps(mvAlong.connectivity.added).length === 0 && overlaps(mvAlong.connectivity.cleared).length === 0
    && mvAlong.connectivity.warningCount === mvBack.connectivity.warningCount);

  // --- background and visible document give the same issues (geometry only, no draw-time bounding box)
  const all = [];
  [...Object.values(OVERLAP_CASES), OVERLAP_CLEAN.diamondBridge, OVERLAP_CLEAN.transistorPins, OVERLAP_CLEAN.potWiring].forEach((elements, k) => {
    const dx = 20 * k;
    for (const e of elements) all.push({ ...e, id: e.id + '_' + k, start: { x: e.start.x + dx, y: e.start.y }, end: { x: e.end.x + dx, y: e.end.y } });
  });
  const rb = await run(B, all);
  const rv = await run(A0, all);
  await sleep(300); // the visible tab draws (bounding boxes) before the second read
  const cv = await A('getConnectivity', { doc: A0, includeNets: false });
  const keys = (c) => (c.data.issues || []).map((i) => i.key).sort();
  out.notes.backgroundVsVisible = { background: overlaps(rb.con.data.issues).length, visible: overlaps(cv.data.issues).length };
  ck('backgroundSameAsVisible', rb.imp.ok && rv.imp.ok && cv.ok && rb.ov.length === Object.keys(OVERLAP_CASES).length && same(keys(rb.con), keys(cv)) && same(keys(rv.con), keys(cv)));

  // --- bundled examples drawn without overlaps stay clean
  const dirty = {};
  for (const name of OVERLAP_CLEAN_EXAMPLES) {
    const text = await s.eval(`__H.fetchText('/circuitjs1/circuits/' + ${JSON.stringify(name)})`);
    await A('importCircuit', { doc: B, circuit: text });
    const con = await A('getConnectivity', { doc: B, includeNets: false });
    const ov = overlaps(con.data && con.data.issues);
    if (!con.ok || ov.length) dirty[name] = con.ok ? ov.map((i) => i.message) : con.issues;
  }
  out.notes.examples = dirty;
  ck('examplesClean', Object.keys(dirty).length === 0);

  await A('closeDocument', { doc: B, discardChanges: true });
  // restore the visible document's circuit
  const restored = await A('importCircuit', { doc: A0, circuit: visibleText });
  ck('visibleRestored', restored.ok && (await s.eval('CircuitJS1.exportCircuit()')) === visibleText);
  ck('noPageExceptions', s.exceptions.length === exMark);
  if (s.exceptions.length !== exMark) out.exceptions = s.exceptions.slice(exMark).map((e) => e.slice(0, 600));
  fs.writeFileSync(path.join(OUT_DIR, 'agent_overlap.json'), JSON.stringify(out, null, 2));
  const failed = Object.keys(out.checks).filter((n) => !out.checks[n]);
  report('AG.agent_overlap', failed.length === 0, { checks: Object.keys(out.checks).length, failed, details: path.join(OUT_DIR, 'agent_overlap.json') });
}

// ---------------------------------------------------------------- render_text (live agent series 2026-10-04)
// One-post elements drawn with a 1-cell lead (BaseCircuitElm.leadFraction): their text lies on the
// `end` side of the post in all four directions. Each element is rendered alone as SVG through the
// agent `render` op; inside the drawing group SVG coordinates are editor pixels, so the post is at
// (0, 0). Every text's estimated box centre must lie on the end side; for up/down the whole box
// (font-size high), for right a text that is not middle-anchored and for left a start-anchored
// text (at least 0.45 em per character wide) must not reach past the post. No stroked line
// starting at the post has a point behind it. The lit LED shows a lit-coloured triangle on its axis
// and two arrow heads on one side, inside the render area. Also writes visual samples (LED lit and
// unlit, labels, AM source) to OUT_DIR/render_text/.
const RENDER_TEXT_TYPES = ['LabeledNode', 'TestPoint', 'Output', 'StopTrigger', 'Rail', 'AMSource', 'FMSource', 'SweepGenerator', 'AudioOutput', 'LogicOutput'];
const RENDER_TEXT_DIRS = { right: [1, 0], left: [-1, 0], down: [0, 1], up: [0, -1] };

function svgTexts(svg) {
  return [...svg.matchAll(/<text([^>]*)>([^<]*)<\/text>/g)].map((m) => {
    const attr = (k) => { const mm = m[1].match(new RegExp(' ' + k + '="([^"]*)"')); return mm ? mm[1] : null; };
    return { text: m[2], x: +attr('x'), y: +attr('y'), anchor: attr('text-anchor') || 'start', baseline: attr('dominant-baseline') || 'alphabetic',
      size: parseFloat(attr('font-size') || '12'), transform: attr('transform') };
  });
}

// @return the polylines of the SVG paths (M/L points only; arcs ignored) with their fill
function svgPaths(svg) {
  return [...svg.matchAll(/<path([^>]*)\/?>/g)].map((m) => {
    const fill = (m[1].match(/ fill="([^"]*)"/) || [])[1] || 'none';
    const d = (m[1].match(/ d="([^"]*)"/) || [])[1] || '';
    const pts = [];
    const re = /([ML])\s*(-?[\d.]+(?:e-?\d+)?)\s+(-?[\d.]+(?:e-?\d+)?)/g;
    let mm;
    while ((mm = re.exec(d))) pts.push([+mm[2], +mm[3]]);
    return { fill, pts, closed: /Z/.test(d), arc: /A/.test(d) };
  });
}

// @return the drawing area in editor pixels: [x0, y0, x1, y1] from the root size and the group translate
function svgArea(svg) {
  const size = svg.match(/<svg[^>]* width="([\d.]+)" height="([\d.]+)"/);
  const tr = svg.match(/<g transform="scale\(([\d.]+),[\d.]+\) translate\((-?[\d.]+),(-?[\d.]+)\)"/);
  if (!size || !tr) return null;
  const k = +tr[1];
  return [-tr[2], -tr[3], -tr[2] + size[1] / k, -tr[3] + size[2] / k];
}

// @return the problems of one text relative to a post at (0, 0) whose end lies in direction d
function textSideProblems(t, d) {
  const w = 0.45 * t.size * t.text.length;
  const x0 = t.anchor === 'middle' ? t.x - w / 2 : t.anchor === 'end' ? t.x - w : t.x;
  const yTop = t.baseline === 'central' || t.baseline === 'middle' ? t.y - t.size / 2 : t.y - 0.75 * t.size;
  const cx = x0 + w / 2, cy = yTop + t.size / 2;
  const p = [];
  if (t.transform) p.push('transformed text');
  if (cx * d[0] + cy * d[1] <= 0) p.push('centre not on the end side');
  if (d[1] === 1 && yTop < -1) p.push('box reaches above the post');
  if (d[1] === -1 && yTop + t.size > 1) p.push('box reaches below the post');
  // a middle-anchored text is centred on a symbol at end (AM circle, AudioOutput box): only its centre counts
  if (d[0] === 1 && t.anchor !== 'middle' && x0 < -1) p.push('box starts left of the post');
  if (d[0] === -1 && t.anchor === 'start' && t.x + w > 1) p.push('start-anchored text reaches right of the post');
  return p;
}

async function scenarioRenderText(s) {
  const out = { checks: {}, notes: { bad: [], noText: [], leadsBehind: [] } };
  const ck = (name, cond) => { out.checks[name] = !!cond; return !!cond; };
  const A = (op, args) => s.call('agentCall', op, args);
  const render = (args) => s.call('agentAsync', 'render', args, 30000);
  await resetApp(s);
  const exMark = s.exceptions.length;
  const dir = path.join(OUT_DIR, 'render_text');
  fs.mkdirSync(dir, { recursive: true });
  const doc = (await A('createDocument', { title: 'Render text' })).data.doc;
  let cases = 0, rendered = 0, leadsChecked = 0;
  for (const type of RENDER_TEXT_TYPES) {
    for (const [dn, d] of Object.entries(RENDER_TEXT_DIRS)) {
      cases++;
      const imp = await A('importCircuit', { doc, circuit: { elements: [{ id: 'E1', type, start: { x: 0, y: 0 }, end: { x: d[0], y: d[1] } }] } });
      const r = await render({ doc, format: 'svg' });
      const svg = r && r.data ? r.data.content : '';
      if (!imp.ok || !svg || !/<g transform="scale\(1,1\) translate\(/.test(svg)) { out.notes.bad.push({ type, dn, imp: imp.ok, render: !!svg }); continue; }
      rendered++;
      const texts = svgTexts(svg);
      if (!texts.length) out.notes.noText.push(type + ' ' + dn);
      for (const t of texts) {
        const p = textSideProblems(t, d);
        if (p.length) out.notes.bad.push({ type, dn, text: t, problems: p });
      }
      // a lead drawn from the post must not run behind it
      for (const pa of svgPaths(svg)) {
        if (pa.fill !== 'none' || pa.arc || !pa.pts.length || Math.hypot(pa.pts[0][0], pa.pts[0][1]) > 0.5) continue;
        leadsChecked++;
        const behind = pa.pts.filter((q) => q[0] * d[0] + q[1] * d[1] < -1);
        if (behind.length) out.notes.leadsBehind.push({ type, dn, pts: pa.pts });
      }
    }
  }
  ck('allRendered', rendered === cases);
  ck('textOnEndSide', out.notes.bad.length === 0);
  out.notes.leadsChecked = leadsChecked;
  ck('noLeadBehindPost', leadsChecked >= cases && out.notes.leadsBehind.length === 0);
  ck('labelsHaveText', !out.notes.noText.some((c) => /^(LabeledNode|TestPoint|Rail|StopTrigger) /.test(c)));

  // visual samples (not asserted beyond a successful render)
  const save = async (name, elements, run) => {
    await A('importCircuit', { doc, circuit: { elements } });
    if (run) await s.call('agentAsync', 'run', { doc, span: '5 ms', reset: true }, 30000);
    let svg = null;
    for (const format of ['png', 'svg']) {
      const r = await render({ doc, format, scale: format === 'png' ? 2 : 1 });
      const c = r && r.data ? r.data.content : null;
      if (!c) continue;
      if (format === 'png') fs.writeFileSync(path.join(dir, name + '.png'), Buffer.from(c.replace(/^data:image\/png;base64,/, ''), 'base64'));
      else { fs.writeFileSync(path.join(dir, name + '.svg'), c); svg = c; }
    }
    return svg;
  };
  const ledLoop = (open) => [
    { id: 'V1', type: 'DCVoltage', start: { x: 0, y: 4 }, end: { x: 0, y: 0 }, properties: { max_voltage: 5 } },
    { id: 'R1', type: 'Resistor', start: { x: 0, y: 0 }, end: { x: 4, y: 0 }, properties: { resistance: 220 } },
    { id: 'LED1', type: 'LED', start: { x: 4, y: 0 }, end: { x: 4, y: 4 } },
    { id: 'W1', type: 'Wire', start: { x: 4, y: 4 }, end: { x: open ? 2 : 0, y: 4 } },
    ...(open ? [{ id: 'W2', type: 'Wire', start: { x: 1, y: 4 }, end: { x: 0, y: 4 } }] : []),
    { id: 'G1', type: 'Ground', start: { x: 0, y: 4 }, end: { x: 0, y: 5 } },
  ];
  const labels = Object.entries(RENDER_TEXT_DIRS).flatMap(([dn, d], k) => [
    { id: 'L' + (k + 1), type: 'LabeledNode', start: { x: 6 * k, y: 0 }, end: { x: 6 * k + d[0], y: d[1] }, properties: { label: dn } },
    { id: 'TP' + (k + 1), type: 'TestPoint', start: { x: 6 * k, y: 6 }, end: { x: 6 * k + d[0], y: 6 + d[1] } },
  ]);
  const am = [{ id: 'AM1', type: 'AMSource', start: { x: 0, y: 0 }, end: { x: 0, y: -1 } }, { id: 'R1', type: 'Resistor', start: { x: 0, y: 0 }, end: { x: 0, y: 4 } },
    { id: 'G1', type: 'Ground', start: { x: 0, y: 4 }, end: { x: 0, y: 5 } }];
  const saved = [await save('led_lit', ledLoop(false), true), await save('led_unlit', ledLoop(true), true),
    await save('labels_1cell_4dir', labels, false), await save('am_source_1cell', am, true)];
  ck('visualsSaved', saved.every(Boolean));
  out.notes.visuals = dir;

  // lit LED (axis x = 64, y 0..64): a lit-coloured triangle on the axis and two arrow heads on one
  // side (IEC 60617), all inside the render area
  const led = { tri: [], arrows: [], area: saved[0] ? svgArea(saved[0]) : null };
  if (saved[0]) {
    for (const pa of svgPaths(saved[0])) {
      if (pa.fill === 'none' || pa.arc || !pa.closed || pa.pts.length !== 3) continue;
      const cx = pa.pts.reduce((a, q) => a + q[0], 0) / 3, cy = pa.pts.reduce((a, q) => a + q[1], 0) / 3;
      if (cy <= 0 || cy >= 64) continue;
      if (Math.abs(cx - 64) < 2) led.tri.push({ fill: pa.fill, pts: pa.pts });
      else if (Math.abs(cx - 64) > 6 && Math.abs(cx - 64) < 40) led.arrows.push({ side: Math.sign(cx - 64), pts: pa.pts });
    }
  }
  const inArea = (q) => led.area && q[0] >= led.area[0] && q[0] <= led.area[2] && q[1] >= led.area[1] && q[1] <= led.area[3];
  out.notes.led = led;
  ck('ledLitSymbol', led.tri.some((t) => !/^#(000000|ffffff)$/i.test(t.fill)) && led.arrows.length === 2
    && led.arrows[0].side === led.arrows[1].side && led.arrows.every((a) => a.pts.every(inArea)));

  await A('closeDocument', { doc, discardChanges: true });
  ck('noPageExceptions', s.exceptions.length === exMark);
  if (s.exceptions.length !== exMark) out.exceptions = s.exceptions.slice(exMark).map((e) => e.slice(0, 600));
  fs.writeFileSync(path.join(OUT_DIR, 'render_text.json'), JSON.stringify(out, null, 2));
  const failed = Object.keys(out.checks).filter((n) => !out.checks[n]);
  report('AG.render_text', failed.length === 0, { checks: Object.keys(out.checks).length, failed, cases, details: path.join(OUT_DIR, 'render_text.json') });
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

// xfmr_draw: transformer drawings keep the core between the windings and the coils facing it.
// Since dde7f33 (geometry refactor) CustomTransformer interpolated its core along point1-point2
// after moving point2 to the handle corner, so the core ran diagonally across the box and the
// primary coil (and its label) turned away from it; Transformer took its coil bulge sign from the
// diagonal handle box instead of the winding axis, so a flipped horizontal transformer drew its
// coils facing outwards. Each case is rendered (Agent API `render`, PNG) with the 2-D canvas
// calls recorded in device coordinates; the checks are geometric, not pixel comparisons:
// coil half-arcs lie on exactly two winding lines, every arc bulges towards the other winding,
// exactly two straight core lines lie between the windings, parallel to them and within their
// span. Posts must stay where the dde7f33..60b9b21 builds put them (positions are saved in files).
const XFMR_OPTIONS = '$ 1 0.000005 10.20027730826997 50 5 50 5e-11';
const XFMR_CT = '0.000047 0.98 1:40\\p40,100 0';
const XFMR_T = '4 1 0 0 0.99';
const XFMR_P = '4 1 0 0 0 0.99 64 32'; // spacing 64 / tap 32: what the editor saves on the 16 px grid
const XFMR_DRAW_CASES = [
  // CustomTransformer (406): primary on the start side, stacks drawn from the start y outwards
  ['ct_conv', `406 128 128 192 24 0 ${XFMR_CT}`, 'pin1=128,128 pin2=128,24 pin3=192,128 pin4=192,96 pin5=192,64 pin6=192,56 pin7=192,24'],
  ['ct_conv_rev', `406 192 128 128 24 0 ${XFMR_CT}`, 'pin1=192,128 pin2=192,24 pin3=128,128 pin4=128,96 pin5=128,64 pin6=128,56 pin7=128,24'],
  ['ct_conv_flip', `406 128 24 192 128 1 ${XFMR_CT}`, 'pin1=128,24 pin2=128,128 pin3=192,24 pin4=192,56 pin5=192,88 pin6=192,96 pin7=192,128'],
  ['ct_conv_flip_rev', `406 192 24 128 128 1 ${XFMR_CT}`, 'pin1=192,24 pin2=192,128 pin3=128,24 pin4=128,56 pin5=128,88 pin6=128,96 pin7=128,128'],
  ['ct_default', '406 160 192 192 160 0 1 0.99 1,1:1 0', 'pin1=160,192 pin2=160,184 pin3=160,168 pin4=160,152 pin5=192,192 pin6=192,152'],
  ['ct_2w_small', '406 160 192 192 176 0 1 0.99 1:2 0', 'pin1=160,192 pin2=160,176 pin3=192,192 pin4=192,176'],
  ['ct_2w_long', '406 160 192 288 128 0 1 0.99 1:2 0', 'pin1=160,192 pin2=160,128 pin3=288,192 pin4=288,128'],
  ['ct_4w', '406 160 256 256 96 0 1 0.99 1,2:3,4 0', 'pin1=160,256 pin2=160,180 pin3=160,164 pin4=160,96 pin5=256,256 pin6=256,180 pin7=256,172 pin8=256,96'],
  ['ct_taps', '406 160 256 224 128 0 1 0.99 1\\p1:2\\p2\\p2 0', 'pin1=160,256 pin2=160,213 pin3=160,127 pin4=224,256 pin5=224,213 pin6=224,170 pin7=224,127'],
  ['ct_neg', '406 160 256 224 160 0 1 0.99 1:-2 0', 'pin1=160,256 pin2=160,160 pin3=224,256 pin4=224,160'],
  // Transformer (T): horizontal and vertical (flag 8), editor flips (flag 16 / mirrored ends), reverse polarity (flag 4)
  ['t_h', `T 160 160 224 192 0 ${XFMR_T}`, 'p1=160,160 s1=224,160 p2=160,192 s2=224,192'],
  ['t_h_flipx', `T 224 160 160 192 0 ${XFMR_T}`, 'p1=224,160 s1=160,160 p2=224,192 s2=160,192'],
  ['t_h_flipy', `T 160 192 224 160 16 ${XFMR_T}`, 'p1=160,192 s1=224,192 p2=160,160 s2=224,160'],
  ['t_h_rot180', `T 224 192 160 160 16 ${XFMR_T}`, 'p1=224,192 s1=160,192 p2=224,160 s2=160,160'],
  ['t_h_up_noflip', `T 160 192 224 160 0 ${XFMR_T}`, 'p1=160,192 s1=224,192 p2=160,224 s2=224,224'],
  ['t_h_big', `T 160 160 288 224 0 ${XFMR_T}`, 'p1=160,160 s1=288,160 p2=160,224 s2=288,224'],
  ['t_h_rev', `T 160 160 224 192 4 ${XFMR_T}`, 'p1=160,160 s1=224,192 p2=160,192 s2=224,160'],
  ['t_h_rev_flipx', `T 224 160 160 192 4 ${XFMR_T}`, 'p1=224,160 s1=160,192 p2=224,192 s2=160,160'],
  ['t_v', `T 160 160 192 224 8 ${XFMR_T}`, 'p1=160,160 s1=160,224 p2=192,160 s2=192,224'],
  ['t_v_flipx', `T 192 160 160 224 24 ${XFMR_T}`, 'p1=192,160 s1=192,224 p2=160,160 s2=160,224'],
  ['t_v_flipy', `T 160 224 192 160 8 ${XFMR_T}`, 'p1=160,224 s1=160,160 p2=192,224 s2=192,160'],
  ['t_v_rev', `T 160 160 192 224 12 ${XFMR_T}`, 'p1=160,160 s1=192,224 p2=192,160 s2=160,224'],
  // TappedTransformer (169): horizontal, reversed, flipped (flag 1), vertical
  ['p_h', `169 160 192 224 192 0 ${XFMR_P}`, 'pri1=160,192 pri2=160,320 sec1=224,192 tap=224,224 sec2=224,320'],
  ['p_h_rev', `169 224 192 160 192 0 ${XFMR_P}`, 'pri1=224,192 pri2=224,64 sec1=160,192 tap=160,160 sec2=160,64'],
  ['p_h_flip', `169 160 192 224 192 1 ${XFMR_P}`, 'pri1=160,192 pri2=160,64 sec1=224,192 tap=224,160 sec2=224,64'],
  ['p_v', `169 192 160 192 224 0 ${XFMR_P}`, 'pri1=192,160 pri2=64,160 sec1=192,224 tap=160,224 sec2=64,224'],
  ['p_v_rev', `169 192 224 192 160 0 ${XFMR_P}`, 'pri1=192,224 pri2=320,224 sec1=192,160 tap=224,160 sec2=320,160'],
  // legacy lines: no third current / coupling / spacing (original 32 px spacing, tap at 32 px), and a
  // Transformer without the coupling coefficient; the readers dropped them from eb72ca5
  ['p_legacy', '169 160 192 224 192 0 4 1 0 0', 'pri1=160,192 pri2=160,256 sec1=224,192 tap=224,224 sec2=224,256'],
  ['p_legacy_k', '169 224 192 160 192 0 4 1 0 0 0 0.99', 'pri1=224,192 pri2=224,128 sec1=160,192 tap=160,160 sec2=160,128'],
  ['t_legacy', 'T 160 160 224 192 0 4 1 0 0', 'p1=160,160 s1=224,160 p2=160,192 s2=224,192'],
];
// Transformer posts of every bundled example that has one (reference: the 60b9b21 build)
const XFMR_EXAMPLE_POSTS = {
  'joule-thief.txt': ['p1=288,192 s1=336,224 p2=288,224 s2=336,192'],
  'tesla.txt': ['p1=240,256 s1=320,256 p2=240,304 s2=320,304', 'p1=464,256 s1=528,256 p2=464,304 s2=528,304'],
  'longdist.txt': ['p1=160,128 s1=240,128 p2=160,160 s2=240,160', 'p1=432,128 s1=496,128 p2=432,160 s2=496,160'],
  'transformerdown.txt': ['p1=272,192 s1=352,192 p2=272,224 s2=352,224'],
  'transformerdc.txt': ['p1=272,192 s1=352,192 p2=272,224 s2=352,224'],
  'transformerup.txt': ['p1=272,192 s1=352,192 p2=272,224 s2=352,224'],
  'transformer.txt': ['p1=272,192 s1=352,192 p2=272,224 s2=352,224'],
  // legacy 169 lines without the third current and the coupling (dropped on load from eb72ca5 until fixed)
  'ringmod.txt': ['pri1=144,144 pri2=144,208 sec1=208,144 tap=208,176 sec2=208,208', 'pri1=496,208 pri2=496,144 sec1=432,208 tap=432,176 sec2=432,144'],
};

// Page side: records stroked paths and arcs of every 2-D canvas in device coordinates.
function xfmrRecorderInstall() {
  const P = CanvasRenderingContext2D.prototype;
  if (window.__xfmrRec) return true;
  const orig = { beginPath: P.beginPath, moveTo: P.moveTo, lineTo: P.lineTo, arc: P.arc, stroke: P.stroke };
  const rec = window.__xfmrRec = { on: false, byCanvas: new Map(), orig };
  const tp = (ctx, x, y) => { const m = ctx.getTransform(); return [m.a * x + m.c * y + m.e, m.b * x + m.d * y + m.f]; };
  const cur = (ctx) => { if (!ctx.__xp) ctx.__xp = { segs: [], arcs: [] }; return ctx.__xp; };
  P.beginPath = function () { if (rec.on) this.__xp = { segs: [], arcs: [] }; return orig.beginPath.apply(this, arguments); };
  P.moveTo = function (x, y) { if (rec.on) cur(this).last = tp(this, x, y); return orig.moveTo.apply(this, arguments); };
  P.lineTo = function (x, y) {
    if (rec.on) { const c = cur(this); const p = tp(this, x, y); if (c.last) c.segs.push([c.last, p]); c.last = p; }
    return orig.lineTo.apply(this, arguments);
  };
  P.arc = function (x, y, r, a0, a1) {
    if (rec.on) {
      const c = cur(this); const mid = (a0 + a1) / 2;
      c.arcs.push({ sweep: Math.abs(a1 - a0), center: tp(this, x, y), apex: tp(this, x + r * Math.cos(mid), y + r * Math.sin(mid)),
        chordA: tp(this, x + r * Math.cos(a0), y + r * Math.sin(a0)), chordB: tp(this, x + r * Math.cos(a1), y + r * Math.sin(a1)) });
      c.last = tp(this, x + r * Math.cos(a1), y + r * Math.sin(a1));
    }
    return orig.arc.apply(this, arguments);
  };
  P.stroke = function () {
    if (rec.on && this.__xp) {
      const key = this.canvas.width + 'x' + this.canvas.height;
      if (!rec.byCanvas.has(key)) rec.byCanvas.set(key, []);
      rec.byCanvas.get(key).push({ segs: this.__xp.segs.slice(), arcs: this.__xp.arcs.slice() });
      this.__xp.segs = []; this.__xp.arcs = [];
    }
    return orig.stroke.apply(this, arguments);
  };
  return true;
}

// Geometric checks of one recorded drawing (device coordinates; uniform scale, no rotation).
function xfmrAnalyze(paths, scale) {
  const tol = 1.5 * scale;
  const arcs = []; const lines = [];
  for (const p of paths) {
    const half = p.arcs.filter((a) => Math.abs(a.sweep - Math.PI) < 1e-6);
    if (half.length) arcs.push(...half);
    else if (!p.arcs.length) lines.push(...p.segs);
  }
  if (!arcs.length) return { ok: false, why: 'no coil arcs' };
  const chord = (a) => [a.chordB[0] - a.chordA[0], a.chordB[1] - a.chordA[1]];
  const vertical = arcs.every((a) => Math.abs(chord(a)[0]) < 0.01 * Math.abs(chord(a)[1]));
  const horizontal = arcs.every((a) => Math.abs(chord(a)[1]) < 0.01 * Math.abs(chord(a)[0]));
  if (vertical === horizontal) return { ok: false, why: 'coil chords not all horizontal or all vertical' };
  const perp = (pt) => (vertical ? pt[0] : pt[1]); const along = (pt) => (vertical ? pt[1] : pt[0]);
  // winding lines: clusters of arc-center perpendicular coordinates
  const lanes = [];
  for (const a of arcs) { const v = perp(a.center); if (!lanes.some((l) => Math.abs(l - v) < tol)) lanes.push(v); }
  lanes.sort((x, y) => x - y);
  if (lanes.length !== 2) return { ok: false, why: 'coil arcs on ' + lanes.length + ' winding lines', lanes };
  const [lo, hi] = lanes; const mid = (lo + hi) / 2;
  const outward = arcs.filter((a) => Math.sign(perp(a.apex) - perp(a.center)) !== Math.sign(mid - perp(a.center))).length;
  const aMin = Math.min(...arcs.map((a) => Math.min(along(a.chordA), along(a.chordB))));
  const aMax = Math.max(...arcs.map((a) => Math.max(along(a.chordA), along(a.chordB))));
  // core: straight lines with both ends strictly between the winding lines
  const inner = lines.filter(([p, q]) => [p, q].every((pt) => perp(pt) > lo + tol && perp(pt) < hi - tol));
  const core = inner.map(([p, q]) => ({ parallel: Math.abs(perp(p) - perp(q)) < 0.5 * scale, at: perp(p),
    from: Math.min(along(p), along(q)), to: Math.max(along(p), along(q)) }));
  const coreOk = core.length === 2 && core.every((c) => c.parallel && c.from >= aMin - tol && c.to <= aMax + tol && (c.to - c.from) >= 0.5 * (aMax - aMin))
    && Math.abs(core[0].at - core[1].at) > 0.5 * scale;
  return { ok: outward === 0 && coreOk, windings: vertical ? 'vertical' : 'horizontal', arcs: arcs.length, outward, coreLines: core.length,
    coreOk, core: core.map((c) => [Math.round(c.at), Math.round(c.from), Math.round(c.to)]), lanes: lanes.map(Math.round), span: [Math.round(aMin), Math.round(aMax)] };
}

async function scenarioXfmrDraw(s) {
  const out = { checks: {}, cases: {}, examples: {}, mismatches: [] };
  const ck = (name, cond) => { out.checks[name] = !!cond; return !!cond; };
  await resetApp(s);
  const exMark = s.exceptions.length;
  await s.eval(`(${xfmrRecorderInstall.toString()})()`);
  const scale = 2;
  const pins = () => s.eval(`(() => {
    const j = JSON.parse(CircuitJS1.exportAsJson());
    return Object.values(j.elements || {}).filter((e) => /Transformer$/.test(e.type)).map((e) => Object.entries(e.pins || {})
      .filter(([n]) => n[0] !== '_').map(([n, p]) => n + '=' + p.position.x + ',' + p.position.y).join(' '));
  })()`);
  for (const [name, line, expectedPins] of XFMR_DRAW_CASES) {
    await s.call('importText', `${XFMR_OPTIONS}\n${line}\n`);
    const got = await pins();
    await s.eval(`(() => { window.__xfmrRec.byCanvas.clear(); window.__xfmrRec.on = true; return true; })()`);
    const r = await s.call('agentAsync', 'render', { scale }, 30000);
    const key = r && r.ok ? `${r.data.width}x${r.data.height}` : '';
    const recorded = await s.eval(`(() => { const rec = window.__xfmrRec; rec.on = false; return rec.byCanvas.get(${JSON.stringify(key)}) || []; })()`);
    const a = xfmrAnalyze(recorded, scale);
    a.rendered = !!(r && r.ok); a.posts = got.length === 1 && got[0] === expectedPins;
    out.cases[name] = a;
    if (!ck(name + '.draw', a.rendered && a.ok)) out.mismatches.push({ case: name, ...a });
    if (!ck(name + '.posts', a.posts)) out.mismatches.push({ case: name, expected: expectedPins, got });
  }
  // posts of the transformers in the bundled examples are unchanged
  // and every element line of those examples loads (none is dropped by its reader)
  for (const [ex, expected] of Object.entries(XFMR_EXAMPLE_POSTS)) {
    await s.call('loadExample', ex);
    const got = await pins();
    const lines = await s.eval(`(async () => (await window.__H.fetchText('/circuitjs1/circuits/' + ${JSON.stringify(ex)}))
      .split('\\n').filter((l) => l.trim() && !/^(\\$|o|h|%|\\?|!|&|38|#) /.test(l.trim() + ' ')).length)()`);
    const loaded = await s.call('count');
    out.examples[ex] = { posts: got, elementLines: lines, loaded };
    if (!ck('example_' + ex, JSON.stringify(got) === JSON.stringify(expected))) out.mismatches.push({ example: ex, expected, got });
    if (!ck('loadsAll_' + ex, loaded === lines)) out.mismatches.push({ example: ex, elementLines: lines, loaded });
  }
  // Agent API: getCircuit -> importCircuit keeps the tapped transformer shape (spacing and
  // tap_position are JSON properties); a new part keeps the editor minimum of 64 px
  const A = (op, args) => s.call('agentCall', op, args);
  const postsOf = (g) => ((g && g.data && g.data.elements) || []).map((e) => e.id + ':' + e.type + ':'
    + e.posts.map((p) => p.pin + '@' + p.at.x + ',' + p.at.y).join(' ')).sort().join('\n');
  const netsOf = (g) => {
    const nets = {};
    for (const e of (g && g.data && g.data.elements) || []) for (const p of e.posts) (nets[p.net] = nets[p.net] || []).push(e.id + '.' + p.pin);
    return Object.values(nets).map((l) => l.sort().join(',')).sort().join('|');
  };
  await s.call('loadExample', 'ringmod.txt');
  const g1 = await A('getCircuit', { detail: 'full', limit: 500 });
  const nd = await A('createDocument', {});
  const B = nd.data && nd.data.doc;
  const reimp = await A('importCircuit', { doc: B, circuit: { elements: g1.data.elements } });
  const g2 = await A('getCircuit', { doc: B, detail: 'full', limit: 500 });
  out.agentRoundTrip = { imported: reimp.ok, codes: (reimp.issues || []).map((i) => i.code),
    tapped: ((g1.data && g1.data.elements) || []).filter((e) => e.type === 'TappedTransformer').map((e) => e.properties) };
  ck('agentRoundTrip_ringmod_posts', reimp.ok && out.agentRoundTrip.tapped.length === 2 && postsOf(g1) === postsOf(g2));
  ck('agentRoundTrip_ringmod_nets', reimp.ok && netsOf(g1) === netsOf(g2));
  if (B) await A('closeDocument', { doc: B, discardChanges: true });
  await s.call('importText', `${XFMR_OPTIONS}\n`);
  const add = await A('applyEdits', { edits: [{ op: 'add', element: { id: 'TAP1', type: 'TappedTransformer', start: { x: 10, y: 10 }, end: { x: 12, y: 10 } } }] });
  const tp = (r) => { const e = r && r.data && r.data.elements && r.data.elements.find((x) => x.id === 'TAP1'); return e ? e.posts.map((p) => p.pin + '@' + p.at.x + ',' + p.at.y).join(' ') : null; };
  out.newPlacement = tp(add);
  ck('newTappedKeepsEditorMinimum', out.newPlacement === 'pri1@10,10 pri2@10,18 sec1@12,10 tap@12,12 sec2@12,18');
  const setSp = await A('applyEdits', { edits: [{ op: 'set', id: 'TAP1', properties: { spacing: 32 } }] });
  const after = await A('getCircuit', { detail: 'full', limit: 500 });
  out.setSpacing = { ok: setSp.ok, codes: (setSp.issues || []).map((i) => i.code), posts: tp(after) };
  ck('setSpacingReshapes', setSp.ok && out.setSpacing.posts === 'pri1@10,10 pri2@10,14 sec1@12,10 tap@12,12 sec2@12,14');
  await s.eval(`(() => { const rec = window.__xfmrRec; if (rec) { const P = CanvasRenderingContext2D.prototype; Object.assign(P, rec.orig); delete window.__xfmrRec; } return true; })()`);
  ck('noPageException', s.exceptions.length === exMark);
  fs.writeFileSync(path.join(OUT_DIR, 'xfmr_draw.json'), JSON.stringify(out, null, 2));
  const failed = Object.entries(out.checks).filter(([, v]) => !v).map(([k]) => k);
  report('xfmr_draw', !failed.length, { checks: Object.keys(out.checks).length, failed, details: path.join(OUT_DIR, 'xfmr_draw.json') });
}

// agent_axis: [SP_AGA_03_01] "Axis-bound elements". An element the editor places only horizontally
// or vertically (CircuitElm.isAxisBound: noDiagonal, except the transformers whose end is a resize-box
// corner) rejects a diagonal start/end with not_axis_aligned on add, move with start and end, and
// AgentCircuit import; before, the agent API accepted it and the element was drawn rotated with its
// derived posts off the lattice (a TappedTransformer at (10,12)-(14,14) put pri2 at (6.4375, 19.125)).
const AXIS_BOUND_TYPES = ['ADC', 'ANDGate', 'AnalogSwitch', 'AnalogSwitch2', 'CC2', 'CC2Neg', 'CCCS', 'CCVS', 'Comparator', 'Counter', 'Counter2',
  'CrossSwitch', 'CustomCompositeChip', 'CustomLogic', 'DAC', 'DFlipFlop', 'DPDTSwitch', 'DarlingtonNPN', 'DarlingtonPNP', 'DeMultiplexer',
  'DecimalDisplay', 'DelayBuffer', 'FullAdder', 'HalfAdder', 'Inverter', 'InvertingSchmitt', 'JKFlipFlop', 'LEDArray', 'Latch', 'MBBSwitch',
  'Monostable', 'Multiplexer', 'NJFET', 'NMOS', 'NandGate', 'NorGate', 'ORGate', 'OTA', 'OpAmp', 'OpAmpReal', 'PISOShiftRegister', 'PJFET',
  'PMOS', 'PhaseComparator', 'Relay', 'RelayCoil', 'RelayContact', 'RingCounter', 'SIPOShiftRegister', 'SPDTSwitch', 'SRAM', 'Schmitt',
  'SequenceGenerator', 'SevenSegment', 'SevenSegmentDecoder', 'TFlipFlop', 'TappedTransformer', 'TimeDelayRelay', 'Timer555', 'TransistorNPN',
  'TransistorPNP', 'TransmissionLine', 'TriStateBuffer', 'Triode', 'UnijunctionTransistor', 'VCCS', 'VCO', 'VCVS', 'XORGate'];

async function scenarioAgentAxis(s) {
  const out = { checks: {}, types: {}, mismatches: [] };
  const ck = (name, cond) => { out.checks[name] = !!cond; return !!cond; };
  await resetApp(s);
  const exMark = s.exceptions.length;
  const A = (op, args) => s.call('agentCall', op, args);
  const codes = (r) => (r.issues || []).map((i) => i.code);
  const clear = () => s.call('importText', '$ 1 0.000005 10.20027730826997 50 5 50 5e-11\n');
  const count = () => s.call('count');
  // (1) every type with an axis-aligned default size, placed with a diagonal end
  const types = (await A('listTypes', {})).data.types.map((t) => t.type);
  const seenBound = [];
  for (const t of types) {
    const d = await A('describeType', { type: t });
    const ds = d.ok && d.data.defaultSize;
    if (!ds || (ds.dx !== 0) === (ds.dy !== 0)) continue; // no size, or a diagonal box by default
    const end = ds.dy === 0 ? { x: 20 + ds.dx, y: 22 } : { x: 22, y: 20 + ds.dy };
    await clear();
    const r = await A('applyEdits', { edits: [{ op: 'add', element: { type: t, start: { x: 20, y: 20 }, end } }] });
    const bound = AXIS_BOUND_TYPES.includes(t);
    const rejected = r.ok === false && codes(r)[0] === 'not_axis_aligned' && (await count()) === 0;
    if (rejected) seenBound.push(t);
    const ok = bound ? rejected : r.ok === true && !codes(r).includes('not_axis_aligned');
    out.types[t] = { bound, ok: r.ok, codes: codes(r) };
    if (!ok) out.mismatches.push({ type: t, bound, result: { ok: r.ok, codes: codes(r) } });
  }
  ck('everyAxisBoundTypeRejectsDiagonal', AXIS_BOUND_TYPES.every((t) => seenBound.includes(t)));
  ck('otherTypesAcceptDiagonal', out.mismatches.every((m) => m.bound));
  // (2) TappedTransformer: horizontal and vertical ends apply; move with a diagonal end is rejected
  await clear();
  const h = await A('applyEdits', { edits: [{ op: 'add', element: { id: 'TAP1', type: 'TappedTransformer', start: { x: 10, y: 10 }, end: { x: 12, y: 10 } } },
    { op: 'add', element: { id: 'TAP2', type: 'TappedTransformer', start: { x: 30, y: 10 }, end: { x: 30, y: 12 } } }] });
  ck('tappedAxisAddApplies', h.ok === true);
  const before = await s.call('exportText');
  const mv = await A('applyEdits', { edits: [{ op: 'move', id: 'TAP1', start: { x: 10, y: 10 }, end: { x: 14, y: 14 } }] });
  ck('tappedDiagonalMoveRejected', mv.ok === false && codes(mv)[0] === 'not_axis_aligned' && (await s.call('exportText')) === before);
  const mvAxis = await A('applyEdits', { edits: [{ op: 'move', id: 'TAP1', start: { x: 10, y: 20 }, end: { x: 13, y: 20 } }] });
  ck('tappedAxisMoveApplies', mvAxis.ok === true);
  const mvBy = await A('applyEdits', { edits: [{ op: 'move', id: 'TAP2', by: { dx: 1, dy: 1 } }] });
  ck('moveByUnaffected', mvBy.ok === true);
  // (3) AgentCircuit import: a diagonal TappedTransformer rejects the import; box-cornered transformers do not
  const beforeImp = await s.call('exportText');
  const imp = await A('importCircuit', { circuit: { elements: [{ type: 'Resistor', start: { x: 0, y: 0 }, end: { x: 4, y: 0 } },
    { type: 'TappedTransformer', start: { x: 10, y: 12 }, end: { x: 14, y: 14 } }] } });
  ck('importDiagonalRejected', imp.ok === false && codes(imp).includes('not_axis_aligned') && (await s.call('exportText')) === beforeImp);
  out.importIssue = (imp.issues || [])[0] || null;
  ck('issueNamesElementAndHint', !!(out.importIssue && out.importIssue.elements && out.importIssue.elements.length && /defaultSize/.test(out.importIssue.hint || '')));
  const box = await A('importCircuit', { circuit: { elements: [{ type: 'Transformer', start: { x: 0, y: 0 }, end: { x: 4, y: 2 } },
    { type: 'CustomTransformer', start: { x: 10, y: 8 }, end: { x: 12, y: 6 } }] } });
  ck('transformerBoxImportApplies', box.ok === true && !codes(box).includes('not_axis_aligned'));
  ck('noPageException', s.exceptions.length === exMark);
  fs.writeFileSync(path.join(OUT_DIR, 'agent_axis.json'), JSON.stringify(out, null, 2));
  const failed = Object.entries(out.checks).filter(([, v]) => !v).map(([k]) => k);
  report('AG.agent_axis', !failed.length, { checks: Object.keys(out.checks).length, failed, axisBound: seenBound.length, mismatches: out.mismatches.length, details: path.join(OUT_DIR, 'agent_axis.json') });
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
  // Regression (found after agent_freerun, 2026-10-05): an agent transaction open on the visible
  // document, then the user's content replacement (CircuitJS1.importCircuit) seals it; the Undo
  // item must name the sealed entry at once, not when a later background operation refreshes
  // the session menu (which changed the visible label during R1).
  await A('applyEdits', { edits: [{ op: 'add', element: { id: 'RBGSEAL', type: 'Resistor', start: { x: 300, y: 300 }, end: { x: 304, y: 300 } } }] });
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
  ck('undoLabelSealedAtUserImport', /agent edits \(auto\)/.test(JSON.stringify(base.session.menu)));
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

// agent_models: model definitions, diode and transistor (PL_AGA Phase 11; SP_AGA_01_13, §02_03,
// §02_04 defineModel, §02_05 models, §02_15 listModels, §03_03 Model names, §03_11, §05_01 rows,
// §05_02 "ok=false ⇒ catalogues unchanged"). On background documents: listModels kinds/order/name
// and the ensured logic `default`; defineModel of an LED model whose forward voltage a run measures
// (2.1 V ± 0.02 V at 20 mA) and describeType choices; name taken (built-in, earlier user model,
// internal name, logic `default`); identical redefinition (existing); batch rollback and rollback on
// a forced exception; bad `from`; the diode input errors; diode record re-import (same model line
// but the name) and record mismatches; transistor from `default` with an Early voltage and with
// "inf" (inverse 0 in the model line); undo/redo keep the model; `set` of an unknown CustomLogic
// model_name; importCircuit ModelText errors, models rollback, the same-session round trip (and a
// fresh-session one in a side page), the `fwdrop=0.8` ModelText fallback, a legacy text model line
// that differs (name_taken + openFile hint) and a legacy CustomLogic line naming an unknown model;
// openFile (text and JSON, through an in-page fake of the desktop file system) of a CustomLogic
// naming an unknown model loads with value_adjusted. Every rejection leaves the catalogues and the
// document unchanged; no alert, no page exception (apart from the forced one).
async function scenarioAgentModels(s) {
  const out = { checks: {}, notes: {} };
  const ck = (name, cond) => { out.checks[name] = !!cond; return !!cond; };
  const A = (op, args) => s.call('agentCall', op, args);
  const R = (args) => s.call('agentAsync', 'run', args, 60000);
  const codes = (r) => (r.issues || []).map((i) => i.code);
  const has = (r, code, re) => (r.issues || []).some((i) => i.code === code && (!re || re.test(i.message + ' ' + (i.hint || ''))));
  const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
  const strip = (m) => { const c = Object.assign({}, m); delete c.usedBy; return c; };
  const catalogue = async () => JSON.stringify((await A('listModels', {})).data.models.map(strip));
  const record = async (kind, name) => { const r = await A('listModels', { kind, name }); return r.ok ? r.data.models[0] : null; };
  const docState = async (doc) => {
    const st = JSON.parse(await s.eval(`CircuitJS1Agent.debugDocState(${JSON.stringify(doc)})`));
    return { text: (await A('exportCircuit', { doc, format: 'text' })).data.content, ids: (await A('getCircuit', { doc })).data.elements.map((e) => e.id), undo: st.undo, marks: st.openMarks };
  };
  const define = (doc, model, more) => A('applyEdits', { doc, edits: [{ op: 'defineModel', model }].concat(more || []) });
  // a rejection: ok=false with the code, document and catalogues unchanged (SP_AGA_05_02)
  const rejections = {};
  const rejects = async (name, doc, fn, code, re) => {
    const c0 = await catalogue(); const d0 = await docState(doc);
    const r = await fn();
    const c1 = await catalogue(); const d1 = await docState(doc);
    rejections[name] = { ok: r.ok, issues: (r.issues || []).map((i) => i.code + ': ' + i.message + ' | ' + (i.hint || '')) };
    return ck(name, r.ok === false && has(r, code, re) && c0 === c1 && same(d0, d1));
  };
  try {
    await resetApp(s);
    const exMark = s.exceptions.length;
    const alertMark = s.dialogs.length;
    const vis0 = await s.call('visibleTab');
    const D = (await A('createDocument', { title: 'Models' })).data.doc;

    // ---------------------------------------------------------------- listModels
    const all = (await A('listModels', {})).data.models;
    const kindOrder = ['diode', 'transistor', 'logic', 'subcircuit'];
    let ordered = true;
    for (let i = 1; i < all.length; i++) {
      const a = all[i - 1], b = all[i];
      const ka = kindOrder.indexOf(a.kind), kb = kindOrder.indexOf(b.kind);
      if (ka > kb || (ka === kb && (a.builtIn === false && b.builtIn === true || (a.builtIn === b.builtIn && a.name >= b.name)))) { ordered = false; out.notes.orderBreak = [a.kind + ':' + a.name, b.kind + ':' + b.name]; }
    }
    ck('list_allKinds', kindOrder.every((k) => all.some((m) => m.kind === k)));
    ck('list_order', ordered);
    ck('list_noInternal', !all.some((m) => m.name.startsWith('~') || m.name === 'old-default-led' || /^xlm324/.test(m.name)));
    const logicDefault = all.find((m) => m.kind === 'logic' && m.name === 'default');
    out.notes.logicDefault = logicDefault;
    ck('list_logicDefault', logicDefault && logicDefault.builtIn === false && same(logicDefault.inputs, ['A', 'B']) && same(logicDefault.outputs, ['C', 'D']) && same(logicDefault.rules, []));
    const n4148 = await A('listModels', { kind: 'diode', name: '1N4148' });
    ck('list_name', n4148.ok && n4148.data.models.length === 1 && n4148.data.models[0].builtIn === true && n4148.data.models[0].parameters.breakdown_voltage === '75 V');
    const noKind = await A('listModels', { name: '1N4148' });
    ck('list_nameWithoutKind', noKind.ok === false && has(noKind, 'invalid_value', /'name'/));
    const nope = await A('listModels', { kind: 'diode', name: 'nope' });
    ck('list_unknownName', nope.ok === false && has(nope, 'unknown_model'));
    ck('list_internalName', has(await A('listModels', { kind: 'diode', name: 'old-default-led' }), 'unknown_model'));

    // ---------------------------------------------------------------- LED with a forward voltage
    const ledSpec = { kind: 'diode', name: 'led-green-2v1', parameters: { forward_voltage: '2.1 V', forward_current: '20 mA' } };
    const ledCircuit = [
      { op: 'add', element: { id: 'V1', type: 'DCVoltage', start: { x: 0, y: 4 }, end: { x: 0, y: 0 }, properties: { max_voltage: '5 V' } } },
      { op: 'add', element: { id: 'R1', type: 'Resistor', start: { x: 0, y: 0 }, end: { x: 4, y: 0 }, properties: { resistance: 145 } } },
      { op: 'add', element: { id: 'LED1', type: 'LED', start: { x: 4, y: 0 }, end: { x: 4, y: 4 }, properties: { model: 'led-green-2v1' } } },
      { op: 'add', element: { id: 'W1', type: 'Wire', start: { x: 4, y: 4 }, end: { x: 0, y: 4 } } },
      { op: 'add', element: { id: 'G1', type: 'Ground', start: { x: 0, y: 4 }, end: { x: 0, y: 5 } } }];
    const led = await define(D, ledSpec, ledCircuit);
    out.notes.led = { ok: led.ok, issues: codes(led), models: led.data && led.data.models };
    ck('led_defined', led.ok && led.data.models.length === 1 && led.data.models[0].name === 'led-green-2v1' && !led.data.models[0].existing
      && led.data.models[0].parameters.forward_voltage === '2.1 V' && led.data.models[0].parameters.forward_current === '20 mA'
      && same(led.data.models[0].usedBy, [{ doc: D, ids: ['LED1'] }]));
    const run = await R({ doc: D, span: '5 ms', reset: true, maxPoints: 10, probes: [{ post: 'LED1.anode', name: 'va' }, { post: 'LED1.cathode', name: 'vk' }, { element: 'R1', quantity: 'current', name: 'i' }] });
    const fin = run.ok ? Object.fromEntries(run.data.probes.map((p) => [p.name, p.stats.final])) : {};
    out.notes.ledRun = { ok: run.ok, fin, issues: codes(run) };
    ck('led_dropsForwardVoltage', run.ok && Math.abs((fin.va - fin.vk) - 2.1) <= 0.02 && Math.abs(Math.abs(fin.i) - 0.02) <= 0.0005);
    const ledType = await A('describeType', { type: 'LED' });
    const ledModelKey = ledType.data.properties.find((p) => p.key === 'model');
    ck('led_choices', ledModelKey && ledModelKey.choices.includes('led-green-2v1'));

    // ---------------------------------------------------------------- name taken / internal / identical
    await rejects('taken_builtIn', D, () => define(D, { kind: 'diode', name: '1N4148', parameters: { emission_coefficient: 3 } }), 'name_taken');
    await rejects('taken_user', D, () => define(D, { kind: 'diode', name: 'led-green-2v1', parameters: { forward_voltage: '2.2 V', forward_current: '20 mA' } }), 'name_taken');
    await rejects('taken_internal', D, () => define(D, { kind: 'diode', name: 'old-default-led', parameters: {} }), 'name_taken');
    ck('taken_internalNotListed', !(await A('listModels', { kind: 'diode' })).data.models.some((m) => m.name === 'old-default-led'));
    await rejects('taken_logicDefault', D, () => define(D, { kind: 'logic', name: 'default', inputs: ['A', 'B'], outputs: ['Y'], rules: ['11=1'] }), 'name_taken');
    const recBefore = await record('diode', 'led-green-2v1');
    const again = await define(D, ledSpec, [{ op: 'add', element: { id: 'LED2', type: 'LED', start: { x: 8, y: 0 }, end: { x: 8, y: 4 }, properties: { model: 'led-green-2v1' } } }]);
    const recAfter = await record('diode', 'led-green-2v1');
    ck('identical_existing', again.ok && again.data.models[0].existing === true && same(strip(recBefore), strip(recAfter))
      && same(recAfter.usedBy, [{ doc: D, ids: ['LED1', 'LED2'] }]));

    // ---------------------------------------------------------------- rollback
    await rejects('rollback_batch', D, () => define(D, { kind: 'diode', name: 'rb-diode', parameters: {} }, [{ op: 'add', element: { type: 'Nope', start: { x: 0, y: 10 } } }]), 'unknown_type');
    ck('rollback_batchNotListed', has(await A('listModels', { kind: 'diode', name: 'rb-diode' }), 'unknown_model'));
    {
      const c0 = await catalogue(); const d0 = await docState(D);
      await s.eval('CircuitJS1Agent.debugFailNextMutation()');
      const fe = await define(D, { kind: 'diode', name: 'ex-diode', parameters: {} }, [{ op: 'add', element: { type: 'Resistor', start: { x: 0, y: 10 } } }]);
      await sleep(200);
      await s.call('closeDialogs');
      const c1 = await catalogue(); const d1 = await docState(D);
      ck('rollback_exception', fe.ok === false && has(fe, 'internal_error') && c0 === c1 && same(d0, d1)
        && has(await A('listModels', { kind: 'diode', name: 'ex-diode' }), 'unknown_model'));
    }

    // ---------------------------------------------------------------- bad from, diode input errors
    await rejects('from_unknown', D, () => define(D, { kind: 'diode', name: 'f1', from: 'nope', parameters: {} }), 'unknown_model', /\.from'/);
    await rejects('from_internal', D, () => define(D, { kind: 'diode', name: 'f2', from: 'old-default-led', parameters: {} }), 'unknown_model', /\.from'/);
    await define(D, { kind: 'transistor', name: 'bjt-only', parameters: { beta_reverse: 2 } });
    await rejects('from_otherKind', D, () => define(D, { kind: 'diode', name: 'f3', from: 'bjt-only', parameters: {} }), 'unknown_model', /\.from'/);
    await rejects('from_logic', D, () => define(D, { kind: 'logic', name: 'f4', from: 'default', inputs: ['A'], outputs: ['Y'], rules: ['1=1'] }), 'invalid_value', /\.from'/);
    const diodeErrors = {
      fcWithoutFv: [{ forward_current: '20 mA' }, 'invalid_value', /parameters\.forward_current'/],
      fvWithEcOnly: [{ forward_voltage: '2 V', emission_coefficient: 2 }, 'invalid_value', /parameters\.forward_voltage'/],
      negativeBv: [{ breakdown_voltage: '-5 V' }, 'invalid_value', /parameters\.breakdown_voltage'/],
      unitOnNumber: [{ emission_coefficient: '2 V' }, 'invalid_value', /parameters\.emission_coefficient'/],
      unknownKey: [{ foo: 1 }, 'unknown_property', /parameters\.foo'/],
      fvFcWithEc: [{ forward_voltage: '2 V', forward_current: '10 mA', emission_coefficient: 2 }, 'invalid_value', /parameters\.forward_voltage' cannot be combined with emission_coefficient/],
    };
    for (const [k, [params, code, re]] of Object.entries(diodeErrors)) {
      await rejects('diode_' + k, D, () => define(D, { kind: 'diode', name: 'de-' + k, parameters: params }), code, re);
    }
    // "inf" only on the four transistor keys that take it
    await rejects('inf_transistorSaturation', D, () => define(D, { kind: 'transistor', name: 'ti1', parameters: { saturation_current: 'inf' } }), 'invalid_value', /parameters\.saturation_current'/);
    await rejects('inf_diodeForwardVoltage', D, () => define(D, { kind: 'diode', name: 'ti2', parameters: { forward_voltage: 'inf', forward_current: '20 mA' } }), 'invalid_value', /parameters\.forward_voltage'/);

    // ---------------------------------------------------------------- diode record re-import and mismatches
    const rec = await record('diode', 'led-green-2v1');
    const copy = await define(D, { kind: 'diode', name: 'led-green-copy', parameters: rec.parameters },
      [{ op: 'add', element: { id: 'D9', type: 'Diode', start: { x: 12, y: 0 }, end: { x: 12, y: 4 }, properties: { model: 'led-green-copy' } } }]);
    const txt = (await A('exportCircuit', { doc: D, format: 'text' })).data.content;
    const lineOf = (name) => txt.split('\n').find((l) => l.startsWith('34 ' + name + ' '));
    out.notes.recordLines = [lineOf('led-green-2v1'), lineOf('led-green-copy')];
    ck('record_reimport', copy.ok && lineOf('led-green-2v1') && lineOf('led-green-copy')
      && lineOf('led-green-2v1').replace('led-green-2v1', 'X') === lineOf('led-green-copy').replace('led-green-copy', 'X')
      && lineOf('led-green-copy').split(' ')[2] === '1');
    const off = Object.assign({}, rec.parameters, { forward_voltage: '2.121 V' });
    await rejects('record_fvMismatch', D, () => define(D, { kind: 'diode', name: 'rm1', parameters: off }), 'invalid_value', /parameters\.forward_voltage'.*omit emission_coefficient and series_resistance/);
    const withRs = Object.assign({}, rec.parameters, { series_resistance: '1 Ohm' });
    await rejects('record_fcWithRs', D, () => define(D, { kind: 'diode', name: 'rm2', parameters: withRs }), 'invalid_value', /parameters\.forward_current'/);

    // ---------------------------------------------------------------- transistor
    const tr = await define(D, { kind: 'transistor', name: 'bjt-lowbeta', from: 'default', parameters: { early_voltage_forward: '100 V' } });
    const def = await record('transistor', 'default');
    const low = tr.ok ? tr.data.models[0] : null;
    ck('transistor_fromDefault', low && low.parameters.early_voltage_forward === '100 V'
      && Object.keys(def.parameters).every((k) => k === 'early_voltage_forward' || same(low.parameters[k], def.parameters[k])));
    const inf = await define(D, { kind: 'transistor', name: 'bjt-inf', from: 'bjt-lowbeta', parameters: { early_voltage_forward: 'inf', knee_current_forward: 'inf' } },
      [{ op: 'add', element: { id: 'Q1', type: 'TransistorNPN', start: { x: 20, y: 0 }, end: { x: 24, y: 0 }, properties: { model: 'bjt-inf' } } }]);
    const qtxt = (await A('exportCircuit', { doc: D, format: 'text' })).data.content;
    const qline = qtxt.split('\n').find((l) => l.startsWith('32 bjt-inf '));
    out.notes.infLine = qline;
    const qt = qline ? qline.split(' ') : [];
    ck('transistor_inf', inf.ok && inf.data.models[0].parameters.early_voltage_forward === 'inf' && inf.data.models[0].parameters.knee_current_forward === 'inf'
      && qt[4] === '0' && qt[12] === '0');

    // ---------------------------------------------------------------- undo keeps the model
    await A('checkpoint', { doc: D });
    const u0 = await record('diode', 'led-green-2v1');
    const ub = await define(D, { kind: 'diode', name: 'undo-led', parameters: { forward_voltage: '1.9 V', forward_current: '10 mA' } },
      [{ op: 'add', element: { id: 'LED7', type: 'LED', start: { x: 30, y: 0 }, end: { x: 30, y: 4 }, properties: { model: 'undo-led' } } }]);
    const ur0 = await record('diode', 'undo-led');
    const un = await A('undo', { doc: D });
    const afterUndo = (await A('getCircuit', { doc: D })).data.elements.map((e) => e.id);
    const ur1 = await record('diode', 'undo-led');
    const re = await A('redo', { doc: D });
    const afterRedo = (await A('getCircuit', { doc: D })).data.elements.map((e) => e.id);
    const ur2 = await record('diode', 'undo-led');
    ck('undo_modelKept', ub.ok && un.ok && re.ok && !afterUndo.includes('LED7') && afterRedo.includes('LED7')
      && ur1 && ur2 && same(ur0.parameters, ur1.parameters) && same(ur0.parameters, ur2.parameters)
      && same(strip(u0), strip(await record('diode', 'led-green-2v1'))));

    // ---------------------------------------------------------------- set unknown model_name (CustomLogic)
    const L = (await A('createDocument', { title: 'Logic' })).data.doc;
    await A('applyEdits', { doc: L, edits: [{ op: 'add', element: { id: 'CL1', type: 'CustomLogic', start: { x: 0, y: 0 } } }] });
    const logicType = await A('describeType', { type: 'CustomLogic' });
    const mk = logicType.data.properties.find((p) => p.key === 'model_name');
    ck('logic_choices', mk && Array.isArray(mk.choices) && mk.choices.includes('default'));
    await rejects('set_unknownModelName', L, () => A('applyEdits', { doc: L, edits: [{ op: 'set', id: 'CL1', properties: { model_name: 'nope' } }] }), 'invalid_value', /model_name.*Available logic models: .*default/);
    ck('set_unknownNotRegistered', has(await A('listModels', { kind: 'logic', name: 'nope' }), 'unknown_model'));

    // ---------------------------------------------------------------- importCircuit models / ModelText
    const I = (await A('createDocument', { title: 'Import' })).data.doc;
    const imp = (circuit) => () => A('importCircuit', { doc: I, circuit });
    const mt = (m) => ({ elements: [], models: [m] });
    await rejects('mt_twoLines', I, imp(mt({ kind: 'diode', name: 'mt1', modelText: '34 mt1 0 1e-14 0 1 0 0\n34 mt1 0 1e-14 0 1 0 0' })), 'invalid_value', /modelText'/);
    await rejects('mt_wrongToken', I, imp(mt({ kind: 'diode', name: 'mt2', modelText: '32 mt2 0 1e-13 0 0 1.5 0 0 2 1 1 0 0 1' })), 'invalid_value', /modelText'/);
    await rejects('mt_nameMismatch', I, imp(mt({ kind: 'diode', name: 'mt3', modelText: '34 other 0 1e-14 0 1 0 0' })), 'invalid_value', /modelText'/);
    await rejects('mt_tildeName', I, imp(mt({ kind: 'diode', name: '~x', modelText: '34 ~x 0 1e-14 0 1 0 0' })), 'invalid_value', /(modelText|name)'/);
    await rejects('mt_badField', I, imp(mt({ kind: 'transistor', name: 'mt5', modelText: '32 mt5 0 abc' })), 'invalid_value', /modelText'/);
    await rejects('models_rollback', I, imp({ elements: [], models: [{ kind: 'diode', name: 'mr-a', parameters: {} }, { kind: 'diode', name: 'led-green-2v1', parameters: { forward_voltage: '3 V', forward_current: '20 mA' } }] }), 'name_taken', /openFile/);
    ck('models_rollbackNotListed', has(await A('listModels', { kind: 'diode', name: 'mr-a' }), 'unknown_model'));
    // models cap: more than 200 entries are rejected; getCircuit lists 200 and counts the rest
    const capModels = (from, to) => Array.from({ length: to - from }, (_, k) => ({ kind: 'diode', name: 'cap-' + (from + k), parameters: { emission_coefficient: 1 + (from + k) / 1000 } }));
    await rejects('models_over200', I, imp({ elements: [], models: capModels(0, 201) }), 'invalid_value', /circuit\.models'/);
    const C = (await A('createDocument', { title: 'Cap' })).data.doc;
    await define(C, { kind: 'diode', name: 'cap-0', parameters: { emission_coefficient: 1 } });
    const capElms = Array.from({ length: 201 }, (_, k) => ({ id: 'D' + (k + 1), type: 'Diode', start: { x: (k % 20) * 6, y: Math.floor(k / 20) * 4 }, properties: { model: 'cap-' + k } }));
    const capImp = await A('importCircuit', { doc: C, circuit: { elements: capElms, models: capModels(1, 201) } });
    const capGc = await A('getCircuit', { doc: C, limit: 1 });
    out.notes.cap = { ok: capImp.ok, issues: codes(capImp).slice(0, 3), models: capGc.data && capGc.data.models && capGc.data.models.length, truncated: capGc.data && capGc.data.modelsTruncated };
    ck('models_getCircuitCap', capImp.ok && capGc.data.models.length === 200 && capGc.data.modelsTruncated === 1);
    await A('closeDocument', { doc: C, discardChanges: true });
    // a valid ModelText defines the model; the element uses it
    const mtOk = await A('importCircuit', { doc: I, circuit: { models: [{ kind: 'diode', name: 'mt-ok', modelText: '34 mt-ok 0 2e-14 0 1.5 0 0\n' }],
      elements: [{ id: 'D1', type: 'Diode', start: { x: 0, y: 0 }, properties: { model: 'mt-ok' } }] } });
    const mtRec = await record('diode', 'mt-ok');
    ck('mt_defines', mtOk.ok && mtRec && mtRec.parameters.emission_coefficient === 1.5 && same(mtRec.usedBy, [{ doc: I, ids: ['D1'] }]));
    // logic ModelText (PL_AGA Phase 12) defines a new name; the ensured default, identical, is accepted
    const lxText = await A('importCircuit', { doc: I, circuit: mt({ kind: 'logic', name: 'lx', modelText: '! lx 0 A Y lx 1\\q1\\n' }) });
    const lxRec = await record('logic', 'lx');
    ck('mt_logicNew', lxText.ok && lxRec && same(lxRec.inputs, ['A']) && same(lxRec.outputs, ['Y']) && same(lxRec.rules, ['1=1']));
    const ldText = (await A('importCircuit', { doc: I, circuit: { elements: [], models: [{ kind: 'logic', name: 'default', modelText: '! default 0 A,B C,D custom\\slogic \\0' }] } }));
    ck('mt_logicDefaultIdentical', ldText.ok);

    // round trip in the same session: getCircuit form → new document, every entry identical
    const gc = await A('getCircuit', { doc: D, detail: 'full' });
    const gcModels = gc.data.models || [];
    out.notes.gcModels = gcModels.map((m) => m.kind + ':' + m.name + (m.modelText ? ' (text)' : ' (spec)'));
    ck('get_modelsListed', gcModels.some((m) => m.name === 'led-green-2v1' && m.parameters) && gcModels.some((m) => m.name === 'bjt-inf' && m.parameters)
      && !gcModels.some((m) => m.name === 'default' || m.name === 'spice-default'));
    const gcPage2 = await A('getCircuit', { doc: D, offset: 1 });
    ck('get_modelsOffsetZeroOnly', gcPage2.ok && gcPage2.data.models === undefined);
    const form = { elements: gc.data.elements, simulation: gc.data.simulation, scopes: gc.data.scopes, models: gcModels };
    const RT = (await A('createDocument', { title: 'Roundtrip' })).data.doc;
    const c0 = await catalogue();
    const rt = await A('importCircuit', { doc: RT, circuit: form });
    const c1 = await catalogue();
    const gc2 = await A('getCircuit', { doc: RT, detail: 'full' });
    const recs = (g) => JSON.stringify(g.data.elements.map((e) => { const c = Object.assign({}, e); delete c.posts; return c; }));
    out.notes.roundtrip = { ok: rt.ok, issues: (rt.issues || []).map((i) => i.code + ': ' + i.message), catalogueSame: c0 === c1, recordsSame: recs(gc) === recs(gc2),
      modelsSame: same(gc2.data.models, gcModels) };
    if (recs(gc) !== recs(gc2)) out.notes.roundtripRecords = [recs(gc).slice(0, 1500), recs(gc2).slice(0, 1500)];
    ck('roundtrip_sameSession', rt.ok && c0 === c1 && recs(gc) === recs(gc2) && same(gc2.data.models, gcModels));
    // a different line under an existing name in the content → name_taken with the openFile hint
    const differing = JSON.parse(JSON.stringify(form));
    differing.models.find((m) => m.name === 'led-green-2v1').parameters.forward_current = '10 mA';
    delete differing.models.find((m) => m.name === 'led-green-2v1').parameters.forward_voltage;
    differing.models.find((m) => m.name === 'led-green-2v1').parameters = { forward_voltage: '2.1 V', forward_current: '10 mA' };
    await rejects('roundtrip_differing', RT, () => A('importCircuit', { doc: RT, circuit: differing }), 'name_taken', /open the file with `openFile`/i);

    // ModelText fallback: an editor-made diode model fwdrop=0.8 (legacy diode line with a forward drop)
    const F = (await A('createDocument', { title: 'Fwdrop' })).data.doc;
    const fw = await A('importCircuit', { doc: F, circuit: '$ 1 0.000005 10 50 5 50 5e-11\nd 0 0 64 0 1 0.8\n' });
    const fgc = await A('getCircuit', { doc: F, detail: 'full' });
    const fm = (fgc.data.models || []).find((m) => /^fwdrop=0\.8/.test(m.name));
    out.notes.fwdrop = fm;
    ck('fallback_modelText', fw.ok && fm && typeof fm.modelText === 'string' && fm.modelText.startsWith('34 ') && !fm.parameters);
    const F2 = (await A('createDocument', {})).data.doc;
    const fc0 = await catalogue();
    const frt = await A('importCircuit', { doc: F2, circuit: { elements: fgc.data.elements, simulation: fgc.data.simulation, models: fgc.data.models } });
    ck('fallback_reimportIdentical', frt.ok && fc0 === await catalogue());

    // fresh session (side page): the getCircuit form restores the models it carries
    // a fresh session: withSidePage serves the app from another origin (no session restore of this page's tabs)
    await withSidePage(s, async (s2) => {
      const A2 = async (op, args) => JSON.parse(await s2.eval(`CircuitJS1Agent.call(${JSON.stringify(op)}, ${JSON.stringify(JSON.stringify(args))})`));
      await waitFor(async () => { try { return (await A2('listDocuments', {})).ok; } catch { return false; } }, LOAD_TIMEOUT_MS, 'side page');
      const before = await A2('listModels', { kind: 'diode', name: 'led-green-2v1' });
      const fr = await A2('importCircuit', { circuit: form });
      const after = await A2('listModels', { kind: 'diode', name: 'led-green-2v1' });
      const back = await A2('getCircuit', { detail: 'full' });
      out.notes.fresh = { before: codes(before), import: fr.ok ? 'ok' : codes(fr), after: after.ok && after.data.models[0].parameters, backModels: back.data && back.data.models };
      ck('roundtrip_freshSession', has(before, 'unknown_model') && fr.ok && after.ok
        && same(after.data.models[0].parameters, (await record('diode', 'led-green-2v1')).parameters)
        && same(back.data.models, gcModels));
    });

    // ---------------------------------------------------------------- legacy text content
    const T = (await A('createDocument', { title: 'Text' })).data.doc;
    const opts = '$ 1 0.000005 10 50 5 50 5e-11';
    await rejects('text_differingLogicDefault', T, () => A('importCircuit', { doc: T, circuit: opts + '\n! default 0 A,B C,D custom\\slogic 11\\q11\\n\n' }), 'name_taken', /open the file with `openFile`/i);
    // a CustomLogic line naming an unknown model, without a model line
    const ltxt = (await A('exportCircuit', { doc: L, format: 'text' })).data.content.split('\n');
    const clLine = ltxt.find((l) => l.startsWith('208 '));
    const withModel = (name) => { const t = clLine.split(' '); t[6] = name; return t.join(' '); };
    out.notes.clLine = clLine;
    await rejects('text_unknownLogicModel', T, () => A('importCircuit', { doc: T, circuit: opts + '\n' + withModel('nope') + '\n' }), 'invalid_value', /'nope'/);
    const tu = await A('importCircuit', { doc: T, circuit: opts + '\n' + withModel('nope') + '\n' });
    ck('text_unknownLogicNamesElement', tu.issues.some((i) => i.code === 'invalid_value' && (i.elements || []).length === 1));
    ck('text_unknownLogicNotRegistered', has(await A('listModels', { kind: 'logic', name: 'nope' }), 'unknown_model'));
    // control: the same line after its model line loads (a model defined by the content)
    const tk = await A('importCircuit', { doc: T, circuit: opts + '\n! tl-ok 0 A,B Y tl-ok 11\\q1\\n\n' + withModel('tl-ok') + '\n' });
    ck('text_logicModelLineDefines', tk.ok && (await record('logic', 'tl-ok')) !== null);
    // the element line before its own model line: the fallback's empty entry is no session entry,
    // so the later model line defines the model (no name_taken) and the element takes its pins
    const tl = await A('importCircuit', { doc: T, circuit: opts + '\n' + withModel('tl-late') + '\n! tl-late 0 A,B Y tl-late 11\\q1\\n\n' });
    const tlRec = await record('logic', 'tl-late');
    const tlElm = tl.ok ? (await A('getCircuit', { doc: T })).data.elements.find((e) => e.type === 'CustomLogic') : null;
    out.notes.modelLineAfterElement = { ok: tl.ok, issues: codes(tl), rec: tlRec && [tlRec.inputs, tlRec.outputs], posts: tlElm && tlElm.posts.map((p) => p.pin) };
    ck('text_modelLineAfterElement', tl.ok && tlRec && same(tlRec.outputs, ['Y']) && tlElm && same(tlElm.posts.map((p) => p.pin), ['A', 'B', 'Y']));
    // a rejected import puts back the fwdrop entry its legacy diode line created
    await rejects('text_fwdropRestored', T, () => A('importCircuit', { doc: T, circuit: opts + '\nd 0 0 64 0 1 0.777\nHello there\n' }), 'import_element_skipped');
    ck('text_fwdropNotListed', has(await A('listModels', { kind: 'diode', name: 'fwdrop=0.777' }), 'unknown_model'));

    // ---------------------------------------------------------------- openFile with an unknown logic model
    const jsonDoc = JSON.parse((await A('exportCircuit', { doc: L, format: 'json' })).data.content);
    for (const e of Object.values(jsonDoc.elements)) if (e.type === 'CustomLogic') e.properties.model_name = 'nope-file-j';
    const files = { '/tmp/am_logic.txt': opts + '\n' + withModel('nope-file-t') + '\n', '/tmp/am_logic.json': JSON.stringify(jsonDoc),
      '/tmp/am_reject.txt': opts + '\n' + withModel('nope-file-r') + '\nd 0 64 64 64 1 0.778\n32 am-badt 0 abc\n' };
    await s.eval(`(() => {
      const files = ${JSON.stringify(files)};
      const err = (p) => { const e = new Error('ENOENT: no such file or directory, ' + p); e.code = 'ENOENT'; return e; };
      const loose = (base) => new Proxy(base, { get: (t, k) => (k in t ? t[k] : () => undefined) });
      const fs = loose({
        realpathSync: (p) => { if (!(p in files)) throw err(p); return p; },
        statSync: (p) => { if (!(p in files)) throw err(p); return { isFile: () => true, size: files[p].length, mode: 420 }; },
        readFileSync: (p) => { if (!(p in files)) throw err(p); const t = files[p]; return { length: t.length, toString: () => t }; },
      });
      const path = loose({ resolve: (p) => p, isAbsolute: (p) => p.startsWith('/'), basename: (p) => p.replace(/^.*\\//, ''), join: (...a) => a.join('/'), dirname: (p) => p.replace(/\\/[^/]*$/, '') });
      window.__savedNw = window.nw;
      window.nw = { require: (m) => (m === 'fs' ? fs : m === 'path' ? path : m === 'buffer' ? { Buffer: function () {} } : undefined) };
    })()`);
    try {
      const docsBefore = (await A('listDocuments', {})).data.documents.length;
      const ot = await A('openFile', { path: '/tmp/am_logic.txt' });
      const oj = await A('openFile', { path: '/tmp/am_logic.json' });
      out.notes.openFile = { text: { ok: ot.ok, issues: (ot.issues || []).map((i) => i.code + ': ' + i.message) }, json: { ok: oj.ok, issues: (oj.issues || []).map((i) => i.code + ': ' + i.message) } };
      const rt1 = await record('logic', 'nope-file-t');
      const rj1 = await record('logic', 'nope-file-j');
      ck('openFile_textUnknownLogic', ot.ok && has(ot, 'value_adjusted', /nope-file-t/) && rt1 && same(rt1.inputs, ['A', 'B']) && same(rt1.outputs, ['C', 'D']) && same(rt1.rules, []));
      ck('openFile_jsonUnknownLogic', oj.ok && has(oj, 'value_adjusted', /nope-file-j/) && rj1 !== null);
      ck('openFile_documentsCreated', (await A('listDocuments', {})).data.documents.length === docsBefore + 2);
      for (const r of [ot, oj]) if (r.ok) await A('closeDocument', { doc: r.data.doc, discardChanges: true });
      // a rejected openFile (an unknown line) runs the restorers of the entries its lines created
      const c0r = await catalogue();
      const orj = await A('openFile', { path: '/tmp/am_reject.txt' });
      ck('openFile_rejectedRestores', orj.ok === false && has(orj, 'import_element_skipped') && c0r === await catalogue()
        && has(await A('listModels', { kind: 'logic', name: 'nope-file-r' }), 'unknown_model')
        && has(await A('listModels', { kind: 'diode', name: 'fwdrop=0.778' }), 'unknown_model')
        && (await A('listDocuments', {})).data.documents.length === docsBefore);
    } finally {
      await s.eval('window.nw = window.__savedNw; delete window.__savedNw; if (window.nw === undefined) delete window.nw;');
    }

    for (const doc of [D, L, I, RT, F, F2, T]) await A('closeDocument', { doc, discardChanges: true });
    out.rejections = rejections;
    ck('visibleTabUnchanged', same(await s.call('visibleTab'), vis0));
    const unexpected = s.exceptions.slice(exMark).filter((e) => !/debugFailNextMutation/.test(e));
    out.notes.exceptions = unexpected.slice(0, 5);
    ck('noPageException', unexpected.length === 0);
    ck('noAlert', s.dialogs.length === alertMark);
  } catch (e) {
    out.notes.error = e.stack || e.message;
    ck('noHarnessError', false);
  }
  fs.writeFileSync(path.join(OUT_DIR, 'agent_models.json'), JSON.stringify(out, null, 2));
  const failed = Object.entries(out.checks).filter(([, v]) => !v).map(([k]) => k);
  report('AG.agent_models', failed.length === 0, { checks: Object.keys(out.checks).length, failed, details: path.join(OUT_DIR, 'agent_models.json') });
}

// agent_models_logic: custom logic model definitions (PL_AGA Phase 12; SP_AGA_01_13 logic ModelSpec,
// pin markup, limits, logic ModelText import; §03_11 No dialogs; §06_01 item 25; §05_01 rows
// defineModel logic / logic bad rule / logic limits / logic pin names / bad `from` (logic),
// importCircuit ModelText errors (logic line with a bad rule), getCircuit ModelText fallback (a
// 9-char pin name), same-session re-import with a logic model). window.alert is hooked in the page
// for the whole scenario: no agent path may reach it; the editor's model dialog (driven through the
// UI: double click, "Edit Model", a bad rule, OK) still alerts the parser's message.
async function scenarioAgentModelsLogic(s) {
  const out = { checks: {}, notes: {} };
  const ck = (name, cond) => { out.checks[name] = !!cond; return !!cond; };
  const A = (op, args) => s.call('agentCall', op, args);
  const R = (args) => s.call('agentAsync', 'run', args, 60000);
  const codes = (r) => (r.issues || []).map((i) => i.code);
  const has = (r, code, re) => (r.issues || []).some((i) => i.code === code && (!re || re.test(i.message + ' ' + (i.hint || ''))));
  const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
  const strip = (m) => { const c = Object.assign({}, m); delete c.usedBy; return c; };
  const catalogue = async () => JSON.stringify((await A('listModels', {})).data.models.map(strip));
  const record = async (kind, name) => { const r = await A('listModels', { kind, name }); return r.ok ? r.data.models[0] : null; };
  const alerts = async () => JSON.parse(await s.eval('JSON.stringify(window.__alerts || [])'));
  const docState = async (doc) => {
    const st = JSON.parse(await s.eval(`CircuitJS1Agent.debugDocState(${JSON.stringify(doc)})`));
    return { text: (await A('exportCircuit', { doc, format: 'text' })).data.content, ids: (await A('getCircuit', { doc })).data.elements.map((e) => e.id), undo: st.undo, marks: st.openMarks };
  };
  const define = (doc, model, more) => A('applyEdits', { doc, edits: [{ op: 'defineModel', model }].concat(more || []) });
  const rejections = {};
  const rejects = async (name, doc, fn, code, re) => {
    const c0 = await catalogue(); const d0 = await docState(doc); const a0 = (await alerts()).length;
    const r = await fn();
    const c1 = await catalogue(); const d1 = await docState(doc); const a1 = (await alerts()).length;
    rejections[name] = { ok: r.ok, issues: (r.issues || []).map((i) => i.code + ': ' + i.message + ' | ' + (i.hint || '')) };
    return ck(name, r.ok === false && has(r, code, re) && c0 === c1 && same(d0, d1) && a0 === a1);
  };
  const posts = (rec) => (rec ? rec.posts.map((p) => p.pin) : null);
  const postAt = (rec, pin) => rec.posts.find((p) => p.pin === pin).at;
  await s.eval('window.__alerts = []; window.__savedAlert = window.alert; window.alert = (m) => { window.__alerts.push(String(m)); };');
  try {
    await resetApp(s);
    const exMark = s.exceptions.length;
    const dialogMark = s.dialogs.length;
    const D = (await A('createDocument', { title: 'Logic models' })).data.doc;

    // ---------------------------------------------------------------- defineModel logic (AND), used in the same batch
    const andSpec = { kind: 'logic', name: 'lg-and', inputs: ['A', 'B'], outputs: ['Y'], rules: ['11=1', '??=0'] };
    const and = await define(D, andSpec, [{ op: 'add', element: { id: 'CL1', type: 'CustomLogic', start: { x: 10, y: 4 }, properties: { model_name: 'lg-and' } } }]);
    const cl1 = and.ok ? and.data.elements.find((e) => e.id === 'CL1') : null;
    const m0 = and.ok ? and.data.models[0] : null;
    out.notes.and = { ok: and.ok, issues: codes(and), model: m0, posts: cl1 && cl1.posts };
    ck('and_defined', and.ok && m0 && m0.kind === 'logic' && m0.builtIn === false && !m0.existing && same(m0.inputs, ['A', 'B']) && same(m0.outputs, ['Y'])
      && same(m0.rules, ['11=1', '??=0']) && m0.info === 'lg-and' && same(m0.usedBy, [{ doc: D, ids: ['CL1'] }]));
    ck('and_elementPins', same(posts(cl1), ['A', 'B', 'Y']) && cl1.properties.input_count === 2 && cl1.properties.output_count === 1);
    if (cl1) {
      const a = postAt(cl1, 'A'), b = postAt(cl1, 'B');
      await A('applyEdits', { doc: D, edits: [
        { op: 'add', element: { id: 'IA', type: 'LogicInput', start: a, end: { x: a.x - 3, y: a.y }, properties: { position: 1 } } },
        { op: 'add', element: { id: 'IB', type: 'LogicInput', start: b, end: { x: b.x - 3, y: b.y }, properties: { position: 1 } } }] });
      const truth = {};
      for (const [pa, pb] of [[1, 1], [1, 0], [0, 1], [0, 0]]) {
        await A('applyEdits', { doc: D, edits: [{ op: 'set', id: 'IA', properties: { position: pa } }, { op: 'set', id: 'IB', properties: { position: pb } }] });
        const run = await R({ doc: D, span: '1 ms', reset: true, maxPoints: 10, probes: [{ post: 'CL1.Y', name: 'y' }] });
        truth[`${pa}${pb}`] = run.ok ? run.data.probes[0].stats.final : codes(run).join(',');
      }
      out.notes.andTruth = truth;
      ck('and_computesAnd', truth['11'] > 2.5 && truth['10'] < 0.5 && truth['01'] < 0.5 && truth['00'] < 0.5);
    }
    // identical redefinition (existing); a definition differing only in info is name_taken
    const andAgain = await define(D, andSpec);
    ck('and_identicalExisting', andAgain.ok && andAgain.data.models[0].existing === true);
    await rejects('and_differingInfo', D, () => define(D, Object.assign({}, andSpec, { info: 'other' })), 'name_taken');

    // ---------------------------------------------------------------- bad rules: invalid_value naming rules[i], no alert
    await rejects('badRule_spec', D, () => define(D, { kind: 'logic', name: 'lg-bad1', inputs: ['A', 'B'], outputs: ['Y'], rules: ['1=11'] }), 'invalid_value', /'edits\[0\]\.model\.rules\[0\]'/);
    await rejects('badRule_afterComment', D, () => define(D, { kind: 'logic', name: 'lg-bad2', inputs: ['A', 'B'], outputs: ['Y'], rules: ['# AND', '11=1', '1*=0'] }), 'invalid_value', /rules\[2\]'/);
    await rejects('badRule_rightSide', D, () => define(D, { kind: 'logic', name: 'lg-bad3', inputs: ['A', 'B'], outputs: ['Y'], rules: ['11=10'] }), 'invalid_value', /rules\[0\]'.*right side/);
    await rejects('badRule_noEquals', D, () => define(D, { kind: 'logic', name: 'lg-bad4', inputs: ['A'], outputs: ['Y'], rules: ['11'] }), 'invalid_value', /rules\[0\]'/);
    ck('badRule_notRegistered', has(await A('listModels', { kind: 'logic', name: 'lg-bad1' }), 'unknown_model'));
    await rejects('from_logic', D, () => define(D, { kind: 'logic', name: 'lg-from', from: 'default', inputs: ['A'], outputs: ['Y'], rules: ['1=1'] }), 'invalid_value', /\.from'/);
    await rejects('logic_parametersField', D, () => define(D, { kind: 'logic', name: 'lg-par', inputs: ['A'], outputs: ['Y'], rules: ['1=1'], parameters: {} }), 'invalid_value', /\.parameters'/);

    // ---------------------------------------------------------------- limits
    const pinsN = (n, p) => Array.from({ length: n }, (_, k) => p + k);
    const lim = (name, extra) => Object.assign({ kind: 'logic', name, inputs: ['A', 'B'], outputs: ['Y'], rules: ['11=1'] }, extra);
    const limits = {
      inputs33: [{ inputs: pinsN(33, 'I'), rules: ['?'.repeat(33) + '=0'] }, /model\.inputs'/],
      outputsNone: [{ outputs: [] }, /model\.outputs'/],
      pin9chars: [{ inputs: ['ABCDEFGHI', 'B'] }, /model\.inputs\[0\]'/],
      pinComma: [{ inputs: ['A,C', 'B'] }, /model\.inputs\[0\]'/],
      duplicateInput: [{ inputs: ['A', 'A'] }, /model\.inputs\[1\]'/],
      duplicateAcross: [{ outputs: ['A'] }, /model\.outputs\[0\]'/],
      pinClk: [{ inputs: ['A', 'CLK'] }, /model\.inputs\[1\]'.*empty after its markup/],
      pinSlashOnly: [{ outputs: ['/'] }, /model\.outputs\[0\]'.*empty after its markup/],
      pinInvOnly: [{ inputs: ['INV:', 'B'] }, /model\.inputs\[0\]'/],
      rules257: [{ rules: Array.from({ length: 257 }, () => '11=1') }, /model\.rules'/],
      rulesNone: [{ rules: [] }, /model\.rules'/],
      rule101: [{ rules: ['11=1', '# ' + 'x'.repeat(99)] }, /model\.rules\[1\]'/],
      ruleTwoLines: [{ rules: ['11=1\n00=0'] }, /model\.rules\[0\]'/],
      info201: [{ info: 'i'.repeat(201) }, /model\.info'/],
    };
    for (const [k, [extra, re]] of Object.entries(limits)) {
      await rejects('limit_' + k, D, () => define(D, lim('lg-lim-' + k, extra)), 'invalid_value', re);
    }
    // the limits themselves are accepted: 32 pins per side, 256 rule lines of 100 chars, info of 200 chars
    const maxOk = await define(D, lim('lg-max', { inputs: pinsN(32, 'I'), outputs: pinsN(32, 'O'), info: 'i'.repeat(200),
      rules: ['?'.repeat(32) + '=' + '0'.repeat(32), '# ' + 'x'.repeat(98)].concat(Array.from({ length: 254 }, () => '#')) }));
    out.notes.maxOk = { ok: maxOk.ok, issues: (maxOk.issues || []).map((i) => i.message) };
    ck('limit_maxAccepted', maxOk.ok);

    // ---------------------------------------------------------------- pin names (markup)
    const latch = { kind: 'logic', name: 'lg-dff', inputs: ['D', 'CLK:C'], outputs: ['Q', '/Q'], rules: ['0+=01', '1+=10', '??ab=ab'], info: 'D flip-flop' };
    const lt = await define(D, latch, [{ op: 'add', element: { id: 'FF1', type: 'CustomLogic', start: { x: 30, y: 4 }, properties: { model_name: 'lg-dff' } } },
      { op: 'markOpen', posts: ['FF1.Q_2'] }]);
    const ff = lt.ok ? lt.data.elements.find((e) => e.id === 'FF1') : null;
    out.notes.latch = { ok: lt.ok, issues: (lt.issues || []).map((i) => i.code + ': ' + i.message), model: lt.ok && lt.data.models[0], posts: posts(ff) };
    ck('pins_elementNames', lt.ok && same(posts(ff), ['D', 'C', 'Q', 'Q_2']) && ff.posts[3].open === true);
    ck('pins_recordKeepsMarkup', lt.ok && same(lt.data.models[0].inputs, ['D', 'CLK:C']) && same(lt.data.models[0].outputs, ['Q', '/Q'])
      && lt.data.models[0].info === 'D flip-flop');
    // the flip-flop latches D on the rising clock edge
    if (ff) {
      const at = (p) => postAt(ff, p);
      await A('applyEdits', { doc: D, edits: [
        { op: 'add', element: { id: 'ID', type: 'LogicInput', start: at('D'), end: { x: at('D').x - 3, y: at('D').y }, properties: { position: 1 } } },
        { op: 'add', element: { id: 'IC', type: 'LogicInput', start: at('C'), end: { x: at('C').x - 3, y: at('C').y }, properties: { position: 0 } } }] });
      const q = async () => { const r = await R({ doc: D, span: '0.2 ms', maxPoints: 10, probes: [{ post: 'FF1.Q', name: 'q' }, { post: 'FF1.Q_2', name: 'nq' }] }); return r.ok ? r.data.probes.map((p) => p.stats.final) : codes(r); };
      await R({ doc: D, span: '0.2 ms', reset: true, maxPoints: 10, probes: [{ post: 'FF1.Q' }] });
      const q0 = await q();
      await A('applyEdits', { doc: D, edits: [{ op: 'set', id: 'IC', properties: { position: 1 } }] });
      const q1 = await q();
      await A('applyEdits', { doc: D, edits: [{ op: 'set', id: 'ID', properties: { position: 0 } }] });
      const q2 = await q();
      out.notes.latchRun = { q0, q1, q2 };
      ck('pins_flipFlopLatches', q1[0] > 2.5 && q1[1] < 0.5 && q2[0] > 2.5 && q2[1] < 0.5);
    }

    // the D latch of the skill text (agent-format §6, elements.md): transparent while E is high, holds while low
    const dl = await define(D, { kind: 'logic', name: 'lg-dlatch', inputs: ['D', 'E'], outputs: ['Q', '/Q'], rules: ['01=01', '11=10', '??ab=ab'] },
      [{ op: 'add', element: { id: 'DL1', type: 'CustomLogic', start: { x: 40, y: 14 }, properties: { model_name: 'lg-dlatch' } } }]);
    const dle = dl.ok ? dl.data.elements.find((e) => e.id === 'DL1') : null;
    if (dle) {
      const at = (p) => postAt(dle, p);
      await A('applyEdits', { doc: D, edits: [
        { op: 'add', element: { id: 'LD', type: 'LogicInput', start: at('D'), end: { x: at('D').x - 3, y: at('D').y }, properties: { position: 1 } } },
        { op: 'add', element: { id: 'LE', type: 'LogicInput', start: at('E'), end: { x: at('E').x - 3, y: at('E').y }, properties: { position: 1 } } }] });
      const q = async (reset) => { const r = await R({ doc: D, span: '0.2 ms', reset, maxPoints: 10, probes: [{ post: 'DL1.Q', name: 'q' }, { post: 'DL1.Q_2', name: 'nq' }] }); return r.ok ? r.data.probes.map((p) => p.stats.final) : codes(r); };
      const l1 = await q(true); // E high, D high: Q follows D
      await A('applyEdits', { doc: D, edits: [{ op: 'set', id: 'LE', properties: { position: 0 } }] });
      await A('applyEdits', { doc: D, edits: [{ op: 'set', id: 'LD', properties: { position: 0 } }] });
      const l2 = await q(false); // E low: Q holds 1
      await A('applyEdits', { doc: D, edits: [{ op: 'set', id: 'LE', properties: { position: 1 } }] });
      const l3 = await q(false); // E high again: Q follows D = 0
      out.notes.dlatchRun = { posts: posts(dle), l1, l2, l3 };
      ck('pins_dLatch', same(posts(dle), ['D', 'E', 'Q', 'Q_2']) && l1[0] > 2.5 && l1[1] < 0.5 && l2[0] > 2.5 && l3[0] < 0.5 && l3[1] > 2.5);
    } else {
      ck('pins_dLatch', false);
    }

    // ---------------------------------------------------------------- set model_name: the element takes the model's pins
    const sm = await A('applyEdits', { doc: D, edits: [{ op: 'add', element: { id: 'CL2', type: 'CustomLogic', start: { x: 50, y: 4 }, properties: { model_name: 'default' } } }] });
    const cl2a = sm.ok ? sm.data.elements[0] : null;
    const sm2 = await A('applyEdits', { doc: D, edits: [{ op: 'set', id: 'CL2', properties: { model_name: 'lg-and' } }, { op: 'markOpen', posts: ['CL2.Y'] }] });
    const cl2b = sm2.ok ? sm2.data.elements.find((e) => e.id === 'CL2') : null;
    out.notes.setModel = { before: posts(cl2a), after: posts(cl2b), issues: (sm2.issues || []).map((i) => i.code + ': ' + i.message) };
    ck('set_modelPins', same(posts(cl2a), ['A', 'B', 'C', 'D']) && sm2.ok && same(posts(cl2b), ['A', 'B', 'Y']) && cl2b.properties.model_name === 'lg-and'
      && cl2b.properties.output_count === 1 && cl2b.posts[2].open === true && !has(sm2, 'value_adjusted'));
    const usedAnd = await record('logic', 'lg-and');
    ck('set_usedBy', usedAnd && same(usedAnd.usedBy, [{ doc: D, ids: ['CL1', 'CL2'] }]));

    // ---------------------------------------------------------------- importCircuit: logic ModelText
    const I = (await A('createDocument', { title: 'Logic import' })).data.doc;
    const mt = (m) => ({ elements: [], models: [m] });
    await rejects('mt_badRule', I, () => A('importCircuit', { doc: I, circuit: mt({ kind: 'logic', name: 'lg-mt', modelText: '! lg-mt 0 A,B Y lg-mt 1\\q11\\n' }) }), 'invalid_value', /modelText'.*rule line 1/);
    ck('mt_badRuleNotRegistered', has(await A('listModels', { kind: 'logic', name: 'lg-mt' }), 'unknown_model'));
    await rejects('mt_badFlags', I, () => A('importCircuit', { doc: I, circuit: mt({ kind: 'logic', name: 'lg-mt', modelText: '! lg-mt x A,B Y lg-mt 11\\q1\\n' }) }), 'invalid_value', /modelText'/);
    // a 9-char pin name travels as ModelText; the element takes its pins
    const longText = '! lg-long 0 ABCDEFGHI,B Y lg-long 11\\q1\\n';
    const lo = await A('importCircuit', { doc: I, circuit: { models: [{ kind: 'logic', name: 'lg-long', modelText: longText }],
      elements: [{ id: 'CL3', type: 'CustomLogic', start: { x: 0, y: 0 }, properties: { model_name: 'lg-long' } },
        { id: 'CL4', type: 'CustomLogic', start: { x: 12, y: 0 }, properties: { model_name: 'lg-and' } }] } });
    const loRec = await record('logic', 'lg-long');
    out.notes.longImport = { ok: lo.ok, issues: (lo.issues || []).map((i) => i.code + ': ' + i.message), rec: loRec && strip(loRec) };
    ck('mt_defines', lo.ok && loRec && same(loRec.inputs, ['ABCDEFGHI', 'B']) && same(loRec.rules, ['11=1']));
    const gc = await A('getCircuit', { doc: I, detail: 'full' });
    const gm = gc.data.models || [];
    out.notes.gcModels = gm;
    const gLong = gm.find((m) => m.name === 'lg-long'), gAnd = gm.find((m) => m.name === 'lg-and');
    ck('get_longPinIsModelText', gLong && gLong.modelText === longText && !gLong.inputs);
    ck('get_andIsModelSpec', gAnd && !gAnd.modelText && same(gAnd.inputs, ['A', 'B']) && same(gAnd.outputs, ['Y']) && same(gAnd.rules, ['11=1', '??=0']) && gAnd.info === 'lg-and');
    ck('get_elementPins', same(posts(gc.data.elements.find((e) => e.id === 'CL3')), ['ABCDEFGHI', 'B', 'Y']));
    // same-session re-import of the getCircuit form: every entry identical, records equal
    const RT = (await A('createDocument', { title: 'Logic roundtrip' })).data.doc;
    const c0 = await catalogue();
    const rt = await A('importCircuit', { doc: RT, circuit: { elements: gc.data.elements, simulation: gc.data.simulation, scopes: gc.data.scopes, models: gm } });
    const c1 = await catalogue();
    const gc2 = await A('getCircuit', { doc: RT, detail: 'full' });
    const recs = (g) => JSON.stringify(g.data.elements.map((e) => { const c = Object.assign({}, e); delete c.posts; return c; }));
    out.notes.roundtrip = { ok: rt.ok, issues: (rt.issues || []).map((i) => i.code + ': ' + i.message), catalogueSame: c0 === c1 };
    ck('roundtrip_sameSession', rt.ok && c0 === c1 && recs(gc) === recs(gc2) && same(gc2.data.models, gm));
    // the ModelSpec form of getCircuit, defined under a new name, gives the same model line but the name
    const asNew = Object.assign({}, gAnd, { name: 'lg-and-copy' });
    const cp = await define(I, asNew, [{ op: 'add', element: { id: 'CL5', type: 'CustomLogic', start: { x: 24, y: 0 }, properties: { model_name: 'lg-and-copy' } } }]);
    const txt = (await A('exportCircuit', { doc: I, format: 'text' })).data.content;
    const line = (n) => txt.split('\n').find((l) => l.startsWith('! ' + n + ' '));
    out.notes.lines = [line('lg-and'), line('lg-and-copy')];
    ck('spec_reimportSameLine', cp.ok && line('lg-and') && line('lg-and-copy') && line('lg-and').replace(/lg-and/g, 'X') === line('lg-and-copy').replace(/lg-and-copy/g, 'X').replace(/lg-and/g, 'X'));

    // ---------------------------------------------------------------- legacy text content
    const T = (await A('createDocument', { title: 'Logic text' })).data.doc;
    const opts = '$ 1 0.000005 10 50 5 50 5e-11';
    await rejects('text_badRuleLine', T, () => A('importCircuit', { doc: T, circuit: opts + '\n! lg-tbad 0 A,B Y lg-tbad 1\\q11\\n\n' }), 'invalid_value', /lg-tbad.*rule line 1/);
    ck('text_badRuleNotRegistered', has(await A('listModels', { kind: 'logic', name: 'lg-tbad' }), 'unknown_model'));
    const tg = await A('importCircuit', { doc: T, circuit: opts + '\n! lg-tok 0 A,B Y lg-tok 11\\q1\\n\n' });
    ck('text_goodRuleLine', tg.ok && (await record('logic', 'lg-tok')) !== null);
    await rejects('text_differingLogicDefault', T, () => A('importCircuit', { doc: T, circuit: opts + '\n! default 0 A,B C,D custom\\slogic 11\\q11\\n\n' }), 'name_taken', /open the file with `openFile`/i);

    // ---------------------------------------------------------------- openFile of a file with a bad rule: loads, warning, no alert
    const files = { '/tmp/aml_bad.txt': opts + '\n! lg-fbad 0 A,B Y lg-fbad 11\\q1\\n1\\q11\\n\n' };
    await s.eval(`(() => {
      const files = ${JSON.stringify(files)};
      const err = (p) => { const e = new Error('ENOENT: no such file or directory, ' + p); e.code = 'ENOENT'; return e; };
      const loose = (base) => new Proxy(base, { get: (t, k) => (k in t ? t[k] : () => undefined) });
      const fs = loose({
        realpathSync: (p) => { if (!(p in files)) throw err(p); return p; },
        statSync: (p) => { if (!(p in files)) throw err(p); return { isFile: () => true, size: files[p].length, mode: 420 }; },
        readFileSync: (p) => { if (!(p in files)) throw err(p); const t = files[p]; return { length: t.length, toString: () => t }; },
      });
      const path = loose({ resolve: (p) => p, isAbsolute: (p) => p.startsWith('/'), basename: (p) => p.replace(/^.*\\//, ''), join: (...a) => a.join('/'), dirname: (p) => p.replace(/\\/[^/]*$/, '') });
      window.__savedNw = window.nw;
      window.nw = { require: (m) => (m === 'fs' ? fs : m === 'path' ? path : m === 'buffer' ? { Buffer: function () {} } : undefined) };
    })()`);
    try {
      const a0 = (await alerts()).length;
      const of = await A('openFile', { path: '/tmp/aml_bad.txt' });
      const fr = await record('logic', 'lg-fbad');
      out.notes.openFile = { ok: of.ok, issues: (of.issues || []).map((i) => i.code + ': ' + i.message), rec: fr && fr.rules };
      ck('openFile_badRuleLoads', of.ok && has(of, 'value_adjusted', /lg-fbad.*rule line 2/) && fr && same(fr.rules, ['11=1', '1=11'])
        && (await alerts()).length === a0);
      if (of.ok) await A('closeDocument', { doc: of.data.doc, discardChanges: true });
    } finally {
      await s.eval('window.nw = window.__savedNw; delete window.__savedNw; if (window.nw === undefined) delete window.nw;');
    }

    // ---------------------------------------------------------------- user load of a bad rule alerts (editor behaviour); agent undo/redo over it do not
    let userAlerts = 0;
    {
      const V = (await A('createDocument', { title: 'Logic user', activate: true })).data.doc;
      await sleep(200);
      const a0 = (await alerts()).length;
      await s.call('importText', opts + '\n! lg-ubad 0 A,B Y lg-ubad 1\\q11\\n\n');
      const afterLoad = (await alerts()).slice(a0);
      const a1 = a0 + afterLoad.length;
      // an element using the user's model, so every undo entry carries its model line
      const ed = await A('applyEdits', { doc: V, edits: [{ op: 'add', element: { id: 'CU', type: 'CustomLogic', start: { x: 2, y: 2 }, properties: { model_name: 'lg-ubad' } } }] });
      await A('checkpoint', { doc: V });
      const ed2 = await A('applyEdits', { doc: V, edits: [{ op: 'add', element: { id: 'R1', type: 'Resistor', start: { x: 20, y: 0 }, end: { x: 24, y: 0 } } }] });
      const un = await A('undo', { doc: V });
      const re = await A('redo', { doc: V });
      const rc = await A('restoreCheckpoint', { doc: V, checkpointId: (await A('getHistory', { doc: V })).data.undo.map((e) => e.checkpointId).find((c) => c) });
      const a2 = (await alerts()).length;
      // a background document's agent undo: no alert either
      const W = (await A('createDocument', { title: 'Logic bg' })).data.doc;
      await A('importCircuit', { doc: W, circuit: (await A('exportCircuit', { doc: V, format: 'text' })).data.content });
      const bw1 = await A('applyEdits', { doc: W, edits: [{ op: 'add', element: { id: 'R9', type: 'Resistor', start: { x: 30, y: 0 }, end: { x: 34, y: 0 } } }] });
      const bu = await A('undo', { doc: W });
      const a3 = (await alerts()).length;
      await A('closeDocument', { doc: W, discardChanges: true });
      // the user's own redo (Ctrl+Y in the visible tab; after the restore the redo entry holds CU
      // and R1) reloads the model line: the editor alerts
      await s.call('focus'); await s.key('Escape');
      await s.key('KeyY', { ctrl: true });
      await sleep(300);
      const afterUserRedo = (await alerts()).slice(a3);
      out.notes.userLoad = { afterLoad, agentAlerts: a3 - a1, afterUserRedo, ok: [ed.ok, ed2.ok, un.ok, re.ok, rc.ok, bw1.ok, bu.ok] };
      ck('userLoad_alerts', afterLoad.length === 1 && afterLoad[0] === 'Model must have >= 2 digits on left side');
      ck('agentUndo_noAlert', ed.ok && ed2.ok && un.ok && re.ok && rc.ok && a2 === a1);
      ck('agentBackgroundUndo_noAlert', bw1.ok && bu.ok && a3 === a2);
      ck('userRedo_alerts', afterUserRedo.length >= 1 && afterUserRedo.every((m) => m === 'Model must have >= 2 digits on left side'));
      userAlerts = afterLoad.length + afterUserRedo.length;

      // a session entry whose stored rules do not parse (the user's load kept them) travels as
      // ModelText and re-imports where the identical entry exists (§02_05 Round trip, its exception);
      // as a new name (the fresh-session case) the same line is invalid_value
      const vg = await A('getCircuit', { doc: V, detail: 'full' });
      const ubad = (vg.data.models || []).find((m) => m.name === 'lg-ubad');
      const ar0 = (await alerts()).length;
      const RB = (await A('createDocument', { title: 'Logic bad rt' })).data.doc;
      const cb0 = await catalogue();
      const rb = await A('importCircuit', { doc: RB, circuit: { elements: vg.data.elements, simulation: vg.data.simulation, models: vg.data.models } });
      const cb1 = await catalogue();
      const rbm = await A('importCircuit', { doc: RB, circuit: { elements: [], models: [ubad] } });
      out.notes.badRoundtrip = { model: ubad, import: rb.ok ? 'ok' : (rb.issues || []).map((i) => i.code + ': ' + i.message), models: rbm.ok };
      ck('badRules_sameSessionReimport', ubad && typeof ubad.modelText === 'string' && rb.ok && rbm.ok && cb0 === cb1);
      const renamed = ubad ? { kind: 'logic', name: 'lg-ubad-new', modelText: ubad.modelText.replace(/lg-ubad/g, 'lg-ubad-new') } : null;
      await rejects('badRules_newNameRejected', RB, () => A('importCircuit', { doc: RB, circuit: { elements: [], models: [renamed] } }), 'invalid_value', /modelText'.*do not parse/);
      ck('badRules_noAlert', (await alerts()).length === ar0);
      await A('closeDocument', { doc: RB, discardChanges: true });

      // ---------------------------------------------------------------- editor: the model dialog still alerts a bad rule
      await A('closeDocument', { doc: V, discardChanges: true });
      const E = (await A('createDocument', { title: 'Logic editor', activate: true })).data.doc;
      await sleep(300);
      const ee = await define(E, { kind: 'logic', name: 'lg-edit', inputs: ['A', 'B'], outputs: ['Y'], rules: ['11=1'] },
        [{ op: 'add', element: { id: 'CE', type: 'CustomLogic', start: { x: 10, y: 6 }, properties: { model_name: 'lg-edit' } } }]);
      const ce = ee.ok ? ee.data.elements.find((e) => e.id === 'CE') : null;
      await A('checkpoint', { doc: E });
      await sleep(300);
      const dcr = await s.call('canvasRect');
      const view = (await s.call('visibleTab')).view;
      const k = dcr.w / view.canvas.width, t = view.transform;
      const toScreen = (gx, gy) => ({ x: dcr.x + (t[0] * gx + t[4]) * k, y: dcr.y + (t[3] * gy + t[5]) * k });
      let dialogAlert = null;
      if (ce) {
        const xs = ce.posts.map((p) => p.at.x), ys = ce.posts.map((p) => p.at.y);
        const mid = toScreen(16 * (Math.min(...xs) + Math.max(...xs)) / 2, 16 * (Math.min(...ys) + Math.max(...ys)) / 2);
        await s.call('focus'); await s.key('Escape');
        const b0 = (await alerts()).length;
        await s.mouseDoubleClick(mid.x, mid.y);
        await sleep(400);
        const shown = await s.call('dialogShowing');
        // the element dialog's one body button is "Edit Model" (labels are translated; the bottom
        // row .topSpace holds Apply, OK, Cancel)
        const clicked = await s.eval(`(() => { const b = Array.from(document.querySelectorAll('.gwt-DialogBox button')).find((x) => x.offsetWidth > 0 && !x.closest('.topSpace')); if (!b) return false; b.click(); return true; })()`);
        await sleep(400);
        const typed = await s.eval(`(() => { const ta = Array.from(document.querySelectorAll('.gwt-DialogBox textarea')).find((x) => x.offsetWidth > 0); if (!ta) return false; ta.value = '1=11'; const d = ta.closest('.gwt-DialogBox'); const ok = d.querySelectorAll('.topSpace button')[1]; if (!ok) return 'noOk'; ok.click(); return true; })()`);
        await sleep(400);
        const all = await alerts();
        dialogAlert = { at: mid, shown, clicked, typed, alerts: all.slice(b0) };
        await s.call('closeDialogs'); await s.key('Escape');
      }
      out.notes.editorDialog = dialogAlert;
      ck('editor_dialogAlertsBadRule', dialogAlert && dialogAlert.alerts.length >= 1 && /^Model must have >= 2 digits on left side$/.test(dialogAlert.alerts[0]));
      await A('closeDocument', { doc: E, discardChanges: true });
    }

    for (const doc of [D, I, RT, T]) await A('closeDocument', { doc, discardChanges: true });
    out.rejections = rejections;
    const unexpected = s.exceptions.slice(exMark);
    out.notes.exceptions = unexpected.slice(0, 5);
    ck('noPageException', unexpected.length === 0);
    // alerts: only the editor dialog's (hooked), none through CDP
    const allAlerts = await alerts();
    out.notes.alerts = allAlerts;
    ck('noAgentAlert', allAlerts.length === userAlerts + (out.notes.editorDialog ? out.notes.editorDialog.alerts.length : 0) && s.dialogs.length === dialogMark);
  } catch (e) {
    out.notes.error = e.stack || e.message;
    ck('noHarnessError', false);
  } finally {
    await s.eval('if (window.__savedAlert) { window.alert = window.__savedAlert; delete window.__savedAlert; }').catch(() => {});
  }
  fs.writeFileSync(path.join(OUT_DIR, 'agent_models_logic.json'), JSON.stringify(out, null, 2));
  const failed = Object.entries(out.checks).filter(([, v]) => !v).map(([k]) => k);
  report('AG.agent_models_logic', failed.length === 0, { checks: Object.keys(out.checks).length, failed, details: path.join(OUT_DIR, 'agent_models_logic.json') });
}

// agent_models_sub: subcircuit model definitions (PL_AGA Phase 13; SP_AGA_01_13 subcircuit ModelSpec
// `source` and ModelText, read-only source, source errors, inner references; §02_05 models closure;
// §03_11 Identical (state is part of the line), Dependencies, Scope (no `subcircuit:` storage), No
// dialogs; §05_01 rows defineModel subcircuit, subcircuit source errors, importCircuit unknown inner
// model, getCircuit dependency closure, same-session re-import with a subcircuit model; §05_02
// "ok=false ⇒ catalogues unchanged" and R1 for a build whose source is the active, free-running
// document). An RC block built in its own document behaves as the RC in a run; a block using an
// agent-defined diode model travels as [diode ModelSpec, subcircuit ModelText] and re-imports in the
// same session (identical) and in a fresh one (side page); every source error is invalid_value
// naming `source` with its reason, without an alert or dialog, with the catalogues and the source
// document unchanged. window.alert is hooked for the whole scenario; the editor's "Create
// Subcircuit" (File menu) still alerts a label on ground and still creates a model.
async function scenarioAgentModelsSub(s) {
  const out = { checks: {}, notes: {} };
  const ck = (name, cond) => { out.checks[name] = !!cond; return !!cond; };
  const A = (op, args) => s.call('agentCall', op, args);
  const R = (args) => s.call('agentAsync', 'run', args, 60000);
  const codes = (r) => (r.issues || []).map((i) => i.code);
  const has = (r, code, re) => (r.issues || []).some((i) => i.code === code && (!re || re.test(i.message + ' ' + (i.hint || ''))));
  const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
  const strip = (m) => { const c = Object.assign({}, m); delete c.usedBy; return c; };
  const catalogue = async () => JSON.stringify((await A('listModels', {})).data.models.map(strip));
  const record = async (kind, name) => { const r = await A('listModels', { kind, name }); return r.ok ? r.data.models[0] : null; };
  const alerts = async () => JSON.parse(await s.eval('JSON.stringify(window.__alerts || [])'));
  const storageKeys = () => s.eval("JSON.stringify(Object.keys(localStorage).filter((k) => k.startsWith('subcircuit:')).sort())");
  const text = async (doc) => (await A('exportCircuit', { doc, format: 'text' })).data.content;
  const docState = async (doc) => {
    const st = JSON.parse(await s.eval(`CircuitJS1Agent.debugDocState(${JSON.stringify(doc)})`));
    return { text: await text(doc), ids: (await A('getCircuit', { doc })).data.elements.map((e) => e.id), undo: st.undo, marks: st.openMarks };
  };
  const define = (doc, model, more) => A('applyEdits', { doc, edits: [{ op: 'defineModel', model }].concat(more || []) });
  const label = (id, text, x, y, ex, ey) => ({ id, type: 'LabeledNode', start: { x, y }, end: { x: ex, y: ey }, properties: { label: text } });
  const posts = (rec) => (rec ? rec.posts.map((p) => p.pin) : null);
  const postAt = (rec, pin) => rec.posts.find((p) => p.pin === pin).at;
  const rejections = {};
  let editorUncAlerts = 0; // the editor's "Some nodes are unconnected!" alerts of the unconnected_* variants
  // a rejection: the code (and reason), catalogues, the target and (when given) the source unchanged, no alert, no dialog
  const rejects = async (name, doc, src, fn, code, re) => {
    const c0 = await catalogue(); const d0 = await docState(doc); const s0 = src ? await docState(src) : null; const a0 = (await alerts()).length;
    const g0 = await s.call('dialogShowing');
    const r = await fn();
    const c1 = await catalogue(); const d1 = await docState(doc); const s1 = src ? await docState(src) : null; const a1 = (await alerts()).length;
    const g1 = await s.call('dialogShowing');
    rejections[name] = { ok: r.ok, issues: (r.issues || []).map((i) => i.code + ': ' + i.message + ' | ' + (i.hint || '')),
      unchanged: { catalogue: c0 === c1, doc: same(d0, d1), source: same(s0, s1), alerts: a0 === a1, dialogs: same(g0, g1) } };
    return ck(name, r.ok === false && has(r, code, re) && c0 === c1 && same(d0, d1) && same(s0, s1) && a0 === a1 && same(g0, g1));
  };
  // creates a document holding `elements` (AgentCircuit), stopped
  const sourceDoc = async (title, elements) => {
    const d = (await A('createDocument', { title })).data.doc;
    const r = await A('importCircuit', { doc: d, circuit: { elements } });
    if (!r.ok) out.notes['import_' + title] = (r.issues || []).map((i) => i.code + ': ' + i.message);
    return d;
  };
  await s.eval('window.__alerts = []; window.__savedAlert = window.alert; window.alert = (m) => { window.__alerts.push(String(m)); };');
  try {
    await resetApp(s);
    const exMark = s.exceptions.length;
    const dialogMark = s.dialogs.length;
    const keys0 = await storageKeys();

    // ---------------------------------------------------------------- the class-name list of the static scan equals the factory's
    {
      const src = fs.readFileSync(path.join(PROJECT, 'src/main/java/com/lushprojects/circuitjs1/client/CircuitElmCreator.java'), 'utf8');
      const ctor = src.slice(src.indexOf('public static CircuitElm constructElement('));
      const labels = [...ctor.matchAll(/case "([A-Za-z0-9]+)":/g)].map((m) => m[1]).sort();
      const listSrc = src.slice(src.indexOf('CLASS_NAMES = {'), src.indexOf('};', src.indexOf('CLASS_NAMES = {')));
      const list = [...listSrc.matchAll(/"([A-Za-z0-9]+)"/g)].map((m) => m[1]).sort();
      out.notes.classNames = { labels: labels.length, list: list.length };
      ck('classNames_inSync', labels.length > 100 && same(labels, list));
    }

    // ---------------------------------------------------------------- an RC block in its own document
    // in (west) -> R 1k -> out (east); C 1 uF from out to ground
    const rcElements = [
      label('LIN', 'in', 4, 2, 2, 2),
      { id: 'R1', type: 'Resistor', start: { x: 4, y: 2 }, end: { x: 8, y: 2 }, properties: { resistance: '1k' } },
      { id: 'C1', type: 'Capacitor', start: { x: 8, y: 2 }, end: { x: 8, y: 6 }, properties: { capacitance: '1 uF' } },
      { id: 'G1', type: 'Ground', start: { x: 8, y: 6 }, end: { x: 8, y: 7 } },
      label('LOUT', 'out', 8, 2, 10, 2),
    ];
    const S = await sourceDoc('RC block', rcElements);
    const T = (await A('createDocument', { title: 'Uses the block' })).data.doc;
    const sText0 = await text(S);
    const sTime0 = (await A('getDiagnostics', { doc: S })).data;
    const rc = await define(T, { kind: 'subcircuit', name: 'rc-sub', source: { doc: S } }, [
      { op: 'add', element: { id: 'X1', type: 'Subcircuit', start: { x: 10, y: 10 }, properties: { model_name: 'rc-sub' } } },
      { op: 'markOpen', posts: ['X1.pin2'] }]);
    const rcm = rc.ok ? rc.data.models[0] : null;
    const x1 = rc.ok ? rc.data.elements.find((e) => e.id === 'X1') : null;
    out.notes.rc = { ok: rc.ok, issues: (rc.issues || []).map((i) => i.code + ': ' + i.message), model: rcm, posts: x1 && x1.posts };
    ck('rc_defined', rc.ok && rcm && rcm.kind === 'subcircuit' && rcm.builtIn === false && !rcm.existing && rcm.showLabel === true
      && same(rcm.pins, [{ pin: 'pin1', label: 'in', side: 'W' }, { pin: 'pin2', label: 'out', side: 'E' }]) && same(rcm.usedBy, [{ doc: T, ids: ['X1'] }]));
    ck('rc_elementPins', same(posts(x1), ['pin1', 'pin2']) && x1.posts[1].open === true);
    ck('rc_sourceUnchanged', (await text(S)) === sText0 && same((await A('getDiagnostics', { doc: S })).data, sTime0));
    ck('rc_noStorage', (await storageKeys()) === keys0);
    const subType = await A('describeType', { type: 'Subcircuit' });
    const subKey = subType.ok ? subType.data.properties.find((p) => p.key === 'model_name') : null;
    ck('rc_choices', subKey && Array.isArray(subKey.choices) && subKey.choices.includes('rc-sub'));
    // identical redefinition from the unchanged (stopped) source: existing
    const rcAgain = await define(T, { kind: 'subcircuit', name: 'rc-sub', source: { doc: S } });
    ck('rc_identicalExisting', rcAgain.ok && rcAgain.data.models[0].existing === true);
    // showLabel false is another definition (the flags differ): name_taken; under a new name it is stored
    await rejects('rc_otherShowLabel', T, S, () => define(T, { kind: 'subcircuit', name: 'rc-sub', source: { doc: S }, showLabel: false }), 'name_taken');
    // batch rollback and rollback on a forced exception: the subcircuit entry is not kept
    await rejects('rc_batchRollback', T, S, () => define(T, { kind: 'subcircuit', name: 'sub-rb', source: { doc: S } },
      [{ op: 'add', element: { type: 'NoSuchType', start: { x: 0, y: 30 } } }]), 'unknown_type');
    {
      const c0 = await catalogue();
      await s.eval('CircuitJS1Agent.debugFailNextMutation()');
      const fx = await define(T, { kind: 'subcircuit', name: 'sub-rb', source: { doc: S } }, [
        { op: 'add', element: { id: 'X9', type: 'Subcircuit', start: { x: 30, y: 30 }, properties: { model_name: 'sub-rb' } } }]);
      await sleep(200);
      await s.call('closeDialogs');
      ck('rc_exceptionRollback', fx.ok === false && has(fx, 'internal_error') && c0 === await catalogue()
        && has(await A('listModels', { kind: 'subcircuit', name: 'sub-rb' }), 'unknown_model'));
    }
    const nl = await define(T, { kind: 'subcircuit', name: 'rc-nolabel', source: { doc: S }, showLabel: false });
    ck('rc_showLabelFalse', nl.ok && nl.data.models[0].showLabel === false);

    // the block behaves as the RC: 5 V step into pin1, pin2 compared with the same RC drawn flat
    if (x1) {
      const p1 = postAt(x1, 'pin1');
      await A('applyEdits', { doc: T, edits: [
        { op: 'add', element: { id: 'V1', type: 'VoltageSourceDC', start: { x: p1.x - 4, y: p1.y + 4 }, end: { x: p1.x - 4, y: p1.y }, properties: { max_voltage: '5 V' } } },
        { op: 'add', element: { id: 'W1', type: 'Wire', start: { x: p1.x - 4, y: p1.y }, end: p1 } },
        { op: 'add', element: { id: 'G2', type: 'Ground', start: { x: p1.x - 4, y: p1.y + 4 }, end: { x: p1.x - 4, y: p1.y + 5 } } }] });
      const F = await sourceDoc('Flat RC', [
        { id: 'V1', type: 'VoltageSourceDC', start: { x: 0, y: 4 }, end: { x: 0, y: 0 }, properties: { max_voltage: '5 V' } },
        { id: 'R1', type: 'Resistor', start: { x: 0, y: 0 }, end: { x: 4, y: 0 }, properties: { resistance: '1k' } },
        { id: 'C1', type: 'Capacitor', start: { x: 4, y: 0 }, end: { x: 4, y: 4 }, properties: { capacitance: '1 uF' } },
        { id: 'W1', type: 'Wire', start: { x: 4, y: 4 }, end: { x: 0, y: 4 } },
        { id: 'G1', type: 'Ground', start: { x: 0, y: 4 }, end: { x: 0, y: 5 } }]);
      const sub = await R({ doc: T, span: '1 ms', reset: true, maxPoints: 20, probes: [{ post: 'X1.pin2', name: 'out' }] });
      const flat = await R({ doc: F, span: '1 ms', reset: true, maxPoints: 20, probes: [{ post: 'C1.pin1', name: 'out' }] });
      const fs1 = sub.ok ? sub.data.probes[0].stats.final : codes(sub);
      const ff1 = flat.ok ? flat.data.probes[0].stats.final : codes(flat);
      out.notes.rcRun = { sub: fs1, flat: ff1 };
      // 1 tau: 5 (1 - 1/e) = 3.16 V
      ck('rc_behavesAsRc', typeof fs1 === 'number' && typeof ff1 === 'number' && Math.abs(fs1 - ff1) < 1e-3 * Math.max(1, Math.abs(ff1)) && Math.abs(fs1 - 3.16) < 0.1);
      await A('closeDocument', { doc: F, discardChanges: true });
    } else {
      ck('rc_behavesAsRc', false);
    }

    // ---------------------------------------------------------------- source errors: invalid_value naming source, each reason
    const srcErr = (src, name) => () => define(T, { kind: 'subcircuit', name: name || 'sub-err', source: { doc: src } });
    await rejects('err_unknownDocument', T, null, srcErr('d999'), 'unknown_document');
    await rejects('err_sourceIsTarget', S, null, () => define(S, { kind: 'subcircuit', name: 'sub-err', source: { doc: S } }), 'invalid_value', /'edits\[0\]\.model\.source'.*cannot be built from the document it is defined in/);
    const N0 = await sourceDoc('No labels', [
      { id: 'R1', type: 'Resistor', start: { x: 0, y: 0 }, end: { x: 4, y: 0 } },
      { id: 'W1', type: 'Wire', start: { x: 4, y: 0 }, end: { x: 4, y: 4 } },
      { id: 'G1', type: 'Ground', start: { x: 4, y: 4 }, end: { x: 4, y: 5 } },
      { id: 'G2', type: 'Ground', start: { x: 0, y: 0 }, end: { x: 0, y: 1 } }]);
    await rejects('err_noLabels', T, N0, srcErr(N0), 'invalid_value', /model\.source'.*device has no external inputs\/outputs/);
    const NG = await sourceDoc('Label on ground', [label('LG', 'g', 0, 0, -2, 0),
      { id: 'R1', type: 'Resistor', start: { x: 0, y: 0 }, end: { x: 4, y: 0 } },
      { id: 'G1', type: 'Ground', start: { x: 0, y: 0 }, end: { x: 0, y: 1 } },
      { id: 'G2', type: 'Ground', start: { x: 4, y: 0 }, end: { x: 4, y: 1 } }]);
    await rejects('err_labelOnGround', T, NG, srcErr(NG), 'invalid_value', /model\.source'.*node g can't be connected to ground/);
    const NU = await sourceDoc('Unused label', [label('LA', 'a', 0, 0, -2, 0), label('LU', 'u', 0, 8, -2, 8),
      { id: 'R1', type: 'Resistor', start: { x: 0, y: 0 }, end: { x: 4, y: 0 } },
      { id: 'G1', type: 'Ground', start: { x: 4, y: 0 }, end: { x: 4, y: 1 } }]);
    await rejects('err_unusedLabel', T, NU, srcErr(NU), 'invalid_value', /model\.source'.*node u is not used/);
    const NC = await sourceDoc('Unconnected', [label('LA', 'a', 0, 0, -2, 0),
      { id: 'R1', type: 'Resistor', start: { x: 0, y: 0 }, end: { x: 4, y: 0 } },
      { id: 'G1', type: 'Ground', start: { x: 4, y: 0 }, end: { x: 4, y: 1 } },
      { id: 'R2', type: 'Resistor', start: { x: 10, y: 0 }, end: { x: 14, y: 0 } }]);
    await rejects('err_unconnectedNodes', T, NC, srcErr(NC), 'invalid_value', /model\.source'.*some nodes are unconnected/);
    const N2 = await sourceDoc('Two labels', [label('LA', 'a', 0, 0, -2, 0), label('LB', 'b', 0, 0, 0, -2),
      { id: 'R1', type: 'Resistor', start: { x: 0, y: 0 }, end: { x: 4, y: 0 } },
      { id: 'G1', type: 'Ground', start: { x: 4, y: 0 }, end: { x: 4, y: 1 } }]);
    await rejects('err_twoLabelsOneNode', T, N2, srcErr(N2), 'invalid_value', /model\.source'.*labels (a and b|b and a) are on one node/);
    // a source busy with an agent run: busy (the source is checked when the run ends)
    {
      await s.call('agentStart', 'subBusy', 'run', { doc: S, span: 1000, budgetMs: 1500 });
      const c0 = await catalogue(); const a0 = (await alerts()).length;
      const r = await srcErr(S, 'sub-busy')();
      const c1 = await catalogue();
      await waitFor(async () => (await s.call('agentStarted', 'subBusy')).calls === 1, 15000, 'busy run end');
      rejections.err_sourceBusy = { ok: r.ok, issues: (r.issues || []).map((i) => i.code + ': ' + i.message) };
      ck('err_sourceBusy', r.ok === false && has(r, 'busy', /model\.source\.doc'|busy/) && c0 === c1 && (await alerts()).length === a0);
    }
    // the source spec itself: source missing, source without doc, a doc that is not a string, showLabel not a boolean, an extra field
    await rejects('spec_sourceMissing', T, null, () => define(T, { kind: 'subcircuit', name: 'sub-x' }), 'invalid_value', /model\.source'/);
    await rejects('spec_sourceNoDoc', T, null, () => define(T, { kind: 'subcircuit', name: 'sub-x', source: {} }), 'invalid_value', /model\.source\.doc'/);
    await rejects('spec_sourceExtra', T, null, () => define(T, { kind: 'subcircuit', name: 'sub-x', source: { doc: S, path: 'x' } }), 'invalid_value', /model\.source\.path'/);
    await rejects('spec_showLabelType', T, null, () => define(T, { kind: 'subcircuit', name: 'sub-x', source: { doc: S }, showLabel: 'yes' }), 'invalid_value', /model\.showLabel'/);
    await rejects('spec_parametersField', T, null, () => define(T, { kind: 'subcircuit', name: 'sub-x', source: { doc: S }, parameters: {} }), 'invalid_value', /model\.parameters'/);

    // ---------------------------------------------------------------- a block using an agent-defined diode model (closure)
    // the diode model is defined in the block's own document; the block (anode a west, cathode k east)
    const B = (await A('createDocument', { title: 'Diode block' })).data.doc;
    const db = await define(B, { kind: 'diode', name: 'sub-diode', parameters: { forward_voltage: '0.7 V', forward_current: '10 mA' } }, [
      { op: 'add', element: label('LA', 'a', 2, 2, 0, 2) },
      { op: 'add', element: { id: 'D1', type: 'Diode', start: { x: 2, y: 2 }, end: { x: 6, y: 2 }, properties: { model: 'sub-diode' } } },
      { op: 'add', element: label('LK', 'k', 6, 2, 8, 2) }]);
    out.notes.diodeBlock = { ok: db.ok, issues: (db.issues || []).map((i) => i.code + ': ' + i.message) };
    const U = (await A('createDocument', { title: 'Uses the diode block' })).data.doc;
    const dm = await define(U, { kind: 'subcircuit', name: 'sub-dmod', source: { doc: B } }, [
      { op: 'add', element: { id: 'X2', type: 'Subcircuit', start: { x: 10, y: 10 }, properties: { model_name: 'sub-dmod' } } }]);
    out.notes.dmod = { ok: dm.ok, issues: (dm.issues || []).map((i) => i.code + ': ' + i.message), model: dm.ok && dm.data.models[0] };
    ck('dmod_defined', db.ok && dm.ok && same(dm.data.models[0].pins.map((p) => p.label), ['a', 'k']));
    const gc = await A('getCircuit', { doc: U, detail: 'full' });
    const gm = gc.data.models || [];
    out.notes.closure = gm;
    ck('closure_dependenciesFirst', gc.ok && same(gm.map((m) => m.kind + ':' + m.name), ['diode:sub-diode', 'subcircuit:sub-dmod'])
      && gm[0].parameters && typeof gm[1].modelText === 'string' && gm[1].modelText.startsWith('. sub-dmod '));
    const page2 = await A('getCircuit', { doc: U, offset: 1 });
    ck('closure_offset0Only', page2.ok && page2.data.models === undefined);
    const dRec = await record('diode', 'sub-diode');
    out.notes.diodeUsedBy = dRec && dRec.usedBy;
    ck('closure_usedByThroughSubcircuit', dRec && dRec.usedBy.some((u) => u.doc === U && same(u.ids, ['X2'])) && dRec.usedBy.some((u) => u.doc === B && same(u.ids, ['D1'])));
    // the text export lists the diode model line before the subcircuit line
    const ut = (await text(U)).split('\n');
    const iD = ut.findIndex((l) => l.startsWith('34 sub-diode ')), iS = ut.findIndex((l) => l.startsWith('. sub-dmod '));
    ck('closure_textOrder', iD >= 0 && iS > iD);
    // same-session re-import of the getCircuit form: every entry identical, elements equal
    const RT = (await A('createDocument', { title: 'Sub roundtrip' })).data.doc;
    const c0 = await catalogue();
    const rt = await A('importCircuit', { doc: RT, circuit: { elements: gc.data.elements, simulation: gc.data.simulation, scopes: gc.data.scopes, models: gm } });
    const c1 = await catalogue();
    const gc2 = await A('getCircuit', { doc: RT, detail: 'full' });
    const recs = (g) => JSON.stringify(g.data.elements);
    out.notes.roundtrip = { ok: rt.ok, issues: (rt.issues || []).map((i) => i.code + ': ' + i.message), same: c0 === c1 };
    ck('roundtrip_sameSession', rt.ok && c0 === c1 && recs(gc) === recs(gc2) && same(gc2.data.models, gm));
    // nested: a block containing the diode block lists diode, inner block, outer block
    const O = await sourceDoc('Outer block', []);
    const ob = await A('applyEdits', { doc: O, edits: [{ op: 'add', element: { id: 'X3', type: 'Subcircuit', start: { x: 4, y: 4 }, properties: { model_name: 'sub-dmod' } } }] });
    const x3 = ob.ok ? ob.data.elements[0] : null;
    if (x3) {
      const pa = postAt(x3, 'pin1'), pk = postAt(x3, 'pin2');
      await A('applyEdits', { doc: O, edits: [{ op: 'add', element: label('OA', 'p', pa.x, pa.y, pa.x - 2, pa.y) }, { op: 'add', element: label('OK', 'q', pk.x, pk.y, pk.x + 2, pk.y) }] });
    }
    const outer = await define(U, { kind: 'subcircuit', name: 'sub-outer', source: { doc: O } }, [
      { op: 'add', element: { id: 'X4', type: 'Subcircuit', start: { x: 10, y: 20 }, properties: { model_name: 'sub-outer' } } }]);
    const gcn = await A('getCircuit', { doc: U });
    out.notes.nested = { ok: outer.ok, issues: (outer.issues || []).map((i) => i.code + ': ' + i.message), models: (gcn.data.models || []).map((m) => m.name) };
    ck('closure_nested', outer.ok && same((gcn.data.models || []).map((m) => m.name), ['sub-diode', 'sub-dmod', 'sub-outer']));
    const dRec2 = await record('diode', 'sub-diode');
    ck('closure_usedByNested', dRec2 && dRec2.usedBy.some((u) => u.doc === U && same(u.ids, ['X2', 'X4'])));

    // fresh session (side page): the getCircuit form restores both models
    // a fresh session: withSidePage serves the app from another origin (no session restore of this page's tabs)
    await withSidePage(s, async (s2) => {
      const A2 = async (op, args) => JSON.parse(await s2.eval(`CircuitJS1Agent.call(${JSON.stringify(op)}, ${JSON.stringify(JSON.stringify(args))})`));
      await waitFor(async () => { try { return (await A2('listDocuments', {})).ok; } catch { return false; } }, LOAD_TIMEOUT_MS, 'side page');
      const before = await A2('listModels', { kind: 'subcircuit', name: 'sub-dmod' });
      const fr = await A2('importCircuit', { circuit: { elements: gc.data.elements, simulation: gc.data.simulation, models: gm } });
      const back = await A2('getCircuit', { detail: 'full' });
      const sr = await A2('listModels', { kind: 'subcircuit', name: 'sub-dmod' });
      out.notes.fresh = { before: codes(before), import: fr.ok ? 'ok' : (fr.issues || []).map((i) => i.code + ': ' + i.message), backModels: back.data && back.data.models };
      ck('roundtrip_freshSession', has(before, 'unknown_model') && fr.ok && same(back.data.models, gm) && sr.ok
        && same(strip(sr.data.models[0]), strip(await record('subcircuit', 'sub-dmod'))));
    });

    // ---------------------------------------------------------------- inner references of a ModelText
    // a block with a CustomLogic of a defined logic model; its ModelText with the name replaced
    const LB = (await A('createDocument', { title: 'Logic block' })).data.doc;
    const lb = await define(LB, { kind: 'logic', name: 'sub-lg', inputs: ['A'], outputs: ['Y'], rules: ['1=0', '0=1'] }, [
      { op: 'add', element: { id: 'CL1', type: 'CustomLogic', start: { x: 4, y: 4 }, properties: { model_name: 'sub-lg' } } }]);
    const cl1 = lb.ok ? lb.data.elements.find((e) => e.id === 'CL1') : null;
    if (cl1) {
      const pa = postAt(cl1, 'A'), py = postAt(cl1, 'Y');
      await A('applyEdits', { doc: LB, edits: [{ op: 'add', element: label('LA', 'a', pa.x, pa.y, pa.x - 2, pa.y) }, { op: 'add', element: label('LY', 'y', py.x, py.y, py.x + 2, py.y) }] });
    }
    const lgs = await define(T, { kind: 'subcircuit', name: 'sub-lgblock', source: { doc: LB } });
    out.notes.logicBlock = { ok: lgs.ok, issues: (lgs.issues || []).map((i) => i.code + ': ' + i.message) };
    const I = (await A('createDocument', { title: 'Inner refs' })).data.doc;
    const tg = await A('importCircuit', { doc: I, circuit: { elements: [{ id: 'X5', type: 'Subcircuit', start: { x: 4, y: 4 }, properties: { model_name: 'sub-lgblock' } }] } });
    const lmt = tg.ok ? ((await A('getCircuit', { doc: I })).data.models || []).find((m) => m.name === 'sub-lgblock') : null;
    out.notes.logicBlockText = lmt;
    ck('inner_logicBlock', lgs.ok && lmt && /CustomLogicElm/.test(lmt.modelText) && lmt.modelText.includes('sub-lg'));
    const renamed = (from, to, extra) => ({ kind: 'subcircuit', name: 'sub-bad', modelText: lmt.modelText.replace('. sub-lgblock ', '. sub-bad ').split(from).join(to) + (extra || '') });
    if (lmt) {
      await rejects('inner_unknownModel', I, null, () => A('importCircuit', { doc: I, circuit: { elements: [], models: [renamed('sub-lg', 'nope')] } }), 'invalid_value', /models\[0\]\.modelText'.*inner model nope unknown/);
      ck('inner_unknownNotRegistered', has(await A('listModels', { kind: 'logic', name: 'nope' }), 'unknown_model') && has(await A('listModels', { kind: 'subcircuit', name: 'sub-bad' }), 'unknown_model'));
      await rejects('inner_unknownClass', I, null, () => A('importCircuit', { doc: I, circuit: { elements: [], models: [renamed('CustomLogicElm', 'NopeLogicElm')] } }), 'invalid_value', /modelText'.*unknown element class NopeLogicElm/);
      // a field that does not load (the pin node is in no node-list line): the trial build fails -> invalid_value
      const tok = renamed('\u0000', '').modelText.split(' ');
      tok[7] = '99';
      await rejects('inner_doesNotLoad', I, null, () => A('importCircuit', { doc: I, circuit: { elements: [], models: [{ kind: 'subcircuit', name: 'sub-bad', modelText: tok.join(' ') }] } }), 'invalid_value', /modelText'.*does not load/);
      // the model the definition depends on, defined earlier in the same list: accepted (dependencies first)
      const dep = await A('importCircuit', { doc: I, circuit: { elements: [], models: [
        { kind: 'logic', name: 'sub-lg2', inputs: ['A'], outputs: ['Y'], rules: ['1=0', '0=1'] }, renamed('sub-lg', 'sub-lg2')] } });
      out.notes.innerDep = { ok: dep.ok, issues: (dep.issues || []).map((i) => i.code + ': ' + i.message) };
      ck('inner_dependencyFirst', dep.ok && (await record('subcircuit', 'sub-bad')) !== null);
      // legacy text content with a model line naming an unknown inner model: invalid_value, nothing registered
      const opts = '$ 1 0.000005 10 50 5 50 5e-11';
      const badLine = lmt.modelText.replace('. sub-lgblock ', '. sub-bad2 ').split('sub-lg').join('nope2');
      await rejects('inner_textLine', I, null, () => A('importCircuit', { doc: I, circuit: opts + '\n' + badLine + '\n' }), 'invalid_value', /sub-bad2.*inner model nope2 unknown/);
      ck('inner_textNotRegistered', has(await A('listModels', { kind: 'logic', name: 'nope2' }), 'unknown_model'));
    }
    // a subcircuit without a pin (ModelText and a legacy text `.` line): invalid_value, nothing registered
    {
      const rcText = ((await A('getCircuit', { doc: T })).data.models || []).find((m) => m.name === 'rc-sub');
      const t = rcText ? rcText.modelText.split(' ') : null;
      const nopin = t ? ['.', 'sub-nopin', t[2], t[3], t[4], '0'].concat(t.slice(6 + 4 * Number(t[5]))).join(' ') : null;
      out.notes.noPin = nopin;
      await rejects('pins_modelTextNone', I, null, () => A('importCircuit', { doc: I, circuit: { elements: [], models: [{ kind: 'subcircuit', name: 'sub-nopin', modelText: nopin }] } }),
        'invalid_value', /modelText'.*a subcircuit needs at least one pin/);
      await rejects('pins_textLineNone', I, null, () => A('importCircuit', { doc: I, circuit: '$ 1 0.000005 10 50 5 50 5e-11\n' + nopin + '\n' }),
        'invalid_value', /sub-nopin.*a subcircuit needs at least one pin/);
      ck('pins_noneNotRegistered', nopin && has(await A('listModels', { kind: 'subcircuit', name: 'sub-nopin' }), 'unknown_model'));
    }
    // recursion (guard): a ModelText whose Subcircuit names the model itself
    {
      const R3 = await sourceDoc('Recursion block', [{ id: 'X6', type: 'Subcircuit', start: { x: 4, y: 4 }, properties: { model_name: 'rc-sub' } }]);
      const x6 = (await A('getCircuit', { doc: R3 })).data.elements[0];
      const p1 = postAt(x6, 'pin1'), p2 = postAt(x6, 'pin2');
      await A('applyEdits', { doc: R3, edits: [{ op: 'add', element: label('RA', 'a', p1.x, p1.y, p1.x - 2, p1.y) }, { op: 'add', element: label('RB', 'b', p2.x, p2.y, p2.x + 2, p2.y) }] });
      const ro = await define(T, { kind: 'subcircuit', name: 'sub-outer-rc', source: { doc: R3 } });
      await A('applyEdits', { doc: T, edits: [{ op: 'add', element: { id: 'X7', type: 'Subcircuit', start: { x: 40, y: 40 }, properties: { model_name: 'sub-outer-rc' } } }] });
      const rm = ((await A('getCircuit', { doc: T })).data.models || []).find((m) => m.name === 'sub-outer-rc');
      out.notes.recursion = { ok: ro.ok, model: rm && rm.modelText.slice(0, 120) };
      if (rm) {
        const rec = rm.modelText.split('rc-sub').join('sub-self').replace('. sub-outer-rc ', '. sub-self ');
        await rejects('inner_recursion', I, null, () => A('importCircuit', { doc: I, circuit: { elements: [], models: [{ kind: 'subcircuit', name: 'sub-self', modelText: rec }] } }), 'invalid_value', /modelText'.*recursion/);
      } else {
        ck('inner_recursion', false);
      }
      await A('closeDocument', { doc: R3, discardChanges: true });
    }

    // ---------------------------------------------------------------- R1: the source is the active, free-running document
    {
      const V = (await A('createDocument', { title: 'Active block', activate: true })).data.doc;
      const vi = await A('importCircuit', { doc: V, circuit: { elements: [
        label('LIN', 'in', 4, 2, 2, 2),
        { id: 'R1', type: 'Resistor', start: { x: 4, y: 2 }, end: { x: 8, y: 2 }, properties: { resistance: '1k' } },
        { id: 'R2', type: 'Resistor', start: { x: 8, y: 2 }, end: { x: 8, y: 6 }, properties: { resistance: '1k' } },
        { id: 'G1', type: 'Ground', start: { x: 8, y: 6 }, end: { x: 8, y: 7 } },
        label('LOUT', 'out', 8, 2, 10, 2),
        // a charging RC that keeps the state moving
        { id: 'V1', type: 'VoltageSourceDC', start: { x: 14, y: 6 }, end: { x: 14, y: 2 }, properties: { max_voltage: '5 V' } },
        { id: 'R3', type: 'Resistor', start: { x: 14, y: 2 }, end: { x: 18, y: 2 }, properties: { resistance: '10k' } },
        { id: 'C3', type: 'Capacitor', start: { x: 18, y: 2 }, end: { x: 18, y: 6 }, properties: { capacitance: '100 uF' } },
        { id: 'W1', type: 'Wire', start: { x: 18, y: 6 }, end: { x: 14, y: 6 } },
        { id: 'G2', type: 'Ground', start: { x: 14, y: 6 }, end: { x: 14, y: 7 } }] } });
      await A('simControl', { doc: V, action: 'run' });
      await sleep(600);
      await s.eval("CircuitJS1.selectElementById('R1', false); CircuitJS1.selectElementById('C3', true);");
      // selectElementById does not refresh the Edit menu items; a background export does (DocumentScope exit)
      await A('exportCircuit', { doc: T, format: 'text' });
      // one synchronous page task: state, defineModel from the running active document, state again
      const sync = JSON.parse(await s.eval(`(() => {
        const st = () => ({ info: CircuitJS1.getSimInfo(), text: JSON.parse(CircuitJS1Agent.call('exportCircuit', JSON.stringify({ doc: ${JSON.stringify(V)}, format: 'text' }))).data.content,
          diag: JSON.parse(CircuitJS1Agent.call('getDiagnostics', JSON.stringify({ doc: ${JSON.stringify(V)} }))).data,
          sel: CircuitJS1.getElements().filter((e) => e.isSelected()).map((e) => e.getId()).sort(), r1: window.__H.r1Sample() });
        const a = st();
        const r = JSON.parse(CircuitJS1Agent.call('applyEdits', JSON.stringify({ doc: ${JSON.stringify(T)}, edits: [{ op: 'defineModel', model: { kind: 'subcircuit', name: 'sub-active', source: { doc: ${JSON.stringify(V)} } } }] })));
        const b = st();
        return JSON.stringify({ a, b, ok: r.ok, issues: r.issues, model: r.ok ? r.data.models[0] : null });
      })()`));
      out.notes.r1 = { ok: sync.ok, issues: sync.issues, time: [sync.a.info.time, sync.b.info.time], timeStep: [sync.a.info.timeStep, sync.b.info.timeStep], sel: [sync.a.sel, sync.b.sel],
        differs: ['info', 'text', 'diag', 'sel', 'r1'].filter((k) => !same(sync.a[k], sync.b[k])) };
      if (out.notes.r1.differs.length) out.notes.r1.detail = { a: sync.a, b: sync.b };
      ck('r1_built', vi.ok && sync.ok && same(sync.model.pins.map((p) => p.label), ['in', 'out']));
      ck('r1_wholeCircuitDespiteSelection', sync.ok && same(sync.a.sel, ['C3', 'R1']) && (await A('getCircuit', { doc: T })).ok);
      ck('r1_syncUnchanged', same(sync.a.info, sync.b.info) && sync.a.text === sync.b.text && same(sync.a.diag, sync.b.diag) && same(sync.a.sel, sync.b.sel) && same(sync.a.r1, sync.b.r1));
      // the document keeps free-running correctly afterwards: time advances, no stop, C3 still charging and the divider holds 0 V
      const t1 = await s.call('simTime');
      await sleep(600);
      // one page task: run state, time and the readings at that time (C3 charges through 10k into 100 uF: tau = 1 s)
      const after = JSON.parse(await s.eval(`JSON.stringify({ info: CircuitJS1.getSimInfo(), read: JSON.parse(CircuitJS1Agent.call('read', JSON.stringify({ doc: ${JSON.stringify(V)}, targets: [{ post: 'C3.pin1' }, { post: 'R2.pin1' }] }))) })`));
      const vc = after.read.ok ? after.read.data.values[0].value : null;
      const expect = 5 * (1 - Math.exp(-after.info.time / 1));
      out.notes.r1After = { t1, info: after.info, vc, expect, divider: after.read.ok ? after.read.data.values[1].value : codes(after.read) };
      ck('r1_keepsRunning', after.info.running && after.info.time > t1 && !after.info.stopMessage && after.read.ok
        && Math.abs(vc - expect) <= 0.02 * expect && Math.abs(after.read.data.values[1].value) < 1e-9);
      // the subcircuit line holds the elements' state: after more simulated time the same source is another definition
      const again = await define(T, { kind: 'subcircuit', name: 'sub-active', source: { doc: V } });
      out.notes.stateIdentity = { ok: again.ok, issues: codes(again) };
      ck('identity_includesState', again.ok === false && has(again, 'name_taken'));
      await A('simControl', { doc: V, action: 'stop' });
      await A('closeDocument', { doc: V, discardChanges: true });
    }

    // ---------------------------------------------------------------- R1: a background source while another tab free-runs
    {
      const W = (await A('createDocument', { title: 'Visible', activate: true })).data.doc;
      await A('importCircuit', { doc: W, circuit: { elements: [
        { id: 'V1', type: 'VoltageSourceDC', start: { x: 0, y: 4 }, end: { x: 0, y: 0 }, properties: { max_voltage: '5 V' } },
        { id: 'R1', type: 'Resistor', start: { x: 0, y: 0 }, end: { x: 4, y: 0 }, properties: { resistance: '1k' } },
        { id: 'C1', type: 'Capacitor', start: { x: 4, y: 0 }, end: { x: 4, y: 4 }, properties: { capacitance: '1 mF' } },
        { id: 'W1', type: 'Wire', start: { x: 4, y: 4 }, end: { x: 0, y: 4 } },
        { id: 'G1', type: 'Ground', start: { x: 0, y: 4 }, end: { x: 0, y: 5 } }] } });
      await A('simControl', { doc: W, action: 'run' });
      await sleep(400);
      const r0 = await s.call('r1Sample');
      const t0 = await s.call('simTime');
      const bg = await define(T, { kind: 'subcircuit', name: 'sub-bg', source: { doc: S } });
      const r1 = await s.call('r1Sample');
      await sleep(300);
      const t1 = await s.call('simTime');
      out.notes.r1Background = { ok: bg.ok, t0, t1 };
      ck('r1_backgroundSource', bg.ok && same(r0, r1) && t1 > t0);
      await A('simControl', { doc: W, action: 'stop' });
      await A('closeDocument', { doc: W, discardChanges: true });
    }
    // one page task: state of `doc` (simulated time and step, text, diagnostics, the R1 sample, given reads), defineModel into T, state again
    const syncBuild = async (doc, name, reads) => JSON.parse(await s.eval(`(() => {
      const doc = ${JSON.stringify(doc)};
      const st = () => ({ info: CircuitJS1.getSimInfo(),
        text: JSON.parse(CircuitJS1Agent.call('exportCircuit', JSON.stringify({ doc, format: 'text' }))).data.content,
        diag: JSON.parse(CircuitJS1Agent.call('getDiagnostics', JSON.stringify({ doc }))).data,
        reads: ${JSON.stringify(reads || [])}.length ? JSON.parse(CircuitJS1Agent.call('read', JSON.stringify({ doc, targets: ${JSON.stringify(reads || [])} }))) : null,
        r1: window.__H.r1Sample() });
      const a = st();
      const r = JSON.parse(CircuitJS1Agent.call('applyEdits', JSON.stringify({ doc: ${JSON.stringify(T)}, edits: [{ op: 'defineModel', model: { kind: 'subcircuit', name: ${JSON.stringify(name)}, source: { doc } } }] })));
      const b = st();
      return JSON.stringify({ a, b, ok: r.ok, issues: (r.issues || []).map((i) => i.code + ': ' + i.message) });
    })()`));
    const differs = (x) => ['info', 'text', 'diag', 'reads', 'r1'].filter((k) => !same(x.a[k], x.b[k]));

    // ---------------------------------------------------------------- R1: an active, free-running source without Ground
    // (the normal allocation grounds V1's first terminal, the subcircuit allocation does not)
    {
      const V = (await A('createDocument', { title: 'No ground', activate: true })).data.doc;
      const vi = await A('importCircuit', { doc: V, circuit: { elements: [
        { id: 'V1', type: 'VoltageSourceDC', start: { x: 2, y: 6 }, end: { x: 2, y: 2 }, properties: { max_voltage: '5 V' } },
        { id: 'R1', type: 'Resistor', start: { x: 2, y: 2 }, end: { x: 6, y: 2 }, properties: { resistance: '1k' } },
        { id: 'R2', type: 'Resistor', start: { x: 6, y: 2 }, end: { x: 6, y: 6 }, properties: { resistance: '1k' } },
        { id: 'W1', type: 'Wire', start: { x: 6, y: 6 }, end: { x: 2, y: 6 } },
        label('LIN', 'in', 2, 2, 0, 2), label('LOUT', 'out', 6, 2, 8, 2)] } });
      await A('simControl', { doc: V, action: 'run' });
      await sleep(400);
      const sb = await syncBuild(V, 'sub-noground', [{ post: 'R2.pin1' }, { element: 'R1', quantity: 'current' }]);
      await sleep(600);
      const after = JSON.parse(await s.eval(`JSON.stringify({ info: CircuitJS1.getSimInfo(), read: JSON.parse(CircuitJS1Agent.call('read', JSON.stringify({ doc: ${JSON.stringify(V)}, targets: [{ post: 'R2.pin1' }, { element: 'R1', quantity: 'current' }] }))) })`));
      const vals = after.read.ok ? after.read.data.values.map((v) => v.value) : null;
      out.notes.r1NoGround = { import: vi.ok, build: sb.ok, issues: sb.issues, differs: differs(sb), time: [sb.a.info.time, sb.b.info.time, after.info.time], vals };
      ck('r1_noGroundSyncUnchanged', vi.ok && differs(sb).length === 0);
      ck('r1_noGroundKeepsRunning', after.info.running && after.info.time > sb.b.info.time && !after.info.stopMessage && vals
        && Math.abs(vals[0] - 2.5) < 1e-6 && Math.abs(Math.abs(vals[1]) - 2.5e-3) < 1e-8);
      await A('simControl', { doc: V, action: 'stop' });
      await A('closeDocument', { doc: V, discardChanges: true });
    }

    // ---------------------------------------------------------------- an inductor whose current path exists only through the
    // normal allocation's ground (V1's first terminal -> a logic input's ground connection): the build does not reset it
    {
      const L = await sourceDoc('Inductor path', [
        { id: 'V1', type: 'VoltageSourceDC', start: { x: 2, y: 6 }, end: { x: 2, y: 2 }, properties: { max_voltage: '5 V' } },
        { id: 'L1', type: 'Inductor', start: { x: 2, y: 2 }, end: { x: 6, y: 2 }, properties: { inductance: '1 H' } },
        { id: 'LI1', type: 'LogicInput', start: { x: 6, y: 2 }, end: { x: 9, y: 2 }, properties: { position: 0 } },
        label('LIN', 'in', 2, 2, 0, 2), label('LOUT', 'out', 6, 2, 6, 0)]);
      const lr = await R({ doc: L, span: '2 ms', reset: true, maxPoints: 10, probes: [{ element: 'L1', quantity: 'current', name: 'i' }] });
      const sb = await syncBuild(L, 'sub-inductor', [{ element: 'L1', quantity: 'current' }]);
      const i0 = sb.a.reads && sb.a.reads.ok ? sb.a.reads.data.values[0].value : null;
      const i1 = sb.b.reads && sb.b.reads.ok ? sb.b.reads.data.values[0].value : null;
      out.notes.inductor = { run: lr.ok ? lr.data.probes[0].stats.final : codes(lr), build: sb.ok, issues: sb.issues, i0, i1, differs: differs(sb) };
      ck('inductor_notReset', lr.ok && typeof i0 === 'number' && Math.abs(i0) > 1e-6 && i0 === i1 && differs(sb).length === 0);
      await A('closeDocument', { doc: L, discardChanges: true });
    }

    // ---------------------------------------------------------------- a wire loop (warned under recovery): the solver events stay those of the last analysis
    {
      const WL = await sourceDoc('Wire loop', [label('LA', 'a', 2, 2, 0, 2),
        { id: 'W1', type: 'Wire', start: { x: 2, y: 2 }, end: { x: 6, y: 2 } },
        { id: 'W2', type: 'Wire', start: { x: 6, y: 2 }, end: { x: 6, y: 6 } },
        { id: 'W3', type: 'Wire', start: { x: 6, y: 6 }, end: { x: 2, y: 6 } },
        { id: 'W4', type: 'Wire', start: { x: 2, y: 6 }, end: { x: 2, y: 2 } },
        { id: 'R1', type: 'Resistor', start: { x: 6, y: 6 }, end: { x: 10, y: 6 }, properties: { resistance: '1k' } },
        { id: 'G1', type: 'Ground', start: { x: 10, y: 6 }, end: { x: 10, y: 7 } }]);
      const d0 = (await A('getDiagnostics', { doc: WL })).data;
      const sb = await syncBuild(WL, 'sub-wireloop');
      out.notes.wireLoop = { build: sb.ok, issues: sb.issues, differs: differs(sb), events: d0 && d0.events };
      ck('wireLoop_eventsUnchanged', JSON.stringify(d0.events || []).includes('wire loop') && differs(sb).length === 0);
      await A('closeDocument', { doc: WL, discardChanges: true });
    }

    // ---------------------------------------------------------------- the trial build leaves the session-wide MOSFET display flags alone
    {
      const digital = async (id) => {
        await A('applyEdits', { doc: T, edits: [{ op: 'add', element: { id, type: 'NMOS', start: { x: 60, y: 10 + 6 * id.length } } }] });
        const r = (await A('getCircuit', { doc: T, detail: 'full', ids: [id] })).data.elements[0];
        return !!r.properties.digital;
      };
      const g0 = await digital('MA');
      // a subcircuit of one MOSFET whose dump carries the opposite symbol flag (4 = digital, 32 = body diode)
      const mos = (name) => ({ kind: 'subcircuit', name, modelText: `. ${name} 0 2 2 3 g 1 0 0 d 2 0 2 s 3 1 3 NMosfetElm\\s1\\s2\\s3 ${g0 ? 32 : 36}\\\\s1.5\\\\s0.02` });
      const ok1 = await A('importCircuit', { doc: I, circuit: { elements: [], models: [mos('sub-mos')] } });
      const g1 = await digital('MBB');
      const rj = await A('importCircuit', { doc: I, circuit: { elements: [], models: [mos('sub-mos2'), { kind: 'diode', name: '1N4148', parameters: { emission_coefficient: 3 } }] } });
      const g2 = await digital('MCCC');
      out.notes.mosfet = { g0, g1, g2, ok1: ok1.ok ? 'ok' : (ok1.issues || []).map((i) => i.code + ': ' + i.message), rejected: codes(rj) };
      ck('trial_mosfetFlagsKept', ok1.ok && rj.ok === false && has(rj, 'name_taken') && g1 === g0 && g2 === g0);
    }

    // ---------------------------------------------------------------- same-session re-import of a document using diode, logic and subcircuit models
    {
      const M = await sourceDoc('Mixed models', [
        { id: 'X1', type: 'Subcircuit', start: { x: 4, y: 4 }, properties: { model_name: 'sub-dmod' } },
        { id: 'X2', type: 'Subcircuit', start: { x: 4, y: 14 }, properties: { model_name: 'sub-lgblock' } },
        { id: 'CL1', type: 'CustomLogic', start: { x: 20, y: 4 }, properties: { model_name: 'sub-lg' } }]);
      const g = await A('getCircuit', { doc: M, detail: 'full' });
      const ms = g.data.models || [];
      const M2 = (await A('createDocument', { title: 'Mixed models again' })).data.doc;
      const c0 = await catalogue();
      const ri = await A('importCircuit', { doc: M2, circuit: { elements: g.data.elements, simulation: g.data.simulation, models: ms } });
      const c1 = await catalogue();
      const g2 = await A('getCircuit', { doc: M2, detail: 'full' });
      out.notes.mixed = { models: ms.map((m) => m.kind + ':' + m.name), import: ri.ok ? 'ok' : (ri.issues || []).map((i) => i.code + ': ' + i.message) };
      ck('roundtrip_sameSessionLogic', ri.ok && c0 === c1 && same(ms.map((m) => m.kind + ':' + m.name), ['logic:sub-lg', 'diode:sub-diode', 'subcircuit:sub-dmod', 'subcircuit:sub-lgblock'])
        && JSON.stringify(g.data.elements) === JSON.stringify(g2.data.elements) && same(g2.data.models, ms));
      await A('closeDocument', { doc: M, discardChanges: true });
      await A('closeDocument', { doc: M2, discardChanges: true });
    }
    ck('noStorage_end', (await storageKeys()) === keys0);

    // ---------------------------------------------------------------- the editor's Create Subcircuit (File menu): alerts unchanged, still creates a model
    {
      const E = (await A('createDocument', { title: 'Editor block', activate: true })).data.doc;
      await A('importCircuit', { doc: E, circuit: { elements: [label('LG', 'g', 4, 4, 2, 4),
        { id: 'R1', type: 'Resistor', start: { x: 4, y: 4 }, end: { x: 8, y: 4 } },
        { id: 'G1', type: 'Ground', start: { x: 4, y: 4 }, end: { x: 4, y: 5 } },
        { id: 'G2', type: 'Ground', start: { x: 8, y: 4 }, end: { x: 8, y: 5 } }] } });
      await sleep(300);
      await s.call('focus'); await s.key('Escape');
      const b0 = (await alerts()).length;
      const dlg0 = await s.call('dialogShowing');
      const m1 = await s.call('clickMenuPath', [menuTexts('File'), menuTexts('Create Subcircuit...')]);
      await sleep(400);
      const groundAlerts = (await alerts()).slice(b0);
      const dlg1 = await s.call('dialogShowing');
      out.notes.editorGround = { menu: m1, alerts: groundAlerts, dialog: dlg1 };
      ck('editor_labelOnGroundAlerts', m1 === 2 && groundAlerts.length === 1 && groundAlerts[0] === 'Node "g" can\'t be connected to ground' && same(dlg0, dlg1));
      await s.call('closeDialogs'); await s.key('Escape');
      await A('importCircuit', { doc: E, circuit: { elements: rcElements } });
      await sleep(300);
      await s.call('focus'); await s.key('Escape');
      const b1 = (await alerts()).length;
      const m2 = await s.call('clickMenuPath', [menuTexts('File'), menuTexts('Create Subcircuit...')]);
      await sleep(500);
      const okTexts = menuTexts('OK');
      const named = await s.eval(`(() => { const d = Array.from(document.querySelectorAll('.gwt-DialogBox')).find((x) => x.offsetWidth > 0 && x.querySelector('canvas') && x.querySelector('input.gwt-TextBox'));
        if (!d) return 'noDialog'; const tb = d.querySelector('input[type=text]'); if (!tb) return 'noTextBox'; tb.value = 'ed-rc';
        const ok = Array.from(d.querySelectorAll('button')).find((b) => ${JSON.stringify(okTexts)}.includes(b.textContent.trim())); if (!ok) return 'noOk'; ok.click(); return 'ok'; })()`);
      await sleep(400);
      const er = await record('subcircuit', 'ed-rc');
      out.notes.editorCreate = { menu: m2, named, alerts: (await alerts()).slice(b1), record: er && strip(er) };
      ck('editor_createsModel', m2 === 2 && named === 'ok' && er && same(er.pins.map((p) => p.label), ['in', 'out']) && (await alerts()).length === b1);
      await s.call('closeDialogs'); await s.key('Escape');
      // "some nodes are unconnected" (SP_AGA_01_13 Subcircuit source errors): the agent's read-only
      // build decides as the editor's Create Subcircuit on the same circuit. With a ground in the
      // source every part without a path to it is rejected; without any ground-connected element the
      // first floating group of internal nodes is tolerated (the editor's rule), a second one is not.
      const floating = (x) => ({ id: 'RF' + x, type: 'Resistor', start: { x, y: 10 }, end: { x: x + 4, y: 10 } });
      const block = [label('LA', 'a', 4, 4, 2, 4), { id: 'R1', type: 'Resistor', start: { x: 4, y: 4 }, end: { x: 8, y: 4 } }, label('LB', 'b', 8, 4, 10, 4)];
      const variants = {
        grounded: block.concat([{ id: 'G1', type: 'Ground', start: { x: 8, y: 4 }, end: { x: 8, y: 6 } }]).filter((e) => e.id !== 'LB').concat([floating(14)]),
        noGroundOneFloating: block.concat([floating(14)]),
        noGroundTwoFloating: block.concat([floating(14), floating(20)]),
      };
      const expectAccepted = { grounded: false, noGroundOneFloating: true, noGroundTwoFloating: false };
      out.notes.unconnected = {};
      let vi = 0;
      for (const [name, elements] of Object.entries(variants)) {
        const ir = await A('importCircuit', { doc: E, circuit: { elements } });
        const g0 = (await alerts()).length;
        const ag = await A('applyEdits', { doc: T, edits: [{ op: 'defineModel', model: { kind: 'subcircuit', name: 'sub-unc-' + (vi++), source: { doc: E } } }] });
        const agentAlerts = (await alerts()).length - g0;
        await sleep(300);
        await s.call('focus'); await s.key('Escape');
        const a0 = (await alerts()).length;
        const mm = await s.call('clickMenuPath', [menuTexts('File'), menuTexts('Create Subcircuit...')]);
        await sleep(400);
        const edAlerts = (await alerts()).slice(a0);
        editorUncAlerts += edAlerts.length;
        const edDialog = await s.eval(`!!Array.from(document.querySelectorAll('.gwt-DialogBox')).find((x) => x.offsetWidth > 0 && x.querySelector('canvas') && x.querySelector('input.gwt-TextBox'))`);
        // cancel the pin-layout dialog (its OK would alert the empty model name)
        await s.eval(`(() => { const d = Array.from(document.querySelectorAll('.gwt-DialogBox')).find((x) => x.offsetWidth > 0 && x.querySelector('canvas') && x.querySelector('input.gwt-TextBox'));
          if (!d) return; const c = Array.from(d.querySelectorAll('button')).find((b) => ${JSON.stringify(menuTexts('Cancel'))}.includes(b.textContent.trim())); if (c) c.click(); })()`);
        await s.key('Escape');
        const agentAccepted = ag.ok === true;
        const editorAccepted = edAlerts.length === 0 && edDialog === true;
        const agentReason = (ag.issues || []).map((i) => i.message).join('; ');
        out.notes.unconnected[name] = { import: ir.ok, agentAccepted, agentReason, agentAlerts, menu: mm, editorAlerts: edAlerts, editorDialog: edDialog };
        ck('unconnected_' + name + '_agentAsEditor', ir.ok && mm === 2 && agentAlerts === 0 && agentAccepted === editorAccepted && agentAccepted === expectAccepted[name]
          && (agentAccepted || (/some nodes are unconnected/.test(agentReason) && same(edAlerts, ['Some nodes are unconnected!']))));
      }
      await A('closeDocument', { doc: E, discardChanges: true });
    }

    for (const doc of [S, T, N0, NG, NU, NC, N2, B, U, RT, O, LB, I]) await A('closeDocument', { doc, discardChanges: true });
    out.rejections = rejections;
    const unexpected = s.exceptions.slice(exMark).filter((e) => !/debugFailNextMutation/.test(e));
    out.notes.exceptions = unexpected.slice(0, 5);
    ck('noPageException', unexpected.length === 0);
    const allAlerts = await alerts();
    out.notes.alerts = allAlerts;
    // the only alerts: the editor's label-on-ground one and its two "Some nodes are unconnected!" ones
    ck('noAgentAlert', editorUncAlerts === 2 && allAlerts.length === 1 + editorUncAlerts
      && allAlerts.filter((m) => m === 'Some nodes are unconnected!').length === editorUncAlerts && s.dialogs.length === dialogMark);
  } catch (e) {
    out.notes.error = e.stack || e.message;
    ck('noHarnessError', false);
  } finally {
    await s.eval('if (window.__savedAlert) { window.alert = window.__savedAlert; delete window.__savedAlert; }').catch(() => {});
  }
  fs.writeFileSync(path.join(OUT_DIR, 'agent_models_sub.json'), JSON.stringify(out, null, 2));
  const failed = Object.entries(out.checks).filter(([, v]) => !v).map(([k]) => k);
  report('AG.agent_models_sub', failed.length === 0, { checks: Object.keys(out.checks).length, failed, details: path.join(OUT_DIR, 'agent_models_sub.json') });
}

// pin_names: polar pin names state the real polarity (SP_AGA_DEC_06, SP_AGA_DEC_08 FETs, SP_AGA_03_02 "Polar names",
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
    // a new export: the current version (2.1 renamed the pins; 2.2 since PL_AGA Phase 14), new names
    const ex = await A('exportCircuit', { doc, format: 'json' });
    const j = JSON.parse(ex.data.content);
    out.notes[k].exportPins = Object.keys(j.elements.X1.pins);
    ck(k + '_exportNewNames', j.schema.version === '2.2' && same(Object.keys(j.elements.X1.pins).filter((p) => !p.startsWith('_')), c.names));
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
  // FETs and BJTs wired by their pin names (SP_AGA_DEC_08): the supply on `source`/`emitter`, the
  // load (1 kOhm) from `drain`/`collector` to the other rail, the gate/base on a rail (BJT: through
  // 100 kOhm). Off: the drain stays at the load's rail — a P-channel body diode tied to the wrong
  // post conducts and lifts it (11.43 V before the fix). On: the device conducts; `current` is the
  // drain/collector current, positive into the drain (negative for P-channel and PNP), `voltage`
  // of a FET is drain minus source.
  const fetCases = {
    nmos: { type: 'NMOS', p: false, names: ['gate', 'source', 'drain'], on: 12, off: 0 },
    pmos: { type: 'PMOS', p: true, names: ['gate', 'drain', 'source'], on: 0, off: 12 },
    pmos_noBodyDiode: { type: 'PMOS', props: { body_diode: false }, p: true, names: ['gate', 'drain', 'source'], on: 0, off: 12 },
    pmos_bodyTerminal: { type: 'PMOS', props: { body_terminal: true }, p: true, names: ['gate', 'drain', 'source', 'body'], on: 0, off: 12 },
    nmos_bodyTerminal: { type: 'NMOS', props: { body_terminal: true }, p: false, names: ['gate', 'source', 'drain', 'body'], on: 12, off: 0 },
    njfet: { type: 'NJFET', p: false, names: ['gate', 'source', 'drain'], on: 0, off: -6 },
    pjfet: { type: 'PJFET', p: true, names: ['gate', 'drain', 'source'], on: 12, off: 18 },
    pnp: { type: 'TransistorPNP', p: true, bjt: true, names: ['base', 'collector', 'emitter'], on: 0, off: 12 },
    // a Darlington is a composite without element quantities: post voltages only
    pdarl: { type: 'DarlingtonPNP', p: true, bjt: true, noQty: true, names: ['base', 'collector', 'emitter'], on: 0, off: 12 },
    npn: { type: 'TransistorNPN', p: false, bjt: true, names: ['base', 'collector', 'emitter'], on: 12, off: 0 },
  };
  const away = (at, dx, dy) => ({ x: at.x + dx, y: at.y + dy });
  out.notes.fets = {};
  for (const [k, c] of Object.entries(fetCases)) {
    const q = { id: 'Q1', type: c.type, start: { x: 0, y: 0 }, ...(c.props ? { properties: c.props } : {}) };
    const imp0 = await A('importCircuit', { doc, circuit: { elements: [q] } });
    const rec = imp0.ok && ((await A('getCircuit', { doc })).data.elements || []).find((e) => e.id === 'Q1');
    const note = out.notes.fets[k] = { imp0: imp0.ok ? 'ok' : codes(imp0.issues), pins: rec && rec.posts.map((p) => p.pin + '@' + p.at.x + ',' + p.at.y) };
    ck('fet_' + k + '_names', rec && same(rec.posts.map((p) => p.pin), c.names));
    if (!rec) continue;
    // wired by the names the record gives (whatever post they are on)
    const at = Object.fromEntries(rec.posts.map((p) => [p.pin, p.at]));
    const [ctl, out1, sup] = c.bjt ? ['base', 'collector', 'emitter'] : ['gate', 'drain', 'source'];
    const supV = c.p ? 12 : 0;
    const loadV = c.p ? 0 : 12;
    // rails leave the posts sideways (gate left, others right), clear of the symbol
    const circuit = (ctlV) => [q,
      { id: 'VS', type: 'Rail', start: at[sup], end: away(at[sup], 2, 0), properties: { max_voltage: supV + ' V' } },
      ...(c.bjt
        ? [{ id: 'RB', type: 'Resistor', start: at[ctl], end: away(at[ctl], -4, 0), properties: { resistance: '100 kOhm' } },
          { id: 'VG', type: 'Rail', start: away(at[ctl], -4, 0), end: away(at[ctl], -6, 0), properties: { max_voltage: ctlV + ' V' } }]
        : [{ id: 'VG', type: 'Rail', start: at[ctl], end: away(at[ctl], -2, 0), properties: { max_voltage: ctlV + ' V' } }]),
      { id: 'RL', type: 'Resistor', start: at[out1], end: away(at[out1], 4, 0), properties: { resistance: '1 kOhm' } },
      { id: 'VL', type: 'Rail', start: away(at[out1], 4, 0), end: away(at[out1], 6, 0), properties: { max_voltage: loadV + ' V' } },
      // the body terminal goes to the supply (source) rail
      ...(at.body ? [{ id: 'VB', type: 'Rail', start: at.body, end: away(at.body, 0, at.body.y >= at[ctl].y ? 2 : -2), properties: { max_voltage: supV + ' V' } }] : []),
    ];
    for (const state of ['off', 'on']) {
      const imp = await A('importCircuit', { doc, circuit: { elements: circuit(c[state]) } });
      await R({ doc, span: '2 ms', reset: true, maxPoints: 10 });
      const v = await values([{ name: 'out', post: 'Q1.' + out1 }, { name: 'sup', post: 'Q1.' + sup },
        ...(c.noQty ? [] : [{ name: 'i', element: 'Q1', quantity: 'current' }, { name: 'v', element: 'Q1', quantity: 'voltage' }])]);
      note[state] = { ok: imp.ok ? true : codes(imp.issues), ...v };
      if (!imp.ok) { ck('fet_' + k + '_' + state + '_import', false); continue; }
      if (state === 'off') {
        // nothing conducts: the output stays at the load rail, no current
        ck('fet_' + k + '_offBlocks', near(v.out, loadV, 0.01) && near(v.sup, supV) && (c.noQty || Math.abs(v.i) < 1e-6));
      } else {
        // conducting: the output is pulled most of the way to the supply; current into the
        // drain/collector is positive for N-type, negative for P-type, about the load current
        const iLoad = (v.out - loadV) / 1000;
        ck('fet_' + k + '_onConducts', Math.abs(v.out - loadV) > 6);
        if (!c.noQty) ck('fet_' + k + '_currentSign', near(v.i, -iLoad, Math.abs(iLoad) * 0.02) && (c.p ? v.i < 0 : v.i > 0));
        if (!c.bjt) ck('fet_' + k + '_voltageDrainMinusSource', near(v.v, v.out - v.sup, 1e-3));
      }
    }
  }
  // a JFET's drain current includes its gate-drain junction: n-channel, source open, gate fed
  // from 5 V through 10 kOhm, drain through 1 kOhm to ground. The whole gate current leaves at
  // the drain (through the junction and the channel), so `current` = -(gate current); the
  // gate junction currents were never computed before (JfetElm.calculateCurrent unused).
  {
    const rec = (await A('importCircuit', { doc, circuit: { elements: [{ id: 'Q1', type: 'NJFET', start: { x: 0, y: 0 } }] } })).ok
      && ((await A('getCircuit', { doc })).data.elements || [])[0];
    const at = rec ? Object.fromEntries(rec.posts.map((p) => [p.pin, p.at])) : {};
    const imp = rec && await A('importCircuit', { doc, circuit: { elements: [{ id: 'Q1', type: 'NJFET', start: { x: 0, y: 0 } },
      { id: 'RG', type: 'Resistor', start: at.gate, end: away(at.gate, -4, 0), properties: { resistance: '10 kOhm' } },
      { id: 'VG', type: 'Rail', start: away(at.gate, -4, 0), end: away(at.gate, -6, 0), properties: { max_voltage: '5 V' } },
      { id: 'RL', type: 'Resistor', start: at.drain, end: away(at.drain, 4, 0), properties: { resistance: '1 kOhm' } },
      { id: 'G1', type: 'Ground', start: away(at.drain, 4, 0), end: away(at.drain, 4, 1) }] } });
    await R({ doc, span: '2 ms', reset: true, maxPoints: 10 });
    const v = await values([{ name: 'g', post: 'Q1.gate' }, { name: 'd', post: 'Q1.drain' }, { name: 'i', element: 'Q1', quantity: 'current' }]);
    const iGate = (5 - v.g) / 10000;
    out.notes.jfetGateCurrent = { ok: imp && imp.ok, iGate, ...v };
    ck('fet_njfet_drainCurrentWithGate', imp && imp.ok && iGate > 1e-4 && near(v.i, -iGate, iGate * 0.01) && near(v.d, iGate * 1000, 0.01));
  }
  // user scopes are unchanged (SP_AGA_DEC_08): on the visible tab a conducting PMOS (source 12 V,
  // gate 0 V, drain through 1 kOhm to ground) has a current and a voltage scope; they plot the
  // channel current ids (Isd, positive) and post 2 minus post 1 (Vsd, positive), while `current`
  // and `voltage` read the drain current (negative) and drain minus source (negative)
  {
    const A0 = ((await A('listDocuments', {})).data.documents || []).find((d) => d.active).doc;
    const imp = await A('importCircuit', { doc: A0, circuit: { elements: [
      { id: 'Q1', type: 'PMOS', start: { x: 0, y: 0 } },
      { id: 'VS', type: 'Rail', start: { x: 4, y: -1 }, end: { x: 6, y: -1 }, properties: { max_voltage: '12 V' } },
      { id: 'VG', type: 'Rail', start: { x: 0, y: 0 }, end: { x: -2, y: 0 }, properties: { max_voltage: '0 V' } },
      { id: 'RL', type: 'Resistor', start: { x: 4, y: 1 }, end: { x: 8, y: 1 }, properties: { resistance: '1 kOhm' } },
      { id: 'G1', type: 'Ground', start: { x: 8, y: 1 }, end: { x: 8, y: 2 } }] } });
    const sc = await A('applyEdits', { doc: A0, edits: [{ op: 'addScope', element: 'Q1', quantity: 'current' }, { op: 'addScope', element: 'Q1', quantity: 'voltage' }] });
    await R({ doc: A0, span: '2 ms', reset: true, maxPoints: 10 });
    const rd = await A('read', { doc: A0, targets: [{ name: 'i', element: 'Q1', quantity: 'current' }, { name: 'v', element: 'Q1', quantity: 'voltage' },
      { name: 'd', post: 'Q1.drain' }, { name: 's', post: 'Q1.source' }] });
    const r = rd.ok ? Object.fromEntries(rd.data.values.map((x) => [x.name, x.value])) : {};
    // last recorded sample of the two newest scopes (plot 0 each)
    const scopes = await s.eval(`(() => { const n = CircuitJS1.getScopeCount(); const last = (k) => { const d = CircuitJS1.getScopeData(k, 0);
      if (!d) return null; const m = d.maxValues.length; const j = (d.ptr - 1 + m) % m; return { max: d.maxValues[j], min: d.minValues[j], units: d.units }; };
      return { n, current: last(n - 2), voltage: last(n - 1) }; })()`);
    out.notes.userScopePmos = { imp: imp.ok, scopes: sc.ok, read: r, scopeValues: scopes };
    const isd = r.d / 1000; // load current = source-to-drain channel current
    ck('pmos_userScopeUnchanged', imp.ok && sc.ok && scopes && scopes.current && scopes.voltage
      && scopes.current.units === 1 && near(scopes.current.max, isd, isd * 0.02) && scopes.current.max > 0
      && near(scopes.voltage.max, r.s - r.d, 1e-3) && scopes.voltage.max > 0
      && near(r.i, -isd, isd * 0.02) && near(r.v, r.d - r.s, 1e-3));
    await A('applyEdits', { doc: A0, edits: [{ op: 'removeScope', element: 'Q1' }] });
  }
  // catalogue
  const want = { VoltageSourceDC: ['minus', 'plus'], VoltageSourceAC: ['minus', 'plus'], VoltageSourceSquare: ['minus', 'plus'],
    CurrentSource: ['in', 'out'], OhmMeter: ['com', 'probe'], OpAmp: ['in-', 'in+', 'out'], PolarCapacitor: ['positive', 'negative'], Rail: ['output'],
    NMOS: ['gate', 'source', 'drain'], PMOS: ['gate', 'drain', 'source'], NJFET: ['gate', 'source', 'drain'], PJFET: ['gate', 'drain', 'source'] };
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

// agent_defects: the defect batch found while writing the agent skill (docs/agent-api.plan.md
// Backlog, PL_AGS Phase 1), on a background document. (1) a BJT's element `current` is its
// collector current; (2) ground_path_no_resistance and wire_loop are connectivity issues, and a
// current source without a current path (two in series) is `current_source_no_path`; (3) a 555
// output that drives only a label is not an isolated group (TimerElm declares its internal paths);
// (4) LogicInput `state` is read-only, `position` sets the level; (5) TypeInfo.defaultFlags equals
// the flags of a record after add, AgentCircuit import, JSON and text round trips (format bits
// that dump() sets are part of the flags from the start).
async function scenarioAgentDefects(s) {
  const out = { checks: {}, notes: {} };
  const ck = (name, cond) => { out.checks[name] = !!cond; return !!cond; };
  const A = (op, args) => s.call('agentCall', op, args);
  const R = (args) => s.call('agentAsync', 'run', args, 60000);
  const codes = (list) => (list || []).map((i) => i.code);
  const near = (v, x, rel) => typeof v === 'number' && Math.abs(v - x) <= Math.abs(x) * (rel || 1e-3);
  await resetApp(s);
  const exMark = s.exceptions.length;
  const vis0 = await s.call('visibleTab');
  const doc = (await A('createDocument', { title: 'Defects' })).data.doc;
  const conn = async () => { const r = await A('getConnectivity', { doc, includeNets: false }); return r.ok ? r.data.issues : []; };
  const postsOf = async (id) => Object.fromEntries(((await A('getCircuit', { doc })).data.elements.find((e) => e.id === id).posts).map((p) => [p.pin, p.at]));
  const at = (p, dx, dy) => ({ x: p.x + dx, y: p.y + dy });

  // (1) BJT current: common emitter, NPN and PNP; the collector resistor carries I_C
  for (const t of ['TransistorNPN', 'TransistorPNP']) {
    await A('importCircuit', { doc, circuit: { elements: [{ id: 'Q1', type: t, start: { x: 0, y: 0 }, end: { x: 4, y: 0 } }] } });
    const P = await postsOf('Q1');
    const npn = t === 'TransistorNPN';
    // NPN: base from +5 V through 100k, collector to +10 V through 1k, emitter grounded.
    // PNP: emitter at +10 V, base to ground through 100k, collector to ground through 1k.
    const els = [{ id: 'Q1', type: t, start: { x: 0, y: 0 }, end: { x: 4, y: 0 } },
      { id: 'RB', type: 'Resistor', start: at(P.base, -4, 0), end: P.base, properties: { resistance: '100k' } },
      { id: 'RC', type: 'Resistor', start: P.collector, end: at(P.collector, 0, -4), properties: { resistance: '1k' } }];
    if (npn) {
      els.push({ id: 'V1', type: 'Rail', start: at(P.base, -4, 0), end: at(P.base, -6, 0), properties: { max_voltage: '5 V' } },
        { id: 'V2', type: 'Rail', start: at(P.collector, 0, -4), end: at(P.collector, 0, -6), properties: { max_voltage: '10 V' } },
        { id: 'G1', type: 'Ground', start: P.emitter, end: at(P.emitter, 0, 1) });
    } else {
      els.push({ id: 'G1', type: 'Ground', start: at(P.base, -4, 0), end: at(P.base, -4, 1) },
        { id: 'G2', type: 'Ground', start: at(P.collector, 0, -4), end: at(P.collector, 0, -5) },
        { id: 'V2', type: 'Rail', start: P.emitter, end: at(P.emitter, 0, 2), properties: { max_voltage: '10 V' } });
    }
    const imp = await A('importCircuit', { doc, circuit: { elements: els } });
    const run = await R({ doc, span: '1 ms', reset: true, maxPoints: 10, probes: [{ element: 'Q1', quantity: 'current', name: 'iq' }, { element: 'RC', quantity: 'current', name: 'irc' }] });
    const f = run.ok ? Object.fromEntries(run.data.probes.map((p) => [p.name, p.stats.final])) : {};
    const rd = await A('read', { doc, targets: [{ element: 'Q1', quantity: 'current', name: 'iq' }] });
    const iqRead = rd.ok ? rd.data.values[0].value : null;
    // RC current runs pin1 (collector) -> pin2: NPN draws I_C into the collector (irc < 0)
    out.notes[t] = { imp: imp.ok, errors: imp.ok ? imp.connectivity.errorCount : codes(imp.issues), f, iqRead };
    const k = npn ? 'npn' : 'pnp';
    ck(k + 'CurrentIsCollector', imp.ok && Math.abs(f.irc) > 1e-3 && near(f.iq, -f.irc, 1e-3) && (npn ? f.iq > 0 : f.iq < 0));
    ck(k + 'CurrentRead', near(iqRead, f.iq, 1e-3));
  }

  // (2) static solver-found issues
  await A('importCircuit', { doc, circuit: { elements: [
    { id: 'V1', type: 'Rail', start: { x: 0, y: 0 }, end: { x: 0, y: -2 } },
    { id: 'W1', type: 'Wire', start: { x: 0, y: 0 }, end: { x: 4, y: 0 } },
    { id: 'G1', type: 'Ground', start: { x: 4, y: 0 }, end: { x: 4, y: 1 } }] } });
  let iss = await conn();
  out.notes.railToGround = iss.map((i) => i.code + ':' + i.severity + ':' + (i.elements || []).join(','));
  ck('groundPathStatic', iss.some((i) => i.code === 'ground_path_no_resistance' && i.severity === 'error' && (i.elements || []).includes('V1')));
  const loopEls = [
    { id: 'V1', type: 'VoltageSourceDC', start: { x: 0, y: 4 }, end: { x: 0, y: 0 } },
    { id: 'R1', type: 'Resistor', start: { x: 0, y: 0 }, end: { x: 4, y: 0 } },
    { id: 'W1', type: 'Wire', start: { x: 4, y: 0 }, end: { x: 4, y: 4 } },
    { id: 'W2', type: 'Wire', start: { x: 4, y: 4 }, end: { x: 0, y: 4 } },
    { id: 'W3', type: 'Wire', start: { x: 4, y: 0 }, end: { x: 8, y: 0 } },
    { id: 'W4', type: 'Wire', start: { x: 8, y: 0 }, end: { x: 8, y: 4 } },
    { id: 'W5', type: 'Wire', start: { x: 8, y: 4 }, end: { x: 4, y: 4 } },
    { id: 'G1', type: 'Ground', start: { x: 0, y: 4 }, end: { x: 0, y: 5 } }];
  const impLoop = await A('importCircuit', { doc, circuit: { elements: loopEls } });
  iss = await conn();
  out.notes.wireLoop = iss.map((i) => i.code + ':' + i.severity);
  ck('wireLoopStatic', iss.some((i) => i.code === 'wire_loop' && i.severity === 'warning')
    && impLoop.ok && codes(impLoop.connectivity.added).includes('wire_loop'));
  // two current sources in series (1 mA, 2 mA) into R1: both broken, 0 A everywhere
  await A('importCircuit', { doc, circuit: { elements: [
    { id: 'I1', type: 'CurrentSource', start: { x: 0, y: 4 }, end: { x: 0, y: 0 }, properties: { current: '1 mA' } },
    { id: 'I2', type: 'CurrentSource', start: { x: 0, y: 0 }, end: { x: 4, y: 0 }, properties: { current: '2 mA' } },
    { id: 'R1', type: 'Resistor', start: { x: 4, y: 0 }, end: { x: 4, y: 4 } },
    { id: 'W2', type: 'Wire', start: { x: 4, y: 4 }, end: { x: 0, y: 4 } },
    { id: 'G1', type: 'Ground', start: { x: 0, y: 4 }, end: { x: 0, y: 5 } }] } });
  iss = await conn();
  out.notes.currentSeries = iss.map((i) => i.code + ':' + i.severity + ':' + (i.elements || []).join(','));
  const noPath = iss.filter((i) => i.code === 'current_source_no_path' && i.severity === 'warning').map((i) => i.elements.join(','));
  ck('currentSourcesInSeries', noPath.includes('I1') && noPath.includes('I2'));
  // controls: one current source into a resistor; an ohmmeter with open probes (a valid R = inf)
  await A('importCircuit', { doc, circuit: { elements: [
    { id: 'I1', type: 'CurrentSource', start: { x: 0, y: 4 }, end: { x: 0, y: 0 }, properties: { current: '1 mA' } },
    { id: 'R1', type: 'Resistor', start: { x: 0, y: 0 }, end: { x: 4, y: 0 } },
    { id: 'W1', type: 'Wire', start: { x: 4, y: 0 }, end: { x: 4, y: 4 } },
    { id: 'W2', type: 'Wire', start: { x: 4, y: 4 }, end: { x: 0, y: 4 } },
    { id: 'G1', type: 'Ground', start: { x: 0, y: 4 }, end: { x: 0, y: 5 } },
    { id: 'OM1', type: 'OhmMeter', start: { x: 10, y: 4 }, end: { x: 10, y: 0 } },
    { id: 'G2', type: 'Ground', start: { x: 10, y: 4 }, end: { x: 10, y: 5 } }] } });
  await A('applyEdits', { doc, edits: [{ op: 'markOpen', posts: ['OM1.probe'] }] });
  iss = await conn();
  out.notes.currentControls = iss.map((i) => i.code + ':' + (i.elements || []).join(','));
  const rd1 = await A('read', { doc, targets: [{ post: 'I1.out' }] });
  ck('currentSourceControls', !codes(iss).includes('current_source_no_path') && rd1.ok);

  // (3) 555 output driving only a label (Vcc on a rail, ground pin grounded)
  await A('importCircuit', { doc, circuit: { elements: [{ id: 'T1', type: 'Timer555', start: { x: 0, y: 0 }, end: { x: 4, y: 0 } }] } });
  const T = await postsOf('T1');
  const t555 = [{ id: 'T1', type: 'Timer555', start: { x: 0, y: 0 }, end: { x: 4, y: 0 } },
    { id: 'V1', type: 'Rail', start: T.Vcc, end: at(T.Vcc, 0, -2), properties: { max_voltage: '5 V' } },
    { id: 'G1', type: 'Ground', start: T.gnd, end: at(T.gnd, 0, 1) },
    { id: 'L1', type: 'LabeledNode', start: T.out, end: at(T.out, 2, 0), properties: { label: 'q' } }];
  await A('importCircuit', { doc, circuit: { elements: t555 } });
  iss = await conn();
  const groups = iss.filter((i) => i.code === 'isolated_group').map((i) => i.posts.join(','));
  out.notes.timerLabel = groups;
  ck('timerOutLabelNotIsolated', !groups.some((g) => g.includes('T1.out') || g.includes('L1.node')));
  ck('timerCtlNotIsolated', !groups.some((g) => g.includes('T1.ctl')));
  // control: the trigger input still has no path (a real floating input)
  ck('timerInputsStillIsolated', groups.some((g) => g.includes('T1.tr')));
  // the same 555 without a ground pin
  await A('applyEdits', { doc, edits: [{ op: 'set', id: 'T1', properties: { has_ground_pin: false } }, { op: 'delete', id: 'G1' }] });
  iss = await conn();
  const groups2 = iss.filter((i) => i.code === 'isolated_group').map((i) => i.posts.join(','));
  out.notes.timerLabelNoGnd = groups2;
  ck('timerNoGroundPinOutNotIsolated', !groups2.some((g) => g.includes('T1.out') || g.includes('T1.ctl')));
  // behaviour unchanged: 555square.txt oscillates at the frequency of the pre-fix build
  const sq = await s.eval(`__H.fetchText('/circuitjs1/circuits/555square.txt')`);
  await A('importCircuit', { doc, circuit: sq });
  const sqRun = await R({ doc, span: '40 ms', recordFrom: '10 ms', reset: true, maxPoints: 10, probes: [{ post: 'TIM1.out', name: 'out' }] });
  const sqStats = sqRun.ok ? sqRun.data.probes[0].stats : null;
  out.notes.square = sqStats && { f: sqStats.frequency, duty: sqStats.dutyCycle, max: sqStats.max, min: sqStats.min, issues: codes(sqRun.issues) };
  ck('timerSquareUnchanged', sqStats && near(sqStats.frequency, TIMER_SQUARE_HZ, 1e-4) && near(sqStats.dutyCycle, TIMER_SQUARE_DUTY, 1e-4));

  // (4) LogicInput: position is the level, state is read-only
  const li = await A('describeType', { type: 'LogicInput' });
  const liProp = (k) => li.ok && li.data.properties.find((p) => p.key === k);
  ck('logicInputStateReadOnly', liProp('state') && liProp('state').readOnly === true && liProp('position') && !liProp('position').readOnly);
  await A('importCircuit', { doc, circuit: { elements: [
    { id: 'SW1', type: 'LogicInput', start: { x: 0, y: 0 }, end: { x: 2, y: 0 } },
    { id: 'R1', type: 'Resistor', start: { x: 0, y: 0 }, end: { x: 0, y: 4 } },
    { id: 'G1', type: 'Ground', start: { x: 0, y: 4 }, end: { x: 0, y: 5 } }] } });
  const setState = await A('applyEdits', { doc, edits: [{ op: 'set', id: 'SW1', properties: { state: 'open' } }] });
  out.notes.setState = { ok: setState.ok, issues: (setState.issues || []).map((i) => i.code + ': ' + i.message + ' / ' + i.hint) };
  ck('logicInputSetStateRejected', !setState.ok && codes(setState.issues).includes('invalid_value')
    && setState.issues.some((i) => /position/.test(i.hint)));
  const setPos = await A('applyEdits', { doc, edits: [{ op: 'set', id: 'SW1', properties: { position: 1 } }] });
  await R({ doc, span: '1 ms', reset: true, maxPoints: 10 });
  const vOut = await A('read', { doc, targets: [{ post: 'SW1.output' }] });
  const liRec = (await A('getCircuit', { doc, detail: 'full' })).data.elements.find((e) => e.id === 'SW1');
  out.notes.setPosition = { ok: setPos.ok, issues: codes(setPos.issues), v: vOut.ok && vOut.data.values[0].value, props: liRec.properties };
  ck('logicInputPositionSetsLevel', setPos.ok && !codes(setPos.issues).includes('value_adjusted') && vOut.ok && near(vOut.data.values[0].value, 5, 1e-6) && liRec.properties.state === 'open');

  // (5) flags: TypeInfo.defaultFlags = add = AgentCircuit import = JSON and text round trips, for every type
  const types = (await A('listTypes', {})).data.types.map((t) => t.type);
  const flagBad = []; let flagTypes = 0;
  for (const type of types) {
    const d = await A('describeType', { type });
    if (!d.ok) continue;
    const def = d.data.defaultFlags;
    const flagsOf = async () => { const g = await A('getCircuit', { doc, detail: 'full' }); return g.ok && g.data.elements.length === 1 ? g.data.elements[0].flags : null; };
    await A('importCircuit', { doc, circuit: { elements: [] } });
    const add = await A('applyEdits', { doc, edits: [{ op: 'add', element: { id: 'X1', type, start: { x: 0, y: 0 } } }] });
    if (!add.ok) continue;
    flagTypes++;
    const fAdd = await flagsOf();
    const imp = await A('importCircuit', { doc, circuit: { elements: [{ id: 'X1', type, start: { x: 0, y: 0 } }] } });
    const fImp = imp.ok ? await flagsOf() : 'rejected';
    // concise records omit flags equal to the default (read before the text leg: a text load may
    // give an equivalent class, e.g. Clock -> Rail with the clock bit)
    const conc = await A('getCircuit', { doc });
    const concFlags = conc.ok && conc.data.elements.length === 1 ? conc.data.elements[0].flags : 'none';
    const exJ = await A('exportCircuit', { doc, format: 'json' });
    const fJson = (await A('importCircuit', { doc, circuit: exJ.data.content })).ok ? await flagsOf() : 'rejected';
    const exT = await A('exportCircuit', { doc, format: 'text' });
    const fText = (await A('importCircuit', { doc, circuit: exT.data.content })).ok ? await flagsOf() : 'rejected';
    // a type the text format does not carry as one element line (CustomCompositeChip, Scope) skips that leg
    const legs = fText === null ? [fAdd, fImp, fJson] : [fAdd, fImp, fJson, fText];
    if (!legs.every((f) => f === def) || concFlags !== undefined) flagBad.push({ type, def, fAdd, fImp, fJson, fText, concFlags });
  }
  out.notes.flags = { types: flagTypes, bad: flagBad };
  ck('flagsStable', flagTypes > 100 && flagBad.length === 0);

  // (6) number-or-string arguments double-encoded by agent hosts ("\"10 ms\"") are read without the
  // quotes (SP_AGA §03_03); anything else inside the quotes is still invalid_value
  await A('importCircuit', { doc, circuit: { elements: [
    { id: 'V1', type: 'VoltageSourceDC', start: { x: 0, y: 4 }, end: { x: 0, y: 0 }, properties: { max_voltage: '5 V' } },
    { id: 'R1', type: 'Resistor', start: { x: 0, y: 0 }, end: { x: 4, y: 0 }, properties: { resistance: '"2k"' } },
    { id: 'R2', type: 'Resistor', start: { x: 4, y: 0 }, end: { x: 4, y: 4 }, properties: { resistance: '1k' } },
    { id: 'W1', type: 'Wire', start: { x: 4, y: 4 }, end: { x: 0, y: 4 } },
    { id: 'G1', type: 'Ground', start: { x: 0, y: 4 }, end: { x: 0, y: 5 } }] } });
  const q = await R({ doc, reset: true, span: '"1 ms"', recordFrom: '"0.5 ms"', probes: [{ name: 'mid', post: 'R2.' + (await A('describeType', { type: 'Resistor' })).data.pins[0] }] });
  const qBad = await R({ doc, reset: true, span: '"1 parsec"' });
  const r1 = (await A('getCircuit', { doc, ids: ['R1'] })).data.elements[0];
  out.notes.quoted = { ok: q.ok, reason: q.data && q.data.reason, mid: q.ok && q.data.probes[0].stats.final, bad: codes(qBad.issues), r1: r1 && r1.properties };
  ck('quotedSpanAccepted', q.ok && q.data.reason === 'span_reached' && near(q.data.tEnd - q.data.tStart, 1e-3, 0.02));
  // R1 = 2k over R2 = 1k from 5 V: the mid node reads 5/3 V only if the quoted "2k" was applied
  ck('quotedPropertyAccepted', r1 && /^2 ?k/.test(String(r1.properties && r1.properties.resistance)) && q.ok && near(q.data.probes[0].stats.final, 5 / 3, 1e-3));
  ck('quotedJunkRejected', !qBad.ok && codes(qBad.issues).includes('invalid_value'));

  await A('closeDocument', { doc, discardChanges: true });
  ck('visibleTabUnchanged', JSON.stringify(vis0) === JSON.stringify(await s.call('visibleTab')));
  ck('noExceptions', s.exceptions.length === exMark);
  const failed = Object.keys(out.checks).filter((k) => !out.checks[k]);
  fs.writeFileSync(path.join(OUT_DIR, 'agent_defects.json'), JSON.stringify(out, null, 2));
  report('AG.agent_defects', failed.length === 0, { checks: Object.keys(out.checks).length, failed });
}
// verify_defects: the defect batch of the live JFET DC-DC verify (docs/agent-api.plan.md Backlog,
// 2026-10-03), mostly on a background document. (1) Transformer pins name the windings: p1-p2 is
// the primary, s1-s2 the secondary, p1/s1 the in-phase ends (a 1 V step on p1 gives +ratio V on
// s1, also with reverse_polarity, which moves s1 in the drawing); JSON 2.0 names load as aliases,
// old names are no PostRefs. (2) Transformer/TappedTransformer `ratio` (N2/N1) carries its own
// TypeInfo label and no N1/N2 slider seeds. (3) A model name the session does not hold is
// invalid_value on add/set/AgentCircuit/JSON/text import (a text model line defines one), is never
// registered, and the TypeInfo lists the model choices; user loads are unchanged. (4) Geometry
// that collapses posts is zero_length; an end an element replaces is value_adjusted. (5) render
// uses printable colours and keeps value labels inside the image. (6) TypeInfo.quantities agrees
// with the element probes `read` accepts.
async function scenarioVerifyDefects(s) {
  const out = { checks: {}, notes: {} };
  const ck = (name, cond) => { out.checks[name] = !!cond; return !!cond; };
  const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
  const A = (op, args) => s.call('agentCall', op, args);
  const R = (args) => s.call('agentAsync', 'run', args, 60000);
  const AA = (op, args) => s.call('agentAsync', op, args, 60000);
  const codes = (list) => (list || []).map((i) => i.code);
  const near = (v, x, tol) => typeof v === 'number' && Math.abs(v - x) <= tol;
  const at = (p, dx, dy) => ({ x: p.x + dx, y: p.y + dy });
  await resetApp(s);
  const exMark = s.exceptions.length;
  const vis0 = await s.call('visibleTab');
  const doc = (await A('createDocument', { title: 'Verify defects' })).data.doc;
  const recOf = async (id) => ((await A('getCircuit', { doc, detail: 'full' })).data.elements || []).find((e) => e.id === id);
  const postsOf = async (id) => { const r = await recOf(id); return r ? Object.fromEntries(r.posts.map((p) => [p.pin, p.at])) : {}; };
  const values = async (targets) => {
    const r = await A('read', { doc, targets });
    return r.ok ? Object.fromEntries(r.data.values.map((v) => [v.name, v.value])) : { __issues: codes(r.issues) };
  };

  // (1) Transformer pin names follow the windings; (2) the ratio label
  const tT = await A('describeType', { type: 'Transformer' });
  const tTap = await A('describeType', { type: 'TappedTransformer' });
  out.notes.pins = { Transformer: tT.data.pins, TappedTransformer: tTap.data.pins };
  ck('xfPinNames', same(tT.data.pins, ['p1', 's1', 'p2', 's2']));
  ck('tappedPinNamesKept', same(tTap.data.pins, ['pri1', 'pri2', 'sec1', 'tap', 'sec2']));
  const xf = (rev) => ({ id: 'T1', type: 'Transformer', start: { x: 0, y: 0 }, end: { x: 4, y: 0 },
    properties: { inductance: '1 H', ratio: 2, coupling: 0.999, reverse_polarity: rev } });
  let xfJson = null;
  for (const rev of [false, true]) {
    const k = 'rev' + rev;
    await A('importCircuit', { doc, circuit: { elements: [xf(rev)] } });
    const P = await postsOf('T1');
    if (!P.p1 || !P.s1 || !P.p2 || !P.s2) { ck('xfInPhase_' + k, false); out.notes[k] = { posts: P }; continue; }
    // a 1 V step on p1 (p2 grounded); s2 grounded, s1 loaded by 1 MOhm: V(s1) = coupling * ratio * 1 V
    const imp = await A('importCircuit', { doc, circuit: { elements: [xf(rev),
      { id: 'V1', type: 'Rail', start: P.p1, end: at(P.p1, -2, 0), properties: { max_voltage: '1 V' } },
      { id: 'G1', type: 'Ground', start: P.p2, end: at(P.p2, 0, 1) },
      { id: 'G2', type: 'Ground', start: P.s2, end: at(P.s2, 0, P.s2.y > P.s1.y ? 1 : -1) },
      { id: 'RL', type: 'Resistor', start: P.s1, end: at(P.s1, 4, 0), properties: { resistance: '1 MOhm' } },
      { id: 'G3', type: 'Ground', start: at(P.s1, 4, 0), end: at(P.s1, 4, 1) }] } });
    const run = await R({ doc, span: '1 ms', reset: true, maxPoints: 10 });
    const v = await values([{ name: 'p1', post: 'T1.p1' }, { name: 's1', post: 'T1.s1' }, { name: 's2', post: 'T1.s2' }]);
    out.notes[k] = { posts: P, imp: imp.ok ? imp.connectivity.errorCount : codes(imp.issues), run: run.ok && run.data.reason, v };
    ck('xfInPhase_' + k, imp.ok && run.ok && near(v.p1, 1, 1e-9) && near(v.s1, 2 * 0.999, 0.02) && near(v.s2, 0, 1e-9));
    ck('xfReverseMovesS1_' + k, rev ? same(P.s1, { x: 4, y: 2 }) && same(P.s2, { x: 4, y: 0 }) : same(P.s1, { x: 4, y: 0 }) && same(P.s2, { x: 4, y: 2 }));
    if (!rev) xfJson = (await A('exportCircuit', { doc, format: 'json' })).data.content;
  }
  const oldRef = await A('read', { doc, targets: [{ post: 'T1.pri2' }] });
  ck('xfOldPostRefUnknown', !oldRef.ok && codes(oldRef.issues).includes('unknown_post'));
  if (xfJson) {
    const j = JSON.parse(xfJson);
    const keys = Object.keys(j.elements.T1.pins).filter((p) => !p.startsWith('_'));
    out.notes.exportPins = keys;
    ck('xfExportNames', same(keys, ['p1', 's1', 'p2', 's2']));
    // the same circuit as a JSON 2.0 file: the old names in post order, references rewritten, a
    // marker voltage on post 1 (old `pri2`) in state.pins
    const ren = { p1: 'pri1', s1: 'pri2', p2: 'sec1', s2: 'sec2' };
    const oldJ = JSON.parse(xfJson);
    oldJ.schema.version = '2.0';
    const p = oldJ.elements.T1.pins;
    oldJ.elements.T1.pins = Object.fromEntries(Object.keys(p).map((n) => [ren[n] || n, p[n]]));
    const fixRef = (r) => { const d = r.indexOf('.'); return d > 0 && r.slice(0, d) === 'T1' && ren[r.slice(d + 1)] ? 'T1.' + ren[r.slice(d + 1)] : r; };
    for (const e of Object.values(oldJ.elements)) for (const pin of Object.values(e.pins || {})) {
      if (Array.isArray(pin.connected_to)) pin.connected_to = pin.connected_to.map(fixRef);
      else if (typeof pin.connected_to === 'string') pin.connected_to = fixRef(pin.connected_to);
    }
    oldJ.elements.T1.state = { pins: { pri1: { v: 0 }, pri2: { v: 0.777 }, sec1: { v: 0 }, sec2: { v: 0 } } };
    const imp2 = await A('importCircuit', { doc, circuit: JSON.stringify(oldJ) });
    const vs = await values([{ name: 's1', post: 'T1.s1' }]);
    out.notes.json20 = { ok: imp2.ok, issues: codes(imp2.issues), state: vs };
    ck('xfJson20StateAlias', imp2.ok && near(vs.s1, 0.777, 1e-9));
    await R({ doc, span: '1 ms', reset: true, maxPoints: 10 });
    const v2 = await values([{ name: 's1', post: 'T1.s1' }]);
    out.notes.json20.run = v2;
    ck('xfJson20Loads', imp2.ok && near(v2.s1, 2 * 0.999, 0.02));
  } else {
    ck('xfExportNames', false);
  }
  const ratioOf = (d) => d.ok && d.data.properties.find((q) => q.key === 'ratio');
  out.notes.ratio = { Transformer: ratioOf(tT), TappedTransformer: ratioOf(tTap) };
  ck('xfRatioLabel', ratioOf(tT) && /N2\/N1/.test(ratioOf(tT).label) && ratioOf(tT).sliderMin === undefined);
  ck('tappedRatioLabel', ratioOf(tTap) && /N2\/N1/.test(ratioOf(tTap).label) && ratioOf(tTap).sliderMin === undefined);

  // (3) model names
  const modelOf = (d) => d.ok && d.data.properties.find((q) => q.key === 'model');
  const dD = await A('describeType', { type: 'Diode' });
  const dZ = await A('describeType', { type: 'ZenerDiode' });
  const dQ = await A('describeType', { type: 'TransistorNPN' });
  out.notes.choices = { diode: modelOf(dD) && modelOf(dD).choices, zener: modelOf(dZ) && modelOf(dZ).choices, npn: modelOf(dQ) && modelOf(dQ).choices };
  ck('diodeModelChoices', modelOf(dD) && Array.isArray(modelOf(dD).choices)
    && ['default', 'spice-default', '1N4148', '1N4004', '1N5711', '1N5712'].every((n) => modelOf(dD).choices.includes(n)));
  ck('zenerModelChoices', modelOf(dZ) && Array.isArray(modelOf(dZ).choices) && modelOf(dZ).choices.includes('default-zener') && !modelOf(dZ).choices.includes('default'));
  ck('transistorModelChoices', modelOf(dQ) && Array.isArray(modelOf(dQ).choices) && modelOf(dQ).choices.includes('default'));
  const diode = (id, model, x) => ({ id, type: 'Diode', start: { x, y: 0 }, end: { x: x + 4, y: 0 }, properties: model ? { model } : {} });
  await A('importCircuit', { doc, circuit: { elements: [diode('D1', '1N5711', 0)] } });
  const bad = {};
  const addBad = await A('applyEdits', { doc, edits: [{ op: 'add', element: diode('D2', 'nonexistent_model', 8) }] });
  const addIssue = (addBad.issues || [])[0] || {};
  bad.add = { codes: codes(addBad.issues), msg: addIssue.message, hint: addIssue.hint };
  ck('diodeAddUnknownModelRejected', !addBad.ok && codes(addBad.issues).includes('invalid_value') && /model/.test(bad.add.msg) && /1N4148/.test(bad.add.hint));
  const setBad = await A('applyEdits', { doc, edits: [{ op: 'set', id: 'D1', properties: { model: 'BAT54' } }] });
  bad.set = codes(setBad.issues);
  ck('diodeSetUnknownModelRejected', !setBad.ok && codes(setBad.issues).includes('invalid_value') && ((await recOf('D1')) || {}).properties.model === '1N5711');
  const setOk = await A('applyEdits', { doc, edits: [{ op: 'set', id: 'D1', properties: { model: '1N4148' } }] });
  ck('diodeSetKnownModel', setOk.ok && ((await recOf('D1')) || {}).properties.model === '1N4148');
  const impBad = await A('importCircuit', { doc, circuit: { elements: [diode('D1', 'schottky', 0)] } });
  bad.agentImport = codes(impBad.issues);
  ck('diodeAgentImportUnknownModelRejected', !impBad.ok && codes(impBad.issues).includes('invalid_value'));
  await A('importCircuit', { doc, circuit: { elements: [diode('D1', '1N4148', 0)] } });
  const jsonText = (await A('exportCircuit', { doc, format: 'json' })).data.content.replace('"1N4148"', '"xyz_model"');
  const impJson = await A('importCircuit', { doc, circuit: jsonText });
  bad.json = codes(impJson.issues);
  ck('diodeJsonImportUnknownModelRejected', jsonText.includes('xyz_model') && !impJson.ok && codes(impJson.issues).includes('invalid_value'));
  const impText = await A('importCircuit', { doc, circuit: '$ 1 0.000005 10 50 5 50\nd 0 0 64 0 2 BAT54X\n' });
  bad.text = { codes: codes(impText.issues), msg: ((impText.issues || [])[0] || {}).message };
  ck('diodeTextImportUnknownModelRejected', !impText.ok && codes(impText.issues).includes('invalid_value'));
  const impText2 = await A('importCircuit', { doc, circuit: '$ 1 0.000005 10 50 5 50\n34 vd_model 0 1e-14 0 1 0 0\nd 0 0 64 0 2 vd_model\n' });
  const dText = impText2.ok && (await A('getCircuit', { doc, detail: 'full' })).data.elements[0];
  bad.textDefined = { ok: impText2.ok, issues: codes(impText2.issues), model: dText && dText.properties.model };
  ck('diodeTextModelLineDefines', impText2.ok && dText && dText.properties.model === 'vd_model');
  // a model line after the element that uses it is "in the same content"
  const impText3 = await A('importCircuit', { doc, circuit: '$ 1 0.000005 10 50 5 50\nd 0 0 64 0 2 vd2_model\n34 vd2_model 0 1e-14 0 1 0 0\n' });
  const dText3 = impText3.ok && (await A('getCircuit', { doc, detail: 'full' })).data.elements[0];
  bad.textDefinedAfter = { ok: impText3.ok, issues: codes(impText3.issues), model: dText3 && dText3.properties.model };
  ck('diodeTextModelLineAfterDefines', impText3.ok && dText3 && dText3.properties.model === 'vd2_model' && codes(impText3.issues).length === 0);
  // accepted set = choices: a zener takes only breakdown models, a transistor no internal model
  const zc = (modelOf(dZ) && modelOf(dZ).choices) || [];
  await A('importCircuit', { doc, circuit: { elements: [{ id: 'Z1', type: 'ZenerDiode', start: { x: 0, y: 0 }, end: { x: 4, y: 0 } }] } });
  const zSet = {};
  for (const m of ['default', 'spice-default', ...zc]) zSet[m] = (await A('applyEdits', { doc, edits: [{ op: 'set', id: 'Z1', properties: { model: m } }] })).ok;
  bad.zenerSet = zSet;
  ck('zenerAcceptedEqualsChoices', zc.length > 0 && zSet.default === false && zSet['spice-default'] === false && zc.every((m) => zSet[m] === true));
  const qc = (modelOf(dQ) && modelOf(dQ).choices) || [];
  await A('importCircuit', { doc, circuit: { elements: [{ id: 'Q1', type: 'TransistorNPN', start: { x: 0, y: 0 }, end: { x: 4, y: 0 } }] } });
  const qSet = {};
  for (const m of ['xlm324v2-qpi', ...qc]) qSet[m] = (await A('applyEdits', { doc, edits: [{ op: 'set', id: 'Q1', properties: { model: m } }] })).ok;
  bad.npnSet = qSet;
  ck('transistorAcceptedEqualsChoices', qc.length > 0 && qSet['xlm324v2-qpi'] === false && qc.every((m) => qSet[m] === true));
  const qBad = await A('applyEdits', { doc, edits: [{ op: 'add', element: { id: 'Q9', type: 'TransistorNPN', start: { x: 20, y: 0 }, end: { x: 24, y: 0 }, properties: { model: 'nonexistent_bjt' } } }] });
  bad.bjt = codes(qBad.issues);
  ck('transistorUnknownModelRejected', !qBad.ok && codes(qBad.issues).includes('invalid_value'));
  out.notes.models = bad;
  const after = { diode: modelOf(await A('describeType', { type: 'Diode' })), npn: modelOf(await A('describeType', { type: 'TransistorNPN' })) };
  const typos = ['nonexistent_model', 'BAT54', 'schottky', 'xyz_model', 'BAT54X'];
  ck('noTypoRegistered', after.diode && Array.isArray(after.diode.choices) && typos.every((t) => !after.diode.choices.includes(t))
    && after.npn && Array.isArray(after.npn.choices) && !after.npn.choices.includes('nonexistent_bjt'));

  // (4) geometry: collapsed posts and replaced ends
  await A('importCircuit', { doc, circuit: { elements: [{ id: 'CT1', type: 'CustomTransformer', start: { x: 4, y: 0 } }] } });
  const ct0 = await recOf('CT1');
  const mvZero = await A('applyEdits', { doc, edits: [{ op: 'move', id: 'CT1', start: { x: 4, y: 0 }, end: { x: 4, y: -10 } }] });
  const ct1 = await recOf('CT1');
  out.notes.ctZero = { codes: codes(mvZero.issues), msg: ((mvZero.issues || [])[0] || {}).message };
  ck('ctZeroWidthRejected', !mvZero.ok && codes(mvZero.issues).includes('zero_length') && ct0 && ct1 && same(ct0.posts, ct1.posts));
  // the end the element replaced is still reported on the rejection
  ck('ctZeroWidthKeepsEndWarning', !mvZero.ok && codes(mvZero.issues)[0] === 'zero_length' && codes(mvZero.issues).includes('value_adjusted'));
  const mvEnd = await A('applyEdits', { doc, edits: [{ op: 'move', id: 'CT1', start: { x: 4, y: 0 }, end: { x: 8, y: -8 } }] });
  const adj = (mvEnd.issues || []).find((i) => i.code === 'value_adjusted');
  out.notes.ctEnd = { ok: mvEnd.ok, issues: (mvEnd.issues || []).map((i) => i.code + ': ' + i.message), end: mvEnd.ok && mvEnd.data.elements[0].end };
  ck('ctEndAdjustedWarned', mvEnd.ok && adj && /CT1\.end/.test(adj.message) && mvEnd.data.elements[0].end.y !== -8);
  const xfZero = await A('applyEdits', { doc, edits: [{ op: 'add', element: { id: 'T9', type: 'Transformer', start: { x: 20, y: 0 }, end: { x: 20, y: 5 } } }] });
  out.notes.xfZero = codes(xfZero.issues);
  ck('xfCollapsedRejected', !xfZero.ok && codes(xfZero.issues).includes('zero_length'));
  const impZero = await A('importCircuit', { doc, circuit: { elements: [{ id: 'CT1', type: 'CustomTransformer', start: { x: 4, y: 0 }, end: { x: 4, y: -10 } }] } });
  ck('ctImportCollapsedRejected', !impZero.ok && codes(impZero.issues).includes('zero_length'));
  const pot = await A('applyEdits', { doc, edits: [{ op: 'add', element: { id: 'P9', type: 'Potentiometer', start: { x: 30, y: 0 }, end: { x: 36, y: 1 } } }] });
  out.notes.pot = { ok: pot.ok, issues: (pot.issues || []).map((i) => i.code + ': ' + i.message) };
  ck('potEndAdjustedWarned', pot.ok && codes(pot.issues).includes('value_adjusted'));

  // sweep: every type added at start + defaultSize (end given) is never rejected for geometry;
  // which types replace that end; (6) TypeInfo.quantities agrees with an element `read`
  const types = (await A('listTypes', {})).data.types.map((t) => t.type);
  const sweep = { rejected: [], adjusted: [], quantityMismatch: [], noQuantities: [], typesWithQuantities: 0 };
  for (const type of types) {
    const d = await A('describeType', { type });
    if (!d.ok) continue;
    await A('importCircuit', { doc, circuit: { elements: [] } });
    const end = { x: d.data.defaultSize.dx, y: d.data.defaultSize.dy };
    if (end.x === 0 && end.y === 0) end.x = 1;
    const add = await A('applyEdits', { doc, edits: [{ op: 'add', element: { id: 'X1', type, start: { x: 0, y: 0 }, end } }] });
    if (!add.ok) { sweep.rejected.push(type + ':' + codes(add.issues).join(',')); continue; }
    if (codes(add.issues).includes('value_adjusted')) sweep.adjusted.push(type);
    const q = d.data.quantities;
    if (!Array.isArray(q)) { sweep.noQuantities.push(type); continue; }
    if (q.length) sweep.typesWithQuantities++;
    const rd = await A('read', { doc, targets: [{ element: 'X1', quantity: 'power' }] });
    if (rd.ok !== (q.length === 3)) sweep.quantityMismatch.push(type + ':' + q.length + '/' + rd.ok);
  }
  out.notes.sweep = sweep;
  ck('sweepNoGeometryRejection', sweep.rejected.every((r) => !/zero_length/.test(r)));
  ck('sweepAdjustedOnlyDerivedEnds', sweep.adjusted.every((t) => ['CustomTransformer'].includes(t)));
  ck('quantitiesListed', sweep.noQuantities.length === 0 && sweep.typesWithQuantities > 50);
  ck('quantitiesMatchRead', sweep.quantityMismatch.length === 0);
  ck('quantitiesTransformer', same(tT.data.quantities, []) && same((await A('describeType', { type: 'Resistor' })).data.quantities, ['voltage', 'current', 'power']));

  // (5) render: printable colours (white background), value labels inside the image
  const session0 = await s.eval('CircuitJS1Agent.debugSessionState()');
  await A('importCircuit', { doc, circuit: { elements: [
    { id: 'V1', type: 'VoltageSourceDC', start: { x: 0, y: 4 }, end: { x: 0, y: 0 }, properties: { max_voltage: '100 mV' } },
    { id: 'R1', type: 'Resistor', start: { x: 0, y: 0 }, end: { x: 4, y: 0 } },
    { id: 'W1', type: 'Wire', start: { x: 4, y: 0 }, end: { x: 4, y: 4 } },
    { id: 'W2', type: 'Wire', start: { x: 4, y: 4 }, end: { x: 0, y: 4 } },
    { id: 'G1', type: 'Ground', start: { x: 0, y: 4 }, end: { x: 0, y: 5 } }] } });
  const inkOf = async (b64) => s.eval(`(async () => {
    const img = new Image(); img.src = 'data:image/png;base64,' + ${JSON.stringify(b64)}; await img.decode();
    const c = document.createElement('canvas'); c.width = img.width; c.height = img.height;
    const x = c.getContext('2d'); x.drawImage(img, 0, 0);
    const d = x.getImageData(0, 0, c.width, c.height).data;
    let minX = 1e9, minY = 1e9, maxX = -1, maxY = -1, ink = 0;
    for (let y = 0; y < c.height; y++) for (let i = 0; i < c.width; i++) {
      const o = (y * c.width + i) * 4;
      if (d[o] < 250 || d[o + 1] < 250 || d[o + 2] < 250) { ink++; minX = Math.min(minX, i); maxX = Math.max(maxX, i); minY = Math.min(minY, y); maxY = Math.max(maxY, y); }
    }
    return { w: c.width, h: c.height, bg: Array.from(d.slice(0, 4)), ink, minX, minY, maxX, maxY };
  })()`);
  const rp = await AA('render', { doc });
  const px = rp.ok ? await inkOf(rp.data.content) : null;
  out.notes.render = px;
  ck('renderPrintableWhite', px && same(px.bg, [255, 255, 255, 255]) && px.ink > 100);
  ck('renderLabelsNotCropped', px && px.minX >= 8 && px.minY >= 8 && px.maxX <= px.w - 9 && px.maxY <= px.h - 9);
  ck('renderSessionUnchanged', (await s.eval('CircuitJS1Agent.debugSessionState()')) === session0);
  // the circuit of the JFET verify run (outside the repository): re-rendered next to the old image
  const conv = '/tmp/circuitjs1_desktop/jfet_dcdc/converter.json';
  if (fs.existsSync(conv)) {
    const ci = await A('importCircuit', { doc, circuit: fs.readFileSync(conv, 'utf8') });
    const cr = ci.ok ? await AA('render', { doc, scale: 2 }) : null;
    if (cr && cr.ok) {
      fs.writeFileSync(path.join(path.dirname(conv), 'converter_fixed.png'), Buffer.from(cr.data.content, 'base64'));
      out.notes.converter = { size: [cr.data.width, cr.data.height], ink: await inkOf(cr.data.content) };
    } else {
      out.notes.converter = { import: codes(ci.issues), render: cr && codes(cr.issues) };
    }
  }

  await A('closeDocument', { doc, discardChanges: true });
  ck('visibleTabUnchanged', same(vis0, await s.call('visibleTab')));

  // user loads are unchanged: an unknown model name loads with the fallback model, as before
  const saved = await s.call('exportText');
  const userText = await s.call('importText', '$ 1 0.000005 10 50 5 50\nd 0 0 64 0 2 user_text_model\n');
  const userJson = JSON.parse(xfJson || '{"schema":{"format":"circuitjs","version":"2.1"},"elements":{}}');
  userJson.elements = { D1: { type: 'Diode', properties: { model: 'user_json_model' }, pins: { anode: { position: { x: 0, y: 0 } }, cathode: { position: { x: 64, y: 0 } } } } };
  const userJsonCount = await s.call('importJson', JSON.stringify(userJson));
  const userExport = await s.call('exportJson');
  out.notes.userLoads = { text: userText, json: userJsonCount };
  ck('userLoadsUnchanged', userText === 1 && userJsonCount === 1 && /user_json_model/.test(userExport));
  await s.call('importText', saved);

  // Import-scaling fix round (2026-10-05). SP_AGA §06_01 item 31: relay coils and SPICE-style
  // controlled sources receive the element list before their stamp again — the three examples
  // stepped into an exception in stamp() before.
  const saved2 = await s.call('exportText');
  out.notes.parentList = {};
  for (const name of ['latchingrelay.txt', 'relays.txt', 'ujtosc.txt']) {
    const t = await s.call('fetchText', '/circuitjs1/circuits/' + name);
    const r = await s.eval(`(() => { CircuitJS1.importCircuit(${JSON.stringify(t)}, false); CircuitJS1.setSimRunning(false);
      for (let k = 0; k < 50; k++) CircuitJS1.stepSimulation(); const i = CircuitJS1.getSimInfo(); return { stop: i.stopMessage || null, time: i.time }; })()`);
    out.notes.parentList[name] = r;
    ck('stepsWithoutStop_' + name.replace('.txt', ''), !r.stop && r.time > 0);
  }
  await s.call('importText', saved2);
  const d2 = (await A('createDocument', { title: 'Deferred stamp' })).data.doc;
  // §06_01 item 32: an agent mutation leaves the matrix stamp for later; what the stamp shows
  // in the drawing (a current source's value) is set by the node analysis (applyStampedValues)
  await A('importCircuit', { doc: d2, circuit: { elements: [
    { id: 'R1', type: 'Resistor', start: { x: 8, y: 0 }, end: { x: 8, y: 4 } },
    { id: 'W1', type: 'Wire', start: { x: 0, y: 0 }, end: { x: 8, y: 0 } },
    { id: 'W2', type: 'Wire', start: { x: 0, y: 4 }, end: { x: 8, y: 4 } },
    { id: 'G1', type: 'Ground', start: { x: 8, y: 4 }, end: { x: 8, y: 5 } }] } });
  const addI = await A('applyEdits', { doc: d2, edits: [{ op: 'add', element: { id: 'I1', type: 'CurrentSource', start: { x: 0, y: 4 }, end: { x: 0, y: 0 } } }] });
  const layI = await A('checkLayout', { doc: d2, includeBoxes: true });
  const iTexts = ((layI.data && layI.data.boxes) || []).filter((b) => (b.elements || [b.element]).includes('I1') || /mA/.test(b.text)).map((b) => b.text);
  const svgI = await AA('render', { doc: d2, format: 'svg' });
  out.notes.stampedValues = { add: addI.ok, layoutTexts: iTexts, svgHasMa: !!(svgI.data && /10 ?mA/.test(svgI.data.content)) };
  ck('stampedValueLayout', addI.ok && iTexts.some((t) => /10 ?mA/.test(t)));
  ck('stampedValueRender', svgI.ok && /10 ?mA/.test(svgI.data.content));
  // a stamp that throws (a SPICE-style CCCS without the voltage sources it controls from) is
  // still reported by simControl run and drawn by render (both stamp what a mutation left)
  await A('importCircuit', { doc: d2, circuit: { elements: [{ id: 'F1', type: 'CCCS', start: { x: 0, y: 0 }, end: { x: 4, y: 0 }, flags: 2 }] } });
  const runF = await A('simControl', { doc: d2, action: 'run' });
  const svgF = await AA('render', { doc: d2, format: 'svg' });
  await A('simControl', { doc: d2, action: 'stop' });
  out.notes.deferredStampStop = { running: runF.data && runF.data.running, issues: codes(runF.issues), svgRed: !!(svgF.data && /#ff0000/i.test(svgF.data.content)) };
  ck('deferredStampStopOnRun', runF.ok && runF.data.running === false && codes(runF.issues).includes('solver_stop'));
  ck('deferredStampStopOnRender', svgF.ok && /#ff0000/i.test(svgF.data.content));
  await A('closeDocument', { doc: d2, discardChanges: true });
  // Review round P2: a read-only subcircuit build from a document whose stamp analyseNodes left
  // for later keeps that allocation: the visible document's onanalyze hook does not fire again
  // at the next getConnectivity (one page task, so no frame runs between)
  const d3 = (await A('createDocument', { title: 'P2 target' })).data.doc;
  const savedVis = await s.call('exportText');
  const p2 = await s.eval(`(() => {
    const call = (op, a) => JSON.parse(CircuitJS1Agent.call(op, JSON.stringify(a)));
    CircuitJS1.setSimRunning(false);
    const V = call('listDocuments', {}).data.documents.find((d) => d.active).doc;
    let n = 0; CircuitJS1.onanalyze = function () { n++; };
    const imp = call('importCircuit', { doc: V, circuit: { elements: [
      { id: 'LIN', type: 'LabeledNode', start: { x: 0, y: 0 }, end: { x: 0, y: -1 }, properties: { label: 'in' } },
      { id: 'R1', type: 'Resistor', start: { x: 0, y: 0 }, end: { x: 4, y: 0 } },
      { id: 'LOUT', type: 'LabeledNode', start: { x: 4, y: 0 }, end: { x: 4, y: -1 }, properties: { label: 'out' } },
      { id: 'R2', type: 'Resistor', start: { x: 4, y: 0 }, end: { x: 4, y: 4 } },
      { id: 'G1', type: 'Ground', start: { x: 4, y: 4 }, end: { x: 4, y: 5 } }] } });
    const n1 = n;
    const def = call('applyEdits', { doc: ${JSON.stringify(d3)}, edits: [{ op: 'defineModel', model: { kind: 'subcircuit', name: 'vd-p2-sub', source: { doc: V } } }] });
    const conn = call('getConnectivity', { doc: V });
    const n2 = n;
    CircuitJS1.onanalyze = null;
    return { imp: imp.ok, def: def.ok, defIssues: def.issues, conn: conn.ok, n1, n2 };
  })()`);
  out.notes.p2CompositeKeepsAllocation = p2;
  // the import itself analyses before and after the change (n1 = 2); nothing may follow
  ck('compositeBuildKeepsAllocation', p2.imp && p2.def && p2.conn && p2.n1 >= 1 && p2.n2 === p2.n1);
  await A('closeDocument', { doc: d3, discardChanges: true });
  // Review round P3: a stamp exception in a free-running frame (outside the per-element guard,
  // forced by debugFailNextStamp) marks the analysis failed, so getConnectivity reports
  // analysis_failed without a getDiagnostics or read call first
  await A('importCircuit', { circuit: { elements: [
    { id: 'R1', type: 'Resistor', start: { x: 0, y: 0 }, end: { x: 4, y: 0 } },
    { id: 'G1', type: 'Ground', start: { x: 0, y: 0 }, end: { x: 0, y: 1 } },
    { id: 'G2', type: 'Ground', start: { x: 4, y: 0 }, end: { x: 4, y: 1 } }] } });
  await s.eval(`CircuitJS1Agent.debugFailNextStamp(); CircuitJS1.setSimRunning(true); true`);
  await sleep(500);
  await s.eval(`CircuitJS1.setSimRunning(false); true`);
  const p3 = await A('getConnectivity', {});
  out.notes.p3FrameStampFailure = { ok: p3.ok, analysed: p3.data && p3.data.analysed, issues: codes(p3.data && p3.data.issues) };
  ck('frameStampFailureIsAnalysisFailed', p3.ok && p3.data.analysed === false && codes(p3.data.issues).includes('analysis_failed'));
  await s.call('importText', savedVis);
  // the forced failure went to the global handler (shown and logged); it is not a page exception
  ck('noExceptions', s.exceptions.length === exMark);
  const failed = Object.keys(out.checks).filter((k) => !out.checks[k]);
  fs.writeFileSync(path.join(OUT_DIR, 'verify_defects.json'), JSON.stringify(out, null, 2));
  report('AG.verify_defects', failed.length === 0, { checks: Object.keys(out.checks).length, failed });
}

// solver_defects: the defects found by the sparse-solver spike (docs/sparse-solver.spike.md,
// 2026-10-05), on a background document. Wraps the GWT-emitted CircuitMath.lu_factor and
// CircuitSimulator.stampCircuit (draftCompile names) to count and to force failures.
// (1) An agent run with reset: true stamps (and, for a linear circuit, LU-factors) once, not twice.
// (2) A nonlinear circuit whose LU factorization fails again with the singular-matrix stabilizers
// active reports singular_matrix (SP_SIM_02 SINGULAR: escalate) instead of re-factoring the matrix
// lu_factor has already overwritten. (3) The singularity message names the unknown of the failed
// reduced column through the reduction's column map (SP_SLV_01_09), not the full-system index.
async function scenarioSolverDefects(s) {
  const out = { checks: {}, notes: {} };
  const ck = (name, cond) => { out.checks[name] = !!cond; return !!cond; };
  const A = (op, args) => s.call('agentCall', op, args);
  const R = (args) => s.call('agentAsync', 'run', args, 60000);
  const codes = (list) => (list || []).map((i) => i.code);
  await resetApp(s);
  const exMark = s.exceptions.length;
  const vis0 = await s.call('visibleTab');
  const installed = await s.eval(`(() => {
    const w = [...document.querySelectorAll('iframe')].map((f) => { try { return f.contentWindow; } catch (e) { return null; } }).find((x) => x && x.clcc);
    if (!w) return 'no GWT iframe';
    const G = w.clcc;
    const F = 'com_lushprojects_circuitjs1_client_CircuitMath_lu_1factor___3_3DI_3IZ';
    const key = Object.keys(G).find((k) => k.startsWith('com_lushprojects_circuitjs1_client_CircuitSimulator_CircuitSimulator__'));
    const P = key && G[key].prototype;
    const SC = 'package_private$com_lushprojects_circuitjs1_client$stampCircuit__V';
    if (typeof G[F] !== 'function' || !P || typeof P[SC] !== 'function') return 'GWT names not found';
    window.__solverProbe = { factor: 0, stamp: 0, failNext: 0, failCol: -1 };
    if (!G[F].__probe) {
      // the wrappers read the probe at call time, so a re-run of the scenario in the same page counts;
      // a forced failure reports failCol as the failed (reduced) column, as a real one sets lastLuFail*
      const f0 = G[F];
      const C = 'com_lushprojects_circuitjs1_client_CircuitMath_lastLuFail';
      G[F] = function (a, n, ip) {
        const st = window.__solverProbe; st.factor++;
        if (st.failNext > 0) { st.failNext--; G[C + 'Column'] = st.failCol; G[C + 'Row'] = st.failCol; G[C + 'PivotAbs'] = 0; return false; }
        return f0(a, n, ip);
      };
      G[F].__probe = true;
      const s0 = P[SC];
      P[SC] = function () { window.__solverProbe.stamp++; return s0.call(this); };
    }
    return 'ok';
  })()`);
  out.notes.installed = installed;
  if (!ck('probeInstalled', installed === 'ok')) {
    fs.writeFileSync(path.join(OUT_DIR, 'solver_defects.json'), JSON.stringify(out, null, 2));
    report('AG.solver_defects', false, { checks: 1, failed: ['probeInstalled'] });
    return;
  }
  const probe = (expr) => s.eval(`(() => { const st = window.__solverProbe; ${expr} })()`);
  const doc = (await A('createDocument', { title: 'Solver defects' })).data.doc;

  // (1) linear divider: one stamp and one factorization for a reset run
  await A('importCircuit', { doc, circuit: { elements: [
    { id: 'V1', type: 'Rail', start: { x: 0, y: 0 }, end: { x: 0, y: -2 }, properties: { max_voltage: '5 V' } },
    { id: 'R1', type: 'Resistor', start: { x: 0, y: 0 }, end: { x: 4, y: 0 }, properties: { resistance: '1 kOhm' } },
    { id: 'R2', type: 'Resistor', start: { x: 4, y: 0 }, end: { x: 4, y: 4 }, properties: { resistance: '1 kOhm' } },
    { id: 'G1', type: 'Ground', start: { x: 4, y: 4 }, end: { x: 4, y: 5 } }] } });
  await probe('st.factor = 0; st.stamp = 0; st.failNext = 0;');
  const r1 = await R({ doc, span: '1 ms', reset: true, probes: [{ post: 'R1.#1' }] });
  const c1 = await probe('return { factor: st.factor, stamp: st.stamp };');
  out.notes.resetRun = { ok: r1.ok, reason: r1.data && r1.data.reason, issues: (r1.issues || []).map((i) => i.message), final: r1.ok && r1.data.probes[0].stats.final, ...c1 };
  ck('resetRunStampsOnce', r1.ok && r1.data.reason === 'span_reached' && c1.stamp === 1 && c1.factor === 1);
  ck('resetRunReading', r1.ok && Math.abs(r1.data.probes[0].stats.final - 2.5) < 1e-6);

  // (2) diode clamp: the first failed factorization enables the stabilizers and re-stamps; the
  // second one (stabilizers active) must be reported, not retried on the overwritten matrix
  await A('importCircuit', { doc, circuit: { elements: [
    { id: 'V1', type: 'Rail', start: { x: 0, y: 0 }, end: { x: 0, y: -2 }, properties: { max_voltage: '5 V' } },
    { id: 'R1', type: 'Resistor', start: { x: 0, y: 0 }, end: { x: 4, y: 0 }, properties: { resistance: '1 kOhm' } },
    { id: 'D1', type: 'Diode', start: { x: 4, y: 0 }, end: { x: 4, y: 4 } },
    { id: 'G1', type: 'Ground', start: { x: 4, y: 4 }, end: { x: 4, y: 5 } }] } });
  await R({ doc, span: '10 us', reset: true });
  await probe('st.factor = 0; st.stamp = 0; st.failNext = 2;');
  let r2, c2;
  try {
    r2 = await R({ doc, span: '10 us', reset: true });
  } finally {
    // never leak forced failures into later scenarios on this page
    c2 = await probe('const r = { factor: st.factor, stamp: st.stamp, left: st.failNext }; st.failNext = 0; return r;');
  }
  const d2 = await A('getDiagnostics', { doc });
  const ev = codes(d2.data && d2.data.events);
  out.notes.singularRetry = { ok: r2.ok, reason: r2.data && r2.data.reason, issues: codes(r2.issues), events: ev, ...c2 };
  ck('singularForcedTwice', c2.left === 0);
  ck('singularReportedNotRetried', ev.includes('singular_matrix') || codes(r2.issues).includes('singular_matrix'));
  // (3) the singularity message names the unknown of the failed reduced column. With the
  // stabilizers active only a row with a single unknown reduces: node X, tied to ground by R2 alone,
  // becomes a constant, so the 4 unknowns (3 nodes, the rail's source current) reduce to 3 and
  // reduced column 2 is the source current (the full index 2 would be a node voltage)
  await A('importCircuit', { doc, circuit: { elements: [
    { id: 'V1', type: 'Rail', start: { x: 0, y: 0 }, end: { x: 0, y: -2 }, properties: { max_voltage: '5 V' } },
    { id: 'R1', type: 'Resistor', start: { x: 0, y: 0 }, end: { x: 4, y: 0 }, properties: { resistance: '1 kOhm' } },
    { id: 'D1', type: 'Diode', start: { x: 4, y: 0 }, end: { x: 4, y: 4 } },
    { id: 'G1', type: 'Ground', start: { x: 4, y: 4 }, end: { x: 4, y: 5 } },
    { id: 'R2', type: 'Resistor', start: { x: 8, y: 0 }, end: { x: 8, y: 4 }, properties: { resistance: '1 kOhm' } },
    { id: 'G2', type: 'Ground', start: { x: 8, y: 4 }, end: { x: 8, y: 5 } }] } });
  await R({ doc, span: '10 us', reset: true });
  const logMark = ((await A('getDiagnostics', { doc, log: { since: 0, limit: 1 } })).data.log || {}).cursor || 0;
  await probe('st.failNext = 2; st.failCol = 2;');
  try {
    await R({ doc, span: '10 us', reset: true });
  } finally {
    await probe('st.failNext = 0; st.failCol = -1;');
  }
  const logs = (((await A('getDiagnostics', { doc, log: { since: logMark, limit: 500 } })).data.log || {}).entries || []).map((e) => e.text);
  const pivotLine = logs.find((t) => /pivot failed with stabilizers at col=2/.test(t)) || null;
  out.notes.variable = { pivotLine };
  ck('singularVariableMapped', pivotLine && /var=voltageSourceCurrent\(vs=0/.test(pivotLine));
  // after the forced failures the circuit recovers and solves again
  const r3 = await R({ doc, span: '1 ms', reset: true, probes: [{ post: 'D1.#0' }] });
  out.notes.recovered = { issues: (r3.issues || []).map((i) => i.message), ok: r3.ok, reason: r3.data && r3.data.reason, final: r3.ok && r3.data.probes[0].stats.final };
  ck('recoversAfterReset', r3.ok && r3.data.reason === 'span_reached' && r3.data.probes[0].stats.final > 0.3 && r3.data.probes[0].stats.final < 0.9);

  await A('closeDocument', { doc, discardChanges: true });
  ck('visibleTabUnchanged', JSON.stringify(vis0) === JSON.stringify(await s.call('visibleTab')));
  ck('noExceptions', s.exceptions.length === exMark);
  const failed = Object.keys(out.checks).filter((k) => !out.checks[k]);
  fs.writeFileSync(path.join(OUT_DIR, 'solver_defects.json'), JSON.stringify(out, null, 2));
  report('AG.solver_defects', failed.length === 0, { checks: Object.keys(out.checks).length, failed });
}

// ---------------------------------------------------------------- solver_corpus (PL_SLV P1, SP_SLV_05_02)
// Not in the default run. Bit identity of the solver over the example corpus (CIRCUITS, default
// all; noise-source examples left out): on a background document every example is imported and run
// with reset for SOLVER_CORPUS_STEPS (default 200) × its maximum time step, then every net voltage
// is read. SOLVER_CORPUS=record runs each example twice and writes OUT_DIR/solver_corpus.json
// (examples whose two runs differ are excluded as nondeterministic); the default mode compares
// with tests/live/fixtures/solver_corpus.json exactly (numbers through their shortest round-trip
// text) and reports per example identical / differs (max abs and relative difference) and the
// reduced size m once Diagnostics has the solver block. A differing example is run up to twice
// more (latches settle at random after gate oscillation); one exact match counts as identical. A difference fails for m ≤
// SOLVER_DENSE_MAX (64, the dense path in AUTO), for an unknown m, and for every example with
// SOLVER_CORPUS_STRICT=1 (the runtime rollback check with the session default Dense).
const SOLVER_CORPUS_FIXTURE = path.join(HERE, 'fixtures', 'solver_corpus.json');
const SOLVER_DENSE_MAX = 64;

async function solverCorpusProbe(doc, text, steps) {
  const H = window.__H;
  const A = (op, a) => H.agentCall(op, a);
  const imp = A('importCircuit', { doc, circuit: text });
  if (!imp.ok) return { error: 'import: ' + (imp.issues || []).map((i) => i.code).join(',') };
  const st = A('simControl', { doc, action: 'stop' });
  const maxStep = st.data && st.data.timeStep ? st.data.timeStep.max : null;
  if (!(maxStep > 0)) return { error: 'no maxTimeStep' };
  const run = await H.agentAsync('run', { doc, span: steps * maxStep, reset: true, budgetMs: 120000 }, 180000);
  const reason = run && run.data ? run.data.reason : (run && run.timeout ? 'timeout' : 'failed: ' + (run && run.issues ? run.issues.map((i) => i.code).join(',') : 'no result'));
  const nets = ((A('getConnectivity', { doc }).data || { nets: [] }).nets || []).map((n) => n.name);
  const v = {};
  for (let i = 0; i < nets.length; i += 100) {
    const r = A('read', { doc, targets: nets.slice(i, i + 100).map((n) => ({ net: n })) });
    if (!r.ok) return { error: 'read: ' + (r.issues || []).map((x) => x.code).join(','), run: reason };
    for (const x of r.data.values) v[x.name] = x.value;
  }
  const d = A('getDiagnostics', { doc });
  const sv = d.data && d.data.solver;
  return { run: reason, t: run && run.data ? run.data.tEnd : null, maxStep, nets: v, m: sv ? sv.size : null, path: sv ? sv.path : null };
}

async function scenarioSolverCorpus(s) {
  const record = process.env.SOLVER_CORPUS === 'record';
  const strict = !!process.env.SOLVER_CORPUS_STRICT;
  const fixture = record ? null : JSON.parse(fs.readFileSync(process.env.SOLVER_CORPUS_FIXTURE || SOLVER_CORPUS_FIXTURE, 'utf8'));
  // compare mode runs the fixture's span
  const steps = fixture ? fixture.steps : +(process.env.SOLVER_CORPUS_STEPS || 200);
  const A = (op, args) => s.call('agentCall', op, args);
  const probe = (doc, text) => s.eval(`(${solverCorpusProbe.toString()})(${JSON.stringify(doc)}, ${JSON.stringify(text)}, ${steps})`);
  const only = process.env.CIRCUITS && process.env.CIRCUITS !== 'all' ? process.env.CIRCUITS.split(',') : null;
  const list = record ? (only || listAllCircuits()) : Object.keys(fixture.examples).filter((n) => !only || only.includes(n));
  const unknownNames = !record && only ? only.filter((n) => !fixture.examples[n]) : [];
  if (!list.length || unknownNames.length) {
    // an empty comparison must never pass
    report('SLV.solver_corpus', false, { error: !list.length ? 'nothing to compare' : 'not in the fixture', unknownNames });
    return;
  }
  // per number with Object.is: -0, NaN and ±Infinity are told apart (JSON text would not)
  const sameVals = (a, b) => {
    const ka = Object.keys(a), kb = Object.keys(b);
    return ka.length === kb.length && ka.every((k) => Object.prototype.hasOwnProperty.call(b, k) && Object.is(a[k], b[k]));
  };
  await resetApp(s);
  const exMark = s.exceptions.length;
  const t0 = Date.now();
  const doc = (await A('createDocument', { title: 'Solver corpus' })).data.doc;
  if (record) {
    const outFile = path.join(OUT_DIR, 'solver_corpus.json');
    const rec = { created: new Date().toISOString(), site: SITE_DIR, steps, examples: {}, excluded: {} };
    for (const name of list) {
      const text = await s.eval(`__H.fetchText('/circuitjs1/circuits/' + ${JSON.stringify(name)})`);
      if (hasNoiseSource(text)) { rec.excluded[name] = 'noise source'; continue; }
      const r1 = await probe(doc, text);
      if (r1.error) { rec.excluded[name] = r1.error; continue; }
      // a run cut by its wall-clock budget ends at a time that depends on the machine
      if (r1.run === 'budget_exhausted') { rec.excluded[name] = 'budget_exhausted'; continue; }
      const r2 = await probe(doc, text);
      if (r2.error || r1.run !== r2.run || r1.t !== r2.t || !sameVals(r1.nets, r2.nets)) { rec.excluded[name] = 'nondeterministic'; continue; }
      rec.examples[name] = { run: r1.run, t: r1.t, maxStep: r1.maxStep, nets: r1.nets };
    }
    fs.writeFileSync(outFile, JSON.stringify(rec, null, 1) + '\n');
    await A('closeDocument', { doc, discardChanges: true });
    report('SLV.solver_corpus', s.exceptions.length === exMark, { mode: 'record', file: outFile, examples: Object.keys(rec.examples).length,
      excluded: Object.keys(rec.excluded).length, s: Math.round((Date.now() - t0) / 1000) });
    return;
  }
  const per = {}; const errors = {}; const failing = []; let identical = 0, allowed = 0;
  for (const name of list) {
    const base = fixture.examples[name];
    const text = await s.eval(`__H.fetchText('/circuitjs1/circuits/' + ${JSON.stringify(name)})`);
    let r = await probe(doc, text);
    if (r.error) { errors[name] = r.error; failing.push(name); continue; }
    // gates that oscillate and op-amps draw from an unseeded generator (RandomUtils), so a latch
    // may settle either way: a differing example is run again, and one exact match counts
    let reruns = 0;
    while (reruns < 2 && !(r.run === base.run && r.t === base.t && sameVals(r.nets, base.nets))) {
      const again = await probe(doc, text);
      reruns++;
      if (!again.error) r = again;
    }
    let maxAbs = 0, maxRel = 0, missing = 0;
    for (const k of new Set([...Object.keys(base.nets), ...Object.keys(r.nets)])) {
      const a = base.nets[k], b = r.nets[k];
      if (typeof a !== 'number' || typeof b !== 'number') { if (a !== b) missing++; continue; }
      const d = Math.abs(a - b);
      if (d > maxAbs) maxAbs = d;
      const rel = d / Math.max(1e-12, Math.abs(a));
      if (rel > maxRel) maxRel = rel;
    }
    const same = r.run === base.run && r.t === base.t && sameVals(r.nets, base.nets);
    per[name] = { result: same ? 'identical' : 'differs', m: r.m, path: r.path, ...(reruns ? { reruns } : {}), ...(same ? {} : { maxAbs, maxRel, missing, run: [base.run, r.run], t: [base.t, r.t] }) };
    if (same) identical++;
    else if (!strict && r.m != null && r.m > SOLVER_DENSE_MAX) allowed++;
    else failing.push(name);
  }
  await A('closeDocument', { doc, discardChanges: true });
  fs.writeFileSync(path.join(OUT_DIR, 'solver_corpus_compare.json'), JSON.stringify({ fixture: process.env.SOLVER_CORPUS_FIXTURE || SOLVER_CORPUS_FIXTURE, strict, per, errors }, null, 1));
  report('SLV.solver_corpus', failing.length === 0 && s.exceptions.length === exMark, { mode: 'compare', strict, compared: list.length, identical,
    differsAllowed: allowed, failing: failing.slice(0, 15), failingCount: failing.length, errors: Object.keys(errors).length,
    s: Math.round((Date.now() - t0) / 1000), details: path.join(OUT_DIR, 'solver_corpus_compare.json') });
}

// 555square.txt output frequency and duty cycle measured on the pre-fix build (HEAD 942a7ae)
const TIMER_SQUARE_HZ = 239.521;
const TIMER_SQUARE_DUTY = 0.507567;

// In-page fake of the desktop file system (nw.require('fs'|'path'|'buffer')) for openFile/saveFile
// checks in headless Chromium: reads and writes go to window.__fakeFiles.
function fakeFsScript(files) {
  return `(() => {
    const files = window.__fakeFiles = ${JSON.stringify(files)};
    const fds = {}; let nfd = 3;
    const err = (p, code) => { const e = new Error((code || 'ENOENT') + ': ' + p); e.code = code || 'ENOENT'; return e; };
    const loose = (base) => new Proxy(base, { get: (t, k) => (k in t ? t[k] : () => undefined) });
    const fs = loose({
      realpathSync: (p) => { if (!(p in files)) throw err(p); return p; },
      statSync: (p) => { if (!(p in files)) throw err(p); return { isFile: () => true, size: files[p].length, mode: 420 }; },
      lstatSync: (p) => { throw err(p); },
      readFileSync: (p) => { if (!(p in files)) throw err(p); const t = files[p]; return { length: t.length, toString: () => t }; },
      openSync: (p, flag) => { if (p in files && flag === 'wx') throw err(p, 'EEXIST'); const fd = nfd++; fds[fd] = { p, data: '' }; return fd; },
      fchmodSync: () => {}, fsyncSync: () => {},
      writeSync: (fd, buf, off, len) => { fds[fd].data += buf.s.substr(off, len); return len; },
      closeSync: (fd) => { if (fds[fd]) { files[fds[fd].p] = fds[fd].data; delete fds[fd]; } },
      renameSync: (a, b) => { files[b] = files[a]; delete files[a]; },
      unlinkSync: (p) => { delete files[p]; },
    });
    const path = loose({ resolve: (p) => p, isAbsolute: (p) => p.startsWith('/'), basename: (p) => p.replace(/^.*\\//, ''), join: (...a) => a.join('/'), dirname: (p) => p.replace(/\\/[^/]*$/, '') });
    const buffer = { Buffer: { from: (s) => ({ length: s.length, s }) } };
    window.__savedNw = window.nw;
    window.nw = { require: (m) => (m === 'fs' ? fs : m === 'path' ? path : m === 'buffer' ? buffer : undefined) };
  })()`;
}
const FAKE_FS_RESTORE = 'window.nw = window.__savedNw; delete window.__savedNw; if (window.nw === undefined) delete window.nw;';

// Opens the app in a side page, runs fn(s2) and closes the page. The page is served as
// http://localhost:<port> — another origin than the main page (127.0.0.1), so its local storage is
// its own: no session restore of the main page's tabs (whose model lines would define the models)
// and no stored subcircuits; a fresh session with its own model catalogues and clipboard.
async function withSidePage(s, fn) {
  let target = null; let cdp2 = null;
  const base = s.baseUrl.replace('//127.0.0.1:', '//localhost:');
  try {
    await s.cdp.send('Browser.grantPermissions', { permissions: ['clipboardReadWrite', 'clipboardSanitizedWrite'], origin: base }).catch(() => {});
    target = await (await fetch(`http://127.0.0.1:${s.cdpPort}/json/new?${base}/circuitjs.html`, { method: 'PUT' })).json();
    cdp2 = new CDP(target.webSocketDebuggerUrl); await cdp2.open();
    const s2 = new Session(cdp2, base);
    await cdp2.send('Runtime.enable'); await cdp2.send('Page.enable');
    // headless: the side page counts as focused (the Clipboard API reads only in a focused document)
    await cdp2.send('Emulation.setFocusEmulationEnabled', { enabled: true }).catch(() => {});
    await waitFor(() => s2.eval(`typeof CircuitJS1 !== 'undefined' && typeof CircuitJS1.getElementCount === 'function' && typeof CircuitJS1Agent !== 'undefined'`), LOAD_TIMEOUT_MS, 'side page');
    await s2.eval(`(${pageHelpers.toString()})()`);
    await waitFor(() => s2.call('ready'), 20000, 'side page ready');
    await sleep(1000);
    await cdp2.send('Page.bringToFront').catch(() => {});
    return await fn(s2);
  } finally {
    if (cdp2) cdp2.close();
    if (target) await fetch(`http://127.0.0.1:${s.cdpPort}/json/close/${target.id}`).catch(() => {});
    await s.cdp.send('Page.bringToFront').catch(() => {});
  }
}

// json_models: the JSON v2 `models` section, format 2.2 (PL_AGA Phase 14; SP_AGA_03_12, §02_03 JSON
// text models, §03_04, §05_01 rows "exportCircuit / saveFile json models section", "user load (JSON)
// invalid models entry", "user paste (JSON) models in a paste", "user import (JSON) subcircuits
// only", §06_01 items 22-23) and the in-circuit scope settings (Scope element property `scope`).
// Main page: exportCircuit writes 2.2 with `models` = the getCircuit models (dependencies first) and
// no `models` key without user models; saveFile json (in-page fake file system) writes it; agent
// importCircuit of JSON text: identical entries accepted (catalogues unchanged), a differing entry
// `name_taken` with the openFile hint, `kind:"foo"`, `from`, `source`, a subcircuit without pins and
// one with an unknown inner model `invalid_value`, a new entry defined and used, a rejected import
// registers nothing; user load of a file with an invalid entry loads (console message, the element
// falls back, no alert); openFile of it reports value_adjusted; a rejected openFile restores the
// entry its models section overwrote; in-circuit scope (403) lines survive text -> JSON -> text.
// Side page (fresh session): openFile of the saved file defines its model; "Import subcircuits only"
// defines the subcircuit and its diode dependency only; a Ctrl+V paste of a JSON 2.2 text defines
// its model, one undo removes the pasted elements and keeps the model; a user JSON load defines
// diode, transistor and logic models with the records of the writing session; every bundled example
// with model lines keeps them through JSON into the fresh session.
async function scenarioJsonModels(s) {
  const out = { checks: {}, notes: {} };
  const ck = (name, cond) => { out.checks[name] = !!cond; return !!cond; };
  const A = (op, args) => s.call('agentCall', op, args);
  const has = (r, code, re) => (r.issues || []).some((i) => i.code === code && (!re || re.test(i.message + ' ' + (i.hint || ''))));
  const issues = (r) => (r.issues || []).map((i) => i.code + ': ' + i.message);
  const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
  const strip = (m) => { const c = Object.assign({}, m); delete c.usedBy; return c; };
  const catalogueOf = async (call) => JSON.stringify((await call('listModels', {})).data.models.map(strip));
  const catalogue = () => catalogueOf(A);
  const recordOf = async (call, kind, name) => { const r = await call('listModels', { kind, name }); return r.ok ? strip(r.data.models[0]) : null; };
  const record = (kind, name) => recordOf(A, kind, name);
  const define = (doc, edits) => A('applyEdits', { doc, edits });
  const label = (id, text, x, y, ex, ey) => ({ id, type: 'LabeledNode', start: { x, y }, end: { x: ex, y: ey }, properties: { label: text } });
  const exportJson = async (doc) => (await A('exportCircuit', { doc, format: 'json' })).data.content;
  const docText = async (doc) => (await A('exportCircuit', { doc, format: 'text' })).data.content;
  const modelLines = (t) => String(t).split('\n').filter((l) => /^(34|32|!|\.) /.test(l));
  const rejections = {};
  const rejects = async (name, doc, fn, code, re) => {
    const c0 = await catalogue(); const t0 = await docText(doc);
    const r = await fn();
    const c1 = await catalogue(); const t1 = await docText(doc);
    rejections[name] = { ok: r.ok, issues: (r.issues || []).map((i) => i.code + ': ' + i.message + ' | ' + (i.hint || '')) };
    return ck(name, r.ok === false && has(r, code, re) && c0 === c1 && t0 === t1);
  };
  await s.eval('window.__alerts = []; window.__savedAlert = window.alert; window.alert = (m) => { window.__alerts.push(String(m)); };');
  try {
    await resetApp(s);
    const exMark = s.exceptions.length;
    const dialogMark = s.dialogs.length;

    // ---------------------------------------------------------------- exportCircuit json: 2.2 and models
    const D = (await A('createDocument', { title: 'JSON models' })).data.doc;
    const dd = await define(D, [
      { op: 'defineModel', model: { kind: 'diode', name: 'jm-led', parameters: { forward_voltage: '2.1 V', forward_current: '20 mA' } } },
      { op: 'defineModel', model: { kind: 'transistor', name: 'jm-bjt', parameters: { early_voltage_forward: '100 V' } } },
      { op: 'defineModel', model: { kind: 'logic', name: 'jm-and', inputs: ['A', 'B'], outputs: ['Y'], rules: ['11=1', '??=0'] } },
      { op: 'add', element: { id: 'LED1', type: 'LED', start: { x: 4, y: 0 }, end: { x: 4, y: 4 }, properties: { model: 'jm-led' } } },
      { op: 'add', element: { id: 'Q1', type: 'TransistorNPN', start: { x: 10, y: 0 }, end: { x: 14, y: 0 }, properties: { model: 'jm-bjt' } } },
      { op: 'add', element: { id: 'CL1', type: 'CustomLogic', start: { x: 20, y: 4 }, properties: { model_name: 'jm-and' } } }]);
    out.notes.define = { ok: dd.ok, issues: issues(dd) };
    const jD = JSON.parse(await exportJson(D));
    const gcD = await A('getCircuit', { doc: D, detail: 'full' });
    out.notes.exportModels = jD.models;
    ck('export_version22', dd.ok && jD.schema && jD.schema.version === '2.2');
    ck('export_modelsEqualGetCircuit', Array.isArray(jD.models) && same(jD.models.map((m) => m.name), ['jm-and', 'jm-led', 'jm-bjt']) && same(jD.models, gcD.data.models));
    ck('export_modelSpecForms', jD.models && jD.models.every((m) => !m.modelText) && jD.models[1].parameters.forward_current === '20 mA');
    const P = (await A('createDocument', { title: 'Plain' })).data.doc;
    await A('importCircuit', { doc: P, circuit: { elements: [{ id: 'R1', type: 'Resistor', start: { x: 0, y: 0 }, end: { x: 4, y: 0 } },
      { id: 'D1', type: 'Diode', start: { x: 0, y: 4 }, end: { x: 4, y: 4 } }] } });
    const jP = JSON.parse(await exportJson(P));
    ck('export_noModelsKey', jP.schema.version === '2.2' && !('models' in jP));

    // ---------------------------------------------------------------- a subcircuit with a diode dependency
    const B = (await A('createDocument', { title: 'Diode block' })).data.doc;
    const db = await define(B, [{ op: 'defineModel', model: { kind: 'diode', name: 'jm-dd', parameters: { forward_voltage: '0.65 V', forward_current: '10 mA' } } },
      { op: 'add', element: label('LA', 'a', 2, 2, 0, 2) },
      { op: 'add', element: { id: 'D1', type: 'Diode', start: { x: 2, y: 2 }, end: { x: 6, y: 2 }, properties: { model: 'jm-dd' } } },
      { op: 'add', element: label('LK', 'k', 6, 2, 8, 2) }]);
    const U = (await A('createDocument', { title: 'Uses the block' })).data.doc;
    const du = await define(U, [{ op: 'defineModel', model: { kind: 'subcircuit', name: 'jm-dsub', source: { doc: B } } },
      { op: 'defineModel', model: { kind: 'diode', name: 'jm-extra', parameters: { forward_voltage: '1.9 V', forward_current: '10 mA' } } },
      { op: 'add', element: { id: 'X1', type: 'Subcircuit', start: { x: 10, y: 10 }, properties: { model_name: 'jm-dsub' } } },
      { op: 'add', element: { id: 'D9', type: 'Diode', start: { x: 2, y: 20 }, end: { x: 6, y: 20 }, properties: { model: 'jm-extra' } } }]);
    out.notes.subBlock = { block: issues(db), user: issues(du) };
    const jU = JSON.parse(await exportJson(U));
    ck('export_dependenciesFirst', db.ok && du.ok && same((jU.models || []).map((m) => m.kind + ':' + m.name), ['diode:jm-extra', 'diode:jm-dd', 'subcircuit:jm-dsub'])
      && typeof jU.models[2].modelText === 'string' && jU.models[2].modelText.startsWith('. jm-dsub '));
    const subText = jU.models[2].modelText;

    // ---------------------------------------------------------------- saveFile json (in-page fake file system)
    const D2 = (await A('createDocument', { title: 'Saved' })).data.doc;
    await define(D2, [{ op: 'defineModel', model: { kind: 'diode', name: 'jm-file', parameters: { forward_voltage: '3.1 V', forward_current: '15 mA' } } },
      { op: 'add', element: { id: 'LED1', type: 'LED', start: { x: 4, y: 0 }, end: { x: 4, y: 4 }, properties: { model: 'jm-file' } } }]);
    let saved = null;
    await s.eval(fakeFsScript({}));
    try {
      const sv = await A('saveFile', { doc: D2, path: '/tmp/jm_save.json', format: 'json' });
      saved = await s.eval(`window.__fakeFiles['/tmp/jm_save.json'] || null`);
      out.notes.saveFile = { ok: sv.ok, issues: issues(sv) };
    } finally {
      await s.eval(FAKE_FS_RESTORE);
    }
    const jSaved = saved ? JSON.parse(saved) : null;
    ck('saveFile_modelsSection', jSaved && jSaved.schema.version === '2.2' && same((jSaved.models || []).map((m) => m.name), ['jm-file']));

    // ---------------------------------------------------------------- agent importCircuit of JSON text
    const E = (await A('createDocument', { title: 'Agent JSON' })).data.doc;
    const c0 = await catalogue();
    const ri = await A('importCircuit', { doc: E, circuit: JSON.stringify(jD) });
    ck('agent_identicalAccepted', ri.ok && c0 === await catalogue());
    const withModels = (models, extra) => JSON.stringify(Object.assign({}, jP, { models }, extra || {}));
    const differing = JSON.parse(JSON.stringify(jD));
    differing.models[1].parameters = { forward_voltage: '2.5 V', forward_current: '20 mA' };
    await rejects('agent_differingNameTaken', E, () => A('importCircuit', { doc: E, circuit: JSON.stringify(differing) }), 'name_taken', /open the file with `openFile`/i);
    await rejects('agent_kindFoo', E, () => A('importCircuit', { doc: E, circuit: withModels([{ kind: 'foo', name: 'jm-foo' }]) }), 'invalid_value', /circuit\.models\[0\]\.kind'/);
    await rejects('agent_fromInFile', E, () => A('importCircuit', { doc: E, circuit: withModels([{ kind: 'diode', name: 'jm-from', from: 'default', parameters: {} }]) }), 'invalid_value', /circuit\.models\[0\]\.from'.*not allowed in a file/);
    await rejects('agent_sourceInFile', E, () => A('importCircuit', { doc: E, circuit: withModels([{ kind: 'subcircuit', name: 'jm-src', source: { doc: B } }]) }), 'invalid_value', /circuit\.models\[0\]\.source'.*not allowed in a file/);
    const t = subText.split(' ');
    const noPin = ['.', 'jm-nopin', t[2], t[3], t[4], '0'].concat(t.slice(6 + 4 * Number(t[5]))).join(' ');
    await rejects('agent_subcircuitNoPin', E, () => A('importCircuit', { doc: E, circuit: withModels([{ kind: 'subcircuit', name: 'jm-nopin', modelText: noPin }]) }), 'invalid_value', /modelText'.*a subcircuit needs at least one pin/);
    const innerBad = subText.replace(/^\. jm-dsub /, '. jm-inner ').split('jm-dd').join('jm-nope');
    await rejects('agent_innerUnknown', E, () => A('importCircuit', { doc: E, circuit: withModels([{ kind: 'subcircuit', name: 'jm-inner', modelText: innerBad }]) }), 'invalid_value', /modelText'.*inner model jm-nope unknown/);
    // a new entry and a bad one: nothing registered
    await rejects('agent_rejectedRegistersNothing', E, () => A('importCircuit', { doc: E, circuit: withModels([{ kind: 'diode', name: 'jm-new0', parameters: { forward_voltage: '1.5 V', forward_current: '5 mA' } }, { kind: 'foo', name: 'x' }]) }), 'invalid_value');
    ck('agent_rejectedNotListed', has(await A('listModels', { kind: 'diode', name: 'jm-new0' }), 'unknown_model'));
    const newJ = JSON.parse(withModels([{ kind: 'diode', name: 'jm-new', parameters: { forward_voltage: '1.6 V', forward_current: '5 mA' } }]));
    for (const e of Object.values(newJ.elements)) if (e.type === 'Diode') e.properties = Object.assign({}, e.properties, { model: 'jm-new' });
    const rn = await A('importCircuit', { doc: E, circuit: JSON.stringify(newJ) });
    const rnRec = await record('diode', 'jm-new');
    out.notes.agentNew = { ok: rn.ok, issues: issues(rn), rec: rnRec };
    ck('agent_newDefinedAndUsed', rn.ok && rnRec && rnRec.parameters.forward_current === '5 mA'
      && (await A('getCircuit', { doc: E })).data.elements.some((e) => e.type === 'Diode' && e.properties.model === 'jm-new'));
    // a file's models section has no cap (the AgentCircuit cap is 200)
    const many = Array.from({ length: 201 }, (_, i) => ({ kind: 'diode', name: 'jm-many' + i, parameters: { forward_voltage: (1 + i / 1000) + ' V', forward_current: '10 mA' } }));
    const rm = await A('importCircuit', { doc: E, circuit: withModels(many) });
    ck('agent_fileModelsUncapped', rm.ok && !!(await record('diode', 'jm-many200')));
    // new logic and subcircuit entries in a JSON text: defined, elements use them
    const DN = (await A('createDocument', { title: 'New entries' })).data.doc;
    await define(DN, [{ op: 'add', element: { id: 'CL1', type: 'CustomLogic', start: { x: 4, y: 4 }, properties: { model_name: 'jm-and' } } },
      { op: 'add', element: { id: 'X1', type: 'Subcircuit', start: { x: 20, y: 4 }, properties: { model_name: 'jm-dsub' } } }]);
    const renamed = (await exportJson(DN)).split('jm-and').join('jm-newlg').split('jm-dsub').join('jm-newsub');
    const rnew = await A('importCircuit', { doc: E, circuit: renamed });
    const lgRec = await record('logic', 'jm-newlg'), subRec = await record('subcircuit', 'jm-newsub');
    const lgOld = await record('logic', 'jm-and'), subOld = await record('subcircuit', 'jm-dsub');
    out.notes.agentNewLogicSub = { ok: rnew.ok, issues: issues(rnew) };
    ck('agent_newLogicAndSubcircuit', rnew.ok && lgRec && subRec && same(lgRec.rules, lgOld.rules) && same(lgRec.inputs, lgOld.inputs)
      && same(subRec.pins, subOld.pins) && (await A('getCircuit', { doc: E })).data.elements.some((e) => e.type === 'Subcircuit' && e.properties.model_name === 'jm-newsub'));
    await A('closeDocument', { doc: DN, discardChanges: true });
    // a model name the content does not define is still invalid_value (JSON elements are checked against the content's models)
    const unk = JSON.parse(JSON.stringify(newJ)); delete unk.models;
    await rejects('agent_unknownStillRejected', E, () => A('importCircuit', { doc: E, circuit: JSON.stringify(Object.assign(unk, { elements: Object.fromEntries(Object.entries(unk.elements).map(([k, e]) => [k, e.type === 'Diode' ? Object.assign({}, e, { properties: Object.assign({}, e.properties, { model: 'jm-undefined' }) }) : e])) })) }), 'invalid_value', /jm-undefined/);

    // ---------------------------------------------------------------- user load (JSON) with invalid entries
    const bad = JSON.parse(JSON.stringify(jP));
    bad.models = [{ kind: 'foo', name: 'jm-foo' }, { kind: 'diode', name: 'jm-bad-from', from: 'default', parameters: { breakdown_voltage: '7 V' } }];
    for (const e of Object.values(bad.elements)) if (e.type === 'Diode') e.properties = Object.assign({}, e.properties, { model: 'jm-bad-from' });
    const badText = JSON.stringify(bad);
    // the log buffer is bounded: start from an empty one
    await s.call('clearLogs');
    const logMark = await s.call('logCount'); const conMark = s.markConsole();
    const alerts0 = (await s.eval('window.__alerts.length'));
    const nBad = await s.call('importJson', badText);
    const loadLogs = [...(await s.call('logsSince', logMark)), ...s.consoleSince(conMark).map((c) => c.text)];
    const fallback = await record('diode', 'jm-bad-from');
    out.notes.userInvalid = { count: nBad, logs: loadLogs.filter((l) => /models\[/.test(l)).slice(0, 4), fallback };
    ck('user_invalidLoads', nBad === Object.keys(bad.elements).length);
    ck('user_invalidLogged', loadLogs.some((l) => /models\[0\]/.test(l)) && loadLogs.some((l) => /models\[1\]\.from/.test(l)));
    ck('user_invalidFallback', !fallback || fallback.parameters.breakdown_voltage !== '7 V');
    ck('user_invalidNoAlert', (await s.eval('window.__alerts.length')) === alerts0 && s.dialogs.length === dialogMark);

    // ---------------------------------------------------------------- openFile: invalid entries (value_adjusted) and a rejected load restoring its overwrite
    const overwrite = JSON.parse(JSON.stringify(jD));
    overwrite.models[1].parameters = { forward_voltage: '2.6 V', forward_current: '20 mA' };
    overwrite.elements.ZZ1 = { type: 'NoSuchElementType', p1: { x: 0, y: 0 }, p2: { x: 64, y: 0 } };
    const ledBefore = await record('diode', 'jm-led');
    // a model name no load of this session has registered yet (the user load above left a fallback entry)
    const badFile = badText.split('jm-bad-from').join('jm-bad-file');
    await s.eval(fakeFsScript({ '/tmp/jm_bad.json': badFile, '/tmp/jm_over.json': JSON.stringify(overwrite) }));
    try {
      const ob = await A('openFile', { path: '/tmp/jm_bad.json' });
      out.notes.openInvalid = { ok: ob.ok, issues: issues(ob) };
      ck('openFile_invalidAdjusted', ob.ok && has(ob, 'value_adjusted', /models\[0\]/) && has(ob, 'value_adjusted', /models\[1\]\.from/)
        && (ob.issues || []).some((i) => i.code === 'value_adjusted' && /jm-bad-file/.test(i.message) && (i.elements || []).length === 1));
      if (ob.ok) await A('closeDocument', { doc: ob.data.doc, discardChanges: true });
      const c1 = await catalogue();
      const oo = await A('openFile', { path: '/tmp/jm_over.json' });
      out.notes.openRejected = { ok: oo.ok, issues: issues(oo) };
      ck('openFile_rejectedRestores', oo.ok === false && has(oo, 'import_element_skipped') && c1 === await catalogue() && same(await record('diode', 'jm-led'), ledBefore));
    } finally {
      await s.eval(FAKE_FS_RESTORE);
    }

    // ---------------------------------------------------------------- a logic entry whose rules do not parse
    // user JSON load: loads the rules before the bad line and alerts the parser's message once, as
    // a text '!' line does; openFile: value_adjusted, no alert; importCircuit: invalid_value, no alert
    const badRule = (name) => JSON.stringify(Object.assign({}, jP, { models: [{ kind: 'logic', name, modelText: '! ' + name + ' 0 A,B Y ' + name + ' 1\\q11\\n' }] }));
    const ar0 = await s.eval('window.__alerts.length');
    await s.call('importJson', badRule('jm-badrule'));
    const ar1 = await s.eval('window.__alerts.length');
    const brRec = await record('logic', 'jm-badrule');
    out.notes.badRule = { alerts: await s.eval(`window.__alerts.slice(${ar0})`), rec: brRec };
    ck('badRule_userLoadAlertsOnce', ar1 === ar0 + 1 && brRec && same(brRec.outputs, ['Y']));
    await s.eval(fakeFsScript({ '/tmp/jm_badrule.json': badRule('jm-badrule2') }));
    try {
      const obr = await A('openFile', { path: '/tmp/jm_badrule.json' });
      out.notes.badRuleOpenFile = { ok: obr.ok, issues: issues(obr) };
      ck('badRule_openFileAdjusted', obr.ok && has(obr, 'value_adjusted', /jm-badrule2.*do not parse/) && (await s.eval('window.__alerts.length')) === ar1);
      if (obr.ok) await A('closeDocument', { doc: obr.data.doc, discardChanges: true });
    } finally {
      await s.eval(FAKE_FS_RESTORE);
    }
    await rejects('badRule_importCircuitInvalid', E, () => A('importCircuit', { doc: E, circuit: badRule('jm-badrule3') }), 'invalid_value', /models\[0\]\.modelText'.*do not parse/);
    ck('badRule_agentNoAlert', (await s.eval('window.__alerts.length')) === ar1);

    // ---------------------------------------------------------------- in-circuit scopes (403) through JSON
    // with the user's saved scope defaults switching manual scale and voltage on (a new Scope takes
    // them; settings the JSON omits must not come from them), and a line using trigger, history,
    // manual scale with divisions, AC coupling, a second plot and a label
    out.notes.scopeElm = {};
    const scopeCover = '$ 1 0.000005 10 50 5 50 5e-11\nv 0 128 0 0 0 1 40 5 0 0 0.5\nr 0 0 128 0 0 1000\nw 128 0 128 128 0\nw 0 128 128 128 0\n'
      + '403 192 0 320 128 0 1_64_0_xfc1213_5_0.1_0_2_6_1_0.5_1_0_0_3_0.01_0_1_1_1_0.5_0.001_0.3_0_1_16_0_0_probe\n';
    await s.eval(`localStorage.setItem('scopeDefaults', '1 18 64')`);
    try {
    for (const f of ['multivib-a.txt', 'qam-256.txt', 'coverage']) {
      if (f === 'coverage') await s.call('importText', scopeCover); else await s.call('loadExample', f);
      const T1 = await s.call('exportText');
      const J1 = await s.call('exportJson');
      await s.call('importJson', J1);
      const T2 = await s.call('exportText');
      const l1 = T1.split('\n').filter((l) => l.startsWith('403 ')), l2 = T2.split('\n').filter((l) => l.startsWith('403 '));
      const scopeProps = Object.values(JSON.parse(J1).elements).filter((e) => e.type === 'Scope').map((e) => e.properties && e.properties.scope);
      out.notes.scopeElm[f] = { lines: l1.length, kept: l1.filter((l) => l2.includes(l)).length, withScope: scopeProps.filter(Boolean).length };
      ck('scopeElm_' + f, l1.length > 0 && same(l1, l2) && scopeProps.length === l1.length && scopeProps.every((p) => p && typeof p.element === 'string'));
      if (f === 'coverage') ck('scopeElm_coverageKept', l1.length === 1 && l1[0].endsWith('_probe') && l1[0].includes('_xfc1213_'));
    }
    } finally {
      await s.eval(`localStorage.removeItem('scopeDefaults')`);
    }
    await s.call('loadExample', 'qam-256.txt');
    const qamJ1 = await s.call('exportJson');
    const qamDoc = (await A('createDocument', { title: 'qam' })).data.doc;
    await A('importCircuit', { doc: qamDoc, circuit: qamJ1 });
    const scopeRec = (await A('getCircuit', { doc: qamDoc, detail: 'full', limit: 500 })).data.elements.find((e) => e.type === 'Scope');
    out.notes.agentFormScope = scopeRec ? { properties: Object.keys(scopeRec.properties || {}) } : null;
    ck('agentForm_scopeOmitted', scopeRec && !('scope' in (scopeRec.properties || {})));
    const multivibJ1 = await (async () => { await s.call('loadExample', 'multivib-a.txt'); return s.call('exportJson'); })();

    // ---------------------------------------------------------------- examples with model lines: J1 into a fresh session
    const EXAMPLES = ['brentkung.txt', 'early.txt', 'ledarray.txt', 'opamp-regulator.txt'];
    const exJ = {};
    for (const f of EXAMPLES) {
      await s.call('loadExample', f);
      exJ[f] = { T1: modelLines(await s.call('exportText')), J1: await s.call('exportJson') };
    }

    // paste fragment: a JSON 2.2 text with a model the side session lacks
    const D3 = (await A('createDocument', { title: 'Paste source' })).data.doc;
    await define(D3, [{ op: 'defineModel', model: { kind: 'diode', name: 'jm-paste', parameters: { forward_voltage: '2.9 V', forward_current: '12 mA' } } },
      { op: 'add', element: { id: 'LED1', type: 'LED', start: { x: 4, y: 0 }, end: { x: 4, y: 4 }, properties: { model: 'jm-paste' } } }]);
    const pasteText = await exportJson(D3);
    const recs = {};
    for (const [k, n] of [['diode', 'jm-led'], ['transistor', 'jm-bjt'], ['logic', 'jm-and'], ['diode', 'jm-file'], ['diode', 'jm-dd'], ['subcircuit', 'jm-dsub'], ['diode', 'jm-paste']]) {
      recs[n] = await record(k, n);
    }
    const noUse = (r) => { if (!r) return r; const c = Object.assign({}, r); delete c.usedBy; return c; };

    // ---------------------------------------------------------------- side page: a fresh session
    await withSidePage(s, async (s2) => {
      const A2 = (op, args) => s2.call('agentCall', op, args);
      const rec2 = (kind, name) => recordOf(A2, kind, name);
      const side = out.notes.side = {};
      side.fresh = (await A2('listModels', {})).data.models.filter((m) => /^jm-/.test(m.name)).map((m) => m.name);
      ck('fresh_sessionHasNoModels', side.fresh.length === 0);
      // openFile of the file saveFile wrote defines its model
      await s2.eval(fakeFsScript({ '/tmp/jm_save.json': saved || '' }));
      try {
        const before = await A2('listModels', { kind: 'diode', name: 'jm-file' });
        const of = await A2('openFile', { path: '/tmp/jm_save.json' });
        side.openFile = { before: before.ok, ok: of.ok, issues: issues(of) };
        ck('fresh_openFileDefines', !before.ok && of.ok && same(noUse(await rec2('diode', 'jm-file')), noUse(recs['jm-file'])));
      } finally {
        await s2.eval(FAKE_FS_RESTORE);
      }
      // "Import subcircuits only": the subcircuit and its diode dependency, no other entry, no element
      const n0 = await s2.call('count');
      await s2.eval(`CircuitJS1.importCircuit(${JSON.stringify(JSON.stringify(jU))}, true)`);
      side.subOnly = { count: [n0, await s2.call('count')], dd: !!(await rec2('diode', 'jm-dd')), dsub: !!(await rec2('subcircuit', 'jm-dsub')), extra: !!(await rec2('diode', 'jm-extra')) };
      ck('fresh_subcircuitsOnly', same(noUse(await rec2('subcircuit', 'jm-dsub')), noUse(recs['jm-dsub'])) && same(noUse(await rec2('diode', 'jm-dd')), noUse(recs['jm-dd']))
        && has(await A2('listModels', { kind: 'diode', name: 'jm-extra' }), 'unknown_model') && (await s2.call('count')) === n0);
      // Ctrl+V of prose from the system clipboard pastes nothing: no element, no undo entry, not modified
      const sideDoc = (await A2('listDocuments', {})).data.documents.find((d) => d.active).doc;
      const docState2 = async () => { const st = JSON.parse(await s2.eval(`CircuitJS1Agent.debugDocState(${JSON.stringify(sideDoc)})`)); return [await s2.call('count'), st.undo, st.modified]; };
      const pr0 = await docState2();
      await s2.eval(`navigator.clipboard.writeText(${JSON.stringify('Notes for the meeting\nwe will discuss r and c\nw 1 and l 2\n')})`);
      await s2.call('focus');
      await s2.key('KeyV', { ctrl: true });
      await sleep(500);
      const pr1 = await docState2();
      side.prose = [pr0, pr1];
      ck('fresh_prosePastesNothing', same(pr0, pr1));
      // Ctrl+V of a JSON 2.2 text from the system clipboard (the side session's internal clipboard is empty)
      const p0 = await s2.call('count');
      await s2.eval(`navigator.clipboard.writeText(${JSON.stringify(pasteText)})`);
      await s2.call('focus');
      await s2.key('KeyV', { ctrl: true });
      await waitFor(async () => (await s2.call('count')) > p0, 5000, 'paste').catch(() => {});
      const p1 = await s2.call('count');
      const pasted = await rec2('diode', 'jm-paste');
      await s2.key('KeyZ', { ctrl: true });
      const p2 = await s2.call('count');
      side.paste = { counts: [p0, p1, p2], rec: pasted, console: s2.console.slice(-8).map((c) => c.text.slice(0, 200)), logs: (await s2.call('logsSince', Math.max(0, (await s2.call('logCount')) - 8))).map((l) => l.slice(0, 200)) };
      ck('fresh_pasteDefines', p1 === p0 + 1 && same(noUse(pasted), noUse(recs['jm-paste'])));
      ck('fresh_pasteUndoKeepsModel', p2 === p0 && same(noUse(await rec2('diode', 'jm-paste')), noUse(recs['jm-paste'])));
      // a user JSON load defines diode, transistor and logic models as the writing session had them
      await s2.call('importJson', JSON.stringify(jD));
      ck('fresh_userLoadDefines', same(noUse(await rec2('diode', 'jm-led')), noUse(recs['jm-led']))
        && same(noUse(await rec2('transistor', 'jm-bjt')), noUse(recs['jm-bjt'])) && same(noUse(await rec2('logic', 'jm-and')), noUse(recs['jm-and'])));
      // every bundled example with model lines keeps them through JSON
      side.examples = {};
      for (const f of EXAMPLES) {
        await s2.call('importJson', exJ[f].J1);
        const T2 = modelLines(await s2.call('exportText'));
        side.examples[f] = { T1: exJ[f].T1.map((l) => l.slice(0, 60)), same: same(T2, exJ[f].T1) };
      }
      ck('fresh_examplesKeepModels', EXAMPLES.every((f) => exJ[f].T1.length > 0 && side.examples[f].same));
      ck('fresh_noAlertNoException', s2.dialogs.length === 0 && s2.exceptions.length === 0);
    });

    // a second fresh session: Ctrl+V of a JSON text holding Scope elements, then Ctrl+Z
    await withSidePage(s, async (s2) => {
      const n0 = await s2.call('count');
      const scopes0 = (await s2.call('exportText')).split('\n').filter((l) => l.startsWith('403 ')).length;
      await s2.eval(`navigator.clipboard.writeText(${JSON.stringify(multivibJ1)})`);
      await s2.call('focus');
      await s2.key('KeyV', { ctrl: true });
      await waitFor(async () => (await s2.call('count')) > n0, 5000, 'scope paste').catch(() => {});
      const n1 = await s2.call('count');
      const scopes1 = (await s2.call('exportText')).split('\n').filter((l) => l.startsWith('403 ')).length;
      await s2.key('KeyZ', { ctrl: true });
      const n2 = await s2.call('count');
      const scopes2 = (await s2.call('exportText')).split('\n').filter((l) => l.startsWith('403 ')).length;
      out.notes.scopePaste = { counts: [n0, n1, n2], scopeLines: [scopes0, scopes1, scopes2] };
      ck('fresh_scopePasteUndo', n1 === n0 + Object.keys(JSON.parse(multivibJ1).elements).length && scopes1 === scopes0 + 4 && n2 === n0 && scopes2 === scopes0);
      ck('fresh_scopePasteNoException', s2.exceptions.length === 0);
    });

    // text circuits from the system clipboard still paste (a fresh session each, its internal
    // clipboard empty): ledarray (a `!` model line) and lrc (`o`, `38` and `h` lines)
    out.notes.textPaste = {};
    for (const f of ['ledarray.txt', 'lrc.txt']) {
      const expected = (await s.call('loadExample', f)).count;
      await withSidePage(s, async (s2) => {
        const raw = await s2.call('fetchText', '/circuitjs1/circuits/' + f);
        const n0 = await s2.call('count');
        await s2.eval(`navigator.clipboard.writeText(${JSON.stringify(raw)})`);
        await s2.call('focus');
        await s2.key('KeyV', { ctrl: true });
        await waitFor(async () => (await s2.call('count')) > n0, 5000, 'text paste').catch(() => {});
        const n1 = await s2.call('count');
        const smiley = f === 'ledarray.txt' ? (await s2.call('agentCall', 'listModels', { kind: 'logic', name: 'smiley' })).ok : null;
        out.notes.textPaste[f] = { counts: [n0, n1], expected, smiley };
        ck('fresh_textPaste_' + f, n1 === n0 + expected && smiley !== false && s2.exceptions.length === 0);
      });
    }

    for (const doc of [D, P, B, U, D2, E, D3, qamDoc]) await A('closeDocument', { doc, discardChanges: true });
    out.rejections = rejections;
    out.notes.exceptions = s.exceptions.slice(exMark).slice(0, 5);
    ck('noPageException', s.exceptions.length === exMark);
    // the one expected alert: the user JSON load of a logic entry whose rules do not parse
    ck('noOtherAlert', (await s.eval('window.__alerts.length')) === 1 && s.dialogs.length === dialogMark);
  } finally {
    await s.eval('if (window.__savedAlert) window.alert = window.__savedAlert;');
  }
  fs.writeFileSync(path.join(OUT_DIR, 'json_models.json'), JSON.stringify(out, null, 2));
  const failed = Object.entries(out.checks).filter(([, v]) => !v).map(([k]) => k);
  report('M.json_models', failed.length === 0, { checks: Object.keys(out.checks).length, failed, details: path.join(OUT_DIR, 'json_models.json') });
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
  ck('toolsVersion', page.server && page.server.toolsVersion === '1.2');
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

// ---------------------------------------------------------------- render_pixels (PL_AGA Phase 16a step 0)
// Pixel tooling of the text layout split: RENDER_BASELINE=<dir> writes a PNG render (scale 1) of
// every example (CIRCUITS, default all) after the same run with reset (span RENDER_RUN_SPAN,
// default 20 us) into <dir>, with manifest.json; RENDER_COMPARE=<dir> renders again and compares
// the pixel data with those files (same machine, Chromium and session language). Examples with
// noise sources (RandomUtils: a noise waveform) are left out, and so is any example whose two
// baseline renders differ or whose run did not reach its span. Live text boxes of checkLayout
// (grown by 2 px) are masked once the contract exists. RENDER_SLICE=<n> forces an offscreen slice
// break after every n elements (debugRenderSliceElements); RENDER_NOMASK=1 compares the live
// texts too. Differences are written to
// OUT_DIR/render_pixels/<example>.{base,now,diff}.png.
const RENDER_RUN_SPAN = process.env.RENDER_RUN_SPAN || '20 us';

function hasNoiseSource(text) {
  return String(text).split('\n').some((l) => {
    const t = l.trim().split(/\s+/);
    return (t[0] === 'v' || t[0] === 'R' || t[0] === 'n') && (t[0] === 'n' || t[6] === '6');
  });
}

async function renderPixelsOf(s, doc, text, sliceN) {
  const A = (op, args) => s.call('agentCall', op, args);
  const imp = await A('importCircuit', { doc, circuit: text });
  if (!imp.ok) return { error: 'import: ' + (imp.issues || []).map((i) => i.code).join(',') };
  const run = await s.call('agentAsync', 'run', { doc, span: RENDER_RUN_SPAN, reset: true, budgetMs: 120000 }, 180000);
  const reason = run && run.data ? run.data.reason : (run && run.timeout ? 'timeout' : 'failed');
  if (sliceN !== undefined) await s.eval(`CircuitJS1Agent.debugRenderSliceElements && CircuitJS1Agent.debugRenderSliceElements(${+sliceN || 0})`);
  const r = await s.call('agentAsync', 'render', { doc, format: 'png', scale: 1 }, 120000);
  if (sliceN !== undefined) await s.eval('CircuitJS1Agent.debugRenderSliceElements && CircuitJS1Agent.debugRenderSliceElements(0)');
  if (!r || !r.data) return { error: 'render: ' + JSON.stringify(r && (r.issues || r)).slice(0, 200), run: reason };
  // live boxes (cells) of checkLayout, in image pixels through the SVG origin, grown by 2 px
  let masks = [];
  const lay = process.env.RENDER_NOMASK ? null : await A('checkLayout', { doc, includeBoxes: true });
  if (lay && lay.ok && lay.data && lay.data.boxes) {
    const live = lay.data.boxes.filter((b) => b.live);
    if (live.length) {
      const sv = await s.call('agentAsync', 'render', { doc, format: 'svg', scale: 1 }, 120000);
      const area = sv && sv.data ? svgArea(sv.data.content) : null;
      if (area) masks = live.map((b) => [b.box.x1 * 16 - area[0] - 2, b.box.y1 * 16 - area[1] - 2, b.box.x2 * 16 - area[0] + 2, b.box.y2 * 16 - area[1] + 2]);
    }
  }
  return { png: r.data.content.replace(/^data:image\/png;base64,/, ''), w: r.data.width, h: r.data.height, run: reason, masks };
}

async function scenarioRenderPixels(s) {
  const baseDir = process.env.RENDER_BASELINE, cmpDir = process.env.RENDER_COMPARE;
  if (!baseDir && !cmpDir) { report('AG.render_pixels', false, { error: 'set RENDER_BASELINE=<dir> or RENDER_COMPARE=<dir>' }); return; }
  const sliceN = process.env.RENDER_SLICE !== undefined ? +process.env.RENDER_SLICE : undefined;
  const A = (op, args) => s.call('agentCall', op, args);
  await resetApp(s);
  const exMark = s.exceptions.length;
  const doc = (await A('createDocument', { title: 'Render pixels' })).data.doc;
  const outDir = path.join(OUT_DIR, 'render_pixels');
  fs.rmSync(outDir, { recursive: true, force: true });
  fs.mkdirSync(outDir, { recursive: true });
  const t0 = Date.now();
  if (baseDir) {
    const list = process.env.CIRCUITS && process.env.CIRCUITS !== 'all' ? process.env.CIRCUITS.split(',') : listAllCircuits();
    fs.mkdirSync(baseDir, { recursive: true });
    const manifest = { created: new Date().toISOString(), site: SITE_DIR, runSpan: RENDER_RUN_SPAN, examples: {}, excluded: {} };
    for (const name of list) {
      const text = await s.eval(`__H.fetchText('/circuitjs1/circuits/' + ${JSON.stringify(name)})`);
      if (hasNoiseSource(text)) { manifest.excluded[name] = 'noise source'; continue; }
      const r1 = await renderPixelsOf(s, doc, text, sliceN);
      if (r1.error) { manifest.excluded[name] = r1.error; continue; }
      if (r1.run !== 'span_reached' && r1.run !== 'solver_stop' && r1.run !== 'stop_trigger') { manifest.excluded[name] = 'run ' + r1.run; continue; }
      const r2 = await renderPixelsOf(s, doc, text, sliceN);
      const d = r2.error ? { diff: -1 } : await s.call('pngDiff', r1.png, r2.png, []);
      if (d.diff !== 0) { manifest.excluded[name] = 'nondeterministic (' + d.diff + ' px between two renders)'; continue; }
      fs.writeFileSync(path.join(baseDir, name.replace(/\.txt$/, '.png')), Buffer.from(r1.png, 'base64'));
      manifest.examples[name] = { w: r1.w, h: r1.h, run: r1.run };
    }
    fs.writeFileSync(path.join(baseDir, 'manifest.json'), JSON.stringify(manifest, null, 2));
    await A('closeDocument', { doc, discardChanges: true });
    report('AG.render_pixels', s.exceptions.length === exMark, { mode: 'baseline', dir: baseDir, examples: Object.keys(manifest.examples).length,
      excluded: Object.keys(manifest.excluded).length, s: Math.round((Date.now() - t0) / 1000) });
    return;
  }
  const manifest = JSON.parse(fs.readFileSync(path.join(cmpDir, 'manifest.json'), 'utf8'));
  const only = process.env.CIRCUITS && process.env.CIRCUITS !== 'all' ? new Set(process.env.CIRCUITS.split(',')) : null;
  const diffs = {}; const errors = {}; let compared = 0, masked = 0;
  for (const name of Object.keys(manifest.examples)) {
    if (only && !only.has(name)) continue;
    const text = await s.eval(`__H.fetchText('/circuitjs1/circuits/' + ${JSON.stringify(name)})`);
    const r = await renderPixelsOf(s, doc, text, sliceN);
    if (r.error) { errors[name] = r.error; continue; }
    const base = fs.readFileSync(path.join(cmpDir, name.replace(/\.txt$/, '.png'))).toString('base64');
    const d = await s.call('pngDiff', base, r.png, r.masks);
    compared++;
    if (r.masks.length) masked++;
    if (d.diff !== 0) {
      const stem = path.join(outDir, name.replace(/\.txt$/, ''));
      fs.writeFileSync(stem + '.base.png', Buffer.from(base, 'base64'));
      fs.writeFileSync(stem + '.now.png', Buffer.from(r.png, 'base64'));
      if (d.png) fs.writeFileSync(stem + '.diff.png', Buffer.from(d.png, 'base64'));
      diffs[name] = d.sameSize ? { diff: d.diff, bbox: d.bbox, size: d.size } : { sizeChanged: [d.a, d.b] };
    }
  }
  await A('closeDocument', { doc, discardChanges: true });
  fs.writeFileSync(path.join(OUT_DIR, 'render_pixels.json'), JSON.stringify({ baseline: cmpDir, sliceN, diffs, errors }, null, 2));
  report('AG.render_pixels', Object.keys(diffs).length === 0 && Object.keys(errors).length === 0 && s.exceptions.length === exMark,
    { mode: 'compare', baseline: cmpDir, compared, masked, differing: Object.keys(diffs).length, errors: Object.keys(errors).length,
      first: Object.keys(diffs).slice(0, 12), s: Math.round((Date.now() - t0) / 1000), details: path.join(OUT_DIR, 'render_pixels.json') });
}

// ---------------------------------------------------------------- text_sites (SP_AGA_05_02 "Drawing paints the layout")
// Static check of the element sources (src/.../client/element, subpackages included): a text site
// is a call of drawString(, drawValues(, drawLabeledNode(, drawCenteredText( or fillText outside
// the layout classes (comments and method declarations do not count; the CircuitElm paint wrappers
// are layout classes until PL_AGA Phase 16b). Fails a class with a text site whose effective
// textLayoutCovered() (its own or the nearest ancestor's declaration; default true) is not false,
// and a class that overrides draw() without super.draw() (or ChipElm's drawChip) while it
// inherits a non-empty layoutTexts and reports itself covered. A class without calls of its own
// takes its parent's coverage (Java inheritance).
const TEXT_SITE_RE = /\b(drawString|drawValues|drawLabeledNode|drawCenteredText)\s*\(|\bfillText\b/g;
const TEXT_LAYOUT_CLASSES = new Set(['CircuitElm', 'TextPlacement', 'TextLayout', 'PaintingTextLayout', 'MeasuringTextLayout']);

// Comments and the contents of string and char literals blanked (newlines kept, so line numbers hold)
function stripJavaComments(src) {
  let out = '';
  for (let i = 0; i < src.length; i++) {
    const c = src[i], n = src[i + 1];
    if (c === '/' && n === '/') { while (i < src.length && src[i] !== '\n') i++; out += '\n'; continue; }
    if (c === '/' && n === '*') { i += 2; while (i < src.length && !(src[i] === '*' && src[i + 1] === '/')) { if (src[i] === '\n') out += '\n'; i++; } i++; continue; }
    if (c === '"' || c === '\'') {
      out += c; i++;
      while (i < src.length && src[i] !== c && src[i] !== '\n') { if (src[i] === '\\') i++; i++; }
      out += c; continue;
    }
    out += c;
  }
  return out;
}
// @return the body text of the first method whose declaration matches re, or null
function javaMethodBody(src, re) {
  const m = re.exec(src);
  if (!m) return null;
  let i = src.indexOf('{', m.index + m[0].length - 1);
  if (i < 0) return null;
  let depth = 0;
  for (let j = i; j < src.length; j++) {
    if (src[j] === '{') depth++;
    else if (src[j] === '}' && --depth === 0) return src.slice(i + 1, j);
  }
  return null;
}

function textSitesCheck() {
  const root = path.join(PROJECT, 'src/main/java/com/lushprojects/circuitjs1/client/element');
  const files = [];
  const walk = (d) => { for (const f of fs.readdirSync(d)) { const p = path.join(d, f); if (fs.statSync(p).isDirectory()) walk(p); else if (f.endsWith('.java')) files.push(p); } };
  walk(root);
  const cls = {};
  for (const file of files) {
    const src = stripJavaComments(fs.readFileSync(file, 'utf8'));
    const m = src.match(/\b(?:class|interface|enum)\s+(\w+)(?:\s*<[^>{]*>)?(?:\s+extends\s+(\w+))?/);
    if (!m) continue;
    const name = m[1];
    const sites = [];
    src.split('\n').forEach((line, i) => {
      if (/^\s*(?:(?:public|protected|private|static|final|abstract)\s+)*[\w<>\[\]]+\s+(drawString|drawValues|drawLabeledNode|drawCenteredText)\s*\(/.test(line)) return; // declaration
      if (TEXT_SITE_RE.test(line)) sites.push(i + 1);
      TEXT_SITE_RE.lastIndex = 0;
    });
    const covered = javaMethodBody(src, /\bboolean\s+textLayoutCovered\s*\(\s*\)/g);
    const layout = javaMethodBody(src, /\bvoid\s+layoutTexts\s*\(\s*TextLayout\b[^)]*\)/g);
    const draw = javaMethodBody(src, /\bvoid\s+draw\s*\(\s*Graphics\s+\w+\s*\)/g);
    cls[name] = { name, file: path.relative(PROJECT, file), parent: m[2] || null, sites: TEXT_LAYOUT_CLASSES.has(name) ? [] : sites,
      coveredDecl: covered == null ? null : !/return\s+false\s*;/.test(covered),
      layoutDecl: layout == null ? null : layout.trim().length > 0,
      draw: draw == null ? null : { superDraw: /\bsuper\.draw\s*\(/.test(draw), drawChip: /\bdrawChip\s*\(/.test(draw) } };
  }
  const chain = (n) => { const r = []; let c = cls[n]; while (c) { r.push(c); c = c.parent ? cls[c.parent] : null; } return r; };
  const effCovered = (n) => { for (const c of chain(n)) if (c.coveredDecl !== null) return c.coveredDecl; return true; };
  const inheritedLayout = (n) => { for (const c of chain(n).slice(1)) if (c.layoutDecl !== null) return { from: c.name, texts: c.layoutDecl }; return null; };
  const isChip = (n) => chain(n).some((c) => c.name === 'ChipElm');
  const failures = [], notCovered = [], withSites = [];
  for (const c of Object.values(cls)) {
    const cov = effCovered(c.name);
    if (!cov) notCovered.push(c.name);
    if (c.sites.length) {
      withSites.push(c.name);
      if (cov) failures.push({ class: c.name, rule: 'text site in a covered class', lines: c.sites });
    }
    if (c.draw && !c.draw.superDraw && !(c.draw.drawChip && isChip(c.name)) && c.layoutDecl === null && cov) {
      const inh = inheritedLayout(c.name);
      if (inh && inh.texts) failures.push({ class: c.name, rule: 'draw() without super.draw() inherits layoutTexts of ' + inh.from });
    }
  }
  return { classes: Object.keys(cls).length, withSites: withSites.sort(), notCovered: notCovered.sort(), failures };
}

async function scenarioTextSites(s) {
  const r = textSitesCheck();
  fs.writeFileSync(path.join(OUT_DIR, 'text_sites.json'), JSON.stringify(r, null, 2));
  report('AG.text_sites', r.failures.length === 0, { classes: r.classes, withSites: r.withSites.length, notCovered: r.notCovered.length,
    failures: r.failures.slice(0, 10), details: path.join(OUT_DIR, 'text_sites.json') });
}

// ---------------------------------------------------------------- agent_layout (PL_AGA Phase 16a)
// SP_AGA_02_16 checkLayout and SP_AGA_03_13 text layout: the SP_AGA_05_01 checkLayout rows (T8,
// key stability, clean value texts, T9, T3, text over a symbol, chip pin names, own drawing
// exempt, live texts, values hidden, highlight does not count, background = visible, busy
// document, caps, example corpus, the 16a fixture of text_not_covered, render "explicit fonts",
// layout equals drawing for every catalogue type in four directions with option variants and
// three examples), the SP_AGA_05_02 rows "checkLayout changes no state" and "Drawing paints the
// layout" (text_sites). Pixel equality runs separately (render_pixels with RENDER_COMPARE); the
// cost row runs in layout_cost.
// Calibration list of the example corpus (PL_AGA Phase 16a step 10, CIRCUITS=all agent_connect_all
// with LAYOUT_RENDERS=1, every flag looked at in the render): example → its text_overlap pairs with
// the text and what the drawing shows: "crossed" (a wire or lead through or touching the text),
// "over symbol" (the text runs into or touches the part's drawing), "touching text"; or
// "body model, clear in the drawing" (SP_AGA_03_13 Obstacles: the symbol_overlap body model of a
// chip, pot, relay or transistor reaches past the drawn outline; the drawing clears the text).
const LAYOUT_CALIBRATION = {
  "555monostable.txt": [{ pair: "C1|TIM1", text: "10μF", drawing: "body model, clear in the drawing" }],
  "actbutterband.txt": [{ pair: "C3|R4", text: "63.8nF", drawing: "over symbol" }, { pair: "C5|R7", text: "63.8nF", drawing: "over symbol" }, { pair: "C7|R10", text: "82.3nF", drawing: "over symbol" }],
  "besselbutter.txt": [{ pair: "C5|R1", text: "395.4nF", drawing: "over symbol" }, { pair: "C6|R2", text: "497.9nF", drawing: "over symbol" }],
  "butter10lo.txt": [{ pair: "C5|R1", text: "497.9nF", drawing: "over symbol" }],
  "butter10loaud.txt": [{ pair: "C5|R1", text: "497.9nF", drawing: "over symbol" }],
  "capmult.txt": [{ pair: "C1|TEX3", text: "100nF", drawing: "touching text" }],
  "cappar.txt": [{ pair: "C2|W1", text: "200μF", drawing: "crossed" }],
  "chaos1.txt": [{ pair: "GND1|OUT1", text: "вихід", drawing: "over symbol" }],
  "chaos2.txt": [{ pair: "R10|TEX3", text: "R1", drawing: "over symbol" }],
  "chua.txt": [{ pair: "C3|W32", text: "100nF", drawing: "crossed" }, { pair: "R5|R6", text: "2.2k", drawing: "over symbol" }],
  "crystalosc.txt": [{ pair: "R1|V1", text: "+5V", drawing: "over symbol" }],
  "digsine.txt": [{ pair: "C1|R7", text: "57.6k", drawing: "crossed" }],
  "dram.txt": [{ pair: "V1|W34", text: "+2.5V", drawing: "crossed" }],
  "eclosc.txt": [{ pair: "TEX7|V1", text: "Коли Rp=RL, тоді QL=Qu/2", drawing: "touching text" }],
  "filt-vcvs-lopass.txt": [{ pair: "C1|W3", text: "159nF", drawing: "crossed" }],
  "freqdouble.txt": [{ pair: "C2|W4", text: "10μF", drawing: "crossed" }, { pair: "C2|W5", text: "10μF", drawing: "crossed" }],
  "hfadc.txt": [{ pair: "R4|V4", text: "100k", drawing: "touching text" }],
  "howland.txt": [{ pair: "R5|TEX5", text: "приймач", drawing: "over symbol" }],
  "ledarray.txt": [{ pair: "R1|R2", text: "300", drawing: "over symbol" }, { pair: "R1|R8", text: "300", drawing: "over symbol" }, { pair: "R2|R3", text: "300", drawing: "over symbol" }, { pair: "R3|R6", text: "300", drawing: "over symbol" }, { pair: "R4|R5", text: "300", drawing: "over symbol" }, { pair: "R5|R6", text: "300", drawing: "over symbol" }, { pair: "R7|R8", text: "300", drawing: "over symbol" }],
  "mux3state.txt": [{ pair: "TEX4|V1", text: "буфер з 3-ма стан.", drawing: "touching text" }],
  "opint-current.txt": [{ pair: "C1|R8", text: "30pF", drawing: "over symbol" }, { pair: "R10|R11", text: "50k", drawing: "over symbol" }, { pair: "R8|W49", text: "4.5k", drawing: "crossed" }, { pair: "R9|W48", text: "7.5k", drawing: "crossed" }],
  "opint-invert-amp.txt": [{ pair: "C1|R8", text: "30pF", drawing: "over symbol" }, { pair: "R10|R11", text: "50k", drawing: "over symbol" }, { pair: "R8|W49", text: "4.5k", drawing: "crossed" }, { pair: "R9|W48", text: "7.5k", drawing: "crossed" }],
  "opint-slew.txt": [{ pair: "C1|R8", text: "30pF", drawing: "over symbol" }, { pair: "R10|R11", text: "50k", drawing: "over symbol" }, { pair: "R8|W49", text: "4.5k", drawing: "crossed" }, { pair: "R9|W48", text: "7.5k", drawing: "crossed" }],
  "opint.txt": [{ pair: "C1|R8", text: "30pF", drawing: "over symbol" }, { pair: "R10|R11", text: "50k", drawing: "over symbol" }, { pair: "R8|W49", text: "4.5k", drawing: "crossed" }, { pair: "R9|W48", text: "7.5k", drawing: "crossed" }],
  "ota-vcf-single.txt": [{ pair: "V6|W7", text: "+9V", drawing: "crossed" }],
  "peak-detect.txt": [{ pair: "SW1|TEX1", text: "скидання", drawing: "over symbol" }],
  "phaseseq.txt": [{ pair: "C1|R5", text: "100", drawing: "over symbol" }, { pair: "C10|R15", text: "100", drawing: "over symbol" }, { pair: "C11|R14", text: "100", drawing: "over symbol" }, { pair: "C12|R13", text: "100", drawing: "over symbol" }, { pair: "C2|R6", text: "100", drawing: "over symbol" }, { pair: "C3|R7", text: "100", drawing: "over symbol" }, { pair: "C4|R8", text: "100", drawing: "over symbol" }, { pair: "C5|R9", text: "100", drawing: "over symbol" }, { pair: "C6|R10", text: "100", drawing: "over symbol" }, { pair: "C7|R11", text: "100", drawing: "over symbol" }, { pair: "C8|R12", text: "100", drawing: "over symbol" }, { pair: "C9|R16", text: "100", drawing: "over symbol" }, { pair: "R4|W3", text: "100", drawing: "crossed" }],
  "phasesplit.txt": [{ pair: "R5|R6", text: "10k", drawing: "over symbol" }],
  "pll2a.txt": [{ pair: "C2|W6", text: "10μF", drawing: "crossed" }, { pair: "C2|W7", text: "10μF", drawing: "crossed" }],
  "pot.txt": [{ pair: "POT1|V2", text: "5 V", drawing: "body model, clear in the drawing" }],
  "qam-256.txt": [{ pair: "C1|W66", text: "1μF", drawing: "crossed" }, { pair: "C4|W65", text: "1μF", drawing: "crossed" }, { pair: "C5|W67", text: "1μF", drawing: "crossed" }, { pair: "C6|W72", text: "1μF", drawing: "crossed" }, { pair: "C7|W85", text: "1μF", drawing: "crossed" }, { pair: "C8|W90", text: "1μF", drawing: "crossed" }, { pair: "OUT3|W18", text: "вихід", drawing: "crossed" }, { pair: "OUT3|W34", text: "вихід", drawing: "crossed" }, { pair: "OUT3|W56", text: "вихід", drawing: "crossed" }],
  "relayctr.txt": [{ pair: "K1|V2", text: "+6V", drawing: "body model, clear in the drawing" }, { pair: "K4|V3", text: "+6V", drawing: "body model, clear in the drawing" }, { pair: "K6|V5", text: "+6V", drawing: "body model, clear in the drawing" }, { pair: "V2|W11", text: "+6V", drawing: "crossed" }, { pair: "V3|W33", text: "+6V", drawing: "crossed" }, { pair: "V5|W51", text: "+6V", drawing: "crossed" }],
  "rmsconverter.txt": [{ pair: "C1|W26", text: "30pF", drawing: "crossed" }, { pair: "R11|TRA3", text: "2k", drawing: "body model, clear in the drawing" }, { pair: "U3|V1", text: "+1.2V", drawing: "over symbol" }, { pair: "V1|W24", text: "+1.2V", drawing: "crossed" }, { pair: "V1|W6", text: "+1.2V", drawing: "crossed" }],
  "samplenhold.txt": [{ pair: "C1|W6", text: "100nF", drawing: "crossed" }],
  "spark-marx.txt": [{ pair: "C1|SPA1", text: "24nF", drawing: "over symbol" }, { pair: "C2|SPA2", text: "24nF", drawing: "over symbol" }, { pair: "C3|SPA5", text: "24nF", drawing: "over symbol" }, { pair: "C9|SPA4", text: "24nF", drawing: "over symbol" }],
  "sram.txt": [{ pair: "V1|W41", text: "+5V", drawing: "crossed" }, { pair: "V2|W42", text: "+5V", drawing: "crossed" }],
  "switchedcap.txt": [{ pair: "C2|W5", text: "95.1μF", drawing: "crossed" }],
  "tllight.txt": [{ pair: "R1|V1", text: "*", drawing: "over symbol" }],
  "tlmatch1.txt": [{ pair: "R1|V1", text: "*", drawing: "over symbol" }],
  "tlmatch2.txt": [{ pair: "R1|V1", text: "*", drawing: "over symbol" }],
  "tlmis1.txt": [{ pair: "R1|V1", text: "*", drawing: "over symbol" }],
  "traffic.txt": [{ pair: "C1|TIM1", text: "10μF", drawing: "over symbol" }],
  "trianglevco.txt": [{ pair: "R2|R3", text: "49.9k", drawing: "over symbol" }, { pair: "R2|R4", text: "49.9k", drawing: "over symbol" }, { pair: "V3|W20", text: "-5V", drawing: "crossed" }],
  "unishiftreg.txt": [{ pair: "SW3|TEX3", text: "L", drawing: "touching text" }, { pair: "SW8|TEX8", text: "послід. вправо.", drawing: "crossed" }],
};
const lyE = (id, type, x1, y1, x2, y2, properties, flags) => ({ id, type, start: { x: x1, y: y1 }, end: { x: x2, y: y2 },
  ...(properties ? { properties } : {}), ...(flags !== undefined ? { flags } : {}) });
const LAYOUT_T8 = [lyE('C1', 'Capacitor', 0, 0, 0, 4, { capacitance: '10 nF' }), lyE('W1', 'Wire', 1, -1, 1, 5)];
const CANVAS_FONT_RE = /^\s*(?=(?:(?:[-a-z]+\s*){0,2}(italic|oblique))?)(?=(?:(?:[-a-z]+\s*){0,2}(small-caps))?)(?=(?:(?:[-a-z]+\s*){0,2}(bold(?:er)?|lighter|[1-9]00))?)(?:(?:normal|\1|\2|\3)\s*){0,3}((?:xx?-)?(?:small|large)|medium|smaller|larger|[.\d]+(?:%|in|[cem]m|ex|p[ctx]))(?:\s*\/\s*(normal|[.\d]+(?:%|in|[cem]m|ex|p[ctx])))?\s*([-,'"\sa-z0-9]+?)\s*$/i;
// @return {style, weight, size, family} of a canvas font string (the canvas2svg parse)
function parseCanvasFont(f) {
  const m = CANVAS_FONT_RE.exec(f || '');
  return m ? { style: m[1] || 'normal', weight: m[3] || 'normal', size: m[4] || '10px', family: m[6] || 'sans-serif' } : null;
}
const SVG_ANCHOR = { left: 'start', center: 'middle', right: 'end' };
const SVG_BASELINE = { alphabetic: 'alphabetic', middle: 'central', top: 'text-before-edge', bottom: 'text-after-edge' };
const unescapeXml = (t) => t.replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&#(\d+);/g, (m, n) => String.fromCharCode(+n))
  .replace(/&#x([0-9a-f]+);/gi, (m, n) => String.fromCharCode(parseInt(n, 16))).replace(/&amp;/g, '&');
// 2-D affine matrices [a, b, c, d, e, f] (SVG order)
const mMul = (p, q) => [p[0] * q[0] + p[2] * q[1], p[1] * q[0] + p[3] * q[1], p[0] * q[2] + p[2] * q[3], p[1] * q[2] + p[3] * q[3],
  p[0] * q[4] + p[2] * q[5] + p[4], p[1] * q[4] + p[3] * q[5] + p[5]];
function parseSvgTransform(t) {
  let m = [1, 0, 0, 1, 0, 0];
  for (const x of String(t || '').matchAll(/(\w+)\s*\(([^)]*)\)/g)) {
    const a = x[2].split(/[\s,]+/).filter(Boolean).map(Number);
    let q = null;
    if (x[1] === 'translate') q = [1, 0, 0, 1, a[0], a[1] || 0];
    else if (x[1] === 'scale') q = [a[0], 0, 0, a.length > 1 ? a[1] : a[0], 0, 0];
    else if (x[1] === 'matrix') q = a;
    else if (x[1] === 'rotate') { const r = a[0] * Math.PI / 180; q = [Math.cos(r), Math.sin(r), -Math.sin(r), Math.cos(r), 0, 0]; }
    if (q) m = mMul(m, q);
  }
  return m;
}
// SVG <text> elements with their position in circuit pixels (all group transforms applied, the
// root scale/translate undone), anchor, baseline and parsed font
function svgTextsCircuit(svg) {
  const area = svgArea(svg);
  const out = [];
  const stack = [[1, 0, 0, 1, 0, 0]];
  const re = /<(\/?)(g|text)\b([^>]*?)(\/?)>/g;
  let m;
  while ((m = re.exec(svg))) {
    const attrs = m[3];
    const attr = (k) => { const mm = attrs.match(new RegExp('\\s' + k + '="([^"]*)"')); return mm ? mm[1] : null; };
    if (m[2] === 'g') {
      if (m[1]) stack.pop();
      else if (!m[4]) stack.push(mMul(stack[stack.length - 1], parseSvgTransform(attr('transform'))));
      continue;
    }
    if (m[1] || m[4]) continue;
    const end = svg.indexOf('</text>', re.lastIndex);
    const text = unescapeXml(svg.slice(re.lastIndex, end));
    const M = mMul(stack[stack.length - 1], parseSvgTransform(attr('transform')));
    const x = +attr('x'), y = +attr('y');
    const ix = M[0] * x + M[2] * y + M[4], iy = M[1] * x + M[3] * y + M[5];
    const k = area ? (svg.match(/<g transform="scale\(([\d.]+),/) || [0, 1])[1] : 1;
    out.push({ text, x: area ? ix / k + area[0] : ix, y: area ? iy / k + area[1] : iy, anchor: attr('text-anchor') || 'start', baseline: attr('dominant-baseline') || 'alphabetic',
      font: { style: attr('font-style') || 'normal', weight: attr('font-weight') || 'normal', size: attr('font-size') || '10px', family: attr('font-family') || 'sans-serif' } });
  }
  return out;
}
// @return the problems of matching SVG texts to TextBoxes (one box per text, same string, anchor
// within 0.5 px, align/baseline through the canvas2svg mapping, equal parsed font)
function matchTextsToBoxes(texts, boxes) {
  const cut = (t) => (t.length <= 32 ? t : t.slice(0, 32) + '…');
  const left = boxes.map((b) => ({ ...b, used: false }));
  const problems = [];
  for (const t of texts.filter((x) => x.text.trim() !== '')) {
    const cands = left.filter((b) => !b.used && b.text === cut(t.text));
    const ok = cands.find((b) => {
      const f = parseCanvasFont(b.font);
      return Math.abs(b.anchor.x * 16 - t.x) <= 0.5 && Math.abs(b.anchor.y * 16 - t.y) <= 0.5 && SVG_ANCHOR[b.align] === t.anchor
        && SVG_BASELINE[b.baseline] === t.baseline && f && f.style === t.font.style && f.weight === t.font.weight && f.size === t.font.size && f.family === t.font.family;
    });
    if (ok) ok.used = true;
    else problems.push({ svg: t, candidates: cands.map((b) => ({ anchor: [b.anchor.x * 16, b.anchor.y * 16], align: b.align, baseline: b.baseline, font: b.font })) });
  }
  for (const b of left.filter((x) => !x.used)) problems.push({ boxWithoutSvgText: { text: b.text, anchor: [b.anchor.x * 16, b.anchor.y * 16], font: b.font } });
  return problems;
}

async function scenarioAgentLayout(s) {
  const out = { checks: {}, notes: {} };
  const ck = (name, cond) => { out.checks[name] = !!cond; return !!cond; };
  const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
  const A = (op, args) => s.call('agentCall', op, args);
  const render = (args) => s.call('agentAsync', 'render', args, 60000);
  const tov = (r) => ((r && r.data && r.data.issues) || []).filter((i) => i.code === 'text_overlap');
  const keys = (r) => ((r && r.data && r.data.issues) || []).map((i) => i.key);
  const anyTextIssue = (l) => (l || []).some((i) => /^text_/.test(i.code));
  await resetApp(s);
  const exMark = s.exceptions.length;
  let forcedExceptions = 0;
  const A0 = (await A('listDocuments', {})).data.documents.find((d) => d.active).doc;
  const B = (await A('createDocument', { title: 'Layout B' })).data.doc;
  const load = async (doc, elements) => A('importCircuit', { doc, circuit: { elements } });
  const lay = (doc, boxes) => A('checkLayout', { doc, ...(boxes ? { includeBoxes: true } : {}) });

  // --- T8: a value text crossed by a wire; only checkLayout reports it
  const impT8 = await load(B, LAYOUT_T8);
  const t8 = await lay(B, true);
  const conT8 = await A('getConnectivity', { doc: B, includeNets: false });
  const i8 = tov(t8);
  const box8 = t8.data && t8.data.boxes.find((b) => b.element === 'C1');
  const centre = (b) => ({ x: Math.round((b.box.x1 + b.box.x2) * 8) / 16, y: Math.round((b.box.y1 + b.box.y2) * 8) / 16 });
  out.notes.t8 = { issues: t8.data && t8.data.issues, box: box8 };
  ck('t8Crossed', impT8.ok && t8.ok && i8.length === 1 && same(i8[0].elements, ['C1', 'W1']) && i8[0].severity === 'warning'
    && /10nF/.test(i8[0].message) && /crossed/.test(i8[0].message) && box8 && same(i8[0].at, centre(box8)) && i8[0].at.x > 0 && i8[0].at.y > 0 && i8[0].at.y < 4);
  ck('t8OnlyCheckLayout', !anyTextIssue(conT8.data && conT8.data.issues) && !anyTextIssue(impT8.connectivity && impT8.connectivity.added));
  ck('t8TextsCounted', t8.data && t8.data.texts === 1 && t8.data.truncated === false);

  // --- key stability: the key follows the text, not the wire; no delta lists text issues
  const mv = async (by) => A('applyEdits', { doc: B, edits: [{ op: 'move', id: 'W1', by }] });
  const m1 = await mv({ dx: 3, dy: 0 }); const k1 = await lay(B);
  const m2 = await mv({ dx: -3, dy: 0 }); const k2 = await lay(B);
  const m3 = await mv({ dx: 0, dy: 0.5 }); const k3 = await lay(B);
  ck('keyStable', tov(k1).length === 0 && same(keys(k2), keys(t8)) && same(keys(k3), keys(t8)) && keys(t8).length === 1);
  ck('keyNoDelta', [m1, m2, m3].every((m) => m.ok && !anyTextIssue(m.connectivity.added) && !anyTextIssue(m.connectivity.cleared)));

  // --- clean value texts
  await load(B, [lyE('R1', 'Resistor', 0, 0, 4, 0, { resistance: '4.7k' }), lyE('W1', 'Wire', -3, 0, 0, 0), lyE('C1', 'Capacitor', 4, 0, 4, 4), lyE('W2', 'Wire', 7, -2, 7, 6)]);
  const clean = await lay(B);
  ck('cleanValueTexts', clean.ok && clean.data.issues.length === 0 && clean.data.texts === 2);

  // --- T9: label on label; five cells apart none
  await load(B, [lyE('L1', 'LabeledNode', 0, 0, 0, -1, { label: 'VOUT_MAIN' }), lyE('L2', 'LabeledNode', 2, 0, 2, -1, { label: 'VREF_MAIN' })]);
  const t9 = await lay(B);
  await load(B, [lyE('L1', 'LabeledNode', 0, 0, 0, -1, { label: 'VOUT_MAIN' }), lyE('L2', 'LabeledNode', 5, 0, 5, -1, { label: 'VREF_MAIN' })]);
  const t9b = await lay(B);
  ck('t9LabelOnLabel', tov(t9).length === 1 && same(tov(t9)[0].elements, ['L1', 'L2']) && /overlaps the text/.test(tov(t9)[0].message) && tov(t9b).length === 0 && t9b.data.issues.length === 0);

  // --- rule 3 at the 2 px threshold: two facing labels whose ink boxes are 1–2 px apart touch
  // (each box grown by PAD = 1 px); 2–3 px apart they do not. L2's anchor moves in whole pixels
  // (1/16 cell); the ink gap comes from the exact anchors and the canvas ink metrics of the font.
  const facing = (dpx) => [lyE('L1', 'LabeledNode', 0, 0, 1, 0, { label: 'AAA' }), lyE('L2', 'LabeledNode', 10 + dpx / 16, 0, 9 + dpx / 16, 0, { label: 'AAA' })];
  const inkGap = async (r) => {
    const b1 = r.data.boxes.find((b) => b.element === 'L1'), b2 = r.data.boxes.find((b) => b.element === 'L2');
    const ink = await s.eval(`(() => { const c = document.createElement('canvas').getContext('2d'); c.font = ${JSON.stringify(b1.font)};
      const m = c.measureText('AAA'); return [m.actualBoundingBoxLeft, m.actualBoundingBoxRight]; })()`);
    return (b2.anchor.x * 16 - ink[0]) - (b1.anchor.x * 16 + ink[1]);
  };
  await load(B, facing(0));
  const gap0 = await inkGap(await lay(B, true));
  const atGap = async (target) => {
    await load(B, facing(Math.round(target - gap0)));
    const r = await lay(B, true);
    return { r, gap: await inkGap(r) };
  };
  // Anchors move in whole pixels, so the measured gaps land near (not at) the targets — about 1.99 px and
  // 2.99 px with the default font; the check asserts the side of the 2 px threshold, recorded in notes.rule3Gap.
  const g15 = await atGap(1.5), g25 = await atGap(2.5);
  out.notes.rule3Gap = { gap0, g15: g15.gap, g25: g25.gap, issues15: keys(g15.r), issues25: keys(g25.r) };
  ck('textsUnder2pxTouch', g15.gap > 1 && g15.gap < 2 && g25.gap >= 2 && tov(g15.r).length === 1 && /overlaps the text/.test(tov(g15.r)[0].message)
    && tov(g25.r).length === 0);

  // --- T3: label text across a lead; text over a symbol
  await load(B, [lyE('L1', 'LabeledNode', 0, 0, 1, 0, { label: 'input' }), lyE('R1', 'Resistor', 2, -6, 2, 2)]);
  const t3 = await lay(B);
  const c3 = await A('getConnectivity', { doc: B, includeNets: false });
  ck('t3LabelAcrossLead', tov(t3).length === 1 && same(tov(t3)[0].elements, ['L1', 'R1']) && /crossed by R1/.test(tov(t3)[0].message)
    && !(c3.data.issues || []).some((i) => i.code === 'symbol_overlap' && same(i.elements, ['L1', 'R1'])));
  await load(B, [lyE('L1', 'LabeledNode', 0, 0, 1, 0, { label: 'input' }), lyE('G1', 'Ground', 2, -1, 2, 0)]);
  const sy = await lay(B);
  ck('textOverSymbol', tov(sy).length === 1 && same(tov(sy)[0].elements, ['G1', 'L1']) && /lies over the symbol of G1/.test(tov(sy)[0].message));

  // --- chip pin names: inside the body, no issue; a wire across the body is symbol_overlap only
  await load(B, [lyE('U1', 'DFlipFlop', 0, 0, 4, 0)]);
  const gc = await A('getCircuit', { doc: B });
  const u1 = gc.data.elements.find((e) => e.id === 'U1');
  const ps = u1.posts.map((p) => p.at);
  const bx = [Math.min(...ps.map((p) => p.x)), Math.min(...ps.map((p) => p.y)), Math.max(...ps.map((p) => p.x)), Math.max(...ps.map((p) => p.y))];
  const wires = ps.map((p, k) => {
    const d = p.x === bx[0] ? [-2, 0] : p.x === bx[2] ? [2, 0] : p.y === bx[1] ? [0, -2] : [0, 2];
    return lyE('W' + (k + 1), 'Wire', p.x, p.y, p.x + d[0], p.y + d[1]);
  });
  const chipEls = [lyE('U1', 'DFlipFlop', 0, 0, 4, 0), ...wires];
  await load(B, chipEls);
  const chip = await lay(B, true);
  const chipSvg = await render({ doc: B, format: 'svg' });
  const chipTexts = chipSvg.data ? svgTextsCircuit(chipSvg.data.content).filter((t) => t.text.trim()) : [];
  const pinBoxes = (chip.data ? chip.data.boxes : []).filter((b) => b.element === 'U1');
  // the chip outline: U1's bounding box as the render's draw set it (pin names do not widen it)
  const u1bb = ((await s.call('docState', B)).elements.find((e) => e.id === 'U1') || {}).bbox;
  const body = u1bb ? [u1bb[0] / 16, u1bb[1] / 16, (u1bb[0] + u1bb[2]) / 16, (u1bb[1] + u1bb[3]) / 16] : null;
  out.notes.chip = { posts: ps, outline: body, boxes: pinBoxes.map((b) => [b.text, b.box]), svgTexts: chipTexts.length };
  ck('chipPinNames', chip.ok && chip.data.issues.length === 0 && pinBoxes.length >= 3 && pinBoxes.length === chipTexts.length && body
    && pinBoxes.every((b) => b.box.x1 >= body[0] && b.box.x2 <= body[2] && b.box.y1 >= body[1] && b.box.y2 <= body[3]));
  await load(B, [...chipEls, lyE('WX', 'Wire', (bx[0] + bx[2]) / 2, bx[1] - 1, (bx[0] + bx[2]) / 2, bx[3] + 1)]);
  const chipX = await lay(B);
  const chipXc = await A('getConnectivity', { doc: B, includeNets: false });
  ck('chipWireAcrossBody', tov(chipX).every((i) => !same(i.elements, ['U1', 'WX'])) && (chipXc.data.issues || []).some((i) => i.code === 'symbol_overlap' && same(i.elements, ['U1', 'WX'])));

  // --- own drawing exempt: a vertical capacitor value, labels on 1-cell stems in four directions, op-amp signs
  const ownEls = [lyE('C1', 'Capacitor', 0, 0, 0, 2, { capacitance: '4.7 uF' }),
    lyE('L1', 'LabeledNode', 10, 0, 11, 0, { label: 'right' }), lyE('L2', 'LabeledNode', 20, 0, 19, 0, { label: 'left' }),
    lyE('L3', 'LabeledNode', 30, 0, 30, 1, { label: 'down' }), lyE('L4', 'LabeledNode', 40, 0, 40, -1, { label: 'up' }), lyE('OA1', 'OpAmp', 50, 0, 54, 0)];
  await load(B, ownEls);
  const own = await lay(B);
  ck('ownDrawingExempt', own.ok && own.data.issues.length === 0 && own.data.texts === 7);

  // --- live texts: readings are listed live, never counted, never reported
  const liveEls = [lyE('O1', 'Output', 0, 0, 0, -1, { show_voltage: true }), lyE('W1', 'Wire', -1, -2, 1, -2),
    lyE('P1', 'Probe', 10, 0, 10, 4), lyE('W2', 'Wire', 10, 2, 12, 2),
    lyE('AM1', 'Ammeter', 20, 0, 20, 4), lyE('W3', 'Wire', 20, 2, 22, 2),
    lyE('W4', 'Wire', 30, 0, 34, 0, { show_current: true }), lyE('W5', 'Wire', 32, -2, 32, 0),
    lyE('V1', 'DCVoltage', 40, 4, 40, 0), lyE('R1', 'Resistor', 40, 0, 44, 0), lyE('G1', 'Ground', 40, 4, 40, 5), lyE('W6', 'Wire', 44, 0, 44, 4), lyE('W7', 'Wire', 44, 4, 40, 4)];
  await load(B, liveEls);
  const lv1 = await lay(B, true);
  const lvRun = await s.call('agentAsync', 'run', { doc: B, span: '5 ms', reset: true }, 60000);
  const lv2 = await lay(B, true);
  const liveOf = (r) => (r.data ? r.data.boxes : []).filter((b) => b.live).map((b) => b.element).sort();
  out.notes.live = { before: lv1.data && lv1.data.issues.map((i) => i.code + ' ' + (i.elements || []).join(',')), live: liveOf(lv1), texts: lv1.data && lv1.data.texts, run: lvRun.data && lvRun.data.reason };
  const notCov = (r) => ((r && r.data && r.data.issues) || []).filter((i) => i.code === 'text_not_covered');
  ck('liveTexts', lv1.ok && lvRun.ok && tov(lv1).length === 0 && tov(lv2).length === 0 && same(keys(lv1), keys(lv2))
    && ['O1', 'P1', 'W4'].every((id) => liveOf(lv1).includes(id)) && lv1.data.texts === lv2.data.texts
    && lv1.data.boxes.filter((b) => !b.live).length === lv1.data.texts
    && notCov(lv1).length === 1 && same(notCov(lv1)[0].elements, ['AM1']));

  // --- values hidden (options flag 16): no issue, the SVG has no 10nF
  const hiddenText = '$ 16 0.000005 10.20027730826997 50 5 50 5e-11\nc 0 0 0 64 0 1e-8 0 0.001\nw 16 -16 16 80 0\n';
  const hid = await A('importCircuit', { doc: B, circuit: hiddenText });
  const hidL = await lay(B);
  const hidSvg = await render({ doc: B, format: 'svg' });
  ck('valuesHidden', hid.ok && hidL.ok && hidL.data.issues.length === 0 && hidSvg.data && !/10nF/.test(hidSvg.data.content));

  // --- highlight does not count (hover: pin letters drawn; select: bold Output/DataRecorder labels)
  const hlEls = [...LAYOUT_T8, lyE('Q1', 'TransistorNPN', 10, 0, 14, 0), lyE('WQ', 'Wire', 9.5, -2, 9.5, 2),
    lyE('O1', 'Output', 20, 0, 20, -1), lyE('DR1', 'DataRecorder', 30, 0, 30, -1)];
  await load(B, hlEls);
  const h0 = await lay(B, true);
  await s.eval(`CircuitJS1Agent.debugSetHighlight(${JSON.stringify(B)}, 'Q1', 'hover')`);
  const h1 = await lay(B, true);
  const hSvg = await render({ doc: B, format: 'svg' });
  await s.eval(`CircuitJS1Agent.debugSetHighlight(${JSON.stringify(B)}, 'Q1', null)`);
  await s.eval(`CircuitJS1Agent.debugSetHighlight(${JSON.stringify(B)}, 'O1', 'select'); CircuitJS1Agent.debugSetHighlight(${JSON.stringify(B)}, 'DR1', 'select')`);
  const h2 = await lay(B, true);
  await s.eval(`CircuitJS1Agent.debugSetHighlight(${JSON.stringify(B)}, 'O1', null); CircuitJS1Agent.debugSetHighlight(${JSON.stringify(B)}, 'DR1', null)`);
  const hTexts = hSvg.data ? svgTextsCircuit(hSvg.data.content).map((t) => t.text) : [];
  out.notes.highlight = { svgTexts: hTexts };
  ck('highlightDoesNotCount', h0.ok && same(keys(h0), keys(h1)) && same(keys(h0), keys(h2)) && same(h0.data.boxes, h1.data.boxes) && same(h0.data.boxes, h2.data.boxes)
    && !h0.data.boxes.some((b) => b.element === 'Q1') && ['B', 'C', 'E'].every((l) => hTexts.includes(l)));

  // --- text_not_covered (Phase 16a fixture): a PolarCapacitor with a wire through its value text
  await load(B, [lyE('C1', 'PolarCapacitor', 0, 0, 0, 4, { capacitance: '10 uF' }), lyE('W1', 'Wire', 1, -1, 1, 5)]);
  const nc = await lay(B);
  const ncI = notCov(nc);
  ck('textNotCovered', nc.ok && ncI.length === 1 && ncI[0].severity === 'info' && same(ncI[0].elements, ['C1']) && !ncI[0].at && /PolarCapacitor/.test(ncI[0].message)
    && tov(nc).length === 0 && ncI[0].key === 'text_not_covered|C1||');
  // debugForceNotCovered: a covered class reported as not covered (the 16b fixture)
  await load(B, [lyE('R1', 'Resistor', 0, 0, 0, 4), lyE('W1', 'Wire', 1, -1, 1, 5)]);
  const fc0 = await lay(B);
  await s.eval(`CircuitJS1Agent.debugForceNotCovered('Resistor')`);
  const fc1 = await lay(B);
  await s.eval(`CircuitJS1Agent.debugForceNotCovered(null)`);
  ck('forceNotCovered', tov(fc0).length === 1 && tov(fc1).length === 0 && notCov(fc1).length === 1 && same(notCov(fc1)[0].elements, ['R1']));

  // --- background = visible: the fixtures above in a background and in the visible document
  const fixtures = [LAYOUT_T8, [lyE('L1', 'LabeledNode', 0, 0, 0, -1, { label: 'VOUT_MAIN' }), lyE('L2', 'LabeledNode', 2, 0, 2, -1, { label: 'VREF_MAIN' })],
    [lyE('L1', 'LabeledNode', 0, 0, 1, 0, { label: 'input' }), lyE('R1', 'Resistor', 2, -6, 2, 2)], chipEls, ownEls, liveEls.filter((e) => !/^(V1|R1|G1|W6|W7)$/.test(e.id))];
  const allFx = [];
  fixtures.forEach((els, k) => { for (const e of els) allFx.push({ ...e, id: e.id + '_' + k, start: { x: e.start.x, y: e.start.y + 20 * k }, end: { x: e.end.x, y: e.end.y + 20 * k } }); });
  const bgImp = await load(B, allFx);
  const visImp = await load(A0, allFx);
  await sleep(500); // the visible tab draws (bounding boxes, transform) before the reads
  const bgL = await lay(B, true);
  const visL = await lay(A0, true);
  const strip = (r) => ({ keys: keys(r), boxes: r.data.boxes.filter((b) => !b.live) });
  ck('backgroundEqualsVisible', bgImp.ok && visImp.ok && bgL.ok && visL.ok && same(strip(bgL), strip(visL)) && tov(bgL).length >= 3);
  out.notes.bgVis = { bgIssues: keys(bgL), visIssues: keys(visL) };

  // --- checkLayout changes no state (SP_AGA_05_02): the visible document running with Show
  // Current and an in-circuit scope, and a background document; one synchronous page turn
  await s.eval(`CircuitJS1.importCircuit(${JSON.stringify(await s.eval(`__H.fetchText('/circuitjs1/circuits/lrc.txt')`))}, false)`);
  const scopeAdd = await A('applyEdits', { doc: A0, edits: [{ op: 'addScope', element: (await A('getCircuit', { doc: A0 })).data.elements[0].id }] });
  await s.eval('CircuitJS1.setSimRunning && CircuitJS1.setSimRunning(true)');
  await sleep(400);
  const noState = await s.eval(`(() => {
    const snap = (doc) => {
      const st = JSON.parse(CircuitJS1Agent.debugDocState(doc));
      return JSON.stringify({ text: JSON.parse(CircuitJS1Agent.call('exportCircuit', JSON.stringify({ doc, format: 'text' }))).data.content,
        elements: st.elements, scopeGraphs: st.scopeGraphs, undo: st.undo, redo: st.redo, modified: st.modified, ui: st.ui,
        session: CircuitJS1Agent.debugSessionState(), view: CircuitJS1Agent.debugViewState(), open: JSON.parse(CircuitJS1Agent.call('listDocuments', '{}')).data.documents });
    };
    const res = {};
    for (const doc of [${JSON.stringify(A0)}, ${JSON.stringify(B)}]) {
      const a = snap(doc);
      const r = JSON.parse(CircuitJS1Agent.call('checkLayout', JSON.stringify({ doc, includeBoxes: true })));
      const b = snap(doc);
      res[doc] = { ok: r.ok, same: a === b, len: a.length };
    }
    return res;
  })()`);
  await s.eval('CircuitJS1.setSimRunning && CircuitJS1.setSimRunning(false)');
  out.notes.noState = noState;
  ck('checkLayoutChangesNoState', scopeAdd.ok && Object.values(noState).every((r) => r.ok && r.same));

  // --- busy document: served during a run; the same result as after it, except the live boxes
  // (one page turn: the run has not completed, a mutation is refused with busy, checkLayout is served)
  await load(B, allFx);
  s.call('agentStart', 'lyBusy', 'run', { doc: B, span: '20 ms', reset: true });
  await sleep(30);
  const turn = await s.eval(`(() => {
    const doc = ${JSON.stringify(B)};
    const ed = JSON.parse(CircuitJS1Agent.call('applyEdits', JSON.stringify({ doc, edits: [{ op: 'describe', id: 'C1_0', description: 'x' }] })));
    const lay = JSON.parse(CircuitJS1Agent.call('checkLayout', JSON.stringify({ doc, includeBoxes: true })));
    const run = (window.__agentRuns || {}).lyBusy;
    return { editCode: ed.ok ? 'applied' : ed.issues[0].code, lay, runDone: !!(run && run.calls) };
  })()`);
  const busy = turn.lay;
  const st = await waitFor(async () => { const x = await s.call('agentStarted', 'lyBusy'); return x && x.calls ? x : null; }, 60000, 'busy run');
  const after = await lay(B, true);
  ck('busyServed', turn.editCode === 'busy' && !turn.runDone && busy.ok && st.result && st.result.ok && same(strip(busy), strip(after)));
  out.notes.busy = { editCode: turn.editCode, runDoneAtRead: turn.runDone, busyOk: busy.ok, runReason: st.result && st.result.data && st.result.data.reason };

  // --- caps: 120 crossings → 100 issues, truncated; 2100 resistors with boxes → 2000 boxes, truncated
  const crossings = [];
  for (let k = 0; k < 120; k++) { const x = (k % 20) * 8, y = Math.floor(k / 20) * 8; crossings.push(lyE('R' + k, 'Resistor', x, y, x, y + 4), lyE('W' + k, 'Wire', x + 1, y - 1, x + 1, y + 5)); }
  await load(B, crossings);
  const cap1 = await lay(B);
  const many = [];
  // grounded chains of 50 (isolated resistors would make the import very slow: each dangling post
  // and isolated group is an issue, PL_AGA backlog "importCircuit scales")
  for (let k = 0; k < 2100; k++) {
    const x = (k % 50) * 4, y = Math.floor(k / 50) * 6;
    many.push(lyE('R' + k, 'Resistor', x, y, x + 4, y));
    if (k % 50 === 0) many.push(lyE('GA' + k, 'Ground', x, y, x, y + 2));
    if (k % 50 === 49) many.push(lyE('GB' + k, 'Ground', x + 4, y, x + 4, y + 2));
  }
  await load(B, many);
  const cap2 = await lay(B, true);
  ck('caps', cap1.ok && cap1.data.issues.length === 100 && cap1.data.truncated === true && cap2.ok && cap2.data.boxes.length === 2000 && cap2.data.truncated === true);

  // --- render "explicit fonts": the same elements listed after and before an op-amp give the
  // same SVG fonts (= their placements'), equal PNGs, equal PNG with a slice break after every element
  const fontEls = [lyE('L1', 'LabeledNode', 0, 0, 0, -1, { label: 'lbl' }), lyE('G1', 'AndGate', 4, 0, 8, 0), lyE('I1', 'Inverter', 12, 0, 16, 0),
    lyE('S1', 'Switch', 20, 0, 24, 0, { label: 'SW' }), lyE('Q1', 'TransistorNPN', 28, 0, 32, 0)];
  const opa = lyE('OA1', 'OpAmp', 36, 0, 40, 0);
  // close the menu bar as a user click elsewhere does (an open menu bar would take the next menu click)
  const closeMenus = async () => {
    await s.key('Escape');
    await s.eval(`document.body.dispatchEvent(new MouseEvent('mousedown', { bubbles: true })); document.body.dispatchEvent(new MouseEvent('mouseup', { bubbles: true })); true`);
    await sleep(200);
  };
  const euro = await s.call('clickMenuPath', [menuTexts('Options'), menuTexts('IEC Gates')]);
  await closeMenus();
  const fontCase = async (els) => {
    await load(B, els);
    await s.eval(`CircuitJS1Agent.debugSetHighlight(${JSON.stringify(B)}, 'Q1', 'hover')`);
    const sv = await render({ doc: B, format: 'svg' });
    const png = await render({ doc: B, format: 'png' });
    await s.eval('CircuitJS1Agent.debugRenderSliceElements(1)');
    const png1 = await render({ doc: B, format: 'png' });
    await s.eval('CircuitJS1Agent.debugRenderSliceElements(0)');
    const lb = await lay(B, true);
    await s.eval(`CircuitJS1Agent.debugSetHighlight(${JSON.stringify(B)}, 'Q1', null)`);
    const texts = sv.data ? svgTextsCircuit(sv.data.content).filter((t) => t.text.trim()) : [];
    const strip64 = (r) => (r && r.data ? r.data.content.replace(/^data:image\/png;base64,/, '') : '');
    return { texts, boxes: lb.data ? lb.data.boxes : [], png: strip64(png), png1: strip64(png1) };
  };
  const after1 = await fontCase([opa, ...fontEls]);
  const before1 = await fontCase([...fontEls, opa]);
  // switch IEC gates off again; the option is stored, so later scenarios would draw IEC gates
  // (a menu click can miss while a menu is still open: check and retry)
  const euroOn = async () => (await s.eval(`localStorage.getItem('euroGates')`)) === 'true';
  const euroClicks = [];
  for (let k = 0; k < 6 && (await euroOn()); k++) {
    // a checked item reads "\u2714IEC Gates" (CheckboxMenuItem)
    const n = await s.call('clickMenuPath', [menuTexts('Options'), menuTexts('IEC Gates').map((t) => '\u2714' + t)]);
    euroClicks.push(n);
    if (n === 2) await closeMenus();
  }
  out.notes.euroRestore = { clicks: euroClicks, stored: await s.eval(`localStorage.getItem('euroGates')`) };
  ck('iecGatesRestored', !(await euroOn()));
  const fontOf = (t) => [t.font.style, t.font.weight, t.font.size, t.font.family].join(' ');
  const units = 'normal normal 12px sans-serif';
  const pinLetters = (c) => c.texts.filter((t) => /^[BCE]$/.test(t.text));
  const d1 = await s.call('pngDiff', after1.png, before1.png, []);
  const d2 = await s.call('pngDiff', after1.png, after1.png1, []);
  const d3 = await s.call('pngDiff', before1.png, before1.png1, []);
  out.notes.fonts = { euroToggled: euro, after: after1.texts.map((t) => t.text + ':' + fontOf(t)), before: before1.texts.map((t) => t.text + ':' + fontOf(t)), pngOrder: d1.diff, pngSlices: [d2.diff, d3.diff] };
  const nonPin = (c) => c.texts.filter((t) => !/^[BCE]$/.test(t.text));
  const tf = (c) => c.texts.map((t) => t.text + ':' + fontOf(t)).sort();
  ck('explicitFonts', euro === 2 && after1.texts.length >= 8 && same(tf(after1), tf(before1))
    && matchTextsToBoxes(nonPin(after1), after1.boxes).length === 0 && matchTextsToBoxes(nonPin(before1), before1.boxes).length === 0
    && ['lbl', 'SW', '&', '1'].every((x) => after1.texts.some((t) => t.text === x && fontOf(t) === units) && before1.texts.some((t) => t.text === x && fontOf(t) === units))
    && pinLetters(after1).length === 3 && pinLetters(after1).every((t) => fontOf(t) === units) && pinLetters(before1).every((t) => fontOf(t) === units)
    && d1.diff === 0 && d2.diff === 0 && d3.diff === 0);

  // --- an element draw that throws leaves nothing behind (the session's measuring context is
  // restored in a finally): the next render of the same circuit equals the one before
  await load(B, ownEls);
  const pngOf = async () => { const r = await render({ doc: B, format: 'png' }); return r && r.data ? r.data.content.replace(/^data:image\/png;base64,/, '') : null; };
  const pA = await pngOf();
  const exBefore = s.exceptions.length;
  await s.eval('CircuitJS1Agent.debugFailNextOffscreenDraw()');
  const failedRender = await render({ doc: B, format: 'png' });
  await sleep(200);
  forcedExceptions += s.exceptions.length - exBefore;
  const pB = await pngOf();
  const dAB = pA && pB ? await s.call('pngDiff', pA, pB, []) : { diff: -1 };
  out.notes.drawFailure = { failed: failedRender && failedRender.issues && failedRender.issues[0] && failedRender.issues[0].code, diff: dAB.diff, size: dAB.size || [dAB.a, dAB.b] };
  ck('throwingDrawLeavesNoState', failedRender && failedRender.ok === false && failedRender.issues[0].code === 'internal_error' && dAB.diff === 0);

  // --- layout equals drawing, every catalogue type, four directions, option variants, examples
  const types = (await A('listTypes', {})).data.types.map((t) => t.type);
  const everyType = { compared: 0, texts: 0, problems: {}, notCoveredWithTexts: [], partlyCovered: [], importFailed: [], uncoveredSilent: [] };
  const rot = (d, k) => { let [x, y] = [d.dx, d.dy]; for (let i = 0; i < k; i++) [x, y] = [-y, x]; return [x, y]; };
  const compareDoc = async (label, run) => {
    if (run) await s.call('agentAsync', 'run', { doc: B, span: '1 us', reset: true }, 30000);
    const sv = await render({ doc: B, format: 'svg' });
    const lb = await lay(B, true);
    const texts = sv.data ? svgTextsCircuit(sv.data.content).filter((t) => t.text.trim()) : [];
    const nc = notCov(lb);
    everyType.compared++;
    everyType.texts += texts.length;
    const boxes = lb.data ? lb.data.boxes : [];
    if (nc.length && !boxes.length) { if (texts.length) everyType.notCoveredWithTexts.push(label); return; }
    // with not-covered elements in the document (counter.txt), an SVG text that no box claims may be
    // theirs: only the texts with a same-string box and every box are compared then
    let p = matchTextsToBoxes(texts, boxes);
    if (nc.length) {
      everyType.partlyCovered.push(label);
      p = p.filter((x) => x.boxWithoutSvgText || x.candidates.length);
    }
    if (p.length) everyType.problems[label] = p.slice(0, 4);
  };
  for (const type of types) {
    const ti = (await A('describeType', { type })).data;
    for (let k = 0; k < 4; k++) {
      const [dx, dy] = rot(ti.defaultSize, k);
      const imp = await load(B, [lyE('X1', type, 10, 10, 10 + dx, 10 + dy)]);
      if (!imp.ok) { everyType.importFailed.push(type + '/' + k + ': ' + (imp.issues || []).map((i) => i.code).join(',')); continue; }
      await compareDoc(type + '/' + k, false);
    }
  }
  const variants = {
    mosfetShowVt: [lyE('M1', 'NMOS', 0, 0, 4, 0, null, 32 | 2)],
    potShowValues: [lyE('P1', 'Potentiometer', 0, 0, 4, 0, null, 1), lyE('R1', 'Resistor', 0, 0, 0, 4), lyE('R2', 'Resistor', 4, 0, 4, 4), lyE('V1', 'DCVoltage', 0, 4, 4, 4)],
    switchLabel: [lyE('S1', 'Switch', 0, 0, 4, 0, { label: 'SW1' }), lyE('S2', 'Switch', 10, 0, 10, 4, { label: 'SW2' })],
    textTwoLinesBar: [lyE('T1', 'Text', 0, 0, 4, 0, { text: 'first\\nsecond', draw_bar: true })],
    outputShowVoltage: [lyE('O1', 'Output', 0, 0, 0, -1, { show_voltage: true })],
    wireShowCurrent: [lyE('W1', 'Wire', 0, 0, 4, 0, { show_current: true })],
  };
  for (const [name, els] of Object.entries(variants)) {
    for (let k = 0; k < 4; k++) {
      const rotEls = els.map((e) => { const [x1, y1] = rot({ dx: e.start.x, dy: e.start.y }, k); const [x2, y2] = rot({ dx: e.end.x, dy: e.end.y }, k);
        return { ...e, start: { x: x1 + 20, y: y1 + 20 }, end: { x: x2 + 20, y: y2 + 20 } }; });
      const imp = await load(B, rotEls);
      if (!imp.ok) { everyType.importFailed.push(name + '/' + k + ': ' + (imp.issues || []).map((i) => i.code).join(',')); continue; }
      await compareDoc(name + '/' + k, name === 'potShowValues');
    }
  }
  for (const ex of ['555int.txt', 'counter.txt', 'alu74181.txt']) {
    const text = await s.eval(`__H.fetchText('/circuitjs1/circuits/' + ${JSON.stringify(ex)})`);
    await A('importCircuit', { doc: B, circuit: text });
    await compareDoc(ex, false);
  }
  out.notes.everyType = everyType;
  // the only rejected geometries: a Transformer with its end straight below or above its start (zero_length)
  ck('layoutEqualsDrawing', everyType.compared > 500 && Object.keys(everyType.problems).length === 0
    && same(everyType.importFailed, ['Transformer/1: zero_length', 'Transformer/3: zero_length']));

  // --- example corpus: the clean examples of agent_overlap report no text_overlap unless calibrated
  const dirty = {};
  for (const name of OVERLAP_CLEAN_EXAMPLES) {
    const text = await s.eval(`__H.fetchText('/circuitjs1/circuits/' + ${JSON.stringify(name)})`);
    await A('importCircuit', { doc: B, circuit: text });
    const r = await lay(B);
    const found = tov(r).map((i) => i.elements.join('|')).sort();
    const cal = (LAYOUT_CALIBRATION[name] || []).map((c) => c.pair).sort();
    if (!r.ok || !same(found, cal)) dirty[name] = { found, calibrated: cal };
  }
  out.notes.examples = dirty;
  ck('examplesAsCalibrated', Object.keys(dirty).length === 0);

  // --- static text sites (SP_AGA_05_02 "Drawing paints the layout")
  const ts = textSitesCheck();
  out.notes.textSites = { withSites: ts.withSites, failures: ts.failures };
  ck('textSitesStatic', ts.failures.length === 0);

  await A('importCircuit', { doc: A0, circuit: await s.eval(`__H.fetchText('/circuitjs1/circuits/lrc.txt')`) });
  await A('closeDocument', { doc: B, discardChanges: true });
  // the forced draw failure reaches the global handler on purpose (RULE_ERR_004)
  ck('noPageExceptions', s.exceptions.length === exMark + forcedExceptions);
  if (s.exceptions.length !== exMark) out.exceptions = s.exceptions.slice(exMark).map((e) => e.slice(0, 600));
  fs.writeFileSync(path.join(OUT_DIR, 'agent_layout.json'), JSON.stringify(out, null, 2));
  const failed = Object.keys(out.checks).filter((n) => !out.checks[n]);
  report('AG.agent_layout', failed.length === 0, { checks: Object.keys(out.checks).length, failed, compared: everyType.compared, details: path.join(OUT_DIR, 'agent_layout.json') });
}

// ---------------------------------------------------------------- layout_cost (SP_AGA_05_01 checkLayout cost)
// The measurement mix (Resistor, Capacitor, LabeledNode, TransistorNPN, OpAmp) of COST_SIZES
// elements (default 100,2500), median of COST_RUNS (5) runs of importCircuit, applyEdits (one
// `set`) and getConnectivity on the visible document, and of checkLayout on the visible and on a
// background document when the contract exists. Written to OUT_DIR/layout_cost.json.
function costMix(n, typeList, pitch) {
  const types = typeList || ['Resistor', 'Capacitor', 'LabeledNode', 'TransistorNPN', 'OpAmp'];
  const cols = Math.ceil(Math.sqrt(n));
  const els = [];
  for (let i = 0; i < n; i++) {
    const type = types[i % types.length];
    const x = (i % cols) * (pitch || 8), y = Math.floor(i / cols) * (pitch || 8);
    const e = { id: 'E' + (i + 1), type, start: { x, y }, end: type === 'LabeledNode' ? { x, y: y - 1 } : { x: x + 4, y } };
    if (type === 'LabeledNode') e.properties = { label: 'N' + (i + 1) };
    els.push(e);
  }
  return { elements: els };
}

async function scenarioLayoutCost(s) {
  const sizes = (process.env.COST_SIZES || '100,2500').split(',').map(Number);
  const runs = +(process.env.COST_RUNS || 5);
  const A = (op, args) => s.call('agentCall', op, args);
  await resetApp(s);
  const exMark = s.exceptions.length;
  await s.eval(`window.__timed = (op, args) => { const t = performance.now(); const r = JSON.parse(CircuitJS1Agent.call(op, JSON.stringify(args))); return { ms: performance.now() - t, ok: r.ok, r }; }`);
  const T = async (op, args) => s.eval(`(() => { const x = window.__timed(${JSON.stringify(op)}, ${JSON.stringify(args)}); return { ms: x.ms, ok: x.ok, issues: x.ok ? null : x.r.issues }; })()`);
  const docs = (await A('listDocuments', {})).data.documents;
  const vis = docs.find((d) => d.active).doc;
  const bg = (await A('createDocument', { title: 'Cost' })).data.doc;
  const hasLayout = (await A('checkLayout', { doc: vis })).ok === true;
  const med = (a) => { const b = [...a].sort((x, y) => x - y); return Math.round(b[Math.floor(b.length / 2)] * 100) / 100; };
  const out = { runs, hasLayout, sizes: {}, render: {} };
  const parts = (process.env.COST_PARTS || 'ops,render').split(',');
  for (const n of parts.includes('ops') ? sizes : []) {
    const mix = costMix(n);
    await s.eval(`window.__mix = ${JSON.stringify(mix)}`);
    const t = { importCircuit: [], applyEdits: [], getConnectivity: [], checkLayoutVisible: [], checkLayoutBackground: [] };
    let failed = null;
    for (let k = 0; k < runs; k++) {
      const imp = await s.eval(`(() => { const x = window.__timed('importCircuit', { doc: ${JSON.stringify(vis)}, circuit: window.__mix }); return { ms: x.ms, ok: x.ok }; })()`);
      if (!imp.ok) failed = 'import';
      t.importCircuit.push(imp.ms);
      const ed = await T('applyEdits', { doc: vis, edits: [{ op: 'set', id: 'E1', properties: { resistance: k % 2 ? '1k' : '2k' } }] });
      if (!ed.ok) failed = 'applyEdits ' + JSON.stringify(ed.issues);
      t.applyEdits.push(ed.ms);
      t.getConnectivity.push((await T('getConnectivity', { doc: vis })).ms);
      if (hasLayout) t.checkLayoutVisible.push((await T('checkLayout', { doc: vis })).ms);
    }
    if (hasLayout) {
      await s.eval(`(() => window.__timed('importCircuit', { doc: ${JSON.stringify(bg)}, circuit: window.__mix }))()`);
      for (let k = 0; k < runs; k++) t.checkLayoutBackground.push((await T('checkLayout', { doc: bg })).ms);
    }
    out.sizes[n] = { failed, median: Object.fromEntries(Object.entries(t).filter(([, v]) => v.length).map(([k, v]) => [k, med(v)])), all: t };
    await A('importCircuit', { doc: vis, circuit: { elements: [] } });
  }
  // Render time (PL_AGA Phase 16a, lead): the draw time of a PNG (RENDER_FORMAT=svg: SVG) render (scale 1) of a background
  // document — the sum of its render slices (measure and draw passes, the encode start), median of
  // COST_RUNS — for RENDER_MIXES (mix = the measurement mix, or one type) of RENDER_SIZE elements.
  // RENDER_BEFORE=<layout_cost.json of the step-0 build> fails a median more than 5 % above it.
  if (parts.includes('render')) {
    const rn = +(process.env.RENDER_SIZE || 2500);
    for (const m of (process.env.RENDER_MIXES || 'mix,Resistor,Wire,OpAmp').split(',')) {
      // a 6-cell pitch keeps the 2500-element image below the 40-megapixel render limit
      await s.eval(`window.__mix = ${JSON.stringify(costMix(rn, m === 'mix' ? null : [m], 6))}`);
      await s.eval(`(() => window.__timed('importCircuit', { doc: ${JSON.stringify(bg)}, circuit: window.__mix }).ok)()`);
      const once = async () => {
        await s.call('startSliceProbe');
        const r = await s.call('agentAsync', 'render', { doc: bg, format: process.env.RENDER_FORMAT || 'png', scale: 1 }, 600000);
        const list = await s.call('stopSliceProbe');
        if (!(r && r.ok)) throw new Error('render failed: ' + JSON.stringify(r && (r.issues || r)).slice(0, 300));
        return list.filter((x) => x.op === 'render').reduce((a, x) => a + x.ms, 0);
      };
      await once(); // warm-up (first draw of each class)
      const ms = [];
      for (let k = 0; k < runs; k++) ms.push(await once());
      out.render[m] = { size: rn, median: med(ms), all: ms.map((x) => Math.round(x * 10) / 10) };
    }
  }
  await A('closeDocument', { doc: bg, discardChanges: true });
  fs.writeFileSync(path.join(OUT_DIR, 'layout_cost.json'), JSON.stringify(out, null, 2));
  const summary = Object.fromEntries(Object.entries(out.sizes).map(([n, v]) => [n, v.median]));
  summary.render = Object.fromEntries(Object.entries(out.render).map(([m, v]) => [m, v.median]));
  // [SP_AGA_05_01] checkLayout budget (draft-compiled build): visible ≤ 8 ms at 100 and ≤ 120 ms at
  // 2500 elements; background the same plus the scoped bind (≤ 3 ms). COST_BEFORE=<layout_cost.json of
  // the pre-16a build> also checks importCircuit, applyEdits and getConnectivity within 10 %.
  const BUDGET = { 100: 8, 2500: 120 };
  const over = [];
  for (const [n, v] of Object.entries(out.sizes)) {
    if (!hasLayout || !BUDGET[n]) continue;
    if (v.median.checkLayoutVisible > BUDGET[n]) over.push(`${n} visible ${v.median.checkLayoutVisible} > ${BUDGET[n]} ms`);
    if (v.median.checkLayoutBackground > BUDGET[n] + 3) over.push(`${n} background ${v.median.checkLayoutBackground} > ${BUDGET[n] + 3} ms`);
  }
  if (process.env.COST_BEFORE) {
    const before = JSON.parse(fs.readFileSync(process.env.COST_BEFORE, 'utf8'));
    for (const [n, v] of Object.entries(out.sizes)) {
      const b = before.sizes && before.sizes[n];
      if (!b) continue;
      for (const op of ['importCircuit', 'applyEdits', 'getConnectivity']) {
        if (v.median[op] > b.median[op] * 1.1) over.push(`${n} ${op} ${v.median[op]} > 110 % of ${b.median[op]} ms`);
      }
    }
  }
  if (process.env.RENDER_BEFORE) {
    const before = JSON.parse(fs.readFileSync(process.env.RENDER_BEFORE, 'utf8'));
    for (const [m, v] of Object.entries(out.render)) {
      const b = before.render && before.render[m];
      if (b && v.median > b.median * 1.05) over.push(`render ${m} ${v.median} > 105 % of ${b.median} ms`);
    }
  }
  out.over = over;
  fs.writeFileSync(path.join(OUT_DIR, 'layout_cost.json'), JSON.stringify(out, null, 2));
  report('AG.layout_cost', s.exceptions.length === exMark && Object.values(out.sizes).every((v) => !v.failed) && over.length === 0, { hasLayout, ...summary, over });
}

// ---------------------------------------------------------------- import_cost (importCircuit scaling)
// Backlog "importCircuit scales" (PL_AGA): an N sweep (IMPORT_SIZES, default 250,500,1000,2000) of
// the fixtures IMPORT_FIXTURES (default unconnected,chains,mix,wires): `unconnected` = N resistors
// that touch nothing (two dangling posts and an isolated group each), `chains` = grounded chains
// of 50 resistors, `mix` = the layout_cost measurement mix, `wires` = grounded rows of 25
// resistors joined by 25 collinear wires. Median of IMPORT_RUNS (3) runs on the visible
// document of: agent importCircuit, applyEdits (one `set`), getConnectivity, importCircuit into a
// background document, and the user path — CircuitJS1.importCircuit of the same circuit as text
// (`userImport`: the synchronous call; `userFrames`: until two animation frames later, which
// includes the frame's analysis and stamp). Fails when a time grows faster than 1.5 x the size
// ratio between consecutive sizes (time(2N)/time(N) > 3; only above 50 ms) or exceeds a budget:
// importCircuit ≤ 5 s at 2000 unconnected, ≤ 3 s at 2500 mix; applyEdits ≤ 1 s up to 2500.
// IMPORT_PROFILE=<fixture>:<n>[,...] records a CPU profile of one importCircuit and one applyEdits
// of that size (OUT_DIR/import_cost/*.cpuprofile, open in Chrome DevTools) and lists the top
// functions by self and total time in import_cost.json. Not in the default run (timing).
function importFixture(kind, n) {
  if (kind === 'mix') return costMix(n);
  if (kind === 'unconnected') return costMix(n, ['Resistor']);
  if (kind === 'chains') {
    // like the MCP e2e layout-sizing fixture: rows of 50 resistors, a Ground at both row ends
    const els = [];
    for (let k = 0; k < n; k++) {
      const x = (k % 50) * 4, y = Math.floor(k / 50) * 6;
      els.push({ id: 'E' + (k + 1), type: 'Resistor', start: { x, y }, end: { x: x + 4, y } });
      if (k % 50 === 0) els.push({ id: 'GA' + k, type: 'Ground', start: { x, y }, end: { x, y: y + 2 } });
      if (k % 50 === 49 || k === n - 1) els.push({ id: 'GB' + k, type: 'Ground', start: { x: x + 4, y }, end: { x: x + 4, y: y + 2 } });
    }
    return { elements: els };
  }
  if (kind === 'wires') {
    // rows of 25 resistors joined by 25 collinear wires (n elements, half of them wires), a Ground
    // at both row ends: the wire closure, wire-current order and the wire rules at scale
    const els = [];
    for (let k = 0; k < n; k++) {
      const row = Math.floor(k / 50), col = k % 50, y = row * 6, x = Math.floor(col / 2) * 6 + (col % 2 ? 4 : 0);
      els.push(col % 2 ? { id: 'W' + (k + 1), type: 'Wire', start: { x, y }, end: { x: x + 2, y } }
        : { id: k === 0 ? 'E1' : 'R' + (k + 1), type: 'Resistor', start: { x, y }, end: { x: x + 4, y } });
      if (col === 0) els.push({ id: 'GA' + k, type: 'Ground', start: { x, y }, end: { x, y: y + 2 } });
      if (col === 49 || k === n - 1) els.push({ id: 'GB' + k, type: 'Ground', start: { x: x + (col % 2 ? 2 : 4), y }, end: { x: x + (col % 2 ? 2 : 4), y: y + 2 } });
    }
    return { elements: els };
  }
  throw new Error('unknown IMPORT_FIXTURES entry ' + kind);
}

// Top functions of a CDP CPU profile: self time and total (inclusive, once per sample stack) in ms.
function profileTop(profile, limit) {
  const byId = new Map(profile.nodes.map((nd) => [nd.id, nd]));
  const parent = new Map();
  for (const nd of profile.nodes) for (const c of nd.children || []) parent.set(c, nd.id);
  const name = (nd) => `${nd.callFrame.functionName || '(anonymous)'} ${(nd.callFrame.url || '').split('/').pop()}:${nd.callFrame.lineNumber + 1}`;
  const self = new Map(), total = new Map();
  const dts = profile.timeDeltas || [];
  for (let i = 0; i < profile.samples.length; i++) {
    const dt = (dts[i + 1] !== undefined ? dts[i + 1] : 0) / 1000;
    let nd = byId.get(profile.samples[i]);
    self.set(name(nd), (self.get(name(nd)) || 0) + dt);
    const seen = new Set();
    for (let id = nd.id; id !== undefined; id = parent.get(id)) {
      const k = name(byId.get(id));
      if (!seen.has(k)) { seen.add(k); total.set(k, (total.get(k) || 0) + dt); }
    }
  }
  const top = (m) => [...m.entries()].sort((a, b) => b[1] - a[1]).slice(0, limit).map(([k, v]) => [k, Math.round(v)]);
  return { self: top(self), total: top(total) };
}

async function scenarioImportCost(s) {
  const sizes = (process.env.IMPORT_SIZES || '250,500,1000,2000').split(',').map(Number);
  const fixtures = (process.env.IMPORT_FIXTURES || 'unconnected,chains,mix,wires').split(',');
  const runs = +(process.env.IMPORT_RUNS || 3);
  const profiles = (process.env.IMPORT_PROFILE || '').split(',').filter(Boolean);
  const A = (op, args) => s.call('agentCall', op, args);
  await resetApp(s);
  const exMark = s.exceptions.length;
  await s.eval(`window.__timed = (op, args) => { const t = performance.now(); const r = JSON.parse(CircuitJS1Agent.call(op, JSON.stringify(args))); return { ms: performance.now() - t, ok: r.ok, r }; }`);
  const T = async (op, args) => s.eval(`(() => { const x = window.__timed(${JSON.stringify(op)}, ${JSON.stringify(args)}); return { ms: x.ms, ok: x.ok, issues: x.ok ? null : x.r.issues }; })()`);
  // the user path: the synchronous CircuitJS1.importCircuit and the time until two frames later
  const userImport = async () => s.eval(`new Promise((res) => { const t = performance.now(); CircuitJS1.importCircuit(window.__text, false);
    const sync = performance.now() - t; requestAnimationFrame(() => requestAnimationFrame(() => res({ sync, frames: performance.now() - t, n: CircuitJS1.getElementCount() }))); })`);
  const docs = (await A('listDocuments', {})).data.documents;
  const vis = docs.find((d) => d.active).doc;
  const bg = (await A('createDocument', { title: 'ImportCost' })).data.doc;
  const med = (a) => { const b = [...a].sort((x, y) => x - y); return Math.round(b[Math.floor(b.length / 2)] * 10) / 10; };
  const profDir = path.join(OUT_DIR, 'import_cost');
  if (profiles.length) fs.mkdirSync(profDir, { recursive: true });
  const out = { runs, running: (await s.call('simInfo') || {}).running, fixtures: {}, profiles: {} };
  const ops = ['importCircuit', 'applyEdits', 'getConnectivity', 'importBackground', 'userImport', 'userFrames'];
  for (const kind of fixtures) {
    out.fixtures[kind] = {};
    for (const n of sizes) {
      const fx = importFixture(kind, n);
      await s.eval(`window.__mix = ${JSON.stringify(fx)}`);
      const t = Object.fromEntries(ops.map((o) => [o, []]));
      let failed = null, issues = null;
      for (let k = 0; k < runs; k++) {
        const imp = await s.eval(`(() => { const x = window.__timed('importCircuit', { doc: ${JSON.stringify(vis)}, circuit: window.__mix });
          return { ms: x.ms, ok: x.ok, errors: x.ok ? x.r.connectivity.errorCount : null, warnings: x.ok ? x.r.connectivity.warningCount : null, issues: x.ok ? null : x.r.issues }; })()`);
        if (!imp.ok) failed = 'import ' + JSON.stringify(imp.issues).slice(0, 300);
        issues = { errors: imp.errors, warnings: imp.warnings };
        t.importCircuit.push(imp.ms);
        const ed = await T('applyEdits', { doc: vis, edits: [{ op: 'set', id: 'E1', properties: { resistance: k % 2 ? '1k' : '2k' } }] });
        if (!ed.ok) failed = 'applyEdits ' + JSON.stringify(ed.issues).slice(0, 300);
        t.applyEdits.push(ed.ms);
        t.getConnectivity.push((await T('getConnectivity', { doc: vis })).ms);
        const bi = await s.eval(`(() => { const x = window.__timed('importCircuit', { doc: ${JSON.stringify(bg)}, circuit: window.__mix }); return { ms: x.ms, ok: x.ok }; })()`);
        if (!bi.ok) failed = 'background import';
        t.importBackground.push(bi.ms);
        if (k === 0) await s.eval(`window.__text = CircuitJS1.exportCircuit()`);
        const u = await userImport();
        if (u.n !== fx.elements.length) failed = `user import ${u.n} elements of ${fx.elements.length}`;
        t.userImport.push(u.sync);
        t.userFrames.push(u.frames);
      }
      for (const spec of profiles) {
        if (spec !== `${kind}:${n}`) continue;
        await s.cdp.send('Profiler.enable');
        await s.cdp.send('Profiler.setSamplingInterval', { interval: 500 });
        const prof = {};
        for (const [op, expr] of [['importCircuit', `window.__timed('importCircuit', { doc: ${JSON.stringify(vis)}, circuit: window.__mix }).ms`],
          ['applyEdits', `window.__timed('applyEdits', { doc: ${JSON.stringify(vis)}, edits: [{ op: 'set', id: 'E1', properties: { resistance: '3k' } }] }).ms`],
          ['userImport', `(() => { const t = performance.now(); CircuitJS1.importCircuit(window.__text, false); return performance.now() - t; })()`]]) {
          await s.cdp.send('Profiler.start');
          const ms = await s.eval(expr);
          const { profile } = await s.cdp.send('Profiler.stop');
          fs.writeFileSync(path.join(profDir, `${kind}_${n}_${op}.cpuprofile`), JSON.stringify(profile));
          prof[op] = { ms: Math.round(ms), ...profileTop(profile, 25) };
        }
        await s.cdp.send('Profiler.disable');
        out.profiles[spec] = prof;
      }
      out.fixtures[kind][n] = { elements: fx.elements.length, failed, issues, median: Object.fromEntries(ops.map((o) => [o, med(t[o])])), all: t };
      log(`import_cost ${kind} ${n}: ${JSON.stringify(out.fixtures[kind][n].median)}`);
      fs.writeFileSync(path.join(OUT_DIR, 'import_cost.json'), JSON.stringify(out, null, 2));
    }
  }
  await A('importCircuit', { doc: vis, circuit: { elements: [] } });
  await A('closeDocument', { doc: bg, discardChanges: true });
  // growth: time(n2)/time(n1) ≤ 1.5 · n2/n1 between consecutive sizes (above a 50 ms floor), and the budgets
  const over = [];
  const growth = {};
  for (const [kind, bySize] of Object.entries(out.fixtures)) {
    const ns = Object.keys(bySize).map(Number).sort((a, b) => a - b);
    for (const op of ops) {
      const ratios = [];
      for (let i = 1; i < ns.length; i++) {
        const a = bySize[ns[i - 1]].median[op], b = bySize[ns[i]].median[op];
        const r = Math.round((b / Math.max(a, 1e-3)) * 100) / 100;
        ratios.push(r);
        if (b > 50 && b > a * 1.5 * (ns[i] / ns[i - 1])) over.push(`${kind} ${op} ${ns[i - 1]}→${ns[i]}: ${a} → ${b} ms (x${r})`);
      }
      growth[`${kind}.${op}`] = ratios;
    }
    for (const n of ns) {
      const m = bySize[n].median;
      if (kind === 'unconnected' && n === 2000 && m.importCircuit > 5000) over.push(`unconnected 2000 importCircuit ${m.importCircuit} > 5000 ms`);
      if (kind === 'mix' && n === 2500 && m.importCircuit > 3000) over.push(`mix 2500 importCircuit ${m.importCircuit} > 3000 ms`);
      if (n <= 2500 && m.applyEdits > 1000) over.push(`${kind} ${n} applyEdits ${m.applyEdits} > 1000 ms`);
    }
  }
  out.growth = growth;
  out.over = over;
  fs.writeFileSync(path.join(OUT_DIR, 'import_cost.json'), JSON.stringify(out, null, 2));
  const summary = Object.fromEntries(Object.entries(out.fixtures).map(([k, v]) => [k, Object.fromEntries(Object.entries(v).map(([n, x]) => [n, x.failed || x.median]))]));
  report('AG.import_cost', s.exceptions.length === exMark && Object.values(out.fixtures).every((v) => Object.values(v).every((x) => !x.failed)) && over.length === 0, { ...summary, over });
}

// ---------------------------------------------------------------- agent_echo (bounded echo of client values)
// PL_AGA backlog "Bounded echo of client values in Agent API issue messages" (SP_AGA §01_07): a
// 1 MB string fed into argument kinds that issue messages quote — operation name, document
// handle, type name, property key and model-name value of `set`, element IDs (edit, scope, add),
// a post, a net and reading names, a checkpoint ID, a model name (defineModel, listModels), a model
// parameter key, a rule line, file paths (openFile over the in-page fake file system), a JSON
// element key and a label text (agent `set` at its 1000-character limit, and 1 MB through a text
// import). Every issue message (result issues,
// connectivity delta, getConnectivity issues) is at most ECHO_MAX_MESSAGE (400) characters, the
// quoting sites show the clipped value with its length ("… (1048576 chars)"; a 900-character model
// name for the model-name check, a 1 MB value is rejected by its length), the whole answer stays
// below 64 kB and each call answers within 2 s.
async function echoProbe() {
  const big = 'Q'.repeat(1 << 20);
  const call = (op, args) => { const t = performance.now(); const text = CircuitJS1Agent.call(op, typeof args === 'string' ? args : JSON.stringify(args)); return { ms: performance.now() - t, size: text.length, r: JSON.parse(text) }; };
  const out = {};
  const doc = call('createDocument', { title: 'Echo' }).r.data.doc;
  call('importCircuit', { doc, circuit: { elements: [
    { id: 'R1', type: 'Resistor', start: { x: 0, y: 0 }, end: { x: 4, y: 0 } },
    { id: 'D1', type: 'Diode', start: { x: 0, y: 4 }, end: { x: 4, y: 4 } },
    { id: 'L1', type: 'LabeledNode', start: { x: 8, y: 0 }, end: { x: 8, y: -1 }, properties: { label: 'LBLMARK' } }] } });
  const text = call('exportCircuit', { doc, format: 'text' }).r.data.content;
  // more than 40 session diode models: the unknown-model hint lists 40, then a count
  const defs = Array.from({ length: 45 }, (_, i) => ({ op: 'defineModel', model: { kind: 'diode', name: 'echo-d' + i, parameters: {} } }));
  out.defined = call('applyEdits', { doc, edits: defs }).r.ok;
  // JSON importer sites through openFile (in-page fake file system): a ModelText name in an entry
  // problem (rules that do not parse), the element key of an unresolved model, and entry variants
  // that may throw while loading ("entry failed to load")
  const json = JSON.parse(call('exportCircuit', { doc, format: 'json' }).r.data.content);
  const longName = 'N' + big.slice(0, 200000);
  const files = window.__fakeFiles || {};
  files['/tmp/echo_rule.json'] = JSON.stringify(Object.assign({}, json, { models: [{ kind: 'logic', name: longName, modelText: '! ' + longName + ' 0 A,B Y ' + longName + ' 1\\q11\\n' }] }));
  const d1 = Object.keys(json.elements).find((k) => json.elements[k].type === 'Diode');
  const els = Object.assign({}, json.elements);
  els[big] = Object.assign({}, els[d1], { properties: Object.assign({}, els[d1].properties, { model: 'echo-no-such-model' }) });
  delete els[d1];
  files['/tmp/echo_key.json'] = JSON.stringify(Object.assign({}, json, { elements: els }));
  files['/tmp/echo_entry.json'] = JSON.stringify(Object.assign({}, json, { models: [{ kind: 'subcircuit', name: longName, modelText: '. ' + longName + ' 0 ' + big.slice(0, 1000) }] }));
  const at64 = 'B'.repeat(64), at65 = 'B'.repeat(65), pair = 'a'.repeat(63) + '\u{1F600}' + 'a'.repeat(10);
  const cases = {
    op: [big, {}, true],
    doc: ['getCircuit', { doc: big }, true],
    typeName: ['describeType', { type: big }, true],
    setKey: ['applyEdits', { doc, edits: [{ op: 'set', id: 'R1', properties: { [big]: 1 } }] }, true],
    setValue: ['applyEdits', { doc, edits: [{ op: 'set', id: 'R1', properties: { resistance: big } }] }, false],
    setModel: ['applyEdits', { doc, edits: [{ op: 'set', id: 'D1', properties: { model: big } }] }, false],
    setModelName: ['applyEdits', { doc, edits: [{ op: 'set', id: 'D1', properties: { model: 'M'.repeat(900) } }] }, true],
    editId: ['applyEdits', { doc, edits: [{ op: 'delete', id: big }] }, true],
    editOp: ['applyEdits', { doc, edits: [{ op: big, id: 'R1' }] }, true],
    addId: ['applyEdits', { doc, edits: [{ op: 'add', element: { id: big, type: 'Resistor', start: { x: 20, y: 0 } } }] }, true],
    scopeElement: ['importCircuit', { doc, circuit: { elements: [{ id: 'R9', type: 'Resistor', start: { x: 0, y: 0 }, end: { x: 4, y: 0 } }], scopes: [{ element: big }] } }, true],
    markPost: ['applyEdits', { doc, edits: [{ op: 'markOpen', posts: ['R1.' + big] }] }, true],
    readNet: ['read', { doc, targets: [{ net: big }] }, true],
    readPost: ['read', { doc, targets: [{ post: 'R1.' + big }] }, true],
    readNames: ['read', { doc, targets: [{ element: 'R1', name: big }, { element: 'R1', name: big }] }, true],
    checkpoint: ['restoreCheckpoint', { doc, checkpointId: big }, true],
    modelName: ['applyEdits', { doc, edits: [{ op: 'defineModel', model: { kind: 'diode', name: big, parameters: {} } }] }, true],
    modelParam: ['applyEdits', { doc, edits: [{ op: 'defineModel', model: { kind: 'diode', name: 'echo-d', parameters: { [big]: 1 } } }] }, true],
    listModel: ['listModels', { kind: 'diode', name: big }, true],
    ruleLine: ['applyEdits', { doc, edits: [{ op: 'defineModel', model: { kind: 'logic', name: 'echo-l', inputs: ['A'], outputs: ['B'], rules: [big] } }] }, false],
    pathRelative: ['openFile', { path: big + '.txt' }, true],
    pathExtension: ['openFile', { path: '/tmp/' + big }, true],
    jsonKey: ['importCircuit', { doc, circuit: JSON.stringify({ schema: { format: 'circuitjs', version: '2.0' }, elements: { [big]: { type: 'NoSuchType' } } }) }, true],
    labelSet: ['applyEdits', { doc, edits: [{ op: 'set', id: 'L1', properties: { label: 'W'.repeat(1000) } }] }, false],
    labelText: ['importCircuit', { doc, circuit: text.replace('LBLMARK', big) }, false],
    modelHint: ['applyEdits', { doc, edits: [{ op: 'set', id: 'D1', properties: { model: 'echo-unknown' } }] }, false],
    fileRuleName: ['openFile', { path: '/tmp/echo_rule.json' }, true],
    fileElementKey: ['openFile', { path: '/tmp/echo_key.json' }, true],
    fileEntry: ['openFile', { path: '/tmp/echo_entry.json' }, true],
    at64: ['describeType', { type: at64 }, false],
    at65: ['describeType', { type: at65 }, true],
    surrogate: ['describeType', { type: pair }, true],
  };
  for (const [name, [op, args, echo]] of Object.entries(cases)) {
    const x = call(op, args);
    let issues = [...(x.r.issues || []), ...((x.r.connectivity && x.r.connectivity.added) || [])];
    let size = x.size, ms = x.ms;
    if (name === 'labelSet' || name === 'labelText') {
      // the single_label issue of the label text, from getConnectivity
      const c = call('getConnectivity', { doc, includeNets: false });
      issues = issues.concat(c.r.data ? c.r.data.issues : c.r.issues);
      size = Math.max(size, c.size); ms = Math.max(ms, c.ms);
    }
    if (op === 'openFile' && x.r.ok && x.r.data && x.r.data.doc) call('closeDocument', { doc: x.r.data.doc, discardChanges: true });
    const msgs = issues.map((i) => i.message);
    out[name] = { ok: x.r.ok, ms: Math.round(ms), size, maxMessage: Math.max(0, ...msgs.map((m) => m.length)), codes: issues.map((i) => i.code),
      clipped: msgs.some((m) => /… \(\d+ chars\)/.test(m)), echo, first: (msgs.find((m) => m.length > 0) || '').slice(0, 200),
      singleLabel: issues.some((i) => i.code === 'single_label'), hints: name === 'modelHint' ? issues.map((i) => i.hint) : undefined,
      exact64: name === 'at64' ? msgs.some((m) => m.includes("'" + at64 + "'")) : undefined,
      loneSurrogate: msgs.some((m) => /[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?:[^\uD800-\uDBFF]|^)[\uDC00-\uDFFF]/.test(m)),
      cut63: name === 'surrogate' ? msgs.some((m) => m.includes("'" + 'a'.repeat(63) + '… (75 chars)')) : undefined, hugeHint: issues.some((i) => (i.hint || '').length > 4000), hugeKey: issues.some((i) => i.key.length > 400) };
  }
  call('closeDocument', { doc, discardChanges: true });
  return JSON.stringify(out);
}

async function scenarioAgentEcho(s) {
  await resetApp(s);
  const exMark = s.exceptions.length;
  const maxMessage = +(process.env.ECHO_MAX_MESSAGE || 400);
  // file paths are checked by openFile only where file access exists: the in-page fake file system
  await s.eval(fakeFsScript({}));
  let res;
  try {
    res = JSON.parse(await s.eval(`(${echoProbe.toString()})()`));
  } finally {
    await s.eval(FAKE_FS_RESTORE);
  }
  const failed = [];
  const defined = res.defined;
  delete res.defined;
  for (const [name, v] of Object.entries(res)) {
    if (!v.codes.length) failed.push(`${name}: no issue`);
    if (v.maxMessage > maxMessage) failed.push(`${name}: message ${v.maxMessage} > ${maxMessage}`);
    if (v.echo && !v.clipped) failed.push(`${name}: no clipped echo`);
    if (v.size > 65536) failed.push(`${name}: answer ${v.size} chars`);
    if (v.ms > 2000) failed.push(`${name}: ${v.ms} ms`);
    if (v.hugeHint || v.hugeKey) failed.push(`${name}: hint or key unbounded`);
    if ((name === 'labelSet' || name === 'labelText') && !v.singleLabel) failed.push(`${name}: no single_label`);
    if (v.loneSurrogate) failed.push(`${name}: a message splits a surrogate pair`);
  }
  if (!defined) failed.push('45 diode models not defined');
  const hint = (res.modelHint && res.modelHint.hints || []).join(' ');
  const listed = /models: (.*?), … \((\d+) in all; listModels lists them\)/.exec(hint);
  if (!listed || listed[1].split(', ').length !== 40 || +listed[2] <= 40) failed.push('modelHint: not 40 names and a count');
  if (!(res.at64 && res.at64.exact64 && !res.at64.clipped)) failed.push('at64: a 64-character value is not quoted as given');
  if (!(res.surrogate && res.surrogate.cut63)) failed.push('surrogate: not cut before the pair');
  fs.writeFileSync(path.join(OUT_DIR, 'agent_echo.json'), JSON.stringify(res, null, 2));
  report('AG.agent_echo', s.exceptions.length === exMark && failed.length === 0,
    { cases: Object.keys(res).length, maxMessage: Math.max(...Object.values(res).map((v) => v.maxMessage)), maxMs: Math.max(...Object.values(res).map((v) => v.ms)), failed });
}

// ---------------------------------------------------------------- frame_cost (visible-tab frame draw)
// PL_AGA backlog "The visible tab's frame draw grows super-linearly": an N sweep (FRAME_SIZES,
// default 250,500,1000,2000,4000) of the layout_cost measurement mix (FRAME_FIXTURE: `mix`, or one
// type name) imported by the agent into the visible document. Idle: median of FRAME_RUNS (5)
// synchronous renders of the stopped visible tab — CircuitJS1Agent.debugCanvasPixels() minus a
// PNG encoding of a copy of the same image. Free-running (FRAME_RUN_SIZES, default 1000,2000; 0 = none)
// of FRAME_RUN_FIXTURE (default `chains` of import_cost — the mix's dense nonlinear matrix needs
// about 50 s per solver step at 1000 elements): FRAME_RUN_MS (3000) of the running tab after a
// warm-up (FRAME_WARM_MS, 2000; it also covers the first frame's stamp): frames
// (CircuitJS1.onupdate), median frame interval, simulated time per wall second and steps per wall
// second (simulated time / time step). Fails when the idle frame grows faster than
// time(2N)/time(N) > 2.3 between consecutive sizes (above 20 ms) or exceeds 200 ms at 1000
// elements. FRAME_PROFILE=<n>:idle|run[,...] records a CPU profile of 10 idle renders or of the
// free-running window (OUT_DIR/frame_cost/*.cpuprofile) with the top functions in frame_cost.json.
// FRAME_BEFORE=<frame_cost.json of another build> fails a free-running steps/s median more than
// 15 % below it, or an idle frame whose PNG (hash) differs from it. Not in the default run (timing).
async function scenarioFrameCost(s) {
  const sizes = (process.env.FRAME_SIZES || '250,500,1000,2000,4000').split(',').map(Number);
  const runSizes = (process.env.FRAME_RUN_SIZES || '1000,2000').split(',').map(Number).filter((n) => n > 0);
  const runs = +(process.env.FRAME_RUNS || 5);
  const runMs = +(process.env.FRAME_RUN_MS || 3000), warmMs = +(process.env.FRAME_WARM_MS || 2000);
  const fixture = process.env.FRAME_FIXTURE || 'mix';
  const runFixture = process.env.FRAME_RUN_FIXTURE || 'chains';
  const profiles = (process.env.FRAME_PROFILE || '').split(',').filter(Boolean);
  const A = (op, args) => s.call('agentCall', op, args);
  await resetApp(s);
  const exMark = s.exceptions.length;
  const vis = (await A('listDocuments', {})).data.documents.find((d) => d.active).doc;
  const med = (a) => { const b = [...a].sort((x, y) => x - y); return Math.round(b[Math.floor(b.length / 2)] * 10) / 10; };
  const profDir = path.join(OUT_DIR, 'frame_cost');
  if (profiles.length) fs.mkdirSync(profDir, { recursive: true });
  const out = { fixture, runFixture, runs, sizes: {}, profiles: {} };
  // the encode time: a fresh PNG encoding of a copy of the same image (the canvas caches its last encoding)
  const idleOnce = `(() => { const c = [...document.querySelectorAll('canvas')].sort((a, b) => b.width * b.height - a.width * a.height)[0];
    let t = performance.now(); CircuitJS1Agent.debugCanvasPixels(); const all = performance.now() - t;
    const cp = document.createElement('canvas'); cp.width = c.width; cp.height = c.height; cp.getContext('2d').drawImage(c, 0, 0);
    t = performance.now(); cp.toDataURL('image/png'); return { all, encode: performance.now() - t }; })()`;
  const profile = async (name, fn) => {
    await s.cdp.send('Profiler.enable');
    await s.cdp.send('Profiler.setSamplingInterval', { interval: 500 });
    await s.cdp.send('Profiler.start');
    await fn();
    const { profile: p } = await s.cdp.send('Profiler.stop');
    await s.cdp.send('Profiler.disable');
    fs.writeFileSync(path.join(profDir, `${name}.cpuprofile`), JSON.stringify(p));
    out.profiles[name] = profileTop(p, 30);
  };
  const all = [...new Set([...sizes, ...runSizes])].sort((a, b) => a - b);
  for (const n of all) {
    await s.eval(`CircuitJS1.setSimRunning(false); true`);
    // free-running uses FRAME_RUN_FIXTURE (default `chains`, an import_cost fixture): the mix's dense
    // nonlinear matrix takes about 50 s per solver step at 1000 elements (the O(m³) solver, not the draw)
    const idleSize = sizes.includes(n);
    const fx = idleSize ? (fixture === 'mix' ? costMix(n) : costMix(n, [fixture])) : importFixture(runFixture, n);
    await s.eval(`window.__mix = ${JSON.stringify(fx)}`);
    const imp = await s.eval(`JSON.parse(CircuitJS1Agent.call('importCircuit', JSON.stringify({ doc: ${JSON.stringify(vis)}, circuit: window.__mix }))).ok`);
    const rec = { elements: fx.elements.length, failed: imp ? null : 'import' };
    if (sizes.includes(n)) {
      await s.eval(idleOnce); await s.eval(idleOnce); // warm-up (first draw of each class)
      const t = { frame: [], all: [], encode: [] };
      for (let k = 0; k < runs; k++) {
        const r = await s.eval(idleOnce);
        t.all.push(r.all); t.encode.push(r.encode); t.frame.push(Math.max(0, r.all - r.encode));
      }
      // a hash of the idle frame's PNG: FRAME_BEFORE checks the visible frame is pixel-equal
      const png = await s.eval(`(() => { const u = CircuitJS1Agent.debugCanvasPixels(); let h = 0x811c9dc5;
        for (let i = 0; i < u.length; i++) { h ^= u.charCodeAt(i); h = Math.imul(h, 0x01000193) >>> 0; } return h.toString(16) + ':' + u.length; })()`);
      rec.idle = { frame: med(t.frame), all: med(t.all), encode: med(t.encode), perElementUs: Math.round(med(t.frame) * 1000 / n), png };
      if (profiles.includes(`${n}:idle`)) await profile(`idle_${n}`, () => s.eval(`(() => { for (let k = 0; k < 10; k++) CircuitJS1Agent.debugCanvasPixels(); return true; })()`));
    }
    if (runSizes.includes(n)) {
      if (idleSize) {
        await s.eval(`window.__mix = ${JSON.stringify(importFixture(runFixture, n))}`);
        if (!(await s.eval(`JSON.parse(CircuitJS1Agent.call('importCircuit', JSON.stringify({ doc: ${JSON.stringify(vis)}, circuit: window.__mix }))).ok`))) rec.failed = 'run import';
      }
      await s.eval(`(() => { window.__frames = []; CircuitJS1.onupdate = () => window.__frames.push(performance.now()); CircuitJS1.setSimRunning(true); return true; })()`);
      await sleep(warmMs);
      const window_ = async () => {
        const a = await s.eval(`(() => { window.__frames = []; return { t: CircuitJS1.getTime(), w: performance.now() }; })()`);
        await sleep(runMs);
        const b = await s.eval(`(() => { const i = CircuitJS1.getSimInfo(); return { t: CircuitJS1.getTime(), w: performance.now(), f: window.__frames.slice(), dt: i.timeStep, running: i.running, stop: i.stopMessage }; })()`);
        const sec = (b.w - a.w) / 1000, f = b.f;
        const iv = f.slice(1).map((x, i) => x - f[i]);
        return { frames: f.length, fps: Math.round(f.length / sec * 10) / 10, frameIntervalMs: iv.length ? med(iv) : null,
          simTimePerSec: (b.t - a.t) / sec, stepsPerSec: Math.round((b.t - a.t) / b.dt / sec), running: b.running, stop: b.stop || null };
      };
      if (profiles.includes(`${n}:run`)) {
        let r; await profile(`run_${n}`, async () => { r = await window_(); }); rec.run = r;
      } else rec.run = await window_();
      await s.eval(`(() => { CircuitJS1.setSimRunning(false); CircuitJS1.onupdate = null; return true; })()`);
    }
    out.sizes[n] = rec;
    log(`frame_cost ${n}: ${JSON.stringify({ idle: rec.idle, run: rec.run })}`);
    fs.writeFileSync(path.join(OUT_DIR, 'frame_cost.json'), JSON.stringify(out, null, 2));
  }
  await A('importCircuit', { doc: vis, circuit: { elements: [] } });
  const over = [], growth = [];
  for (let i = 1; i < sizes.length; i++) {
    const a = out.sizes[sizes[i - 1]].idle.frame, b = out.sizes[sizes[i]].idle.frame;
    const r = Math.round((b / Math.max(a, 1e-3)) * 100) / 100;
    growth.push(r);
    if (b > 20 && r > 2.3 * (sizes[i] / sizes[i - 1]) / 2) over.push(`idle ${sizes[i - 1]}→${sizes[i]}: ${a} → ${b} ms (x${r})`);
  }
  if (out.sizes[1000] && out.sizes[1000].idle && out.sizes[1000].idle.frame > 200) over.push(`idle 1000 ${out.sizes[1000].idle.frame} > 200 ms`);
  if (process.env.FRAME_BEFORE) {
    const before = JSON.parse(fs.readFileSync(process.env.FRAME_BEFORE, 'utf8'));
    for (const n of runSizes) {
      const b = before.sizes && before.sizes[n] && before.sizes[n].run, v = out.sizes[n].run;
      if (b && v && v.stepsPerSec < b.stepsPerSec * 0.85) over.push(`run ${n} steps/s ${v.stepsPerSec} < 85 % of ${b.stepsPerSec}`);
    }
    for (const n of sizes) {
      const b = before.sizes && before.sizes[n] && before.sizes[n].idle, v = out.sizes[n].idle;
      if (b && b.png && v && v.png !== b.png) over.push(`idle ${n} frame pixels differ from FRAME_BEFORE`);
    }
  }
  out.growth = growth;
  out.over = over;
  fs.writeFileSync(path.join(OUT_DIR, 'frame_cost.json'), JSON.stringify(out, null, 2));
  const summary = Object.fromEntries(Object.entries(out.sizes).map(([n, v]) => [n, v.failed || { idle: v.idle && v.idle.frame, fps: v.run && v.run.fps, stepsPerSec: v.run && v.run.stepsPerSec }]));
  report('AG.frame_cost', s.exceptions.length === exMark && Object.values(out.sizes).every((v) => !v.failed) && over.length === 0, { ...summary, growth, over });
}

// ---------------------------------------------------------------- agent_equiv (behaviour equivalence of two builds)
// Not in the default run. For a change that must not change behaviour (PL_AGA backlog "importCircuit
// scales"): over the examples (CIRCUITS, default all) records a hash per result of agent importCircuit,
// getConnectivity, getCircuit(full), checkLayout(boxes), render (SVG, canvas2svg's random gradient ids
// numbered), a move and a delete edit with their deltas, getDiagnostics, read, the user path
// (CircuitJS1.importCircuit: text export, IDs, connectivity) and 40 stepSimulation steps with the net
// voltages; writes agent_equiv.json. EQUIV_BEFORE=<agent_equiv.json of the other build> fails on any
// differing result except render and steps, which also differ between two runs of one build for a
// few examples (listed in `noisy`); EQUIV_FULL=1 stores the results instead of hashes (one example).
async function equivProbe(names, full) {
  const H = window.__H;
  const A = (op, a) => H.agentCall(op, a);
  const hash = (s) => { let h = 0x811c9dc5; for (let i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = Math.imul(h, 0x01000193) >>> 0; } return h.toString(16) + ':' + s.length; };
  const strip = (r) => JSON.stringify(r, (k, v) => (k === 'cursor' || k === 'seq' || k === 'transaction' || k === 'ms' || k === 'elapsedMs' || k === 'wallMs' ? undefined : v));
  const out = {};
  const bg = A('createDocument', { title: 'Equiv' }).data.doc;
  for (const name of names) {
    const rec = {};
    try {
      const text = await H.fetchText('/circuitjs1/circuits/' + name);
      const imp = A('importCircuit', { doc: bg, circuit: text });
      rec.import = strip(imp);
      rec.conn = strip(A('getConnectivity', { doc: bg }));
      rec.circuit = strip(A('getCircuit', { doc: bg, detail: 'full', limit: 500 }));
      rec.layout = strip(A('checkLayout', { doc: bg, includeBoxes: true }));
      const sv = await H.agentAsync('render', { doc: bg, format: 'svg' }, 60000);
      // canvas2svg gives gradients random ids: number them by first appearance
      let svgText = strip(sv);
      const svgIds = [...new Set([...svgText.matchAll(/id=\\"([A-Za-z0-9]+)\\"/g)].map((m) => m[1]))];
      svgIds.forEach((id, i) => { svgText = svgText.split(id).join('ID' + i); });
      rec.render = svgText;
      const ids = imp.ok && imp.data.ids ? imp.data.ids : [];
      rec.edit = ids.length ? strip(A('applyEdits', { doc: bg, edits: [{ op: 'move', id: ids[0], by: { dx: 1, dy: 0 } }] })) : null;
      rec.edit2 = ids.length > 1 ? strip(A('applyEdits', { doc: bg, edits: [{ op: 'delete', id: ids[ids.length - 1] }] })) : null;
      rec.connAfterEdit = strip(A('getConnectivity', { doc: bg }));
      rec.layoutAfterEdit = strip(A('checkLayout', { doc: bg, includeBoxes: true }));
      rec.diag = strip(A('getDiagnostics', { doc: bg }).data && { ...A('getDiagnostics', { doc: bg }).data, log: undefined });
      rec.readStopped = strip(A('read', { doc: bg, targets: (A('getConnectivity', { doc: bg }).data || { nets: [] }).nets.slice(0, 15).map((n) => ({ net: n.name })) }));
      // user path in the visible document
      CircuitJS1.importCircuit(text, false);
      await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)));
      rec.user = JSON.stringify({ text: CircuitJS1.exportCircuit(), ids: CircuitJS1.getElementIds(), conn: JSON.parse(CircuitJS1Agent.call('getConnectivity', '{}')) });
      // the solver, deterministically: 40 single steps of the visible document, then the nets
      CircuitJS1.setSimRunning(false);
      for (let k = 0; k < 40; k++) CircuitJS1.stepSimulation();
      const vnets = (JSON.parse(CircuitJS1Agent.call('getConnectivity', '{}')).data || { nets: [] }).nets.slice(0, 20).map((n) => ({ net: n.name }));
      rec.steps = JSON.stringify({ info: CircuitJS1.getSimInfo(), read: vnets.length ? JSON.parse(CircuitJS1Agent.call('read', JSON.stringify({ targets: vnets }))) : null });
    } catch (e) { rec.error = String(e && e.stack || e).slice(0, 500); }
    out[name] = full ? rec : Object.fromEntries(Object.entries(rec).map(([k, v]) => [k, v == null ? v : (k === 'error' ? v : hash(v))]));
  }
  A('closeDocument', { doc: bg, discardChanges: true });
  return JSON.stringify(out);
}

async function scenarioAgentEquiv(s) {
  const list = process.env.CIRCUITS && process.env.CIRCUITS !== 'all' ? process.env.CIRCUITS.split(',') : listAllCircuits();
  await resetApp(s);
  const exMark = s.exceptions.length;
  const out = JSON.parse(await s.eval(`(${equivProbe.toString()})(${JSON.stringify(list)}, ${!!process.env.EQUIV_FULL})`));
  fs.writeFileSync(path.join(OUT_DIR, 'agent_equiv.json'), JSON.stringify(out, null, 2));
  const summary = { circuits: Object.keys(out).length, errors: Object.keys(out).filter((k) => out[k].error) };
  if (process.env.EQUIV_BEFORE) {
    const before = JSON.parse(fs.readFileSync(process.env.EQUIV_BEFORE, 'utf8'));
    const diffs = {}, noisy = {};
    for (const k of Object.keys(out)) {
      for (const sec of new Set([...Object.keys(out[k]), ...Object.keys(before[k] || {})])) {
        if (JSON.stringify(out[k][sec]) === JSON.stringify((before[k] || {})[sec])) continue;
        const t = sec === 'render' || sec === 'steps' ? noisy : diffs;
        (t[sec] = t[sec] || []).push(k);
      }
    }
    Object.assign(summary, { diffs, noisy });
  }
  report('AG.agent_equiv', s.exceptions.length === exMark && summary.errors.length === 0 && !(summary.diffs && Object.keys(summary.diffs).length), summary);
}

async function main() {
  const wanted = process.argv.slice(2);
  const scen = wanted.length && !wanted.includes('all') ? wanted : ['undo', 'paste', 'sliders', 'loadstate', 'textfid', 'scope_float', 'roundtrip', 'synth', 'agent_docs', 'agent_ids', 'agent_catalogue', 'agent_edit', 'agent_connect', 'agent_connect_all', 'agent_overlap', 'agent_layout', 'render_text', 'agent_freerun', 'geom_posts', 'xfmr_draw', 'agent_axis', 'agent_history', 'agent_run', 'agent_bg', 'agent_files', 'pin_names', 'agent_defects', 'verify_defects', 'solver_defects', 'agent_models', 'agent_models_logic', 'agent_models_sub', 'json_models', 'agent_echo', 'mcp_browser', 'mcp_dialog'];
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
  const table = { undo: scenarioUndo, paste: scenarioPaste, sliders: scenarioSliders, loadstate: scenarioLoadState, roundtrip: scenarioRoundtrip, synth: scenarioSynth, textfid: scenarioTextFidelity, scope_float: scenarioScopeFloat, agent_docs: scenarioAgentDocs, agent_ids: scenarioAgentIds, agent_catalogue: scenarioAgentCatalogue, agent_edit: scenarioAgentEdit, agent_connect: scenarioAgentConnect, agent_connect_all: scenarioAgentConnectAll, agent_overlap: scenarioAgentOverlap, render_text: scenarioRenderText, agent_freerun: scenarioAgentFreeRun, geom_posts: scenarioGeomPosts, xfmr_draw: scenarioXfmrDraw, agent_axis: scenarioAgentAxis, agent_history: scenarioAgentHistory, agent_run: scenarioAgentRun, agent_bg: scenarioAgentBackground, agent_files: scenarioAgentFiles, pin_names: scenarioPinNames, agent_defects: scenarioAgentDefects, verify_defects: scenarioVerifyDefects, solver_defects: scenarioSolverDefects, solver_corpus: scenarioSolverCorpus, agent_models: scenarioAgentModels, agent_models_logic: scenarioAgentModelsLogic, agent_models_sub: scenarioAgentModelsSub, json_models: scenarioJsonModels, mcp_browser: scenarioMcpBrowser, mcp_dialog: scenarioMcpDialog, render_pixels: scenarioRenderPixels, layout_cost: scenarioLayoutCost, import_cost: scenarioImportCost, frame_cost: scenarioFrameCost, agent_echo: scenarioAgentEcho, agent_equiv: scenarioAgentEquiv, text_sites: scenarioTextSites, agent_layout: scenarioAgentLayout, eval: scenarioEval };
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
