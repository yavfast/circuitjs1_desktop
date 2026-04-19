# Onboard Final Report — CircuitJS1 Desktop Mod

**Project:** CircuitJS1 Desktop Mod (GWT + NW.js)
**Onboard run:** 2026-04-18 → 2026-04-19
**Status:** completed — all 3 validation findings resolved (2026-04-19)

## Summary

| Metric | Count |
|---|---:|
| Source files analyzed | 275 Java files |
| Logical modules identified | 17 (collapsed from 11 packages; 5 SCCs resolved) |
| Analysis files produced | 37 |
| Concept triples generated | 45 (135 files in `docs/`) |
| Epic documents | 4 |
| Coding rules extracted | 45 (6 categories) |
| Knowledge-base skills seeded | 10 (5 domains) |
| Issues flagged (pre-existing) | 14 (9 analysis + 5 rules-extraction) |
| Validation findings (this report) | 3 (1 blocker + 2 high-priority) |

## Documents

### Concepts by layer
- **Layer 0** (leaf utilities): 6 — util-locale-log, geometry, rendering-primitives, math-dsp, expression-engine, platform-bridge
- **Layer 1** (UI primitives): 2 — ui-tabs, legacy-ui-wrappers
- **Layer 2** (domain core + IO): 22
  - 3 bases — element-base, waveforms, shared-models
  - 14 element categories — passives, sources, diodes-semis, transistors, opamps-analog, logic-combinational, logic-sequential, chips, switches, io-probes-meters, audio-rf, electromechanical, graphic-overlay, magnetics-transmission
  - 4 dialog concepts — edit-info-contract, import-export-ui, dialog-info, dialog-specialized
  - 1 io framework
- **Layer 3** (application shell): 15 — simulator-engine, app-controller, document-model, netlist-graph, canvas-editor, commands-undo, menus-actions, clipboard, user-preferences, scope-visualization, adjustable-sliders, session-logging, dialog-routing, browser-file-bridge, app-entrypoint

### Epics (4)
- E_DOMAIN_CORE — circuit elements + edit UI + serialization
- E_EDITOR — canvas editing + commands + menus + clipboard
- E_SIMULATOR — numerical engine + document model
- E_VISUALIZATION — scope, sliders, display settings

### Rules (45 across 6 files)
naming (10), structure (8), architecture (8), error-handling (6), style (8), testing (5) — severities reflect enforcement (many `should` rather than `must` due to observed drift).

### Skills (10 across 5 domains)
gwt (2), simulator (3), elements (2), io (2), editor (1). Each cites concrete `file:line` anchors and cross-references rules.

## Validation Results

### Infrastructure integrity — PASS
- `.dev_flow/rules/_index.yaml` and all 6 category files present.
- `.dev_flow/skills/_index.yaml` + 5 domain `_index.yaml` files + 10 skill markdowns present.
- No orphaned concept triples — every `*.concept.md` has matching `*.sp.md` and `*.plan.md`.

### ID conflicts — V-1 RESOLVED (2026-04-19)
- `elements-diodes-semis` triple renamed from `C_EDI/SP_EDI/PL_EDI` to `C_EDS/SP_EDS/PL_EDS`. `C_EDI` is now unambiguously `canvas-editor`. Verified via grep: no remaining collision.

### Cross-reference drift — V-2 RESOLVED (2026-04-19)
- `C_MDS` vs `C_SHM` URLs and labels corrected:
  - `simulator-engine.{concept,sp}.md` — `[C_MDS/SP_MDS]` now link to `math-dsp.*.md` (not shared-models).
  - `scope-visualization.concept.md` — `C_MDS (shared-models, …)` replaced with `[C_MDS](./math-dsp.concept.md) (CircuitMath + FFT)`.
  - `element-base.concept.md:118` — `C_MDS (shared-models)` replaced with `[C_SHM](./shared-models.concept.md)` (correct ID for `CustomLogicModel.escape/unescape`).

### Broken file URLs — V-3 RESOLVED (2026-04-19)
- Batch URL rewrite across all `docs/*.{concept,sp,plan,epic}.md` + `_index.md`:
  - `utilities.* → util-locale-log.*`, `util.{concept,sp,plan}.md → util-locale-log.*`
  - `rendering.{concept,sp,plan}.md → rendering-primitives.*`
  - `layout-window.* → legacy-ui-wrappers.*`
  - `user-prefs.* → user-preferences.*`
- Stale `"(pending)"` markers for `C_DOC`, `C_EIC`, `C_ADJ`, `C_SCP`, `C_SIM`, `SP_*` — all replaced with proper markdown links.
- Stale consolidated-dialog refs: `C_DIE/SP_DIE` → `C_EIC/SP_EIC` (edit-info-contract); `C_DIM/C_DIX/SP_DIM/SP_DIX` → `C_IEU/SP_IEU` (import-export-ui); `C_DIN/C_DSP` links normalized.
- `_index.md` "Notes" section updated to record the V-1 rename rather than the prior disambiguation workaround.

### Rules compliance sample — PASS with minor drift
- 5 sampled concepts: frontmatter, status, analysis cross-ref, and Changelog all present.
- Changelog canonical phrase "Initialized from existing codebase via onboard procedure" matches verbatim in 5/45 files; other 40 paraphrase. Non-blocking phrasing drift.

### Pre-existing docs linkage — minor gap
Only the `io-framework` triple cross-references `EXPORT_CJS.md`. `INTERNALS.md`, `project.md`, `elements.md`, `JS_API.md`, `remote_dbg*.md` are not referenced from generated concepts. Follow-up: add "See also" links where content overlap is explicit (simulator-engine ↔ INTERNALS.md; element-base ↔ elements.md; app-controller ↔ JS_API.md; import-export-ui ↔ EXPORT_*.md).

## Issues Requiring Manual Attention

Per-issue detail in `.dev_flow/onboard/issues.md`; headline items:
1. `util/PerfMonitor` → `element/BaseCircuitElm` layer inversion (pre-existing, low-priority cleanup).
2. `element` ↔ `dialog` SCC-A coupling (120 edges) — documented, no refactor planned.
3. `element` ↔ `element/waveform` cycle — documented.
4. `io` ↔ `io/text` / `io/json` registration cycle — acceptable.
5. Empty `client/options/`, `client/ui/` packages — leave as-is.
6. `Editable` marker interface bidirectional coupling — captured in concept.
7. Legacy `client/Diode.java` vs `element/DiodeElm.java` naming collision — documented.
8. Element category bucketing refinements (verified during Step 4).
9. No JUnit harness — Phase 5 (Test) limited to build+roundtrip+devmode.
10–14. Rules-extraction inconsistencies (RI-1..RI-6): Locale.LS drift, `==` on strings, DialogManager tracking, DialogManager.show() convention split, dormant `util/Log`.

## Next Suggested Steps

1. ~~V-1 fix~~ — done (2026-04-19).
2. ~~V-2 fix~~ — done (2026-04-19).
3. ~~V-3 fix~~ — done (2026-04-19).
4. **Sample accuracy check** — read 2–3 concepts end-to-end against source code to confirm they accurately describe current behavior before treating the concepts as authoritative.
5. **Git workflow** — per `SKILL.md`, concept+spec should be one PR per concept (or per epic); implementation plan is a separate PR. For this reverse-engineered setup, one initial bulk commit grouping the onboard output is acceptable.
6. **Archive onboard workspace (optional)** — `.dev_flow/onboard/analysis/` remains the authoritative raw extraction and should be kept; `state.yaml`, `queue.yaml`, `project_structure.md`, `dependency_graph.md`, `layers.md`, `issues.md`, and `report.md` (this file) can be archived or left in-tree.

## Observability Artifacts

- `.dev_flow/onboard/state.yaml` — onboard progress snapshot (status: completed)
- `.dev_flow/active_context.md` — working context for resuming work
- `.dev_flow/onboard/issues.md` — 14 flagged issues + cross-references
- `.dev_flow/onboard/analysis/*.md` — 37 authoritative raw analyses (fall back here when detail is needed)

## Provenance

Run via `/dev-flow onboard` command following the procedure in
`/home/yavfast/.claude/skills/dev-flow/phases/onboard.md`, dispatched across ~30
parallel subagents over multiple waves with user-approved scope at the analysis
gate (15-category element bucketing instead of per-file).
