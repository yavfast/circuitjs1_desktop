import { call, close } from './mcp.mjs';
import fs from 'node:fs';
const types = process.argv[2].split(',');
const d = (await call('circuit_documents', { action: 'create', title: 'single-test' })).json.data.doc;
const els = [];
types.forEach((t, i) => {
  const y = i * 6;
  els.push({ type: t, start: { x: 0, y }, end: { x: 1, y } });
  els.push({ type: t, start: { x: 10, y }, end: { x: 14, y } });
  els.push({ type: t, start: { x: 24, y }, end: { x: 24, y: y - 1 } });
  els.push({ type: t, start: { x: 32, y }, end: { x: 32, y: y + 1 } });
});
const r = await call('circuit_import', { doc: d, circuit: { elements: els } });
console.log(d, r.json.ok, JSON.stringify(r.json.issues || []).slice(0, 300));
const img = await call('circuit_render', { doc: d, scale: 1.5 });
fs.writeFileSync(process.argv[3], Buffer.from(img.images[0].data, 'base64'));
await close();
