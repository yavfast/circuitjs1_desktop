'use strict';
// [SP_MCP_02_02] The tool catalogue: 14 `circuit_*` tools grouped over the SP_AGA contracts
// (SP_MCP_DEC_01). Each tool validates its arguments (SP_MCP_03_02), maps them to one Agent API
// contract with the SP_AGA argument names unchanged, and shapes the OperationResult
// (SP_MCP_01_04, SP_MCP_03_04). Tools add no circuit logic: the structuredContent of a result is
// the OperationResult of the effective Agent API call (SP_MCP_05_02), apart from the PNG content
// of circuit_render and the server's own result_too_large / internal_error rejections.

const S = require('./schemas.js');
const { validate } = require('./validate.js');
const shaping = require('./shaping.js');
const { McpError, ErrorCode } = require('@modelcontextprotocol/sdk/types.js');

const LIMIT = shaping.TEXT_LIMIT;

// [SP_MCP_02_02] annotations: the most conservative values over the tool's actions
function hints(readOnly, destructive, idempotent, title) {
  return { title, readOnlyHint: readOnly, destructiveHint: destructive, idempotentHint: idempotent, openWorldHint: false };
}

function inputSchema(properties, required) {
  const schema = {
    type: 'object',
    properties: Object.assign({ doc: S.DOC }, properties),
    additionalProperties: false,
  };
  if (required && required.length) schema.required = required;
  return schema;
}

function invalidParams(tool, field, problem) {
  return new McpError(ErrorCode.InvalidParams, `Invalid arguments for tool ${tool}: ${field} ${problem}`);
}

/** Copies the named arguments (and `doc`) that are present into the Agent API arguments. */
function pick(args, keys) {
  const out = {};
  for (const k of ['doc'].concat(keys)) {
    if (args[k] !== undefined) out[k] = args[k];
  }
  return out;
}

/**
 * Routes an `action` tool: rejects arguments that the action does not take and missing
 * action-specific required arguments (both -32602, naming the field), then returns the
 * contract call. `routes`: action -> {op, keys, required?}.
 */
function route(tool, args, routes, actionKey) {
  const key = actionKey || 'action';
  const action = args[key];
  const r = routes[action];
  const allowed = new Set(['doc', key].concat(r.keys));
  for (const k of Object.keys(args)) {
    if (!allowed.has(k)) {
      const takers = Object.keys(routes).filter((a) => routes[a].keys.includes(k));
      throw invalidParams(tool, k, `does not apply to ${key} "${action}"` + (takers.length ? ` (used by: ${takers.join(', ')})` : ''));
    }
  }
  for (const k of r.required || []) {
    if (args[k] === undefined) throw invalidParams(tool, k, `is required for ${key} "${action}"`);
  }
  return { op: r.op, args: pick(args, r.keys) };
}

/** Names the changed arguments of a reduced (re-executed) call, for the note of SP_MCP_03_04. */
function reductionNote(requested, effective, extra) {
  const show = (v) => (v === undefined ? '(default)' : JSON.stringify(v));
  const parts = [];
  for (const k of Object.keys(effective)) {
    if (JSON.stringify(effective[k]) !== JSON.stringify(requested[k])) {
      parts.push(`${k}=${JSON.stringify(effective[k])} instead of ${show(requested[k])}`);
    }
  }
  return `[result reduced to fit the ${LIMIT}-character limit: called with ${parts.join(', ')}${extra ? '; ' + extra : ''}]`;
}

// ------------------------------------------------------------------------ the catalogue

const TOOLS = [
  {
    name: 'circuit_types',
    title: 'Element catalogue',
    description:
      'List the element types of the catalogue, or describe one type, or list the session models. Without ' +
      '`type`: the index {type, aliases, pins, geometry, summary} of every type, narrowed by `filter` ' +
      '(substring of name, alias or summary). With `type` (a name or alias): its TypeInfo - pins in post ' +
      'order, geometry (single | two_point | derived), defaultSize and derivedPostsAtDefault in grid cells ' +
      '(1 cell = 16 px), property keys with kind, default and unit (model keys: choices), defaultFlags, ' +
      'quantities. With `models` (diode | transistor | logic | subcircuit | all): the model records ' +
      '{kind, name, builtIn, parameters, usedBy}; `model` picks one name of one kind. Use it before ' +
      'circuit_import or circuit_edit to learn pin names, property keys and model names. The catalogue is ' +
      'the same for all documents: a given `doc` must be open but does not change the answer. Example: ' +
      '{"type": "Resistor"} or {"models": "diode", "model": "1N4148"}.',
    inputSchema: inputSchema({
      type: { type: 'string', description: 'Type name or alias to describe; absent: list the index.' },
      filter: { type: 'string', description: 'Index filter (substring), only without `type` and `models`.' },
      models: { type: 'string', enum: ['diode', 'transistor', 'logic', 'subcircuit', 'all'], description: 'List the session models of a kind (all: every kind).' },
      model: { type: 'string', description: 'With `models` of one kind: that one model.' },
    }),
    outputSchema: S.operationResult({
      types: Object.assign({}, S.ARRAY_OF_OBJECTS, { description: 'Index entries {type, aliases, pins, geometry, summary}.' }),
      type: S.STRING, aliases: { type: 'array', items: S.STRING }, dumpCode: S.STRING, idPrefix: S.STRING,
      geometry: S.STRING, pins: { type: 'array', items: S.STRING }, defaultSize: S.OBJECT,
      derivedPostsAtDefault: S.OBJECT, properties: S.ARRAY_OF_OBJECTS, defaultFlags: S.INT,
      quantities: { type: 'array', items: S.STRING },
      models: Object.assign({}, S.ARRAY_OF_OBJECTS, { description: 'ModelRecords {kind, name, builtIn, parameters?, usedBy}.' }),
    }, 'listTypes: {types}; describeType: TypeInfo; listModels: {models}.'),
    annotations: hints(true, false, true, 'Element catalogue'),
    map(args, name) {
      // [SP_MCP_02_02] models/model → listModels; inapplicable combinations are -32602
      if (args.model !== undefined && args.models === undefined) throw invalidParams(name, 'model', 'requires `models` with one kind');
      if (args.models !== undefined) {
        if (args.type !== undefined) throw invalidParams(name, 'type', 'does not apply when `models` is given');
        if (args.filter !== undefined) throw invalidParams(name, 'filter', 'does not apply when `models` is given');
        if (args.models === 'all' && args.model !== undefined) throw invalidParams(name, 'model', 'needs `models` of one kind, not "all"');
        const out = pick(args, []);
        if (args.models !== 'all') out.kind = args.models;
        if (args.model !== undefined) out.name = args.model;
        return { op: 'listModels', args: out };
      }
      if (args.type !== undefined) {
        if (args.filter !== undefined) throw invalidParams(name, 'filter', 'does not apply when `type` is given');
        return { op: 'describeType', args: pick(args, ['type']) };
      }
      return { op: 'listTypes', args: pick(args, ['filter']) };
    },
  },
  {
    name: 'circuit_documents',
    title: 'Documents (tabs)',
    description:
      'Manage the open documents (tabs). action list: every document {doc, title, active, modified, ' +
      'running, busy, elementCount, filePath}. create: a new empty document, in the background unless ' +
      'activate: true; returns {doc}; optional title. activate: show document `doc` (required). close: ' +
      'close document `doc` (required); a modified document needs discardChanges: true; closing the last ' +
      'one returns a blank `replacement`. The other tools act on the active document when `doc` is ' +
      'absent; pass `doc` to work in a background document without disturbing the user\'s tab. ' +
      'Example: {"action": "create", "title": "filter test"}.',
    inputSchema: inputSchema({
      action: { type: 'string', enum: ['list', 'create', 'activate', 'close'] },
      title: { type: 'string', maxLength: 200, description: 'create: tab title until the document has a file name.' },
      activate: { type: 'boolean', description: 'create: show the new document (default false).' },
      discardChanges: { type: 'boolean', description: 'close: close a modified document, ending its run.' },
    }, ['action']),
    outputSchema: S.operationResult({
      documents: S.ARRAY_OF_OBJECTS, doc: S.STRING, replacement: S.STRING,
    }, 'list: {documents}; create/activate: {doc}; close: {doc, replacement?}.'),
    annotations: hints(false, true, false, 'Documents (tabs)'),
    map(args, name) {
      return route(name, args, {
        list: { op: 'listDocuments', keys: [] },
        create: { op: 'createDocument', keys: ['title', 'activate'] },
        activate: { op: 'activateDocument', keys: [], required: ['doc'] },
        close: { op: 'closeDocument', keys: ['discardChanges'], required: ['doc'] },
      });
    },
  },
  {
    name: 'circuit_import',
    title: 'Import a whole circuit',
    description:
      'Replace a document\'s whole circuit in one call (atomic: on any error nothing changes). `circuit` ' +
      'is an AgentCircuit object {elements: ElementSpec[], simulation?, scopes?, models?}, a JSON v2 circuit text ' +
      'or a legacy text circuit. ElementSpec: {id?, type, start: {x, y}, end?, properties?, flags?, ' +
      'description?}; coordinates are grid cells (1 cell = 16 px, x right, y down; author on the ' +
      'half-cell lattice). Records read with circuit_get or the circuitjs://documents/{doc}/circuit ' +
      'resource import unchanged (models: ModelSpec/ModelText, create-only names). The simulation is reset. ' +
      'Returns {elements, ids} and the connectivity ' +
      'delta: check connectivity.errorCount. Acts on the active document unless `doc` is given. Example: ' +
      '{"circuit": {"elements": [{"id": "R1", "type": "Resistor", "start": {"x": 0, "y": 0}, "end": ' +
      '{"x": 4, "y": 0}, "properties": {"resistance": "4.7k"}}]}}.',
    inputSchema: inputSchema({
      circuit: {
        type: ['object', 'string'],
        description: 'AgentCircuit object, or a JSON v2 or legacy text circuit passed as a string.',
        oneOf: [S.AGENT_CIRCUIT, { type: 'string', maxLength: 10 * 1024 * 1024 }],
      },
    }, ['circuit']),
    outputSchema: S.operationResult({
      elements: Object.assign({}, S.INT, { description: 'Number of elements imported.' }),
      ids: { type: 'array', items: S.STRING, description: 'Element IDs (when at most 200 elements).' },
    }, '{elements, ids?}'),
    annotations: hints(false, true, false, 'Import a whole circuit'),
    map(args) {
      return { op: 'importCircuit', args: pick(args, ['circuit']) };
    },
  },
  {
    name: 'circuit_edit',
    title: 'Edit the circuit',
    description:
      'Apply an ordered batch of 1..200 edits atomically (one invalid edit rejects the whole batch). ' +
      'Ops: add {element: ElementSpec}; move {id, start, end?} or {id, by: {dx, dy}}; delete {id}; set ' +
      '{id, properties, flags?} (a patch: other keys keep their value); describe {id, description}; ' +
      'addScope / removeScope {element, quantity?}; markOpen {posts: PostRef[], open?}; defineModel {model: ' +
      'ModelSpec} (a new session model, create-only: an identical existing one is reported existing). Coordinates are ' +
      'grid cells (1 cell = 16 px) on a half-cell lattice; later edits see earlier ones (an added id can ' +
      'be set in the same batch). Returns {applied, created, elements} (records of changed elements with ' +
      'post positions and nets) and the connectivity delta; values the element adjusts come back as ' +
      'value_adjusted warnings. Acts on the active document unless `doc` is given. Example: {"edits": ' +
      '[{"op": "add", "element": {"type": "Capacitor", "start": {"x": 4, "y": 0}, "end": {"x": 4, "y": 4}}}, ' +
      '{"op": "set", "id": "R1", "properties": {"resistance": "10k"}}]}.',
    inputSchema: inputSchema({
      edits: { type: 'array', items: S.EDIT, minItems: 1, maxItems: 200 },
    }, ['edits']),
    outputSchema: S.operationResult({
      applied: S.INT,
      created: { type: 'array', items: S.STRING },
      elements: { type: 'array', items: S.ELEMENT_RECORD, description: 'Records of created, moved or set elements (at most 50).' },
      truncated: S.INT,
      models: Object.assign({}, S.ARRAY_OF_OBJECTS, { description: 'One ModelRecord per defineModel (existing: true when identical).' }),
    }, '{applied, created, elements, truncated, models?}'),
    annotations: hints(false, true, false, 'Edit the circuit'),
    map(args) {
      return { op: 'applyEdits', args: pick(args, ['edits']) };
    },
  },
  {
    name: 'circuit_get',
    title: 'Read the circuit',
    description:
      'Read the circuit as element records {id, type, start, end, posts: [{pin, index, at, net, open}], ' +
      'properties, flags, description}; coordinates in grid cells (1 cell = 16 px). detail concise ' +
      '(default) omits default-valued properties and flags; full lists them all. Pages by offset/limit ' +
      '(default 200, max 500): continue at data.nextOffset. ids reads a subset. Also returns total, the ' +
      'simulation settings, the scope views and (offset 0 only) the models the circuit uses (ModelSpec or ' +
      'ModelText, importable with circuit_import). A result over the size limit is re-read with smaller ' +
      'arguments (concise, then a halved limit) and the text names the reduction. Acts on the active ' +
      'document unless `doc` is given. Example: {"detail": "full", "ids": ["R1", "C1"]}.',
    inputSchema: inputSchema({
      detail: { type: 'string', enum: ['concise', 'full'] },
      ids: { type: 'array', items: S.ELEMENT_ID, description: 'Subset of element IDs.' },
      offset: { type: 'integer', minimum: 0 },
      limit: { type: 'integer', minimum: 1, maximum: 500 },
    }),
    outputSchema: S.operationResult({
      elements: { type: 'array', items: S.ELEMENT_RECORD },
      total: S.INT,
      nextOffset: Object.assign({}, S.INT, { description: 'Present when more elements follow.' }),
      simulation: S.OBJECT,
      scopes: S.ARRAY_OF_OBJECTS,
      models: Object.assign({}, S.ARRAY_OF_OBJECTS, { description: 'Offset 0 only: the non-built-in models the circuit uses.' }),
      modelsTruncated: S.INT,
    }, '{elements, total, nextOffset?, simulation, scopes, models?, modelsTruncated?}'),
    annotations: hints(true, false, true, 'Read the circuit'),
    map(args) {
      return { op: 'getCircuit', args: pick(args, ['detail', 'ids', 'offset', 'limit']) };
    },
    // [SP_MCP_03_04] concise, then limit halved until it fits
    async reduce(result, call, ctx) {
      const requested = call.args;
      let effective = Object.assign({}, requested);
      let r = result;
      if ((effective.detail || 'concise') !== 'concise') {
        effective.detail = 'concise';
        r = await ctx.agent(call.op, effective);
      }
      let limit = Number.isInteger(effective.limit) ? effective.limit : 200;
      while (r.ok && !shaping.fits(r, ctx.noteSpace) && limit > 1) {
        limit = Math.max(1, Math.floor(limit / 2));
        effective = Object.assign({}, effective, { limit });
        r = await ctx.agent(call.op, effective);
      }
      const more = r.ok && r.data && r.data.nextOffset !== undefined ? `continue at offset ${r.data.nextOffset}` : '';
      return { result: r, note: reductionNote(requested, effective, more) };
    },
  },
  {
    name: 'circuit_connectivity',
    title: 'Connectivity report',
    description:
      'Full connectivity report: nets {name, posts, wires, labels} and issues (dangling_post, ' +
      'post_on_wire_body, isolated_group, no_ground, source_or_wire_loop, ...) each with a fix hint. ' +
      'Net names: gnd, a label text, label:<text>, or $<k> for unlabelled nets; posts are ' +
      '<ElementId>.<PinName>. includeNets: false returns the issues only; netFilter: [names] returns only ' +
      'those nets (issues stay complete). Check it after building and before running. A report over the ' +
      'size limit is re-read with includeNets: false. Acts on the active document unless `doc` is given. ' +
      'Example: {"netFilter": ["out", "gnd"]}.',
    inputSchema: inputSchema({
      includeNets: { type: 'boolean' },
      netFilter: { type: 'array', items: { type: 'string' }, minItems: 1, maxItems: 1000 },
    }),
    outputSchema: S.operationResult({
      nets: S.ARRAY_OF_OBJECTS,
      issues: { type: 'array', items: S.ISSUE },
      implicitGround: S.BOOL,
      analysed: S.BOOL,
      truncated: S.BOOL,
    }, 'ConnectivityReport {nets, issues, implicitGround, analysed, truncated}'),
    annotations: hints(true, false, true, 'Connectivity report'),
    map(args) {
      return { op: 'getConnectivity', args: pick(args, ['includeNets', 'netFilter']) };
    },
    // [SP_MCP_03_04] includeNets: false (issues only)
    async reduce(result, call, ctx) {
      if (call.args.includeNets === false) return null;
      const effective = Object.assign({}, call.args, { includeNets: false });
      const r = await ctx.agent(call.op, effective);
      return { result: r, note: reductionNote(call.args, effective, 'read nets with netFilter') };
    },
  },
  {
    name: 'circuit_read',
    title: 'Instant readings',
    description:
      'Instant readings at the current simulated time (no stepping). targets: 1..100 probes, each one of ' +
      '{net: name}, {post: "R1.pin2"} or {element: id, quantity: voltage | current | power}, with an ' +
      'optional name. Returns {t, values: [{name, value, unit}]}; an unknown net, post or element is an ' +
      'error, never 0 V. Use circuit_run to advance time and record waveforms. Acts on the active ' +
      'document unless `doc` is given. Example: {"targets": [{"net": "out"}, {"element": "R1", ' +
      '"quantity": "current"}]}.',
    inputSchema: inputSchema({
      targets: { type: 'array', items: S.PROBE_SPEC, minItems: 1, maxItems: 100 },
    }, ['targets']),
    outputSchema: S.operationResult({
      t: S.NUMBER,
      values: Object.assign({}, S.ARRAY_OF_OBJECTS, { description: '{name, value (null only when not finite), unit}' }),
    }, '{t, values}'),
    annotations: hints(true, false, true, 'Instant readings'),
    map(args) {
      return { op: 'read', args: pick(args, ['targets']) };
    },
  },
  {
    name: 'circuit_render',
    title: 'Render an image',
    description:
      'Draw the whole circuit of a document as an image, offscreen (the visible tab is not disturbed). ' +
      'format png (default; returned as an image part, the JSON says "<image>") or svg (text); scale ' +
      '0.25..4 (the image is at most 16384 px per side and 40 megapixels); includeScopes adds the scope ' +
      'panels. The image covers every element plus a 1-cell margin (grid cells of 16 px times scale), in ' +
      'printable colours. An SVG over the 60000-character limit is rejected with result_too_large: use ' +
      'png or a lower scale. Acts on the active document unless `doc` is given. Example: {"format": ' +
      '"png", "scale": 1}.',
    inputSchema: inputSchema({
      format: { type: 'string', enum: ['png', 'svg'] },
      scale: { type: 'number', minimum: 0.25, maximum: 4 },
      includeScopes: { type: 'boolean' },
    }),
    outputSchema: S.operationResult({
      format: S.STRING, width: S.INT, height: S.INT,
      content: Object.assign({}, S.STRING, { description: 'SVG text, or "<image>" for png (the PNG is the image part).' }),
    }, '{format, width, height, content}'),
    annotations: hints(true, false, true, 'Render an image'),
    map(args) {
      return { op: 'render', args: pick(args, ['format', 'scale', 'includeScopes']) };
    },
  },
  {
    name: 'circuit_sim',
    title: 'Simulation control',
    description:
      'Free-running control and time-step settings. action run / stop: the free-running flag (only the ' +
      'visible tab advances; a background document\'s flag takes effect when it is shown); reset: ' +
      'simulated time 0, initial element state, scope histories and the stop state cleared; configure: ' +
      'settings {maxTimeStep, minTimeStep, autoTimeStep}, steps in seconds or unit strings ("1 us"). ' +
      'Returns {running, simTime, timeStep}. For measurements use circuit_run, which steps the circuit ' +
      'itself. Acts on the active document unless `doc` is given. Example: {"action": "configure", ' +
      '"settings": {"maxTimeStep": "1 us"}}.',
    inputSchema: inputSchema({
      action: { type: 'string', enum: ['run', 'stop', 'reset', 'configure'] },
      settings: {
        type: 'object',
        description: 'configure: at least one setting.',
        properties: {
          maxTimeStep: { type: ['number', 'string'] },
          minTimeStep: { type: ['number', 'string'] },
          autoTimeStep: { type: 'boolean' },
        },
        additionalProperties: false,
      },
    }, ['action']),
    outputSchema: S.operationResult({ running: S.BOOL, simTime: S.NUMBER, timeStep: S.TIME_STEP }, '{running, simTime, timeStep}'),
    annotations: hints(false, true, false, 'Simulation control'),
    map(args, name) {
      const r = route(name, args, {
        run: { op: 'simControl', keys: [] },
        stop: { op: 'simControl', keys: [] },
        reset: { op: 'simControl', keys: [] },
        configure: { op: 'simControl', keys: ['settings'], required: ['settings'] },
      });
      r.args.action = args.action; // simControl takes the action itself
      return r;
    },
  },
  {
    name: 'circuit_run',
    title: 'Run and measure',
    description:
      'Advance simulated time under agent control (also in background documents) and record probes. ' +
      'mode span (default) needs span (seconds or "20 ms"); mode settle ends when every net voltage varies ' +
      'less than settle.tolerance over settle.window, at most settle.maxSpan. probes: up to 16 of {net}, ' +
      '{post} or {element, quantity}; each returns stats (min, max, mean, rms, final, frequency, ' +
      'dutyCycle, riseTime) and a series of at most maxPoints points (default 200, sum <= 2000). budgetMs ' +
      'bounds wall time (default 10000, max 120000). reset: true starts from initial conditions - pass it ' +
      'for repeatable runs; without it the run continues from the current state. reason: span_reached, ' +
      'settled, settle_timeout, solver_stop, stop_trigger, budget_exhausted or cancelled; solver problems ' +
      'are issues. Acts on the active document unless `doc` is given. Example: {"span": "5 ms", "reset": ' +
      'true, "probes": [{"net": "out"}]}.',
    inputSchema: inputSchema({
      mode: { type: 'string', enum: ['span', 'settle'] },
      span: { type: ['number', 'string'], description: 'span mode: simulated seconds or a unit string.' },
      settle: {
        type: 'object',
        properties: {
          tolerance: { type: ['number', 'string'], description: 'Volts or a unit string ("0.1 mV"), default 1e-4.' },
          window: { type: ['number', 'string'], description: 'Seconds or a unit string, default 50 max time steps.' },
          maxSpan: { type: ['number', 'string'], description: 'Seconds or a unit string ("5 ms"), default 1.' },
        },
        additionalProperties: false,
      },
      budgetMs: S.BUDGET,
      probes: { type: 'array', items: S.PROBE_SPEC, maxItems: 16 },
      recordFrom: { type: ['number', 'string'], description: 'Absolute simulated time from which probes record.' },
      maxPoints: { type: 'integer', minimum: 10, maximum: 2000, description: 'Series points per probe.' },
      reset: { type: 'boolean' },
    }),
    outputSchema: S.operationResult({
      reason: { type: 'string', enum: ['span_reached', 'settled', 'settle_timeout', 'solver_stop', 'stop_trigger', 'budget_exhausted', 'cancelled'] },
      tStart: S.NUMBER, tEnd: S.NUMBER, steps: S.INT, wallMs: S.INT,
      probes: Object.assign({}, S.ARRAY_OF_OBJECTS, { description: '{name, unit, stats, series: {t, v}}' }),
    }, '{reason, tStart, tEnd, steps, wallMs, probes}'),
    annotations: hints(false, true, false, 'Run and measure'),
    map(args) {
      // span/settle per mode are checked by the Agent API (invalid_value with a hint)
      return { op: 'run', args: pick(args, ['mode', 'span', 'settle', 'budgetMs', 'probes', 'recordFrom', 'maxPoints', 'reset']) };
    },
  },
  {
    name: 'circuit_diagnostics',
    title: 'Solver diagnostics and log',
    description:
      'Solver state and the session log: stopped, stop (the stop issue with its culprit element), ' +
      'warning, events (solver warnings and stops since the last analysis), recovering, lastImport ' +
      'issues, simTime, running and timeStep. log: {since, limit} adds the log entries with seq > since ' +
      '(limit default 50, max 500); pass the returned cursor as since next time. Use it when a run ends ' +
      'with solver_stop or values look wrong. Acts on the active document unless `doc` is given. ' +
      'Example: {"log": {"since": 0, "limit": 20}}.',
    inputSchema: inputSchema({
      log: {
        type: 'object',
        properties: { since: { type: 'integer', minimum: 0 }, limit: { type: 'integer', minimum: 1, maximum: 500 } },
        additionalProperties: false,
      },
    }),
    outputSchema: S.operationResult({
      stopped: S.BOOL, stop: S.ISSUE, warning: S.ISSUE, events: { type: 'array', items: S.ISSUE },
      recovering: S.BOOL, lastImport: { type: 'array', items: S.ISSUE }, simTime: S.NUMBER,
      running: S.BOOL, timeStep: S.TIME_STEP,
      log: { type: 'object', properties: { entries: S.ARRAY_OF_OBJECTS, cursor: S.INT, gap: S.BOOL } },
    }, 'Diagnostics'),
    annotations: hints(true, false, true, 'Solver diagnostics and log'),
    map(args) {
      return { op: 'getDiagnostics', args: pick(args, ['log']) };
    },
    // [SP_MCP_03_04] log limit halved until it fits
    async reduce(result, call, ctx) {
      const requested = call.args;
      if (!requested.log) return null;
      let limit = Number.isInteger(requested.log.limit) ? requested.log.limit : 50;
      let effective = requested;
      let r = result;
      while (r.ok && !shaping.fits(r, ctx.noteSpace) && limit > 1) {
        limit = Math.max(1, Math.floor(limit / 2));
        effective = Object.assign({}, requested, { log: Object.assign({}, requested.log, { limit }) });
        r = await ctx.agent(call.op, effective);
      }
      return { result: r, note: reductionNote(requested, effective, 'continue from data.log.cursor') };
    },
  },
  {
    name: 'circuit_checkpoint',
    title: 'Checkpoint',
    description:
      'Seal the agent\'s open edit transaction as one named undo entry; the undo menu then shows ' +
      '"Undo: <comment>". All successful agent edits since the last checkpoint form one transaction; ' +
      'checkpoint after each meaningful step so the user, or circuit_history restore, can return to it. ' +
      'comment: 1..120 characters, one line. Returns {checkpointId, noChanges}. Acts on the active ' +
      'document unless `doc` is given. Example: {"comment": "add RC low-pass stage"}.',
    inputSchema: inputSchema({
      comment: { type: 'string', minLength: 1, maxLength: 120 },
    }, ['comment']),
    outputSchema: S.operationResult({ checkpointId: S.STRING, noChanges: S.BOOL }, '{checkpointId?, noChanges}'),
    annotations: hints(false, false, false, 'Checkpoint'),
    map(args) {
      return { op: 'checkpoint', args: pick(args, ['comment']) };
    },
  },
  {
    name: 'circuit_history',
    title: 'Undo history',
    description:
      'Undo history of a document. action list: undo and redo entries {checkpointId?, comment?, auto, ' +
      'kind, position} and whether a transaction is open (limit, default 20); undo / redo: steps entries ' +
      '(default 1); restore: checkpointId (required) pops undo entries down to and including that ' +
      'checkpoint, restoring the state before it. An open transaction is sealed first (comment "agent ' +
      'edits (auto)"). Element IDs survive undo and redo. Acts on the active document unless `doc` is ' +
      'given. Example: {"action": "restore", "checkpointId": "cp2"}.',
    inputSchema: inputSchema({
      action: { type: 'string', enum: ['list', 'undo', 'redo', 'restore'] },
      steps: { type: 'integer', minimum: 1, maximum: 50 },
      checkpointId: { type: 'string', pattern: '^cp[1-9][0-9]*$' },
      limit: { type: 'integer', minimum: 1, maximum: 150 },
    }, ['action']),
    outputSchema: S.operationResult({
      undo: S.ARRAY_OF_OBJECTS, redo: S.ARRAY_OF_OBJECTS, openTransaction: S.BOOL, undone: S.INT, redone: S.INT,
    }, 'list: {undo, redo, openTransaction}; undo/restore: {undone}; redo: {redone}.'),
    annotations: hints(false, true, false, 'Undo history'),
    map(args, name) {
      return route(name, args, {
        list: { op: 'getHistory', keys: ['limit'] },
        undo: { op: 'undo', keys: ['steps'] },
        redo: { op: 'redo', keys: ['steps'] },
        restore: { op: 'restoreCheckpoint', keys: ['checkpointId'], required: ['checkpointId'] },
      });
    },
  },
  {
    name: 'circuit_file',
    title: 'Circuit files and export',
    description:
      'Circuit files and export. action open: read the absolute path (.txt or .json, at most 10 MB, ' +
      'content must be a circuit) into a new document (into "new", the default; activate shows it) or ' +
      'into the open document `into`, replacing its circuit. save: write the document to path (absolute, ' +
      '.txt or .json; absent = its current path) as text or json (default from the extension); an ' +
      'existing file is overwritten only when it is empty or a circuit. export: return the content as ' +
      'text or json (default json) without touching files; content over the 60000-character limit is ' +
      'rejected with result_too_large - use save, or circuit_get pages. Any other path is ' +
      'file_not_allowed. Acts on the active document unless `doc` is given. Example: {"action": "save", ' +
      '"path": "/home/me/rc.json"}.',
    inputSchema: inputSchema({
      action: { type: 'string', enum: ['open', 'save', 'export'] },
      path: { type: 'string', description: 'Absolute path ending in .txt or .json.' },
      into: { type: 'string', description: 'open: "new" (default) or a document handle.' },
      activate: { type: 'boolean', description: 'open: show the document (default false).' },
      format: { type: 'string', enum: ['text', 'json'] },
    }, ['action']),
    outputSchema: S.operationResult({
      doc: S.STRING, elements: S.INT, path: S.STRING, bytes: S.INT, content: S.STRING,
    }, 'open: {doc, elements}; save: {path, bytes}; export: {content}.'),
    annotations: hints(false, true, false, 'Circuit files and export'),
    map(args, name) {
      return route(name, args, {
        open: { op: 'openFile', keys: ['path', 'into', 'activate'], required: ['path'] },
        save: { op: 'saveFile', keys: ['path', 'format'] },
        export: { op: 'exportCircuit', keys: ['format'] },
      });
    },
  },
];

const BY_NAME = new Map(TOOLS.map((t) => [t.name, t]));

// room reserved for a reduction note when measuring a re-executed result (the note is short)
const NOTE_SPACE = 'x'.repeat(400);

/** The tools/list descriptors (SP_MCP_01_03). */
function descriptors() {
  return TOOLS.map((t) => ({
    name: t.name,
    title: t.title,
    description: t.description,
    inputSchema: t.inputSchema,
    outputSchema: t.outputSchema,
    annotations: t.annotations,
  }));
}

/**
 * @param agent the agent client of agent.js ({call(op, args, opts), reportError(message)})
 * @returns {{list(): object[], call(name, args, extra): Promise<object>, plan(name, args): {op, args}}}
 */
function createTools(agent) {
  const list = descriptors();

  /** Validates and maps the arguments; throws McpError -32602 for bad requests. */
  function plan(name, args) {
    const tool = BY_NAME.get(name);
    if (!tool) {
      throw new McpError(ErrorCode.InvalidParams, `Unknown tool: ${String(name).slice(0, 64)}`);
    }
    const a = args === undefined || args === null ? {} : args;
    const err = validate(tool.inputSchema, a, '');
    if (err) throw invalidParams(name, err.path, err.message);
    return tool.map(a, name);
  }

  async function shape(tool, call, result) {
    const ctx = { agent: (op, a) => agent.call(op, a), noteSpace: NOTE_SPACE };
    // circuit_render: PNG to the image part; an oversized SVG is result_too_large
    if (tool.name === 'circuit_render') {
      const { result: r, image } = shaping.extractPng(result);
      if (!image && r.ok === true && !shaping.fits(r)) {
        const d = r.data || {};
        return shaping.toolResult(shaping.resultTooLarge(r, shaping.textLength(r), 'Use format png or a lower scale.',
          { format: d.format, width: d.width, height: d.height }));
      }
      return shaping.toolResult(r, { image });
    }
    if (shaping.fits(result)) return shaping.toolResult(result);
    // circuit_file export: oversized content is result_too_large
    if (call.op === 'exportCircuit' && result.ok === true) {
      return shaping.toolResult(shaping.resultTooLarge(result, shaping.textLength(result),
        'Use action save, or circuit_get pages.', null));
    }
    // read-only tools: re-execute with smaller arguments (SP_MCP_03_04)
    if (tool.reduce && tool.annotations.readOnlyHint) {
      const reduced = await tool.reduce(result, call, ctx);
      if (reduced) return shaping.toolResult(reduced.result, { note: reduced.note });
    }
    // runs and mutating tools are never re-executed: the text part alone is shortened
    return shaping.toolResult(result);
  }

  async function call(name, args) {
    try {
      const p = plan(name, args); // McpError for unknown tools and schema violations
      const result = await agent.call(p.op, p.args);
      return await shape(BY_NAME.get(name), p, result);
    } catch (e) {
      if (e instanceof McpError) throw e;
      // [SP_MCP_03_03] unexpected exception in a tool: isError result, and the global handler
      agent.reportError(`MCP tool ${name}: ${e && e.stack ? e.stack : e}`);
      return shaping.toolResult(shaping.internalErrorResult(
        `Unexpected error in tool ${name}: ${String(e && e.message ? e.message : e).slice(0, 300)}`));
    }
  }

  return { list: () => list, call, plan };
}

module.exports = { createTools, descriptors, TOOLS };
