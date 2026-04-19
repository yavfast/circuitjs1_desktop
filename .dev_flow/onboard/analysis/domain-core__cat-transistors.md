# Module Analysis: domain-core / category — transistors-and-tubes

> **Path:** `src/main/java/com/lushprojects/circuitjs1/client/element/` (13 files)
> **Layer:** 2 (SCC-A, element catalog)
> **Analyzed:** 2026-04-18
> **Files:** 13 source (`TransistorElm`, `NTransistorElm`, `PTransistorElm`, `MosfetElm`, `NMosfetElm`, `PMosfetElm`, `JfetElm`, `NJfetElm`, `PJfetElm`, `DarlingtonElm`, `NDarlingtonElm`, `PDarlingtonElm`, `TriodeElm`), 0 tests.

## Purpose

The **transistors-and-tubes** category packages every **three-terminal
(plus body) active nonlinear device** that the simulator supports:

- **Bipolar junction transistors (BJTs)** — Gummel-Poon model, parametrised
  by a shared `TransistorModel` catalog entry.
- **MOSFETs** — SPICE Level-1 square-law model, plus an optional pair of
  internal body diodes (via the `Diode` solver helper).
- **JFETs** — reuses the MOSFET square-law channel and adds **two gate PN
  junctions** (gate-source + gate-drain `Diode` instances) to model gate
  leakage / forward conduction.
- **Darlington pair** — a **composite element** (not a BJT subclass)
  built from two `NTransistorElm` instances wired B-B/C-B-E.
- **Vacuum triode** — Koren-style three-halves-power plate law
  (`ids = (Vgk + Vpk/mu)^1.5 / kg1`) with a fixed `6 kΩ` grid current
  resistor when the grid is positively biased.

All members of the category are **3-post** (except body-terminal MOSFETs,
which are 4) and **zero-voltage-source** — they work by stamping
nonlinear conductances + right-hand-side equivalent current sources into
the MNA matrix, not by asking the simulator for a `voltSource` index.
Only **BJTs** resolve a shared-model parameter set
(`TransistorModel`, `client/TransistorModel.java`); MOSFET/JFET/Triode
keep their physics parameters as plain per-instance fields (correcting
the note in `shared-models.md §Used by` that explicitly excludes them).

## Per-element catalog

| Element | Extends | Model | Polarity | Posts | V-sources | Non-lin. iters? | File:lines |
|---|---|---|---|---|---|---|---|
| `TransistorElm` (abstract-ish base) | `CircuitElm` | `TransistorModel` (Gummel-Poon) + per-elm `beta` | `pnp` flag: `+1` NPN / `-1` PNP | 3 (B,C,E) | 0 | yes — `nonLinear()=true` (`TransistorElm.java:108`) | `TransistorElm.java:1-807` |
| `NTransistorElm` | `TransistorElm` | inherited | `pnp=+1` (via `super(.., false)`) | 3 | 0 | yes | `NTransistorElm.java:1-37` |
| `PTransistorElm` | `TransistorElm` | inherited | `pnp=-1` (via `super(.., true)`) | 3 | 0 | yes | `PTransistorElm.java:1-37` |
| `MosfetElm` (base) | `CircuitElm` | per-instance `vt`, `beta` (no shared model) + 2 `Diode` helpers for body diode | `pnp` flag + `FLAG_PNP=1` in dump flags | 3 (G,S,D) or 4 with `FLAG_BODY_TERMINAL` | 0 | yes — `nonLinear()=true` (`MosfetElm.java:124`) | `MosfetElm.java:1-728` |
| `NMosfetElm` | `MosfetElm` | inherited | `pnp=+1` | 3/4 | 0 | yes | `NMosfetElm.java:1-36` |
| `PMosfetElm` | `MosfetElm` | inherited | `pnp=-1` | 3/4 | 0 | yes | `PMosfetElm.java:1-36` |
| `JfetElm` | **`MosfetElm`** (!) | per-instance `vt`, `beta` + 2 `Diode` helpers for **gate** junctions (not body) | `pnp` flag (inherited) | 3 (G,S,D) — forces `showBulk()=false`, no body terminal | 0 | yes (inherited) | `JfetElm.java:1-301` |
| `NJfetElm` | `JfetElm` | inherited | `pnp=+1` | 3 | 0 | yes | `NJfetElm.java:1-32` |
| `PJfetElm` | `JfetElm` | inherited | `pnp=-1` | 3 | 0 | yes | `PJfetElm.java:1-13` |
| `DarlingtonElm` | **`CompositeElm`** (!) | transitively two `NTransistorElm`s inside — `TransistorModel` applies to each child | `pnp` stored on `DarlingtonElm` **and** written into `compElmList.get(i).pnp` at construction | 3 external (B,C,E) | 0 external (children each report 0) | yes (any child nonlinear → composite nonlinear) | `DarlingtonElm.java:1-174` |
| `NDarlingtonElm` | `DarlingtonElm` | inherited | `pnp=+1` | 3 | 0 | yes | `NDarlingtonElm.java:1-16` |
| `PDarlingtonElm` | `DarlingtonElm` | inherited | `pnp=-1` | 3 | 0 | yes | `PDarlingtonElm.java:1-14` |
| `TriodeElm` | `CircuitElm` | per-instance `mu`, `kg1` only | N/A (vacuum tube — no polarity split) | 3 (plate, grid, cathode) | 0 | yes — `nonLinear()=true` (`TriodeElm.java:54`) | `TriodeElm.java:1-325` |

Dump-type tokens observed: BJT = `'t'` (116, `TransistorElm.java:121`),
MOSFET = `'f'` (102, `MosfetElm.java:162`), JFET = `'j'`
(`JfetElm.java:199`), Darlington = `400` (`DarlingtonElm.java:47`),
Triode = `173` (`TriodeElm.java:70`).

## Shared solver logic

### BJT — Gummel-Poon, inlined in `TransistorElm.doStep` (no separate solver class)

The BJT is the **only** element in this category that pulls physics
parameters from a shared `TransistorModel` catalog entry
(`TransistorElm.java:50,102`). The entire Gummel-Poon (SPICE bjtload.c)
step is inlined in `TransistorElm.doStep()`
(`TransistorElm.java:310-509`) — there is no `Transistor` helper class
analogous to the `Diode` helper used by MOSFET/JFET.

Flow per Newton iteration:

1. Read `vbc = pnp*(V[0]-V[1])`, `vbe = pnp*(V[0]-V[2])` (line 311-312).
2. Convergence check via `CircuitMath.isConverged` against
   `lastvbc`/`lastvbe` (line 313-316).
3. `gmin` starts at `1e-12`; grows exponentially once `subIterations >
   gminStartIters` (default 100, lowered to 20 during
   `ConvergencePanicLevel > 0`) — classic "gmin stepping" for
   convergence (line 321-339).
4. `limitStep(vnew, vold)` (line 279-302) — **SPICE-style junction
   voltage limiting** with `maxDelta = 2*vt` (or `20*vt` in panic mode).
   When limiting fires it sets `simulator().converged = false`.
5. `vcrit = vt * log(vt / (sqrt(2)*IS))` from
   `TransistorElm.java:104` — the SPICE critical-voltage formula.
6. Compute forward/reverse diode currents `cbe`, `cbc` with emission
   coefficients `NF`, `NR` and leakage currents `ISE`/`ISC` × `NE`/`NC`
   (lines 362-401). `exp` argument clamped to `±700` to avoid overflow.
7. Base charge `qb` — full Gummel-Poon formula with early voltages
   (`VAF`, `VAR` via `invEarlyVoltF/R`) and knee currents (`IKF`, `IKR`
   via `invRollOffF/R`) (lines 405-418).
8. Numerical safety: `qb`, `dqbdve/dqbdvc`, `ceqbe`, `ceqbc`, all four
   conductances `gpi/gmu/go/gm`, and all three currents
   `ib/ic/ie` are tested for NaN/Infinity and clamped / zeroed
   (lines 422-488). This is the **recovery-from-blown-step** pattern
   shared with the rest of the non-convergence work.
9. Stamp a dense 3×3 conductance block + 3-entry RHS via
   `stampMatrix` / `stampRightSide` (lines 492-507). Stamp pattern is
   the standard Ebers-Moll / linearised-BJT companion — base row has
   `gpi+gmu`, collector row has `gmu+go`, emitter row has `gpi+gm+go`.
10. `stepFinished()` (lines 685-703): clamp `|ic/ib/ie| ≤ 1e12`, mark
    `converged=false` if exceeded, bump `badIters` counter — once
    `badIters > badIterLimit` (default 200, but 1,000,000 in panic
    mode) the convergence escalation stops pushing `gmin` higher.

`stamp()` is minimal: `stampNonLinear` on all three nodes
(`TransistorElm.java:304-308`).

### MOSFET — SPICE Level-1, inlined in `MosfetElm.calculate`

`MosfetElm.doStep()` delegates to `calculate(boolean finished)`
(`MosfetElm.java:419-523`). Four-region selection:

- `vgs < vt` — cutoff → treated as a `gmin` resistor
  (line 472-477) so the matrix stays non-singular (`mode=0`).
- `vds < vgs - vt` — triode/linear region → `ids = β*((vgs-vt)*vds - vds²/2)`,
  `gm=β*vds`, `Gds=β*(vgs-vds-vt)` (line 478-483, `mode=1`).
- else — saturation → `ids = ½·β*(vgs-vt)²`, `gm=β*(vgs-vt)`,
  `Gds=gmin` (a small residual to avoid singular matrix — line 484-491,
  `mode=2`).

Key details:
- **Source/drain auto-swap**: if `pnp*V[1] > pnp*V[2]`, source and drain
  labels are swapped before the square-law eval (line 450-453), and
  `ids` sign is flipped back at the end (line 503-506). This is why
  MOSFETs in CircuitJS are **symmetric** despite real-world asymmetry.
- **Step limiting**: `maxDelta = 0.5` (5.0 in panic mode) on S and D
  (line 432-443); convergence check done via `nonConvergence(last, now)`
  helper (`MosfetElm.java:389-407`) which loosens tolerance as
  `subIterations` grows.
- **Body diodes** (optional, gated by `FLAG_BODY_DIODE` + `showBulk()`):
  owned as two `Diode` helper instances from
  `client/Diode.java`. Stamped in `MosfetElm.stamp()` (line 376-386)
  with polarity-aware ordering; stepped via `Diode.doStep` in
  `calculate()` (line 493-497). This is the **direct reuse** of the
  `Diode` solver that `shared-models.md` calls out as the BJT-side
  counter-example.
- **stampMatrix** pattern (lines 513-522): 2×3 block into drain/source
  rows (`Gds`, `gm`); RHS = `rs = -pnp*ids0 + Gds*vds + gm*vgs`.
  Gate row is **not** stamped — gate current is zero in the square-law
  model, all channel current is assumed to balance between S and D.

### JFET — reuses MOSFET square-law + two gate PN junctions

`JfetElm extends MosfetElm`. The only additions:
- Two more `Diode` instances (`diodeGS`, `diodeGD`,
  `JfetElm.java:34-46`) stamped in `JfetElm.stamp()` (line 149-167)
  with polarity-aware orientation (anode on gate for n-JFET, cathode on
  gate for p-JFET).
- `JfetElm.doStep()` calls `super.doStep()` (= MOSFET square-law) then
  steps the two gate diodes (line 169-182).
- `calculateCurrent()` (line 184-192) — uses the default
  `CircuitElm.setNodeVoltage → calculateCurrent` hook to recover gate
  currents from the two diodes post-solve.
- `getCurrentIntoNode(n)` override (line 103-110) routes the three
  per-post currents as {gate = -(Igs+Igd), source = +Igs+ids, drain =
  -ids+Igd}.
- Default `vt = -4 V`, `β = 0.00125`
  (`JfetElm.getDefaultThreshold/Beta`, lines 202-213 — from
  Hayes/Horowitz p.155).
- `showBulk()` is overridden to always return `false` — this suppresses
  the MOSFET-inherited body terminal and the body-diode code path
  (no gate→body diodes stamped).

This is a **clean reuse** of the square-law channel physics; the
"JFET = MOSFET-with-two-gate-diodes" relation is captured purely by
inheritance.

### Triode — Child-Langmuir three-halves power law

Simpler than the transistors. `TriodeElm.doStep()`
(`TriodeElm.java:172-231`):

1. Read plate/grid/cathode node voltages, apply `±0.5 V` per-iteration
   limit on grid and cathode voltages (line 177-184).
2. Convergence test: if any of `|V[i]-lastV[i]| > 0.01` then
   `simulator.converged = false` (line 188-194).
3. Compute `vgk`, `vpk`, and the Koren intermediate
   `ival = vgk + vpk/mu`.
4. **Grid-conduction branch** (line 200-204): when `vgk > 0.01`, stamp
   a fixed `6 kΩ` resistor between grid and cathode (`gridCurrentR`
   field, line 33); otherwise stamp `1e8 Ω` to keep the matrix
   non-singular.
5. **Plate-current branch** (line 205-217): if `ival < 0` (plate
   cut-off), stamp `Gds = 1e-8`; else
   `ids = ival^1.5 / kg1`, with partial derivatives
   `Gds = q = 1.5·sqrt(ival)/kg1`, `gm = q / mu`.
6. Plate/cathode linearised stamp — **identical 2×3 MNA block shape as
   the MOSFET** (plate row ≅ drain, cathode row ≅ source;
   `TriodeElm.java:221-230`). Grid row is **not** stamped in the plate
   block (grid current goes through the explicit resistor).
7. Records per-terminal currents into `currentp`, `currentg`,
   `currentc` for animation dots and scope.

Defaults `mu=93`, `kg1=680` (≈ 12AX7).

## Polarity-specialization pattern — N vs P variants

**Both**, but strongly skewed toward "flag field":

1. **The physics is driven by the flag.** Every base class stores an
   `int pnp` field whose value is `+1` (N-type) or `-1` (P-type):
   `TransistorElm.pnp` (line 45), `MosfetElm.pnp` (line 36),
   `DarlingtonElm.pnp` (line 21). All equations multiply by `pnp`
   (e.g. `TransistorElm.java:311-312,442-444,472-473`;
   `MosfetElm.java:465-466,503-506`). So at runtime the polarity is a
   *scalar sign*, not dispatch.
2. **The subclass exists only as a constructor-wrapper + shortcut key +
   `getDumpClass()` override.** Every N/P subclass body is <40 LOC
   (compare NTransistorElm = 37 LOC, PJfetElm = 14 LOC). The subclass
   does three things and nothing else:
   - Pass `true`/`false` for `pnpflag` to the super ctor.
   - Override `getDumpClass()` to return the **base class**, so the
     text-format dump type stays unique per base and the importer
     re-creates the subclass via a factory lookup driven by
     `pnp`-sniff on the dumped line.
   - Override `getShortcut()` (only on NPN, NMOS, PMOS — not on JFET
     and Darlington, which are menu-only with no keyboard shortcuts).
3. **Persistence works through the base class only.** Only the base
   (`TransistorElm`, `MosfetElm`, `JfetElm`, `DarlingtonElm`) appears
   in the dump-type registry (`t`, `f`, `j`, `400`); the PNP/NPN bit
   is stored either as a parsed `pnp` token in the dump
   (`TransistorElm.java:68`, `DarlingtonElm.java:37`) **or** as a flag
   bit in `flags` (`MosfetElm.FLAG_PNP = 1`, line 37, used on line 70).
   JFET inherits the MOSFET convention.
4. **Consequence:** a `NTransistorElm` dumped to `.circuitjs` and
   re-imported may come back as `TransistorElm` with a positive `pnp`
   depending on the factory path. The `getDumpClass()` → base-class
   pattern is the project's way of **unifying the dump type code
   across subclasses** while keeping the subclass distinction for UI
   (menu entries, shortcuts).

In short: **polarity is a flag field that drives the physics; the
subclass is a thin menu/shortcut/"new blank element" convenience that
fixes the flag at construction.** Same pattern used by every diode
subclass (`ZenerElm`, `LEDElm`, `VaractorElm`) in the wider catalog.

## Darlington composition

`DarlingtonElm extends CompositeElm` — **not** `TransistorElm`
(`DarlingtonElm.java:15`). This is the **only multi-transistor element**
in the project and the composition strategy is minimalist:

- A **hard-coded netlist string** drives the composite construction:
  `"NTransistorElm 1 2 4\rNTransistorElm 4 2 3"`
  (`DarlingtonElm.java:23`). Node `4` is internal (first BJT's emitter
  → second BJT's base); external nodes are `{1=Base, 2=Collector,
  3=Emitter}` (`modelExternalNodes = {1,2,3}`, line 24).
- The parent `CompositeElm` constructor calls `loadComposite()` which
  dispatches the two `NTransistorElm` children through
  `CircuitElmCreator` — exactly the subcircuit path documented in
  `element-base.md §CompositeElm`.
- Polarity is applied **after** child construction: the Darlington ctor
  reaches into `compElmList` and mutates the `pnp` field on each
  `TransistorElm` in place (`DarlingtonElm.java:29-30`). This is why
  both NPN and PNP Darlingtons can share a single `NTransistorElm`-based
  child netlist — the child class is technically always an
  `NTransistorElm`, but its runtime polarity is overwritten by the
  Darlington-level flag. Fragile: any `TransistorElm.setup()`-like
  re-initialisation path that re-reads polarity from a model/config
  field would break this trick.
- Simulation is entirely delegated: `stamp()`, `doStep()`,
  `startIteration()`, `stepFinished()`, `reset()`, `nonLinear()`,
  `getPower()` all forward to the two children via
  `CompositeElm`'s default implementations. The Gummel-Poon step runs
  twice per Newton iteration, once per child, independently stamping
  their respective conductance blocks — the shared internal node
  (BJT1 emitter = BJT2 base) couples them through the matrix.
- **Dump type is `400`** (the first of the composite-element range
  `400..419`, next to `CustomCompositeElm`'s `410`).
- External post count = 3, voltage-source count = 0 (inherited from
  `CompositeElm` via sum-over-children; each child reports 0).

## TriodeElm peculiarities

- **No polarity split.** Vacuum tubes are unidirectional devices
  (electrons only from cathode to plate). `TriodeElm` has no `pnp`
  field, no N/P subclasses, no flag bit for orientation. Disables both
  `canFlipX()` and `canFlipY()` (lines 280-286) — even spatial
  flipping is forbidden (because flipping plate and cathode would
  reverse electron flow, which the model does not support).
- **Hard-coded grid current resistor** (`gridCurrentR = 6000`,
  `TriodeElm.java:33`). Not editable via `EditInfo`, not a
  `TransistorModel`-style parameter. This is the tube's forward-grid
  current limiter — represents the cathode→grid emission path when
  the grid goes positive.
- **`getConnection(int n1, int n2)` returns `!(n1==1 || n2==1)`**
  (line 253-255) — i.e. the **grid is galvanically isolated** from
  plate and cathode at the connectivity level. This is the right
  behaviour for the MNA connectivity analysis (wire-closure
  optimisation), because the grid only conducts through the stamped
  resistor, not a hard short.
- **Only two parameters**: `mu` (amplification factor, default 93) and
  `kg1` (perveance, default 680). Both mutable via `EditInfo` rows 0-1
  (line 257-270). No shared model catalog — unlike BJT, each triode
  carries its own constants.
- Only element in the category whose dump-type (`173`) is not part of
  any named range documented in the project.

## Validation rules

- **`TransistorElm.limitStep`** — SPICE-style Newton-step voltage
  limiting on both `vbe` and `vbc`; sets `converged=false` when
  limiting fires (`TransistorElm.java:287-300`). Coupled to
  `simulator().getConvergencePanicLevel()` to allow `maxDelta = 20*vt`
  when in panic (line 285).
- **`TransistorElm` NaN/Inf guards** (lines 422-488) — `qb`, `dqbdve`,
  `dqbdvc`, `gpi`, `gmu`, `go`, `gm`, `ceqbe`, `ceqbc`, `ib`, `ic`,
  `ie` all tested and replaced with safe defaults (0 or 1). This is
  the element-level portion of the **non-convergence recovery**
  infrastructure referenced in recent commit
  `fb4ee85 Enhance non-convergence recovery …`.
- **`TransistorElm.stepFinished` current clamp** — `|I| ≤ 1e12` A;
  over-limit trips `converged=false`
  (`TransistorElm.java:688-693`).
- **`TransistorElm.badIters`** — count of consecutive iterations that
  required extra gmin; thresholds at 200 (normal) / 1M (panic mode);
  governs whether gmin-stepping continues (`TransistorElm.java:56,
  333-339, 695-703`).
- **`MosfetElm.nonConvergence`** (lines 389-407) — adaptive
  convergence test: difference < 10 mV always OK; looser than
  0.1 % of value when `subIterations > 10`; even looser once
  `subIterations > 100`. Handles both low-beta (`beta < 1`) and
  high-beta (multiplier ×100) cases.
- **`MosfetElm` step limit** — `maxDelta = 0.5` V (5 V panic) on S and
  D node voltages per iteration (line 432-443).
- **`MosfetElm` cutoff/saturation gmin** — `Gds = max(1e-8,
  getExtraConvergenceGmin())` in both subthreshold and saturation to
  keep the matrix non-singular (line 471, 488).
- **`TriodeElm`** — fixed `±0.5 V` per-iteration limit on grid and
  cathode (lines 177-184); convergence tolerance `0.01 V`
  (lines 188-194). No panic-level adaptation, no badIters counter —
  triode is simple enough that aggressive limiting is rarely needed.
- **Breakdown limits** — **none** in this category. No zener-style
  reverse-breakdown branch on BJT, no gate-oxide breakdown on MOSFET.
  Extreme reverse bias simply saturates the exp().

## Integration points

### Depends on

- **element-base (same package):** `CircuitElm` (all except
  `DarlingtonElm`), `CompositeElm` (`DarlingtonElm`),
  `ElmGeometry` (for `adjustDerivedGeometry` overrides — `TransistorElm`
  overrides it to enforce `minDn = 16` pixels, `TransistorElm.java:268`;
  `MosfetElm` has an empty override placeholder, line 356).
- **shared-models (client/, SCC-A):**
  - `TransistorModel` — only `TransistorElm` (`TransistorElm.java:35,50,102`).
    MOSFET/JFET/Triode do **not** import it.
  - `Diode` (the solver helper, `client/Diode.java` — not a model!) —
    `MosfetElm` (2 instances for body diodes, lines 51,91-98),
    `JfetElm` (2 more instances for gate junctions, lines 34-46).
    `DiodeModel` is not directly referenced (each `Diode` is
    configured via `setupForDefaultModel()`).
- **Simulator core (client/):** `CircuitSimulator` — used for
  `stampMatrix`, `stampRightSide`, `stampNonLinear`, `stampResistor`
  (triode grid), `getConvergencePanicLevel`, `getExtraConvergenceGmin`,
  `subIterations`, `converged` flag. Accessed via the package helper
  `simulator()` (inherited from `CircuitElm`).
- **Math/utils:** `CircuitMath.isConverged` (BJT), `Scope` constants
  (`VAL_IB/IC/IE/VBE/VBC/VCE/POWER`), `StringTokenizer`, `Point`,
  `Polygon`, `Graphics`.
- **UI plumbing:** `dialog.EditInfo`, `Checkbox`, `Choice`, `Button`
  (all via `getEditInfo`/`setEditValue`), `util.Locale`.
- **JSON I/O:** `io.json.UnitParser` (JFET only, for
  `threshold_voltage` parse — `JfetElm.java:276-279`).

### Used by

- `CircuitElmCreator` (element factory; dispatches on dump-type code to
  the 6 base+3 composite constructors).
- `MenuManager` (category entries — "Active Components" submenu,
  bipolar & FET sub-submenus per `docs/elements.md:273-322`).
- `TransistorModel.updateModel()` → `CircuitSimulator.updateModels()` →
  every `TransistorElm` that references the edited model re-runs
  `setup()` to pick up the new Gummel-Poon constants
  (`TransistorElm.java:128-130`). This is the `SimulationContextAware`
  fan-out described in `shared-models.md §SimulationContextAware
  propagation`.
- `EditTransistorModelDialog` (referenced from
  `TransistorElm.java:666,675` via `getDialogManager()`) — UI for
  editing the shared `TransistorModel` or creating a branched copy.

### Helper classes (non-element but relevant)

- `client/Diode.java` — the two-node PN-junction solver. Used as a
  **direct field** by `MosfetElm` (body diodes, 2 instances) and
  `JfetElm` (gate diodes, 2 more instances). This is the "`Diode`
  helper reused inside non-diode elements" split called out in
  `shared-models.md §Separation: Diode.java vs DiodeElm.java vs
  DiodeModel.java`.
- `client/TransistorModel.java` — Gummel-Poon parameters only. No
  analogous `Transistor.java` solver helper exists; the solver is the
  inlined Gummel-Poon block in `TransistorElm.doStep`.

## Issues / questions

1. **No `Transistor.java` solver helper, unlike `Diode.java`.** The
   Gummel-Poon step (~160 LOC) lives inline in `TransistorElm.doStep`
   (`TransistorElm.java:310-509`). `DarlingtonElm` therefore cannot
   reuse the step as a library call — it has to instantiate whole
   `NTransistorElm` children via `CompositeElm`. Extracting a
   `Transistor` helper mirroring `Diode`'s shape would let
   `DarlingtonElm` stack two solver instances without a composite
   sub-netlist.
2. **`DarlingtonElm.modelString` hard-codes `NTransistorElm` for both
   PNP and NPN.** Polarity is then mutated in-place on the child
   after construction (`DarlingtonElm.java:29-30`). Any future change
   that makes `TransistorElm.setup()` re-derive behaviour from the
   model file or dump-class name would break the trick. A cleaner
   approach: two `modelString` constants (N and P) and pick the right
   one in the ctor.
3. **`JfetElm extends MosfetElm`.** The inheritance models the physics
   relation (both are square-law) but also drags in the body-diode
   code path that JFET has to disable via `showBulk()=false`
   (`JfetElm.java:194-196`). The `FLAG_BODY_TERMINAL` / `bodyTerminal`
   logic in the MOSFET base is all dead code for JFET. Extracting a
   common `SquareLawChannel` helper could avoid the inheritance quirk.
4. **Polarity as scalar is error-prone.** `pnp` is `int` not `enum` —
   `+1`/`-1` is a magic convention. Multiple sites compute
   `pnp*(V[0]-V[2])` (BJT), `pnp*vs[1]>pnp*vs[2]` (MOSFET swap), and a
   typo would silently flip polarity without a test catching it.
5. **`TransistorElm.stamp` emits `System.out.println` on non-convergence**
   (`TransistorElm.java:314`) — inconsistent with the rest of the
   codebase, which routes errors through `CirSim.console`.
6. **`MosfetElm.doStep` relies on `startIteration` never running** —
   the class has no `startIteration()` override, so state like
   `lastv0/1/2` is updated purely inside `calculate()`. This is fine
   under the element-base contract but makes state transitions
   harder to reason about. Compare `TransistorElm` which uses
   `lastvbc/lastvbe` the same way without `startIteration`.
7. **`TriodeElm` has no shared model / catalog.** Unlike BJTs, every
   triode instance stores its own `mu`/`kg1`. If the codebase grows to
   include pentodes / beam tetrodes, a `TubeModel` parallel to
   `TransistorModel` would avoid copy-paste.
8. **`TriodeElm.gridCurrentR` is a hard-coded 6 kΩ** (line 33, not
   editable). Appropriate for a 12AX7 but not universal.
9. **Darlington dump type (400) and Triode dump type (173) are magic
   numbers** without a central registry — same issue raised for
   `CustomCompositeElm` (410) in `element-base.md §Issues #9`.
10. **`JfetElm.getConnection` always returns true** (line 225-227) —
    this says every JFET pin is electrically connected to every other
    JFET pin, which is incorrect at the connectivity-analysis layer
    (the gate is isolated from S/D except through the gate diodes).
    The MOSFET default is the `CircuitElm` default (`return true`)
    which has the same theoretical issue but is masked by the fact
    that MOSFETs rarely see wire-collapse optimisation across G/S/D.
    Compare `TriodeElm` which correctly returns
    `!(n1==1 || n2==1)` for the grid.
11. **MOSFET source/drain auto-swap** (`MosfetElm.java:450-453`) makes
    the MOSFET **symmetric** in the simulation — real MOSFETs are
    asymmetric (source has a body diode in parallel; drain doesn't,
    except for the body-diode FETs). The body-diode branch partially
    compensates, but the asymmetry is not fully captured.

## Suggested concept boundary

**A single `transistors-and-tubes` concept covering all 13 files** is
the right granularity:

- Shared architecture: all 3-post nonlinear stamping elements that
  compute their own linearised conductance block + RHS companion.
- Shared solver patterns: voltage limiting, gmin stepping, NaN/Inf
  safety, badIters convergence-failure escalation.
- Shared polarity pattern: `pnp` flag + thin subclass wrappers.
- Shared dependency on `element-base` contract + optional
  `TransistorModel` / `Diode` helper integration.
- The Darlington/Triode outliers are variations on the theme — a
  composite and a non-polar variant — not independent contracts.

If finer granularity is needed, the clean cuts are:

1. **bjt** — `TransistorElm` + N/P + `DarlingtonElm` + N/P (all
   share the Gummel-Poon engine, directly or via composition).
2. **fet** — `MosfetElm` + `JfetElm` and N/P subclasses (share the
   square-law channel and the `Diode`-helper pattern).
3. **tube** — `TriodeElm` alone (Koren three-halves-power, no polarity).

The dependency graph does not require this split — all 13 files are
within the same category-slice of the SCC-A element layer.
