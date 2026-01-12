const fs = require('fs');
const path = require('path');
const { io } = require('socket.io-client');

const channelId = process.env.CHANNEL_ID;
if (!channelId) {
  console.error('Missing CHANNEL_ID env var');
  process.exit(2);
}

const serverUrl = process.env.SERVER_URL || 'http://127.0.0.1:3030';
const timeoutMs = Number(process.env.TIMEOUT_MS || 20000);
const outDir = process.env.OUT_DIR || __dirname;

function parseMaybeJson(value) {
  if (value == null) return value;
  if (typeof value !== 'string') return value;
  const trimmed = value.trim();
  if (!trimmed.startsWith('{') && !trimmed.startsWith('[')) return value;
  try {
    return JSON.parse(trimmed);
  } catch {
    return value;
  }
}

function summarizeCircuit(circuit) {
  const elements = circuit?.elements && typeof circuit.elements === 'object' ? circuit.elements : {};
  const ids = Object.keys(elements);

  const byType = new Map();
  for (const id of ids) {
    const el = elements[id];
    const type = String(el?.type ?? 'Unknown');
    if (!byType.has(type)) byType.set(type, []);
    byType.get(type).push(id);
  }

  const typesSorted = [...byType.entries()].sort((a, b) => b[1].length - a[1].length);
  const topTypes = typesSorted.slice(0, 20).map(([type, list]) => ({ type, count: list.length, sample: list.slice(0, 8) }));

  return {
    schema: circuit?.schema ?? null,
    simulation: circuit?.simulation ?? null,
    elementCount: ids.length,
    types: topTypes,
  };
}

function writeDataUrlToFile(dataUrl, filePath) {
  const match = /^data:([^;]+);base64,(.*)$/.exec(dataUrl);
  if (!match) throw new Error('Unexpected data URL format');
  const base64 = match[2];
  fs.writeFileSync(filePath, Buffer.from(base64, 'base64'));
}

async function main() {
  const socket = io(serverUrl, {
    path: '/debug',
    query: { channelId, role: 'viewer' },
    transports: ['websocket'],
    timeout: 5000,
  });

  let gotExport = false;
  let gotScreenshot = false;
  let docInfo = null;

  const exportPath = path.join(outDir, `tmp_export_${channelId}.json`);
  const summaryPath = path.join(outDir, `tmp_export_${channelId}.summary.json`);
  const screenshotPath = path.join(outDir, `tmp_screenshot_${channelId}.png`);

  const failTimer = setTimeout(() => {
    console.error(`FAIL: timeout after ${timeoutMs}ms (export=${gotExport} screenshot=${gotScreenshot})`);
    try { socket.disconnect(); } catch {}
    process.exit(1);
  }, timeoutMs);

  socket.on('connect_error', (err) => {
    console.error('FAIL: connect_error:', err?.message || err);
    process.exit(1);
  });

  socket.on('document_info', (data) => {
    docInfo = data;
    console.log(`document_info: docName=${data?.docName ?? ''} tabCount=${data?.tabCount ?? ''}`);
  });

  socket.on('circuit_export_result', (data) => {
    if (!data || data.success !== true) {
      console.error('FAIL: circuit_export_result not success:', JSON.stringify(data).slice(0, 500));
      process.exit(1);
    }

    const circuit = parseMaybeJson(data.circuit);
    if (!circuit || typeof circuit !== 'object') {
      console.error('FAIL: export circuit invalid');
      process.exit(1);
    }

    fs.writeFileSync(exportPath, JSON.stringify(circuit, null, 2), 'utf8');
    const summary = summarizeCircuit(circuit);
    summary.documentInfo = docInfo;
    fs.writeFileSync(summaryPath, JSON.stringify(summary, null, 2), 'utf8');

    console.log(`export: wrote ${exportPath}`);
    console.log(`summary: wrote ${summaryPath}`);
    console.log(`export summary: elements=${summary.elementCount} topTypes=${summary.types.length}`);

    gotExport = true;
    socket.emit('screenshot', { type: 'png' });
  });

  socket.on('screenshot_result', (data) => {
    if (!data || data.success !== true) {
      console.error('FAIL: screenshot_result not success:', JSON.stringify(data).slice(0, 500));
      process.exit(1);
    }
    if (typeof data.image !== 'string' || !data.image.startsWith('data:image/png')) {
      console.error('FAIL: screenshot_result unexpected image prefix');
      process.exit(1);
    }

    try {
      writeDataUrlToFile(data.image, screenshotPath);
    } catch (e) {
      console.error('FAIL: write screenshot:', e?.message || e);
      process.exit(1);
    }

    console.log(`screenshot: wrote ${screenshotPath}`);
    gotScreenshot = true;

    clearTimeout(failTimer);
    socket.disconnect();
    process.exit(0);
  });

  socket.on('connect', () => {
    console.log(`viewer connected: ${socket.id}`);
    socket.emit('circuit_export', { format: 'json', withState: false });
  });
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
