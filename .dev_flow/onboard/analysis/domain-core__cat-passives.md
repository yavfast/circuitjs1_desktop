# Module Analysis: domain-core / element-categories / passives

> **Path:** src/main/java/com/lushprojects/circuitjs1/client/element/ (14 files — VaristorElm not present)
> **Layer:** 2 (SCC-A)
> **Analyzed:** 2026-04-18
> **Files:** 14 source files (catalog requested 15 — `VaristorElm.java` does not exist in the tree; tracked in Issues)

## Purpose

Basic 2-terminal (and a few multi-terminal) passive components. This category
hosts the simplest reference implementations of the `CircuitElm` contract
(ResistorElm, WireElm), the canonical trapezoidal/backward-Euler companion
models (CapacitorElm, InductorElm), non-linear state elements (MemristorElm,
SparkGapElm, FuseElm, LampElm), environmentally-parameterised resistors
(ThermistorNTCElm, LDRElm), a 3-terminal Pot, and topology-only elements
(WireElm, GroundElm, LabeledNodeElm). All extend `CircuitElm` directly
except `PolarCapacitorElm` (extends `CapacitorElm`).

## Per-element catalog

| Element | Extends | Posts | V-src | Int.nodes | Linear? | State vars | Dump-type | Parameters (units) | File:lines |
|---|---|---|---|---|---|---|---|---|---|
| ResistorElm | CircuitElm | 2 | 0 | 0 | Y | — | `'r'` (114) | `resistance` (Ω) | ResistorElm.java:31-185 |
| CapacitorElm | CircuitElm | 2 | 0 | 0 or 1 (if `seriesResistance>0`) | Y (companion) | `voltDiff`, `curSourceValue`, `compResistance` | `'c'` (99) | `capacitance` (F), `initialVoltage` (V), `seriesResistance` (Ω); flags `FLAG_BACK_EULER=2`, `FLAG_RESISTANCE=4` | CapacitorElm.java:33-396 |
| PolarCapacitorElm | CapacitorElm | 2 | 0 | inherits | Y (companion) + voltage clamp | inherits + clamps `voltDiff` | `209` | inherits + `maxNegativeVoltage` (V) | PolarCapacitorElm.java:10-114 |
| InductorElm | CircuitElm | 2 | 0 | 0 | Y (companion, via `Inductor` helper) | `ind.current`, `ind.curSourceValue`, `ind.compResistance` | `'l'` (108) | `inductance` (H), `initialCurrent` (A); flag `Inductor.FLAG_BACK_EULER=2` | InductorElm.java:29-216 |
| PotElm | CircuitElm | **3** | 0 | 0 | Y | `position` (slider), `resistance1`, `resistance2`, `current1..3`, `curcount1..3` | `174` | `maxResistance` (Ω), `position` [0..1], `sliderText`; flags `FLAG_SHOW_VALUES=1`, `FLAG_FLIP=2`, `FLAG_FLIP_OFFSET=4` | PotElm.java:36-456 |
| MemristorElm | CircuitElm | 2 | 0 | 0 | **N** (nonLinear=true, iterative R) | `dopeWidth`, `resistance` | `'m'` (109) | `r_on` (Ω), `r_off` (Ω), `dopeWidth` (m), `totalWidth` (m), `mobility` (m²/(s·V)) | MemristorElm.java:32-233 |
| SparkGapElm | CircuitElm | 2 | 0 | 0 | **N** (two-state hysteresis) | `state` (bool), `resistance` | `187` | `onresistance` (Ω), `offresistance` (Ω), `breakdown` (V), `holdcurrent` (A) | SparkGapElm.java:31-205 |
| FuseElm | CircuitElm | 2 | 0 | 0 | **N** (resistance switches on blow) | `heat` (J-like I²t accumulator), `blown` (bool) | `404` | `resistance` (Ω), `i2t` (A²·s), `heat` (state), `blown` (state); flag `FLAG_IEC_SYMBOL=1`; const `blownResistance=1e9` | FuseElm.java:31-245 |
| WireElm | CircuitElm | 2 | 0 | 0 | Y (trivial — no stamp) | — | `'w'` (119) | none (display flags only `FLAG_SHOWCURRENT=1`, `FLAG_SHOWVOLTAGE=2`) | WireElm.java:29-178 |
| LabeledNodeElm | CircuitElm | **1** | 0 | 0 | Y (topology only) | `text` (label) + static `labelList` registry | `207` | `text` (String); flags `FLAG_ESCAPE=4`, `FLAG_INTERNAL=1` | LabeledNodeElm.java:33-229 |
| GroundElm | CircuitElm | **1** | 0 (or 1 in legacy `FLAG_OLD_STYLE`) | 0 | Y | `symbolType` (enum 0..3) | `'g'` (103) | `symbolType` (int); flag `FLAG_OLD_STYLE=1` | GroundElm.java:30-233 |
| ThermistorNTCElm | CircuitElm | 2 | 0 | 0 | Y (R recomputed per stamp) | `resistance`, `temperature`, `position` (slider) | `350` | `r25` (Ω), `r50` (Ω), `minTempr`/`maxTempr` (°C), `position`, `sliderText`; derived: `rneg40`, `b25100` | ThermistorNTCElm.java:29-293 |
| LDRElm | CircuitElm | 2 | 0 | 0 | Y (R recomputed per stamp) | `resistance`, `lux`, `position` (slider) | `374` | `position`, `sliderText`; defaults `minLux=0.1`, `maxLux=10000` | LDRElm.java:21-260 |
| LampElm | CircuitElm | 2 | 0 | 0 | **N** (nonLinear=true; thermal) | `temp` (K), `resistance` | `181` | `nom_pow` (W), `nom_v` (V), `warmTime` (s), `coolTime` (s); const `roomTemp=300 K`, `filament_len=24` | LampElm.java:33-308 |

Legend: V-src = `getVoltageSourceCount()`. Posts/V-src/Int.nodes are the
reported or implicit values. "Linear?" means `nonLinear() == false`.

## Shared Patterns

### Companion-model representation

Two elements embed MNA companion models for reactive passives:

- **CapacitorElm** — trapezoidal (`compResistance = Δt / (2C)`) or backward-
  Euler (`Δt / C`). Chosen by absence/presence of `FLAG_BACK_EULER`. See
  `CapacitorElm.java:184-188` (stamp), `:197-203` (startIteration —
  `curSourceValue = -V/R - I` trapz, `-V/R` back-Euler), `:232-237`
  (doStep — stamps current source), `:205-208` (stepFinished — captures
  `voltDiff` and triggers `calculateCurrent`). A DC-analysis branch
  replaces the cap with a 1e8 Ω resistor (`:163-171`). Optional
  `seriesResistance` introduces an internal node (`capNode2=2`) — see
  `getInternalNodeCount()` (`:239-245`).
- **InductorElm** — delegates **all** companion-model logic to the
  `Inductor` helper (see element-base analysis; trapz
  `compResistance = 2L/Δt`, back-Euler `L/Δt`). InductorElm only hooks
  lifecycle: `stamp(n0,n1)` at `:95-97`, `startIteration(voltdiff)` at
  `:99-101`, `doStep(voltdiff)` at `:112-115`, `calculateCurrent()` at
  `:107-110`. The `FLAG_BACK_EULER` constant is owned by `Inductor`, not
  InductorElm. InductorElm also forwards `nonLinear()` to `ind.nonLinear()`
  (`:103-105`) — the helper can flag non-linearity for saturable variants.

### Non-linear numerics

`nonLinear() == true` for four elements: `MemristorElm`, `SparkGapElm`,
`FuseElm`, `LampElm`. The common pattern is:

1. **`stamp()`** registers the nodes as non-linear participants via
   `stampNonLinear(node)` — the resistance itself is NOT stamped here
   (MemristorElm:132-136, SparkGapElm:131-134, FuseElm:148-152, LampElm:164-167).
2. **`doStep()`** stamps a fresh `stampResistor(n0, n1, currentR)` each
   Newton iteration — the resistance comes from an element-private state
   updated in `startIteration()`.
3. **`startIteration()`** advances the state variable:
   - Memristor: integrates `dopeWidth` via `dopeWidth += Δt·μ·r_on·I/L`
     then `R = r_on·(w/L) + r_off·(1-w/L)` (MemristorElm.java:122-130).
   - SparkGap: hysteretic two-state machine — turns on at `|Vd|>breakdown`,
     off at `|I|<holdcurrent` (SparkGapElm.java:114-120).
   - Fuse: I²t integrator with 3-second self-dissipation, trips `blown`
     when `heat>i2t` (FuseElm.java:158-172).
   - Lamp: Intusoft AN-11 filament model — updates `temp` from dissipated
     power with separate `capw`/`capc` warm/cool thermal time constants;
     R = f(T) polynomial (LampElm.java:173-190).

MemristorElm and SparkGapElm call `simulator().converged = false` on
degenerate state (non-finite/zero resistance) to force another Newton pass
(SparkGapElm:101-105, 124-127).

### Topology-only elements

- **WireElm** — `stamp()` is empty (`:63-64`); `isWireEquivalent()=true`
  (`:93-95`) and `isRemovableWire()=true` (`:97-99`) make the simulator
  collapse its two posts into a single node during wire-closure.
- **GroundElm** — 1 post; `isWireEquivalent()`/`isRemovableWire()=true`;
  `hasGroundConnection(n1)=true` (`:164-166`). `getConnectedPost()` shares
  the first ground's point1 across all ground symbols so the whole
  document converges to one node (`:141-152`). Legacy `FLAG_OLD_STYLE`
  path stamps a 0 V voltage source for backward-compat with subcircuits
  saved before the wire-equivalence refactor (`:120-127`).
- **LabeledNodeElm** — 1 post; uses a **static `labelList` HashMap**
  (`:68`) to link every node sharing the same `text` label. First instance
  per label registers its point1 in the registry; subsequent
  `getConnectedPost()` calls return that same point so all labeled nodes
  collapse into a single node during connectivity analysis (`:99-113`).
  `setNode()` records the assigned global node number back into the entry
  so external callers can resolve by name (`:115-122`,
  `getByName(String)` at `:141-146`).

### Slider-driven elements

Four elements spawn a GWT `Scrollbar` widget in the UI vertical panel at
construction and read its position each `stamp()`/`setPoints()`:
**PotElm**, **ThermistorNTCElm**, **LDRElm**, (also FuseElm / MemristorElm
do NOT have sliders). They implement `com.google.gwt.user.client.Command`
and `MouseWheelHandler`. `delete()` must un-register the widget
(PotElm:103-107, ThermistorNTCElm:110-113, LDRElm:88-91). Each overrides
`execute()` to call `needsAnalysis()` + `setPoints()`.

## Notable Per-element Logic

- **ResistorElm** — The simplest reference impl. `stamp()` is one line
  (`:125-127`): `simulator().stampResistor(getNode(0), getNode(1), resistance)`.
  `calculateCurrent()` is Ohm's law (`:120-123`). `getEditValue` clamps
  non-positive resistance to `1e-9` (`:148-150`) — silently prevents
  matrix singularity.
- **CapacitorElm** — `setNodeVoltage()` is overridden to call
  `setNodeVoltageDirect` and skip `calculateCurrent()` (`:210-215`) —
  otherwise `curSourceValue` captured in `startIteration()` would be
  inconsistent with the voltage currently being set. `stepFinished()`
  reads `voltDiff` from nodes `0..capNode2` (`:205-208`) so DC-only
  capacitors still behave correctly. `shorted()` is an external helper
  (`:86-89`) used by the simulator to zero the cap when a short-circuit
  is detected. `isIdealCapacitor()` returns true iff `seriesResistance==0`
  (`:319-321`) — matrix optimization hook.
- **PolarCapacitorElm** — Inherits companion model; the only simulator-
  level extension is in `stepFinished()` (`:81-92`), which, when
  `voltDiff < -maxNegativeVoltage`, sets `simulator().converged=false`
  and **clamps** `voltDiff` to `-maxNegativeVoltage` instead of hard-
  stopping — "educational/robustness mode" per code comment. Otherwise
  adds a "+" sign glyph and pin names `positive`/`negative`.
- **InductorElm** — Constructors capture `simulator()` into the Inductor
  helper eagerly (`:36-38`, `:44-50`); `setCircuitDocument` re-bumps the
  simulator reference on document swap (`:166-169`). `reset()` zeroes node
  voltages and restores `current = initialCurrent` (`:87-93`).
- **PotElm** — 3 posts, **zero voltage sources** — modeled as two series
  resistors between nodes 0-2 and 2-1 (`:333-338`). `setPoints()` auto-
  rotates the body to horizontal/vertical depending on drag direction and
  re-writes `geom().setEndpoints(...)` (`:113-176`). `flipX/Y/XY` toggle
  `FLAG_FLIP_OFFSET` so the wiper stays on the correct side
  (`:389-408`). `getCurrentIntoNode(n)` is overridden for 3-post semantics
  (`:324-331`). JSON type name override `"Potentiometer"` (`:411-413`).
- **VaristorElm** — **NOT PRESENT** in the current tree (see Issues).
  Would normally be a non-linear V/I passive; the closest existing
  analogues are SparkGapElm (hysteretic) and the diode family.
- **MemristorElm** — HP (Strukov) memristor model. State variable
  `dopeWidth` is the conductive-region thickness; it integrates in
  `startIteration()` using the current from the previous timestep
  (MemristorElm.java:122-130). Exposes R as a scope value
  (`canShowValueInScope(Scope.VAL_R)` at `:157-159`).
- **SparkGapElm** — Two-state latch; guards against non-finite / zero
  resistance by forcing a tiny resistance and marking the solver non-
  convergent (`:124-127`) so the next Newton step re-evaluates.
- **FuseElm** — Thermal I²t accumulator. Once `blown`, swaps in
  `blownResistance = 1e9` permanently until `reset()` (`:68-72`).
  `getTempColor` interpolates through red→orange→yellow→white as heat
  approaches i2t (`:80-104`).
- **LampElm** — Non-linear; uses separate thermal RC constants for warm-
  up and cool-down (`capw = cap·warmTime/0.4`, `capc = cap·coolTime/0.4`).
  The resistance-vs-temperature formula becomes unstable above 5390 K so
  `tp` is clamped (`:177-180`). Constructor calls `startIteration()`
  eagerly to pre-populate `resistance` before the first timestep
  (`:45`, `:58`). Bulb glow color interpolates with temperature
  (`:100-120`).
- **ThermistorNTCElm** — Beta-parameter NTC model: `B25/100` computed from
  two datasheet resistance points (`:270-276`). Resistance is
  recomputed inside `stamp()` from the current slider temperature
  (`:188-192`) — so temperature changes between analyses take effect on
  next `stamp()`. The `Math.max(-700, Math.min(700, arg))` clamp in
  `calcResistance` (`:254-262`) avoids `exp` overflow.
- **LDRElm** — Simple linear `R = (maxLux - lux + 1)·10` mapping
  (`:220-232`). Resistance recomputed every `stamp()` from slider.
- **WireElm** — `getMouseDistance` uses a tighter 10-px threshold than
  default to make wires easier to grab (`:134-140`). `getPower()` returns
  0 unconditionally (`:85-87`).
- **LabeledNodeElm** — `setCurrent(int,double)` is overridden
  (`:168-170`) even though the element reports 0 voltage sources —
  the labeled-node wire-closure replaces the physical element and the
  simulator writes the closure current into post 0. `getCurrentIntoNode`
  returns `-current` (single-post convention).
- **GroundElm** — Four visual symbol variants (Earth/Chassis/Signal/
  Common) selected via `symbolType`; rendering branches on it
  (`:71-110`). Static `firstGround` cache in `getConnectedPost()`
  (`:141-152`) serves the same role as LabeledNodeElm's hashmap but
  without a label key.

## Validation Rules

- **Non-positive resistance clamps** — ResistorElm sets `resistance=1e-9`
  if input ≤ 0 (ResistorElm.java:148-150); CapacitorElm sets
  `capacitance=1e-12` if input ≤ 0 (CapacitorElm.java:280-282); InductorElm
  only accepts `inductance>0` (InductorElm.java:139-140); FuseElm/
  SparkGapElm require all edit values > 0 or silently drop them
  (FuseElm.java:197-206, SparkGapElm.java:158-167). MemristorElm accepts
  values as-is (no guard) — see Issues.
- **Solver-safe defaults** — SparkGapElm.doStep re-materializes a near-
  zero resistance (`1e-12`) when the current `resistance` is zero or
  non-finite, forcing `converged=false` (SparkGapElm.java:122-129).
- **DC analysis** — CapacitorElm.stamp replaces the cap with a 1e8 Ω
  resistor when `circuitInfo.dcAnalysisFlag` is set (CapacitorElm.java:163-171).
  CapacitorElm.calculateCurrent uses the same 1e8 in DC mode
  (`:217-228`).
- **Polar cap voltage** — PolarCapacitorElm clamps `voltDiff` to
  `-maxNegativeVoltage` on reverse breach rather than aborting the
  sim (PolarCapacitorElm.java:81-92).
- **Internal-node guard** — CapacitorElm.getInternalNodeCount has an
  early return `0` when `circuitDocument == null` (during construction)
  to avoid NPE (`:239-245`).
- **Thermistor sanity** — `r25>r50` enforced through EditInfo bounds
  (ThermistorNTCElm.java:203-213); `exp` argument clamped to ±700
  to prevent overflow (`:254-262`).
- **Lamp high-temp clamp** — tp clamped to ≤5390 K in
  resistance-vs-T formula (LampElm.java:177-180).

## Integration Points

- **Element-base contract** — All 14 extend `CircuitElm` (most directly;
  PolarCapacitorElm via `CapacitorElm`). Override points that the
  base declares default-noop and every non-trivial passive overrides:
  `stamp()`, `calculateCurrent()`, `getInfo()`, `getEditInfo()`,
  `setEditValue()`, `draw()`, `dump()`, `getDumpType()`. Reactive/non-
  linear elements additionally override `startIteration()`, `doStep()`,
  `nonLinear()`, `reset()`, `stepFinished()` — see per-element citations
  in the catalog.
- **`Inductor` helper class** — Used **only** by `InductorElm` in this
  category (the broader list of Inductor clients — TransformerElm,
  CustomTransformerElm, TappedTransformerElm, DCMotorElm,
  ThreePhaseMotorElm, RelayCoilElm — belongs to other categories). See
  `Inductor.java:24` and `element-base.md` § "Inductor (physics helper)".
- **Root utilities** — `Point`, `Polygon`, `Graphics`, `Color`,
  `CanvasGradient`, `StringTokenizer` from `client/`.
- **`util.Locale`** — resistance/ohm/power string formatting
  (ResistorElm, PotElm, MemristorElm, SparkGapElm, FuseElm,
  ThermistorNTCElm, LDRElm, LampElm).
- **`Scope` constants** — `Scope.VAL_R`, `Scope.UNITS_OHMS` used by
  MemristorElm and LampElm to expose their dynamic resistance to
  oscilloscope traces.
- **`CircuitSimulator`** — `stampResistor`, `stampNonLinear`,
  `stampCurrentSource`, `stampRightSide`, `stampVoltageSource`,
  `timeStep`, `converged`.
- **`CircuitDocument.circuitInfo.dcAnalysisFlag`** — CapacitorElm only.
- **`CustomLogicModel.escape/unescape`** — used by ThermistorNTCElm,
  LDRElm, LabeledNodeElm to round-trip multi-word slider text through
  the legacy dump format.
- **`io.json.UnitParser.parse`** — used by PotElm.applyJsonProperties
  (`:434-435`) and LampElm.applyJsonProperties (`:265-278`) to accept
  unit-prefixed strings.
- **`dialog.EditInfo`, `Checkbox`, `Choice`, `Scrollbar`, `Label`** —
  UI widgets; Scrollbar/Label added to and removed from the CirSim
  vertical panel by slider-owning elements.
- **Used by** — `CircuitElementFactory` (io) via dump-type dispatch
  (`'r'`, `'c'`, `'l'`, `'m'`, `'w'`, `'g'`, `209`, `174`, `187`, `404`,
  `207`, `350`, `374`, `181`); `CircuitElmCreator` for UI menu
  instantiation; `CircuitEditor`/`CircuitRenderer` for hit-testing and
  drawing.

## Issues / Questions

1. **`VaristorElm.java` does not exist** in
   `src/main/java/com/lushprojects/circuitjs1/client/element/`. The
   category task asked for 15 files but only 14 are present. The
   closest analogue in the passive-ish space is `SparkGapElm` (a
   hysteretic voltage-breakdown device). Worth clarifying whether
   VaristorElm was removed in an earlier refactor or never implemented.
2. **`MemristorElm` lacks edit-value guards** — any value is accepted in
   `setEditValue` (MemristorElm.java:175-186), so a user can enter
   `r_on=0` or negative mobility and blow up the solver. Compare to the
   1e-9 resistor clamp (ResistorElm.java:148-150).
3. **Duplicated resistor-body draw code** — ResistorElm.draw (`:71-118`),
   ThermistorNTCElm.draw (`:128-182`), LDRElm.draw (`:108-169`),
   FuseElm.draw (`:106-142`) all reimplement essentially the same
   zigzag/rect transform-and-stroke pass. Extracting a shared helper on
   `CircuitElm`/`BaseCircuitElm` would shrink these by ~40 LOC each.
4. **CapacitorElm magic constants** — initial-voltage default `1e-3` V
   (`:55`, comment `// put small charge on caps when reset to start
   oscillators`) and series-resistance default `1e-3` Ω (`:56`) are
   hard-coded; the rationale (break pathological DC operating points)
   is in the reset-comment but never surfaces to the user.
5. **Hard-coded dump type registry** — dump types for the passive
   family span a mix of single-char (`'r' 'c' 'l' 'm' 'w' 'g'`) and
   3-digit numeric codes (`174, 181, 187, 207, 209, 350, 374, 404`).
   No central enum/registry; collisions are possible when adding new
   elements. Same issue noted in `element-base.md` § Issues #9.
6. **LabeledNodeElm.`labelList` is static** — per-JVM global; means
   two CircuitJS1 documents in the same page would share labels.
   `resetNodeList()` is called by `CirSim` on circuit load to clear
   it, but this is brittle if multiple circuits are live.
7. **PotElm.getJsonState missing** — The potentiometer's slider
   position is serialised as a **property** (`position` field), not
   `state`. That means reloading a saved simulation will reset the
   wiper to whatever position was dumped at save time — fine, but
   inconsistent with Capacitor/Inductor/Lamp which split state
   (`voltage_diff`, `current`, `temperature`) from properties.
8. **GroundElm legacy `FLAG_OLD_STYLE`** — only path where Ground
   actually stamps a voltage source (`:120-127`). Dead code for
   any modern document but kept alive because old subcircuits on
   disk may still trigger it. A migration on import would let us
   drop the branch.
9. **LampElm.startIteration called from constructor** (LampElm.java:45, 58)
   — runs before `stamp()`, which means `simulator().timeStep` might
   be zero / not yet initialised at construction time. The `cap*Δt/0.4`
   math still works (produces zero deltas), but the pattern is fragile.
10. **ThermistorNTCElm omits `@Override`** annotations on most methods
    and uses magic numbers (`t0=273.15`, `.0099`, `.0001`) inline.
    Cosmetic but contributes to onboarding cost.
11. **PolarCapacitorElm's `stepFinished` clamping hack** — setting
    `simulator().converged = false` from inside stepFinished is
    unusual (convergence is a Newton-loop concept, and stepFinished
    runs after the step converges). It works because the next
    timestep re-enters the Newton loop, but the intent is not
    obvious from the code.
12. **No per-element JSON state for LDR / Thermistor / Pot** —
    slider position is a property, not state; there's also no
    `getJsonState()` override on ThermistorNTCElm or LDRElm.
    This is probably fine (slider position *is* a property) but
    worth documenting.
13. **`WireElm.isRemovableWire()=true` plus `isWireEquivalent()=true`**
    mean wires disappear from the netlist during analysis. Any UI
    code that expects a stable post-analysis element reference
    (e.g. scope probes attached to a wire) must handle this.

## Suggested Concept Boundaries

A single **`passive-elements`** concept covering all 14 files is the
right granularity — they share:

- the 2-terminal (or 1-/3-terminal) `CircuitElm` contract,
- linear MNA stamping via `stampResistor` as the default path,
- companion-model and non-linear state machinery as minor variants
  on a common lifecycle,
- overlapping `dump`/`JSON` persistence pattern.

Finer-grained cuts, if required downstream:

1. **passive-linear-2term** — ResistorElm, WireElm, GroundElm,
   LabeledNodeElm (topology-only or pure-R, no state).
2. **passive-reactive** — CapacitorElm, PolarCapacitorElm, InductorElm
   (trapezoidal/backward-Euler companion models).
3. **passive-nonlinear** — MemristorElm, SparkGapElm, FuseElm,
   LampElm (iterative R with private state).
4. **passive-env-driven** — ThermistorNTCElm, LDRElm, PotElm
   (slider-controlled parameter → R).

The dependency graph does not force this split; they all live in the
same SCC-A.
