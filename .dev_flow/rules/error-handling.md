# Error-Handling Rules

**Metadata**
- Scope: simulator convergence failures, file import/export errors, JSNI exceptions, logging.
- Config scanned: no custom checkstyle; convention-driven.
- Evidence source: `.dev_flow/onboard/analysis/layer3__simulator-core.md`, `domain-core__dialog-info.md`, `layer3__cross-cutting-managers.md`, `io-framework.md`, `layer3__app-entry.md`, `util.md`. Recent commit `fb4ee85` (non-convergence recovery) and `a488ebb` (reset path) are authoritative.

---

## Rule: UseSimulatorStopForConvergenceFailures

**Category:** error-handling
**Severity:** must
**Applies to:** simulator-side failures and element `doStep` failures that affect solvability

### Description
When an element detects an unrecoverable numerical condition (singular matrix, NaN, runaway voltage), it signals the failure via `CircuitSimulator.stop(msg, ce)` / `warn(msg, ce)` (`layer3__simulator-core.md:456`) — not by throwing. The simulator stores `stopMessage` / `stopElm` / `warningMessage` / `warningElm`, which the UI surfaces and which blocks further `setSimRunning(true)` until `clearStopState()` is called on reset (`BaseCirSim.resetAction` L139, `layer3__simulator-core.md:73-74`).

The 3-level non-convergence escalator (commit `fb4ee85`, `layer3__simulator-core.md:363-389`) is the preferred recovery path for convergence failures; `stop()` is the fallback when `nonConvergenceRecoveryEnabled == false`.

### Examples
**Correct:**
```java
// CircuitSimulator pattern — cited layer3__simulator-core.md:73, 382
simulator.stop("Convergence failed! Element: " + getElementId(), this);
simulator.warn("wire loop detected", this);   // soft, non-fatal
```
**Incorrect:**
```java
@Override public void doStep() {
    if (Double.isNaN(value)) throw new RuntimeException("NaN in MyElm");  // escapes solver
}
```

### Rationale
Raw exceptions from `doStep` unwind through the Newton loop and break the reset path — the UI shows a stack trace but cannot recover. `stop(msg, ce)` integrates with `BaseCirSim.resetAction` and the panic-level escalator.

---

## Rule: NoRawRuntimeExceptionFromDoStep

**Category:** error-handling
**Severity:** must
**Applies to:** `CircuitElm.doStep()`, `startIteration()`, `stepFinished()`, `stamp()` overrides

### Description
Do not throw raw `RuntimeException`/`IllegalStateException`/`ArithmeticException` from the Newton-loop contract methods. The simulator wraps `elm.stamp()` in try/catch and routes failures to `stop()` (`layer3__simulator-core.md:228`); `doStep` has no such net. Prefer:
- Set the result via `simulator.stampCurrentSource(...)` / `simulator.converged = false` to trigger another Newton iteration.
- Call `simulator.stop(msg, this)` for unrecoverable state.
- Use `sanitizeStampValue` semantics (clamp ±1e12, NaN→0) — the simulator already does this for you (`layer3__simulator-core.md:291`).

### Examples
**Incorrect:**
```java
@Override public void doStep() {
    if (r <= 0) throw new IllegalArgumentException("negative resistance");
}
```
**Correct:**
```java
@Override public void doStep() {
    if (r <= 0) { simulator.stop("Invalid resistance", this); return; }
    simulator.stampResistor(n0, n1, r);   // stampResistor guards r=0/NaN/Inf internally (simulator-core.md:300)
}
```

### Rationale
`stop` path keeps the solver's invariants (`stopElm` set, mouse mode reset, UI informed, next reset usable). Raw exceptions bypass all of this.

---

## Rule: IoImportReturnsNullOrFlag

**Category:** error-handling
**Severity:** should
**Applies to:** text and JSON importers (`io/text/`, `io/json/`, `TextCircuitImporter`, `CircuitElementFactory`, model `undumpModel` methods)

### Description
Parse errors in file import should be reported via a returned `null`/false or by setting an error flag on the caller — not by throwing. The current importers already follow this: `CustomCompositeModel.undumpModel` silently skips if the model is already loaded (`CustomCompositeModel.java:115-131`, cited shared-models.md:130); `CustomLogicModel.parseRules` reports via `Window.alert` but does not throw. New importer code should prefer `LogManager.addLogEntry` plus a user-visible dialog over `Window.alert`.

An important caveat: **silent swallowing of parse errors is not the goal** — it is a pattern flagged as a should-not in the analyses (see Issues in `domain-core__shared-models.md` and `io-framework.md`). New code should log and surface, not silently drop.

### Examples
**Correct (new code):**
```java
try { ... parse ... }
catch (NumberFormatException e) {
    cirSim.log("Import: bad numeric token at line " + n + ": " + e.getMessage());
    return null;   // caller treats null as "skip this element"
}
```
**Incorrect:**
```java
try { ... } catch (Exception e) { /* swallow */ }   // silent, no trace
```

### Rationale
Import paths run during circuit load; any thrown exception aborts the whole file. Returning null lets the importer skip the broken element and continue; logging keeps the failure diagnosable. Silent catches accumulate technical debt (see issues #3, #6 in `domain-core__shared-models.md`, and `dialog-info.md`).

---

## Rule: JsniExceptionsViaUncaughtHandler

**Category:** error-handling
**Severity:** must
**Applies to:** JSNI boundary code and any code that may trigger asynchronous exceptions

### Description
Use `GWT.setUncaughtExceptionHandler(...)` — installed once in `circuitjs1.onModuleLoad` (`layer3__app-entry.md`) — to capture uncaught exceptions. They surface via `UncaughtExceptionDialog`. Do not install your own `try { ... } catch (Throwable t) { }` blocks at JSNI boundaries; the global handler already formats stack traces, preserves log state, and presents them to the user.

### Examples
**Correct:**
```java
// circuitjs1.java:50-66 (onModuleLoad) — install once
GWT.setUncaughtExceptionHandler(new GWT.UncaughtExceptionHandler() {
    @Override public void onUncaughtException(Throwable t) { new UncaughtExceptionDialog(t).show(); }
});
```

### Rationale
Per-site catches dilute the global handler's coverage and often swallow the type information GWT needs for meaningful stack traces in compiled JS.

---

## Rule: LogViaLogManager

**Category:** error-handling
**Severity:** must
**Applies to:** all diagnostic/log output

### Description
Log diagnostic messages through `BaseCirSim.log(String)` (`BaseCirSim.java:257-259`), which forwards to `LogManager.addLogEntry(...)`. `LogManager` maintains both the in-memory buffer consumed by `ShowLogDialog` and the optional NW.js file output (`layer3__cross-cutting-managers.md:87-103`).

The legacy `util/Log` class (dormant, 0 callers) was deleted on 2026-10-01 (BL-A01); do not reintroduce a parallel logger that bypasses `LogManager`.

`System.out.println` / `printStackTrace()` are also out of bounds (the last live sites, incl. `ChipElm.setVoltageSource`, were removed 2026-09-30). `CirSim.console(...)` is the per-document channel; since 2026-09-30 it also feeds `LogManager`, so it satisfies this rule.

### Examples
**Correct:**
```java
// BaseCirSim.log → LogManager.addLogEntry
cirSim.log("tripled timestep back to maxTimeStep=" + maxTimeStep);
// structured level helpers exist but are currently unused (LogManager.java:387-401):
logManager.logWarning("gmin ramp engaged at subIter=" + subIterations);
```
**Incorrect:**
```java
System.out.println("setVoltageSource failed for " + this);   // bypasses LogManager
GWT.log("debug: " + x);                                      // skips LogManager
```

### Rationale
`ShowLogDialog` reads `LogManager.logEntries` and provides a live tail + save-to-file feature; anything not routed through `LogManager` is invisible to the user when a bug report is requested.

---

## Rule: DoNotSilentlySwallowParseErrors

**Category:** error-handling
**Severity:** should
**Applies to:** new parser/importer code

### Description
Silent `catch { }` blocks that discard the thrown exception without logging are an anti-pattern flagged by multiple analyses (`domain-core__shared-models.md` issue #3, `io-framework.md`, `layer3__file-io-glue.md`, `domain-core__dialog-info.md`). Even when the policy is to continue parsing, log the skipped line / field / model and its reason. New code should not add to the existing silent-swallow set.

### Examples
**Incorrect:**
```java
try { return Integer.parseInt(tok); } catch (Exception e) { return 0; }   // silent default
```
**Correct:**
```java
try { return Integer.parseInt(tok); }
catch (NumberFormatException e) {
    cirSim.log("parseInt failed on token '" + tok + "', defaulting to 0");
    return 0;
}
```

### Rationale
Silent swallowing produces round-trip regressions that are invisible until a user reports a missing element; a logged skip is both traceable and recoverable (grep the log, reproduce).
