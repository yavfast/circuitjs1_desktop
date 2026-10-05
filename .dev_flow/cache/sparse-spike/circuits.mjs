// Synthetic circuit generators in the legacy text (.txt dump) format.
// Coordinates are editor pixels (grid 16). Throwaway spike code.

const HDR = (dt) => `$ 1 ${dt} 10.2 50 5 50 5e-11`;

// N x N resistor grid, DC source on corner (0,0), far corner grounded.
export function grid(N, { dt = 5e-6 } = {}) {
  const s = 64, L = [HDR(dt)];
  const X = (i) => 64 + s * i, Y = (j) => 64 + s * j;
  for (let j = 0; j < N; j++) for (let i = 0; i < N; i++) {
    if (i + 1 < N) L.push(`r ${X(i)} ${Y(j)} ${X(i + 1)} ${Y(j)} 0 1000`);
    if (j + 1 < N) L.push(`r ${X(i)} ${Y(j)} ${X(i)} ${Y(j + 1)} 0 1000`);
  }
  // source left of node (0,0): v (0,128)->(0,64), wire (0,64)->(64,64), ground at (0,128)
  L.push(`v 0 128 0 64 0 0 40 5 0 0 0.5`, `w 0 64 64 64 0`, `g 0 128 0 160 0`);
  L.push(`g ${X(N - 1)} ${Y(N - 1)} ${X(N - 1)} ${Y(N - 1) + 32} 0`);
  return L.join('\n') + '\n';
}

// Ladder: N series resistors, each node shunted to ground by `shunt`:
//   'c' capacitor (dynamic, linear), 'd' diode (nonlinear), 'r' resistor (static linear),
//   'dc' diode + capacitor.
// Driven by an AC source (1 kHz, 5 V) at the left end.
export function ladder(N, shunt, { dt = 5e-6 } = {}) {
  const s = 32, L = [HDR(dt)], y = 64, yb = 128;
  const X = (k) => 64 + s * k;
  L.push(`v 0 ${yb} 0 ${y} 0 1 1000 5 0 0 0.5`, `w 0 ${y} ${X(0)} ${y} 0`, `g 0 ${yb} 0 ${yb + 32} 0`);
  for (let k = 0; k < N; k++) {
    L.push(`r ${X(k)} ${y} ${X(k + 1)} ${y} 0 100`);
    const x = X(k + 1);
    if (shunt === 'c') L.push(`c ${x} ${y} ${x} ${yb} 0 1e-6 0 0.001`);
    else if (shunt === 'd') L.push(`d ${x} ${y} ${x} ${yb} 2 default`);
    else if (shunt === 'r') L.push(`r ${x} ${y} ${x} ${yb} 0 10000`);
    else if (shunt === 'dc') { L.push(`d ${x} ${y} ${x} ${yb} 2 default`); L.push(`c ${x} ${yb} ${x} ${yb + 64} 0 1e-6 0 0.001`); L.push(`g ${x} ${yb + 64} ${x} ${yb + 96} 0`); continue; }
    L.push(`g ${x} ${yb} ${x} ${yb + 32} 0`);
  }
  return L.join('\n') + '\n';
}

// N x N resistor grid where every node also has a diode to ground (2-D nonlinear).
export function diodeGrid(N, { dt = 5e-6 } = {}) {
  const s = 64, L = [HDR(dt)];
  const X = (i) => 64 + s * i, Y = (j) => 64 + s * j;
  for (let j = 0; j < N; j++) for (let i = 0; i < N; i++) {
    if (i + 1 < N) L.push(`r ${X(i)} ${Y(j)} ${X(i + 1)} ${Y(j)} 0 1000`);
    if (j + 1 < N) L.push(`r ${X(i)} ${Y(j)} ${X(i)} ${Y(j + 1)} 0 1000`);
    // diode diagonally down-right half a cell, then a ground
    L.push(`d ${X(i)} ${Y(j)} ${X(i) + 32} ${Y(j) + 32} 2 default`, `g ${X(i) + 32} ${Y(j) + 32} ${X(i) + 32} ${Y(j) + 48} 0`);
  }
  L.push(`v 0 128 0 64 0 1 1000 5 0 0 0.5`, `w 0 64 64 64 0`, `g 0 128 0 160 0`);
  return L.join('\n') + '\n';
}
