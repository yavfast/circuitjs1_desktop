// node freqresp.mjs <doc> <srcId> <outNet> <step> f1,f2,...
import { call, close } from './mcp.mjs';
const [doc, src, out, step, fs] = process.argv.slice(2);
await call('circuit_sim', { doc, action: 'configure', settings: { maxTimeStep: step } });
for (const f of fs.split(',').map(Number)) {
  await call('circuit_edit', { doc, edits: [{ op: 'set', id: src, properties: { frequency: f } }] });
  const r = (await call('circuit_run', { doc, span: 30 / f, recordFrom: 20 / f, reset: true, budgetMs: 60000, probes: [{ net: 'in' }, { net: out }] })).json;
  const [i, o] = r.data.probes; console.log(JSON.stringify({ f, gain: +(o.stats.peakToPeak / i.stats.peakToPeak).toFixed(4), reason: r.data.reason }));
}
await call('circuit_edit', { doc, edits: [{ op: 'set', id: src, properties: { frequency: 1000 } }] });
await close();
