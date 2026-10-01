# CircuitJS1 Desktop — Glossary

> Canonical domain vocabulary, shared across all concepts. Loaded with _index.md. Started 2026-10-01 with the agent-automation terms of [E_AGT](./agent-automation.epic.md); older concepts predate it.

## Terms

### Agent automation

**Agent API**
The transport-free set of operations through which an AI agent creates, edits, inspects, simulates and debugs circuits ([C_AGA](./agent-api.concept.md)).
_Avoid_: JS API (that is the older `CircuitJS1` scripting global), MCP API

**Element ID**
The stable, human-readable identity of an element within one document (`R1`, `R_load`), kept through edits, undo/redo and JSON save/reload; reloading a legacy text file reassigns the same IDs deterministically.
_Avoid_: element index, element key, element name

**Document handle**
The session-stable identity of one open circuit tab, used to address it from the Agent API.
_Avoid_: tab id, doc index

**Grid cell**
The agent's coordinate unit: one standard editor grid pitch, independent of the small-grid display preference.
_Avoid_: grid unit, pixel (when meaning cells)

**Post**
A connection point of an element, named `elementId.pinName` with a pin name unique within the element.
_Avoid_: terminal, pin position, node (a node is electrical, a post is geometric)

**Net**
A set of posts joined electrically through coincidence, wires, same-named labels or ground.
_Avoid_: node (in agent-facing text), connection

**Connectivity report**
The list of nets and their members together with the connectivity issues of a document.
_Avoid_: lint, netlist dump, topology check

**Issue**
One coded problem with its severity, location, involved elements and a fix hint. Connectivity, validation, import and solver diagnostics all use the same issue form.
_Avoid_: warning (a severity, not the record), error message

**Agent transaction**
The open group of agent edits on a document since its last checkpoint.
_Avoid_: batch, session

**Checkpoint**
A sealed agent transaction stored as one undo entry that carries the agent's comment.
_Avoid_: snapshot (that is the undo storage form), savepoint

**Probe**
A measured quantity (net voltage, post voltage, element voltage/current/power): sampled at every step of an agent-controlled run, or read once at the current instant.
_Avoid_: scope (the on-screen oscilloscope view), trace

**Instance registry**
The per-user record of running app instances and their MCP endpoints.
_Avoid_: discovery file, port file

**MCP bridge**
The stdio program that connects stdio-only agent hosts and shell scripts to an app instance's endpoint ([C_MCB](./mcp-bridge.concept.md)).
_Avoid_: MCP client (the agent host is the protocol client), proxy server

## Flagged ambiguities

- **Node vs net** — the simulator's "node" is a solver row; agent-facing text says "net" for the electrical set of posts and "post" for a connection point. Existing simulator concepts keep "node".
