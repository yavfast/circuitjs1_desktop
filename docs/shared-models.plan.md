# Implementation Plan: Shared Element Models  {#PL_SHM}

> **Code:** PL_SHM
> **Status:** completed
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_SHM](./shared-models.concept.md)
> **Specification:** [SP_SHM](./shared-models.sp.md)
> **Depends on plans:** [PL_ELB](./element-base.plan.md)
> **Used by plans:** — (will be filled by higher layers)
>
> Reverse-engineered plan for the 4 shared-model files at `client/` root.

## Goal

Provide a name-keyed catalog of parameter-only models that multiple element instances can share — enabling physics-parameter reuse (diodes, transistors), user-authored truth-tables (custom logic), and user-authored subcircuits (custom composites) to be edited once and propagated to all consumers.

## Technology Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Catalog | `static HashMap<String, T> modelMap` per model | Global scope fits single-document GWT app. |
| Rebind | `SimulationContextAware` + `simulator.updateModels()` | Decoupled: models don't hold element refs. |
| Dump tokens | Hard-coded (`"34 "`, `"32 "`, `"! "`, `". "`) | Legacy Falstad text-format compatibility. |
| Persistence (Composite) | Browser local-storage via `OptionsManager` | Independent of circuit file so user macros survive. |
| Diode separation | Three-way: Model / Solver / Elm | Solver reusable from `TransistorElm` (two PN junctions). |
| BJT separation | Two-way: Model / Elm (solver inlined in Elm) | Intentional — no reuse driver. |

## Progress

- [x] Phase 1 — DiodeModel + catalog + three-way split (Model/Diode/DiodeElm)
- [x] Phase 2 — TransistorModel (Gummel-Poon parameter set)
- [x] Phase 3 — CustomLogicModel + rule parser + escape/unescape helpers
- [x] Phase 4 — CustomCompositeModel + local-storage persistence + ExtListEntry

## Phases

### Phase 1 — DiodeModel (`client/DiodeModel.java`) [DONE]

**Implements:** [SP_SHM_01_01](./shared-models.sp.md#SP_SHM_01_01)

Delivered: IS/N/Rs/Vbr parameters, UI hints, derived cache (`vscale`, `vdcoef`, `fwdrop`), built-in catalog (11 entries + macro diodes), dump `"34 "`, legacy `getModelWithParameters(fwdrop, zvoltage)` bridge. Separate `Diode.java` solver + `DiodeElm.java` UI.

### Phase 2 — TransistorModel (`client/TransistorModel.java`) [DONE]

**Implements:** [SP_SHM_01_02](./shared-models.sp.md#SP_SHM_01_02)

Delivered: 12 Gummel-Poon parameters, `default`/`spice-default` + macro entries, dump `"32 "`, empty `updateModel()` (Gummel-Poon computed in `TransistorElm.doStep`).

### Phase 3 — CustomLogicModel (`client/CustomLogicModel.java`) [DONE]

**Implements:** [SP_SHM_01_03](./shared-models.sp.md#SP_SHM_01_03)

Delivered: inputs/outputs/rules/infoText, `parseRules` grammar (`0/1/?/+/-/a-z/A-Z` left, `0/1/a-z/_` right), `triState` detection, `FLAG_SCHMITT`, project-wide `escape`/`unescape` helpers (used by all 4 models and `CircuitElm`/`CompositeElm`).

### Phase 4 — CustomCompositeModel (`client/CustomCompositeModel.java`) [DONE]

**Implements:** [SP_SHM_01_04](./shared-models.sp.md#SP_SHM_01_04)

Delivered: sizeX/sizeY, `nodeList`, `extList` of `ExtListEntry`, `elmDump`, `modelCircuit`, `sequenceNumber`, local-storage seed via `OptionsManager` (`subcircuit:*` keys), built-in macros `~LM317-v2`/`~TL431`, dump `". "`, local-wins rule in `undumpModel`.

## Backlog

Items deferred from current cycle (from `.dev_flow/onboard/analysis/domain-core__shared-models.md` §Issues):

- **MOSFET/JFET don't reference TransistorModel (#7).** `project_structure.md:93` overstates coupling. Only `TransistorElm`/`NTransistorElm` consume `TransistorModel`.
- **`CustomLogicModel` copy-constructor shallow aliases (#4).** `rulesLeft`/`rulesRight` Vectors are aliased; safe only because `parseRules` reassigns on next edit. Document or deep-copy.
- **Model rename leaves old key in `modelMap` (#2).** Rename propagation incomplete — old name still resolves. Intentional back-compat but easy to mistake for bug.
- **Global mutable catalogs (#1).** Two open documents on same JVM (theoretically in GWT devmode) would share catalogs. Fine in practice but a smell.
- **No import validation for CustomLogicModel (#3).** `Window.alert` pops on every bad rule line when opening a file. Prefer silent logging.
- **Double `clearDumpedFlags` calls in `TextCircuitExporter` (#5).** Two export paths invoke it; verify no stale `dumped=true` state remains.
- **Dump-token collision risk (#6).** `"32 "`/`"34 "` etc. have no central registry; a future element type with numeric id 32 would clash silently. Consider a `DumpTokens` enum.
- **TransistorModel division-by-zero risk.** UI sends `1/VAF` etc. without guarding against zero-input.
- **`CustomCompositeModel.getModelWithName` does not auto-create.** Asymmetric with the other three — callers must null-check.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
