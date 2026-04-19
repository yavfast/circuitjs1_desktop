# Category Analysis: domain-core / element-catalog — diodes-and-semis

> **Path:** `src/main/java/com/lushprojects/circuitjs1/client/element/`
> **Layer:** 2 (SCC-A; same package as element-base)
> **Analyzed:** 2026-04-18
> **Files:** 11 concrete `*Elm` classes + 1 shared helper (`client/Diode.java`), 0 test files

## Purpose

The `diodes-and-semis` category covers every two- or three-terminal semiconductor
device **other than** transistors, MOSFETs, JFETs, and OTAs — i.e. the
PN-junction family (diode, zener, varactor, tunnel diode), the light-emitting
siblings (LED, LED array), the latching thyristor family (SCR, Triac, Diac),
and two composite devices built from simpler elements (Unijunction,
Optocoupler). All but `TunnelDiodeElm`, `DiacElm`, `UnijunctionElm`, and
`LEDArrayElm` are modelled as a `DiodeModel`-parametrized Shockley exponential
(`client/Diode.java`), usually wrapped in additional passive linear elements
to represent breakdown, hysteresis, or optical coupling.

Three different structural patterns appear in this category:

1. **`DiodeElm` subclass** (`ZenerElm`, `LEDElm`, `VaractorElm`) — override
   rendering + an extra parameter, reuse `DiodeElm`'s `Diode` helper.
2. **Hand-rolled multi-Diode container** (`SCRElm`, `TriacElm`, `DiacElm`,
   `TunnelDiodeElm`, `LEDArrayElm`) — element owns 1…N `Diode` helpers
   (or a bespoke exponential in the tunnel case), plus a state-dependent
   `aresistance`/`state` field to implement latching / negative resistance.
3. **`CompositeElm` subclass** (`UnijunctionElm`, `OptocouplerElm`) — the
   element is literally a tiny sub-netlist (diode + CCCS + NTransistorElm,
   or a diode/VCCS/cap network) authored inline via `loadComposite`.

## Per-Element Catalog

| Element | Extends | Posts | V-sources | Internal nodes | Model-ref | Non-linear? | Dump-type | Parameters | File:lines |
|---|---|---|---|---|---|---|---|---|---|
| `DiodeElm` | `CircuitElm` | 2 (anode, cathode) | 0 | 0 or 1 (if `seriesResistance > 0`) | `DiodeModel` by name | yes | `'d'` (100) | `modelName` | `element/DiodeElm.java:39-349` |
| `ZenerElm` | `DiodeElm` | 2 | 0 | 0/1 | `DiodeModel` (usually `default-zener`, any `breakdownVoltage > 0`) | yes (via super) | `'z'` (122) | `modelName`, legacy `zvoltage` | `element/ZenerElm.java:33-133` |
| `TunnelDiodeElm` | `CircuitElm` | 2 | 0 | 0 | **none** (hard-coded pip/pvp/pvv constants for 1N3712-ish curve) | yes | 175 | (none, all constants) | `element/TunnelDiodeElm.java:29-217` |
| `VaractorElm` | `DiodeElm` | 2 | **1** (for companion-cap) | **1** (internal node between diode + cap Thevenin source) | inherited `DiodeModel` | yes | 176 | `baseCapacitance`, `capvoltdiff` (saved state) | `element/VaractorElm.java:10-199` |
| `LEDElm` | `DiodeElm` | 2 | 0 | 0/1 | `DiodeModel` `default-led` | yes (via super) | 162 | `colorR`, `colorG`, `colorB`, `maxBrightnessCurrent`, inherited model | `element/LEDElm.java:32-179` |
| `LEDArrayElm` | `ChipElm` | `sizeX + sizeY` | 0 | 0 | `DiodeModel` `default-led` per LED (shared) | yes | 405 | `sizeX`, `sizeY` (2…16) | `element/LEDArrayElm.java:31-237` |
| `SCRElm` | `CircuitElm` | 3 (anode, cathode, gate) | 0 | **1** (inode, between anode and the internal diode) | `DiodeModel` default | yes | 177 | `triggerI`, `holdingI`, `gresistance`, saved `lastvac`, `lastvag` | `element/SCRElm.java:38-404` |
| `TriacElm` | `CircuitElm` | 3 (MT2, MT1, gate; MT1=post 1, MT2=post 0) | 0 | **1** (mtinode between MT2 and MT1) | `DiodeModel` default (two `Diode` helpers) | yes | 206 | `triggerI`, `holdingI`, `cresistance`, saved `state` | `element/TriacElm.java:39-363` |
| `DiacElm` | `CircuitElm` | 2 | 0 | **2** (nodes 2 and 3, one per direction) | `DiodeModel` default (two `Diode` helpers) | yes | 203 | `onresistance`, `offresistance`, `breakdown`, `holdcurrent`, `state` | `element/DiacElm.java:33-231` |
| `UnijunctionElm` | `CompositeElm` | 3 (E, B1, B2) | inherited from children | inherited | via inner `DiodeElm` of the composite (`x2n2646-emitter` built-in model) | yes (any child nonlinear) | 417 | pre-baked `ujtModelDump` (CCVS, VCCS, caps, resistors) | `element/UnijunctionElm.java:29-189` |
| `OptocouplerElm` | `CompositeElm` | 4 (LED anode, LED cathode, collector, emitter) | inherited | inherited (3 internal sub-elements) | inner `DiodeElm` + `TransistorElm` (β=700); `CCCSElm` linking them via polynomial I→I | yes | 407 | none user-editable; `cccs` polynomial hard-coded | `element/OptocouplerElm.java:12-230` |

Notes on the table:
- "Non-linear?" refers to whether `nonLinear()` returns `true`. All 11 are
  non-linear (confirmed by `grep nonLinear`): `DiodeElm.java:84`,
  `ZenerElm` inherits, `TunnelDiodeElm.java:41`, `LEDElm`/`VaractorElm`
  inherit, `SCRElm.java:84`, `TriacElm.java:78`, `DiacElm.java:67`,
  `LEDArrayElm.java:99`. `UnijunctionElm` and `OptocouplerElm` inherit
  `CompositeElm`'s "any child nonlinear" rule (`CompositeElm.java:199`).
- `getDumpType()` for `DiodeElm` is the ASCII code of `'d'` = 100, and for
  `ZenerElm` it is `'z'` = 122; everything else is numeric.
- `getPostCount()` default is 2 (`CircuitElm.java:893`); only
  `SCRElm`/`TriacElm`/`UnijunctionElm`/`OptocouplerElm`/`LEDArrayElm`
  override.

## Shared stamping mechanism: the `Diode` helper

`client/Diode.java` (236 LOC, confirmed) is the **sole Newton-Raphson engine**
for every PN-junction in this category (and for the two intrinsic BE/BC
junctions owned by `TransistorElm`). It is *not* a `CircuitElm` — it is a
numerical strategy object owned by elements.

Key contract (see `client/Diode.java:36-225`):

1. `Diode(CircuitSimulator s)` — ctor; simulator reference used for
   `stampNonLinear`, `stampConductance`, `stampCurrentSource`,
   `getConvergencePanicLevel`, `getExtraConvergenceGmin`,
   `converged`/`subIterations` introspection.
2. `setup(DiodeModel m)` (`Diode.java:36-57`) — reads `saturationCurrent`
   (IS), `breakdownVoltage` (Zener Vz), `vscale` (= N·Vt), `vdcoef`
   (= 1/vscale) from the model; computes `vcrit`, `vzcrit`, `zoffset`
   (the latter giving -5 mA at `zvoltage`). `setupForDefaultModel()`
   (`Diode.java:59`) pulls `DiodeModel.getDefaultModel()` — used by
   `SCRElm.setup()` (`SCRElm.java:80`), `TriacElm.setup()`
   (`TriacElm.java:73,75`), `DiacElm.createDiodes()` (`DiacElm.java:63,64`).
3. `stamp(int n0, int n1)` (`Diode.java:151-156`) — records node indices
   and calls `simulator.stampNonLinear` on each. No conductance is stamped
   here; all stamping happens in `doStep`.
4. `doStep(double voltdiff)` (`Diode.java:158-225`) — the Newton iteration:
   (a) triggers non-convergence if `|Δv| > 0.01`;
   (b) passes `vnew` through `limitStep` to cap forward/Zener excursions;
   (c) stamps a gmin (ramped up as `subIterations` grows, more aggressively
       in panic mode) in parallel to avoid singular matrices;
   (d) linearizes the Shockley exponential `I = IS·(e^(v·vdcoef) − 1)`,
       stamping `geq = vdcoef·IS·eval + gmin` as a conductance and
       `nc = (eval − 1)·IS − geq·v` as a parallel current source between
       `n0` and `n1`;
   (e) if `voltdiff < 0 && zvoltage != 0`, uses the Zener branch that
       adds a *second* exponential `e^((−v−zoffset)·vzcoef)` with the
       steeper `vzcoef = 1/vt` slope.
5. `calculateCurrent(double voltdiff)` (`Diode.java:227-235`) —
   Shockley eval without matrix side-effects; called from element
   `calculateCurrent()` overrides.
6. `limitStep(vnew, vold)` (`Diode.java:96-149`) — enforces |Δv| ≤ 2·vscale
   (forward) / 2·vt (Zener), relaxed 10× under
   `simulator.getConvergencePanicLevel() > 0`. Critical for converging
   stiff circuits containing multiple PN junctions.
7. `setSimulator(CircuitSimulator s)` — late-bound for JSON import
   (`DiodeElm.setCircuitDocument`, `SCRElm:354`, `TriacElm:318-319`,
   `DiacElm:207-208`).

**Reuse sites** (confirmed by `grep -l "new Diode"`):

| Element | # Diode helpers | Stamp layout (`Diode.stamp(a,b)`) | Source |
|---|---:|---|---|
| `DiodeElm` | 1 | `(0, internal)` then series R to 1 *or* `(0, 1)` if no Rs | `DiodeElm.java:177-186` |
| `ZenerElm` | 1 (inherited) | same as DiodeElm; Zener kicks in via `zvoltage > 0` in model | inherited |
| `VaractorElm` | 1 (inherited) | diode `(0, 2)`, trap-cap voltage source between 0 and 2 | `VaractorElm.java:104-108` |
| `LEDElm` | 1 (inherited) | same as DiodeElm | inherited |
| `LEDArrayElm` | `sizeX·sizeY` | grid: row pin → column pin per LED | `LEDArrayElm.java:79-88` |
| `SCRElm` | 1 | `(inode, cathode)` — forward PN, rest is R network | `SCRElm.java:247` |
| `TriacElm` | 2 (back-to-back) | `(mt2, mtinode)` and `(mtinode, mt2)` | `TriacElm.java:241-242` |
| `DiacElm` | 2 | `(2, 1)` and `(1, 3)` with paired resistors `(0,2)`/`(0,3)` | `DiacElm.java:162-163` |
| `UnijunctionElm` | 1 (inside composite) | internal: `DiodeElm 1 4` sub-element (uses its own Diode) | `UnijunctionElm.java:46` |
| `OptocouplerElm` | 1 (inside composite) | internal: `DiodeElm 6 1` sub-element | `OptocouplerElm.java:17` |
| `TunnelDiodeElm` | **0** (rolls its own exponential) | stamps `(0, 1)` directly via `stampNonLinear` + `stampConductance` + `stampCurrentSource` | `TunnelDiodeElm.java:119-173` |

## Per-element callouts

### DiodeElm (`element/DiodeElm.java:39`)

- Resolves `modelName → model` in `setup()` via
  `DiodeModel.getModelWithNameOrCopy(modelName, model)` (line 90), which is
  the canonical way an element binds to the global `DiodeModel` catalog.
  `updateModels()` (line 102) just re-calls `setup()` — invoked by the
  simulator after any catalog edit (`DiodeModel.setEditValue` triggers
  `circuitDocument.simulator.updateModels()`, see shared-models analysis).
- **Series resistance** is handled here, *not* inside `Diode.java` (per the
  comment at `Diode.java:22`). When `model.seriesResistance > 0` (line 93):
  `getInternalNodeCount()` returns 1 (line 99), and the stamp becomes
  `Diode.stamp(node0, internalNode) + stampResistor(internalNode, node1, Rs)`
  (lines 177-185). Otherwise it stamps directly between the two externals.
  The field `diodeEndNode` caches which node to subtract from for
  `calculateCurrent` (line 94, 192-194).
- `stepFinished()` (line 287) clamps `current` to ±1e12 and flags
  non-convergence on Inf/NaN — same safety net copied into `LEDArrayElm`
  for each of its diodes.
- `getEditInfo`/`setEditValue` expose a Choice of all models via
  `DiodeModel.getModelList(isZener)` (line 213), plus buttons for
  "Create New Simple Model", "Create New Advanced Model", "Edit Model".
  The "is this a zener?" boolean is communicated by
  `this instanceof ZenerElm` (line 213) — a reflection-style test used
  solely for the model dropdown filter.
- Static `lastModelName` field (line 45) remembers the last model the
  user chose so new placements default to it; overridden by `ZenerElm`
  (`lastZenerModelName`) and `LEDElm` (`lastLEDModelName`).

### ZenerElm (`element/ZenerElm.java:33`)

- Trivial subclass: overrides constructors to seed `default-zener` model,
  overrides `setPoints/draw` to add the characteristic "wings" on the
  cathode bar, and adds `Vz` to the info display (line 111).
- Legacy loading: if `FLAG_MODEL` is absent, the second token in the dump
  is parsed as a zener voltage and resolved via
  `DiodeModel.getModelWithParameters(fwdrop, zvoltage)` (line 47) — the
  same legacy bridge used by `DiodeElm` and `LEDElm`.
- JSON adds `zener_voltage` property (line 130).

### TunnelDiodeElm (`element/TunnelDiodeElm.java:29`)

- **Does not** extend `DiodeElm` nor use `client/Diode.java`. Stamps its
  own conductance/current-source pair with a **triple-exponential**
  model at lines 131-173:
  - Term 1: `pip · e^(−pvpp/pvt) · (e^(v/pvt) − 1)` — the reverse-biased
    tunneling tail.
  - Term 2: `pip · (v/pvp) · e^(1 − v/pvp)` — the characteristic
    **negative-resistance region** around the peak at v = pvp (≈ 0.1 V).
  - Term 3: `piv · e^(v − pvv)` — the thermal/diffusion current above
    the valley voltage pvv (≈ 0.37 V).
  - Constants: `pvp=0.1, pip=4.7e-3, pvv=0.37, pvt=0.026, pvpp=0.525,
    piv=370e-6` (lines 124-129) — modelled on a 1N3712-class tunnel
    diode.
- Has its own `limitStep` (line 107) that caps |Δv| to 1 V (5 V under
  panic), and its own `lastvoltdiff` to drive non-convergence detection
  — duplicating logic from `Diode.java` but with a different scale.
- Safety net at line 163: if `geq` or `i` become non-finite, falls back
  to a small conductance.

### VaractorElm (`element/VaractorElm.java:10`)

- **Voltage-controlled capacitance diode.** Extends `DiodeElm` and
  stacks a trapezoidal capacitor companion model in parallel with the
  PN junction.
- The PN diode uses the inherited `Diode` helper. In addition, the
  varactor allocates 1 internal node (line 159-161) and 1 voltage
  source (line 155-157); these implement the companion-model
  **Thevenin resistor + voltage source** representation of a capacitor
  (lines 110-122).
- `startIteration` recomputes capacitance as
  `C(V) = C0` (when forward-biased) or `C0 / (1 − V/fwdrop)^0.5` (when
  reverse-biased — the standard abrupt-junction square-root dependence
  on bias voltage). Uses `model.fwdrop` as the junction contact
  potential. Then sets `compResistance = Δt / (2·C)` (trapezoidal)
  and `voltSourceValue = −capvoltdiff − capCurrent · compResistance`.
- `stamp()` calls `super.stamp()` (the diode part), then stamps a
  voltage source between node0 and the internal node, and marks the
  internal node non-linear.
- `doStep()` re-stamps the resistor and updates the voltage source each
  Newton iteration.
- `setCurrent(int, double)` (line 149) captures the voltage-source
  current into `capCurrent`; `calculateCurrent` adds it to the diode
  current for scope display.
- Flag pattern note: `VaractorElm` uses the single `voltSource` field
  inherited from `CircuitElm` (fine because it reports a single
  voltage source); this is an example of the single-source-default
  caveat noted in the element-base analysis.

### LEDElm (`element/LEDElm.java:32`)

- Diode numerics are unchanged — inherited from `DiodeElm`. The
  `default-led` `DiodeModel` built-in (seeded in `DiodeModel.java:81-114`)
  supplies a higher forward-drop (~2.1024 V) appropriate for a red LED.
- **Rendering** is the differentiator: replaces `drawDiode` with a
  filled circle whose RGB color is scaled by
  `w = 255·(1 + 0.2·ln(current/maxBrightnessCurrent))` (line 99-106).
  The log scale compresses the dynamic range so a current ~10× above
  max brightness saturates at `w = 255`.
- When the element is highlighted or being dragged, it falls back to
  the diode-schematic draw (line 86-89) so the user can see it as a
  symbol, not a lit dot.
- Editable fields: `colorR/G/B` (0-1 each, dimensionless) and
  `maxBrightnessCurrent` (amps). These are serialized both to the text
  dump (line 71) and to JSON (lines 162-178).

### LEDArrayElm (`element/LEDArrayElm.java:31`)

- Confirmed: extends **`ChipElm`**, not `CompositeElm`. Justification is
  visible in the structure — `LEDArrayElm` needs ChipElm's pin layout
  machinery (`Pin` inner class, `setupPins`, `SIDE_S`/`SIDE_W`,
  chip-drawing primitives), and its children are not full elements but
  raw `Diode` instances.
- Topology: `sizeX + sizeY` external posts (the matrix rows + columns).
  Each (row, col) intersection holds a **`Diode`** helper (not
  `DiodeElm`) wired row→col using `DiodeModel.getModelWithName("default-led")`
  (line 81). Stamp allocates `diodes[sizeX·sizeY]` on each call (line 79).
- **Not** a `CompositeElm` — it does not call `loadComposite`, does not
  maintain a `compElmList`, and does not forward lifecycle to sub-elements.
  Instead it directly iterates the `Diode` array in `doStep`/`calculateCurrent`
  and stamps each one manually. This is a deliberate optimization: for
  an N×M LED array there is no need for the BFS connectivity analysis
  `CompositeElm.getConnection` does.
- `getVoltageSourceCount() == 0` (line 183); `isDigitalChip() == false`
  (line 104) suppresses the "High Logic Voltage" dialog row inherited
  from `ChipElm`.
- Rendering draws a small filled oval per LED whose red intensity tracks
  `currents[i]` (line 157-177). Persistence-of-vision is simulated by
  `brightness[p] *= 0.99` decay (line 173).
- Edit dialog lets the user pick a 2-16 grid for width and height
  (line 194-217); `setChipEditValue` re-runs `allocNodes`/`setupPins`/`setPoints`.

### SCRElm — latching, gate-triggered (`element/SCRElm.java:38`)

- Three external posts (anode, cathode, gate) + **1 internal node**
  `inode` between the anode and the PN-junction diode (`Diode.stamp(inode,
  cnode)` at line 247).
- Internal network (see comments at lines 27-29):
  - **Variable** resistor from anode to inode — `aresistance`, switched
    between `0.0105 Ω` (latched on) and `1e6 Ω` (off) each `doStep`.
  - Fixed diode from inode to cathode (the actual PN-junction).
  - Fixed `gresistance` (default 50 Ω) resistor from gate to cathode.
- **Latching logic** lives in `doStep()` (lines 268-276):
  ```
  aresistance = (−icmult·ic + ia·iamult > 1) ? 0.0105 : 1e6
  ```
  where `icmult = 1/triggerI` and `iamult = 1/holdingI − icmult`. This
  encodes "turn on when cathode current exceeds triggerI, stay on
  while anode current exceeds holdingI" — a state-free implicit
  hysteresis expressed as a single inequality that re-fires each step.
  Note the latching here is *not* maintained as a boolean `state` like
  in Triac/Diac; it is recomputed from the currents on every Newton step.
- `startIteration` does no latching bookkeeping; the inequality runs
  inside `doStep` after the diode is stamped.
- Convergence hook: detects a change >0.01 V in `vac` or `vag`, flags
  the simulator as not converged (lines 253-256). Also guards against
  non-finite / non-positive `triggerI`/`holdingI` (lines 261-268).
- Gate is the third post (`getPost(2)` returns `gate[1]`, line 219) and
  sits perpendicular to the main axis — `setPoints` computes the gate
  stub with `FLAG_GATE_FIX` snapping (lines 113-162).
- `getCurrentIntoNode(n)` (line 210) is hand-rolled because this element
  has 3 external posts — the default "2-post" version of
  `CircuitElm.getCurrentIntoNode` would be wrong.

### TriacElm — bidirectional thyristor (`element/TriacElm.java:39`)

- Same basic shape as SCR but with **two** diodes (`diode03`, `diode30`)
  back-to-back across the internal node and MT2 so conduction is
  bidirectional.
- Internal network (lines 24-30 + stamp at 235-243):
  - `stampResistor(gate, MT1, cresistance)` — gate shunt (default 100 Ω).
  - `Diode.stamp(mt2, mtinode)` and `Diode.stamp(mtinode, mt2)` —
    anti-parallel junctions.
  - Variable `aresistance` between mtinode and MT1, flipped between
    `0.01 Ω` and `1e6 Ω` in `startIteration`.
- **Latching uses an explicit `boolean state`** (line 46) which persists
  across Newton iterations. Updated in `startIteration` (line 245-251):
  ```
  if (|i2| < holdingI) state = false
  if (|ig| > triggerI) state = true
  ```
  — the state is one-way-each-direction: gate current turns it on,
  low principal current turns it off. Unlike SCR's inequality-in-doStep,
  this is a proper latch that holds across Newton iterations but can
  flip between timesteps via `startIteration`.
- `state` is **dumped to disk** (line 96) and round-tripped via JSON
  (lines 347-361) so a paused triac circuit survives save/reload in its
  correct conducting state.

### DiacElm — bidirectional trigger diode (`element/DiacElm.java:33`)

- Two posts only (no gate). Internally 2 internal nodes (nodes 2 and 3),
  one per polarity of conduction.
- Network (line 34-35 + stamp at 158-164):
  - `stampResistor(0, 2, r)` and `stampResistor(0, 3, r)` where
    `r = onresistance` (500 Ω) when `state == true`, else
    `offresistance` (1e8 Ω).
  - `Diode.stamp(2, 1)` (forward path node 2 → 1) and
    `Diode.stamp(1, 3)` (reverse path 1 → 3).
- **Latch** (line 143-147) is in `startIteration` and is **voltage-triggered**
  rather than current-triggered:
  ```
  if (|current| < holdcurrent) state = false
  if (|V(0) − V(1)| > breakdown)  state = true
  ```
  — i.e. "turn on when |V| exceeds `breakdown` (default 30 V); drop out
  when current drops below `holdcurrent`". Classic diac semantics: no
  gate, purely voltage-threshold activation, used as the trigger driver
  for triacs in phase-control circuits.
- `state` is **not** dumped (only the on/off resistances and thresholds are,
  line 75-77); this is a *behavioral* difference from TriacElm and likely
  unintentional — see Issues.

### UnijunctionElm — UJT as a `CompositeElm` subcircuit (`element/UnijunctionElm.java:29`)

- Confirmed extends **`CompositeElm`**. The UJT is literally defined as a
  string-encoded sub-netlist (`ujtModelString`, line 46):
  ```
  DiodeElm 1 4          — emitter PN junction (model "x2n2646-emitter")
  VoltageElm 4 5        — VBB tap voltage reference
  CCVSElm 4 5 6 0       — current-controlled voltage source
  ResistorElm 0 6       — loop resistor
  VCCSElm 5 7 5 7 6 7 5 — voltage-controlled current source (5-node)
  CapacitorElm 5 7      — state capacitor
  ResistorElm 7 2
  ResistorElm 3 5
  ```
  External nodes `{ 1, 2, 3 }` map to E, B2, B1 (line 47).
- The nonlinearity that gives the UJT its characteristic negative
  resistance between E and B1 is the **feedback loop CCVS → VCCS →
  capacitor** inside the composite — not a direct semiconductor
  equation. The DiodeElm handles the emitter PN forward drop; the
  CCVS/VCCS network models the η fraction and the regenerative
  avalanche.
- Sets `simulator().adjustTimeStep = true` at construction (line 55)
  because "model doesn't work without time step auto-adjust" — the
  tight feedback loop becomes numerically unstable at large Δt.
- `dump()` uses `dumpWithMask(0)` (line 61) so **no** child element is
  serialized — the sub-netlist is bundled as the hard-coded
  `ujtModelDump` and re-parsed on every reload. This is opposite to
  a `CustomCompositeElm` (which dumps its children) and is the normal
  pattern for built-in composites.
- `FLAG_ESCAPE` is set (line 52) so nested escape is used by the parser
  even though nothing is being dumped.
- Uses the `x2n2646-emitter` built-in DiodeModel that is seeded as
  `internal` in `DiodeModel.java:81-114`.

### OptocouplerElm — LED + phototransistor with non-linear coupling (`element/OptocouplerElm.java:12`)

- Confirmed extends **`CompositeElm`**. Composite netlist (line 17):
  ```
  DiodeElm 6 1      — the input LED (using default DiodeModel)
  CCCSElm 1 2 3 4   — current-controlled current source, current through
                      the LED (node 1→2) drives a current into node 3→4
  NTransistorElm 3 4 5 — the output phototransistor
  ```
  External nodes `{ 6, 2, 4, 5 }` map to LED anode, LED cathode,
  collector, emitter (line 18).
- **The CCCS is the magic**: in `initOptocoupler` (line 48) a
  piecewise 5th-order polynomial expression from the Clare CLA03
  datasheet application note is installed via `cccs.setExpr(...)`:
  ```
  max(0, min(.0001,
    select(i - .003,
      (-8e10·i⁵ + 8e8·i⁴ - 3e6·i³ + 5177.2·i² + .2453·i − 5e−5)·1.04/700,
      (9e6·i⁵ − 998113·i⁴ + 42174·i³ − 861.32·i² + 9.0836·i − .0078)·.945/700
    )))
  ```
  — two separate polynomial fits for the low-current and high-current
  regions of the datasheet transfer curve, clamped to [0, 100 μA],
  divided by β=700 so the CCCS delivers base current and the
  NTransistor multiplies by β back up to collector current. Comment at
  line 47 points to the CEL AN-3017 app note as the data source.
- `transistor.setBeta(700)` (line 52) is applied after construction so
  the composite's NTransistorElm uses a high β and the combination
  matches the observed CTR curve.
- `getConnection(n1, n2)` is overridden (line 61) to return true **only
  within the same pair** (LED side = nodes 0,1; transistor side = nodes
  2,3), which tells the simulator that the LED side is galvanically
  isolated from the transistor side — no DC path between them, matching
  the physical device.
- Rendering (line 65) delegates to the sub-elements' own `draw` methods
  (`diode.draw(g)`, `transistor.draw(g)`) for the internal glyphs, plus
  a bounding rectangle and two little arrows representing the photon
  path.

## Validation Rules

- `DiodeElm.stepFinished()` (line 287) forces non-convergence if
  `current` is NaN or |current| > 1e12, and clamps the value. Same
  pattern is copied per-diode in `LEDArrayElm.stepFinished()` (line 143).
- `SCRElm.doStep()` coerces non-finite or non-positive
  `triggerI`/`holdingI` to 1e-12 and flags non-convergence (lines 261-268).
  `SCRElm.calculateCurrent()` (line 293) does the same for
  `gresistance`/`aresistance`.
- `DiacElm.calculateCurrent()` (line 135) zeroes the current and flags
  non-convergence if the selected resistance is non-finite or zero.
- `TunnelDiodeElm.doStep()` (line 163) falls back to a small conductance
  on non-finite geq/i so NaN doesn't leak into the matrix.
- `DiodeElm.setup()` (line 90) uses `getModelWithNameOrCopy` so a
  reference to a missing model resolves to a copy of the current model
  under the requested name — never throws.
- `DiodeModel.breakdownVoltage` is coerced to its absolute value in
  `DiodeModel.java:293` (per shared-models analysis) — Zener elements
  cannot accidentally set a negative Vz.
- `SCRElm.setPoints()` / `TriacElm.setPoints()` collapse the element to
  zero length (`geom().setEndpoints(getX(), getY(), getX(), getY())`)
  if the lead is too short to fit the gate stub (SCRElm lines 153-155,
  TriacElm lines 157-159). `creationFailed()` from the base class then
  deletes the element.
- `LEDArrayElm.setChipEditValue` rejects grid dimensions outside [2, 16]
  (lines 203-217); silently drops edits otherwise.
- `VaractorElm.setCurrent(int, double)` (line 149) ignores the `int x`
  argument and always routes to `capCurrent`, implicitly asserting the
  element has exactly one voltage source.

## Integration Points

### Depends on

- **Shared models catalog** (`client/DiodeModel.java`):
  - `DiodeElm` (and all its subclasses via inheritance) binds by name
    via `getModelWithNameOrCopy`.
  - `LEDArrayElm` binds the shared `default-led` model for every diode
    in its grid (LEDArrayElm.java:81).
  - `SCRElm`/`TriacElm`/`DiacElm` use `setupForDefaultModel()` — they do
    *not* expose a model to the user.
  - `UnijunctionElm` references the `x2n2646-emitter` built-in
    indirectly through the embedded `DiodeElm` sub-element.
- **Shared solver** (`client/Diode.java`): every element in the category
  except `TunnelDiodeElm`, `UnijunctionElm` (indirectly), and
  `OptocouplerElm` (indirectly) owns at least one `Diode` instance.
  Confirmed by `grep -l "new Diode("` = `DiodeElm.java:52,60`,
  `LEDArrayElm.java:83`, `SCRElm.java:79`, `TriacElm.java:72,74`,
  `DiacElm.java:61-62`, plus `TransistorElm.java:52,60` outside the
  category.
- **Element-base contract** (see `element-base` analysis): all 11
  elements implement `nonLinear()`, `stamp()`, `doStep()`,
  `setPoints()`, `draw()`, `getDumpType()`, `getInfo()`, JSON
  serialization hooks. Non-standard overrides:
  - `SCRElm`/`TriacElm`/`UnijunctionElm`/`OptocouplerElm` override
    `getPostCount()` (to 3 or 4) and `getPost(int)`.
  - `SCRElm`/`TriacElm` override `getCurrentIntoNode(int)` because the
    default only handles 2-post elements.
  - `VaractorElm` overrides `getVoltageSourceCount()` (returns 1) and
    `getInternalNodeCount()` (returns 1) and exploits the default
    single-`voltSource` field.
  - `LEDArrayElm` inherits `ChipElm`'s pin machinery and overrides
    `isDigitalChip()` to false.
- **Composite base** (`CompositeElm`): `UnijunctionElm` and
  `OptocouplerElm` call `loadComposite` from their constructors;
  forwarding of `stamp/doStep/startIteration/stepFinished/reset` is
  inherited.
- **Simulator stamping primitives**: `stampNonLinear`, `stampResistor`,
  `stampConductance`, `stampCurrentSource`, `stampVoltageSource`,
  `updateVoltageSource` — accessed via `simulator()` on `CircuitElm`.
- **Editor + renderer**: `ElmGeometry` for endpoints/leads;
  `Graphics.fillPolygon`/`fillOval`; `setVoltageColor`/`setPowerColor`
  for per-lead voltage gradient; `draw2Leads`/`drawPosts`/`drawDots`
  for the current-dot animation. LEDElm uses `Color(int,int,int)`
  directly to render the lit-LED tint.

### Used by

- **Menu/palette** (via `docs/elements.md` catalog): `Drawing > Active
  Components > Diodes` lists Diode, Zener, Varactor/Varicap, Tunnel
  Diode (docs/elements.md:260-272). `LED` and `LED Array` live under
  `Outputs and Labels > Outputs` (lines 190-198). `SCR`, `Triac`,
  `Diac` under Active/Semiconductors around lines 303-319.
  `Unijunction Transistor` at line 319; `Optocoupler` at line 391.
- **Element factory** (`CircuitElmCreator`, outside this category) —
  dispatches dump-type tokens `'d'`, `'z'`, 162, 175, 176, 177, 203,
  206, 405, 407, 417 to the appropriate `(CircuitDocument, int, int,
  int, int, int, StringTokenizer)` constructor.
- **`SevenSegElm`** (noted in `shared-models` analysis) embeds
  `default-led` DiodeModel-parameterized diodes but sits outside this
  category — consumer of the same catalog without being a "diode element".
- **Scope**: all 2-terminal members satisfy the default
  `canViewInScope()` test; `TriacElm` explicitly overrides to `true`
  (line 303) even though it has 3 posts.

### External deps

- GWT: `com.google.gwt.user.client.Window` (DiodeElm's "read-only model"
  alert), `com.google.gwt.user.client.ui.Button` (edit-dialog
  buttons).
- JDK: `java.util.{Vector, Map, LinkedHashMap}`.

## Issues / Questions

1. **`DiacElm.state` is lost across save/reload.** Unlike `TriacElm`
   (which dumps `state` and round-trips it via JSON), `DiacElm.dump()`
   (line 75-77) omits `state`, and no `*JsonState` override exists —
   the element re-initializes as off and re-triggers on the next
   overvoltage. In most use cases this is invisible because Diacs
   spend most of their time off, but a paused/exported phase-control
   circuit mid-pulse will lose the conducting state.
2. **SCR latching logic is current-recomputed every step.** The
   inequality `−icmult·ic + ia·iamult > 1` lives inside `doStep()`
   rather than `startIteration()` (compare with `TriacElm`/`DiacElm`
   which use a boolean state). Consequence: during Newton iterations
   the SCR can oscillate between "on" and "off" stamps, burning
   iterations. The `|Δvac| > 0.01` and `|Δvag| > 0.01` non-convergence
   triggers compensate, but it is structurally noisier than the Triac
   approach.
3. **TunnelDiode's constants are hard-coded** — `pvp`, `pip`, `pvv`,
   `piv`, `pvpp` fit a 1N3712-ish curve and cannot be edited. No
   DiodeModel binding, no parameterization in the EditInfo. Users
   cannot simulate a germanium backward-diode or different peak-to-valley
   ratios without modifying the source.
4. **VaractorElm re-uses `model.fwdrop`** for its C(V) formula (line 119),
   but `DiodeModel.fwdrop` is described in the shared-models analysis
   as a UI-only hint populated by `updateModel()`. If a user-authored
   "advanced" diode model never calls `setForwardVoltage()`, `fwdrop`
   may be 0 and the capacitance formula will divide by zero / produce
   Inf. No guard.
5. **LEDArrayElm dedicates no user control over the diode model** — the
   `default-led` name is hardcoded (line 81) and there is no
   `modelName`/`model` field. Customizing the forward drop of an LED
   matrix requires editing `default-led` globally, which affects every
   single-LED instance as well.
6. **LEDArrayElm.stamp() allocates `diodes[sizeX·sizeY]` every call.**
   Because `stamp()` is part of topology analysis and can run each
   time the circuit is analyzed (e.g. after a grid-size edit), this is
   acceptable — but the per-diode `Diode` helpers are not pooled, so
   a 16×16 grid allocates 256 objects on each resize.
7. **OptocouplerElm ignores `getEditInfo`** (returns null, line 217)
   — the CTR polynomial, the LED model, and β=700 are all uneditable
   from the UI. A user who wants a 4N25 vs. a CLA03 must source-edit.
8. **UnijunctionElm forces `simulator.adjustTimeStep = true`** (line 55)
   without any way to turn it back off per-circuit. One UJT in a
   circuit will make every other transient adaptive — usually fine,
   but surprising.
9. **SCR/Triac/Diac use `.01 Ω` / `.0105 Ω` / `500 Ω` "on" resistances
   as magic numbers.** These are not exposed as editable parameters.
   For realistic simulation of high-current SCRs the on-state voltage
   drop should be tunable; today it's a compile-time constant.
10. **LEDArrayElm silently overrides commented-out `getConnection`**
    (line 191-192) — "this is true but it causes strange behavior with
    unconnected pins so we don't do it". The correct connectivity
    (any row/col share conductive path only through an energized LED)
    is therefore not reported to the simulator; this may cause
    unexpected connectivity analysis but is documented.
11. **Gate-fix flag inconsistency.** `SCRElm` has `FLAG_GATE_FIX` which
    gates whether `setPoints` uses the modern `setEndpoints(...)` API
    or the legacy direct-field assignment. New instances set the flag
    (line 50); legacy dumps do not. `TriacElm` has no equivalent flag
    — it always uses the modern path. Asymmetry in migration handling.
12. **Model binding for SCR/Triac/Diac is invisible.** They call
    `setupForDefaultModel()` which resolves `DiodeModel.getDefaultModel()`
    by name ("default"). A user who renames the `default` DiodeModel
    (silently supported per shared-models issue #2) will orphan these
    elements.
13. **TunnelDiode's `reset()`** zeroes node voltages but does not reset
    `lastvoltdiff` to 0 (it does — line 91). But unlike `Diode.reset()`
    in the shared helper, there is no shared reset path, so if the
    internal model is ever unified with `Diode.java` someone must
    remember to port the reset semantics.
14. **Optocoupler `getConnection(n1, n2)` uses `n1/2 == n2/2`** (line
    61-63) which assumes nodes are ordered (LED-anode, LED-cathode,
    collector, emitter). Any reordering of external pins would
    silently connect what should be isolated sides — no test guards
    this.
15. **Dump type 175 (TunnelDiode), 176 (Varactor), 177 (SCR) are
    consecutive** — a cluster that originated together. But 203 (Diac),
    206 (Triac), 405 (LED Array), 407 (Optocoupler), 417 (Unijunction)
    are scattered. Same centralized-dump-type-registry concern that
    appeared in the element-base analysis issue #9.

## Suggested Concept Boundary

**One concept, `diodes-and-semiconductors`**, covering all 11 files.
Rationale:

- They share a single solver contract (`client/Diode.java` +
  `DiodeModel` catalog). Splitting into "PN-junction devices",
  "thyristors", "composite semis" would force each concept to restate
  the `Diode.stamp/doStep` usage pattern.
- The three structural patterns (DiodeElm-subclass, hand-rolled
  multi-Diode, CompositeElm-subclass) are variations on *how to
  compose* the shared helper, not conceptually different devices.
- Cross-references are dense: LEDElm↔DiodeElm, ZenerElm↔DiodeElm,
  VaractorElm↔DiodeElm, LEDArrayElm→Diode, SCR/Triac/Diac→Diode,
  UnijunctionElm embeds DiodeElm, OptocouplerElm embeds DiodeElm +
  TransistorElm.
- From a beginner-onboarding POV the useful single mental model is:
  *every device in this category is "some configuration of PN junctions
  plus optional passive or controlled glue"*. The three flavors of
  latching / negative resistance / light coupling are per-element
  callouts under the same shared engine.

If finer granularity is later required, the clean cuts are:

1. **pn-junction-family** — `DiodeElm`, `ZenerElm`, `LEDElm`,
   `VaractorElm`, `LEDArrayElm`. Share `DiodeModel` binding and the
   basic Shockley mechanics.
2. **thyristor-latch-family** — `SCRElm`, `TriacElm`, `DiacElm`.
   Share the "state-dependent variable resistor + diode(s)" pattern.
3. **special-semis** — `TunnelDiodeElm` (bespoke exponential triple),
   `UnijunctionElm` (composite CCVS/VCCS loop), `OptocouplerElm`
   (composite CCCS polynomial). Each is structurally unique.

Given the size of the category and density of shared mechanics, the
single-concept boundary is recommended.
