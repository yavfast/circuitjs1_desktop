# Implementation Plan: Expression Engine  {#PL_EXP}

> **Code:** PL_EXP
> **Status:** completed
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_EXP](./expression-engine.concept.md)
> **Specification:** [SP_EXP](./expression-engine.sp.md)
> **Depends on plans:** none
> **Used by plans:** `VCVSElm`, `VCCSElm`, `CCVSElm`, `CCCSElm` implementation plans (Layer 2)
>
> Retrospective plan covering the as-built mini-language interpreter used by
> custom-source elements.

## Goal

Document the as-built `Expr` / `ExprParser` / `ExprState` and capture the
three Issues flagged in analysis (CirSim leak, `reset()` `values[4]` bug,
opcode-layout fragility).

## Technology Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Language | Java 17 source | project-wide |
| Client compile target | GWT 2.12 | browser target |
| Parser style | recursive descent, one method per precedence | simple to extend |
| Identifier case | lowercased at construction | case-insensitive without extra lookups |
| AST node | single `Expr` class with opcode + children | trades type safety for code size |
| Variable storage | parallel arrays `values[9]` / `lastValues[9]` | tight hot loop, no boxing |

## Progress

- [DONE] Phase 1 — Expr (AST + eval switch)
- [DONE] Phase 2 — ExprParser (recursive-descent)
- [DONE] Phase 3 — ExprState (runtime environment)
- [backlog] Phase 4 — Remediations

## Phases

### Phase 1 — Expr (`client/Expr.java`) [DONE]

Implements: [SP_EXP_01_01](./expression-engine.sp.md#SP_EXP_01_01), [SP_EXP_02_04](./expression-engine.sp.md#SP_EXP_02_04), [SP_EXP_02_07](./expression-engine.sp.md#SP_EXP_02_07)

Shipped: 50+ opcode constants, `eval(ExprState)` giant switch,
static `pwl`, `posmod`.

### Phase 2 — ExprParser (`client/ExprParser.java`) [DONE]

Implements: [SP_EXP_01_02](./expression-engine.sp.md#SP_EXP_01_02), [SP_EXP_02_01](./expression-engine.sp.md#SP_EXP_02_01)–[SP_EXP_02_03](./expression-engine.sp.md#SP_EXP_02_03)

Shipped: lowercasing ctor, ternary → comparison → add → mult → unary → pow
→ term cascade, fixed identifier vocabulary, `gotError`.

### Phase 3 — ExprState (`client/ExprState.java`) [DONE]

Implements: [SP_EXP_01_03](./expression-engine.sp.md#SP_EXP_01_03), [SP_EXP_02_05](./expression-engine.sp.md#SP_EXP_02_05)–[SP_EXP_02_06](./expression-engine.sp.md#SP_EXP_02_06)

Shipped: `values`/`lastValues`/`lastOutput`/`t`/`timeStep`,
`updateLastValues`, `reset`. Ctor pre-seeds `values[4] = Math.E`.

## Backlog

Items transcribed from the analysis "Issues" section:

- **`Expr.eval` → `CirSim.console(...)` reverse dependency.** Replace with
  `GWT.log` or a pluggable logger injected at construction time so the
  expression engine is truly Layer 0.
- **`ExprState.reset()` does not restore `values[4] = Math.E`** — after
  reset, `e` resolves to 0 until the next `updateLastValues`. Add the
  assignment to `reset()`.
- **Opcode layout fragility** — `E_A + 10 == E_DADT` and `E_DADT + 10 ==
  E_LASTA` are invariant assumptions. Add a static assertion or test to
  prevent accidental renumbering.
- **Ctor parameter `ExprState(int xx)` is ignored** — legacy signature.
  Consider a no-arg ctor and deprecating the old one.
- **Identifier vocabulary is hard-coded in `parseTerm`.** Extracting into a
  map would simplify adding built-ins.
- **No unit tests** — add a suite of parse/eval golden vectors alongside
  any future changes.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure |
