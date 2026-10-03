# Circuit Authoring Skill — Specification  {#SP_AGS}

> **Code:** SP_AGS
> **Status:** draft
> **Created:** 2026-10-01
> **Updated:** 2026-10-03
>
> **Concept:** [C_AGS](./agent-skill.concept.md)
> **Depends on:** [SP_AGA](./agent-api.sp.md), [SP_MCP](./mcp-server.sp.md), [SP_MCB](./mcp-bridge.sp.md)
> **Used by:** —
> **Plan:** [agent-skill.plan.md](./agent-skill.plan.md)
>
> This specification defines the content contract of the `circuitjs-circuits` agent skill: directory layout, entry-file frontmatter and sections, required content of each reference file, the eval format and pass rule, and installation. Read it to write the skill, to review a skill change, or to keep it in step with a tool change.

## Contents

- [01. Data Structures](#SP_AGS_01) — directory layout, frontmatter, eval scenario format
- [02. Contracts](#SP_AGS_02) — required content of the entry file and each reference
- [03. Validation Rules](#SP_AGS_03) — size, naming, accuracy and consistency rules
- [04. State Transitions](#SP_AGS_04) — skill versioning against tool versions
- [05. Verification Criteria](#SP_AGS_05) — eval set, pass rule, consistency checks
- [06. Reversibility](#SP_AGS_06) — uninstall

## 01. Data Structures  {#SP_AGS_01}

> Implements: [C_AGS_02](./agent-skill.concept.md#C_AGS_02)

### 01_01. Directory layout  {#SP_AGS_01_01}

    circuitjs-circuits/
    ├── SKILL.md                     # entry (§02_01)
    ├── reference/
    │   ├── geometry.md              # §02_02
    │   ├── elements.md              # §02_03
    │   ├── diagnostics.md           # §02_04
    │   ├── patterns.md              # §02_05
    │   └── simulation.md            # §02_06
    ├── hosts/
    │   ├── claude-code.md           # connection snippets (§02_07)
    │   └── claude-desktop.json
    └── evals/
        ├── evals.json               # scenario list (§01_03)
        ├── fixtures/                # circuits used as starting points (JSON v2 or text)
        └── check.mjs                # checker runner using the bridge CLI

### 01_02. SKILL.md frontmatter  {#SP_AGS_01_02}

| Field | Type | Required | Constraints | Value |
|-------|------|----------|-------------|-------|
| name | string | yes | ≤ 64 chars, `[a-z0-9-]`, no vendor names | `circuitjs-circuits` |
| description | string | yes | ≤ 1024 chars, third person, states what and when | Builds, edits, simulates, measures and debugs circuits in the CircuitJS1 desktop simulator through its MCP tools (`circuit_*`). Load it before the first `circuit_*` call of any CircuitJS1 task — designing, drawing, simulating or measuring a circuit, and also changing component values of, retuning or repairing a circuit already open in a CircuitJS1 document, even a one-value change. Also use it for circuits in CircuitJS1 or Falstad format. |

### 01_03. Eval scenario  {#SP_AGS_01_03}

`evals.json` = `{version: string, scenarios: Scenario[]}`.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| id | string | yes | Kebab-case unique ID |
| kind | `"build"` \| `"tune"` \| `"debug"` | yes | Scenario class |
| prompt | string | yes | Task text given to the agent verbatim |
| fixture | string? | no | File under `fixtures/` loaded into a new document before the agent starts |
| checks | Check[] | yes | Evaluated after the agent finishes, through the bridge CLI |

Check (discriminated by `type`):
| type | Fields | Passes when |
|------|--------|-------------|
| connectivity_clean | — | `circuit_connectivity` of the agent's document has 0 `error` issues |
| element_count | `elementType`, `min`, `max?` | Count of that type within bounds |
| measure | `probe: ProbeSpec`, `run: {span, recordFrom?, maxPoints?}` (the checker always runs with `reset: true`, so `recordFrom` counts from t = 0), `stat` (a ProbeStats field), `compare: "approx" \| "atLeast" \| "atMost"` (default `approx`), `target: number`, `tolerance: number` (relative, for `approx`) | `approx`: `|stat − target| ≤ tolerance × |target|`; `atLeast`: `stat ≥ target`; `atMost`: `stat ≤ target` |
| checkpoint_exists | `minCount` | At least `minCount` agent checkpoints in history |
| no_solver_stop | — | `circuit_diagnostics.stopped = false` after the measure runs |

Document selection: a scenario with a `fixture` loads it into a new document whose handle the runner appends to the prompt ("The circuit is open in document <doc>."), and its checks read that document. A scenario without a fixture is checked against the most recently created document. Because the fixture prompt names the document, the golden rule "work in a new document unless told otherwise" holds.

## 02. Contracts  {#SP_AGS_02}

### 02_01. SKILL.md sections  {#SP_AGS_02_01}

> **Criticality:** critical

In this order, total ≤ 250 lines:

1. **Purpose and scope.** One paragraph on what the skill covers. Excluded: PCB layout, frequency-domain analysis.
2. **Golden rules.** At most 8 bullets:
   - coordinates in grid cells, whole cells for new parts;
   - work in a new document unless told otherwise;
   - an explicit ground in every circuit;
   - label every net you will probe;
   - no simulation while the connectivity report has errors;
   - measure, never assume;
   - checkpoint with a comment after each logical change;
   - never fix the user's unrelated issues unasked.
3. **Workflow checklist.** A copyable checklist of the ten steps of [C_AGS_03_01](./agent-skill.concept.md#C_AGS_03_01), each naming the tool it uses.
4. **Debug loop.** Read `circuit_connectivity` and `circuit_diagnostics`, look up the issue code in `reference/diagnostics.md`, fix, then re-check.
5. **Tool map.** A table of the 14 `circuit_*` tools and 3 `bridge_*` tools, each with a one-line use. Tools are named as `circuitjs:<tool>` for host-qualified references.
6. **References index.** One line per reference file saying when to read it.

### 02_02. reference/geometry.md  {#SP_AGS_02_02}

It must contain:
- **Units and axes.** The cell unit, the half-cell lattice, and the axis directions (x right, y down).
- **Defining points.**
  - `single` elements: one point.
  - `two_point` elements: `start`/`end` are the posts.
  - `derived` elements: posts are computed. Three worked examples, each with the resulting `posts` from an `applyEdits` reply: a horizontal NPN transistor, an op-amp, an 8-pin chip.
- **Grid-sized parts.** Catalogue sizes and derived pin offsets describe standard-grid documents. In a small-grid document some parts (potentiometer, SCR, triac, tapped transformer, transmission line, wattmeter, real op-amp rails) place their posts differently. Always take post positions from the `posts` of the `circuit_edit`/`circuit_get` reply before wiring ([SP_AGA_03_01](./agent-api.sp.md#SP_AGA_03_01)).
- **Connection rule.** Only coincident posts connect. A post on a wire body does not connect. Split the wire at the junction instead.
- **Labels.** A same-named label joins nets. Label names become net names.
- **Layout style.**
  - Signal flows left to right.
  - The supply rail sits at the top and ground at the bottom.
  - Parts are 3–4 cells long, with 2-cell spacing between parallel branches.
- **Worked example.** One complete AgentCircuit (an RC low-pass), with every coordinate shown.

### 02_03. reference/elements.md  {#SP_AGS_02_03}

- **Table.** The most-used types (the required list below, 26 types), one row each: type, pins, geometry kind, default size, key properties with units, and one typical value.
- **Required types.** The table must include the canonical catalogue names (aliases noted) of: resistor, capacitor, inductor, wire, ground, DC and AC voltage sources (`VoltageSourceDC`, `VoltageSourceAC`), current source, diode, LED, Zener (`ZenerDiode`, alias `Zener`), NPN/PNP transistors (`TransistorNPN`, `TransistorPNP`), N/P MOSFETs (`NMOS`, `PMOS`, aliases `MosfetN`, `MosfetP`), `OpAmp`, `Switch`, `Potentiometer`, `LabeledNode`, a rail, `LogicInput`, `LogicOutput`, AND/OR gates (`ANDGate`, `ORGate`, aliases `AndGate`, `OrGate`), `Inverter`, `Timer555`. `element_count` checks use canonical names only. Every name is checked against `circuitjs://catalogue` ([§05_03](#SP_AGS_05_03)), and the canonical name is the one `describeType` returns.
- **Pointer.** A closing line points to `circuitjs://catalogue` for every other type and for exact property lists.

### 02_04. reference/diagnostics.md  {#SP_AGS_02_04}

- **One row per code.** Every issue code in [SP_AGA_03_05](./agent-api.sp.md#SP_AGA_03_05) and [SP_AGA_03_06](./agent-api.sp.md#SP_AGA_03_06) gets a row with:
  - code;
  - severity;
  - typical cause in this simulator;
  - fix steps;
  - the tool call that confirms the fix.
- **Symptoms section.** Covers symptoms that carry no issue code:
  - flat 0 V trace;
  - an oscillator that never starts;
  - a result that changes with the time step;
  - a slow run.

### 02_05. reference/patterns.md  {#SP_AGS_02_05}

- **Patterns.** At least 8: divider, RC low-pass, RLC resonance, half-wave rectifier with filter, transistor common-emitter stage, op-amp inverting and non-inverting stages, 555 astable, logic gate with LED.
- **Contents of each pattern:**
  - an AgentCircuit in cells;
  - the expected measurements, with formulas;
  - the probes to set.

### 02_06. reference/simulation.md  {#SP_AGS_02_06}

- **Time step.** The maximum time step should be at most 1/50 of the smallest RC or L/R constant and at most 1/(50·f) of the highest frequency of interest.
- **Run spans.** Run at least 5 τ before measuring settled values. Use `recordFrom` to skip start-up transients.
- **Operating point.** Use `mode: "settle"` to find it.
- **Measurements from probe stats.** How to derive:
  - frequency and duty from `frequency`/`dutyCycle`;
  - gain from the ratio of peak-to-peak values;
  - ripple from the peak-to-peak value at the output;
  - rise time from `riseTime`.
- **Budgets.** How to choose `budgetMs`, and what to do after `budget_exhausted` and `settle_timeout`.

### 02_07. hosts/  {#SP_AGS_02_07}

- **`claude-code.md`.** Covers:
  - the direct HTTP command (`claude mcp add --transport http circuitjs http://127.0.0.1:7311/mcp`);
  - the bridge alternative (`claude mcp add circuitjs -- circuitjs-mcp --launch`);
  - installing the skill directory into the skills location.
- **`claude-desktop.json`.** An `mcpServers` entry `{"circuitjs": {"command": "circuitjs-mcp", "args": ["--launch"], "env": {"CIRCUITJS_APP": "<absolute path of the app executable>"}}}` — Claude Desktop does not inherit shell environment variables.

## 03. Validation Rules  {#SP_AGS_03}

### 03_01. Size and form  {#SP_AGS_03_01}

- `SKILL.md` ≤ 250 lines; each reference ≤ 400 lines; a reference over 100 lines starts with a table of contents.
- References are linked only from `SKILL.md` (one level deep).
- Every coordinate in examples is on the half-cell lattice, and every example circuit uses whole cells only.

### 03_02. Accuracy  {#SP_AGS_03_02}

> **Criticality:** critical

- Every type, pin and property key named in the skill exists in `circuitjs://catalogue` of the matching app version.
- Every AgentCircuit in the skill imports with `ok = true` and `connectivity.errorCount = 0`.
- Every issue code named exists in SP_AGA or among the server issue codes of SP_MCP (`result_too_large`), and every SP_AGA issue code appears in `diagnostics.md`.
- Every tool and argument named exists in the SP_MCP tool catalogue.

## 04. State Transitions  {#SP_AGS_04}

### 04_01. Skill version  {#SP_AGS_04_01}

The skill carries a version `MAJOR.MINOR` in `evals/evals.json` and a compatibility line in `SKILL.md` ("works with toolsVersion x.y", [SP_MCP_06_01](./mcp-server.sp.md#SP_MCP_06_01)).

| Event | Effect |
|-------|--------|
| Tool or argument renamed/removed (server breaking change) | Skill MAJOR+1; compatibility line updated; all evals re-run |
| New tool, new issue code, new property | Skill MINOR+1; references updated; consistency checks re-run |
| Wording change only | No version change; consistency checks re-run |

## 05. Verification Criteria  {#SP_AGS_05}

### 05_01. Eval set  {#SP_AGS_05_01}

| id | kind | Prompt (summary) | Checks |
|----|------|------------------|--------|
| rc-lowpass-1k | build | Build an RC low-pass filter with a 1 kHz cut-off, driven by a 1 V-amplitude sine at 1 kHz, output net labelled `out` | connectivity_clean; element_count Resistor ≥ 1, Capacitor ≥ 1; measure net `out` peakToPeak approx 1.414 V (tolerance 0.1, recordFrom 5 ms, span 10 ms); checkpoint_exists ≥ 1 |
| led-driver-10ma | build | Drive a red LED from 5 V with 10 mA | connectivity_clean; measure the LED's current final approx 0.010 A (tolerance 0.15, span 10 ms); no_solver_stop |
| fix-broken-amp | debug | Fixture: common-emitter amplifier (input 0.1 V peak-to-peak at 1 kHz, output net `out`) with a dangling base resistor and no ground; make it amplify | connectivity_clean; measure net `out` peakToPeak atLeast 0.5 V (recordFrom 10 ms, span 20 ms); no_solver_stop |
| tune-divider | tune | Fixture: divider giving 3.0 V from 9 V at net `out`; change it to give 3.3 V within 2 % | measure net `out` final approx 3.3 V (tolerance 0.02, span 1 ms); checkpoint_exists ≥ 1 |

### 05_02. Pass rule  {#SP_AGS_05_02}

- **Pass.** A scenario passes when all its checks pass.
- **Releasable.** A skill change is releasable when every scenario passes in at least 2 of 3 runs, on each of at least two model sizes.
- **Recording.** Results are recorded per skill version in `evals/results.md`.

### 05_03. Consistency checks  {#SP_AGS_05_03}

| Check | Method |
|-------|--------|
| Catalogue names | Script extracts type/pin/property names from the references and verifies them against `circuitjs-mcp read circuitjs://catalogue/<type>` |
| Example circuits | Script imports every AgentCircuit block through `circuitjs-mcp call circuit_import` into a scratch document and asserts `ok` and zero errors |
| Issue code coverage | Script diffs codes in `diagnostics.md` against the SP_AGA code list |
| Tool coverage | Script diffs tool names in `SKILL.md` against `circuitjs-mcp tools` |

### 05_04. Edge Cases and Boundaries  {#SP_AGS_05_04}

| Case | Expected behavior |
|------|-------------------|
| App older than the compatibility line | The skill tells the agent to compare the server's `toolsVersion` (in the server instructions and instance record) with its compatibility line, and to report a mismatch instead of guessing tool names |
| Host without the bridge and without HTTP support | The skill tells the user how to connect (hosts/ files) and stops |

### 05_05. Integration Scenarios  {#SP_AGS_05_05}

| Scenario | Preconditions | Steps | Expected result |
|----------|--------------|-------|-----------------|
| Claude Code over HTTP | App running; skill installed; server added with `claude mcp add --transport http` | Ask for the RC low-pass of `rc-lowpass-1k` | The skill triggers; the agent works in a new document; checks of `rc-lowpass-1k` pass |
| Claude Desktop over the bridge | Desktop config from `hosts/claude-desktop.json`; app not running; `CIRCUITJS_APP` set | Ask for the LED driver | The bridge launches the app; checks of `led-driver-10ma` pass |
| Eval runner | App running; bridge on PATH | `node evals/check.mjs` after an agent session | Per-scenario pass/fail JSON; exit 0 only when all pass |

## 06. Reversibility  {#SP_AGS_06}

### 06_01. Rollback Strategy  {#SP_AGS_06_01}

| Aspect | Rollback approach |
|--------|-------------------|
| Data/state changes | None |
| Artifacts | The skill directory in the host's skills location; removing it uninstalls the skill |
| Dependent modules | None |
| External contracts | The skill name `circuitjs-circuits` is how hosts trigger it; renaming is a MAJOR change |

## Changelog

| Date | Change |
|------|--------|
| 2026-10-01 | Initial version |
| 2026-10-03 | PL_AGS Phase 4: description widened to open-circuit edits and "load before the first `circuit_*` call" (eval round 1–2: agents skipped the skill on one-value changes and missed the checkpoint) |
| 2026-10-02 | PL_AGS Phase 1: the elements table covers the required list (26 types) instead of "the 25 most-used" |
| 2026-10-01 | Review round 2: canonical names, checker reset, desktop env snippet |
| 2026-10-01 | Review round 1: fixture document selection, canonical type names, toolsVersion compatibility, integration scenarios |
