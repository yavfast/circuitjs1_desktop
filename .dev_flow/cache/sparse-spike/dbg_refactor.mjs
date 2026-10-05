import * as S from './sparse.mjs'; import * as G from './matgen.mjs';
for (const n of [500, 1000, 2000]) {
  const M = G.randNet(n); const A = S.tripletsToCSC(M.n, M.I, M.J, M.V);
  const q = S.minDegreeOrder(A); const F = S.luFactor(A, q, 1e-3);
  // perturbation as bench
  const r = G.rng(7); const Ax = Float64Array.from(A.Ax);
  for (let j = 0; j < A.n; j++) for (let p = A.Ap[j]; p < A.Ap[j + 1]; p++) if (A.Ai[p] === j || r() < 0.5) Ax[p] *= 0.5 + r();
  const A2 = { ...A, Ax };
  for (const gt of [1e-8, 1e-12, 0]) { const F2 = S.luFactor(A, q, 1e-3); const ok = S.luRefactor(F2, A2, gt); let res = null;
    if (ok) { const x = Float64Array.from(M.b); S.luSolve(F2, x); const rr = Float64Array.from(M.b); for (let j = 0; j < A.n; j++) for (let p = A.Ap[j]; p < A.Ap[j+1]; p++) rr[A.Ai[p]] -= Ax[p]*x[j]; res = Math.hypot(...rr)/Math.hypot(...M.b); }
    console.log(n, 'growthTol', gt, 'ok', ok, 'resid', res); }
  // realistic Newton perturbation: only diagonal +- small (diode conductance changes on nonlinear rows)
  const Ax3 = Float64Array.from(A.Ax); const r3 = G.rng(9);
  for (let j = 0; j < A.n; j++) for (let p = A.Ap[j]; p < A.Ap[j + 1]; p++) if (A.Ai[p] === j && j < n && r3() < 0.2) Ax3[p] += Math.exp(Math.log(1e-12) + r3() * Math.log(1e12)); // diode g 1e-12..1
  const F3 = S.luFactor(A, q, 1e-3); const ok3 = S.luRefactor(F3, { ...A, Ax: Ax3 });
  const x = Float64Array.from(M.b); if (ok3) S.luSolve(F3, x); const rr = Float64Array.from(M.b); for (let j = 0; j < A.n; j++) for (let p = A.Ap[j]; p < A.Ap[j+1]; p++) rr[A.Ai[p]] -= Ax3[p]*x[j];
  console.log(n, 'diode-like diag change: refactor ok', ok3, 'resid', ok3 ? Math.hypot(...rr)/Math.hypot(...M.b) : null);
}
