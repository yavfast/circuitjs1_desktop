// Faithful port of CircuitMath.lu_factor / lu_solve (Crout, partial pivoting, row-pointer swaps),
// on arrays of arrays created like GWT does (new Array(n) filled with 0).
export function newMatrix(n) {
  const a = new Array(n);
  for (let i = 0; i < n; i++) { const r = new Array(n); for (let j = 0; j < n; j++) r[j] = 0; a[i] = r; }
  return a;
}

export function lu_factor(a, n, ipvt) {
  if (n < 0) return false;
  if (n === 0) return true;
  if (n === 1) { ipvt[0] = 0; return a[0][0] !== 0; }
  for (let j = 0; j < n; j++) {
    for (let i = 0; i < n; i++) {
      let sum = a[i][j];
      const k_max = i < j ? i : j;
      if (k_max > 0) {
        const row_i = a[i];
        for (let k = 0; k < k_max; k++) sum -= row_i[k] * a[k][j];
        a[i][j] = sum;
      }
    }
    let largest = 0, largestRow = j;
    for (let i = j; i < n; i++) { let abs = a[i][j]; if (abs < 0) abs = -abs; if (abs > largest) { largest = abs; largestRow = i; } }
    if (largest < 1e-14) return false;
    if (largestRow !== j) { const t = a[j]; a[j] = a[largestRow]; a[largestRow] = t; }
    ipvt[j] = largestRow;
    if (j < n - 1) {
      const pivot = a[j][j];
      if (Math.abs(pivot) < 1e-14) return false;
      const pinv = 1 / pivot;
      for (let i = j + 1; i < n; i++) a[i][j] *= pinv;
    }
  }
  return true;
}

export function lu_solve(a, n, ipvt, b) {
  if (n <= 0) return;
  for (let i = 0; i < n; i++) {
    const row = ipvt[i];
    if (row !== i) { const s = b[row]; b[row] = b[i]; b[i] = s; }
    let sum = b[i];
    for (let j = 0; j < i; j++) sum -= a[i][j] * b[j];
    b[i] = sum;
  }
  for (let i = n - 1; i >= 0; i--) {
    let sum = b[i];
    for (let j = i + 1; j < n; j++) sum -= a[i][j] * b[j];
    b[i] = sum / a[i][i];
  }
}

// Dense, cache-friendly alternative (no sparsity): right-looking kij elimination on Float64Array
// rows, partial pivoting, skipping zero multipliers. Same O(m^3) worst case, contiguous row access.
export function dense_opt_factor(a, n, ipvt) {
  for (let k = 0; k < n; k++) {
    let p = k, big = Math.abs(a[k][k]);
    for (let i = k + 1; i < n; i++) { const v = Math.abs(a[i][k]); if (v > big) { big = v; p = i; } }
    if (big < 1e-14) return false;
    ipvt[k] = p;
    if (p !== k) { const t = a[k]; a[k] = a[p]; a[p] = t; }
    const rk = a[k], inv = 1 / rk[k];
    for (let i = k + 1; i < n; i++) {
      const ri = a[i]; const f = ri[k];
      if (f === 0) continue;
      const l = f * inv; ri[k] = l;
      for (let j = k + 1; j < n; j++) ri[j] -= l * rk[j];
    }
  }
  return true;
}
export function dense_opt_solve(a, n, ipvt, b) {
  for (let i = 0; i < n; i++) { const r = ipvt[i]; if (r !== i) { const s = b[r]; b[r] = b[i]; b[i] = s; } }
  for (let i = 0; i < n; i++) { const ri = a[i]; let s = b[i]; for (let j = 0; j < i; j++) s -= ri[j] * b[j]; b[i] = s; }
  for (let i = n - 1; i >= 0; i--) { const ri = a[i]; let s = b[i]; for (let j = i + 1; j < n; j++) s -= ri[j] * b[j]; b[i] = s / ri[i]; }
}
