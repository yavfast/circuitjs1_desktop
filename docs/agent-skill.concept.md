# Circuit Authoring Skill  {#C_AGS}

> **Code:** C_AGS
> **Status:** draft
> **Created:** 2026-10-01
> **Updated:** 2026-10-04
> **Author:** main
> **Owner:** automation tooling maintainers (ships with the bridge)
> **Complexity:** medium
> **Criticality:** supporting
>
> **Depends on:** [C_AGA](./agent-api.concept.md), [C_MCP](./mcp-server.concept.md), [C_MCB](./mcp-bridge.concept.md)
> **Used by:** —
> **Epic:** [E_AGT](./agent-automation.epic.md)
> **Spike:** [mcp-agent-bridge.spike.md](./mcp-agent-bridge.spike.md)
> **Specification:** [SP_AGS](./agent-skill.sp.md)
> **Plan:** [agent-skill.plan.md](./agent-skill.plan.md)
>
> An agent skill that teaches an AI agent to design, build, verify and debug CircuitJS1 circuits through the MCP tools. It contains a short workflow file, reference files read on demand, and an eval set that proves the skill works. Read this concept to write or change the skill, or to judge whether a tool change breaks it.

## Contents

- [1. Philosophy](#C_AGS_01) — why the tools alone are not enough and what the skill must not do
- [2. Domain Model](#C_AGS_02) — skill entry, references, evals, and how content stays current
- [3. Mechanisms](#C_AGS_03) — the workflow loop, geometry conventions, debug mapping, evals, edge cases
- [4. Integration Points](#C_AGS_04) — what it reads and how it is installed

## 1. Philosophy  {#C_AGS_01}

### 1.1. Core Principle  {#C_AGS_01_01}

Tools tell an agent what it *can* do. They do not tell it how a working circuit is built here:
- how grid cells and two-point elements map to pins;
- that posts connect only where they coincide;
- that a silent 0 V usually means a dangling post;
- that the time step must be small compared with the circuit's time constants.

Prior art shows agents fail at exactly these points ([spike, Entry 3](./mcp-agent-bridge.spike.md)). The skill carries this procedural knowledge and makes "verify, then trust" the default loop.

### 1.2. Design Constraints  {#C_AGS_01_02}

- **No duplicated catalogue.** Element types, pins, properties and units come from the server's catalogue resource. The skill teaches how to read it and keeps only a short, hand-checked list of the most common types with their geometry. A tool or catalogue change cannot leave the skill silently wrong about the long tail.
- **Small entry.** The entry file holds a copyable checklist; detail sits in reference files one level deep, read only when needed.
- **Format rules.** The skill follows the agent-skill format rules: a lowercase hyphenated name without vendor words, a third-person description of what the skill does and when to use it, and tools referenced by their fully qualified names.
- **Proven by evals.** A skill change ships only with its eval set passing ([§3.4](#C_AGS_03_04)).
- **Rollback.** Uninstalling the skill leaves the tools fully usable, only less guided.

**This concept IS:** the skill's content model, its workflow, its references, its evals, and how it is installed.

**This concept IS NOT:** tool definitions ([C_MCP](./mcp-server.concept.md)), circuit operations ([C_AGA](./agent-api.concept.md)), or a general electronics textbook.

## 2. Domain Model  {#C_AGS_02}

### 2.1. Key Entities  {#C_AGS_02_01}

| Entity | Content |
|--------|---------|
| **Skill entry** | When to use the skill; golden rules; the workflow checklist; the tool map |
| **Geometry reference** | Grid cells; the two-point element definition; how derived pins land, with examples for transistor, op-amp and chip; wires and coincident-post connections; labels as named nets; a compact layout style (signal left→right, supply rail top, ground bottom) |
| **Common elements reference** | The most-used types with pin names, typical properties and units; models (diode, transistor, custom logic, subcircuit): when to define one, how, typical LED drops, and how files carry them; it points to the catalogue resource for everything else |
| **Diagnostics reference** | Every connectivity issue code and solver stop message → likely cause → fix |
| **Patterns reference** | Proven small circuits (divider, RC/RLC, rectifier, transistor bias, op-amp stages, 555 astable, basic logic, and three with models: an LED with a defined model, a custom-logic block, a subcircuit block), each with the probes and measurements that verify it |
| **Simulation reference** | Time-step and run-span rules of thumb; operating point by "run until settled"; how to measure frequency, gain, ripple and rise time from probe statistics |
| **Eval set** | Scenarios, each with a task prompt, expected observable outcomes and a checker |

### 2.2. Data Flows  {#C_AGS_02_02}

```
Agent host loads skill entry ──► agent follows checklist ──► reads references on demand
                                         │
                                         └──► MCP tools / catalogue resource (live facts)
Eval runner (bridge CLI) ──► scenario prompt ──► agent run ──► checker reads circuit/results ──► pass/fail
```

## 3. Mechanisms  {#C_AGS_03}

### 3.1. The workflow loop  {#C_AGS_03_01}

1. **Clarify the target.** Settle the function, the values to hit and how success will be measured.
2. **Pick a starting point.** Use a pattern or example circuit if one fits; otherwise sketch the nets.
3. **Work in its own tab.** Open a new document unless the user asks for edits in an existing one.
4. **Place.** Use grid cells. Build a whole circuit in one import, or make incremental edits for changes.
5. **Read the connectivity report and clear every issue.** No simulation runs while an error-level issue remains.
6. **Sanity-check the operating point.** Run until settled and check that supply nets read their expected voltages.
7. **Run, probe and measure** against the target values.
8. **Iterate on properties.** After each change, re-check connectivity and the measurements.
9. **Checkpoint** with a comment describing the change, so the user sees it in the undo history.
10. **Look at the drawing, then report.** Render the circuit and fix what the image shows (overlaps, crowded values, parts or labels pointing the wrong way) before reporting the measured outcome; skip the look only for a one-value change. The live agent series showed drawing defects that only the image reveals, so the look is part of every build.

### 3.2. Geometry conventions  {#C_AGS_03_02}

- **Label tracked nets.** Name every net the agent will probe or discuss, so the name survives edits.
- **Keep posts on whole cells.** Leave half-cells to imported circuits.
- **Wire from post to post.** A wire never ends on another wire's body; split it at a junction instead.
- **Read back derived pins.** Read multi-terminal pin positions from the placement result before wiring to them.
- **Ground explicitly.** Every circuit gets at least one explicit ground.

### 3.3. Debug mapping  {#C_AGS_03_03}

The diagnostics reference maps every issue code of [C_AGA_03_03](./agent-api.concept.md#C_AGA_03_03) and every solver stop message to a cause and a fix. Examples: a dangling post means a wire end misses the post; a source/wire loop means a missing series resistance; a convergence failure on an element means checking that element's values and the time step. Because the simulator keeps running through most of these conditions in its recovery mode, the skill treats an error-level issue in a run result as invalidating that run's measurements. The agent consults it before changing anything.

### 3.4. Evals  {#C_AGS_03_04}

- **Size and spread.** At least three scenarios, one for each of build, tune and debug:
  - build an RC low-pass filter with a given cut-off frequency (build);
  - drive an LED with a current limit (build);
  - retune a provided voltage divider to a new output voltage (tune);
  - repair a provided broken amplifier (debug).
- **Checkers.** Each scenario's checker inspects the resulting circuit and measurements through the bridge's command-line mode ([C_MCB_03_04](./mcp-bridge.concept.md#C_MCB_03_04)), not the agent's own claims.
- **Models.** The set runs on at least two model sizes before a skill change ships.

### 3.5. Edge Cases  {#C_AGS_03_05}

| Case | Guidance |
|------|----------|
| The user's circuit has issues the user did not ask about | Report them; do not fix them unasked |
| The catalogue lacks the requested part | Say so, and propose the nearest available model or a subcircuit |
| A run hits its wall-clock budget | Shorten the span or increase the time step deliberately; never loop blindly |
| The measured value cannot reach the target with the chosen topology | Report the limit with evidence instead of tuning indefinitely |

## 4. Integration Points  {#C_AGS_04}

### 4.1. Dependencies  {#C_AGS_04_01}

- [C_AGA](./agent-api.concept.md) — the coordinate model, the issue codes, transactions and checkpoints.
- [C_MCP](./mcp-server.concept.md) — tool names and the catalogue and example resources.
- [C_MCB](./mcp-bridge.concept.md) — the command-line mode that runs the evals; distribution.

### 4.2. API Surface  {#C_AGS_04_02}

The skill is a directory that installs into an agent host's skill location, either by the host's skill install mechanism or by copying. It ships with the bridge, together with the host configuration snippets.

## Changelog

| Date | Change |
|------|--------|
| 2026-10-01 | Initial version from the spike and the concept interview |
| 2026-10-04 | C_AGS_03_01 step 10: a look at the rendered drawing (and fixing what it shows) comes before every report, not only on request — drawing defects found in the live agent series (PL_AGA Phase 15) |
| 2026-10-04 | C_AGS_02_01: the elements reference covers models and the patterns reference three model patterns (PL_AGA Phase 15 review) |
