// MNA-like test matrices as triplets (I, J, V) with b; node rows first, then voltage-source rows
// (zero diagonal). Deterministic PRNG.
export function rng(seed) { let s = seed >>> 0; return () => { s = (s + 0x6D2B79F5) >>> 0; let t = s; t = Math.imul(t ^ (t >>> 15), t | 1); t ^= t + Math.imul(t ^ (t >>> 7), t | 61); return ((t ^ (t >>> 14)) >>> 0) / 4294967296; }; }

class Mna {
  constructor(nodes) { this.nodes = nodes; this.vs = 0; this.I = []; this.J = []; this.V = []; this.srcs = []; }
  add(i, j, v) { if (i >= 0 && j >= 0) { this.I.push(i); this.J.push(j); this.V.push(v); } }
  // node index -1 = ground
  g(a, b, g) { this.add(a, a, g); this.add(b, b, g); this.add(a, b, -g); this.add(b, a, -g); }
  vsrc(a, b, v) { const k = this.nodes + this.vs++; this.add(k, a, -1); this.add(k, b, 1); this.add(a, k, 1); this.add(b, k, -1); this.srcs.push([k, v]); return k; }
  // VCVS output (a,b) = gain * (c - d): voltage source row plus control terms
  vcvs(a, b, c, d, gain) { const k = this.vsrc(a, b, 0); this.add(k, c, gain); this.add(k, d, -gain); return k; }
  done() { const n = this.nodes + this.vs; const b = new Float64Array(n); for (const [k, v] of this.srcs) b[k] += v; return { n, I: this.I, J: this.J, V: this.V, b }; }
}

const logU = (r, lo, hi) => Math.exp(Math.log(lo) + r() * (Math.log(hi) - Math.log(lo)));

// 2-D resistor grid N x N, one voltage source between two inner nodes, corner tied to ground
export function grid2d(N, seed = 1) {
  const r = rng(seed), m = new Mna(N * N), id = (i, j) => j * N + i;
  for (let j = 0; j < N; j++) for (let i = 0; i < N; i++) {
    if (i + 1 < N) m.g(id(i, j), id(i + 1, j), logU(r, 1e-4, 1e-2));
    if (j + 1 < N) m.g(id(i, j), id(i, j + 1), logU(r, 1e-4, 1e-2));
  }
  m.g(id(N - 1, N - 1), -1, 1e-3);
  m.vsrc(-1, id(0, 0), 5);
  m.vsrc(id(1, 1), id(N >> 1, N >> 1), 1); // floating source: zero-diagonal row with two node entries
  return m.done();
}

// ladder: series R, shunt conductance (C companion / diode linearization) to ground
export function ladder(N, seed = 2) {
  const r = rng(seed), m = new Mna(N);
  for (let k = 0; k + 1 < N; k++) m.g(k, k + 1, 1e-2);
  for (let k = 0; k < N; k++) m.g(k, -1, logU(r, 1e-6, 1e-1));
  m.vsrc(-1, 0, 5);
  return m.done();
}

// "random circuit": locally connected net (degree 3-6, neighbours within a window, like a
// schematic's nets), a supply rail touching ~5 % of nodes, ~5 % floating voltage sources and
// ~1 % VCVS (op-amp) rows, conductances log-uniform 1e-8..1e2, 1e8-ohm ties for a few nodes.
export function randNet(n, seed = 3) {
  const r = rng(seed), m = new Mna(n);
  const W = 12;
  for (let a = 0; a < n; a++) {
    const d = 1 + Math.floor(r() * 2.5); // ~3-6 total degree after symmetric adds
    for (let k = 0; k < d; k++) {
      const b = Math.min(n - 1, a + 1 + Math.floor(r() * W));
      if (b !== a) m.g(a, b, logU(r, 1e-8, 1e2));
    }
    if (r() < 0.05) m.g(a, -1, logU(r, 1e-6, 1e-1));
  }
  const rail = Math.floor(n / 2);
  for (let a = 0; a < n; a++) if (a !== rail && r() < 0.05) m.g(rail, a, logU(r, 1e-5, 1e-2));
  m.vsrc(-1, rail, 5);
  m.vsrc(-1, 0, 1);
  for (let a = 0; a < n; a++) if (r() < 0.05) { const b = Math.min(n - 1, a + 1 + Math.floor(r() * W)); if (b !== a) m.vsrc(a, b, logU(r, 0.1, 5)); }
  for (let a = 0; a + 3 < n; a++) if (r() < 0.01) m.vcvs(a + 3, -1, a + 1, a + 2, 1e5);
  for (let a = 0; a < n; a += 97) m.g(a, -1, 1e-8);
  return m.done();
}
