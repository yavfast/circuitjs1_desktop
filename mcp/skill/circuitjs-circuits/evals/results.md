# Eval results

Recorded per skill version by `evals/run.mjs` (SP_AGS_05_02). Newest first.

## Skill 1.0 (text 3488c3db) — 2026-10-03

toolsVersion 1.0, app 1.3.2; models haiku, sonnet; 3 rep(s) each.

| Scenario | Model | Passed | Rule (≥ 2 of 3) |
|---|---|---|---|
| rc-lowpass-1k | haiku | 3/3 | pass |
| rc-lowpass-1k | sonnet | 3/3 | pass |
| led-driver-10ma | haiku | 3/3 | pass |
| led-driver-10ma | sonnet | 3/3 | pass |
| fix-broken-amp | haiku | 3/3 | pass |
| fix-broken-amp | sonnet | 3/3 | pass |
| tune-divider | haiku | 3/3 | pass |
| tune-divider | sonnet | 3/3 | pass |

Total cost: $2.63 over 24 run(s).

**Releasable:** yes (every scenario ≥ 2 of 3 on each of ≥ 2 models).

| Scenario | Model | Run | Result | Skill used | Turns | Tokens | Cost (USD) | Wall (s) | Failed checks / error |
|---|---|---|---|---|---|---|---|---|---|
| rc-lowpass-1k | haiku | 1 | PASS | yes | 16 | 440752 | 0.147 | 113 | — |
| rc-lowpass-1k | haiku | 2 | PASS | yes | 15 | 340610 | 0.122 | 90 | — |
| rc-lowpass-1k | haiku | 3 | PASS | yes | 11 | 265989 | 0.095 | 57 | — |
| rc-lowpass-1k | sonnet | 1 | PASS | yes | 10 | 171470 | 0.123 | 26 | — |
| rc-lowpass-1k | sonnet | 2 | PASS | yes | 10 | 169575 | 0.112 | 29 | — |
| rc-lowpass-1k | sonnet | 3 | PASS | yes | 9 | 165568 | 0.109 | 25 | — |
| led-driver-10ma | haiku | 1 | PASS | yes | 16 | 409615 | 0.111 | 71 | — |
| led-driver-10ma | haiku | 2 | PASS | yes | 16 | 426712 | 0.121 | 77 | — |
| led-driver-10ma | haiku | 3 | PASS | yes | 26 | 603809 | 0.188 | 130 | — |
| led-driver-10ma | sonnet | 1 | PASS | yes | 11 | 199666 | 0.123 | 26 | — |
| led-driver-10ma | sonnet | 2 | PASS | yes | 10 | 164372 | 0.105 | 24 | — |
| led-driver-10ma | sonnet | 3 | PASS | yes | 11 | 193828 | 0.121 | 28 | — |
| fix-broken-amp | haiku | 1 | PASS | yes | 10 | 208570 | 0.077 | 43 | — |
| fix-broken-amp | haiku | 2 | PASS | yes | 16 | 397086 | 0.130 | 87 | — |
| fix-broken-amp | haiku | 3 | PASS | yes | 13 | 344628 | 0.131 | 109 | — |
| fix-broken-amp | sonnet | 1 | PASS | yes | 10 | 210409 | 0.129 | 34 | — |
| fix-broken-amp | sonnet | 2 | PASS | yes | 9 | 155291 | 0.119 | 28 | — |
| fix-broken-amp | sonnet | 3 | PASS | yes | 10 | 213737 | 0.131 | 34 | — |
| tune-divider | haiku | 1 | PASS | yes | 7 | 140510 | 0.052 | 25 | — |
| tune-divider | haiku | 2 | PASS | yes | 10 | 250554 | 0.101 | 74 | — |
| tune-divider | haiku | 3 | PASS | yes | 7 | 140293 | 0.052 | 25 | — |
| tune-divider | sonnet | 1 | PASS | yes | 8 | 114606 | 0.081 | 17 | — |
| tune-divider | sonnet | 2 | PASS | yes | 7 | 89951 | 0.074 | 14 | — |
| tune-divider | sonnet | 3 | PASS | yes | 7 | 91001 | 0.074 | 15 | — |

## Skill 1.0 (text 3dab4c17) — 2026-10-03

toolsVersion 1.0, app 1.3.2; models haiku, sonnet; 3 rep(s) each.

| Scenario | Model | Passed | Rule (≥ 2 of 3) |
|---|---|---|---|
| rc-lowpass-1k | haiku | 3/3 | pass |
| rc-lowpass-1k | sonnet | 3/3 | pass |
| led-driver-10ma | haiku | 3/3 | pass |
| led-driver-10ma | sonnet | 3/3 | pass |
| fix-broken-amp | haiku | 3/3 | pass |
| fix-broken-amp | sonnet | 3/3 | pass |
| tune-divider | haiku | 1/3 | FAIL |
| tune-divider | sonnet | 3/3 | pass |

Total cost: $2.83 over 24 run(s).

**Releasable:** no (every scenario ≥ 2 of 3 on each of ≥ 2 models).

| Scenario | Model | Run | Result | Skill used | Turns | Tokens | Cost (USD) | Wall (s) | Failed checks / error |
|---|---|---|---|---|---|---|---|---|---|
| rc-lowpass-1k | haiku | 1 | PASS | yes | 12 | 310600 | 0.120 | 82 | — |
| rc-lowpass-1k | haiku | 2 | PASS | yes | 15 | 412651 | 0.137 | 102 | — |
| rc-lowpass-1k | haiku | 3 | PASS | yes | 15 | 255888 | 0.087 | 60 | — |
| rc-lowpass-1k | sonnet | 1 | PASS | yes | 12 | 191445 | 0.118 | 29 | — |
| rc-lowpass-1k | sonnet | 2 | PASS | yes | 13 | 195814 | 0.133 | 30 | — |
| rc-lowpass-1k | sonnet | 3 | PASS | yes | 12 | 192120 | 0.120 | 28 | — |
| led-driver-10ma | haiku | 1 | PASS | yes | 15 | 254298 | 0.086 | 57 | — |
| led-driver-10ma | haiku | 2 | PASS | yes | 20 | 631534 | 0.199 | 117 | — |
| led-driver-10ma | haiku | 3 | PASS | no | 16 | 341453 | 0.111 | 71 | — |
| led-driver-10ma | sonnet | 1 | PASS | yes | 12 | 217486 | 0.127 | 29 | — |
| led-driver-10ma | sonnet | 2 | PASS | yes | 13 | 240068 | 0.132 | 33 | — |
| led-driver-10ma | sonnet | 3 | PASS | yes | 14 | 258635 | 0.152 | 34 | — |
| fix-broken-amp | haiku | 1 | PASS | no | 24 | 1104546 | 0.315 | 204 | — |
| fix-broken-amp | haiku | 2 | PASS | no | 11 | 270552 | 0.109 | 94 | — |
| fix-broken-amp | haiku | 3 | PASS | no | 10 | 219886 | 0.094 | 80 | — |
| fix-broken-amp | sonnet | 1 | PASS | yes | 9 | 163538 | 0.120 | 24 | — |
| fix-broken-amp | sonnet | 2 | PASS | yes | 9 | 156454 | 0.111 | 25 | — |
| fix-broken-amp | sonnet | 3 | PASS | yes | 10 | 160282 | 0.123 | 31 | — |
| tune-divider | haiku | 1 | FAIL | no | 4 | 89378 | 0.052 | 33 | checkpoint_exists: 0 agent checkpoint(s) (min 1) |
| tune-divider | haiku | 2 | FAIL | no | 6 | 149457 | 0.086 | 73 | checkpoint_exists: 0 agent checkpoint(s) (min 1) |
| tune-divider | haiku | 3 | PASS | yes | 7 | 145926 | 0.063 | 36 | — |
| tune-divider | sonnet | 1 | PASS | yes | 8 | 90186 | 0.076 | 15 | — |
| tune-divider | sonnet | 2 | PASS | yes | 8 | 113823 | 0.080 | 16 | — |
| tune-divider | sonnet | 3 | PASS | yes | 8 | 90373 | 0.077 | 16 | — |

## Skill 1.0 (text 7f3c03a4, as committed in 1f79a13) — 2026-10-03

toolsVersion 1.0, app 1.3.2; models haiku, sonnet; 3 rep(s) each.

| Scenario | Model | Passed | Rule (≥ 2 of 3) |
|---|---|---|---|
| rc-lowpass-1k | haiku | 3/3 | pass |
| rc-lowpass-1k | sonnet | 3/3 | pass |
| led-driver-10ma | haiku | 3/3 | pass |
| led-driver-10ma | sonnet | 3/3 | pass |
| fix-broken-amp | haiku | 3/3 | pass |
| fix-broken-amp | sonnet | 3/3 | pass |
| tune-divider | haiku | 3/3 | pass |
| tune-divider | sonnet | 0/3 | FAIL |

Total cost: $2.84 over 24 run(s).

**Releasable:** no (every scenario ≥ 2 of 3 on each of ≥ 2 models).

| Scenario | Model | Run | Result | Skill used | Turns | Tokens | Cost (USD) | Wall (s) | Failed checks / error |
|---|---|---|---|---|---|---|---|---|---|
| rc-lowpass-1k | haiku | 1 | PASS | yes | 11 | 252128 | 0.084 | 56 | — |
| rc-lowpass-1k | haiku | 2 | PASS | no | 15 | 340025 | 0.085 | 54 | — |
| rc-lowpass-1k | haiku | 3 | PASS | yes | 11 | 264834 | 0.102 | 78 | — |
| rc-lowpass-1k | sonnet | 1 | PASS | yes | 12 | 188163 | 0.113 | 30 | — |
| rc-lowpass-1k | sonnet | 2 | PASS | yes | 10 | 134749 | 0.092 | 25 | — |
| rc-lowpass-1k | sonnet | 3 | PASS | yes | 12 | 165312 | 0.112 | 28 | — |
| led-driver-10ma | haiku | 1 | PASS | yes | 23 | 715054 | 0.194 | 130 | — |
| led-driver-10ma | haiku | 2 | PASS | yes | 18 | 546126 | 0.151 | 83 | — |
| led-driver-10ma | haiku | 3 | PASS | yes | 16 | 458557 | 0.151 | 106 | — |
| led-driver-10ma | sonnet | 1 | PASS | yes | 13 | 220115 | 0.127 | 28 | — |
| led-driver-10ma | sonnet | 2 | PASS | yes | 14 | 226984 | 0.136 | 31 | — |
| led-driver-10ma | sonnet | 3 | PASS | yes | 13 | 238005 | 0.128 | 31 | — |
| fix-broken-amp | haiku | 1 | PASS | no | 12 | 339276 | 0.149 | 100 | — |
| fix-broken-amp | haiku | 2 | PASS | no | 35 | 1798840 | 0.384 | 161 | — |
| fix-broken-amp | haiku | 3 | PASS | no | 18 | 664797 | 0.219 | 165 | — |
| fix-broken-amp | sonnet | 1 | PASS | no | 8 | 147596 | 0.116 | 30 | — |
| fix-broken-amp | sonnet | 2 | PASS | no | 8 | 162623 | 0.103 | 25 | — |
| fix-broken-amp | sonnet | 3 | PASS | no | 5 | 86625 | 0.073 | 19 | — |
| tune-divider | haiku | 1 | PASS | no | 5 | 105246 | 0.042 | 24 | — |
| tune-divider | haiku | 2 | PASS | no | 5 | 110729 | 0.049 | 28 | — |
| tune-divider | haiku | 3 | PASS | no | 6 | 128854 | 0.047 | 41 | — |
| tune-divider | sonnet | 1 | FAIL | no | 6 | 98925 | 0.066 | 16 | checkpoint_exists: 0 agent checkpoint(s) (min 1) |
| tune-divider | sonnet | 2 | FAIL | no | 5 | 77486 | 0.055 | 13 | checkpoint_exists: 0 agent checkpoint(s) (min 1) |
| tune-divider | sonnet | 3 | FAIL | no | 6 | 97873 | 0.062 | 17 | checkpoint_exists: 0 agent checkpoint(s) (min 1) |

## Integration scenarios (SP_AGS_05_05)

| Scenario | Date | Result | Evidence |
|---|---|---|---|
| Claude Code over HTTP | 2026-10-03 | observed | Claude Code 2.1.288 `claude -p` with an HTTP `circuitjs` server. Precondition variant: the server came from a temporary `--strict-mcp-config --mcp-config` file holding the scratch instance's URL, not from `claude mcp add --transport http`; the transport and URL are the same and the skill installed as a project skill; all 24 runs of skill text 3488c3db invoked the skill, worked in their own or the named document, and passed the checks of every scenario, `rc-lowpass-1k` included |
| Claude Desktop over the bridge | — | not yet observed (manual, owed by the developer) | Steps: `hosts/claude-desktop.json` with `CIRCUITJS_APP`, app not running, ask for the LED driver, then `node evals/check.mjs` |
| Eval runner | 2026-10-03 | observed | `node evals/check.mjs` after each agent session: per-scenario pass/fail JSON, exit 0 only when all pass |
