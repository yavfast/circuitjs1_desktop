// Sparse LU for MNA matrices (spike prototype, throwaway).
//  - ordering: minimum degree on the pattern of A + A^T (explicit elimination graph, a simple
//    stand-in for AMD) — or 'natural'
//  - factor:   left-looking Gilbert–Peierls (as CSparse cs_lu / KLU), threshold partial pivoting
//              with diagonal preference (tol), so zero-diagonal voltage-source rows pivot off-diagonal
//  - refactor: same pattern and pivot sequence, numeric values only (KLU klu_refactor), with a
//              pivot-growth check that asks for a full factor when a pivot became too small
//  - solve:    forward/back substitution with the row and column permutations
// CSC input: Ap (n+1), Ai, Ax.

export function tripletsToCSC(n, I, J, V) {
  // sums duplicates
  const cnt = zeroArr(n + 1);
  for (let k = 0; k < J.length; k++) cnt[J[k] + 1]++;
  for (let j = 0; j < n; j++) cnt[j + 1] += cnt[j];
  const Ap = Array.from(cnt), next = Array.from(cnt.slice(0, n));
  const Ai0 = zeroArr(J.length), Ax0 = zeroArr(J.length);
  for (let k = 0; k < J.length; k++) { const p = next[J[k]]++; Ai0[p] = I[k]; Ax0[p] = V[k]; }
  // merge duplicates per column
  const mark = fillArr(n, -1), pos = zeroArr(n);
  const Ai = [], Ax = [], Bp = zeroArr(n + 1);
  for (let j = 0; j < n; j++) {
    const start = Ai.length;
    for (let p = Ap[j]; p < Ap[j + 1]; p++) {
      const i = Ai0[p];
      if (mark[i] === j) Ax[pos[i]] += Ax0[p];
      else { mark[i] = j; pos[i] = Ai.length; Ai.push(i); Ax.push(Ax0[p]); }
    }
    Bp[j + 1] = Ai.length;
    void start;
  }
  return { n, Ap: Bp, Ai: Array.from(Ai), Ax: Array.from(Ax) };
}

// ---- ordering: minimum degree on A + A^T (no aggressive absorption / supervariables)
export function minDegreeOrder(A) {
  const { n, Ap, Ai } = A;
  const adj = Array.from({ length: n }, () => new Set());
  for (let j = 0; j < n; j++) for (let p = Ap[j]; p < Ap[j + 1]; p++) { const i = Ai[p]; if (i !== j) { adj[i].add(j); adj[j].add(i); } }
  const done = new Uint8Array(n), order = zeroArr(n);
  // bucket queue by degree with lazy deletion
  const deg = zeroArr(n);
  const buckets = [];
  const push = (v) => { const d = adj[v].size; deg[v] = d; (buckets[d] || (buckets[d] = [])).push(v); };
  for (let v = 0; v < n; v++) push(v);
  let minD = 0;
  for (let k = 0; k < n; k++) {
    let v = -1;
    for (;;) {
      while (minD < buckets.length && (!buckets[minD] || buckets[minD].length === 0)) minD++;
      const b = buckets[minD]; const c = b.pop();
      if (!done[c] && deg[c] === minD && adj[c].size === minD) { v = c; break; }
    }
    done[v] = 1; order[k] = v;
    const nb = [...adj[v]];
    for (const u of nb) adj[u].delete(v);
    for (let x = 0; x < nb.length; x++) { const a = adj[nb[x]]; for (let y = 0; y < nb.length; y++) if (x !== y) a.add(nb[y]); }
    for (const u of nb) { push(u); if (adj[u].size < minD) minD = adj[u].size; }
    adj[v] = null;
  }
  return order;
}

// ---- numeric factorization (Gilbert–Peierls, CSparse cs_lu style)
// q: column order (q[k] = original column eliminated at step k); tol: diagonal preference threshold
export function luFactor(A, q, tol = 1e-3) {
  const { n, Ap, Ai, Ax } = A;
  let lcap = 4 * Ap[n] + n, ucap = 4 * Ap[n] + n;
  let Li = zeroArr(lcap), Lx = zeroArr(lcap), Ui = zeroArr(ucap), Ux = zeroArr(ucap);
  const Lp = zeroArr(n + 1), Up = zeroArr(n + 1);
  const pinv = fillArr(n, -1), prow = zeroArr(n);
  const x = zeroArr(n), xi = zeroArr(2 * n), stackP = zeroArr(n), marks = fillArr(n, -1);
  let lnz = 0, unz = 0;
  for (let k = 0; k < n; k++) {
    Lp[k] = lnz; Up[k] = unz;
    if (lnz + n > lcap) { lcap = 2 * lcap + n; Li = growA(Li, lcap); Lx = growA(Lx, lcap); }
    if (unz + n > ucap) { ucap = 2 * ucap + n; Ui = growA(Ui, ucap); Ux = growA(Ux, ucap); }
    const col = q[k];
    // reach: DFS from each row of A(:,col) in the graph of L (pivotal rows only have children)
    let top = n;
    for (let p = Ap[col]; p < Ap[col + 1]; p++) {
      const s = Ai[p];
      if (marks[s] === k) continue;
      // iterative DFS
      let head = 0; xi[0] = s;
      while (head >= 0) {
        const j = xi[head];
        const J = pinv[j];
        if (marks[j] !== k) { marks[j] = k; stackP[head] = J < 0 ? 0 : Lp[J] + 1; }
        let doneNode = true;
        if (J >= 0) {
          const pend = Lp[J + 1];
          for (let pp = stackP[head]; pp < pend; pp++) {
            const i = Li[pp];
            if (marks[i] === k) continue;
            stackP[head] = pp + 1; xi[++head] = i; doneNode = false; break;
          }
        }
        if (doneNode) { head--; xi[--top + n] = j; }
      }
    }
    // xi[n+top .. 2n-1] is topological order
    for (let p = top; p < n; p++) x[xi[p + n]] = 0;
    for (let p = Ap[col]; p < Ap[col + 1]; p++) x[Ai[p]] = Ax[p];
    for (let p = top; p < n; p++) {
      const j = xi[p + n], J = pinv[j];
      if (J < 0) continue;
      const xj = x[j]; // unit diagonal L
      if (xj === 0) continue;
      for (let pp = Lp[J] + 1; pp < Lp[J + 1]; pp++) x[Li[pp]] -= Lx[pp] * xj;
    }
    // pivot search; U entries are the pivotal rows (in topological order)
    let ipiv = -1, amax = -1;
    for (let p = top; p < n; p++) {
      const i = xi[p + n];
      if (pinv[i] < 0) { const t = Math.abs(x[i]); if (t > amax) { amax = t; ipiv = i; } }
      else { Ui[unz] = pinv[i]; Ux[unz++] = x[i]; }
    }
    if (ipiv < 0 || amax <= 1e-14) return { ok: false, failStep: k, failCol: col, amax };
    // diagonal preference: the row with the same index as the column (symmetric ordering)
    if (pinv[col] < 0 && marks[col] === k && Math.abs(x[col]) >= amax * tol) ipiv = col;
    const pivot = x[ipiv];
    Ui[unz] = k; Ux[unz++] = pivot;
    pinv[ipiv] = k; prow[k] = ipiv;
    Li[lnz] = ipiv; Lx[lnz++] = 1;
    for (let p = top; p < n; p++) { const i = xi[p + n]; if (pinv[i] < 0) { Li[lnz] = i; Lx[lnz++] = x[i] / pivot; } x[i] = 0; }
  }
  Lp[n] = lnz; Up[n] = unz;
  return { ok: true, n, q, pinv, prow, Lp, Li, Lx, Up, Ui, Ux, lnz, unz, tol };
}

// ---- refactor: same pattern and pivot order (A must have the pattern of the factored matrix,
// or a subset of it). growthTol: a pivot smaller than growthTol * max |candidate| in its column
// fails the refactor (the caller then factors from scratch, re-pivoting).
export function luRefactor(F, A, growthTol = 1e-8) {
  const { n, q, prow, Lp, Li, Lx, Up, Ui, Ux } = F;
  const { Ap, Ai, Ax } = A;
  const x = F.work || (F.work = zeroArr(n));
  for (let k = 0; k < n; k++) {
    const col = q[k];
    for (let p = Ap[col]; p < Ap[col + 1]; p++) x[Ai[p]] = Ax[p];
    const ue = Up[k + 1] - 1; // diagonal stored last
    for (let p = Up[k]; p < ue; p++) {
      const J = Ui[p], xj = x[prow[J]];
      Ux[p] = xj;
      if (xj === 0) continue;
      for (let pp = Lp[J] + 1; pp < Lp[J + 1]; pp++) x[Li[pp]] -= Lx[pp] * xj;
    }
    const r = prow[k], pivot = x[r];
    let amax = Math.abs(pivot);
    for (let pp = Lp[k] + 1; pp < Lp[k + 1]; pp++) { const t = Math.abs(x[Li[pp]]); if (t > amax) amax = t; }
    if (!(Math.abs(pivot) > 1e-14) || Math.abs(pivot) < growthTol * amax) {
      for (let p = Up[k]; p < ue; p++) x[prow[Ui[p]]] = 0;
      for (let pp = Lp[k]; pp < Lp[k + 1]; pp++) x[Li[pp]] = 0;
      return false;
    }
    Ux[ue] = pivot;
    for (let p = Up[k]; p < ue; p++) x[prow[Ui[p]]] = 0;
    x[r] = 0;
    for (let pp = Lp[k] + 1; pp < Lp[k + 1]; pp++) { const i = Li[pp]; Lx[pp] = x[i] / pivot; x[i] = 0; }
  }
  return true;
}

// ---- solve A x = b (b overwritten with x)
export function luSolve(F, b) {
  const { n, q, prow, Lp, Li, Lx, Up, Ui, Ux } = F;
  const z = F.z || (F.z = zeroArr(n));
  for (let k = 0; k < n; k++) {
    const zk = b[prow[k]];
    z[k] = zk;
    if (zk !== 0) for (let p = Lp[k] + 1; p < Lp[k + 1]; p++) b[Li[p]] -= Lx[p] * zk;
  }
  for (let k = n - 1; k >= 0; k--) {
    const ue = Up[k + 1] - 1;
    const zk = (z[k] /= Ux[ue]);
    if (zk !== 0) for (let p = Up[k]; p < ue; p++) z[Ui[p]] -= Ux[p] * zk;
  }
  for (let k = 0; k < n; k++) b[q[k]] = z[k];
}

// ---- maximum transversal (MC21-style DFS augmenting paths, KLU/BTF uses the same idea):
// match[col] = row such that A(row, col) != 0, giving a zero-free diagonal after the row
// permutation. Cheap assignment prefers the structural diagonal, then the largest entry.
export function maxTransversal(A) {
  const { n, Ap, Ai, Ax } = A;
  const rowOf = fillArr(n, -1), colOf = fillArr(n, -1);
  for (let j = 0; j < n; j++) {
    let best = -1, bv = -1;
    for (let p = Ap[j]; p < Ap[j + 1]; p++) { const i = Ai[p]; if (colOf[i] >= 0) continue; const v = i === j ? Infinity : Math.abs(Ax[p]); if (v > bv) { bv = v; best = i; } }
    if (best >= 0) { rowOf[j] = best; colOf[best] = j; }
  }
  const visited = fillArr(n, -1);
  const stackJ = zeroArr(n), stackP = zeroArr(n);
  for (let j0 = 0; j0 < n; j0++) {
    if (rowOf[j0] >= 0) continue;
    // DFS for an augmenting path from column j0
    let h = 0; stackJ[0] = j0; stackP[0] = Ap[j0]; visited[j0] = j0; let found = -1;
    while (h >= 0 && found < 0) {
      const j = stackJ[h]; let advanced = false;
      // lookahead (MC21 / cs_maxtrans): a free row in this column ends the path at once
      for (let p = Ap[j]; p < Ap[j + 1]; p++) if (colOf[Ai[p]] < 0) { found = Ai[p]; break; }
      if (found >= 0) break;
      for (let p = stackP[h]; p < Ap[j + 1]; p++) {
        const i = Ai[p]; stackP[h] = p + 1;
        if (colOf[i] < 0) { found = i; break; }
        const j2 = colOf[i];
        if (visited[j2] !== j0) { visited[j2] = j0; stackJ[++h] = j2; stackP[h] = Ap[j2]; advanced = true; break; }
      }
      if (found < 0 && !advanced) h--;
    }
    if (found < 0) return null; // structurally singular
    // augment along the stack
    let i = found;
    for (let k = h; k >= 0; k--) { const j = stackJ[k]; const prev = rowOf[j]; rowOf[j] = i; colOf[i] = j; i = prev; }
  }
  return rowOf; // rowOf[col] = matched row
}

// Pattern with rows renumbered so that the matched row of column j becomes j (B = P A).
export function permuteRows(A, rowOf) {
  const { n, Ap, Ai, Ax } = A; const inv = zeroArr(n);
  for (let j = 0; j < n; j++) inv[rowOf[j]] = j;
  const Bi = zeroArr(Ai.length); for (let p = 0; p < Ai.length; p++) Bi[p] = inv[Ai[p]];
  return { n, Ap, Ai: Bi, Ax, rowInv: inv };
}
// GWT-like plain JS arrays (new Array(n) filled with 0), as GWT emits int[] / double[]
function zeroArr(n) { const a = new Array(n); for (let i = 0; i < n; i++) a[i] = 0; return a; }
function fillArr(n, v) { const a = new Array(n); for (let i = 0; i < n; i++) a[i] = v; return a; }
function growA(a, cap) { const b = zeroArr(cap); for (let i = 0; i < a.length; i++) b[i] = a[i]; return b; }
