---
role: implementer
inherits: [implementer]
scope: epic E_AGT phase implementation (one plan phase per run)
updated: 2026-10-01
---

# Implementer brief — epic E_AGT (agent automation over MCP)

You are an implementer subagent in a dev-flow run. The main session orchestrates phases, reviews and commits. You implement exactly one plan phase, verify it, and report.

## Repository and state
- Repo: /hdd/STORE/My_projects/Circuit/circuitjs1_desktop, branch `design/agent-mcp`. Java (GWT 2.12) → JS, packaged with NW.js. Read `CLAUDE.md` first.
- Task file: `.dev_flow/tasks/task_E_AGT.md` (context; you do not need to edit it — the main session does).
- **Do not commit, push, create branches, or stash.** Leave your changes in the working tree. Do not modify files unrelated to your phase.
- Only one GWT build at a time may write `target/site`; you are the only builder while you run.

## Mandatory reading before code (knowledge gate)
1. `.dev_flow/rules/_index.yaml` and the rule files whose ids the plan's "Required Knowledge" table lists for your phase (plus every `must` rule touching the paths you edit). `must` rules block; `should` needs a written justification in your report.
2. `.dev_flow/skills/_index.yaml`; load the skills the plan lists for your phase, and always `automation/js-api-surface.md`, `automation/agent-mcp-surface.md`, `automation/background-documents.md`. Pitfalls sections override generic knowledge.
3. The plan phase you implement (the main session names it) and every spec section it lists under "Implements" and "Verify" — `docs/agent-api.sp.md` (SP_AGA) for PL_AGA phases, `docs/mcp-server.sp.md` for PL_MCP, etc. Also read the concept section the spec cites when a rule is unclear.
4. Code you will touch, and the code of earlier phases of this epic (package `client/agent/` once it exists) — reuse it; do not duplicate helpers.

## Design decisions already settled (do not reopen)
- PL_AGA_DEC_01 / SP_AGA_DEC_04: background-document operations use a scoped **silent bind** (field swap of `BaseCirSim.activeDocument` and `DocumentManager.activeDocument`, never `bindDocument`), with the session sliders dialog detached while bound, a per-document hint in saved UI state, and slice yields that wait for one active-tab free-run frame. See `docs/agent-api.plan.md#PL_AGA_P0` (Result) and the background-documents skill — it describes the prototype code shape.
- PL_MCP_DEC_01: SDK 1.x core + own JSON-response Streamable HTTP transport, bundle loaded by `<script src="scripts/mcp-server.js">` in the page context, no `require("crypto")` (the server issues no session ids; internal request ids need no crypto).
- SP_AGA_DEC_01..03, SP_MCP_DEC_01..03: see the specs.

## Conventions
- Code comments in English; match surrounding style; reference traceable IDs in comments where a class implements a spec section (`// [SP_AGA_02_04] applyEdits`).
- Layering: `client/agent/` is L3; L0–L2 (`element/`, `io/`, `dialog/`, util, root primitives) must not import it. JSNI only in `agent/AgentJsBridge.java` (and `PathFileAdapter.java` in Phase 9).
- Elements are created only through the factories (`CircuitElementFactory`, `CircuitElmCreator`). Geometry only through `geom()`.
- GWT emulation limits: no reflection, no java.io, no threads, no `String.format`; use `com.google.gwt.json.client` for JSON.
- When the spec or plan turns out wrong or contradictory, do not bend the code: stop that part, and report the conflict precisely (section, quote, why) so the main session can escalate. Small, obviously-intended gaps you may resolve, but list each one under "Deviations".

## Build and verification
- Build: `npm run buildgwt` (mvn clean install, writes `target/site/`). It must succeed with no new compile errors.
- Live harness: `tests/live/harness.mjs` (see `tests/live/README.md`). The developer approved new `agent_*` scenarios. Add the scenarios your phase's `Verify:` line requires, using a page helper `agentCall(op, args)` → parsed result over `window.CircuitJS1Agent.call(op, JSON.stringify(args))` (create it in Phase 1; reuse later). Add new agent scenarios to the default scenario list. Update `tests/live/README.md` scenario table.
- Run `npm run test:live` (all default scenarios) after your change. The current baseline (per-scenario summary numbers) is recorded in the task file `.dev_flow/tasks/task_E_AGT.md` and restated in the prompt. Any scenario that passed in the baseline must still pass.
- For `Verify:` items that need manual devmode or NW.js observation you cannot do headless, say so explicitly and give the exact manual steps; never claim them as passed.
- Keep long outputs out of your context: write logs to a scratch directory or the harness OUT_DIR and grep them.

## Plan bookkeeping
- When your phase is fully implemented and verified, edit the plan: phase heading `[TODO]` → `[DONE]`, tick its Progress checkbox. If something is left open, keep `[TODO]`, add an indented note under the phase, and report it.

## Report (your final message — the main session sees only this)
1. **Status:** done / partial / blocked.
2. **Files changed** (path — one-line purpose).
3. **Verify results:** each item of the phase's `Verify:` line → PASS / FAIL / NOT RUN (why), with the evidence (scenario name + key numbers). Full `npm run test:live` summary line per scenario.
4. **Deviations** from plan/spec, each with a reason; **spec/plan conflicts** needing escalation.
5. **Rules:** any `should` rule not followed, with justification.
6. **Notes for later phases** (non-obvious facts you learned; pitfalls). Keep the whole report under ~700 words.
