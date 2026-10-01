# Task: Backlog defect fixes, batch 3

- **Task ID:** 20261001_131500_backlog-fixes-3
- **Created:** 2026-10-01
- **Last updated:** 2026-10-01
- **Status:** done
- **Contributors:** main
- **Autonomy:** checkpoints
- **Plan approval:** 2026-10-01 — developer: "Ок. Продовжуй" after batch 2 commit (continuation of the defect-class delegation).

## Current Work Item

- **Document:** `.dev_flow/audit/whole_20260930_173830.plan.md` (backlog)
- **Phase:** fix
- **Traceable ID:** PL_AUDIT_20260930_173830

## Intent

- **Goal:** Close the follow-ups of batch 2.
- **Target:** BL-C07 (counter load guard), BL-C08 (dead `ExportAsLocalFileDialog`), BL-C09 (boolean dump fields, found while verifying BL-C07).
- **Expected result:** fixed and live-verified; harness green vs baseline; clean-context review; commit after sign-off.

## Subtasks

### Subtask: Backlog defect batch 3

- **Author:** main
- **Status:** done
- **Goal:** Fix BL-C07/C08/C09.
- **Progress:**
  - [x] BL-C07 Counter/Counter2: `justLoaded` in text ctor + `applyJsonState`, `skipExecuteAfterLoad(clk)` — also removes the active-low clear at load (count lost on every undo)
  - [x] BL-C08 `ExportAsLocalFileDialog` + commented menu block deleted; C_IEX concept/spec/plan updated
  - [x] BL-C09 Counter/Fuse/MotorProtectionSwitch/Monostable/Triac booleans written `true`/`false`, read with `parseBool`; EXPORT_OLD field order for 164/194 corrected
  - [x] Harness: new scenario `loadstate` (FAIL on a06264a build, PASS now); other 6 scenarios identical to baseline
  - [x] Clean-context review: approve-with-warnings, no must → fixed #1 Monostable + #4 `false` + `s` cases, #2 non-trivial-pass guards, #3 unused menu field, #7 `SwitchElm.momentary` true/false, #8 EXPORT_OLD 194; #5/#6 → BL-C10. Rebuild + harness: loadstate PASS (FAIL on a06264a), other 6 identical to baseline (mechanical confirm)
  - [x] Commit sign-off (developer: `/dev-flow commit`) — code + docs commits on fix/backlog-20260930
- **Activity:**
  - 2026-10-01 — {s:pin} BL-C09 root cause: `dumpValue(boolean)` → `1`/`0` (eb72ca5, 2025-07) vs `Boolean.parseBoolean` readers. Files saved by builds since then carry `1`; `parseBool` reads both. Skill io/text-format pitfalls 9–10.
  - 2026-10-01 — Started.

## Coordination Notes

## Blocking Issues

## Relevant Context

- Previous batch: [task_20261001_113805_backlog-fixes-2](task_20261001_113805_backlog-fixes-2.md)
- Verify: `npm run buildgwt && npm run test:live` (RULE_TEST_006)

## Shared Activity Log

- 2026-10-01 — [main] Committed on fix/backlog-20260930. Task done. Follow-up: BL-C10.
- 2026-10-01 — [main] Task opened.
