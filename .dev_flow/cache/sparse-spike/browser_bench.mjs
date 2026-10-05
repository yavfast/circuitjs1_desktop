// Drives target/site in headless Chromium via CDP: imports synthetic circuits, instruments
// CircuitMath.lu_factor / lu_solve and CircuitSimulator.stampCircuit / simplifyMatrix by
// wrapping the GWT-emitted functions, runs analysis + N steps, optionally CPU-profiles, and
// captures the post-simplify matrix of the first factorization (for the sparse prototype).
// Usage: node browser_bench.mjs <case>... where case = kind:N[:steps]  (kind: grid|rladder|cladder|dladder|dcladder|dgrid)
// Env: PROFILE=1 records a CPU profile per case; CAPTURE=1 writes matrices to ./matrices/.
import fs from 'node:fs';
import path from 'node:path';
import net from 'node:net';
import os from 'node:os';
import { spawn } from 'node:child_process';
import * as C from './circuits.mjs';

const HERE = path.dirname(new URL(import.meta.url).pathname);
const SITE = process.env.SITE || path.resolve(HERE, '../../../target/site');
const OUT = path.join(HERE, 'out'); fs.mkdirSync(OUT, { recursive: true });
const MAT = path.join(HERE, 'matrices'); fs.mkdirSync(MAT, { recursive: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const freePort = () => new Promise((res) => { const s = net.createServer(); s.listen(0, '127.0.0.1', () => { const p = s.address().port; s.close(() => res(p)); }); });
const kids = [];
process.on('exit', () => { for (const c of kids) try { process.kill(-c.pid, 'SIGKILL'); } catch {} });
const start = (cmd, args) => { const c = spawn(cmd, args, { detached: true, stdio: 'ignore' }); kids.push(c); return c; };
async function waitFor(fn, ms, what) { const t0 = Date.now(); while (Date.now() - t0 < ms) { try { const v = await fn(); if (v) return v; } catch {} await sleep(200); } throw new Error('timeout ' + what); }

class CDP {
  constructor(u) { this.ws = new WebSocket(u); this.id = 0; this.p = new Map(); this.ws.onmessage = (e) => { const m = JSON.parse(e.data); if (m.id && this.p.has(m.id)) { const { res, rej } = this.p.get(m.id); this.p.delete(m.id); m.error ? rej(new Error(m.error.message)) : res(m.result); } }; }
  open() { return new Promise((r, j) => { this.ws.onopen = r; this.ws.onerror = j; }); }
  send(method, params = {}) { const id = ++this.id; return new Promise((res, rej) => { this.p.set(id, { res, rej }); this.ws.send(JSON.stringify({ id, method, params })); }); }
}

const instrument = () => {
  const ifr = [...document.querySelectorAll('iframe')].map((f) => { try { return f.contentWindow; } catch { return null; } }).find((w) => w && w.clcc);
  const G = ifr.clcc;
  const st = window.__st = { factor: [], solve: { n: 0, ms: 0, size: 0 }, stamp: [], simplify: [], capture: null, wantCapture: false };
  const F = 'com_lushprojects_circuitjs1_client_CircuitMath_lu_1factor___3_3DI_3IZ';
  const S = 'com_lushprojects_circuitjs1_client_CircuitMath_lu_1solve___3_3DI_3I_3DV';
  const f0 = G[F], s0 = G[S];
  G[F] = function (a, n, ip) {
    if (st.wantCapture && !st.capture) {
      const I = [], J = [], V = [];
      for (let i = 0; i < n; i++) { const r = a[i]; for (let j = 0; j < n; j++) if (r[j] !== 0) { I.push(i); J.push(j); V.push(r[j]); } }
      st.capture = { n, I, J, V };
    }
    const t = performance.now(); const ok = f0(a, n, ip); st.factor.push({ n, ms: performance.now() - t, ok }); return ok;
  };
  G[S] = function (a, n, ip, b) { const t = performance.now(); s0(a, n, ip, b); st.solve.n++; st.solve.ms += performance.now() - t; st.solve.size = n; };
  // prototype of CircuitSimulator
  const ctorKey = Object.keys(G).find((k) => k.startsWith('com_lushprojects_circuitjs1_client_CircuitSimulator_CircuitSimulator__'));
  const P = G[ctorKey].prototype;
  const SC = 'package_private$com_lushprojects_circuitjs1_client$stampCircuit__V';
  const SM = 'package_private$com_lushprojects_circuitjs1_client$simplifyMatrix__IZ';
  const sc0 = P[SC], sm0 = P[SM];
  P[SC] = function () { const t = performance.now(); const f = st.factor.length; sc0.call(this); st.stamp.push({ ms: performance.now() - t, factorMs: st.factor.slice(f).reduce((x, y) => x + y.ms, 0), full: this.com_lushprojects_circuitjs1_client_CircuitSimulator_circuitMatrixFullSize, size: this.com_lushprojects_circuitjs1_client_CircuitSimulator_circuitMatrixSize, nonlinear: this.com_lushprojects_circuitjs1_client_CircuitSimulator_circuitNonLinear, stack: window.__stacks ? (new Error().stack || '').split('\n').slice(2, 9).map((l) => l.trim().replace(/^at /, '').replace(/com_lushprojects_circuitjs1_client_/g, '').split(' ')[0].slice(-60)) : undefined }); };
  P[SM] = function (m) { const t = performance.now(); const r = sm0.call(this, m); st.simplify.push({ m, ms: performance.now() - t }); return r; };
  return { ok: true, proto: !!P[SC] };
};

const callAsync = (op, args) => `new Promise((res) => CircuitJS1Agent.callAsync(${JSON.stringify(op)}, ${JSON.stringify(JSON.stringify(args))}, (r) => res(JSON.parse(r))))`;

async function main() {
  const httpPort = await freePort(), cdpPort = await freePort();
  start('python3', ['-m', 'http.server', String(httpPort), '--directory', SITE, '--bind', '127.0.0.1']);
  const prof = path.join(os.tmpdir(), 'circuitjs1-desktop', 'sparse-spike', 'q1', 'chrome-profile');
  fs.rmSync(prof, { recursive: true, force: true });
  start(process.env.CHROMIUM || 'chromium', ['--headless=new', `--remote-debugging-port=${cdpPort}`, '--remote-debugging-address=127.0.0.1', `--user-data-dir=${prof}`, '--no-first-run', '--disable-gpu', '--disable-extensions', '--window-size=1400,900', 'about:blank']);
  const base = `http://127.0.0.1:${httpPort}`;
  await waitFor(async () => (await fetch(base + '/circuitjs.html')).ok, 15000, 'http');
  await waitFor(async () => (await fetch(`http://127.0.0.1:${cdpPort}/json/version`)).ok, 20000, 'cdp');
  const page = (await (await fetch(`http://127.0.0.1:${cdpPort}/json/list`)).json()).find((t) => t.type === 'page');
  const cdp = new CDP(page.webSocketDebuggerUrl); await cdp.open();
  await cdp.send('Runtime.enable'); await cdp.send('Page.enable');
  const ev = async (expr) => { const r = await cdp.send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true }); if (r.exceptionDetails) throw new Error(JSON.stringify(r.exceptionDetails).slice(0, 600)); return r.result.value; };
  await cdp.send('Page.navigate', { url: base + '/circuitjs.html' });
  await waitFor(() => ev(`typeof CircuitJS1Agent !== 'undefined' && typeof CircuitJS1 !== 'undefined' && CircuitJS1.getElementCount() >= 0`), 90000, 'app');
  await sleep(2000);
  console.log('instrument', await ev(`(${instrument.toString()})()`));
  await ev(`CircuitJS1.setSimRunning(false); window.__stacks = ${!!process.env.STACKS}; true`);

  const results = [];
  let DOC = null;
  if (process.env.BG) DOC = await ev(`JSON.parse(CircuitJS1Agent.call('createDocument', JSON.stringify({ title: 'bench' }))).data.doc`);
  console.log('doc', DOC);
  if (process.env.CORPUS) {
    const dir = '/hdd/STORE/My_projects/Circuit/circuitjs1_desktop/src/main/java/com/lushprojects/circuitjs1/public/circuits';
    const files = fs.readdirSync(dir).filter((f) => f.endsWith('.txt')).sort();
    const rows = [];
    for (const f of files) {
      await ev(`window.__text = ${JSON.stringify(fs.readFileSync(path.join(dir, f), 'utf8'))}; true`);
      const ok = await ev(`(() => { const r = JSON.parse(CircuitJS1Agent.call('importCircuit', JSON.stringify({ doc: ${JSON.stringify(DOC)}, circuit: window.__text }))); return r.ok; })()`);
      await ev(`(() => { const s = window.__st; s.factor = []; s.solve = { n: 0, ms: 0, size: 0 }; s.stamp = []; s.simplify = []; return true; })()`);
      let r = null; try { r = await ev(callAsync('run', { doc: DOC || undefined, span: '50 us', reset: true, budgetMs: 5000 })); } catch (e) { r = null; }
      const st = await ev(`JSON.parse(JSON.stringify((({ capture, ...x }) => x)(window.__st)))`);
      const full = st.stamp.length ? Math.max(...st.stamp.map((x) => x.full || 0)) : 0;
      const m = st.stamp.length ? Math.max(...st.stamp.map((x) => x.size || 0)) : 0;
      const row = { f, ok, full, m, nonlinear: st.stamp.some((x) => x.nonlinear), steps: r && r.data && r.data.steps, factors: st.factor.length, factorMs: Math.round(st.factor.reduce((a, b) => a + b.ms, 0)), solveMs: Math.round(st.solve.ms), wall: r && r.data && r.data.wallMs };
      rows.push(row);
    }
    fs.writeFileSync(path.join(OUT, 'corpus_sizes.json'), JSON.stringify(rows, null, 1));
    const ms = rows.map((r) => r.m).sort((a, b) => a - b); const pct = (p) => ms[Math.floor(p * (ms.length - 1))];
    console.log('corpus', rows.length, 'p50', pct(0.5), 'p90', pct(0.9), 'p99', pct(0.99), 'max', ms[ms.length - 1], '>=32:', ms.filter((x) => x >= 32).length, '>=64:', ms.filter((x) => x >= 64).length, '>=100:', ms.filter((x) => x >= 100).length);
    console.log('largest', rows.sort((a, b) => b.m - a.m).slice(0, 12).map((r) => `${r.f}:${r.full}->${r.m}${r.nonlinear ? 'NL' : ''} fact=${r.factors}/${r.factorMs}ms`).join(' '));
    process.exit(0);
  }
  for (const spec of process.argv.slice(2)) {
    const [kind, nS, stepsS] = spec.split(':'); const N = +nS; const steps = +(stepsS || 20);
    const dt = 5e-6;
    const text = kind === 'grid' ? C.grid(N, { dt }) : kind === 'dgrid' ? C.diodeGrid(N, { dt })
      : C.ladder(N, { rladder: 'r', cladder: 'c', dladder: 'd', dcladder: 'dc' }[kind], { dt });
    await ev(`window.__text = ${JSON.stringify(text)}; CircuitJS1.setSimRunning(false); true`);
    const imp = await ev(`(() => { const r = JSON.parse(CircuitJS1Agent.call('importCircuit', JSON.stringify({ doc: ${JSON.stringify(DOC)}, circuit: window.__text }))); CircuitJS1.setSimRunning(false); return { ok: r.ok, issues: (r.issues||[]).slice(0,3).map(i => i.code + ':' + i.message.slice(0,80)), n: CircuitJS1.getElementCount() }; })()`);
    await ev(`(() => { const s = window.__st; s.factor = []; s.solve = { n: 0, ms: 0, size: 0 }; s.stamp = []; s.simplify = []; s.capture = null; s.wantCapture = ${!!process.env.CAPTURE}; return true; })()`);
    if (process.env.PROFILE) { await cdp.send('Profiler.enable'); await cdp.send('Profiler.setSamplingInterval', { interval: 200 }); await cdp.send('Profiler.start'); }
    // run 1: analysis + 1 step (reset: true -> fresh analysis)
    const r1 = await ev(callAsync('run', { doc: DOC || undefined, span: dt * 1.0001, reset: true, budgetMs: 120000 }));
    const s1 = await ev(`JSON.parse(JSON.stringify((({ capture, ...x }) => x)(window.__st)))`);
    const cap = await ev(`window.__st.capture`);
    await ev(`(() => { const s = window.__st; s.factor = []; s.solve = { n: 0, ms: 0, size: 0 }; s.stamp = []; s.simplify = []; s.wantCapture = false; return true; })()`);
    // run 2: `steps` further steps without reset
    const r2 = await ev(callAsync('run', { doc: DOC || undefined, span: dt * steps * 1.0001, reset: false, budgetMs: 120000 }));
    const s2 = await ev(`JSON.parse(JSON.stringify((({ capture, ...x }) => x)(window.__st)))`);
    let top = null;
    if (process.env.PROFILE) {
      const { profile } = await cdp.send('Profiler.stop'); await cdp.send('Profiler.disable');
      fs.writeFileSync(path.join(OUT, `${kind}_${N}.cpuprofile`), JSON.stringify(profile));
      top = profileSelf(profile, 14);
    }
    if (cap) fs.writeFileSync(path.join(MAT, `${kind}_${N}.json`), JSON.stringify(cap));
    const sum = (a) => a.reduce((x, y) => x + y, 0);
    const rec = {
      spec, elements: imp.n, importOk: imp.ok, importIssues: imp.issues,
      analysis: { wallMs: r1.data && r1.data.wallMs, reason: r1.data && r1.data.reason, steps: r1.data && r1.data.steps,
        stamp: s1.stamp, simplify: s1.simplify, factors: s1.factor.length, factorMs: Math.round(sum(s1.factor.map((f) => f.ms))), factorN: s1.factor[0] && s1.factor[0].n, solve: s1.solve },
      stepping: { wallMs: r2.data && r2.data.wallMs, reason: r2.data && r2.data.reason, steps: r2.data && r2.data.steps, restamps: s2.stamp.length,
        factors: s2.factor.length, factorMs: Math.round(sum(s2.factor.map((f) => f.ms))), solve: { n: s2.solve.n, ms: Math.round(s2.solve.ms * 10) / 10, size: s2.solve.size } },
      nnz: cap ? cap.V.length : null, top,
    };
    results.push(rec);
    console.log(JSON.stringify(rec));
    fs.writeFileSync(path.join(OUT, `results_${process.env.TAG || 'run'}.json`), JSON.stringify(results, null, 1));
  }
  process.exit(0);
}

function profileSelf(profile, limit) {
  const byId = new Map(profile.nodes.map((n) => [n.id, n]));
  const self = new Map(); const dts = profile.timeDeltas || [];
  for (let i = 0; i < profile.samples.length; i++) {
    const dt = (dts[i + 1] || 0) / 1000; const nd = byId.get(profile.samples[i]);
    const k = (nd.callFrame.functionName || '(anon)').replace(/^com_lushprojects_circuitjs1_client_/, '').slice(0, 70);
    self.set(k, (self.get(k) || 0) + dt);
  }
  return [...self.entries()].sort((a, b) => b[1] - a[1]).slice(0, limit).map(([k, v]) => [k, Math.round(v)]);
}
main().catch((e) => { console.error(e); process.exit(2); });
