// Scratch layout/drawing-standard checker for an agent-built CircuitJS1 document.
// Usage: node layout-check.mjs <doc> [--png out.png] [--json]
// Heuristic body model (cells): two_point part = segment with half-width W, interior trimmed by M at both ends;
// single = stem segment + symbol disc at end (+ text box for LabeledNode); derived = bbox of posts/start/end.
import fs from 'node:fs';
import { call, close } from './mcp.mjs';

const doc = process.argv[2];
const argv = process.argv.slice(3);
const W = 0.4, M = 0.5, STEP = 0.125, EPS = 1e-6;

async function all() {
  const out = [];
  for (let offset = 0; ; offset += 200) {
    const r = (await call('circuit_get', { doc, offset, limit: 200, detail: 'full' })).json;
    if (!r.ok) throw new Error(JSON.stringify(r.issues));
    out.push(...r.data.elements);
    if (out.length >= r.data.total || !r.data.elements.length) return { elements: out, sim: r.data.simulation };
  }
}
const typeInfo = {};
async function ti(t) { if (!typeInfo[t]) typeInfo[t] = (await call('circuit_types', { type: t })).json.data; return typeInfo[t]; }

const P = (p) => ({ x: p.x, y: p.y });
const key = (p) => `${+p.x.toFixed(3)},${+p.y.toFixed(3)}`;
const dist = (a, b) => Math.hypot(a.x - b.x, a.y - b.y);
function segProj(p, a, b) { const L = dist(a, b); if (L < EPS) return { t: 0, d: dist(p, a), L }; const ux = (b.x - a.x) / L, uy = (b.y - a.y) / L; const t = (p.x - a.x) * ux + (p.y - a.y) * uy; const d = Math.abs((p.x - a.x) * uy - (p.y - a.y) * ux); return { t, d, L }; }
function samples(a, b, trim) { const L = dist(a, b), n = Math.max(1, Math.ceil(L / STEP)), pts = []; for (let i = 0; i <= n; i++) { const t = (i / n) * L; if (t < trim - EPS || t > L - trim + EPS) continue; pts.push({ x: a.x + ((b.x - a.x) * t) / L, y: a.y + ((b.y - a.y) * t) / L }); } return pts; }

function bodyOf(e, info) {
  const s = P(e.start), en = P(e.end), posts = e.posts.map((p) => P(p.at));
  if (e.type === 'Wire') return { kind: 'wire', a: s, b: en };
  const g = info.geometry;
  if (g === 'two_point') return { kind: 'seg', a: posts[0] || s, b: posts[1] || en };
  if (g === 'single') {
    const b = { kind: 'single', a: s, b: en, r: 0.5 };
    if (e.type === 'LabeledNode' || /Label|Rail|Input|Output/.test(e.type)) {
      const txt = String((e.properties && (e.properties.label || e.properties.text)) || '');
      const len = Math.max(1, txt.length) * 0.5;
      const horiz = Math.abs(en.x - s.x) >= Math.abs(en.y - s.y);
      const dir = horiz ? Math.sign(en.x - s.x || 1) : Math.sign(en.y - s.y || 1);
      b.text = horiz ? { x1: Math.min(en.x, en.x + dir * len), x2: Math.max(en.x, en.x + dir * len), y1: en.y - 0.4, y2: en.y + 0.4, label: txt }
        : { x1: en.x - len / 2, x2: en.x + len / 2, y1: Math.min(en.y, en.y + dir * 1.0), y2: Math.max(en.y, en.y + dir * 1.0), label: txt };
    }
    return b;
  }
  const xs = [s.x, en.x, ...posts.map((p) => p.x)], ys = [s.y, en.y, ...posts.map((p) => p.y)];
  return { kind: 'box', x1: Math.min(...xs), x2: Math.max(...xs), y1: Math.min(...ys), y2: Math.max(...ys) };
}
// inside the body interior of element E (its own posts are excluded by the caller)
function inside(p, B) {
  if (B.kind === 'seg') { const { t, d, L } = segProj(p, B.a, B.b); return d <= W && t >= M && t <= L - M; }
  if (B.kind === 'single') {
    const { t, d, L } = segProj(p, B.a, B.b);
    if (d <= 0.25 && t >= M && t <= L + B.r * 0.5) return true;
    if (dist(p, B.b) < B.r) return true;
    return false;
  }
  if (B.kind === 'box') { const sx = B.x2 - B.x1 < 1 ? 0 : M, sy = B.y2 - B.y1 < 1 ? 0 : M; const wx = B.x2 - B.x1 < 1 ? W : 0, wy = B.y2 - B.y1 < 1 ? W : 0; return p.x > B.x1 + sx - wx && p.x < B.x2 - sx + wx && p.y > B.y1 + sy - wy && p.y < B.y2 - sy + wy; }
  return false;
}
const inText = (p, T) => T && p.x > T.x1 && p.x < T.x2 && p.y > T.y1 && p.y < T.y2;
function skeleton(B) {
  if (B.kind === 'wire') return samples(B.a, B.b, 0.2);
  if (B.kind === 'seg') return samples(B.a, B.b, M + 0.05);
  if (B.kind === 'single') return [...samples(B.a, B.b, 0.3), B.b];
  if (B.kind === 'box') { const pts = []; for (let x = B.x1 + 0.3; x <= B.x2 - 0.3 + EPS; x += 0.25) for (let y = B.y1 + 0.3; y <= B.y2 - 0.3 + EPS; y += 0.25) pts.push({ x, y }); if (!pts.length) pts.push({ x: (B.x1 + B.x2) / 2, y: (B.y1 + B.y2) / 2 }); return pts; }
  return [];
}

const E24 = [1.0, 1.1, 1.2, 1.3, 1.5, 1.6, 1.8, 2.0, 2.2, 2.4, 2.7, 3.0, 3.3, 3.6, 3.9, 4.3, 4.7, 5.1, 5.6, 6.2, 6.8, 7.5, 8.2, 9.1];
const E12 = [1.0, 1.2, 1.5, 1.8, 2.2, 2.7, 3.3, 3.9, 4.7, 5.6, 6.8, 8.2];
const E96 = Array.from({ length: 96 }, (_, i) => Math.round(10 ** (i / 96) * 100) / 100);
const SI = { f: 1e-15, p: 1e-12, n: 1e-9, u: 1e-6, 'µ': 1e-6, 'μ': 1e-6, m: 1e-3, k: 1e3, K: 1e3, M: 1e6, G: 1e9 };
function num(v) { if (typeof v === 'number') return v; const m = String(v).trim().match(/^(-?[\d.]+(?:e-?\d+)?)\s*([fpnuµμmkKMG]?)/); return m ? parseFloat(m[1]) * (SI[m[2]] || 1) : NaN; }
function inSeries(v, ser, tol = 0.011) { if (!(v > 0)) return false; const m = v / 10 ** Math.floor(Math.log10(v) + 1e-9); return ser.some((s) => Math.abs(m - s) / s < tol || Math.abs(m - s * 10) / (s * 10) < tol); }

const { elements, sim } = await all();
for (const e of elements) await ti(e.type);
const els = elements.map((e) => ({ e, B: bodyOf(e, typeInfo[e.type]), info: typeInfo[e.type] }));
const issues = [];
const add = (sev, code, msg) => issues.push({ sev, code, msg });

const parts = els.filter((x) => x.B.kind !== 'wire');
const wires = els.filter((x) => x.B.kind === 'wire');
const ownPost = (x, p) => x.e.posts.some((q) => dist(P(q.at), p) < 0.05);

// wire through part / part overlap / post inside part / label text overlap
const seen = new Set();
for (const A of els) {
  const sk = skeleton(A.B);
  for (const Bx of parts) {
    if (A === Bx) continue;
    const hit = sk.find((p) => inside(p, Bx.B) && !ownPost(Bx, p));
    if (hit) {
      const pair = [A.e.id, Bx.e.id].sort().join('|');
      if (A.B.kind === 'wire') add('error', 'wire_through_part', `Wire ${A.e.id} runs through the body of ${Bx.e.id} (${Bx.e.type}) near (${hit.x.toFixed(2)}, ${hit.y.toFixed(2)})`);
      else if (!seen.has(pair)) { seen.add(pair); add('error', 'part_overlap', `${A.e.id} (${A.e.type}) overlaps ${Bx.e.id} (${Bx.e.type}) near (${hit.x.toFixed(2)}, ${hit.y.toFixed(2)})`); }
    }
    if (Bx.B.text) {
      const th = sk.find((p) => inText(p, Bx.B.text));
      if (th) add('warning', 'label_text_overlap', `${A.e.id} (${A.e.type}) crosses the text '${Bx.B.text.label}' of ${Bx.e.id} near (${th.x.toFixed(2)}, ${th.y.toFixed(2)})`);
    }
  }
  for (const q of A.e.posts) {
    const p = P(q.at);
    for (const Bx of parts) {
      if (A === Bx || ownPost(Bx, p)) continue;
      if (inside(p, Bx.B)) add('error', 'post_inside_part', `Post ${A.e.id}.${q.pin} at (${p.x}, ${p.y}) lies inside the body of ${Bx.e.id} (${Bx.e.type})`);
    }
  }
}
// wire crossings (interior/interior), diagonal and zero-length wires
for (let i = 0; i < wires.length; i++) {
  const a = wires[i].B;
  if (dist(a.a, a.b) < EPS) add('error', 'zero_length_wire', `Wire ${wires[i].e.id} has zero length`);
  if (Math.abs(a.a.x - a.b.x) > EPS && Math.abs(a.a.y - a.b.y) > EPS) add('warning', 'diagonal_wire', `Wire ${wires[i].e.id} is diagonal`);
  for (let j = i + 1; j < wires.length; j++) {
    const b = wires[j].B;
    const d = (a.b.x - a.a.x) * (b.b.y - b.a.y) - (a.b.y - a.a.y) * (b.b.x - b.a.x);
    if (Math.abs(d) < EPS) continue;
    const t = ((b.a.x - a.a.x) * (b.b.y - b.a.y) - (b.a.y - a.a.y) * (b.b.x - b.a.x)) / d;
    const u = ((b.a.x - a.a.x) * (a.b.y - a.a.y) - (b.a.y - a.a.y) * (a.b.x - a.a.x)) / d;
    if (t > 0.01 && t < 0.99 && u > 0.01 && u < 0.99) add('info', 'wire_crossing', `Wires ${wires[i].e.id} and ${wires[j].e.id} cross without a junction`);
  }
}
// junction degree
const deg = new Map();
for (const x of els) for (const q of x.e.posts) { const k = key(q.at); deg.set(k, (deg.get(k) || 0) + 1); }
for (const [k, n] of deg) if (n >= 4) add('warning', 'four_way_junction', `${n} posts/wire ends meet at (${k}) — use staggered T-junctions`);
// collinear wire chains that could be one wire (pure 2-wire joints)
for (const [k, n] of deg) {
  if (n !== 2) continue;
  const ws = wires.filter((w) => key(w.B.a) === k || key(w.B.b) === k);
  if (ws.length === 2) {
    const v = ws.map((w) => (key(w.B.a) === k ? { x: w.B.b.x - w.B.a.x, y: w.B.b.y - w.B.a.y } : { x: w.B.a.x - w.B.b.x, y: w.B.a.y - w.B.b.y }));
    if (Math.abs(v[0].x * v[1].y - v[0].y * v[1].x) < EPS) add('info', 'split_straight_wire', `Wires ${ws[0].e.id}+${ws[1].e.id} form one straight run at (${k})`);
  }
}
// sizes and orientation
for (const x of parts) {
  const { e, info, B } = x;
  if (B.kind === 'seg') {
    const L = dist(B.a, B.b), dL = Math.hypot(info.defaultSize.dx, info.defaultSize.dy);
    if (L < 2 - EPS) add('error', 'compressed_part', `${e.id} (${e.type}) is only ${L} cells long (body needs >= 2)`);
    else if (L < Math.min(3, dL) - EPS) add('warning', 'short_part', `${e.id} (${e.type}) is ${L} cells long (default ${dL})`);
    if (Math.abs(B.a.x - B.b.x) > EPS && Math.abs(B.a.y - B.b.y) > EPS) add('info', 'diagonal_part', `${e.id} (${e.type}) is drawn diagonally`);
  }
  for (const c of ['start', 'end']) if (Math.abs(e[c].x * 2 - Math.round(e[c].x * 2)) > EPS || Math.abs(e[c].y * 2 - Math.round(e[c].y * 2)) > EPS) add('warning', 'off_grid', `${e.id}.${c} off the half-cell lattice`);
  if (e.type === 'Ground' && !(e.end.y > e.start.y)) add('warning', 'ground_orientation', `Ground ${e.id} does not point down (start ${e.start.x},${e.start.y} end ${e.end.x},${e.end.y})`);
  if (/^VoltageSource(DC|AC)$|^Battery/.test(e.type)) {
    const plus = e.posts.find((q) => q.pin === 'plus'), minus = e.posts.find((q) => q.pin === 'minus');
    if (plus && minus && plus.at.x === minus.at.x && plus.at.y > minus.at.y) add('info', 'source_upside_down', `${e.id}: '+' terminal is below '-'`);
    if (plus && minus && plus.at.y === minus.at.y) add('info', 'source_horizontal', `${e.id} is horizontal (vertical with + up is customary)`);
  }
}
// tight spacing: parallel axis-aligned two_point parts closer than 2 cells with overlapping spans (values collide)
const segs = parts.filter((x) => x.B.kind === 'seg');
for (let i = 0; i < segs.length; i++) for (let j = i + 1; j < segs.length; j++) {
  const a = segs[i].B, b = segs[j].B;
  const av = a.a.x === a.b.x, bv = b.a.x === b.b.x, ah = a.a.y === a.b.y, bh = b.a.y === b.b.y;
  let gap = null, ov = 0;
  if (av && bv) { gap = Math.abs(a.a.x - b.a.x); ov = Math.min(Math.max(a.a.y, a.b.y), Math.max(b.a.y, b.b.y)) - Math.max(Math.min(a.a.y, a.b.y), Math.min(b.a.y, b.b.y)); }
  else if (ah && bh) { gap = Math.abs(a.a.y - b.a.y); ov = Math.min(Math.max(a.a.x, a.b.x), Math.max(b.a.x, b.b.x)) - Math.max(Math.min(a.a.x, a.b.x), Math.min(b.a.x, b.b.x)); }
  if (gap !== null && gap > EPS && gap < 3 - EPS && ov > 1) add('warning', 'tight_spacing', `${segs[i].e.id} and ${segs[j].e.id} are parallel only ${gap} cell(s) apart over ${ov} cells (values collide; keep >= 3)`);
}
// values
for (const { e } of parts) {
  const pr = e.properties || {};
  if (e.type === 'Resistor') { const v = num(pr.resistance); if (!(v >= 0.1 && v <= 1e8)) add('warning', 'extreme_value', `${e.id} resistance ${pr.resistance}`); else if (!inSeries(v, E24) && !inSeries(v, E96, 0.006)) add('info', 'nonstandard_value', `${e.id} ${pr.resistance} not in E24/E96`); }
  if (e.type === 'Capacitor' && num(pr.capacitance) >= 1e-6) add('info', 'electrolytic_unpolarized', `${e.id} ${pr.capacitance} drawn non-polar; a real part this size is electrolytic (PolarCapacitor, '+' mark)`);
  if (/Rail$/.test(e.type) && e.type !== 'ACRail' && e.end.x === e.start.x && ((num(pr.max_voltage) >= 0) !== (e.end.y < e.start.y))) add('warning', 'rail_orientation', `Rail ${e.id} (${pr.max_voltage}) should point ${num(pr.max_voltage) >= 0 ? 'up' : 'down'}`);
  if (e.type === 'Capacitor' || e.type === 'PolarCapacitor') { const v = num(pr.capacitance); if (!(v >= 1e-13 && v <= 10)) add('warning', 'extreme_value', `${e.id} capacitance ${pr.capacitance}`); else if (!inSeries(v, E12) && !inSeries(v, E24)) add('info', 'nonstandard_value', `${e.id} ${pr.capacitance} not in E12/E24`); }
  if (e.type === 'Inductor') { const v = num(pr.inductance); if (!(v >= 1e-9 && v <= 100)) add('warning', 'extreme_value', `${e.id} inductance ${pr.inductance}`); }
}
// extent
const xs = els.flatMap((x) => [x.e.start.x, x.e.end.x]), ys = els.flatMap((x) => [x.e.start.y, x.e.end.y]);
const extent = { x: [Math.min(...xs), Math.max(...xs)], y: [Math.min(...ys), Math.max(...ys)] };
const conn = (await call('circuit_connectivity', { doc, includeNets: false })).json.data;
const counts = {}; for (const i of issues) counts[i.code] = (counts[i.code] || 0) + 1;
const summary = { doc, elements: elements.length, wires: wires.length, parts: parts.length, extent, sim: { time_step: sim.time_step, auto: sim.auto_time_step }, connectivity: conn.issues.map((i) => `${i.severity}:${i.code}: ${i.message}`), layout: counts };
console.log(JSON.stringify(summary, null, 1));
for (const i of issues) console.log(`${i.sev.padEnd(7)} ${i.code}: ${i.msg}`);
if (argv.includes('--json')) fs.writeFileSync(`layout-${doc}.json`, JSON.stringify({ summary, issues, elements }, null, 1));
const pi = argv.indexOf('--png');
if (pi >= 0) { const r = await call('circuit_render', { doc, scale: 2 }); if (r.images[0]) fs.writeFileSync(argv[pi + 1], Buffer.from(r.images[0].data, 'base64')); }
await close();
