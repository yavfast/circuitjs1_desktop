# Implementation Plan: IO, Probes, Meters, Displays  {#PL_EIO}

> **Code:** PL_EIO
> **Status:** completed
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_EIO](./elements-io-probes-meters.concept.md)
> **Specification:** [SP_EIO](./elements-io-probes-meters.sp.md)
> **Depends on plans:** [PL_ELB](./element-base.plan.md), [PL_UTL](./util-locale-log.plan.md), [PL_GEO](./geometry.plan.md), [PL_RND](./rendering-primitives.plan.md)
> **Used by plans:** scope-manager, editor, file I/O glue
>
> **Backing analysis:** [.dev_flow/onboard/analysis/domain-core__cat-io-probes-meters.md](../.dev_flow/onboard/analysis/domain-core__cat-io-probes-meters.md)

## Goal

Document the 13 shipped IO/probe/meter/display elements and collect backlog
items covering deduplication (ProbeElm/TestPointElm shared stats), timing
correctness, leak prevention, and documentation gaps.

## Technology Decisions

| Decision | Choice | Rationale |
|---|---|---|
| Series current measurement | 0-V voltage source | Clean MNA current row; robust |
| Voltmeter isolation | optional high R (10 MΩ) or R=0 isolation | Non-invasive by default |
| Ohmmeter | current-source inject | Reuses CurrentElm stamping |
| ScopeElm | embed Scope manager | Avoids duplicating serialization logic |
| File bridges | JSNI (FileReader, Blob) | Browser-native IO |
| DataRecorder | ring buffer | Bounded memory; wrap for long runs |

## Progress

- [DONE] Phase 1 — Probes (`ProbeElm`, `TestPointElm`)
- [DONE] Phase 2 — Series meters (`AmmeterElm`, `WattmeterElm`, `OhmMeterElm`)
- [DONE] Phase 3 — Logic IO (`LogicInputElm`, `LogicOutputElm`, `OutputElm`)
- [DONE] Phase 4 — Data IO (`DataInputElm`, `DataRecorderElm`)
- [DONE] Phase 5 — Displays (`DecimalDisplayElm`, `ScopeElm`)
- [DONE] Phase 6 — `StopTriggerElm`
- [backlog] Phase 7 — Cross-cutting cleanups

## Phases

### Phase 1 — Probes [DONE]

**Implements:** [SP_EIO_02_01](./elements-io-probes-meters.sp.md#SP_EIO_02_01)

- Wave-stats modes, optional series R, plot X/Y markers.

### Phase 2 — Series meters [DONE]

**Implements:** [SP_EIO_02_02](./elements-io-probes-meters.sp.md#SP_EIO_02_02)

- 0-V VS trick for ammeter + wattmeter; ohmmeter via current-source.

### Phase 3 — Logic IO [DONE]

**Implements:** [SP_EIO_01_01](./elements-io-probes-meters.sp.md#SP_EIO_01_01)

- Driven voltage source (LogicInput) and passive level reader + pulldown.

### Phase 4 — Data IO [DONE]

**Implements:** [SP_EIO_02_03](./elements-io-probes-meters.sp.md#SP_EIO_02_03)

- JSNI FileReader for DataInput; Blob URL + filename for DataRecorder.

### Phase 5 — Displays [DONE]

- ChipElm-based DecimalDisplayElm; ScopeElm embeds Scope.

### Phase 6 — StopTrigger [DONE]

**Implements:** [SP_EIO_02_04](./elements-io-probes-meters.sp.md#SP_EIO_02_04)

- Threshold + delay; document-level `setSimRunning(false)`.

## Backlog

Items deferred (from analysis Issues):

1. Document and possibly fix WattmeterElm V-pair shorting behavior —
   surface in dialog (Issue #1).
2. Guard `ProbeElm.stepFinished` against multiple Newton iterations per
   timestep (Issue #2).
3. Replace wall-clock `System.currentTimeMillis()` with `simulator().t`
   in period/PW measurements (Issue #3).
4. Extract shared `WaveformStats` helper from ProbeElm/TestPointElm
   (Issue #4).
5. LRU or reference-count `DataInputElm.dataFileMap` to fix leak
   (Issue #5).
6. Reconcile `DataRecorderElm.getInfo` count vs buffer occupancy
   (Issue #6).
7. Add edge-sensitivity / hysteresis / notification to `StopTriggerElm`
   (Issue #7).
8. Reduce duplication in OutputElm scale serialization (Issue #8).
9. Rename `FLAG_PULLDOWN` / "Current Required" label for clarity
   (Issue #9).
10. Remove unused `FLAG_SHOWCURRENT` on AmmeterElm (Issue #10).
11. Reset `DataInputElm.fileNumCounter` on circuit load to prevent
    collisions (Issue #11).
12. Null-guard `ScopeElm.dump()` output (Issue #12).
13. Reconcile `DecimalDisplayElm` dialog cap (8) with setter widening
    to 16 (Issue #13).

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initial version |
