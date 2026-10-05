import fs from 'node:fs';
import * as S from './sparse.mjs'; import * as P from './sparse_plain.mjs';
const t = (f, reps) => { f(); const t0 = performance.now(); for (let k = 0; k < reps; k++) f(); return (performance.now() - t0) / reps; };
for (const f of ['grid_45', 'dgrid_22', 'dladder_1000', 'cladder_2000']) {
  const c = JSON.parse(fs.readFileSync('matrices/' + f + '.json'));
  const row = [];
  for (const L of [S, P]) {
    let A = L.tripletsToCSC(c.n, c.I, c.J, c.V);
    const m = L.maxTransversal(A); A = L.permuteRows(A, m);
    const q = L.minDegreeOrder(A); let F;
    const tf = t(() => { F = L.luFactor(A, q, 1e-3); }, 20);
    const tr = t(() => L.luRefactor(F, A), 50);
    const b = new Array(c.n).fill(1); const ts = t(() => L.luSolve(F, b.slice()), 200);
    row.push(`factor ${tf.toFixed(2)} refactor ${tr.toFixed(3)} solve ${ts.toFixed(3)}`);
  }
  console.log(f, '| typed:', row[0], '| plain:', row[1]);
}
