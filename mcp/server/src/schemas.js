'use strict';
// [SP_MCP_01_03] JSON Schemas of the tool inputs and outputs, built from the SP_AGA structures
// (docs/agent-api.sp.md §01). Input schemas name the SP_AGA argument names unchanged; they carry
// the documented ranges (minimum, maximum, maxItems, pattern) for the agent's information, but
// the server enforces only types, required fields, enumerations and unknown arguments
// (SP_MCP_03_02, see validate.js): ranges, lattice, IDs and value formats are domain rules of
// the Agent API, which answers them as `isError` results with a hint.
//
// Output schemas describe OperationResult (SP_AGA_01_08) with the contract's `data` fields.
// Nothing inside `data` is required and nested objects stay open, so a client that validates
// `structuredContent` (the SDK client does, with ajv) never rejects a valid Agent API result.

// ---------------------------------------------------------------- shared input structures

const DOC = {
  type: 'string',
  pattern: '^d[1-9][0-9]*$',
  description: 'Document handle (d1, d2, ...) from circuit_documents. Absent: the active document.',
};

const ELEMENT_ID = {
  type: 'string',
  pattern: '^[A-Za-z][A-Za-z0-9_]{0,31}$',
  description: 'Element ID, e.g. R1.',
};

/** [SP_AGA_01_01] CellPoint: grid cells (1 cell = 16 px), x right, y down. */
const CELL_POINT = {
  type: 'object',
  properties: {
    x: { type: 'number', minimum: -4096, maximum: 4096, description: 'Column in grid cells (multiple of 0.5 for edits).' },
    y: { type: 'number', minimum: -4096, maximum: 4096, description: 'Row in grid cells, y grows downwards.' },
  },
  required: ['x', 'y'],
};

const PROPERTY_VALUE = { type: ['number', 'string', 'boolean'] };

/** [SP_AGA_01_03] ElementSpec; an ElementRecord is accepted too (its `posts` are ignored). */
const ELEMENT_SPEC = {
  type: 'object',
  description: 'Element to create. An element record read with circuit_get is accepted unchanged.',
  properties: {
    id: Object.assign({}, ELEMENT_ID, { description: 'Optional ID; generated as <idPrefix><n> when absent.' }),
    type: { type: 'string', description: 'Catalogue type name or alias (circuit_types).' },
    start: Object.assign({}, CELL_POINT, { description: 'First defining point, grid cells.' }),
    end: Object.assign({}, CELL_POINT, { description: 'Second defining point, grid cells; default start + defaultSize.' }),
    properties: {
      type: 'object',
      additionalProperties: PROPERTY_VALUE,
      description: 'Property keys of the type: numbers or unit strings ("4.7k", "10 uF"), booleans, texts.',
    },
    flags: { type: 'integer', minimum: 0, description: 'Raw flag bits (rarely needed).' },
    description: { type: 'string', maxLength: 1000 },
  },
  required: ['type', 'start'],
};

const QUANTITY = { type: 'string', enum: ['voltage', 'current', 'power'] };

/** [SP_AGA_01_09] ProbeSpec: exactly one of net, post, element. */
const PROBE_SPEC = {
  type: 'object',
  description: 'One of net, post or element. Example: {"net": "out"} or {"element": "R1", "quantity": "current"}.',
  properties: {
    name: { type: 'string', description: 'Result key; default the target string.' },
    net: { type: 'string', description: 'Net name: gnd, a label text, label:<text> or $<k>.' },
    post: { type: 'string', description: 'PostRef <ElementId>.<PinName>, e.g. R1.pin2.' },
    element: Object.assign({}, ELEMENT_ID, { description: 'Element whose quantity is read.' }),
    quantity: Object.assign({}, QUANTITY, { description: 'With element: voltage (default), current or power.' }),
  },
};

const BUDGET = { type: 'integer', minimum: 100, maximum: 120000 };

const MODEL_KIND = { type: 'string', enum: ['diode', 'transistor', 'logic', 'subcircuit'] };

/**
 * [SP_AGA_01_13] ModelSpec or ModelText (the form is told by `modelText`). One open object: the
 * fields per kind, the value table and the forms are the Agent API's domain rules.
 */
const MODEL_ENTRY = {
  type: 'object',
  description: 'ModelSpec {kind, name, from?, parameters} (diode, transistor), {kind: "logic", name, inputs, outputs, rules, info?} '
    + 'or {kind: "subcircuit", name, source: {doc}, showLabel?} (the whole circuit of another document, external pins = its '
    + 'labelled nodes, posts pin1..N by label text), or ModelText {kind, name, modelText} (one model line of the text format, '
    + 'as circuit_get returns it). Names are create-only: an existing name is accepted only with an identical definition. '
    + 'Example: {"kind": "diode", "name": "led-green-2v1", "parameters": {"forward_voltage": "2.1 V", "forward_current": "20 mA"}}; '
    + '{"kind": "logic", "name": "and2", "inputs": ["A", "B"], "outputs": ["Y"], "rules": ["11=1", "??=0"]}; '
    + '{"kind": "subcircuit", "name": "rc-filter", "source": {"doc": "d2"}}.',
  properties: {
    kind: MODEL_KIND,
    name: { type: 'string', description: 'Model name: ^[A-Za-z0-9][A-Za-z0-9_.+-]{0,39}$ for a ModelSpec.' },
    from: { type: 'string', description: 'diode/transistor: a listed model of the same kind whose values are the start (default: "default").' },
    parameters: {
      type: 'object',
      additionalProperties: PROPERTY_VALUE,
      description: 'diode: saturation_current, series_resistance, emission_coefficient, breakdown_voltage, forward_voltage + '
        + 'forward_current (simple form); transistor: saturation_current, beta_reverse, emission_coefficient_forward/_reverse, '
        + 'leakage_be/bc_current, leakage_be/bc_emission, early_voltage_forward/_reverse, knee_current_forward/_reverse ("inf" allowed).',
    },
    modelText: { type: 'string', description: 'ModelText: exactly one model line.' },
    inputs: { type: 'array', items: { type: 'string' }, description: 'logic: input pin names (1-32, 1-8 chars each; markup /, #, CLK:, INV:).' },
    outputs: { type: 'array', items: { type: 'string' }, description: 'logic: output pin names (1-32).' },
    rules: { type: 'array', items: { type: 'string' }, description: 'logic: rule lines left=right (1-256, at most 100 chars each).' },
    info: { type: 'string', description: 'logic: info text (at most 200 chars; default the name).' },
    source: { type: 'object', properties: { doc: DOC }, description: 'subcircuit: {doc} — the open document whose whole circuit becomes the model (read only; not the document of the call).' },
    showLabel: { type: 'boolean', description: 'subcircuit: draw the model name on the chip (default true).' },
  },
  required: ['kind', 'name'],
};

/** [SP_AGA_02_04] Edit variants, discriminated by `op`. */
const EDIT_VARIANTS = [
  {
    type: 'object',
    title: 'add',
    properties: { op: { const: 'add' }, element: ELEMENT_SPEC },
    required: ['op', 'element'],
  },
  {
    type: 'object',
    title: 'move',
    description: 'start (and optional end) sets the defining points; start alone translates; by translates by cells.',
    properties: {
      op: { const: 'move' },
      id: ELEMENT_ID,
      start: CELL_POINT,
      end: CELL_POINT,
      by: {
        type: 'object',
        properties: { dx: { type: 'number' }, dy: { type: 'number' } },
        required: ['dx', 'dy'],
      },
    },
    required: ['op', 'id'],
  },
  {
    type: 'object',
    title: 'delete',
    properties: { op: { const: 'delete' }, id: ELEMENT_ID },
    required: ['op', 'id'],
  },
  {
    type: 'object',
    title: 'set',
    description: 'Patch properties; keys not given keep their value.',
    properties: {
      op: { const: 'set' },
      id: ELEMENT_ID,
      properties: { type: 'object', additionalProperties: PROPERTY_VALUE },
      flags: { type: 'integer', minimum: 0 },
    },
    required: ['op', 'id', 'properties'],
  },
  {
    type: 'object',
    title: 'describe',
    properties: { op: { const: 'describe' }, id: ELEMENT_ID, description: { type: 'string', maxLength: 1000 } },
    required: ['op', 'id', 'description'],
  },
  {
    type: 'object',
    title: 'addScope',
    properties: { op: { const: 'addScope' }, element: ELEMENT_ID, quantity: QUANTITY },
    required: ['op', 'element'],
  },
  {
    type: 'object',
    title: 'removeScope',
    properties: { op: { const: 'removeScope' }, element: ELEMENT_ID },
    required: ['op', 'element'],
  },
  {
    type: 'object',
    title: 'defineModel',
    description: 'Register a new session model (create-only); later edits of the batch may use its name.',
    properties: { op: { const: 'defineModel' }, model: MODEL_ENTRY },
    required: ['op', 'model'],
  },
  {
    type: 'object',
    title: 'markOpen',
    description: 'Declare posts intentionally unconnected (open: false removes the mark).',
    properties: {
      op: { const: 'markOpen' },
      posts: { type: 'array', items: { type: 'string' }, minItems: 1 },
      open: { type: 'boolean' },
    },
    required: ['op', 'posts'],
  },
];

const EDIT = {
  type: 'object',
  description: 'One edit, discriminated by op: add | move | delete | set | describe | addScope | removeScope | markOpen | defineModel.',
  oneOf: EDIT_VARIANTS,
};

/** [SP_AGA_02_03] AgentCircuit. */
const AGENT_CIRCUIT = {
  type: 'object',
  properties: {
    elements: { type: 'array', items: ELEMENT_SPEC, maxItems: 5000 },
    simulation: { type: 'object', description: 'JSON v2 simulation settings, e.g. {"time_step": "5 us"}.' },
    scopes: {
      type: 'array',
      maxItems: 20,
      items: {
        type: 'object',
        properties: { element: ELEMENT_ID, quantity: QUANTITY },
        required: ['element'],
      },
    },
    models: {
      type: 'array',
      maxItems: 200,
      items: MODEL_ENTRY,
      description: 'Models defined before the elements, dependencies first (ModelSpec or ModelText; circuit_get returns them).',
    },
  },
  required: ['elements'],
};

// ---------------------------------------------------------------- shared output structures

const ISSUE = {
  type: 'object',
  properties: {
    code: { type: 'string' },
    severity: { type: 'string', enum: ['error', 'warning', 'info'] },
    message: { type: 'string' },
    elements: { type: 'array', items: { type: 'string' } },
    posts: { type: 'array', items: { type: 'string' } },
    at: { type: 'object' },
    hint: { type: 'string' },
    key: { type: 'string' },
  },
  required: ['code', 'severity', 'message'],
};

const ISSUES = { type: 'array', items: ISSUE };

const CONNECTIVITY_DELTA = {
  type: 'object',
  description: 'Connectivity issues added and cleared by the call, with the totals after it.',
  properties: {
    added: ISSUES,
    cleared: ISSUES,
    errorCount: { type: 'integer' },
    warningCount: { type: 'integer' },
    truncatedAdded: { type: 'integer' },
    truncatedCleared: { type: 'integer' },
  },
};

const TRANSACTION = {
  type: 'object',
  properties: { open: { type: 'boolean' }, pendingEdits: { type: 'integer' } },
};

const ELEMENT_RECORD = {
  type: 'object',
  properties: {
    id: { type: 'string' },
    type: { type: 'string' },
    start: { type: 'object' },
    end: { type: 'object' },
    posts: { type: 'array', items: { type: 'object' } },
    properties: { type: 'object' },
    flags: { type: 'integer' },
    description: { type: 'string' },
  },
};

const TIME_STEP = { type: 'object', properties: { current: { type: 'number' }, max: { type: 'number' }, min: { type: 'number' }, auto: { type: 'boolean' } } };

const OBJECT = { type: 'object' };
const ARRAY_OF_OBJECTS = { type: 'array', items: OBJECT };
const STRING = { type: 'string' };
const INT = { type: 'integer' };
const NUMBER = { type: 'number' };
const BOOL = { type: 'boolean' };

/**
 * [SP_MCP_01_03] outputSchema: OperationResult (SP_AGA_01_08) whose `data` has the given
 * (optional) fields.
 */
function operationResult(dataProperties, dataDescription) {
  return {
    type: 'object',
    properties: {
      ok: { type: 'boolean', description: 'false: the call was rejected and nothing changed; issues say why.' },
      data: { type: 'object', description: dataDescription, properties: dataProperties },
      issues: Object.assign({}, ISSUES, { description: 'At most 50, errors first.' }),
      truncatedIssues: { type: 'integer' },
      connectivity: CONNECTIVITY_DELTA,
      transaction: TRANSACTION,
    },
    required: ['ok', 'issues'],
  };
}

module.exports = {
  DOC,
  ELEMENT_ID,
  CELL_POINT,
  ELEMENT_SPEC,
  PROBE_SPEC,
  QUANTITY,
  BUDGET,
  EDIT,
  EDIT_VARIANTS,
  AGENT_CIRCUIT,
  MODEL_KIND,
  MODEL_ENTRY,
  ISSUE,
  ELEMENT_RECORD,
  TIME_STEP,
  OBJECT,
  ARRAY_OF_OBJECTS,
  STRING,
  INT,
  NUMBER,
  BOOL,
  operationResult,
};
