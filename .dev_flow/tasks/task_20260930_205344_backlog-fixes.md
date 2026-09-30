# Task: Backlog defect fixes (priority order, agent's choice)

- **Task ID:** 20260930_205344_backlog-fixes
- **Created:** 2026-09-30
- **Last updated:** 2026-09-30
- **Status:** done
- **Contributors:** main
- **Autonomy:** checkpoints
- **Plan approval:** 2026-09-30 — developer: "Виконуй фікси згідно пріорітету на свій вибір" → Plan→Code gate passed for defect-class backlog items of the audit plan, selection delegated to the agent. Open decisions (DEC_01, DEC_03, "Recover Auto-Save" fate in BL-A05) stay with the developer.

## Current Work Item

- **Document:** `.dev_flow/audit/whole_20260930_173830.plan.md` (backlog)
- **Phase:** fix
- **Traceable ID:** PL_AUDIT_20260930_173830

## Intent

- **Goal:** Close the remaining real defects parked in the audit backlog, highest user impact first.
- **Target:** BL-A06 (slider / Play attachment), BL-B05, BL-B06, BL-A16 meter period bug, then BL-A05 caps and BL-A03 dirty flag if time allows.
- **Expected result:** each selected defect fixed or proven not-a-bug; GWT compile + `npm run test:live` green; clean-context review; commit only after sign-off.

## Subtasks

### Subtask: Backlog defect batch

- **Author:** main
- **Status:** done
- **Goal:** Investigate and fix the selected backlog defects.
- **Progress:**
  - [x] BL-A06 confirmed (static: `CirSim.iFrame` never assigned → Pot/LDR/NTC sliders + AudioOutput Play never shown) → migrated to AdjustableManager (`HasBuiltInSlider`, `HasControlWidget`); live probe PASS
  - [x] BL-B05 TFlipFlop `justLoaded`, VCCS import alerts → log, CustomCompositeChip pinless guard (agent)
  - [x] BL-B06 chip bit minimums shared by JSON + dialog (6 chips; SeqGen not a defect) (agent); LDR/NTC drift gone with BL-A06
  - [x] BL-A16 meter period — dropped: Frequency/Period/Pulse/Duty modes are commented out of the UI choice (unreachable)
  - [x] BL-A05 undo cap 150, closed tabs 20 (logs already capped) · BL-A03 single `CircuitInfo.modified` + unload prompt checks all tabs (agent)
  - [x] GWT compile (BUILD SUCCESS) · live harness A/B PASS
  - [x] Baseline compare vs HEAD (worktree build): no regressions; new diffs = expected `38` lines; LDR text-leg loss and CustomCompositeChip crash fixed
  - [x] Clean-context review: approve-with-warnings → fixed #1 paste sliders, #2 LDR range, #3 label refresh only on change, #4 dimensionless, #8 dead VarRail/VarWaveform + docs, #9 NTC helper; #5/#6/#7 → BL-C02/C05/C06
  - [x] Rebuild + full harness: A/B/D PASS, T/C/S equal to baseline; rules RULE_STRUCT_009/010 (should); docs propagated
  - [x] Commit sign-off (developer: "Ok") — branch fix/backlog-20260930: 21079bc (code + harness), docs/rules/records commit
- **Activity:**
  - 2026-09-30 — Review fixes verified mechanically (build + harness incl. new `sliders` scenario); no re-review: all non-must, contained.
  - 2026-09-30 — Recover Auto-Save: reading disabled (readRecovery only in commented code), writes still go to one global localStorage key → developer decision (remove or per-document key); not changed.
  - 2026-09-30 — {s:pin} Pot/LDR/NTC now save an adjustable `38 …` line (like VarRail already did); position no longer re-derived from a detached Scrollbar (fixes quantization drift); LDR/NTC `delete()` now reach `super.delete()`.
  - 2026-09-30 — Started.

## Coordination Notes

## Blocking Issues

## Relevant Context

- Plan: `.dev_flow/audit/whole_20260930_173830.plan.md` · report `code-audit_20260930_173830.report.md`
- Verify: `npm run buildgwt && npm run test:live` (RULE_TEST_006)

## Shared Activity Log

- 2026-09-30 — [main] Committed on fix/backlog-20260930 (21079bc + docs commit). Task done. Open: BL-C01 Recover Auto-Save decision.
- 2026-09-30 — [main] Task opened.
