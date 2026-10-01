# Task: Backlog defect fixes, batch 2

- **Task ID:** 20261001_113805_backlog-fixes-2
- **Created:** 2026-10-01
- **Last updated:** 2026-10-01
- **Status:** done
- **Contributors:** main
- **Autonomy:** checkpoints
- **Plan approval:** 2026-10-01 — developer: "Продовжуй виконувати виправлення згідно списку рекомендацій" (continuation of the 2026-09-30 delegation: defect-class backlog items, selection by the agent). BL-C01 decided: **remove** Recover Auto-Save (developer, 2026-10-01).

## Current Work Item

- **Document:** `.dev_flow/audit/whole_20260930_173830.plan.md` (backlog)
- **Phase:** fix
- **Traceable ID:** PL_AUDIT_20260930_173830

## Intent

- **Goal:** Close the next defect-class backlog items of the audit plan.
- **Target:** BL-C01 (remove Recover Auto-Save), BL-C02 (dump-null index off-by-one), BL-C03 (flip-flop `lastClock` / TFF init), BL-C04 (SeqGen JSON validation), BL-A14 `String ==` comparisons, BL-A01 dead code if cheap.
- **Expected result:** each item fixed or proven not-a-bug; GWT compile + `npm run test:live` green vs baseline; clean-context review; commit only after sign-off.

## Subtasks

### Subtask: Backlog defect batch 2

- **Author:** main
- **Status:** done
- **Goal:** Investigate and fix the selected backlog defects.
- **Progress:**
  - [x] BL-C01 remove Recover Auto-Save (menu, UndoManager slot, editor/exit/close writes); stale key removed at startup
  - [x] BL-C02 `hasDumpLine()` + `locateElmForDump()` for `38`/scope refs; copy path uses `dumpElm` (no literal `null`, keeps description)
  - [x] BL-C03 `ChipElm.skipExecuteAfterLoad` (D/JK/T, RingCounter) + TFF/JK placement Q̄
  - [x] BL-C04 SeqGen tolerant getters + clamp
  - [x] BL-A14 `String ==` — dropped from batch: not a defect under GWT (JS `===`), stays standards item
  - [x] BL-A01 dead code deleted
  - [x] Build OK · harness 6 scenarios identical to HEAD baseline · probes: baseline shows C02/C03/C04 defects, new build correct
  - [x] Docs propagated (C_UND/SP_UND, C_EDI/SP_EDI, user-preferences, C_PLT/SP_PLT, _index, session-logging, RULE_ERR_005 text, undo skill, audit plan)
  - [x] Clean-context review: approve-with-warnings, no must → fixed #1 ref to a dump-less element (-1, extra scope plots skipped), #2 hint indices, #3 doc note, #6 null guard; #4 → BL-C07, #5 → BL-C08; rebuild + harness identical to baseline + probes PASS (mechanical confirm, no re-review)
  - [x] Commit sign-off (developer: "Ок. Продовжуй") — code + docs commits on fix/backlog-20260930
- **Activity:**
  - 2026-10-01 — Lesson captured in skill editor/undo-snapshot-model (undo rebuilds via text ctors; dump-line indices). No new rule: covered by the skill.
  - 2026-10-01 — {s:pin} C01 menu check live = unobserved (GWT builds the File popup only when opened); verified statically + compile.
  - 2026-10-01 — Started.

## Coordination Notes

## Blocking Issues

## Relevant Context

- Plan: `.dev_flow/audit/whole_20260930_173830.plan.md` · previous batch [task_20260930_205344_backlog-fixes](task_20260930_205344_backlog-fixes.md)
- Verify: `npm run buildgwt && npm run test:live` (RULE_TEST_006)

## Shared Activity Log

- 2026-10-01 — [main] Committed on fix/backlog-20260930. Task done. Follow-ups: BL-C07, BL-C08.
- 2026-10-01 — [main] Task opened.
