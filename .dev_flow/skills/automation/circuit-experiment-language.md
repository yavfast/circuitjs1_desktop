---
skill: circuit-experiment-language
domain: automation
topics: [circuit-language, experiment, test-bench, measure, meas, trig-targ, find-when, sweep, monte-carlo, worst-case, spec-check, llm-reliability, script-host, sandbox, pyodide, quickjs, spice-subset]
source: research
updated: 2026-10-02
---

# Languages for circuit experiments: prior art, LLM evidence, hosting limits here

## Context

Distilled from [docs/circuit-script-language.spike.md](../../../docs/circuit-script-language.spike.md) (2026-10-02). It covers a language in which a circuit is described and simulated with measurements at chosen points, for concept checks, value selection and stability checks. Read it before designing measures, sweeps or a script host on top of the Agent API ([C_AGA](../../../docs/agent-api.concept.md)). The run behaviour these scripts meet (determinism, throughput, time-step traps) is in [agent-run-behaviour](agent-run-behaviour.md).

## Key concepts

- **Separate the circuit from the experiment.**
  - Every mature tool splits the description (netlist, tree or drawing) from the experiment: analysis, probes, measures, sweeps, specs.
  - Several experiments per circuit are normal (tscircuit names them; LTspice/Xyce run several analyses per deck).
- **The SPICE measurement vocabulary is shared** across ngspice, LTspice and Xyce. It is the de-facto standard to borrow:
  - `TRIG <sig> VAL=<v> RISE|FALL|CROSS=<n|LAST> … TARG …` gives delays and periods.
  - `FIND <expr> AT=<t>` and `FIND <expr> WHEN <sig>=<v>` give the value at an instant or event; `WHEN` alone gives the event time.
  - `AVG|MIN|MAX|PP|RMS|INTEG|DERIV <sig>` work over a window `TD=/FROM=/TO=`.
  - Derived measures (`PARAM='…'`, Xyce `EQN`) reuse earlier ones, e.g. pass/fail as `param='(x<lim)?1:0'`.
  - Xyce adds transient `FREQ` (cycle count), `DUTY`, `ON_TIME/OFF_TIME`, `FOUR … AT=freq` and `.FFT` with `THD/SNR/SFDR/ENOB`.
  - A measure that cannot be evaluated is reported as `FAILED` (or a declared `DEFAULT_VAL`), never as a silent number.
- **Sweeps, Monte Carlo, worst case and optimization are outer loops** around one run, not solver features:
  - LTspice `.step` nests at most 3 levels.
  - ngspice has no `.step`; its manual rewrites `.STEP` as a `.control` `while` loop with `alter` + `run` (§12.11.4.3).
  - ngspice also has no built-in optimizer (ch. 19).
  - Monte Carlo needs per-run re-draws with an explicit seed (ngspice draws `agauss` once at parse time; Xyce `.SAMPLING … SEED`).
  - Worst case is 2^n tolerance corners or sensitivity-based.
  - Host-language orchestrators (spicelib, PySpice) exist because the directives cannot do calculations beyond `.MEAS`, cross-run correlation or more than three sweep dimensions.
- **Frequency figures without AC analysis** are built from transient runs:
  - windowed Fourier/FFT of the settled waveform;
  - cycle counting;
  - `TRIG/TARG` periods.

  Swept-sine Bode, step-response overshoot/settling and injection loop gain are the same loop + measure primitives (standard practice, not verified in a source).
- **LLM evidence:**
  - AnalogCoder (GPT-3.5 ablation, Table 4): Python + PySpice Pass@1 21.4 against 13.9 for an ngspice netlist. Removing the check-and-retry flow drops it to 12.8, and removing the in-context example to 8.1.
  - NetlistBench: local parameter edits pass 96–100 %; terminal connectivity ranges 6–99 %; chained edits collapse (80 % at 3, 26 % at 15). Failures are structural, not syntactic.
  - Reliable zone: a mainstream host language, named nets, a verified example to edit, short local edits, and automatic structural checks after each change.
  - Weak zone: writing a topology. No study measured LLMs writing measure/sweep scripts.

## Usage in this project

- **Measures must be computed in the app.**
  - `run` returns exact streaming statistics plus a series that is min/max-bucket decimated and capped at Σ 2000 points (`ProbeRecorder`, SP_AGA §03_07).
  - The series is not uniform, so FFT, THD, delays and settling times cannot be derived from it by a client.
  - New measures belong in the Agent API next to the probe recorder; the radix-2 `FFT.java` (package-private, scope-only today) is the reuse candidate.
- **Script hosts and the security boundary.**
  - The in-app MCP server is always on and unauthenticated; it listens on loopback by default and is private-network reachable only when the user sets the listening address ([C_MCP_DEC_02](../../../docs/mcp-server.concept.md#C_MCP_DEC_02), amended 2026-10-02). The page has full Node privileges and no CSP.
  - Any host that executes script text inside the page becomes a code-execution path once that text can arrive through the server or inside a circuit file.
  - Hosts outside the app (a JS/TS library on Node, a Python client) add no exposure.
  - Declarative bench data (no I/O, no unbounded loops) is the only in-app form that stays data. Whether it counts as "code execution" is an open decision.
- **Transports for an outside host.** The in-app MCP server (PL_MCP, Phases 1–5 done) and the bridge/CLI `circuitjs-mcp` (PL_MCB, Phases 1–4 done; Node ≥ 20, ESM + JSDoc) exist since 2026-10-02 (`28ea470`…`7d182e2`, loopback default `23b0b17`). CDP (as `tests/live/harness.mjs` does) remains the test-harness path.
- **Net-oriented descriptions need geometry.** A `LabeledNode` stub per pin is the cheap net→geometry path:
  - same label = same net;
  - `gnd` is reserved;
  - a name used once is flagged as `single_label`;
  - pin offsets come from `describeType.derivedPostsAtDefault`.

  A bench over a drawn circuit (element IDs, net labels) needs no geometry at all.

## Pitfalls

- **Embedded interpreters vs. the runtime** (NW.js 0.64.1 = Chromium 101):
  - Pyodide (314.x) docs test Chrome ≥ 112, so it is blocked without a runtime upgrade.
  - quickjs-emscripten (0.32, ~0.5 MB wasm, memory/stack/deadline limits) states Chrome 63+ but is unverified on 101.
  - fengari (Lua 5.3, 0.1.5) exposes `io`/`os.execute` when it detects Node, so it needs a restricted environment, and must not ship `fengari-interop`.
  - Isolation of NW frames with Node disabled (`nwdisable`) and of Workers is undocumented; bypass reports exist.
  - CPU-bound script loops block the single UI thread.
- **`ExprParser` is not a script grammar.**
  - It lower-cases its input (`ExprParser.java:301`).
  - Identifiers are letters only, and SI suffixes (`1k`) are not accepted.
  - Its names are fixed, and its errors are one string without a position.
  - It serves the expression-driven elements — do not grow it.
- **SPICE front end:**
  - SPICE numbers are case-insensitive with `M` = milli and `MEG` = mega, while the app's unit parser reads `M` as mega, so a SPICE front end needs its own number parser.
  - Letters after a number are ignored (`4Farads` = 4 fF).
  - Sources lack delay, damping, rise/fall and PWL (workaround: an `expression` source).
  - Diode, BJT and MOSFET model parameters are partly unsupported, so warn per dropped parameter.
  - E/G/F/H have no POLY.
  - `.include/.lib/.control/.system` read files or run commands and must be refused.
- **JSON has no model definitions.** Diode, BJT and subcircuit models exist only in the text format (see [io/json-format](../io/json-format.md)).
- **A new circuit format** must register before `text` with a strict `canImport`, and `ImportOps.importCircuit` must be taught to pass it a format id (it sniffs only `{`). See [io/text-format](../io/text-format.md).

## References

- ngspice manual: https://ngspice.sourceforge.io/docs/ngspice-html-manual/manual.xhtml (§11.4 `.meas`, §12.11.4.3 `.step` replacement, ch. 18 Monte Carlo, ch. 19 optimization)
- LTspice help (LTwiki mirror): https://ltwiki.org/LTspiceHelpXVII/LTspiceHelp/html/DotCommands.htm (`.meas`, `.step`, `.four`, `.temp`, `.param`)
- Xyce Reference Guide 7.10: https://xyce.sandia.gov/ (§2.1.18 `.MEASURE`, §2.1.20 `.FFT`, §2.1.33 `.SAMPLING`, §2.1.35 `.SENS`)
- PySpice: https://github.com/PySpice-org/PySpice · spicelib: https://github.com/nunobrum/spicelib · SPICEBridge: https://github.com/clanker-lover/spicebridge · tscircuit: https://docs.tscircuit.com/elements/analogsimulation
- AnalogCoder: https://arxiv.org/abs/2405.14918 · NetlistBench: https://arxiv.org/abs/2608.12197
- quickjs-emscripten, fengari, pyodide: npm registry and project READMEs (fetched 2026-10-02)
