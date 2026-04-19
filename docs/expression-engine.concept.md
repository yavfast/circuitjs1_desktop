# Expression Engine: Expr, ExprParser, ExprState  {#C_EXP}

> **Code:** C_EXP
> **Status:** active
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
> **Author:** onboard
>
> **Depends on:** none (Layer 0 leaf; with a known back-reference to
> `CirSim.console` for error logging)
> **Used by:** four custom-source elements — `VCVSElm`, `VCCSElm`, `CCVSElm`,
> `CCCSElm`
> **Spike:** —
> **Specification:** [SP_EXP](./expression-engine.sp.md)
> **Plan:** [expression-engine.plan.md](./expression-engine.plan.md)
>
> A small recursive-descent arithmetic/logic mini-language used by voltage-
> and current-source elements that accept user-defined expressions (e.g.
> `sin(2*pi*t*1000) + 0.1*a`). Three classes: AST + interpreter (`Expr`),
> parser (`ExprParser`), runtime state (`ExprState`).

## 1. Philosophy  {#C_EXP_01}

### 1.1. Core Principle  {#C_EXP_01_01}

Users of custom-function sources need to enter a mathematical expression in
a text field and have it evaluated each simulation step. The engine offers
case-insensitive identifiers, built-in functions (`sin`, `cos`, `exp`,
`sqrt`, `tri`, `saw`, `pwl`, `min/max`, `step`, `select`, `clamp`, etc.),
nine scalar variables `a..i`, derivatives `da/dt..di/dt`, previous-step
values `lasta..lasti`, `lastOutput`, `t` (time), `timestep`, `pi`, and
ternary `? :`.

### 1.2. Design Constraints  {#C_EXP_01_02}

- Zero project-internal dependencies except one leak (`Expr.eval` calls
  `CirSim.console("unknown\n")` on bad opcode).
- Identifier vocabulary is fixed at parse time (no user-defined functions).
- Opcode constants are **positionally significant**: variable blocks are
  laid out contiguously so `eval` can compute slot index via subtraction:
  `E_A = 50`, `E_DADT = E_A + 10 = 60`, `E_LASTA = E_DADT + 10 = 70`.
- Parser lowercases the entire input → identifiers are case-insensitive.
- Runtime state pre-seeds `values[4] = Math.E` so variable `e` defaults to
  Euler's number.

## 2. Domain Model  {#C_EXP_02}

### 2.1. Key Entities  {#C_EXP_02_01}

- **Expr** — AST node: `type` (opcode), `value` (literal), `children`
  (`Vector<Expr>` — up to 3 for ternary, variadic for `min/max/pwl/clamp`).
  50+ opcode constants; `eval(ExprState)` is one large switch.
- **ExprParser** — recursive-descent parser with precedence cascade
  `parse` (ternary) → `parseOr` → `parseAnd` → `parseEquals` → `parseCompare`
  → `parseAdd` → `parseMult` → `parseUminus` → `parsePow` → `parseTerm`.
  First error text stored in `err`.
- **ExprState** — runtime environment: `double[] values` and
  `double[] lastValues` (length 9, slots `a..i`), `double lastOutput`,
  `double t`, `double timeStep`.

### 2.2. Data Flows  {#C_EXP_02_02}

Build phase (once per element edit):
`text → new ExprParser(text) → parseExpression() → Expr AST` (or `err` set).

Simulation loop (per step, per element):
caller fills `state.values[0..8]`, `state.t`, `state.timeStep` →
`expr.eval(state)` → output →
`state.updateLastValues(output)` snapshots `values` into `lastValues` and
records `lastOutput` for next-step derivative terms.

## 3. Mechanisms  {#C_EXP_03}

### 3.1. Core Algorithm  {#C_EXP_03_01}

Parser is a textbook recursive descent; pre-lowercases input in constructor
(`text = s.toLowerCase()`). `parseTerm` dispatches on identifier against a
fixed vocabulary; numbers fall through to `Expr(E_VAL, literal)`. Variadic
forms (`min/max/pwl/clamp`) collect comma-separated arguments into the
children vector.

Eval is a recursive tree walk:
- Binary ops read two children; ternary uses lazy eval.
- Variable lookups dispatch on `type >= E_LASTA` / `>= E_DADT` / `>= E_A`,
  picking slot `type - E_A` (0..8).
- `E_DADT` computes `(values[i] - lastValues[i]) / timeStep`, guarding
  `timeStep == 0` with `1e-12`.
- `E_TRIANGLE` / `E_SAWTOOTH` call `posmod` to normalize negative inputs.
- `E_PWL` linearly interpolates between `(x0,y0), (x1,y1), …`.

### 3.2. Edge Cases  {#C_EXP_03_02}

- Unknown opcode < `E_A` → logs `"unknown\n"` to `CirSim.console`, returns 0.
- Division by zero / log of non-positive → IEEE NaN / ±∞ propagates.
- Empty input → `Expr(E_VAL, 0.)`.
- Leftover tokens after top-level `parse()` → `err = "unexpected token: …"`.
- `ExprState.reset()` does NOT restore `values[4] = Math.E` — latent bug
  (variable `e` becomes 0 after reset until next update).

## 4. Integration Points  {#C_EXP_04}

### 4.1. Dependencies  {#C_EXP_04_01}

- Java: `java.util.Vector` (AST children).
- Project: `Expr.eval` calls `CirSim.console(...)` via same-package access
  (no import line, but conceptually a reverse dependency — flagged).

### 4.2. API Surface  {#C_EXP_04_02}

- Parse: `new ExprParser(String)`, `parseExpression() → Expr`, `gotError()
  → String?`.
- Evaluate: `expr.eval(ExprState) → double`.
- State: `new ExprState(int /* ignored */)`, `values[]`, `lastValues[]`,
  `lastOutput`, `t`, `timeStep`, `updateLastValues(double)`, `reset()`.
- Helpers (static on `Expr`): `pwl`, `posmod`.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure |
