# Implementation Plan: Circuit Authoring Skill  {#PL_AGS}

> **Code:** PL_AGS
> **Status:** draft
> **Created:** 2026-10-01
> **Updated:** 2026-10-01
>
> **Concept:** [C_AGS](./agent-skill.concept.md)
> **Specification:** [SP_AGS](./agent-skill.sp.md)
> **Depends on:** [PL_MCP](./mcp-server.plan.md) (Phase 2 tools), [PL_MCB](./mcp-bridge.plan.md) (Phase 3 CLI)
> **Used by:** —
>
> Writes the `circuitjs-circuits` skill (entry, references, host snippets), builds the consistency checks and the eval runner on the bridge CLI, and runs the eval set on two model sizes.

## Goal

For this plan's scope ([task_E_AGT](../.dev_flow/tasks/task_E_AGT.md)): agents that load the skill build, tune and debug circuits through the MCP tools without guessing geometry or tool usage. When this plan is complete, the SP_AGS_05 eval set and consistency checks pass, and the results are recorded per skill version.

## Technology Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Location | `mcp/skill/circuitjs-circuits/` (layout of SP_AGS_01_01) | Ships beside the bridge |
| Content format | Markdown per the agent-skill format rules | SP_AGS_01_02 |
| Example circuits | Written as AgentCircuit JSON blocks, produced from live `circuit_get` output of circuits built in the app | Correct by construction; checked by the consistency script |
| Scripts | Node ≥ 20 ES modules calling `circuitjs-mcp` (CLI mode) | One dependency path; the bridge CLI exists for exactly this |
| Eval execution | Agent runs through Claude Code in non-interactive mode (`claude -p` with the skill installed and the server added), one run per scenario × model × repetition, followed by `evals/check.mjs` | Repeatable runs; the checker never trusts the agent's own claims |

## Required Knowledge

| Kind | Ref | Applies to | Note |
|------|-----|-----------|------|
| skill (apply) | automation/agent-mcp-surface | P1 | tool-design conventions, skill format rules |
| skill (apply) | automation/js-api-surface | P1 | pitfalls the diagnostics reference must explain |
| skill (apply) | simulator/time-step-control | P1 | simulation reference rules of thumb |

## Progress

- [x] [Phase 1 — Skill entry, references, host snippets](#PL_AGS_P1)
- [x] [Phase 2 — Consistency checks](#PL_AGS_P2)
- [x] [Phase 3 — Eval set and runner](#PL_AGS_P3)
- [ ] [Phase 4 — Eval runs and results](#PL_AGS_P4)

## Phases

### Phase 1 — Skill entry, references, host snippets [DONE]  {#PL_AGS_P1}

**Depends on:** PL_MCP Phase 2
**Implements:** [SP_AGS_01_01](./agent-skill.sp.md#SP_AGS_01_01), [SP_AGS_01_02](./agent-skill.sp.md#SP_AGS_01_02), [SP_AGS_02](./agent-skill.sp.md#SP_AGS_02), [SP_AGS_03_01](./agent-skill.sp.md#SP_AGS_03_01), [SP_AGS_04_01](./agent-skill.sp.md#SP_AGS_04_01), [SP_AGS_06_01](./agent-skill.sp.md#SP_AGS_06_01) (uninstall noted in `hosts/claude-code.md`)
**Verify:** [SP_AGS_03_01](./agent-skill.sp.md#SP_AGS_03_01) size and form limits (line counts, contents tables, one-level links); [SP_AGS_05_04](./agent-skill.sp.md#SP_AGS_05_04) both rows as text checks (the entry tells the agent to compare `toolsVersion` and to stop with connection guidance when no tools are reachable) — plus: every section required by SP_AGS_02_01–02_07 present

What to create:
- `SKILL.md`
- `reference/geometry.md`, `reference/elements.md`, `reference/diagnostics.md`, `reference/patterns.md`, `reference/simulation.md`
- `hosts/claude-code.md`, `hosts/claude-desktop.json`

**Result (2026-10-02).**
- **Files.** `mcp/skill/circuitjs-circuits/`: `SKILL.md` (105 lines), `reference/geometry.md` (152), `elements.md` (61), `diagnostics.md` (143), `patterns.md` (237), `simulation.md` (110), `hosts/claude-code.md` (install, connect, uninstall), `hosts/claude-desktop.json` (the SP_AGS_02_07 entry verbatim).
- **Examples from the app.** Ten AgentCircuit blocks (the geometry RC example and nine patterns: divider, RC, RLC, rectifier, common-emitter, inverting, non-inverting, 555 astable, AND gate + LED). Each was built through the bridge CLI against `target/site` under Xvfb with a scratch HOME. The blocks are `circuit_get` output trimmed to the keys the example sets. Every block re-imports with `ok` and 0 errors, and its stated run reproduces the quoted values (e.g. RC `out` peakToPeak 1.693 V, 555 67.9 Hz / 0.527, CE gain 20.1, LED 9.75 mA).
- **Derived-pin examples** (NPN, op-amp, 555) are real `circuit_edit` replies.
- **Polarity.** The skill states no pin-name polarity. It tells agents to verify on a known case and to orient sources `start` = ground side, as in every pattern.
- **Verify.** A scratch check (766 checks, all pass) covers:
  - line limits, contents tables and one-level links;
  - the frontmatter, the SP_AGS_02_01 section order and contents, the 05_04 text rows, and every section of 02_02–02_07;
  - issue-code coverage: 55 codes parsed from SP_AGA §03_04–03_06 plus `result_too_large`, 55 rows;
  - tool names against `circuitjs-mcp tools`;
  - every element row's type, aliases, pins, geometry, size and property keys against `circuitjs://catalogue`;
  - whole-cell coordinates, and the live import of every block.
- **Deviations.**
  - The elements table has 26 rows, the SP_AGS_02_03 required list (spec wording aligned 2026-10-02).
  - The compatibility line and the no-tools guidance are two short paragraphs inside "Purpose and scope", which keeps the six-section order.
  - Checklist step 1 (clarify) names no tool.
  - Example blocks drop the non-default `flags` the app adds on import. The blocks import identically without them.

### Phase 2 — Consistency checks [DONE]  {#PL_AGS_P2}

**Depends on:** Phase 1; PL_MCB Phase 3
**Implements:** [SP_AGS_03_02](./agent-skill.sp.md#SP_AGS_03_02), [SP_AGS_05_03](./agent-skill.sp.md#SP_AGS_05_03)
**Verify:** `node mcp/skill/tools/check-consistency.mjs` passes against a running instance; a deliberately misspelled type name in a scratch copy makes it fail

What to create:
| Entity | Module | Purpose |
|--------|--------|---------|
| Consistency script | `mcp/skill/tools/check-consistency.mjs` | Catalogue names (from `circuitjs://catalogue`), example imports, issue-code coverage against the code lists parsed from `docs/agent-api.sp.md` §03_05/§03_06 and the server codes of `docs/mcp-server.sp.md` §03_03, tool coverage (from `circuitjs-mcp tools`) |

**Result (2026-10-02).**
- **Script.** `mcp/skill/tools/check-consistency.mjs` (Node ≥ 20, no dependencies).
  - **Instance access.** The bridge CLI does the spec-named calls (`tools`, `read circuitjs://catalogue/<type>`, `call circuit_import`). The served input/output schemas, the server instructions and the full catalogue are read by direct JSON-RPC (`initialize`, `tools/list`, `resources/read`, with timeout and content-type check) at the URL of the instance the bridge selects.
  - **Options.** `--url`, `--instance`, `--registry` and `--timeout` are passed through; `--skill <dir>` checks another skill copy; `--offline` runs the form and codes groups only. The header comment documents the options and groups.
  - **Exit codes.** 0 pass; 1 a check failed; 2 usage error or no reachable instance (also mid-run).
- **Groups.**
  - `form`: SP_AGS_03_01 limits; frontmatter; whole-cell examples; half-cell lattice of every element spec; unparseable json examples.
  - `codes`: codes parsed from SP_AGA §03_04–03_06 and SP_MCP §03_03, with a minimum count per section, diffed both ways against the `diagnostics.md` rows.
  - `names`: every backticked snake_case word in any skill file must be one of: an issue code, a property key of some type, a tool, a schema argument or enumeration value, or a catalogue geometry. A one-word allow-list holds `connected_to`.
  - `tools`:
    - tool names against `circuitjs-mcp tools` plus the bridge tools;
    - argument keys of every JSON tool call, pattern run and edit, against the served schemas;
    - keys of shorthand calls (`circuit_run {"span", …}`) against that tool's arguments;
    - the instance toolsVersion against the SKILL.md line (same MAJOR, MINOR ≥);
    - non-zero counts of calls, runs, edits and shorthand.
  - `catalogue`:
    - the elements table: canonical name, aliases, pins, geometry, keys;
    - every element spec's type and property keys;
    - `set` edits resolved through the AgentCircuit of their section, otherwise against the union of all property keys;
    - capitalised prose words, which must be types, aliases, pins, ID prefixes or example IDs.
  - `examples`: each block parsed as `{elements: [...]}` imports into a scratch document with `ok` and 0 errors. Failures are recorded per block. A request the bridge rejects (exit 2, e.g. a -32602 for an element without `type`) fails that block. A lost instance closes the scratch document first and then exits 2. The close itself is checked, so a document left open is reported.
- **Verify.**
  - **Live instance.** All groups PASS: 1449 checks in 17 s.
  - **Defects.** Eleven scratch copies, each with one defect, each fail with exit 1 and name the defect:
    - a misspelled type in a pattern block, and one in the table;
    - a misspelled tool;
    - a deleted code row;
    - a misspelled run argument, and one in a shorthand call;
    - a half-cell coordinate with a removed wire;
    - an over-long SKILL.md, and a link between references;
    - a misspelled code in `simulation.md`;
    - a misspelled key in a `set` edit;
    - a skill line asking for toolsVersion 1.1.
  - **Example without `type`.** A scratch copy with one element missing `type` fails with exit 1, naming the block; `circuit_documents list` afterwards shows no document left open.
  - **Exit 2.** No instance, and an unreachable `--url`, both give exit 2. `--offline` gives exit 0, with SKIP for the live groups.
- **Skill fix found by the script.** Checklist step 8 showed `circuit_edit {"op": "set"}`, which is not a valid argument; it now shows the `edits` form.

### Phase 3 — Eval set and runner [DONE]  {#PL_AGS_P3}

**Depends on:** Phase 2
**Implements:** [SP_AGS_01_03](./agent-skill.sp.md#SP_AGS_01_03), [SP_AGS_05_01](./agent-skill.sp.md#SP_AGS_05_01), [SP_AGS_05_02](./agent-skill.sp.md#SP_AGS_05_02)
**Verify:** `evals/check.mjs` scores a hand-built correct circuit of each scenario as PASS and a hand-broken one as FAIL; [SP_MCB_05_03](./mcp-bridge.sp.md#SP_MCB_05_03) eval harness row (checker parses CLI output, exit code 0)

What to create:
| Entity | Module | Purpose |
|--------|--------|---------|
| Scenarios | `evals/evals.json` | The four SP_AGS_05_01 scenarios with checks |
| Fixtures | `evals/fixtures/fix-broken-amp.txt`, `evals/fixtures/tune-divider.txt` | Starting circuits |
| Checker | `evals/check.mjs` | Loads fixtures into new documents, appends the document handle to prompts, evaluates checks with `reset: true` |
| Run driver | `evals/run.mjs` | Runs `claude -p` per scenario × model × repetition, then the checker; writes `evals/results.md` |

**Result (2026-10-02).**
- **Location.** `mcp/skill/circuitjs-circuits/evals/`, the SP_AGS_01_01 layout. `check-consistency.mjs` skips `evals/`, and `run.mjs` installs the skill without it.
- **Files.**
  - `evals.json`: version `1.0`, the four SP_AGS_05_01 scenarios with the prompts written out.
  - `fixtures/fix-broken-amp.txt`: the common-emitter pattern with a 0.1 V p-p input, the upper base resistor ending one cell short of the base, and no ground.
  - `fixtures/tune-divider.txt`: 9 V over 12 kΩ / 6 kΩ = 3.0 V at `out`. Both fixtures are text exports from the app.
- **`check.mjs`.** It reads only app state, through the bridge CLI.
  - `prepare` loads each fixture into a new document and seals it with the checkpoint "eval fixture <id>", which `checkpoint_exists` does not count. It appends "The circuit is open in document <doc>." to the prompt, records the newest handle for scenarios without a fixture, and writes a state file.
  - `check` evaluates the checks: every measure runs with `reset: true` and a 60 s budget, and `no_solver_stop` is evaluated after the measures. A scenario without a fixture is checked in the newest document created after `prepare`. `--doc` checks one given document.
  - Output is per-scenario pass/fail JSON. Exit 0 all pass, 1 a check failed, 2 no instance.
- **`run.mjs`.** It runs scenario × model (default `haiku,sonnet`) × 3 reps. Each run uses a temporary project directory, with the skill copied into `.claude/skills/`, and calls `claude -p` with:
  - `--setting-sources project` and `--no-session-persistence`;
  - `--strict-mcp-config --mcp-config <tmp>/mcp.json`, holding the HTTP URL of the instance the bridge selects;
  - `--tools Read,Skill,ListMcpResourcesTool,ReadMcpResourceTool`, so no other built-in tool exists in the run;
  - `--allowedTools` those plus the circuit tools without `circuit_file`, which is also in `--disallowedTools`;
  - `--permission-mode dontAsk`. The CLI has no `default` mode; its choices are acceptEdits, auto, bypassPermissions, manual, dontAsk, plan;
  - `--max-budget-usd 2` by default (`none` removes it). Claude 2.1.287 has no turn-cap flag, so the budget and a 20 min wall timeout bound each run;
  - `--output-format stream-json`.

  The `init` message is verified: tools within that set, only the `circuitjs` server and it connected, only the `circuitjs-circuits` skill, no plugin, no plugin-namespaced or user (`~/.claude/skills|commands`) command. A run that fails this is INVALID: recorded as such, and it counts as not passed. After the check, the run's documents are closed (changes discarded) unless `--keep`. The newest section of `results.md` records per-scenario counts, invalid runs, turns, tokens, cost, wall time and the releasable verdict. "Releasable: yes" is given only for a full set: all scenarios, ≥ 2 models, ≥ 3 reps. `--dry-run` prints every command and the first run's temporary config.
- **`check.mjs` additions.** `prepare` records one baseline handle after all fixture documents exist; the newest-document pick excludes the fixture documents. `check --close` closes the checked and fixture documents.
- **Verify.**
  - **Hand-built correct circuits** score PASS, exit 0: RC 1 k/159 nF (`out` p-p 1.415 V), LED with 330 Ω (9.75 mA; its document found as the newest after `prepare`), the repaired amplifier (`out` p-p 3.05 V), the divider with R2 = 6.95 kΩ (3.301 V).
  - **Hand-broken circuits** score FAIL, exit 1: RC with 1 µF and no checkpoint (0.315 V), LED with 1 kΩ (3.3 mA), the untouched amplifier fixture (dangling post; its run exhausts the budget), the untouched divider (3.0 V, no checkpoint).
  - **SP_MCB_05_03 eval-harness row.** The checker parses the CLI output of `call circuit_connectivity`; exit 0.
  - **`run.mjs`.** `--dry-run` printed 24 runs with the final command line. A stand-in `claude` script, which makes no model call, ran the full pipeline:
    - with a clean `init`: two runs, FAIL, as nothing was edited; their documents were closed;
    - with an `init` that leaks an extra skill: the run is marked INVALID in `results.md`.

    A `prepare` of all scenarios followed by a check without an agent document gives "no document", not a fixture document. No real agent eval was run.
- **Deviations.**
  - The `measure` probe of `led-driver-10ma` is `{elementType: "LED", quantity: "current"}`, a checker extension of ProbeSpec, because the agent chooses the IDs. Zero or several LEDs fail the check.
  - Checkpoints count only explicit agent checkpoints, excluding the fixture's.
- **Phase 4 is pending the developer's go-ahead.** It spends model usage.
  - **Size.** 4 scenarios × 2 models × 3 reps = 24 `claude -p` runs.
  - **Per-run assumption.** About 15–30 turns (skill and 1–3 references read, an import, 2–6 runs, edits, a checkpoint); 20–40 k tokens of context, 0.3–1 M input tokens processed (mostly cache reads), 5–15 k output tokens.
  - **Cost.** About $0.1–0.5 per haiku run and $0.5–2 per sonnet run: about $7–30 per full round. Wall time is 2–6 min per run, 1–2.5 h per round. Every skill iteration repeats the round.
  - Each run is capped at $2 by default (`--max-budget-usd`), so a full round costs at most $48.
  - **Isolation in Phase 4.** It is checked per run from `init`. If the CLI reports skills differently from the stand-in's `skills`/`slash_commands` fields, adjust `isolationProblems` after the first run.

### Phase 4 — Eval runs and results [TODO]  {#PL_AGS_P4}

**Depends on:** Phase 3; PL_AGA, PL_MCP, PL_MCB complete
**Implements:** [SP_AGS_05_02](./agent-skill.sp.md#SP_AGS_05_02) pass rule, [SP_AGS_05_05](./agent-skill.sp.md#SP_AGS_05_05) integration scenarios
**Verify:** `evals/results.md` shows every scenario passing in ≥ 2 of 3 runs on each of two model sizes; the Claude Code and Claude Desktop integration rows recorded as `observed` with date

What to do:
- Run the eval set. This spends model usage, so it needs the developer's go-ahead at that time.
- Iterate on the skill text until the pass rule holds.
- Record the results.

> **Open (2026-10-03):** only the Claude Desktop integration row is still to be observed. It is a manual row owed by the developer (steps in `evals/results.md`). Everything else in this phase is done, so the phase stays [TODO] until that row is recorded.

**Result (2026-10-03).** The developer approved the runs on 2026-10-03. They ran with `claude -p` (Claude Code 2.1.288: the CLI updated itself from 2.1.287, and every run's `init` reported 2.1.288), haiku and sonnet, against a scratch app instance (Xvfb, scratch HOME, `target/site` rebuilt at 1f79a13). The skill consistency check passed live before every round.
- **Smoke runs.**
  - The real `init` message lists the CLI's bundled skills (`skills`, `slash_commands`) and bundled plugins (`path: "builtin"`). The isolation check now flags only user skills/commands by name, namespaced names and non-bundled plugins. The user's `~/.claude/skills` did not leak.
  - `run.mjs` gained `--max-total-usd` (cost ceiling), a "Skill used" column and a skill-text hash in the results header. Wording changes keep the version (SP_AGS_04_01), but the hash tells the rounds apart.
- **Harness fix.** `--keep` also left each run's documents open, so a later run edited an earlier run's circuit. The round was aborted after 4 runs and restarted. `--keep` now keeps only the temporary directory; `--keep-docs` is separate.
- **Rounds** (each 4 scenarios × 2 models × 3 reps):

  | Round | Skill text | Failing (model) | Skill invoked | Cost |
  |---|---|---|---|---|
  | 1 | 7f3c03a4 (as committed) | tune-divider sonnet 0/3 | 11/24 | $2.84 |
  | 2 | 3dab4c17 | tune-divider haiku 1/3 | 18/24 | $2.83 |
  | 3 | 3488c3db | none: every scenario 3/3 on both models, **releasable** | 24/24 | $2.63 |

- **Total cost.** About $8.80, plus up to $2 for one killed run:
  - $8.30 for the three rounds (`results.md`);
  - $0.20 for the two smoke runs;
  - $0.30 for the 3 completed runs of the aborted round;
  - one sonnet run of the aborted round was killed before it reported a cost and is capped at $2.

  The $60 ceiling was never approached.
- **Failure analysis.** Every failure was `checkpoint_exists` in `tune-divider`. The agent did not load the skill for a one-value change to an open circuit; it tuned the divider correctly with the tools alone and did not checkpoint. No checker bug and no check was loosened.
- **Skill changes.**
  - **SKILL.md description.** Round 2: it names changing values, retuning and repairing an open circuit. Round 3: it also says "Load it before the first `circuit_*` call of any CircuitJS1 task".
  - **Step 9 note.** Checkpoint also after a one-value change.
  - **Step 7 note.** How to pass times.
  - **Step 2 note.** The most-used type names and keys. This removed every `unknown_type` call: 12 per round before, 0 in round 3.
  - **Version.** Wording only, so no version change per SP_AGS_04_01.
  - **Spec conflict.** SP_AGS_01_02 fixes the description value and must take the new text.
- **SP_AGS_05_05 integration rows.**
  - Claude Code over HTTP: observed 2026-10-03.
  - Eval runner: observed 2026-10-03.
  - Claude Desktop over the bridge: manual, owed by the developer.
- **Agent API/MCP findings, not fixed in this phase.**
  - Tool arguments typed `number | string` (`span`, `recordFrom`) repeatedly arrived as strings with embedded quotes (`"\"10 ms\""`) from both models, which gives `invalid_value`. The agents recovered by sending seconds; 5–9 such errors per round. Possible fix: strip one level of surrounding quotes, or describe the field as a plain string such as "10 ms".
  - Both findings are in the PL_AGA Backlog.
- **Isolation allowlist (review fix).** `run.mjs` now pins the CLI's bundled skills, slash commands and plugins per CLI version (2.1.288, taken from the runs' `init`). Any other name except `circuitjs-circuits` makes a run INVALID. A CLI version without a pinned bundle is refused before any run unless `--accept-bundled` is given. Checked with the stand-in: bundled only → valid; one unknown skill → INVALID; version 9.9.9 → refused, exit 2.
  - Agents probe the LED `color` key, which does not exist (`color_r/g/b`). This is a naming wart, not a defect.

## Backlog

- Template library as MCP resources (prior-art suggestion) — return when: evals show agents failing on circuits that a template would cover.
- Additional eval scenarios (logic, 555, op-amp filters) — return when: the four core scenarios pass stably.

## Changelog

| Date | Change |
|------|--------|
| 2026-10-01 | Initial version |
| 2026-10-02 | Phase 1 done: skill entry, references, host snippets |
| 2026-10-02 | Phase 2 done: consistency script |
| 2026-10-02 | Phase 3 done: eval set, checker, run driver (Phase 4 pending the go-ahead) |
| 2026-10-03 | Phase 4 eval rounds: releasable at skill text 3488c3db; Claude Desktop row owed |
