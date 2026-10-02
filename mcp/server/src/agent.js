'use strict';
// [PL_MCP_P1] Agent client: a Promise wrapper over window.CircuitJS1Agent (SP_AGA). Every call
// goes through callAsync, which calls back synchronously for the synchronous contracts and once
// at the end for the asynchronous ones (run, render). The result is the parsed OperationResult.

// Asynchronous contracts of SP_AGA_02 ("Timing"); every other contract calls back before
// callAsync returns.
const ASYNC_OPS = new Set(['run', 'render']);

// Upper bound for an asynchronous contract that never calls back (an exception before the
// handler started is swallowed by the GWT $entry wrapper). Runs end within their budgetMs
// (at most 120 s, SP_AGA_02_10) plus one slice and the queue of concurrent slices; the 60 s
// margin covers that, and renders, which are sliced and normally take well under a second.
// Stays below the protocol layer's backstop (protocol.js PENDING_TIMEOUT_MS, 200 s), so the
// caller gets this internal_error result rather than a bare JSON-RPC error, and both stay below
// the hosts' 300 s response timeout (SP_MCP_DEC_02).
const ASYNC_TIMEOUT_MS = 180000;

/** An OperationResult (SP_AGA_01_08) with one internal_error issue, shaped like the Java side's. */
function internalError(message) {
  return {
    ok: false,
    issues: [{
      code: 'internal_error', severity: 'error', message,
      hint: 'Retry the call; if it fails again, check the application log.', key: 'internal_error|||',
    }],
    truncatedIssues: 0,
  };
}

/** Parses an Agent API return value; undefined or unparseable text becomes internal_error. */
function parseResult(op, text) {
  if (typeof text !== 'string') {
    return internalError(`The Agent API returned no result for '${op}'.`);
  }
  try {
    const value = JSON.parse(text);
    if (value && typeof value === 'object' && typeof value.ok === 'boolean') {
      return value;
    }
  } catch (e) {
    // fall through
  }
  return internalError(`The Agent API returned an unparseable result for '${op}'.`);
}

/**
 * @param bridge the CircuitJS1Agent object ({call, callAsync, reportError})
 * @returns {{call(op: string, args?: object, opts?: {timeoutMs?: number}): Promise<object>,
 *            reportError(message: string): void}}
 */
function createAgentClient(bridge) {
  function call(op, args, opts) {
    if (!bridge || typeof bridge.callAsync !== 'function') {
      return Promise.resolve(internalError('The Agent API (CircuitJS1Agent) is not available.'));
    }
    const argsJson = JSON.stringify(args || {});
    return new Promise((resolve) => {
      let done = false;
      let timer = null;
      const finish = (result) => {
        if (done) return;
        done = true;
        if (timer) clearTimeout(timer);
        resolve(result);
      };
      try {
        bridge.callAsync(op, argsJson, (resultJson) => finish(parseResult(op, resultJson)));
      } catch (e) {
        finish(internalError(`The Agent API threw for '${op}': ${String(e && e.message ? e.message : e).slice(0, 300)}`));
        return;
      }
      if (done) return;
      if (!ASYNC_OPS.has(op)) {
        // A synchronous contract that did not call back threw inside the app; the exception
        // already reached the global handler through $entry.
        finish(internalError(`The Agent API returned no result for '${op}'.`));
        return;
      }
      const timeoutMs = opts && opts.timeoutMs > 0 ? opts.timeoutMs : ASYNC_TIMEOUT_MS;
      timer = setTimeout(() => finish(internalError(`The Agent API gave no result for '${op}' within ${timeoutMs} ms.`)), timeoutMs);
    });
  }

  function reportError(message) {
    if (bridge && typeof bridge.reportError === 'function') {
      try {
        bridge.reportError(String(message));
      } catch (e) {
        // the global handler itself failed; nothing more to do
      }
    }
  }

  return { call, reportError };
}

module.exports = { createAgentClient, parseResult, internalError, ASYNC_OPS, ASYNC_TIMEOUT_MS };
