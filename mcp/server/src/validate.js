'use strict';
// [SP_MCP_03_02] Argument validation against a tool's inputSchema before mapping. Type,
// required-field, enumeration and unknown-argument violations are reported (the caller turns
// them into JSON-RPC -32602 naming the field). Range keywords (minimum, maximum, minLength,
// maxLength, minItems, maxItems, pattern) are deliberately not enforced: ranges, lattice, IDs
// and value formats are domain validation of the Agent API, which returns them as `isError`
// results with a fix hint (SP_AGA_02 "Argument ranges").
//
// The supported subset is what schemas.js uses: type (one name or a list; "integer"), enum,
// const, properties, required, additionalProperties (false or a schema), items, oneOf/anyOf.
// A oneOf whose variants all fix `op` with `const` is resolved by that discriminator, so the
// message names the variant's own field instead of "matches no variant".

const MAX_ECHO = 60;

function clip(text) {
  const s = String(text);
  return s.length > MAX_ECHO ? s.slice(0, MAX_ECHO) + '…' : s;
}

function describe(value) {
  if (value === null) return 'null';
  if (Array.isArray(value)) return 'array';
  if (typeof value === 'number' && !Number.isInteger(value)) return 'number';
  if (typeof value === 'number') return 'integer';
  return typeof value;
}

function typeMatches(type, value) {
  switch (type) {
    case 'object': return value !== null && typeof value === 'object' && !Array.isArray(value);
    case 'array': return Array.isArray(value);
    case 'string': return typeof value === 'string';
    case 'boolean': return typeof value === 'boolean';
    case 'number': return typeof value === 'number' && Number.isFinite(value);
    case 'integer': return typeof value === 'number' && Number.isInteger(value);
    case 'null': return value === null;
    default: return true;
  }
}

function join(path, key) {
  if (typeof key === 'number') return `${path}[${key}]`;
  return path ? `${path}.${clip(key)}` : clip(key);
}

function fail(path, message) {
  return { path: path || '(arguments)', message };
}

/** The discriminator values of a oneOf whose variants all fix `op` by const, else null. */
function discriminated(variants) {
  const map = new Map();
  for (const v of variants) {
    const op = v.properties && v.properties.op && v.properties.op.const;
    if (typeof op !== 'string') return null;
    map.set(op, v);
  }
  return map;
}

/**
 * @returns null when `value` satisfies `schema`, else {path, message} of the first violation
 */
function validate(schema, value, path) {
  path = path || '';
  if (!schema || typeof schema !== 'object') return null;

  if (schema.type !== undefined) {
    const types = Array.isArray(schema.type) ? schema.type : [schema.type];
    if (!types.some((t) => typeMatches(t, value))) {
      return fail(path, `must be ${types.join(' or ')} (received ${describe(value)})`);
    }
  }
  if (schema.const !== undefined && value !== schema.const) {
    return fail(path, `must be ${JSON.stringify(schema.const)}`);
  }
  if (Array.isArray(schema.enum) && !schema.enum.includes(value)) {
    return fail(path, `must be one of ${schema.enum.join(', ')} (received ${clip(JSON.stringify(value))})`);
  }

  const variants = schema.oneOf || schema.anyOf;
  if (Array.isArray(variants)) {
    const byOp = discriminated(variants);
    if (byOp && value && typeof value === 'object' && !Array.isArray(value)) {
      if (value.op === undefined) return fail(join(path, 'op'), `is required (one of ${[...byOp.keys()].join(', ')})`);
      const v = byOp.get(value.op);
      if (!v) return fail(join(path, 'op'), `must be one of ${[...byOp.keys()].join(', ')} (received ${clip(JSON.stringify(value.op))})`);
      const err = validate(v, value, path);
      if (err) return err;
    } else {
      let first = null;
      let matched = false;
      for (const v of variants) {
        const err = validate(v, value, path);
        if (!err) {
          matched = true;
          break;
        }
        if (!first) first = err;
      }
      if (!matched) return first || fail(path, 'matches none of the allowed forms');
    }
  }

  if (typeMatches('object', value)) {
    const props = schema.properties || {};
    for (const key of schema.required || []) {
      if (value[key] === undefined) return fail(join(path, key), 'is required');
    }
    for (const key of Object.keys(value)) {
      if (Object.prototype.hasOwnProperty.call(props, key)) {
        const err = validate(props[key], value[key], join(path, key));
        if (err) return err;
      } else if (schema.additionalProperties === false) {
        const known = Object.keys(props);
        return fail(join(path, key), `is not an argument of this tool (known: ${known.join(', ')})`);
      } else if (schema.additionalProperties && typeof schema.additionalProperties === 'object') {
        const err = validate(schema.additionalProperties, value[key], join(path, key));
        if (err) return err;
      }
    }
  }

  if (Array.isArray(value) && schema.items) {
    for (let i = 0; i < value.length; i++) {
      const err = validate(schema.items, value[i], join(path, i));
      if (err) return err;
    }
  }
  return null;
}

module.exports = { validate };
