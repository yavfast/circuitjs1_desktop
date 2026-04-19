# Implementation Plan: App Controller  {#PL_APC}

> **Code:** PL_APC
> **Status:** completed
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_APC](./app-controller.concept.md)
> **Specification:** [SP_APC](./app-controller.sp.md)
> **Depends on plans:** [PL_SIM](./simulator-engine.plan.md), [PL_DOC](./document-model.plan.md)
> **Used by plans:** —
>
> Retrospective implementation plan captured from existing code
> (`CirSim.java`) during onboarding.

## Goal

Document the GWT UI shell + JS bridge + bootstrap as shipped.

## Technology Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| UI toolkit | GWT (Java → JS transpile) | legacy/historical, cross-browser canvas |
| Bridge | JSNI into `$wnd.CircuitJS1` | single global, easy to script from host pages |
| Tab model | One controller, many `CircuitDocument`s | swap under `bindDocument` |
| Speed curve | `0.1 * exp((v-61)/24)` | covers 9 decades with a single scrollbar |

## Progress

- [x] Phase 1 — GWT entry + shell construction
- [x] Phase 2 — Canvas + input dispatch
- [x] Phase 3 — Menu/toolbar/scrollbar wiring
- [x] Phase 4 — JS bridge (`setupJSInterface`)
- [x] Phase 5 — Reset / step control + hooks
- [x] Phase 6 — Multi-tab integration

## Phases

### Phase 1 — Shell construction (`CirSim.java` + `CircuitJS1.java` entry) [DONE]

**Implements:** [SP_APC_01](./app-controller.sp.md#SP_APC_01)

- Extend `BaseCirSim`, implement `NativePreviewHandler`.
- Instantiate managers via super-ctor.

### Phase 2 — Canvas + input [DONE]

**Implements:** [SP_APC_04](./app-controller.sp.md#SP_APC_04)

- Mouse / touch / keyboard → `CircuitEditor` routing.
- `NativePreviewHandler.onPreviewNativeEvent` for global shortcuts.

### Phase 3 — Menu / toolbar / scrollbars [DONE]

**Implements:** [SP_APC_02_04](./app-controller.sp.md#SP_APC_02_04)

- Speed/current/power scrollbars → `getIterCount` + renderer intensity.
- Menu commands dispatched through `ActionManager`.

### Phase 4 — JS bridge (`setupJSInterface`, L1437) [DONE]

**Implements:** [SP_APC_02_01](./app-controller.sp.md#SP_APC_02_01)

- Populate `$wnd.CircuitJS1` with ~20 methods + hook slots.
- Fire `oncircuitjsloaded` at end of `onModuleLoad`.

### Phase 5 — Reset / step + hooks (L1195, L1225, L1510–1528) [DONE]

**Implements:** [SP_APC_02_02](./app-controller.sp.md#SP_APC_02_02), [SP_APC_02_03](./app-controller.sp.md#SP_APC_02_03), [SP_APC_02_05](./app-controller.sp.md#SP_APC_02_05)

### Phase 6 — Multi-tab integration [DONE]

- `DocumentManager.setActiveDocument` drives canvas focus + UI-state
  restore.
- `BaseCirSim.bindDocument` is the single swap point.

## Backlog

- Consolidate `resetSimulation` / `resetAction` (see PL_SIM backlog).
- Extract JS bridge into a dedicated `JsBridge` class.
- Expose `nonConvergenceRecoveryEnabled` through `$wnd.CircuitJS1`.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
