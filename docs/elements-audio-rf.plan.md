# Implementation Plan: Audio & RF Elements  {#PL_EAR}

> **Code:** PL_EAR
> **Status:** completed
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_EAR](./elements-audio-rf.concept.md)
> **Specification:** [SP_EAR](./elements-audio-rf.sp.md)
> **Depends on plans:** [PL_ELB](./element-base.plan.md), [PL_UTL](./util-locale-log.plan.md), [PL_GEO](./geometry.plan.md), [PL_RND](./rendering-primitives.plan.md), [PL_ESRC](./elements-sources.plan.md)
> **Used by plans:** circuit element factory, IO framework
>
> **Backing analysis:** [.dev_flow/onboard/analysis/domain-core__cat-audio-rf.md](../.dev_flow/onboard/analysis/domain-core__cat-audio-rf.md)

## Goal

Document the 6 shipped audio/RF elements (2 JS-Audio bridges, 2 analytic
RF sources, 1 scripted antenna, 1 BVD crystal) and capture backlog items
around resettability, dead flags, audio cache leaks and dump-type
registry.

## Technology Decisions

| Decision | Choice | Rationale |
|---|---|---|
| RF source modeling | closed-form analytic voltage | Simple, fast; linear (no Newton) |
| FM integration | forward-Euler phase accumulator | Adequate at typical timesteps |
| Audio bridge | JSNI + Web Audio API | Browser-native, no extra deps |
| Audio cache | static `audioFileMap` by fileNum | Survives cut/paste/undo |
| Crystal | CompositeElm delegating to C/L/R children | Reuse existing physics |
| Antenna | hardcoded 3-AM + 1-FM sum | Scene-dressing for crystal-radio demo |

## Progress

- [DONE] Phase 1 — AMElm, FMElm analytic sources
- [DONE] Phase 2 — AntennaElm scripted ether
- [DONE] Phase 3 — CrystalElm BVD composite
- [DONE] Phase 4 — AudioInputElm (file + Web Audio bridge)
- [DONE] Phase 5 — AudioOutputElm (decimator + WAV playback)
- [backlog] Phase 6 — Cross-cutting cleanups

## Phases

### Phase 1 — AM/FM sources [DONE]

**Implements:** [SP_EAR_01_02](./elements-audio-rf.sp.md#SP_EAR_01_02)

- Closed-form AM; Euler-FM phase accumulator.

### Phase 2 — AntennaElm [DONE]

- Hardcoded 3-AM + 1-FM demo source.

### Phase 3 — CrystalElm [DONE]

**Implements:** [SP_EAR_01_04](./elements-audio-rf.sp.md#SP_EAR_01_04)

- CompositeElm with C‖(C-L-R) sub-netlist.

### Phase 4 — AudioInputElm [DONE]

**Implements:** [SP_EAR_01_03](./elements-audio-rf.sp.md#SP_EAR_01_03)

- File upload, decodeAudioData, static cache.

### Phase 5 — AudioOutputElm [DONE]

**Implements:** [SP_EAR_02_02](./elements-audio-rf.sp.md#SP_EAR_02_02)

- Boxcar decimator, WAV playback via JSNI, download anchor.

## Backlog

Items deferred (from analysis Issues):

1. Fix AudioOutputElm.reset() to re-anchor `nextDataSample` (Issue #1).
2. Prune `AudioInputElm.audioFileMap` on delete to plug leak
   (Issue #2).
3. Fully reset FMElm phase: zero `funcx` and `lasttime` in `reset()`;
   also reset `AntennaElm.fmphase` (Issue #3).
4. Remove dead `FLAG_COS` on AMElm/FMElm or implement cosine-phase
   variant (Issue #4).
5. Add loop/end-of-file behavior option to AudioInputElm (Issue #5).
6. Make AntennaElm parameters user-editable or rename to
   `CrystalRadioAntennaDemoElm` (Issue #6).
7. Make AudioOutputElm oversample ratio configurable and warn at
   sub-µs `maxTimeStep` (Issue #7).
8. Revisit boxcar aliasing for non-integer sampleStep/timeStep ratio
   (Issue #8).
9. Expose `f_p` (parallel resonance) and Q in CrystalElm info pane
   (Issue #9).
10. Introduce central dump-type registry (Issue #10).
11. Explicit `getShortcut()==0` on AudioOutputElm for consistency
    (Issue #11).
12. Align AMElm lead-point lookup with FMElm (`geom().getLead1()`)
    (Issue #12).

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initial version |
