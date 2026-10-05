# Spike: A language for describing circuits, running simulations and measuring at circuit points

> **Status:** concluded
> **Created:** 2026-10-02
> **Updated:** 2026-10-02
> **Author:** main
> **Time-box:** 1 session; three parallel sub-question researchers; ≤15 external sources in total (spent: 15); one throughput measurement against the existing `target/site` build (no rebuild, no source edits)
> **Scope:** codebase + external + prototype (measurement only, scratch area)
> **Mode:** single (fanned out by sub-question, not by lens)
>
> **Target concept:** to be created (relates to epic [E_AGT](./agent-automation.epic.md): the language would run on the operations of [C_AGA](./agent-api.concept.md))
> **Serves:** —
> **Question(s):**
> 1. **Prior art and features.** Which language forms exist for "describe a circuit → run analyses → measure at points" (SPICE netlists with dot-commands and control scripts, Python-embedded DSLs, acausal modelling languages, HDL-AMS, JS-based tools), and which of their features serve the three use cases — checking a concept, selecting component values (sweeps, optimization), and verifying stability (tolerances, Monte Carlo, worst case, settling, oscillation, long runs)? Which forms do people and LLMs write reliably?
> 2. **Engine and API fit.** What can the CircuitJS1 engine and the Agent API ([C_AGA](./agent-api.concept.md), PL_AGA Phases 0–8) support as the runtime of such a language, which gaps block each use case (measurements, sweeps, randomness, determinism, speed, frequency-domain figures, temperature), and how many runs per minute does a sweep get?
> 3. **Host and syntax.** Which host and syntax fit this project — a JS/TS library over the Agent API, a Python client over MCP, a custom text DSL parsed in Java as a circuit format plus a script runner, a SPICE-netlist subset mapped to CircuitJS elements, or an embedded interpreter — under the runtime constraints (GWT single thread, NW.js 0.64.1 / Node 18.0.0 / Chromium 101, no shipped `node_modules`, layering and JSNI-clustering rules)?

## Context

The developer wants a programming language in which a circuit is described and simulated, with diagnostics at chosen points of the circuit: voltage, current, frequency, waveform shape and other figures. The stated uses are checking a concept, selecting element parameters, and verifying that a circuit works stably. Today a circuit is drawn in the editor or loaded from a text/JSON file, and measurement is visual (scopes, meters). Epic [E_AGT](./agent-automation.epic.md) is building a transport-free operation set for AI agents ([C_AGA](./agent-api.concept.md)): grid-cell edits, stable element IDs, a connectivity report, bounded runs with probes and statistics, diagnostics and checkpoints. A language could be a new authoring surface over those operations, a separate format, or something else. Before a concept can be written, the solution space, the engine's limits for these use cases and the hosting options must rest on verified facts. Who writes the scripts (the user, an AI agent, or both) is a decision input this spike does not settle.

## Exploration Log

<!-- One entry per sub-question, written by the main agent from each researcher's returned conclusion. -->

### Entry 1 — 2026-10-02 — Q1: prior art and features

**What was tried / researched:** ngspice manual (47+), LTspice XVII help (LTwiki mirror: `.meas`, `.step`, `.four`, `.temp`, `.param`), Xyce Reference Guide 7.10, PySpice and spicelib READMEs, tscircuit simulation docs, the SPICEBridge MCP server README, and two LLM studies (AnalogCoder, NetlistBench). 9 sources relied on. Not covered in the box: Modelica, VHDL-AMS, ModelingToolkit, SKiDL, Lcapy, ahkab, InSpice, EEcircuit.

**Findings:**
- **Six forms exist:**
  - (i) a declarative netlist with analysis and measure directives (SPICE);
  - (ii) the same with an embedded imperative layer (ngspice `.control … .endc`);
  - (iii) a host-language library or orchestrator that emits or edits netlists (PySpice, spicelib);
  - (iv) a declarative component tree with experiment nodes (tscircuit TSX);
  - (v) tool-call surfaces with no language at all (SPICEBridge MCP);
  - (vi) device-model HDLs (Verilog-A), which describe compact models, not test benches.
- **The SPICE family shares one measurement vocabulary** (ngspice §11.4, LTspice DotMeasure, Xyce §2.1.18):
  - `TRIG … TARG` (delay, period);
  - `FIND … AT|WHEN` and `WHEN` (value at an event);
  - `AVG|MIN|MAX|PP|RMS|INTEG|DERIV` over a window `TD/FROM/TO`;
  - edge selectors `RISE|FALL|CROSS=n|LAST`;
  - derived measures (`PARAM`/`EQN`) that reuse earlier ones.
  - Example: `.measure tran tdiff TRIG v(1) VAL=0.5 RISE=1 TARG v(1) VAL=0.5 RISE=2`.
  - Xyce adds transient-only `FREQ` (cycle counting), `DUTY`, `ON_TIME/OFF_TIME`, `FOUR … AT=freq`, and `.FFT` with `THD/SNR/SFDR/ENOB`. A measure that cannot be evaluated prints `FAILED` (or a `DEFAULT_VAL`), never a silent number.
  - LTspice evaluates measures after the run, over the stored, compressed waveform, which bounds their accuracy.
- **Sweeps, Monte Carlo, worst case and optimization are outer loops around a run:**
  - **Sweeps.** LTspice `.step` sweeps a source, parameter, model parameter or temperature (lin/oct/dec/`LIST`), nests at most 3 levels, and tabulates `.meas` results per step. Xyce has `.STEP`, including `DATA=` tables. **ngspice has no `.step`.** Its manual replaces `.STEP R1 1k 10k 1k` with a `.control` `while` loop of `alter r1 r_act` + `run` (verified, §12.11.4.3).
  - **Monte Carlo.**
    - ngspice draws `agauss/gauss/unif` once at parse time, so MC needs a loop that re-draws and calls `alter` (its ring-oscillator example histograms a per-run FFT peak).
    - LTspice has `mc(x,tol)`, `flat()` and `gauss()`, driven by `.step param run 1 N 1`.
    - Xyce has a first-class `.SAMPLING` (MC or LHS, `SEED`, statistics over named measures) and `.SENS` sensitivities.
  - **Worst case.** Done as 2^n tolerance corners (spicelib `WorstCaseAnalysis`; 512 runs for 9 parts) or from sensitivities.
  - **Optimization.** Outside the language everywhere: "ngspice does not have an integral optimization processor" (verified, ch. 19). External tools consume specs such as `phase_margin:VOUT:GE:51.8`.
- **In-deck checks.** ngspice computes pass/fail as a derived measure (`param='(tdiff < lim) ? 1 : 0'`) and checks per-device safe-operating-area limits during a run (`.option warn=1`, model `Id_max/Pd_max/Bv_max`). SPICEBridge's `compare_specs(id, {"f_3dB_hz": {"target": 1000, "tolerance_pct": 5}})` → PASS is the same idea as a tool call.
- **Host-language libraries fill the gaps of the directives.**
  - PySpice gives an object API over ngspice/Xyce with numpy results and units. Its last production release was in 2021.
  - spicelib/PyLTspice runs batches in parallel, does N-dimensional sweeps (lifting LTspice's 3-level limit), and provides Monte Carlo and worst-case test-bench generators. Its author lists the needs that `.meas` does not meet: calculations beyond `.MEAS`, cross-run correlation, small result files.
- **tscircuit** treats an experiment as a node of the component tree: `<analogsimulation duration="10ms" timePerStep="0.1ms"/>` plus `<voltageprobe connectsTo=".R1 > .pin1"/>` and `<ammeter>`, with several named experiments per board. Its output is scope graphs; the fetched pages show no numeric measures or sweeps.
- **Syntax hazards of SPICE** (ngspice §2.1.3.3):
  - `m` is milli and `Meg` is mega.
  - Letters after a number are ignored, so `4Farads` is 4 femtofarads.
  - Dialects diverge: ngspice carries ps/hs/lt/spectre/kicad compatibility modes.
- **LLM reliability evidence is thin and mixed:**
  - **AnalogCoder** (arXiv 2405.14918; 24 transistor-level tasks; GPT-3.5 ablation, verified in Table 4).
    - Python + PySpice reached Pass@1 21.4 (10 tasks solved) against 13.9 (9 solved) for an ngspice netlist.
    - Removing the check-and-retry flow drops it to 12.8, and removing the in-context example to 8.1.
    - The flow rejects floating nodes, inactive transistors and missing DC paths before simulating.
  - **NetlistBench** (arXiv 2608.12197; 2,342 cases; six LLMs).
    - Local parameter edits pass 96–100 % and device addition 41–83 %.
    - Terminal connectivity ranges 6–99 %.
    - Chained dependent edits collapse (one model: 80 % at 3 edits, 26 % at 15).
    - Failures are structural, not syntactic.
    - SPICE → PySpice output gave a smaller, inconsistent gain than reasoning did (+30–40 points).
  - No source measures LLMs writing measure, sweep or control scripts.
  - Best supported by the evidence:
    - a mainstream host language;
    - named nets;
    - a verified example to start from;
    - short local edits;
    - automatic structural checks after every change.
  - Writing a topology is the weak zone. Setting parameters and declaring experiments is the reliable zone (an inference, not measured).

**Feature × use case** (a = concept check, b = parameter selection, c = stability):

| Feature | Use | How the prior art expresses it |
|---|---|---|
| Run + key figures | a | `.tran 10u 5m` + `.meas tran ypp PP v(1) FROM=2m TO=4m`; PySpice `transient()` → arrays |
| Timing and windows (skip start-up) | a, c | `TRIG/TARG`, `FIND … WHEN`, `TD= FROM= TO=` |
| Derived values, pass/fail specs | a, c | ngspice `param='(x<lim)?1:0'`; LTspice `PARAM`; Xyce `EQN`; `compare_specs` |
| Sweep, nested | b | LTspice `.step param RLOAD LIST 5 10 15`; Xyce `.STEP DATA=`; ngspice `while` + `alter`; spicelib `SimStepper` |
| Optimization / goal seek | b | none built in; external optimizers over specs; SPICEBridge `auto_design` with E24 snapping |
| Monte Carlo | c | LTspice `mc(x,tol)` + `.step param run`; Xyce `.SAMPLING … SEED`; ngspice `agauss` + loop + `setseed` |
| Worst case | c | 2^n corners via `.step param run`; Xyce `.SENS`; spicelib `WorstCaseAnalysis` |
| Temperature | c | LTspice `.step temp -55 125 10`; Xyce `.STEP TEMP`; ngspice `.dc TEMP` |
| Frequency figures from transient | a, c | Xyce `.MEASURE TRAN f FREQ v(out)`; `.four 1kHz V(out)`; ngspice `fft`/`fourier` over the settled window |
| Needs AC analysis | c | `.ac`, `.noise`, `.tf`, `.pz`; bandwidth via `.MEAS AC … mag(V(out))=max/sqrt(2)`; phase margin |
| Early stop on a condition | c | ngspice `.option autostop`, `stop when v(1)>4` |

Without AC, the sources give building blocks rather than a recipe: windowed Fourier/FFT of the settled waveform, cycle counting, and `TRIG/TARG` periods. Swept-sine Bode, overshoot/settling from a step response, and injection-based loop gain are standard practice composed from the same loop-and-measure primitives (not verified in a fetched source).

**Open questions:**
- How reliably LLMs write measure/sweep scripts in a given syntax — unmeasured; a small in-house eval would settle it.
- LTspice worst-case practice beyond the corner pattern (the vendor page refused the fetch).

---

### Entry 2 — 2026-10-02 — Q2: engine and Agent API fit

**What was tried / researched:**
- **Code read:**
  - the Agent API code under `client/agent/` (operations, `RunController`, `Readings`, `ProbeRecorder`, `EditOps`, `Slices`);
  - the solver (`CircuitSimulator`), diode and transistor models, `RandomUtils`;
  - the time-step controls (`ControlsDialog`, `Scrollbar`, text import, tab activation);
  - the FFT and scope-metric code.
- **Measured** against the existing `target/site` build through the live harness `eval` scenario, page-side:
  - The build is draft-compiled, and the browser was headless Chromium 153 (newer than NW.js's Chromium 101).
  - Runs went to a background document.
  - MCP transport overhead is unobserved.

**Findings:**
- **The Agent API already runs use case (a) and, with a client-side loop, use case (b):**
  - 21 operations are registered (`AgentApi.java:115-125`), reached through `CircuitJS1Agent.call`/`callAsync`.
  - `run` advances a `span` or runs until `settle` (every net's max−min below a tolerance over a window).
  - Its options are `reset` and `recordFrom`.
  - Its caps are ≤ 16 probes, Σ `maxPoints` ≤ 2000 and a wall-clock budget of 100 ms to 120 s.
  - It ends with `span_reached`, `settled`, `settle_timeout`, `solver_stop`, `budget_exhausted` or `cancelled`.
  - `openFile`/`saveFile` are still Phase 9 work.
- **Probe quantities are limited:**
  - A probe reads a net, a post, or an element's voltage, current or power (`Readings.java:53-97`). There are no differential or expression probes.
  - The statistics are computed in the app over every step (`ProbeRecorder.java:106-136`):
    - samples, min, max;
    - time-weighted mean and RMS (RMS includes DC), peak-to-peak, final;
    - frequency (mean crossings with 5 % hysteresis);
    - duty cycle;
    - rise time (10–90 % of the observed min→max, so it is biased while the window has not settled).
  - The returned series is min/max-bucket decimated, not uniformly sampled, so a client cannot compute an FFT, THD or settling time from it.
- **Parameter change → fresh run** is two calls:
  - `applyEdits [{op: "set", id, properties: {resistance: "4.7k"}}]` is atomic, re-analyses, and reports clamps as `value_adjusted`.
  - Then `run {reset: true}`.
  - A run without `reset` continues from the current state.
  - The first probe sample after an import or edit without `reset` reads unsolved nodes as 0 V (`RunController.java:363-364`, observed).
- **Determinism (observed).** Repeated runs with `reset: true` and the same span were bit-identical.
  - Circuits covered: RC, relaxation oscillator, buck converter, class-D, 555, two oscillators, a 20-BJT op-amp, and a VCVS loop driven into step halving and forced steps.
  - It held across visible tab stopped vs free-running (different slice boundaries), fixed vs adaptive step, and two browser sessions.
  - Exceptions:
    - Noise sources draw from one static, unseeded `java.util.Random` (`RandomUtils.java:7`, verified), so they are not reproducible.
    - The op-amp and gate elements also draw from it inside `doStep` (`OpAmpElm.java:211,214`, `GateElm.java:271`; no effect observed).
    - `goodIterations` is reset on every `stepLoop` call (`CircuitSimulator.java:1745-1748`), so step regrowth after a halving could depend on where a slice ends (not exercised).
    - A fresh import, which starts from the state saved in the file, differs from a reset, which starts from initial conditions. A relaxation oscillator oscillates at 113 Hz from the file state, but after a reset its capacitor reached only 0.149 V in 20 ms.
- **Engine limits:**
  - **Analysis types.** Transient only, with no AC, noise or DC-sweep code. The UI's "Find DC Operating Point" sets a flag that is likely cleared before the stamp reads it (unverified), and it is not in the API.
  - **Time step.** A fixed `maxTimeStep` that halves only when Newton fails and regrows after 3 good steps (`CircuitSimulator.java:1891-1901`, verified). There is no truncation-error control and there are no source breakpoints, so accuracy rests on the chosen step alone. Matrices are dense, and nonlinear circuits re-factor them on every Newton iteration.
  - **Numerical failure.** The solver recovers instead of stopping, through gmin/shunt panic levels and then forced steps. A run reports `convergence_failed` once, plus `solver_stop` and a `recovering` flag. A degraded waveform can therefore look like circuit behaviour.
  - **Temperature.** Fixed at 27 °C (`Diode.java:67-68` `vt = 0.025865`, verified; `DiodeModel.java:33`, `TransistorElm.java:275`). Only element-level knobs exist (NTC, lamp filament).
  - **Tolerances.** No element or file-format code has any.
  - **Time-step quantization (defect candidate).**
    - Text import and tab activation both call `Scrollbar.setValue`, which fires the slider command. That command rewrites `maxTimeStep` to the nearest entry of a 1-2-5 table running from 1 ps to **10 µs** (`ControlsDialog.java:19-28,44-52,104-111`, `TextCircuitImporter.java:381-382`, `CircuitDocument.java:748`, `Scrollbar.java:311-319`, verified).
    - Observed: 15.625 µs became 10 µs on import, and a configured 1 ms became 10 µs on tab activation.
    - Slow or long-span circuits are therefore forced onto a ≤ 10 µs step unless `configure` is called after import and the tab is not activated afterwards.
  - **Stop triggers.** `StopTriggerElm` only clears the free-run flag (`StopTriggerElm.java:100`, verified), so it does not end an agent run.
  - **A bundled example fails.** `ujtosc.txt` stops at analysis with "Exception in UNI1.stamp(): TypeError" (observed).
- **Reusable pieces:**
  - a radix-2 `FFT.java` (package-private, used by the scope);
  - scope metrics in `CircuitMath.java:190-375`, which are tied to scope ring buffers;
  - expression sources (`sin(2*pi*f*t)`) and `SweepElm` (chirp) as swept-sine stimuli.

**Throughput** (observed, page-side, draft compile; MCP overhead not included):

| Circuit | Steps/s (other tabs stopped / visible tab free-running) | Per run | 20-point sweep (edit + reset run) |
|---|---|---|---|
| RC, 5 elements, 5τ = 1001 steps | 60k / 39k | 27–36 ms | 0.67 s (~1780 runs/min) |
| Relaxation oscillator, ideal op-amp, 2001 steps | 19k | 107–129 ms | ~500 runs/min (estimated) |
| Buck converter, MOSFET + diode, 2001 steps | 9.3k / ~5k | 217–270 ms | 5.0 s (~240 runs/min) |
| 20-BJT op-amp, 107 elements, 5000 steps | 1.5k / 0.93k | 3.3 s | 68 s (~18 runs/min) |
| Non-converging VCVS loop | ~85 | — | — |

Fixed costs are ~8 ms per `run` call and 3.5–31 ms per `applyEdits`. Concurrent runs in one app instance take turns on one thread, so there is no parallel speedup. A free-running visible tab cuts throughput to 50–65 %. Non-convergence slows a run 100× or more.

**Gaps per use case** (a = concept check, b = parameter selection, c = stability; S/M/L = rough size):

| Gap | Use | Level | Size |
|---|---|---|---|
| Measures: overshoot, settling time, fall time, delay/period, value at/when, AC-RMS | a, c | API | S–M |
| Two-probe and derived measures: differential, gain/phase, impedance, efficiency | a, b | API | M |
| Frequency figures (FFT, THD, Bode by swept sine) computed in-app at full resolution | a, b | API (reuse `FFT`) | M |
| Sweep/batch primitive (today 2 calls per point, client loop) | b, c | API | M |
| Initial-state control: start from file state, reset, or a saved state snapshot to fork runs | b, c | API | M |
| User stop conditions (threshold crossed, N cycles) | a, c | API | S–M |
| Per-run quality fields (forced steps, maximum panic level, minimum step) | c | API | S |
| Seeded randomness for noise, op-amp and gate draws; tolerance draws done client-side by `applyEdits` | c | engine S / client | S |
| Time-step quantization on import and activation | all | app shell | S |
| First sample after an un-reset run reads 0 V | a | API | S |
| DC operating point, AC, noise, DC sweep | a, b, c | engine | L |
| Temperature models | c | engine | L |
| Accuracy control (truncation error, breakpoints) | a, c | engine | L |
| Parallel or headless runner | b, c | engine/shell | L |

**Open questions:**
- Does a minimized or hidden NW.js window throttle the slice timers (no `--disable-background-timer-throttling` in the manifest)?
- How much faster is a production (non-draft) compile, and how fast is V8 in Chromium 101?
- What is the per-call MCP latency?
- Is "Find DC Operating Point" broken?
- Could runs fan out across several app instances (each is its own process, with the per-instance server of [C_MCP_DEC_01](./mcp-server.concept.md#C_MCP_DEC_01)) for parallel sweeps? (unverified)

---

### Entry 3 — 2026-10-02 — Q3: host and syntax

**What was tried / researched:**
- **Codebase:**
  - the Agent API JS boundary;
  - the MCP server and bridge documents and their plan status;
  - the format registry and the text-format probe;
  - the agent import path;
  - the JSON importer;
  - the expression engine;
  - SPICE-primitive coverage of the element factory;
  - the labelled-node element.
- **External (6 sources):**
  - npm metadata for fengari, quickjs-emscripten and pyodide;
  - the Pyodide, quickjs-emscripten and fengari docs;
  - the NW.js security pages;
  - the Python `mcp` package.

**Findings:**
- **The JS boundary is the Agent API's single entry point.**
  - `window.CircuitJS1Agent.call(op, argsJson)` returns a JSON string.
  - `run` and `render` work only through `callAsync`; a synchronous call is refused (`AgentJsBridge.java:62-72`, `AgentApi.java:205-214`, verified).
  - A user edit cancels a run on the visible document (SP_AGA §04_02), so any script host should work in its own background document.
- **The transports outside the page are not built yet.**
  - The MCP server has only its Phase-0 hosting prototype done (PL_MCP Phases 1–5 open, verified).
  - The bridge/CLI `circuitjs-mcp` (Node ≥ 20, ESM + JSDoc, `call <tool> <json|->`) exists only as documents.
  - Today an outside process reaches the API only over the Chrome DevTools Protocol, as `tests/live/harness.mjs` does.
- **Security boundary.**
  - The in-app server is always on, reachable from the private network and unauthenticated. Its stated mitigation is "no code-execution tool", together with an Origin check and circuit-file-only file access ([epic risk 4](./agent-automation.epic.md), SP_MCP_DEC_03).
  - The page has full Node privileges and no CSP.
  - Any host that executes script text inside the page creates a code-execution path once that text can arrive through the server, or inside a circuit file opened by the user ("RCE on open").
- **Format registry.**
  - Formats are registered text first, then JSON (`CircuitFormatRegistry.java:40-44`, verified).
  - The first `canImport` that answers yes wins, and the text probe accepts any first line starting with a letter or digit (`TextCircuitImporter.java:108-130`, verified).
  - So a new netlist format must register before text and have a strict probe.
  - Agent import only checks for a leading `{` (`ImportOps.java:103`, verified), so a third format is not reachable by agents without a change there.
  - `ImportReport` items carry a line number, but `ImportOps.toIssue` drops it.
- **The expression engine cannot be grown into a script language.**
  - `ExprParser` parses one numeric expression: its input is lower-cased (`ExprParser.java:301`, verified), identifiers are letters only, and SI suffixes (`1k`) are not accepted.
  - Its names are fixed, and its errors are a single string without a position.
  - It serves the expression-driven elements; changing it risks them.
- **SPICE coverage.** No SPICE import or export exists. The factory has 176 keys over 140 canonical types.
  - R, C and L map directly, including initial conditions.
  - Sources take amplitude, offset, frequency, phase and duty only: no delay, damping, rise/fall or PWL (the workaround is an `expression` source).
  - The diode model has IS, RS, N and BV; the BJT a Gummel-Poon subset with no RB/RC/RE/CJ/TF; MOSFET and JFET only threshold and beta.
  - E/G/F/H map to `expression` sources, with no POLY.
  - **The JSON format carries no model definitions** (`JsonCircuitImporter.java:105-114`, verified). Diode, transistor and subcircuit models exist only in the legacy text format and in session-global model maps.
  - Number syntax conflicts: SPICE is case-insensitive with `M` = milli and `MEG` = mega, while the app's unit parser reads `M` as mega. A SPICE front end needs its own number parser.
- **Net-to-geometry.**
  - A `LabeledNode` stub at every pin joins nets by name with no wires. The Agent API already reads nets by label, reserves `gnd`, and flags a label used once (`single_label`), which catches typos for free.
  - Pin offsets come from `describeType` (`derivedPostsAtDefault`), so a layout needs no round trips.
  - The picture is a symbol, a short stub and a net name at every pin; 6 of the ~340 bundled examples already look like this.
  - Geometry matters only when the language *describes* a circuit. A test bench that drives a circuit drawn in the editor (by element ID and net label) needs no layout at all.
- **Embedded interpreters inside the page** (each still needs an editor, run/stop control and results UI, none of which exists):
  - **Plain JS in a frame with Node disabled** (`nwdisable`): isolation of `sandbox` frames and Workers is undocumented, and historic bypass reports exist (titles only).
  - **QuickJS compiled to WebAssembly** (quickjs-emscripten 0.32.0, ~0.5 MB): memory, stack and deadline limits, and a stated minimum of Chrome 63. Unverified on Chromium 101. CPU-bound loops block the UI thread.
  - **Lua** (fengari 0.1.5, 1.6 MB): coroutines can yield at `run`, but `io` and `os.execute` appear when Node is detected, so it needs a restricted environment. There was a 2018–2025 release gap.
  - **Pyodide** (314.x, 13.9 MB): docs test Chrome ≥ 112, while NW.js 0.64.1 is Chromium 101. Blocked without a runtime upgrade.
- **A Java (GWT) interpreter** has no threads or coroutines, so `run` callbacks need a continuation or state machine. Without JUnit, its parser can be tested only through the compiled live harness (minutes per cycle), while Node-side code tests with `node --test` in milliseconds.

**Open questions:**
- Is a bounded declarative bench (no I/O, finite loops) "code execution" under the server's mitigation?
- Which channel carries model definitions (a JSON `models` section or an Agent API operation)?
- Does an MCP SDK v2 client work against the in-app 2025-11-25 server?
- Do QuickJS/fengari load on NW.js 0.64.1-mod1, and are frame and Worker isolation effective there? (unverified)

## Alternatives Considered

The three questions converge on one structural finding: a "circuit language" here is three separable layers. Each layer has its own alternatives.
- **Circuit description:** how the circuit is given.
- **Experiment:** stimulus, run, probes, measures, sweeps, Monte Carlo, specs.
- **Measurement engine:** where the figures are computed.

**A. Measurement engine — where figures are computed**

| # | Approach | Pros | Cons | Verdict |
|---|----------|------|------|---------|
| A1 | In the app, next to the solver: extend the probe recorder with SPICE-style measures (`TRIG/TARG`, `FIND … WHEN`, windowed `AVG/RMS/PP`, edges, derived values, `FREQ/DUTY`, `FAILED`), two-probe and derived quantities, and windowed FFT/THD | Full resolution at every step; one implementation for every host; small results under the MCP output cap | Java work in the agent layer | chosen-candidate (every host depends on it) |
| A2 | In the client, from returned series | No app work | The series is min/max-bucket decimated, ≤ 2000 points, not uniform: FFT, THD, settling and delays are wrong or impossible; raising the cap breaks the output budget | rejected |

**B. Experiment layer — who runs the loops (sweeps, Monte Carlo, corners, optimization)**

| # | Approach | Pros | Cons | Verdict |
|---|----------|------|------|---------|
| B1 | Host-language loop over `applyEdits` + `run {reset}` | Works today; any algorithm (goal seek, optimizers, statistics) in the host; matches spicelib/PySpice practice | Two calls per point; per-call transport latency (unmeasured) | chosen-candidate (v1) |
| B2 | Server-side batch/sweep operation (cartesian sweep, list, seeded tolerance draws) | One call per experiment; cancellable as one run; usable by agents without a script host | New contract; fixed vocabulary | needs more data — worth it if per-call latency is material or agents need sweeps without a script |
| B3 | Declarative bench data (JSON/YAML: circuit ref, overrides, sweeps, probes, measures, specs) run by a runner | No code execution, so safe to accept through MCP; LLM-friendly; can be the compile target of B1 scripts or `.step/.meas` cards | No loops, conditions or functions | complement to B1/B2 |

**C. Host and syntax of the scripts**

| # | Approach | Pros | Cons | Verdict |
|---|----------|------|------|---------|
| C1 | JS/TS library outside the app (Node), over the Agent API transport | No new exposure through the server; async/await fits `run`; strong LLM prior; matches the bridge's ESM + JSDoc stack and `node --test` | Needs the MCP server/bridge (unbuilt; only CDP works today); a script UI is the user's editor | chosen-candidate |
| C2 | Python client outside the app (plain JSON-RPC over HTTP or the `mcp` SDK) | Best LLM prior (AnalogCoder); numpy/scipy/Jupyter for statistics and plots | Second runtime and test stack; layout/units logic duplicated unless generated; SDK v2 client vs. in-app server unverified | candidate — a thin client after C1, if users want notebooks |
| C3 | Custom text DSL parsed in Java (circuit format + bench runner) | In-app UX; parse-only data is MCP-safe | No coroutines (continuation code), no JUnit (minutes per test cycle), no LLM prior, the expression engine is not reusable, language design lock-in | rejected for the bench; the circuit-part alternative is D3 |
| C4 | SPICE subset (netlist + `.tran/.param/.step/.meas`) | Strongest prior for people and LLMs; the vocabulary of A1 | JSON has no model definitions; source/model parameters partly unsupported (silent-drop risk); `M` = milli conflict; dialect pinning; `.include/.lib/.control` must be refused | candidate as an import front end (D4), not as the experiment host |
| C5 | Interpreter embedded in the page (sandboxed JS frame, QuickJS-wasm, Lua/fengari, Pyodide) | Scripts inside the app for humans | Code execution in a page with Node privileges next to an unauthenticated server; no editor or results UI; isolation unverified on NW.js 0.64.1; Pyodide needs Chrome ≥ 112 (blocked); CPU-bound loops freeze the UI | deferred (QuickJS the only plausible one, after a prototype) |

**D. Circuit description**

| # | Approach | Pros | Cons | Verdict |
|---|----------|------|------|---------|
| D1 | Bench over circuits drawn in the editor or loaded from files (address by element ID and net label) | No geometry problem; uses what users already draw; parameter edits are the LLM-reliable zone | The circuit is not "in the code" | chosen-candidate (v1) |
| D2 | Builder code in the host language emitting the existing AgentCircuit/JSON with the agent's grid cells | Exists today (C_AGA_DEC_01); readable diagrams | Topology authoring with coordinates is the LLM-weak zone | kept (already supported) |
| D3 | Net-oriented description (elements + pin→net names) converted client-side to AgentCircuit with a `LabeledNode` stub per pin | Correct-by-construction connectivity; `single_label` lint catches typos; no Java change | Label-stub drawings (no wires) look crowded | candidate (increment after D1) |
| D4 | SPICE-netlist import (client translator first, a registered format later) | Reuses SPICE corpora and LLM prior | Needs a model-definition channel; per-parameter warnings mandatory; a registered format must precede `text` with a strict probe, and `ImportOps` must accept a format id | needs more data — after a model channel exists |
| D5 | Automatic placement and orthogonal routing | Readable schematics from netlists | Out of scope of C_AGA; large | deferred |

**E. Engine extensions for the stability use case**

| # | Approach | Pros | Cons | Verdict |
|---|----------|------|------|---------|
| E1 | Transient-based substitutes: windowed FFT/THD, swept-sine gain/phase, step response (overshoot, settling), long-run drift, start-up from reset | No solver change; covers most "does it work and settle" questions | Slow for low frequencies; accuracy bound by the fixed step | chosen-candidate |
| E2 | Seeded RNG, tolerance draws, corner runs | Reproducible Monte Carlo and worst case | Engine seeding is small; tolerance metadata is new | chosen-candidate (seeding S; tolerances client-side first) |
| E3 | AC/noise analysis, temperature models, truncation-error step control | True Bode/phase margin, temperature corners, accuracy control | Each is L-sized solver work | deferred |

## Conclusion

**Post-spike update (2026-10-02):** commit `722f9f9` (E_AGT fix round) fixed three findings of Entry 2:
- The time-step quantization (audit BL-D01). Code no longer re-quantizes a loaded or configured step, and a bad `$` step falls back to 5 µs.
- The first-sample 0 V artefact.
- A stop trigger now ends an agent run with reason `stop_trigger`.

The determinism claim of C_AGA_03_04 now names noise sources as the exception. Later the same day the MCP server (PL_MCP Phases 1–5) and the `circuitjs-mcp` bridge (PL_MCB Phases 1–4) landed, and C_MCP_DEC_02 was amended: the server listens on loopback by default and is private-network reachable only by setting the listening address (`23b0b17`). The entries above and the Security/Transport constraints below record the state before these commits.

**Verdict:** Feasible, in layers.
- **Experiment layer first.** The core of the requested language is an experiment layer over the Agent API:
  - set parameters, run from a defined initial state;
  - measure at nets, posts and elements with a SPICE-style vocabulary computed in the app;
  - check results against specs;
  - loop for sweeps, Monte Carlo and corners.
- **Host outside the app.** It is best hosted outside the app (JS/TS library first, Python optional), because that adds no code-execution path to the always-on server and has the strongest LLM evidence.
- **Circuit description is a separate choice.** Working over drawn circuits needs nothing new. Builder code with grid cells already works. Net-oriented text with label stubs and SPICE import are later increments.
- **Use-case coverage.**
  - (a) Concept check is covered once the measures exist.
  - (b) Parameter selection works today with a client loop: seconds for small circuits, about a minute per 20 points for a 100-element transistor circuit.
  - (c) Stability is partly covered by transient substitutes and seeded Monte Carlo. Temperature, AC/phase margin and accuracy control need solver work and are deferred.

**Key constraints discovered:**
- **Transient-only engine.** The temperature is fixed at 27 °C, element tolerances do not exist, noise RNG is unseeded, and the time step is fixed with no truncation-error control. Frequency figures come only from transient runs, so a language must expose and report the time step it ran with.
- **Measures run in the app.** The API returns ≤ 2000 min/max-bucket points per run, not uniform samples, so FFT, THD, delays and settling times cannot be computed by the client.
- **Initial state must be explicit.** Repeated runs with `reset: true` were bit-identical in tests (noise sources excepted). A fresh import (file state) differs from a reset (initial conditions), and a run without `reset` continues from the current state. A script must say which start it means.
- **Time-step quantization.** Text import and tab activation re-quantized `maxTimeStep` to a 1-2-5 table capped at 10 µs. Fixed in `722f9f9`, so the slider's table limits only steps a user sets by hand.
- **Throughput.** Measured in a draft compile:
  - ~1800 runs/min for a 5-element RC;
  - ~240/min for a buck converter;
  - ~18/min for a 20-BJT op-amp.

  There is no parallel speedup inside one app instance, and non-convergence slows a run 100×. A 1000-run Monte Carlo of a mid-size circuit takes minutes, and of a large one about an hour.
- **Numerical vs circuit instability.** The solver recovers from non-convergence with gmin/forced steps instead of stopping, so a stability check must read the run's solver issues, or a numerical artefact passes for circuit behaviour.
- **Security.** No script text may execute inside the page through the MCP server or from a circuit file. The page has full Node privileges, and the server is unauthenticated by decision ([C_MCP_DEC_02](./mcp-server.concept.md#C_MCP_DEC_02)).
- **Transport.** At the time of the spike no outside-the-page transport existed except CDP, and the host library depended on PL_MCP (server) and PL_MCB (bridge). Both have since landed (see the post-spike update).
- **Formats.** JSON carries no model definitions. SPICE numbers conflict with the app's unit parser (`M`). The text format's `canImport` accepts almost anything, so any new format must register before it with a strict probe. The expression engine cannot be grown into a script grammar.
- **LLM evidence.** Writing a topology is the weak zone, while parameter edits and declared experiments are the reliable zone. A mainstream host language, verified examples and automatic structural checks after each change raise the success rate.

**Recommendations for the concept:**
- **Structure.** Model the language as circuit + experiment, with several experiments per circuit. The experiment holds: initial state, time-step settings, stimulus changes, run mode (span/settle/until condition), probes, measures, specs and loops.
- **Measurement vocabulary in the Agent API (Java, transport-free):**
  - window, edge selection, `TRIG/TARG` delay and period, `FIND … AT/WHEN`;
  - overshoot and settling, two-probe and derived quantities, windowed FFT/THD;
  - an explicit `failed` result instead of a number;
  - per-run quality fields (forced steps, maximum panic level, minimum step).

  This is a C_AGA extension and serves agents over MCP directly.
- **Specs** as name + comparator + bound → pass/fail, returned with the measured value.
- **Loops in the host library for v1:** sweep, list, goal seek, Monte Carlo with an explicit seed, corners. Decide on a server-side batch operation (B2) and a declarative bench (B3) once per-call latency is measured.
- **Engine and API small fixes that the language needs:**
  - seeded RNG;
  - the time-step quantization fix (done in `722f9f9`);
  - initial-state control (file state / reset / snapshot);
  - user stop conditions;
  - the first-sample 0 V artefact (done in `722f9f9`).
- **Exclude or defer:**
  - an in-page interpreter or script panel (security, UI cost);
  - Pyodide (Chromium 101);
  - AC/noise/temperature solver work;
  - auto-layout;
  - SPICE import until a model-definition channel exists;
  - optimization algorithms beyond goal seek (left to the host language's libraries).
- **Decision forks for the concept interview:**
  1. Who writes the scripts: the user, AI agents, or both? (drives host, documentation and the skill)
  2. Where they run: outside the app only (C1/C2), or also inside it (C5 later)?
  3. Host language: JS/TS first, Python first, or both?
  4. Circuit description in v1: drawn circuits only (D1), plus builder code (D2), plus net-oriented text (D3), or SPICE import (D4)?
  5. Loops: client-only (B1), or a server-side batch operation (B2) and/or a declarative bench (B3)?
  6. Scope of engine work in v1: seeded RNG and time-step fix only, or also tolerance metadata, temperature, AC?
  7. Placement: a new concept inside epic [E_AGT](./agent-automation.epic.md) (it extends C_AGA, C_MCP tools and the C_AGS skill), or a new epic depending on it?
  8. Is a bounded declarative bench "code execution" under the server's mitigation?
- **Sequencing.** The measure vocabulary (A1) can be specified now, because it only extends C_AGA. The host library can build on the MCP server and the `circuitjs-mcp` bridge, both landed after the spike; CDP stays available for devmode and tests.

**Artifacts to keep:**
- — The fetched manuals (ngspice, Xyce, LTspice help) and papers are public and re-fetchable, so they are not promoted. Their URLs and the distilled vocabulary are in the skill `automation/circuit-experiment-language`.

**Artifacts to discard:**
- The session scratch area `spike_lang/` (q1 downloads ~22 MB, q2 measurement scripts and harness output, q3 notes). No prototype code was written into the repository.
