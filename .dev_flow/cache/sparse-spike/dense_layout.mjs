// Pure layout effect: right-looking kij on Float64Array rows WITHOUT zero skipping, vs the port.
import * as D from './dense.mjs';
function noskip(a, n, ipvt) {
  for (let k = 0; k < n; k++) {
    let p = k, big = Math.abs(a[k][k]);
    for (let i = k + 1; i < n; i++) { const v = Math.abs(a[i][k]); if (v > big) { big = v; p = i; } }
    ipvt[k] = p; if (p !== k) { const t = a[k]; a[k] = a[p]; a[p] = t; }
    const rk = a[k], inv = 1 / rk[k];
    for (let i = k + 1; i < n; i++) { const ri = a[i]; const l = ri[k] * inv; ri[k] = l; for (let j = k + 1; j < n; j++) ri[j] -= l * rk[j]; }
  }
}
for (const n of [500, 1000, 2000]) {
  const a = Array.from({ length: n }, (_, i) => { const r = new Float64Array(n); for (let j = 0; j < n; j++) r[j] = Math.random(); r[i] += n; return r; });
  const ip = new Int32Array(n);
  let t = performance.now(); noskip(a, n, ip); const t1 = performance.now() - t;
  // the port on a fully dense matrix (no zeros at all)
  const b = D.newMatrix(n); for (let i = 0; i < n; i++) { for (let j = 0; j < n; j++) b[i][j] = Math.random(); b[i][i] += n; }
  const ip2 = new Array(n).fill(0);
  t = performance.now(); D.lu_factor(b, n, ip2); const t2 = performance.now() - t;
  console.log(n, 'fully dense matrix: port', Math.round(t2), 'ms; row-oriented Float64Array (no zero skip)', Math.round(t1), 'ms; ratio', (t2 / t1).toFixed(1));
}
