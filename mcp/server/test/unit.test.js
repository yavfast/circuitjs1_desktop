'use strict';
// [PL_MCP_P2] Pure-JS checks of the in-app MCP server's tool table, argument validation, result
// shaping and resources (no browser, no NW.js): `npm run test:mcp-unit` (node --test). A fake
// CircuitJS1Agent stands in for the app; the NW.js rows of SP_MCP_05 run against the real app
// (scratch driver in Phase 2, tests/mcp/e2e.mjs from Phase 4).

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

// the bundle loads agent-format.md as a string (esbuild text loader); do the same here
require.extensions['.md'] = (module, filename) => {
  module.exports = fs.readFileSync(filename, 'utf8');
};

const Ajv = require('ajv');
const { createTools, TOOLS } = require('../src/tools.js');
const shaping = require('../src/shaping.js');
const { validate } = require('../src/validate.js');
const { createResources, parseSetupList, AGENT_FORMAT } = require('../src/resources.js');
const { createAgentClient, ASYNC_TIMEOUT_MS } = require('../src/agent.js');
const { PENDING_TIMEOUT_MS, RESOURCE_NOT_FOUND, createProtocol } = require('../src/protocol.js');

const ROOT = path.resolve(__dirname, '..', '..', '..');
const LIMIT = shaping.TEXT_LIMIT;

/** A fake agent client: records calls; `answer(op, args)` returns the OperationResult. */
function fakeAgent(answer) {
  const calls = [];
  const errors = [];
  return {
    calls,
    errors,
    call: async (op, args) => {
      calls.push({ op, args: JSON.parse(JSON.stringify(args || {})) });
      return answer(op, args || {});
    },
    reportError: (m) => errors.push(m),
  };
}

const ok = (data) => ({ ok: true, data, issues: [], truncatedIssues: 0 });

async function rejects(promise, code, re) {
  await assert.rejects(promise, (e) => {
    assert.equal(e.code, code, e.message);
    if (re) assert.match(e.message, re);
    return true;
  });
}

// ------------------------------------------------------------------ catalogue (SP_MCP_02_02)

const ANNOTATIONS = {
  circuit_types: [true, false, true],
  circuit_documents: [false, true, false],
  circuit_import: [false, true, false],
  circuit_edit: [false, true, false],
  circuit_get: [true, false, true],
  circuit_connectivity: [true, false, true],
  circuit_read: [true, false, true],
  circuit_render: [true, false, true],
  circuit_layout: [true, false, true],
  circuit_sim: [false, true, false],
  circuit_run: [false, true, false],
  circuit_diagnostics: [true, false, true],
  circuit_checkpoint: [false, false, false],
  circuit_history: [false, true, false],
  circuit_file: [false, true, false],
};

test('15 tools with the SP_MCP_02_02 names and annotations', () => {
  const list = createTools(fakeAgent(() => ok({}))).list();
  assert.deepEqual(list.map((t) => t.name), Object.keys(ANNOTATIONS));
  for (const t of list) {
    const [ro, de, id] = ANNOTATIONS[t.name];
    assert.deepEqual([t.annotations.readOnlyHint, t.annotations.destructiveHint, t.annotations.idempotentHint], [ro, de, id], t.name);
    assert.equal(t.annotations.openWorldHint, false, t.name);
    assert.ok(t.title, t.name);
  }
});

test('descriptions: <= 1200 chars, default doc, an example, grid cells for geometry tools', () => {
  for (const t of createTools(fakeAgent(() => ok({}))).list()) {
    assert.ok(t.description.length <= 1200, `${t.name}: ${t.description.length} chars`);
    assert.match(t.description, /Example: \{/, t.name);
    assert.match(t.description, /active document|a given `doc` must be open/i, t.name);
    if (['circuit_import', 'circuit_edit', 'circuit_get', 'circuit_types', 'circuit_render'].includes(t.name)) {
      assert.match(t.description, /grid cells/, t.name);
    }
    // the example parses as the tool's arguments
    const m = /Example: (\{.*?\})(?: or (\{.*\}))?\.$/.exec(t.description);
    assert.ok(m, t.name);
    for (const ex of [m[1], m[2]].filter(Boolean)) {
      const args = JSON.parse(ex);
      assert.equal(validate(t.inputSchema, args), null, `${t.name} example ${ex}`);
    }
  }
});

test('schemas compile under strict ajv and the SDK client settings', () => {
  const strict = new Ajv({ strict: true, allowUnionTypes: true, strictRequired: false });
  const sdk = new Ajv({ strict: false, validateSchema: false, allErrors: true });
  for (const t of TOOLS) {
    strict.compile(t.inputSchema);
    strict.compile(t.outputSchema);
    sdk.compile(t.outputSchema);
    assert.equal(t.inputSchema.type, 'object');
    assert.equal(t.outputSchema.type, 'object');
  }
});

// ------------------------------------------------------------------ mapping (SP_MCP_05_02 "no circuit logic")

const MAPPING = [
  ['circuit_types', {}, 'listTypes', {}],
  ['circuit_types', { filter: 'mosfet' }, 'listTypes', { filter: 'mosfet' }],
  ['circuit_types', { type: 'Resistor', doc: 'd1' }, 'describeType', { doc: 'd1', type: 'Resistor' }],
  ['circuit_types', { models: 'all' }, 'listModels', {}],
  ['circuit_types', { models: 'diode' }, 'listModels', { kind: 'diode' }],
  ['circuit_types', { models: 'transistor', model: 'default', doc: 'd1' }, 'listModels', { doc: 'd1', kind: 'transistor', name: 'default' }],
  ['circuit_documents', { action: 'list' }, 'listDocuments', {}],
  ['circuit_documents', { action: 'create', title: 'x', activate: true }, 'createDocument', { title: 'x', activate: true }],
  ['circuit_documents', { action: 'activate', doc: 'd2' }, 'activateDocument', { doc: 'd2' }],
  ['circuit_documents', { action: 'close', doc: 'd2', discardChanges: true }, 'closeDocument', { doc: 'd2', discardChanges: true }],
  ['circuit_import', { circuit: 'text', doc: 'd3' }, 'importCircuit', { doc: 'd3', circuit: 'text' }],
  ['circuit_import', { circuit: { elements: [] } }, 'importCircuit', { circuit: { elements: [] } }],
  ['circuit_edit', { edits: [{ op: 'delete', id: 'R1' }] }, 'applyEdits', { edits: [{ op: 'delete', id: 'R1' }] }],
  ['circuit_edit', { edits: [{ op: 'defineModel', model: { kind: 'diode', name: 'led-g', parameters: { forward_voltage: '2.1 V', forward_current: '20 mA' } } }] }, 'applyEdits',
    { edits: [{ op: 'defineModel', model: { kind: 'diode', name: 'led-g', parameters: { forward_voltage: '2.1 V', forward_current: '20 mA' } } }] }],
  ['circuit_import', { circuit: { elements: [], models: [{ kind: 'diode', name: 'm', modelText: '34 m 0 1e-14 0 1 0 0' }] } }, 'importCircuit',
    { circuit: { elements: [], models: [{ kind: 'diode', name: 'm', modelText: '34 m 0 1e-14 0 1 0 0' }] } }],
  ['circuit_get', { detail: 'full', ids: ['R1'], offset: 2, limit: 9 }, 'getCircuit', { detail: 'full', ids: ['R1'], offset: 2, limit: 9 }],
  ['circuit_connectivity', { includeNets: false, netFilter: ['out'] }, 'getConnectivity', { includeNets: false, netFilter: ['out'] }],
  ['circuit_read', { targets: [{ net: 'out' }] }, 'read', { targets: [{ net: 'out' }] }],
  ['circuit_render', { format: 'svg', scale: 2, includeScopes: true }, 'render', { format: 'svg', scale: 2, includeScopes: true }],
  ['circuit_layout', { includeBoxes: true, doc: 'd2' }, 'checkLayout', { doc: 'd2', includeBoxes: true }],
  ['circuit_layout', {}, 'checkLayout', {}],
  ['circuit_sim', { action: 'reset' }, 'simControl', { action: 'reset' }],
  ['circuit_sim', { action: 'configure', settings: { maxTimeStep: '1 us' } }, 'simControl', { action: 'configure', settings: { maxTimeStep: '1 us' } }],
  ['circuit_run', { span: '5 ms', reset: true, probes: [{ net: 'a' }], maxPoints: 50, budgetMs: 200, recordFrom: 0.001 }, 'run',
    { span: '5 ms', reset: true, probes: [{ net: 'a' }], maxPoints: 50, budgetMs: 200, recordFrom: 0.001 }],
  ['circuit_run', { mode: 'settle', settle: { tolerance: 0.001 } }, 'run', { mode: 'settle', settle: { tolerance: 0.001 } }],
  ['circuit_diagnostics', { log: { since: 4, limit: 2 } }, 'getDiagnostics', { log: { since: 4, limit: 2 } }],
  ['circuit_checkpoint', { comment: 'c' }, 'checkpoint', { comment: 'c' }],
  ['circuit_history', { action: 'list', limit: 5 }, 'getHistory', { limit: 5 }],
  ['circuit_history', { action: 'undo', steps: 2 }, 'undo', { steps: 2 }],
  ['circuit_history', { action: 'redo' }, 'redo', {}],
  ['circuit_history', { action: 'restore', checkpointId: 'cp1' }, 'restoreCheckpoint', { checkpointId: 'cp1' }],
  ['circuit_file', { action: 'open', path: '/a.txt', into: 'new', activate: true }, 'openFile', { path: '/a.txt', into: 'new', activate: true }],
  ['circuit_file', { action: 'save', path: '/a.json', format: 'json', doc: 'd1' }, 'saveFile', { doc: 'd1', path: '/a.json', format: 'json' }],
  ['circuit_file', { action: 'export', format: 'text' }, 'exportCircuit', { format: 'text' }],
];

test('every tool maps to its SP_AGA contract with the SP_AGA argument names', async () => {
  const seen = new Set();
  for (const [tool, args, op, expected] of MAPPING) {
    const agent = fakeAgent(() => ok({}));
    const r = await createTools(agent).call(tool, args);
    assert.equal(agent.calls.length, 1, tool);
    assert.equal(agent.calls[0].op, op, tool);
    assert.deepEqual(agent.calls[0].args, expected, `${tool} ${JSON.stringify(args)}`);
    assert.deepEqual(r.structuredContent, ok({}));
    seen.add(tool);
  }
  assert.equal(seen.size, 15);
});

// ------------------------------------------------------------------ validation (SP_MCP_03_02)

test('schema violations are -32602 naming the field', async () => {
  const tools = createTools(fakeAgent(() => ok({})));
  await rejects(tools.call('circuit_edit', { edits: { op: 'add' } }), -32602, /edits must be array/);
  await rejects(tools.call('circuit_edit', {}), -32602, /edits is required/);
  await rejects(tools.call('circuit_layout', { includeBoxes: 'yes' }), -32602, /includeBoxes must be boolean/);
  await rejects(tools.call('circuit_layout', { boxes: true }), -32602, /boxes/);
  await rejects(tools.call('circuit_edit', { edits: [{ op: 'ad' }] }), -32602, /edits\[0\]\.op must be one of add, move/);
  await rejects(tools.call('circuit_edit', { edits: [{ op: 'add', element: { type: 'Resistor' } }] }), -32602, /edits\[0\]\.element\.start is required/);
  await rejects(tools.call('circuit_edit', { edits: [{ op: 'set', id: 'R1', properties: { r: null } }] }), -32602, /edits\[0\]\.properties\.r must be number or string or boolean/);
  await rejects(tools.call('circuit_get', { detail: 'all' }), -32602, /detail must be one of concise, full/);
  await rejects(tools.call('circuit_get', { limit: 2.5 }), -32602, /limit must be integer/);
  await rejects(tools.call('circuit_get', { bogus: 1 }), -32602, /bogus is not an argument/);
  await rejects(tools.call('circuit_documents', { action: 'close' }), -32602, /doc is required for action "close"/);
  await rejects(tools.call('circuit_documents', { action: 'list', steps: 1 }), -32602, /steps is not an argument/);
  await rejects(tools.call('circuit_history', { action: 'list', steps: 1 }), -32602, /steps does not apply to action "list" \(used by: undo, redo\)/);
  await rejects(tools.call('circuit_history', { action: 'restore' }), -32602, /checkpointId is required/);
  await rejects(tools.call('circuit_file', { action: 'open' }), -32602, /path is required/);
  await rejects(tools.call('circuit_sim', { action: 'configure' }), -32602, /settings is required/);
  await rejects(tools.call('circuit_types', { type: 'R', filter: 'x' }), -32602, /filter does not apply/);
  // [SP_MCP_02_02] inapplicable circuit_types combinations name the argument
  await rejects(tools.call('circuit_types', { type: 'Resistor', models: 'diode' }), -32602, /circuit_types: type does not apply when `models` is given/);
  await rejects(tools.call('circuit_types', { model: 'x' }), -32602, /circuit_types: model requires `models`/);
  await rejects(tools.call('circuit_types', { models: 'all', model: 'x' }), -32602, /circuit_types: model needs `models` of one kind/);
  await rejects(tools.call('circuit_types', { models: 'diode', filter: 'x' }), -32602, /circuit_types: filter does not apply when `models` is given/);
  await rejects(tools.call('circuit_types', { models: 'mosfet' }), -32602, /models must be one of diode, transistor, logic, subcircuit, all/);
  await rejects(tools.call('circuit_edit', { edits: [{ op: 'defineModel' }] }), -32602, /edits\[0\]\.model is required/);
  await rejects(tools.call('circuit_edit', { edits: [{ op: 'defineModel', model: { name: 'x' } }] }), -32602, /edits\[0\]\.model\.kind is required/);
  await rejects(tools.call('circuit_edit', { edits: [{ op: 'defineModel', model: { kind: 'bjt', name: 'x' } }] }), -32602, /edits\[0\]\.model\.kind must be one of diode/);
  await rejects(tools.call('circuit_import', { circuit: { elements: [], models: {} } }), -32602, /circuit\.models must be array/);
  await rejects(tools.call('circuit_import', { circuit: 5 }), -32602, /circuit must be object or string/);
  await rejects(tools.call('circuit_nope', {}), -32602, /Unknown tool/);
});

test('ranges and formats are left to the Agent API (isError results)', async () => {
  const agent = fakeAgent(() => ({ ok: false, issues: [{ code: 'invalid_value', severity: 'error', message: 'm', hint: 'h', key: 'k' }], truncatedIssues: 0 }));
  const tools = createTools(agent);
  const r = await tools.call('circuit_run', { span: '5 ms', budgetMs: 5 });
  assert.equal(agent.calls.length, 1);
  assert.equal(r.isError, true);
  assert.equal(r.structuredContent.issues[0].code, 'invalid_value');
  await tools.call('circuit_edit', { edits: [{ op: 'move', id: 'R1', by: { dx: 0.3, dy: 0 } }] });
  assert.equal(agent.calls.length, 2);
  // span/settle per mode are the Agent API's rule; settle values may be unit strings like span
  await tools.call('circuit_run', {});
  await tools.call('circuit_run', { mode: 'settle', settle: { tolerance: '0.1 mV', window: '5 ms', maxSpan: '1 s' } });
  assert.equal(agent.calls.length, 4);
  assert.deepEqual(agent.calls[3].args, { mode: 'settle', settle: { tolerance: '0.1 mV', window: '5 ms', maxSpan: '1 s' } });
});

// ------------------------------------------------------------------ shaping (SP_MCP_01_04, SP_MCP_03_04)

test('isError exactly when ok = false; text is the compact OperationResult', async () => {
  for (const okv of [true, false]) {
    const res = { ok: okv, data: { a: 1 }, issues: okv ? [] : [{ code: 'busy', severity: 'error', message: 'm', hint: 'h', key: 'k' }], truncatedIssues: 0 };
    const r = await createTools(fakeAgent(() => res)).call('circuit_checkpoint', { comment: 'x' });
    assert.equal(r.isError, !okv);
    assert.deepEqual(r.structuredContent, res);
    assert.equal(r.content.length, 1);
    assert.equal(r.content[0].text, JSON.stringify(res));
  }
});

test('circuit_render png: image part, "<image>" in structuredContent and text', async () => {
  const png = Buffer.from('fakepng').toString('base64');
  const r = await createTools(fakeAgent(() => ok({ format: 'png', width: 10, height: 12, content: png }))).call('circuit_render', {});
  assert.equal(r.isError, false);
  assert.deepEqual(r.content[1], { type: 'image', data: png, mimeType: 'image/png' });
  assert.equal(r.structuredContent.data.content, '<image>');
  assert.match(r.content[0].text, /"content":"<image>"/);
  assert.equal(r.structuredContent.data.width, 10);
});

test('circuit_render svg over the limit: result_too_large', async () => {
  const r = await createTools(fakeAgent(() => ok({ format: 'svg', width: 9, height: 9, content: 'x'.repeat(LIMIT) }))).call('circuit_render', { format: 'svg' });
  assert.equal(r.isError, true);
  assert.equal(r.structuredContent.issues[0].code, 'result_too_large');
  assert.match(r.structuredContent.issues[0].hint, /use format png or a lower scale/i);
  assert.equal(r.content.length, 1);
  assert.ok(r.content[0].text.length <= LIMIT);
});

test('circuit_file export over the limit: result_too_large; save is not affected', async () => {
  const tools = createTools(fakeAgent(() => ok({ content: 'y'.repeat(LIMIT + 10) })));
  const r = await tools.call('circuit_file', { action: 'export' });
  assert.equal(r.isError, true);
  assert.equal(r.structuredContent.issues[0].code, 'result_too_large');
  assert.match(r.structuredContent.issues[0].hint, /use action save, or circuit_get pages/i);
});

/** A fake getCircuit whose size follows detail and limit (about 300 chars/element full, 120 concise). */
function bigCircuit(total) {
  return (op, args) => {
    const limit = args.limit || 200;
    const offset = args.offset || 0;
    const n = Math.min(limit, total - offset);
    const per = args.detail === 'full' ? 300 : 120;
    const elements = Array.from({ length: n }, (_, i) => ({ id: 'R' + (offset + i + 1), type: 'Resistor', pad: 'p'.repeat(per) }));
    const data = { elements, total, simulation: {}, scopes: [] };
    if (offset + n < total) data.nextOffset = offset + n;
    return ok(data);
  };
}

test('circuit_get oversized: concise, then halved limit; note, nextOffset, effective result', async () => {
  const agent = fakeAgent(bigCircuit(500));
  const r = await createTools(agent).call('circuit_get', { detail: 'full', limit: 500 });
  assert.ok(r.content[0].text.length <= LIMIT, String(r.content[0].text.length));
  assert.equal(r.isError, false);
  const effective = agent.calls[agent.calls.length - 1];
  assert.equal(effective.args.detail, 'concise');
  assert.ok(effective.args.limit < 500);
  assert.equal(r.structuredContent.data.elements.length, effective.args.limit);
  assert.equal(r.structuredContent.data.nextOffset, effective.args.limit);
  const note = r.content[0].text.split('\n')[0];
  assert.match(note, /detail="concise" instead of "full"/);
  assert.match(note, new RegExp(`limit=${effective.args.limit} instead of 500`));
  assert.match(note, /continue at offset/);
  // structuredContent equals the OperationResult of the effective call
  assert.deepEqual(r.structuredContent, bigCircuit(500)('getCircuit', effective.args));
  assert.equal(JSON.parse(r.content[0].text.slice(note.length + 1)).data.elements.length, effective.args.limit);
});

test('circuit_connectivity oversized: includeNets false', async () => {
  const agent = fakeAgent((op, args) => ok({ nets: args.includeNets === false ? [] : [{ posts: ['x'.repeat(LIMIT)] }], issues: [], truncated: false }));
  const r = await createTools(agent).call('circuit_connectivity', {});
  assert.deepEqual(agent.calls.map((c) => c.args.includeNets), [undefined, false]);
  assert.match(r.content[0].text, /^\[result reduced .*includeNets=false instead of \(default\)/);
  assert.deepEqual(r.structuredContent.data.nets, []);
});

test('circuit_layout oversized: includeBoxes false, issues complete', async () => {
  const issue = { code: 'text_overlap', severity: 'warning', message: 'm', elements: ['C1', 'W1'], at: { x: 1, y: 2 }, hint: 'h', key: 'text_overlap|C1,W1||1,2' };
  const box = { element: 'R1', text: '1k', box: { x1: 0, y1: 0, x2: 1, y2: 1 }, anchor: { x: 0, y: 1 }, align: 'left', baseline: 'alphabetic', font: 'normal 12px sans-serif', live: false };
  const agent = fakeAgent((op, args) => ok(Object.assign({ issues: [issue], texts: 2000, truncated: false },
    args.includeBoxes ? { boxes: Array.from({ length: 2000 }, () => box) } : {})));
  const r = await createTools(agent).call('circuit_layout', { includeBoxes: true });
  assert.deepEqual(agent.calls.map((c) => c.args.includeBoxes), [true, false]);
  const note = r.content[0].text.split('\n')[0];
  assert.match(note, /^\[result reduced .*includeBoxes=false instead of true.*boxes were left out/);
  assert.ok(r.content[0].text.length <= LIMIT);
  assert.equal(r.isError, false);
  assert.deepEqual(r.structuredContent.data.issues, [issue]);
  assert.equal(r.structuredContent.data.boxes, undefined);
  // a small result is not re-read
  const small = fakeAgent(() => ok({ issues: [], texts: 1, truncated: false, boxes: [box] }));
  await createTools(small).call('circuit_layout', { includeBoxes: true });
  assert.equal(small.calls.length, 1);
});

test('circuit_diagnostics oversized: log limit halved', async () => {
  const agent = fakeAgent((op, args) => {
    const n = args.log ? args.log.limit || 50 : 0;
    return ok({ log: { entries: Array.from({ length: n }, (_, i) => ({ seq: i + 1, text: 't'.repeat(500) })), cursor: n, gap: false } });
  });
  const r = await createTools(agent).call('circuit_diagnostics', { log: { limit: 500 } });
  assert.ok(r.content[0].text.length <= LIMIT);
  const last = agent.calls[agent.calls.length - 1].args.log.limit;
  assert.ok(last < 500 && last >= 62, String(last));
  assert.match(r.content[0].text, /^\[result reduced .*log=\{"limit":\d+\} instead of \{"limit":500\}/);
});

test('a run or mutation over the limit is not re-executed; only the text part is shortened', async () => {
  const big = ok({ applied: 1, elements: Array.from({ length: 50 }, (_, i) => ({ id: 'R' + i, description: 'd'.repeat(1500) })) });
  const agent = fakeAgent(() => big);
  const r = await createTools(agent).call('circuit_edit', { edits: [{ op: 'delete', id: 'R1' }] });
  assert.equal(agent.calls.length, 1);
  assert.ok(r.content[0].text.length <= LIMIT);
  assert.match(r.content[0].text, /data\.elements \(50 items\)/);
  assert.deepEqual(r.structuredContent, big);
  // the shortened text still parses
  JSON.parse(r.content[0].text.slice(r.content[0].text.indexOf('\n') + 1));
});

test('an exception in a tool: internal_error result and the global handler', async () => {
  const agent = fakeAgent(() => {
    throw new Error('boom');
  });
  const r = await createTools(agent).call('circuit_types', {});
  assert.equal(r.isError, true);
  assert.equal(r.structuredContent.issues[0].code, 'internal_error');
  assert.match(r.structuredContent.issues[0].message, /boom/);
  assert.equal(agent.errors.length, 1);
});

test('agent client: undefined or unparseable returns become internal_error', async () => {
  const c1 = createAgentClient({ callAsync: (op, a, cb) => cb(undefined) });
  assert.equal((await c1.call('listTypes')).issues[0].code, 'internal_error');
  const c2 = createAgentClient({ callAsync: (op, a, cb) => cb('{not json') });
  assert.equal((await c2.call('listTypes')).issues[0].code, 'internal_error');
  const c3 = createAgentClient({ callAsync: () => {} });
  assert.equal((await c3.call('getCircuit')).issues[0].code, 'internal_error');
});

test('timeouts: a 120 s run always answers before the backstop', () => {
  assert.ok(ASYNC_TIMEOUT_MS >= 120000 + 60000, String(ASYNC_TIMEOUT_MS));
  assert.ok(PENDING_TIMEOUT_MS > ASYNC_TIMEOUT_MS + 10000, String(PENDING_TIMEOUT_MS));
});

// ------------------------------------------------------------------ resources (SP_MCP_02_03)

const SETUP = fs.readFileSync(path.join(ROOT, 'src/main/java/com/lushprojects/circuitjs1/public/setuplist.txt'), 'utf8');

function packageFetch(rel) {
  if (rel === 'circuitjs1/setuplist.txt') return Promise.resolve(SETUP);
  const m = /^circuitjs1\/circuits\/(.+)$/.exec(rel);
  if (m && m[1] === 'ohms.txt') return Promise.resolve('$ 1 0.000005 10.20027730826997 50 5 50\n');
  return Promise.reject(new Error('HTTP 404'));
}

test('resources/list and templates', () => {
  const res = createResources({ agent: fakeAgent(() => ok({})), fetchText: packageFetch });
  assert.deepEqual(res.list().map((r) => r.uri), ['circuitjs://catalogue', 'circuitjs://documents', 'circuitjs://examples', 'circuitjs://docs/agent-format']);
  assert.deepEqual(res.templates().map((t) => t.uriTemplate),
    ['circuitjs://catalogue/{type}', 'circuitjs://documents/{doc}/circuit', 'circuitjs://examples/{path}']);
});

test('setuplist parsing: menus, the > default marker, comments', () => {
  const idx = parseSetupList(SETUP);
  assert.ok(idx.length > 300, String(idx.length));
  const ohms = idx.find((e) => e.path === 'ohms.txt');
  assert.deepEqual(ohms, { path: 'ohms.txt', title: "Ohm's Law", menu: 'Basics' });
  assert.ok(idx.find((e) => e.path === 'lrc.txt'), 'the > marked default example');
  assert.ok(idx.every((e) => /\.txt$/.test(e.path) && !e.path.startsWith('>')));
});

test('resource reads: catalogue, type, documents, examples, agent-format, unknown', async () => {
  const agent = fakeAgent((op, args) => {
    if (op === 'describeType' && args.type !== 'Resistor') {
      return { ok: false, issues: [{ code: 'unknown_type', severity: 'error', message: 'no such type', hint: 'h', key: 'k' }], truncatedIssues: 0 };
    }
    return ok({ op, type: args.type });
  });
  const res = createResources({ agent, fetchText: packageFetch });
  const cat = await res.read('circuitjs://catalogue');
  assert.equal(cat.contents[0].mimeType, 'application/json');
  assert.deepEqual(JSON.parse(cat.contents[0].text), { op: 'listTypes' });
  assert.deepEqual(JSON.parse((await res.read('circuitjs://catalogue/Resistor')).contents[0].text), { op: 'describeType', type: 'Resistor' });
  assert.deepEqual(JSON.parse((await res.read('circuitjs://documents')).contents[0].text), { op: 'listDocuments' });
  const ex = JSON.parse((await res.read('circuitjs://examples')).contents[0].text);
  assert.ok(ex.length > 300 && ex[0].path && ex[0].title);
  const one = await res.read('circuitjs://examples/ohms.txt');
  assert.equal(one.contents[0].mimeType, 'text/plain');
  assert.match(one.contents[0].text, /^\$ 1/);
  const doc = await res.read('circuitjs://docs/agent-format');
  assert.equal(doc.contents[0].mimeType, 'text/markdown');
  assert.equal(doc.contents[0].text, AGENT_FORMAT);
  for (const bad of ['circuitjs://nope', 'http://x', 'circuitjs://catalogue/Resistr', 'circuitjs://examples/../package.json',
    'circuitjs://examples/nope.txt', 'circuitjs://documents/x/circuit', 'circuitjs://documents/d1/other']) {
    await rejects(res.read(bad), RESOURCE_NOT_FOUND);
  }
});

test('error replies bound echoed client and Agent API text (1 MB URI)', async () => {
  const huge = 'X'.repeat(1024 * 1024);
  const agent = fakeAgent((op, args) => ({ ok: false, issues: [{ code: 'unknown_type', severity: 'error', message: `No type '${args.type}'.`, hint: 'h', key: 'k' }], truncatedIssues: 0 }));
  const res = createResources({ agent, fetchText: packageFetch });
  for (const uri of ['circuitjs://catalogue/' + huge, 'circuitjs://' + huge, 'circuitjs://examples/' + huge, 'circuitjs://documents/' + huge + '/circuit']) {
    await assert.rejects(res.read(uri), (e) => {
      assert.equal(e.code, RESOURCE_NOT_FOUND);
      assert.ok(e.message.length < 700, String(e.message.length));
      return true;
    });
  }
  const other = createResources({ agent: fakeAgent(() => ({ ok: false, issues: [{ code: 'not_ready', severity: 'error', message: huge }], truncatedIssues: 0 })), fetchText: packageFetch });
  await assert.rejects(other.read('circuitjs://catalogue/' + huge), (e) => e.code === -32603 && e.message.length < 700);
  const broken = createResources({ agent: fakeAgent(() => ok({})), fetchText: () => Promise.reject(new Error(huge)) });
  await assert.rejects(broken.read('circuitjs://examples'), (e) => e.code === -32603 && e.message.length < 700);
  const t = await createTools(fakeAgent(() => { throw new Error(huge); })).call('circuit_types', {});
  assert.ok(t.content[0].text.length < 1500, String(t.content[0].text.length));
  await assert.rejects(createTools(fakeAgent(() => ok({}))).call(huge, {}), (e) => e.code === -32602 && e.message.length < 200);
  await assert.rejects(createTools(fakeAgent(() => ok({}))).call('circuit_get', { [huge]: 1 }), (e) => e.code === -32602 && e.message.length < 400);
  await assert.rejects(createTools(fakeAgent(() => ok({}))).call('circuit_get', { detail: huge }), (e) => e.code === -32602 && e.message.length < 400);
});

test('document circuit resource: full detail, all pages, importable shape', async () => {
  const agent = fakeAgent((op, args) => {
    if (args.doc !== 'd1') return { ok: false, issues: [{ code: 'unknown_document', severity: 'error', message: 'm', hint: 'h', key: 'k' }], truncatedIssues: 0 };
    const r = bigCircuit(1203)(op, args);
    r.data.simulation = { time_step: '5 us' };
    r.data.scopes = [{ element: 'R1', quantity: 'voltage' }];
    return r;
  });
  const res = createResources({ agent, fetchText: packageFetch });
  const c = JSON.parse((await res.read('circuitjs://documents/d1/circuit')).contents[0].text);
  assert.equal(c.elements.length, 1203);
  assert.deepEqual(Object.keys(c), ['elements', 'simulation', 'scopes']);
  assert.deepEqual(agent.calls.map((x) => [x.args.detail, x.args.offset, x.args.limit]), [['full', 0, 500], ['full', 500, 500], ['full', 1000, 500]]);
  await rejects(res.read('circuitjs://documents/d7/circuit'), RESOURCE_NOT_FOUND);
  // [SP_AGA_02_05] models (offset-0 page) and modelsTruncated are carried when present
  const withModels = fakeAgent((op, args) => {
    const r = bigCircuit(3)(op, args);
    if (!args.offset) {
      r.data.models = [{ kind: 'diode', name: 'm', parameters: { forward_voltage: '2 V', forward_current: '20 mA' } }];
      r.data.modelsTruncated = 4;
    }
    return r;
  });
  const cm = JSON.parse((await createResources({ agent: withModels, fetchText: packageFetch }).read('circuitjs://documents/d1/circuit')).contents[0].text);
  assert.deepEqual(Object.keys(cm), ['elements', 'simulation', 'scopes', 'models', 'modelsTruncated']);
  assert.equal(cm.models[0].name, 'm');
  assert.equal(cm.modelsTruncated, 4);
});

test('agent-format text names the current SP_AGA rules', () => {
  for (const s of ['grid cells', '0.5', '1/16', 'stop_trigger', 'First sample', 'Determinism', '40 megapixels', '16384',
    'file_not_allowed', '10 MB', 'result_too_large', 'markOpen', 'value_adjusted', 'post_on_wire_body', 'ground_path_no_resistance', 'current_source_no_path', 'symbol_overlap', 'toolsVersion 1.2', 'text_overlap', 'text_not_covered', 'includeBoxes',
    'defineModel', 'name_taken', 'unknown_model', 'modelText', 'Create-only', 'forward_voltage', '"inf"', '"models": "diode"']) {
    assert.ok(AGENT_FORMAT.includes(s), s);
  }
  for (const t of TOOLS) {
    assert.ok(AGENT_FORMAT.includes(t.name), t.name);
  }
});

// ------------------------------------------------------------------ through the protocol layer

test('tools/list, tools/call and resources through the SDK server', async () => {
  const agent = fakeAgent(() => ok({ types: [] }));
  const p = createProtocol({
    appVersion: 't',
    instructions: 'i',
    tools: createTools(agent),
    resources: createResources({ agent, fetchText: packageFetch }),
  });
  const send = (id, method, params) => p.dispatch({ jsonrpc: '2.0', id, method, params }, { headers: {} });
  const init = await send(1, 'initialize', { protocolVersion: '2025-11-25', capabilities: {}, clientInfo: { name: 'u', version: '1' } });
  assert.equal(init.result.protocolVersion, '2025-11-25');
  const list = await send(2, 'tools/list', {});
  assert.equal(list.result.tools.length, 15);
  const call = await send(3, 'tools/call', { name: 'circuit_types', arguments: {} });
  assert.equal(call.result.isError, false);
  assert.deepEqual(call.result.structuredContent, ok({ types: [] }));
  const bad = await send(4, 'tools/call', { name: 'circuit_edit', arguments: { edits: 'x' } });
  assert.equal(bad.error.code, -32602);
  assert.match(bad.error.message, /edits must be array/);
  // Error messages go out without the SDK's "MCP error <code>: " prefix.
  assert.doesNotMatch(bad.error.message, /^MCP error/);
  const unknown = await send(5, 'resources/read', { uri: 'circuitjs://nope' });
  assert.equal(unknown.error.code, RESOURCE_NOT_FOUND);
  assert.equal(unknown.error.message, 'Resource not found: circuitjs://nope');
  const unknownTool = await send(7, 'tools/call', { name: 'circuit_nope', arguments: {} });
  assert.equal(unknownTool.error.message, 'Unknown tool: circuit_nope');
  const templates = await send(6, 'resources/templates/list', {});
  assert.equal(templates.result.resourceTemplates.length, 3);
  await p.close();
});

test('the bundle contains the tool table and the agent-format text', { skip: !fs.existsSync(path.join(ROOT, 'war/scripts/mcp-server.js')) }, () => {
  const text = fs.readFileSync(path.join(ROOT, 'war/scripts/mcp-server.js'), 'utf8');
  assert.ok(text.includes('circuit_connectivity'));
  assert.ok(text.includes('circuit_layout'));
  assert.ok(text.includes('CircuitJS1 agent format (toolsVersion 1.2)'));
  assert.ok(!/require\(\s*["'](node:)?crypto["']\s*\)/.test(text));
});

test('registry cleanup touches only regular files named <pid>-<ms>.json[.tmp] whose content matches', (t) => {
  const os = require('node:os');
  const registry = require('../src/registry.js');
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'circuitjs-mcp-registry-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  const DEAD = 2 ** 22 + 12345; // above the Linux pid_max: never alive
  const rec = (pid, ms, over) => JSON.stringify(Object.assign({ instanceId: `${pid}-${ms}`, pid }, over));
  const write = (name, text) => fs.writeFileSync(path.join(dir, name), text);
  write(`${DEAD}-1.json`, rec(DEAD, 1)); // stale record: removed
  write(`${DEAD}-2.json.tmp`, '{'); // stale temp file: removed
  write(`${process.pid}-3.json`, rec(process.pid, 3)); // live: kept
  write('settings.json', rec(DEAD, 4)); // not a record name: kept
  write(`${DEAD}-5.json.bak`, rec(DEAD, 5));
  write(`${process.pid}-6.json`, rec(DEAD, 6, { instanceId: `${process.pid}-6` })); // content pid differs
  write(`${DEAD}-7.json`, rec(DEAD, 8)); // instanceId differs from the name
  write(`${DEAD}-9.json`, '{broken');
  const outside = path.join(dir, 'outside.txt');
  write('outside.txt', rec(DEAD, 10));
  fs.symlinkSync(outside, path.join(dir, `${DEAD}-10.json`)); // symlink: kept
  fs.mkdirSync(path.join(dir, `${DEAD}-11.json`)); // directory: kept
  const removed = registry.removeDeadRecords(dir).sort();
  assert.deepEqual(removed, [`${DEAD}-1.json`, `${DEAD}-2.json.tmp`]);
  assert.deepEqual(fs.readdirSync(dir).sort(), [
    `${DEAD}-10.json`, `${DEAD}-11.json`, `${DEAD}-5.json.bak`, `${DEAD}-7.json`, `${DEAD}-9.json`,
    `${process.pid}-3.json`, `${process.pid}-6.json`, 'outside.txt', 'settings.json',
  ].sort());
  assert.equal(registry.parseRecordName('12-34.json.tmp').temp, true);
  assert.equal(registry.parseRecordName('12-34.json').stem, '12-34');
  assert.equal(registry.parseRecordName('12-34x.json'), null);
});
