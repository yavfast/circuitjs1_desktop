const { io } = require('socket.io-client');

const channelId = process.env.CHANNEL_ID;
if (!channelId) {
  console.error('Missing CHANNEL_ID env var');
  process.exit(2);
}

const serverUrl = process.env.SERVER_URL || 'http://127.0.0.1:3030';
const timeoutMs = Number(process.env.TIMEOUT_MS || 20000);

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

async function main() {
  const socket = io(serverUrl, {
    path: '/debug',
    query: { channelId, role: 'viewer' },
    transports: ['websocket'],
    timeout: 5000,
  });

  let stage = 'connect';
  let finished = false;
  let gotDocumentInfo = false;
  let gotConsoleLog = false;
  let gotTelemetry = false;

  const fail = (msg) => {
    if (finished) return;
    finished = true;
    console.error('E2E FAIL:', msg);
    try {
      socket.disconnect();
    } catch {}
    process.exit(1);
  };

  const ok = (msg) => console.log('E2E OK:', msg);

  const watchdog = setTimeout(() => {
    fail(`timeout at stage=${stage}`);
  }, timeoutMs);

  socket.on('connect_error', (err) => fail(`connect_error: ${err.message}`));

  socket.on('document_info', (data) => {
    gotDocumentInfo = true;
    ok(`document_info docName=${data?.docName ?? ''} tabCount=${data?.tabCount ?? ''}`);
  });

  socket.on('console_log', (data) => {
    const msg = String(data?.message ?? '');
    if (msg.includes('RD_E2E_CONSOLE_OK')) {
      gotConsoleLog = true;
      ok('console_log captured');
    }
  });

  socket.on('telemetry', (data) => {
    const elements = data?.elements;
    if (elements && typeof elements === 'object' && elements.R1) {
      if (!gotTelemetry) ok('telemetry received for R1');
      gotTelemetry = true;

      if (stage === 'telemetry') {
        stage = 'screenshot_png';
        socket.emit('screenshot', { type: 'png' });
      }
    }
  });

  socket.on('execute_result', (data) => {
    if (stage !== 'execute') return;
    if (!data || data.success !== true) return fail(`execute_result not success: ${JSON.stringify(data).slice(0, 500)}`);
    const result = parseMaybeJson(data.result);
    if (!result || typeof result !== 'object') return fail('execute_result invalid payload');
    if (result.hasCircuitJS1 !== true) return fail(`CircuitJS1 missing: ${JSON.stringify(result)}`);
    if (!result.sim) return fail(`simInfo missing: ${JSON.stringify(result)}`);
    ok(`getSimInfo running=${result.sim.running} time=${result.sim.time} elementCount=${result.sim.elementCount}`);
    stage = 'export';
    socket.emit('circuit_export', { format: 'json', withState: false });
  });

  socket.on('circuit_export_result', (data) => {
    if (stage !== 'export') return;
    if (!data || data.success !== true) return fail(`circuit_export_result not success: ${JSON.stringify(data).slice(0, 500)}`);
    const circuit = parseMaybeJson(data.circuit);
    if (!circuit || typeof circuit !== 'object') return fail('export circuit invalid');
    const keys = Object.keys(circuit);
    ok(`circuit_export_result keys=${keys.slice(0, 10).join(',')}`);

    stage = 'import';
    socket.emit('execute', {
      command: `(() => {
        const circuit = {
          schema: { format: 'circuitjs', version: '2.0' },
          simulation: { time_step: '5 us', voltage_range: '5 V', current_speed: 50 },
          elements: {
            R1: { type: 'Resistor', p1: {x: 208, y: 112}, p2: {x: 208, y: 272}, properties: { resistance: '10 kΩ' } },
            C1: { type: 'Capacitor', p1: {x: 208, y: 272}, p2: {x: 352, y: 272}, properties: { capacitance: '10 uF' } },
            V1: { type: 'VoltageSource', p1: {x: 352, y: 272}, p2: {x: 352, y: 112}, properties: { waveform: 'square', frequency: '40 Hz', voltage: '5 V' } },
            W1: { type: 'Wire', p1: {x: 352, y: 112}, p2: {x: 208, y: 112} }
          },
          scopes: [ { element: 'C1', show_voltage: true, show_current: false } ]
        };
        CircuitJS1.importFromJson(JSON.stringify(circuit));
        console.log('RD_E2E_CONSOLE_OK');
        return { ok: true, ids: CircuitJS1.getElementIds?.(), sim: CircuitJS1.getSimInfo?.() };
      })()`
    });
  });

  socket.on('element_update_result', (data) => {
    if (stage !== 'update') return;
    if (!data || data.success !== true) return fail(`element_update_result not success: ${JSON.stringify(data).slice(0, 400)}`);
    ok(`element_update_result ${data.elementId}.${data.property}=${data.value}`);

    stage = 'telemetry';
    socket.emit('telemetry_subscribe', { elements: ['R1'], interval: 250, fields: ['voltage', 'current', 'power'] });
  });

  socket.on('screenshot_result', (data) => {
    if (stage !== 'screenshot_png' && stage !== 'screenshot_svg') return;
    if (!data || data.success !== true) return fail(`screenshot_result not success: ${JSON.stringify(data).slice(0, 400)}`);

    if (stage === 'screenshot_png') {
      if (typeof data.image !== 'string' || !data.image.startsWith('data:image/png')) {
        return fail(`unexpected png screenshot prefix: ${typeof data.image}`);
      }
      ok('screenshot png ok');
      stage = 'screenshot_svg';
      socket.emit('screenshot', { type: 'svg' });
      return;
    }

    if (typeof data.image !== 'string' || !data.image.startsWith('data:image/svg+xml')) {
      return fail(`unexpected svg screenshot prefix: ${typeof data.image}`);
    }
    ok('screenshot svg ok');

    stage = 'done';
    socket.emit('telemetry_unsubscribe', {});

    const must = [
      ['document_info', gotDocumentInfo],
      ['console_log', gotConsoleLog],
      ['telemetry', gotTelemetry],
    ];

    const missing = must.filter(([, v]) => !v).map(([k]) => k);
    if (missing.length) {
      return fail(`missing expected events: ${missing.join(', ')}`);
    }

    ok('completed');
    finished = true;
    clearTimeout(watchdog);
    socket.disconnect();
    process.exit(0);
  });

  socket.on('execute_result', (data) => {
    if (stage !== 'import') return;
    if (!data || data.success !== true) return fail(`import execute_result not success: ${JSON.stringify(data).slice(0, 500)}`);
    const result = parseMaybeJson(data.result);
    if (!result || result.ok !== true) return fail(`import did not return ok: ${JSON.stringify(result)}`);

    const ids = Array.isArray(result.ids) ? result.ids : [];
    if (!ids.includes('R1')) return fail(`expected R1 in ids after import; got: ${JSON.stringify(ids)}`);

    ok(`imported circuit ids_count=${ids.length} elementCount=${result.sim?.elementCount}`);

    stage = 'update';
    socket.emit('element_update', { elementId: 'R1', property: 'resistance', value: 2200 });
  });

  socket.on('telemetry', () => {
    if (stage !== 'telemetry') return;
    // handled above: gotTelemetry flag
  });

  socket.on('connect', async () => {
    ok(`viewer connected (id=${socket.id})`);

    stage = 'execute';
    socket.emit('execute', {
      command:
        '(() => ({ hasCircuitJS1: typeof CircuitJS1 !== "undefined", sim: (typeof CircuitJS1 !== "undefined" && CircuitJS1.getSimInfo) ? CircuitJS1.getSimInfo() : null }))()'
    });
  });
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
