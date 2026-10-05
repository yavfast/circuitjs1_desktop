// Dense (faithful CircuitMath port, plus a cache-friendly dense variant) vs sparse LU on MNA-like
// matrices. Usage: node bench.mjs [kinds] [sizes]   e.g. node bench.mjs grid,ladder,rand 50,100,200
// Also: node bench.mjs captured  (matrices captured from the real build in ./matrices)
import fs from 'node:fs';
import path from 'node:path';
import * as D from './dense.mjs';
import * as S from './sparse.mjs';
import * as G from './matgen.mjs';

const HERE = path.dirname(new URL(import.meta.url).pathname);
const now = () => performance.now();
const DENSE_MAX = +(process.env.DENSE_MAX || 2100);

function timeIt(fn, minMs = 200, maxReps = 1000) {
  let reps = 0; const t0 = now(); let t;
  do { fn(reps); reps++; t = now() - t0; } while (t < minMs && reps < maxReps);
  return t / reps;
}
function residual(A, x, b) {
  const { n, Ap, Ai, Ax } = A; const r = Float64Array.from(b);
  for (let j = 0; j < n; j++) for (let p = Ap[j]; p < Ap[j + 1]; p++) r[Ai[p]] -= Ax[p] * x[j];
  let nr = 0, nb = 0; for (let i = 0; i < n; i++) { nr += r[i] * r[i]; nb += b[i] * b[i]; }
  return Math.sqrt(nr) / Math.sqrt(nb || 1);
}
function toDense(A, a) {
  const { n, Ap, Ai, Ax } = A;
  for (let i = 0; i < n; i++) { const r = a[i]; for (let j = 0; j < n; j++) r[j] = 0; }
  for (let j = 0; j < n; j++) for (let p = Ap[j]; p < Ap[j + 1]; p++) a[Ai[p]][j] = Ax[p];
}
// Newton-like perturbation: scale values on the diagonal and a random half of the off-diagonals
function perturb(A, seed) {
  const r = G.rng(seed); const Ax = Float64Array.from(A.Ax);
  for (let j = 0; j < A.n; j++) for (let p = A.Ap[j]; p < A.Ap[j + 1]; p++) if (A.Ai[p] === j || r() < 0.5) Ax[p] *= 0.5 + r();
  return { ...A, Ax };
}

function benchOne(name, n, I, J, V, b) {
  let A = S.tripletsToCSC(n, I, J, V);
  const rec = { name, n, nnz: A.Ap[n] };
  // ---- dense baseline (faithful port)
  if (n <= DENSE_MAX) {
    const orig = D.newMatrix(n); toDense(A, orig);
    const a = D.newMatrix(n); const ip = new Array(n).fill(0);
    const copy = () => { for (let i = 0; i < n; i++) { const s = orig[i], d = a[i]; for (let j = 0; j < n; j++) d[j] = s[j]; } };
    rec.denseCopyMs = timeIt(copy, 100);
    let ok = true;
    const minMs = n >= 1000 ? 1 : 300;
    rec.denseFactorMs = timeIt(() => { copy(); ok = D.lu_factor(a, n, ip); }, minMs, n >= 1000 ? 1 : 100) - rec.denseCopyMs;
    rec.denseOk = ok;
    const x = new Array(n).fill(0);
    rec.denseSolveMs = timeIt(() => { for (let i = 0; i < n; i++) x[i] = b[i]; D.lu_solve(a, n, ip, x); }, 100);
    rec.denseResid = ok ? residual(A, x, b) : null;
    // cache-friendly dense variant on Float64Array rows
    if (process.env.DENSE_OPT !== '0') {
      const o = Array.from({ length: n }, () => new Float64Array(n));
      const copyO = () => { for (let i = 0; i < n; i++) o[i].set(orig[i]); };
      let ok2 = true;
      const tc = timeIt(copyO, 50);
      rec.denseOptFactorMs = timeIt(() => { copyO(); ok2 = D.dense_opt_factor(o, n, ip); }, minMs, n >= 1000 ? 1 : 100) - tc;
      const x2 = new Float64Array(n);
      rec.denseOptSolveMs = timeIt(() => { x2.set(b); D.dense_opt_solve(o, n, ip, x2); }, 50);
      rec.denseOptResid = ok2 ? residual(A, x2, b) : null;
    }
  }
  // ---- sparse
  let q;
  const ORIG = A;
  if (process.env.MATCH) {
    // zero-free diagonal first (as KLU's BTF step), then factor the row-permuted matrix
    let rowOf; rec.matchMs = timeIt(() => { rowOf = S.maxTransversal(A); }, 20, 20);
    if (!rowOf) { rec.sparseFail = 'structurally singular'; return rec; }
    const B = S.permuteRows(A, rowOf);
    // b must follow the row permutation; residuals are checked on B with the permuted b
    const bb = new Float64Array(n); for (let i = 0; i < n; i++) bb[B.rowInv[i]] = b[i];
    b = bb; A = B;
  }
  rec.orderMs = timeIt(() => { q = S.minDegreeOrder(A); }, 50, 20);
  let F;
  rec.factorMs = timeIt(() => { F = S.luFactor(A, q, 1e-3); }, 100);
  if (!F.ok) { rec.sparseFail = F; return rec; }
  rec.lnz = F.lnz; rec.unz = F.unz; rec.fill = Math.round((F.lnz + F.unz - n) / A.Ap[n] * 100) / 100;
  void ORIG; const xs = new Float64Array(n);
  rec.solveMs = timeIt(() => { xs.set(b); S.luSolve(F, xs); }, 100);
  rec.sparseResid = residual(A, xs, b);
  // refactor with Newton-like perturbed values, then check accuracy against the perturbed matrix
  const A2 = perturb(A, 7);
  let rok = true;
  rec.refactorMs = timeIt(() => { rok = S.luRefactor(F, A2); }, 100);
  rec.refactorOk = rok;
  if (rok) { const x3 = Float64Array.from(b); S.luSolve(F, x3); rec.refactorResid = residual(A2, x3, b); }
  // natural ordering for comparison (no fill-reducing order)
  if (process.env.NATURAL) {
    const qn = Int32Array.from({ length: n }, (_, i) => i);
    let Fn; rec.naturalFactorMs = timeIt(() => { Fn = S.luFactor(A, qn, 1e-3); }, 100, 50);
    rec.naturalFill = Fn.ok ? Math.round((Fn.lnz + Fn.unz - n) / A.Ap[n] * 100) / 100 : 'fail';
  }
  return rec;
}

const fmt = (v) => v === undefined || v === null ? '-' : typeof v === 'number' ? (Math.abs(v) < 1e-3 && v !== 0 ? v.toExponential(1) : v >= 100 ? Math.round(v) : Math.round(v * 1000) / 1000) : v;
const COLS = ['name', 'n', 'nnz', 'fill', 'denseFactorMs', 'denseOptFactorMs', 'denseCopyMs', 'denseSolveMs', 'orderMs', 'factorMs', 'refactorMs', 'solveMs', 'denseResid', 'sparseResid', 'refactorResid', 'naturalFill'];
const out = [];
const arg = process.argv[2] || 'grid,ladder,rand';
if (arg === 'captured') {
  const dir = path.join(HERE, 'matrices');
  for (const f of fs.readdirSync(dir).filter((f) => f.endsWith('.json')).sort()) {
    const c = JSON.parse(fs.readFileSync(path.join(dir, f)));
    const b = new Float64Array(c.n); const r = G.rng(5); for (let i = 0; i < c.n; i++) b[i] = r() - 0.5;
    const rec = benchOne('cap:' + f.replace('.json', ''), c.n, c.I, c.J, c.V, b); out.push(rec);
    console.log(COLS.map((k) => fmt(rec[k])).join('\t'));
  }
} else {
  const sizes = (process.argv[3] || '50,100,200,500,1000,2000,5000').split(',').map(Number);
  console.log(COLS.join('\t'));
  for (const kind of arg.split(',')) for (const n of sizes) {
    let M;
    if (kind === 'grid') { const N = Math.round(Math.sqrt(n)); M = G.grid2d(N); }
    else if (kind === 'ladder') M = G.ladder(n);
    else if (kind === 'rand') M = G.randNet(n);
    const rec = benchOne(kind, M.n, M.I, M.J, M.V, M.b);
    out.push(rec);
    console.log(COLS.map((k) => fmt(rec[k])).join('\t'));
  }
}
fs.writeFileSync(path.join(HERE, 'out', `bench_${process.env.TAG || arg}.json`), JSON.stringify(out, null, 1));
