---
name: circuitjs-circuits
description: Builds, edits, simulates, measures and debugs circuits in the CircuitJS1 desktop simulator through its MCP tools (`circuit_*`); use when the user asks to design, draw, simulate, tune or fix an electronic circuit in CircuitJS1 or Falstad format.
---

# CircuitJS1 circuits

## Purpose and scope

This skill is for building, checking, simulating and repairing analog and digital circuits in a running CircuitJS1 Desktop app, through the `circuit_*` tools of its MCP server. You place parts on a grid, clear the connectivity report, run the transient simulator with probes, and report measured values. It does not cover PCB layout or frequency-domain (AC, Bode, noise) analysis: the simulator has transient analysis only, so you measure a frequency response by running the circuit at each frequency.

**Compatibility.** This skill works with **toolsVersion 1.0**. The server instructions contain `toolsVersion X.Y`; through the bridge, `bridge_instances` (or `circuitjs-mcp instances`) shows it per instance. Compare it with 1.0 before the first call:
- same MAJOR (1) and MINOR ≥ 0: go ahead (a newer MINOR only adds tools, issue codes or properties);
- a different MAJOR, or a version older than 1.0: tell the user that the app and this skill do not match, name both versions, and do not guess tool names or arguments.

**No tools?** With the bridge and no app running, only the `bridge_*` tools are listed: if `bridge_launch` is available, call it first. If no `circuit_*` tool appears after that (or there is no bridge), or every call answers `No CircuitJS1 instance`, stop. Tell the user how to connect, and do not try to imitate the tools:
- start the CircuitJS1 Desktop app (its server listens on `http://127.0.0.1:7311/mcp`; Options → "MCP Server..." shows the URL);
- Claude Code: `claude mcp add --transport http circuitjs http://127.0.0.1:7311/mcp`, see [hosts/claude-code.md](hosts/claude-code.md);
- hosts that start only stdio servers (Claude Desktop): the `circuitjs-mcp` bridge. Merge [hosts/claude-desktop.json](hosts/claude-desktop.json) into `claude_desktop_config.json` (Settings → Developer → Edit Config), put the app's absolute path in `CIRCUITJS_APP`, and restart Claude Desktop. With the bridge and no app running, `bridge_launch` starts the app when it is configured.

## Golden rules

- **Coordinates are grid cells** (1 cell = 16 px, x right, y down); put every new part on whole cells.
- **Work in a new document** (`circuit_documents` `create`) unless the user names a document to change.
- **Every circuit gets an explicit `Ground`.**
- **Label every net you will probe** with a `LabeledNode`; its text becomes the net name.
- **Never simulate while the connectivity report has errors.** Floating nodes read 0 V instead of failing.
- **Measure, never assume.** Values, polarity and signs come from `circuit_run` / `circuit_read`, not from pin names or memory.
- **Checkpoint with a comment** (`circuit_checkpoint`) after each logical change.
- **Never fix the user's unrelated issues unasked**: report them.

## Workflow checklist

Copy this list into your notes and tick it off:

```
- [ ] 1. Clarify the target: function, values to hit, how success is measured (ask; no tool)
- [ ] 2. Pick a start: a pattern from reference/patterns.md, an example (circuitjs://examples), or a net sketch; check types with circuit_types
- [ ] 3. Own tab: circuit_documents {"action": "create", "title": "..."}; pass its doc to every later call
- [ ] 4. Place: circuit_import (whole circuit) or circuit_edit (incremental); read derived posts back from the reply
- [ ] 5. Clear connectivity: the connectivity delta of the reply, circuit_connectivity for the full report; 0 errors
- [ ] 6. Operating point: circuit_run {"mode": "settle", "reset": true} with probes on the supply nets
- [ ] 7. Run, probe, measure: circuit_run {"span", "reset": true, "recordFrom", "probes"}; check reason and issues first
- [ ] 8. Iterate: circuit_edit {"edits": [{"op": "set", "id": ..., "properties": {...}}]}; re-check the connectivity delta, re-run step 7
- [ ] 9. Checkpoint: circuit_checkpoint {"comment": "what changed and why"}
- [ ] 10. Report the measured values; circuit_render when the user wants to see it; circuit_file save only when asked
```

Notes on the steps:
- **Step 2.** `circuit_types {"type": "<name>"}` gives pins in post order, the geometry kind, property keys, units and defaults. Do not guess keys: a wrong key is rejected and the hint lists the valid ones.
- **Step 4.** One `circuit_import` call replaces the whole circuit of that document. Edits are atomic per batch: one bad edit rejects the batch, nothing changes.
- **Step 6.** A circuit driven by an AC source never settles (`settle_timeout`); read DC levels from `stats.mean` of a span run instead.
- **Step 7.** Pass `reset: true` on every measuring run, so results are repeatable. Give two probes on the same element distinct `name`s.
- **Step 10.** Say what you measured and with which run (span, step, recordFrom), not what the formula predicts. If the target cannot be reached with the chosen topology, report the limit with the evidence instead of tuning on.

## Debug loop

When a reply carries issues, a run ends with `reason` other than `span_reached`/`settled`, or values look wrong:

1. Read the issues: the `connectivity` delta of the last mutation, `circuit_connectivity {"doc"}` for the full list, and `circuit_diagnostics {"doc"}` for solver events (`stopped`, `stop`, `events`, `recovering`).
2. Look up each `code` in [reference/diagnostics.md](reference/diagnostics.md): cause, fix, confirming call. Read the `hint` and the `elements`/`posts` it names.
3. Fix with the smallest edit (`circuit_edit`): move a post, split a wire, add the missing ground or series resistance, change a value.
4. Re-check: the new connectivity delta must list the issue under `cleared`; then re-run with `reset: true`.
5. An error-level issue in a run result (solver codes such as `convergence_failed` or `source_or_wire_loop`) invalidates that run's measurements, even though the run itself returned `ok: true`. A document stopped by the solver needs `reset: true` before it runs again.

Symptoms without an issue code (flat 0 V, an oscillator that does not start, results that change with the time step, slow runs) are at the end of the diagnostics reference.

## Tool map

Hosts qualify tool names with the server name they were configured with; this skill writes them as `circuitjs:<tool>` (Claude Code shows `mcp__circuitjs__<tool>`). Every `circuit_*` tool takes `doc`; without it, it acts on the visible tab.

| Tool | Use |
|------|-----|
| `circuitjs:circuit_types` | List the catalogue (`filter`), or describe one type (`type`): pins, geometry, sizes, property keys |
| `circuitjs:circuit_documents` | `list`, `create` (background unless `activate: true`), `activate`, `close` documents (tabs) |
| `circuitjs:circuit_import` | Replace a document's circuit with an AgentCircuit `{elements, simulation?, scopes?}` or a text/JSON circuit string |
| `circuitjs:circuit_edit` | Batch of `add`, `move`, `delete`, `set`, `describe`, `addScope`, `removeScope`, `markOpen` edits |
| `circuitjs:circuit_get` | Read element records with posts, nets and properties; paged by `offset`/`limit` |
| `circuitjs:circuit_connectivity` | Full connectivity report: nets and issues with hints (`netFilter`, `includeNets`) |
| `circuitjs:circuit_read` | Instant readings of nets, posts or element quantities at the current time, without stepping |
| `circuitjs:circuit_render` | PNG (default) or SVG image of the whole circuit, offscreen |
| `circuitjs:circuit_sim` | Free-running `run`/`stop` for the user to watch, `reset`, `configure` the time step |
| `circuitjs:circuit_run` | Advance simulated time with probes; returns stats and decimated series |
| `circuitjs:circuit_diagnostics` | Solver state and events since the last analysis; the session log with `log` |
| `circuitjs:circuit_checkpoint` | Seal your edits since the last checkpoint as one named undo entry |
| `circuitjs:circuit_history` | `list` the undo history, `undo`/`redo` steps, `restore` a checkpoint |
| `circuitjs:circuit_file` | `open` a `.txt`/`.json` file, `save` to an absolute path, `export` the content as text or JSON |
| `circuitjs:bridge_instances` | Bridge only: the running app instances with their `toolsVersion` |
| `circuitjs:bridge_select` | Bridge only: forward to one instance (`instanceId` or `url`) |
| `circuitjs:bridge_launch` | Bridge only: start the app (or use the running one), optionally opening a file in a new tab |

Resources (read them with the host's resource reader):
- `circuitjs://docs/agent-format` — the exact contracts: ElementSpec, edit ops, probe and result fields, limits, every issue code. Read it when an argument shape is unclear; this skill does not repeat it.
- `circuitjs://catalogue`, `circuitjs://catalogue/{type}` — every type and its TypeInfo.
- `circuitjs://examples`, `circuitjs://examples/{path}` — the bundled example circuits as text; pass the text to `circuit_import` as `circuit` to load one.
- `circuitjs://documents/{doc}/circuit` — a document's whole circuit in importable form.

## References

Read a reference only when the step needs it:
- [reference/geometry.md](reference/geometry.md) — before placing parts: cells, `start`/`end`, derived pins of transistors, op-amps and chips, how posts connect, labels, layout style.
- [reference/elements.md](reference/elements.md) — when choosing parts: the common types with pins, key properties, units and typical values.
- [reference/patterns.md](reference/patterns.md) — when a known circuit fits: verified circuits with the probes and the measured values.
- [reference/simulation.md](reference/simulation.md) — before measuring: time step, run span, operating point, deriving frequency, gain, ripple and rise time, budgets.
- [reference/diagnostics.md](reference/diagnostics.md) — when a reply carries an issue code, or a result looks wrong.
