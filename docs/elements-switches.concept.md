# Switch Elements  {#C_ESW}

> **Code:** C_ESW
> **Status:** draft
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
> **Author:** OnboardDocGen
>
> **Depends on:** [C_ELB](./element-base.concept.md), [C_UTL](./util-locale-log.concept.md), [C_GEO](./geometry.concept.md), [C_RND](./rendering-primitives.concept.md)
> **Used by:** editor interaction (click-to-toggle), circuit element factory, renderer
> **Spike:** —
> **Specification:** [SP_ESW](./elements-switches.sp.md)
> **Plan:** [elements-switches.plan.md](./elements-switches.plan.md)
>
> **Backing analysis:** [.dev_flow/onboard/analysis/domain-core__cat-switches.md](../.dev_flow/onboard/analysis/domain-core__cat-switches.md)
>
> Concrete mechanical, analog (voltage-controlled) and latching switch
> elements. `SwitchElm` (SPST) is the base for the mechanical family;
> `AnalogSwitchElm` and `MotorProtectionSwitchElm` extend `CircuitElm`
> directly. Three distinct stamping strategies coexist.

## 1. Philosophy  {#C_ESW_01}

### 1.1. Core Principle  {#C_ESW_01_01}

The `switches` category documents how a single conceptual device — a
user- or voltage-controlled open/closed connection — is mapped onto MNA
through **three distinct stamping patterns** rather than the naive
"0 Ω / big R" swap:

1. **Mechanical / wire-equivalent** (`SwitchElm` family): when closed,
   the simulator's wire-closure pass collapses the two posts into a
   single node; multi-pole variants stamp **0 V voltage sources** so
   per-pole currents can be recovered.
2. **Analog (voltage-controlled)** (`AnalogSwitchElm` family): every
   step stamps a resistor (`r_on` ≈ 20 Ω, `r_off` ≈ 1e10 Ω) based on
   control-pin threshold; `nonLinear()==true` allows the decision to
   flip mid-Newton.
3. **Latching with physics** (`MotorProtectionSwitchElm`): three-phase
   fuse with `I²t` heat integrator; stamps `resistance` or
   `blownResistance = 1e9 Ω` based on a one-way-latched `blown` flag;
   cross-element couples to `RelayContactElm` via shared `label`.

### 1.2. Design Constraints  {#C_ESW_01_02}

- Only `SwitchElm` subclasses are **user-click-toggleable** — the editor
  hits `CircuitEditor.doSwitch()` which tests `instanceof SwitchElm`.
- Multi-pole variants force `isRemovableWire() == false` (comment "see
  #646" across the catalog) — the wire-closure optimizer cannot handle
  N-node collapses.
- Ganged operation via shared `label` (`SwitchElm.FLAG_LABEL`) or
  numeric `link` field (`Switch2Elm`, `MBBSwitchElm`) — peer-toggle is
  broadcast through `elmList`.
- `MotorProtectionSwitchElm` is excluded from click pathway but emits
  `setPosition` commands to every `RelayContactElm` sharing its label.

## 2. Domain Model  {#C_ESW_02}

### 2.1. Key Entities  {#C_ESW_02_01}

- **`SwitchElm`** — base SPST; 2 posts, 0 VS; `position ∈ {0=closed, 1=open}`.
- **`Switch2Elm`** — SP-N-throw (2..10); optional center-off (legacy only for
  2-throw); numeric `link` group.
- **`DPDTSwitchElm`** — parametric N-pole (2..10), 2-throw.
- **`PushSwitchElm`** — momentary SPST (17-line subclass).
- **`MBBSwitchElm`** — make-before-break SPDT; 4 positions rotating
  `{A, A+B, B, B+A}`.
- **`CrossSwitchElm`** — 2×2 crossbar; straight or swap.
- **`MotorProtectionSwitchElm`** — 3-phase I²t latching fuse; drives
  relay contacts by label.
- **`AnalogSwitchElm`** — voltage-controlled SPST; 3 posts (in, out, ctl).
- **`AnalogSwitch2Elm`** — voltage-controlled SPDT; 4 posts; stamps both
  branches simultaneously.

### 2.2. Data Flows  {#C_ESW_02_02}

Click toggle (mechanical only):

    CircuitEditor.onMouseDown → doSwitch(grid) → instanceof SwitchElm →
        getSwitchRect.contains(x,y) → switchElm.toggle() →
        ganged peers (same label / link) → simpleToggle() → needAnalyze

Analog switch:

    doStep → V(ctl) vs threshold → stampResistor(r_on | r_off) → Newton

Motor-protection latch:

    startIteration → heats[j] += i²·Δt - Δt·i2t/3 → heats[j] > i2t →
        blown = true → setSwitchPositions() → each RelayContactElm.label match

## 3. Mechanisms  {#C_ESW_03}

### 3.1. Core Algorithm  {#C_ESW_03_01}

**Pattern A — wire-closure + zero-volt source:** base `SwitchElm` only
reports `isWireEquivalent()==true` when closed; multi-pole variants stamp
per-pole `stampVoltageSource(common, throw, vsIdx, 0)` so the solver
assigns a Lagrange current row per pole.

**Pattern B — resistor pair:** `AnalogSwitchElm.doStep()` chooses
`r_on` / `r_off` by control-pin threshold; `FLAG_INVERT` complements,
`FLAG_PULLDOWN` stamps `r_off` from each data post to ground to mitigate
floating-node issues; `AnalogSwitch2Elm` stamps both throw branches.

**Pattern C — latching resistor with `I²t` physics:**
`MotorProtectionSwitchElm` per-phase: `heat += i²·Δt - Δt·i2t/3`; when
any phase `heat > i2t`, set `blown = true` (one-way until reset); stamp
`resistance` or `1e9 Ω`.

### 3.2. Edge Cases  {#C_ESW_03_02}

- `SwitchElm.calculateCurrent()` forces `current = 0` when open — avoids
  stale ghost currents.
- `MBBSwitchElm.getVoltageSourceCount()` mutates `both` as a side effect;
  relies on analyzer calling it before `stamp()`.
- `Switch2Elm.throwCount > 2` silently disables `momentary`.
- `AnalogSwitch2Elm.calculateCurrent` divides by `r_on` even for the open
  branch → reported current wrong magnitude when `r_off != r_on`.
- Dump-type "true"/"false" translated to position in legacy
  `SwitchElm` ctor; `LogicInputElm` inverts semantics.

## 4. Integration Points  {#C_ESW_04}

### 4.1. Dependencies  {#C_ESW_04_01}

- [C_ELB](./element-base.concept.md) — `CircuitElm`, `SwitchElm` base,
  lifecycle, `getSwitchRect`, flip helpers, JSON hooks.
- [C_GEO](./geometry.concept.md) — `ElmGeometry`, `Point`, `Rectangle`.
- [C_RND](./rendering-primitives.concept.md) — `Graphics`, `Context2d`
  text alignment.
- `CircuitEditor` — `doSwitch` / `heldSwitchElm` / `mouseUp` click pathway.
- `RelayContactElm` — cross-element coupling for
  `MotorProtectionSwitchElm`.
- `CustomLogicModel.escape/unescape` — label serialization.

### 4.2. API Surface  {#C_ESW_04_02}

- `toggle()` / `simpleToggle()` / `mouseUp()` (momentary) on mechanical
  switches; peer-toggle broadcast via `elmList`.
- `AnalogSwitchElm` exposes `setEditValue` rows for `r_on`, `r_off`,
  `threshold`, `FLAG_INVERT`, `FLAG_PULLDOWN`.
- `MotorProtectionSwitchElm.setSwitchPositions()` is the label-broadcast
  entry point; `reset()` clears heats and blown.
- Shortcuts: `s` (`SwitchElm`), `S` (`Switch2Elm`); others via menu.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initial version |
