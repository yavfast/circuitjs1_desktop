# Expression Engine — Specification  {#SP_EXP}

> **Code:** SP_EXP
> **Status:** active
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_EXP](./expression-engine.concept.md)
> **Depends on specs:** — (Layer 0; known leak to `CirSim.console`)
> **Used by specs:** `VCVSElm`, `VCCSElm`, `CCVSElm`, `CCCSElm` specs (populated at Layer 2)
> **Plan:** [expression-engine.plan.md](./expression-engine.plan.md)
>
> AST, parser, and runtime-state contracts for the custom-source mini-language.
>
> Backing analysis: [.dev_flow/onboard/analysis/root-utils.md](../.dev_flow/onboard/analysis/root-utils.md)

## 01. Data Structures  {#SP_EXP_01}

> Implements: [C_EXP_02](./expression-engine.concept.md#C_EXP_02)

### 01_01. Expr (AST node)  {#SP_EXP_01_01}

Fields:
| Field | Type | Required | Default | Constraints | Description |
|-------|------|----------|---------|-------------|-------------|
| type | `int` | yes | — | one of `E_*` opcodes | opcode |
| value | `double` | conditional | 0 | only for `E_VAL` | literal value |
| children | `Vector<Expr>` | conditional | empty | 1..N for operators | sub-expressions |

Opcode layout (positionally significant):
| Block | Base | Size | Slots |
|-------|------|------|-------|
| arithmetic / functions | 1..49 | — | `E_ADD=1, E_SUB=2, E_T=3, E_VAL=6, E_SIN, E_COS, …, E_LASTOUTPUT=30, E_TIMESTEP=31, …` |
| variables a..i | `E_A = 50` | 9 | direct variable access |
| derivatives da..di/dt | `E_DADT = 60` | 9 | `d/dt` per variable |
| last values lasta..lasti | `E_LASTA = 70` | 9 | previous-step value |

Invariants: `children.size()` matches the arity expected by `type`.

### 01_02. ExprParser  {#SP_EXP_01_02}

Fields:
| Field | Type | Notes |
|-------|------|-------|
| text | `String` | lowercased in ctor |
| token | `String` | current token |
| pos | `int` | scan position |
| tlen | `int` | current token length |
| err | `String` | first error, or null |

### 01_03. ExprState  {#SP_EXP_01_03}

Fields:
| Field | Type | Default | Description |
|-------|------|---------|-------------|
| values | `public double[]` length 9 | all 0 except `values[4] = Math.E` | current step a..i |
| lastValues | `public double[]` length 9 | all 0 | previous step snapshot |
| lastOutput | `public double` | 0 | previous step's output |
| t | `public double` | 0 | simulation time |
| timeStep | `public double` | 0 | current step size |

Invariants:
- `values.length == lastValues.length == 9`.
- `values[4] = Math.E` at construction; `reset()` does **not** restore it.

## 02. Contracts  {#SP_EXP_02}

### 02_01. new ExprParser(String source)  {#SP_EXP_02_01}

Purpose: Prime the parser; lowercases input; consumes leading whitespace;
primes first token.

### 02_02. parseExpression()  {#SP_EXP_02_02}

Output: root `Expr`. Empty input → `Expr(E_VAL, 0.)`. Extra tokens after
the top-level expression set `err = "unexpected token: …"`.

### 02_03. gotError()  {#SP_EXP_02_03}

Output: first error message or `null`.

### 02_04. Expr.eval(ExprState s)  {#SP_EXP_02_04}

Purpose: Evaluate the AST in `s`. Output: `double`.
Errors: unknown opcode < `E_A` logs `"unknown\n"` to `CirSim.console` and
returns 0. IEEE NaN / Infinity propagate.

Logic (pseudocode):
    FUNCTION eval(s):
        IF type == E_VAL: return value
        IF type == E_T: return s.t
        IF type == E_TIMESTEP: return s.timeStep
        IF type == E_LASTOUTPUT: return s.lastOutput
        IF type >= E_LASTA: return s.lastValues[type - E_LASTA]
        IF type >= E_DADT:
            i = type - E_DADT
            ts = max(s.timeStep, 1e-12)
            return (s.values[i] - s.lastValues[i]) / ts
        IF type >= E_A: return s.values[type - E_A]
        # else: arithmetic / function — dispatch on type

### 02_05. ExprState.updateLastValues(double lastOut)  {#SP_EXP_02_05}

Copies `values` into `lastValues`; sets `lastOutput = lastOut`. Called once
per simulation step by each custom-source element after `eval`.

### 02_06. ExprState.reset()  {#SP_EXP_02_06}

Zeros `lastValues`, `lastOutput`, `timeStep`. **Known issue:** does not
restore `values[4] = Math.E`.

### 02_07. Static helpers  {#SP_EXP_02_07}

- `Expr.pwl(state, Vector<Expr> args)` — piecewise-linear from
  `(x0,y0),(x1,y1),…`.
- `Expr.posmod(double, double)` — always-positive modulo.

## 03. Validation Rules  {#SP_EXP_03}

- Parser rejects leftover tokens with a concrete error message.
- `Expr.eval` guards `timeStep == 0` with floor `1e-12` only for `E_DADT`.
- Unknown opcodes logged but non-fatal (returns 0).
- Identifier vocabulary is fixed; unknown identifiers become an `err` at
  parse time.

## 04. State Transitions  {#SP_EXP_04}

### 04_01. ExprState lifecycle  {#SP_EXP_04_01}

    [ctor]     → values zero, values[4]=e, last*=0
    [step n]   → caller fills values[], t, timeStep; evaluate
    [postStep] → updateLastValues(lastOut): values → lastValues
    [reset]    → lastValues=0, lastOutput=0, timeStep=0 (NB: values[4] NOT restored)

## 05. Verification Criteria  {#SP_EXP_05}

### 05_01. Functional Expectations  {#SP_EXP_05_01}

| Contract | Scenario | Input | Expected outcome |
|----------|----------|-------|------------------|
| parseExpression | `"sin(2*pi*t)"` | valid | AST with root `E_SIN` |
| parseExpression | empty string | `""` | `Expr(E_VAL, 0.)` |
| parseExpression | trailing garbage | `"1+2)"` | err set |
| eval | ternary lazy | `"t>0 ? a/t : 0"` with t=0 | 0, no divide |
| eval | pwl | `pwl(t, 0,0, 1,1, 2,0)` at t=0.5 | 0.5 |
| updateLastValues | — | values[0]=5 | lastValues[0] becomes 5 next step |

### 05_02. Invariant Checks  {#SP_EXP_05_02}

| Invariant | Verification method |
|-----------|--------------------|
| Opcode layout (E_A, E_DADT, E_LASTA spacing of 10) | constant audit |
| `values.length == 9` | inspect after ctor |

### 05_03. Integration Scenarios  {#SP_EXP_05_03}

| Scenario | Preconditions | Steps | Expected result |
|----------|--------------|-------|-----------------|
| VCVSElm per-step eval | parsed AST | per step fill a..i,t → eval → drive output → updateLastValues | stable source output across steps |

### 05_04. Edge Cases and Boundaries  {#SP_EXP_05_04}

| Case | Input | Expected behavior |
|------|-------|-------------------|
| `E_DADT` at t=0 step boundary | timeStep=0 | floor 1e-12 used; no NaN |
| reset() then read `e` | — | 0 (known bug) until next update |
| unknown opcode | hacked AST | console "unknown\n", returns 0 |

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure |
