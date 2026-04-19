# Implementation Plan: Element Base  {#PL_ELB}

> **Code:** PL_ELB
> **Status:** completed
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_ELB](./element-base.concept.md)
> **Specification:** [SP_ELB](./element-base.sp.md)
> **Depends on plans:** [PL_UTL](./util-locale-log.plan.md), [PL_GEO](./geometry.plan.md), [PL_RND](./rendering-primitives.plan.md)
> **Used by plans:** — (will be filled by higher layers)
>
> Reverse-engineered plan for the 11 base files in `client/element/`. Implementation is complete; this document records the delivered structure and outstanding backlog items extracted from the analysis.

## Goal

Provide the abstract contract every `*Elm` subclass implements — unifying simulation, topology, rendering, editing, and persistence behind one polymorphic base so the simulator, editor, and I/O framework can operate uniformly over ~135 concrete elements.

## Technology Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Language | Java 17 | Project baseline. |
| UI toolkit | GWT (JSNI, Canvas) | Existing web-delivery path; `Graphics` wraps HTML5 Canvas. |
| Geometry storage | Delegated to `ElmGeometry` | Single source of truth; prevents field drift. |
| Persistence | Dual: legacy text + JSON | Text for back-compat with Falstad format; JSON for modern export. |
| Dialog binding | `dialog.Editable` marker + `EditInfo` rows | Decouples element from concrete dialog widgets. |
| Abstract-ish `getDumpType` | Concrete that throws at runtime | GWT compiler workaround for OTAElm. |

## Progress

- [x] Phase 1 — BaseCircuitElm (static helpers)
- [x] Phase 2 — ElmGeometry (single-source-of-truth geometry)
- [x] Phase 3 — CircuitElm root + NodeState + Pin
- [x] Phase 4 — GraphicElm (drawing-only branch)
- [x] Phase 5 — SwitchElm (SPST base)
- [x] Phase 6 — ChipElm (multi-pin IC base) + Pin
- [x] Phase 7 — CompositeElm (element-of-elements)
- [x] Phase 8 — CustomCompositeElm + CustomCompositeChipElm
- [x] Phase 9 — CustomLogicElm (user-programmable logic)
- [x] Phase 10 — Inductor companion-model helper

## Phases

### Phase 1 — BaseCircuitElm (`client/element/BaseCircuitElm.java`) [DONE]

**Implements:** [SP_ELB_01_01](./element-base.sp.md#SP_ELB_01_01), [SP_ELB_06](./element-base.sp.md#SP_ELB_06)

Delivered: static formatters (`shortFormat`, `numFormat`, `formatNumber`×3, `getUnitText*`, `getSIPrefix`, `getVoltageText`, `getCurrentText`, `getTimeText`), integer math, geometry helpers (`distance`, `lineDistanceSq`, `interpPoint`×3, `calcArrow`, `createPolygon`×3), thick-line/polygon drawing, `addCurCount`, constants (`CURRENT_TOO_FAST`, `THICK_LINE_WIDTH`, `SCALE_*`).

### Phase 2 — ElmGeometry [DONE]

**Implements:** [SP_ELB_01_05](./element-base.sp.md#SP_ELB_01_05)

Delivered: endpoints + derived geometry, `setEndpoints`, `translate`, `dragTo`, `movePoint`, `flipX/Y/XY`, `calcLeads`, `adjustLeadsToGrid`, bounding-box API, owner hook `adjustDerivedGeometry`.

### Phase 3 — CircuitElm root [DONE]

**Implements:** [SP_ELB_01_01](./element-base.sp.md#SP_ELB_01_01), [SP_ELB_02](./element-base.sp.md#SP_ELB_02), [SP_ELB_05](./element-base.sp.md#SP_ELB_05)

Delivered: 50+ override points (simulation, topology, drawing, editing, persistence, JSON), `NodeState` nested, `Pin` nested, JSNI bridge (`addJSMethods`).

### Phase 4 — GraphicElm [DONE]

Delivered: `postCount == 0`, forced `_startpoint`/`_endpoint` JSON emission.

### Phase 5 — SwitchElm [DONE]

**Implements:** [SP_ELB_01_03](./element-base.sp.md#SP_ELB_01_03)

Delivered: position/posCount/momentary/label, toggle/simpleToggle/mouseUp, labeled-group linking, wire-equivalent when closed.

### Phase 6 — ChipElm [DONE]

**Implements:** [SP_ELB_01_02](./element-base.sp.md#SP_ELB_01_02)

Delivered: `pins[]`, `setupPins` (abstract), `execute`, `stamp`/`doStep`/`reset`/`setCurrent`/`setVoltageSource` overrides, bit-pack helpers, flag/side constants.

### Phase 7 — CompositeElm [DONE]

Delivered: `compElmList`, `compNodeList`, `loadComposite`, `VoltageSourceRecord`, BFS `getConnection`/`hasGroundConnection` with memoization, child forwarding for lifecycle, `dumpElements`/`dumpWithMask`.

### Phase 8 — CustomCompositeElm + CustomCompositeChipElm [DONE]

Delivered: model resolution via `CustomCompositeModel`, owned render chip, pin layout edit, dump type 410.

### Phase 9 — CustomLogicElm [DONE]

Delivered: truth-table rule engine (`0/1/?/+/-/a-z/A-Z` left-side, `0/1/a-z/_` right-side), tri-state internal nodes, `stampNonLinear` pairs, per-step hi-Z resistor swap.

### Phase 10 — Inductor helper [DONE]

**Implements:** [SP_ELB_01_06](./element-base.sp.md#SP_ELB_01_06)

Delivered: trapezoidal and backward-Euler companion models, `setup`/`reset`/`stamp`/`startIteration`/`doStep`/`calculateCurrent`.

## Backlog

Items deferred from current cycle (from `.dev_flow/onboard/analysis/domain-core__element-base.md` §Issues):

- **Bidirectional element↔dialog coupling (#2).** `CircuitElm` imports `dialog.EditInfo`/`dialog.Editable` directly (104 edges). Cleaner: move `Editable` + `EditInfo` into a neutral `contract/` package.
- **`voltSource` single-source assumption (#4).** Default `setVoltageSource` overwrites one field; silent bug for multi-source elements that forget to override.
- **God-class size (#3).** `CircuitElm` is 1955 LOC mixing 7 concerns. Extraction candidates: `ElmPersistence`, `ElmRendering`, `ElmPins`.
- **Deprecated JSON pin APIs (#14).** `getJsonPinNames`/`getJsonPinPosition` still in use despite `getPins()`/`Pin.getPosition()` replacements.
- **`getDumpType()` abstract workaround (#1).** GWT compiler quirk for OTAElm forces non-abstract + runtime throw. Revisit if/when GWT version changes.
- **Flag-bit collision risk.** No central registry for per-element flag bits (`FLAG_SMALL`, `FLAG_IEC`, `FLAG_LABEL`, `FLAG_COS`, `FLAG_PULSE_DUTY`, `FLAG_ESCAPE`, `FLAG_BACK_EULER`, `FLAG_CUSTOM_VOLTAGE`, `FLAG_FLIP_*`, `FLAG_SHOW_LABEL`, `FLAG_SCHMITT`, `FLAG_CLOCK`) — all live on `int flags` with hand-assigned ordinals; no guard against re-use when new elements are added.
- **`BaseCircuitElm.formatNumber` cross-layer edge (#5).** `util/PerfMonitor` → `element/BaseCircuitElm` exists only for `formatNumber`. Moving helpers to `util/` would cut the edge.
- **`Inductor` in element/ package (#6).** Not a `CircuitElm`; consider `physics/` sub-package.
- **`ChipElm.setVoltageSource` uses stdout (#7).** Should route through `CirSim.console` like other errors.
- **Dump-mask regex fragility (#8).** `[A-Za-z0-9]+ 0 0 0 0` assumes alphanumeric dump tokens.
- **Magic dump types (#9).** `208`, `410`, etc. — central `DumpTypes` registry would help.
- **Silent half-init on missing model (#10).** `CustomCompositeElm.updateModels()` returns silently; no user-visible error.
- **Multi-port `getCurrentIntoNode` override hazard (#12).** New multi-post elements that forget to override default report wrong currents.
- **O(N) overlap check in `allowMove` (#13).** Will not scale past hundreds of elements.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
