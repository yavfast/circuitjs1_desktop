// RULE_TEST_002 stand-in for PL_SLV: the real NW.js app (target/site) under Xvfb, visible tab,
// free-running analog / digital / subcircuit examples and a 1000-node RC ladder, plus the
// Other Options Solver row. Run: unshare -rn sh -c 'ip link set lo up && xvfb-run -a node nw_check.mjs'
import fs from 'node:fs';
import path from 'node:path';
import net from 'node:net';
import { spawn } from 'node:child_process';

const ROOT = '/hdd/STORE/My_projects/Circuit/circuitjs1_desktop';
const SITE = path.join(ROOT, 'target/site');
const NW = path.join(ROOT, 'node_modules/nw/nwjs/nw');
const WORK = path.join(process.env.TMPDIR || '/tmp', 'circuitjs-nw-check');
fs.rmSync(WORK, { recursive: true, force: true });
fs.mkdirSync(path.join(WORK, 'home'), { recursive: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const freePort = () => new Promise((res) => { const s = net.createServer(); s.listen(0, '127.0.0.1', () => { const p = s.address().port; s.close(() => res(p)); }); });
const checks = {}; const notes = {};
const ck = (n, c, info) => { checks[n] = !!c; if (info !== undefined) notes[n] = info; };

const port = await freePort();
const env = { ...process.env, HOME: path.join(WORK, 'home') };
delete env.WAYLAND_DISPLAY;
const proc = spawn(NW, [`--remote-debugging-port=${port}`, `--user-data-dir=${path.join(WORK, 'udd')}`, SITE],
  { stdio: ['ignore', fs.openSync(path.join(WORK, 'nw.log'), 'w'), fs.openSync(path.join(WORK, 'nw.err'), 'w')], detached: true, env });
const killAll = () => { try { process.kill(-proc.pid, 'SIGKILL'); } catch (e) {} };
process.on('exit', killAll);
let page;
for (let i = 0; i < 120 && !page; i++) {
  await sleep(500);
  try { page = (await (await fetch(`http://127.0.0.1:${port}/json/list`)).json()).find((t) => t.type === 'page' && /circuitjs\.html/.test(t.url)); } catch (e) {}
}
if (!page) { console.log('NW page not found'); process.exit(2); }
const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((r, j) => { ws.onopen = r; ws.onerror = j; });
let id = 0; const pend = new Map(); const exceptions = []; const consoleErr = [];
ws.onmessage = (e) => {
  const m = JSON.parse(e.data);
  if (m.id && pend.has(m.id)) { const { res, rej } = pend.get(m.id); pend.delete(m.id); m.error ? rej(new Error(m.error.message)) : res(m.result); }
  if (m.method === 'Runtime.exceptionThrown') exceptions.push(JSON.stringify(m.params.exceptionDetails).slice(0, 400));
  if (m.method === 'Runtime.consoleAPICalled' && m.params.type === 'error') consoleErr.push(JSON.stringify(m.params.args).slice(0, 300));
};
const send = (method, params = {}) => new Promise((res, rej) => { const i = ++id; pend.set(i, { res, rej }); ws.send(JSON.stringify({ id: i, method, params })); });
const ev = async (expr) => { const r = await send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true }); if (r.exceptionDetails) throw new Error(JSON.stringify(r.exceptionDetails).slice(0, 500)); return r.result.value; };
await send('Runtime.enable');
for (let i = 0; i < 120; i++) { try { if (await ev(`typeof CircuitJS1Agent !== 'undefined' && JSON.parse(CircuitJS1Agent.call('listDocuments','{}')).ok`)) break; } catch (e) {} await sleep(500); }
await sleep(1500);
const A = async (op, args) => JSON.parse(await ev(`CircuitJS1Agent.call(${JSON.stringify(op)}, ${JSON.stringify(JSON.stringify(args || {}))})`));
const vis = async () => (await A('listDocuments')).data.documents.find((d) => d.active).doc;
const diag = async () => (await A('getDiagnostics', { doc: await vis() })).data;
// close start-up dialogs
await ev(`(() => { for (const d of document.querySelectorAll('.gwt-DialogBox')) { if (d.offsetWidth === 0) continue; for (const b of d.querySelectorAll('button')) if (/^(ok|close|cancel|закрити|скасувати)$/i.test(b.innerText.trim())) { b.click(); break; } } return true; })()`);

const freeRun = async (name, text, ms, expect) => {
  await ev(`CircuitJS1.setSimRunning(false); CircuitJS1.importCircuit(${JSON.stringify(text)}, false); true`);
  await sleep(300);
  await ev('CircuitJS1.setSimRunning(true); true');
  const t0 = (await diag()).simTime;
  await sleep(ms);
  const d = await diag();
  await ev('CircuitJS1.setSimRunning(false); true');
  notes[name] = { t0, t1: d.simTime, stopped: d.stopped, path: d.solver.path, size: d.solver.size, events: d.events.map((e) => e.code) };
  ck(name, d.simTime > t0 && !d.stopped && d.solver.path === expect && !d.events.some((e) => e.severity === 'error'));
};
const ex = (f) => fs.readFileSync(path.join(SITE, 'circuitjs1/circuits', f), 'utf8');
await freeRun('analog_lrc', ex('lrc.txt'), 1500, 'dense');
await freeRun('digital_counter', ex('counter.txt'), 1500, 'dense');
await freeRun('subcircuit_alu74181', ex('alu74181.txt'), 2500, 'sparse');
// the same subcircuit example with a document override: dense vs sparse free-run speed
for (const m of ['dense', 'sparse', 'dense', 'sparse']) {
  await ev(`CircuitJS1.setSimRunning(false); CircuitJS1.importCircuit(${JSON.stringify(ex('alu74181.txt'))}, false); true`);
  await A('simControl', { doc: await vis(), action: 'solver', mode: m });
  await ev('CircuitJS1.setSimRunning(true); true');
  await sleep(500);
  const a = (await diag()).simTime;
  await sleep(3000);
  const d = await diag();
  (notes.alu = notes.alu || []).push([m, d.solver.path, Math.round((d.simTime - a) * 1e9) / 1e3 + ' us/3s']);
  await ev('CircuitJS1.setSimRunning(false); true');
  await A('simControl', { doc: await vis(), action: 'solver', mode: 'session' });
}
// 1000-section RC ladder (m ≈ 1002, sparse in Auto)
const L = ['$ 1 0.000005 10.2 50 5 50 5e-11', 'v 0 128 0 64 0 1 1000 5 0 0 0.5', 'w 0 64 64 64 0', 'g 0 128 0 160 0'];
for (let k = 0; k < 1000; k++) { const x = 64 + 32 * (k + 1); L.push(`r ${x - 32} 64 ${x} 64 0 100`, `c ${x} 64 ${x} 128 0 1e-6 0 0.001`, `g ${x} 128 ${x} 160 0`); }
await freeRun('ladder1000_sparse', L.join('\n') + '\n', 4000, 'sparse');

// Other Options → Solver row on lrc (free-running): Sparse re-stamps at the next frame, then Auto
await ev(`CircuitJS1.importCircuit(${JSON.stringify(ex('lrc.txt'))}, false); CircuitJS1.setSimRunning(true); true`);
await sleep(800);
const texts = (key) => { const out = new Set([key]); for (const f of fs.readdirSync(path.join(SITE, 'circuitjs1')).filter((x) => /^locale_.*\.txt$/.test(x))) for (const line of fs.readFileSync(path.join(SITE, 'circuitjs1', f), 'utf8').split('\n')) { const m = /^"(.*)"="(.*)"\s*$/.exec(line); if (m && m[1] === key) out.add(m[2].trim()); } return [...out]; };
const menu = async (top, item) => ev(`(async () => {
  const fire = (el, t) => el.dispatchEvent(new MouseEvent(t, { bubbles: true, cancelable: true, view: window }));
  const norm = (t) => (t || '').replace(/\\s+/g, ' ').trim();
  const vis = () => Array.from(document.querySelectorAll('.gwt-MenuItem')).filter((e) => e.offsetWidth > 0);
  const a = vis().find((e) => ${JSON.stringify(top)}.includes(norm(e.textContent)));
  if (!a) return 'no top';
  fire(a, 'mouseover'); fire(a, 'click'); await new Promise((r) => setTimeout(r, 300));
  const el = vis().find((e) => ${JSON.stringify(item)}.includes(norm(e.textContent)));
  if (!el) return 'no item';
  fire(el, 'mouseover'); fire(el, 'click'); await new Promise((r) => setTimeout(r, 400));
  return 'ok';
})()`);
const choose = async (idx) => ev(`(() => {
  const sels = Array.from(document.querySelectorAll('.gwt-DialogBox select')).filter((x) => x.offsetWidth > 0 && x.options.length === 3);
  const L = ${JSON.stringify([texts('Auto'), texts('Dense'), texts('Sparse')])};
  const sel = sels.find((x) => L.every((l, i) => l.includes(x.options[i].text.trim())));
  if (!sel) return null;
  const before = sel.selectedIndex;
  sel.selectedIndex = ${idx}; sel.dispatchEvent(new Event('change', { bubbles: true }));
  return before;
})()`);
notes.menu = await menu(texts('Options'), texts('Other Options...'));
const before = await choose(2);
await sleep(800);
const dS = await diag();
await choose(0);
await sleep(800);
const dA = await diag();
await ev(`CircuitJS1.setSimRunning(false); localStorage.removeItem('solverMode'); true`);
notes.solverRow = { menu: notes.menu, before, sparse: [dS.solver.mode, dS.solver.path, dS.simTime], auto: [dA.solver.mode, dA.solver.path, dA.simTime] };
ck('optionsSolverRow', before === 0 && dS.solver.mode === 'sparse' && dS.solver.path === 'sparse' && dA.solver.mode === 'auto' && dA.solver.path === 'dense' && dA.simTime > dS.simTime);
ck('noExceptions', exceptions.length === 0, exceptions.slice(0, 3));
notes.consoleErrors = consoleErr.slice(0, 5);
const failed = Object.keys(checks).filter((k) => !checks[k]);
console.log(JSON.stringify({ pass: failed.length === 0, checks, failed, notes }, null, 1));
ws.close(); killAll();
process.exit(failed.length ? 1 : 0);
