# Coding Style Rules

**Metadata**
- Scope: line-level style — Java level, GWT constraints, i18n, formatting, comments, string comparison, colors.
- Config scanned: `pom.xml` declares Java 17 (`pom.xml:25-26,57-58,125`). No `.editorconfig`, no `checkstyle.xml`, no `.idea/codeStyles/`. `.gitattributes` normalises line endings (`text=auto`).
- Evidence source: `.dev_flow/onboard/analysis/util.md`, `domain-core__element-base.md`, `root-widgets.md`, `layer3__cross-cutting-managers.md`, `domain-core__dialog-base.md`.

---

## Rule: JavaLanguageLevel

**Category:** style
**Severity:** must
**Applies to:** all Java sources

### Description
Project compiles against **Java 17** source and target (`pom.xml:25-26,57-58`; GWT plugin `sourceLevel=17` at `pom.xml:125`). You may use records, pattern matching, `var`, switch expressions, text blocks — whatever Java 17 permits — subject to GWT's supported subset. Running GWT 2.12.2 (`pom.xml:15`), which supports the Java 17 syntactic surface at JRE emulation level ≈ Java 11; avoid `java.util.concurrent`, `java.nio` file APIs, reflection.

### Rationale
Java version is the one objective configuration present in the repo. Enforced by the Maven compiler and the GWT compile step.

---

## Rule: GwtForbiddenApis

**Category:** style
**Severity:** must
**Applies to:** all `client/` code

### Description
GWT compiles Java to JavaScript. The following APIs are not emulated and must not be used:
- `java.awt.*` — GWT has no AWT. Rendering goes through `com.lushprojects.circuitjs1.client.Graphics` (wraps HTML canvas via `com.google.gwt.canvas.dom.client.Context2d`).
- `java.io.*` — all file IO crosses the JSNI boundary (NW.js `fs.*` via `LogManager`, `LoadFile`, etc.). `StringTokenizer` is re-implemented at the client root; use that, not `java.util.StringTokenizer`.
- Threads, `java.util.concurrent`, reflection.
- `java.nio.*`.

UI widgets come from `com.google.gwt.user.client.ui.*` (`DialogBox`, `Button`, `TextBox`, `TextArea`, `Widget`), as cited throughout `domain-core__dialog-base.md:365-378`.

### Examples
**Incorrect:**
```java
import java.awt.Color;            // not emulated
import java.io.FileWriter;        // not emulated
new Thread(() -> ...).start();    // no threads in GWT
```
**Correct:**
```java
import com.lushprojects.circuitjs1.client.Color;        // project's color type
import com.lushprojects.circuitjs1.client.Graphics;     // canvas wrapper
import com.google.gwt.user.client.ui.DialogBox;         // GWT widgets
```

### Rationale
GWT compile fails on any unemulated JRE class — the failure is early but the error messages are opaque. Knowing the allowed surface speeds up diagnosis.

---

## Rule: LocaleLsForUserStrings

**Category:** style
**Severity:** should
**Applies to:** all user-visible strings (dialog labels, menu items, button text, error messages)

### Description
Localise user-facing strings via `util.Locale.LS(String)` or `Locale.LSHTML(String)` (`util.md:89, 104`). Example: `Locale.LS("Resistance (Ω)")`. The localisation table is keyed by the English string.

**Inconsistency flagged:** the pattern is not uniformly applied — `Checkbox.java` calls `Locale.LS` for its label, but `CheckboxMenuItem.java:91` builds its shortcut hint via direct string concatenation (`root-widgets.md`). Treat new code as must-localise and legacy as grandfathered.

### Examples
**Correct:**
```java
return new EditInfo(Locale.LS("Resistance (Ω)"), resistance, 0, 0);
```
**Incorrect:**
```java
return new EditInfo("Resistance (Ω)", resistance, 0, 0);   // hard-coded English
```

### Rationale
Unlocalised strings break German/French/Danish/Polish users silently; they appear in English and the translation table gets no miss record.

---

## Rule: FormatNumbersViaBaseCircuitElm

**Category:** style
**Severity:** should
**Applies to:** any numeric output visible to the user (info panel, edit dialog captions, scope readouts, dump values)

### Description
Use `BaseCircuitElm` / `CircuitElm` formatting helpers for SI-prefixed values:
- `getUnitText(value, unit)` — full SI prefix formatting (`domain-core__element-base.md:272`).
- `getShortUnitText(value, unit)` — compact form.
- `getVoltageText`, `getCurrentText`, `getTimeText` — type-specific wrappers.
- `formatNumber(value, sigFigs)` — for log/telemetry output.
- `showFormat`, `shortFormat` — numeric formatters tuned for UI columns.

For dump-format numeric values, use `dumpValue(double)` / `dumpValues(...)` (`CircuitElm.java:419`) — it switches between decimal and scientific notation by magnitude.

### Examples
**Correct:**
```java
arr[0] = "R = " + getUnitText(resistance, "Ω");   // "1.2 kΩ"
String t = getTimeText(simulator.timeStep);         // "100 µs"
```
**Incorrect:**
```java
arr[0] = "R = " + resistance + " Ω";                 // "1200.0 Ω" — no SI scale
String t = String.format("%.3f", simulator.timeStep); // locale-dependent, no unit
```

### Rationale
Consistent SI-prefix rendering across elements/scopes/info panels. `NumberFormat` is locale-sensitive — the helpers use a single parseable format.

---

## Rule: PublicContractMethodsJavadoc

**Category:** style
**Severity:** should
**Applies to:** new public methods that are part of a published contract

### Description
Existing code is sparsely javadoc'd (most method-level comments sit on key contracts like `NodeState`, `Pin`, `setNodeVoltageDirect`, `ensureGeometryUpdated` — see `domain-core__element-base.md:917-922`; full file-level block comments on `ElmGeometry`, `CompositeElm`). Many analyses flag this as a weak point. New public API should carry brief javadoc on at least the contract behaviour — preconditions, side-effects, ordering constraints. Internal helpers can stay sparse.

### Examples
**Correct:**
```java
/**
 * Mark row {@code i} as having a left-side (matrix) stamp that changes every
 * Newton iteration. Must be called from {@code stamp()}; has no effect if
 * called from {@code doStep()}.
 */
public void stampNonLinear(int i) { ... }
```

### Rationale
The codebase has no external API docs; the javadoc is the contract. Elements inheriting from `CircuitElm` depend on knowing which base methods they can call during which phase.

---

## Rule: UseEqualsNotEqEqForStrings

**Category:** style
**Severity:** should
**Applies to:** all `String` comparisons

### Description
Use `.equals()` / `.equalsIgnoreCase()` for String comparison, not `==`. Existing violations flagged by analyses: `CheckboxMenuItem.java:91` and `ModDialog` (`root-widgets.md`). New code must use `.equals()`.

### Examples
**Correct:**
```java
if ("default".equals(modelName)) { ... }
```
**Incorrect:**
```java
if (modelName == "default") { ... }   // reference comparison, breaks with interned != literal
```

### Rationale
`==` happens to work for string literals because of interning but breaks the moment the string came from parsing (`StringTokenizer.nextToken()`, JSNI return values, imported dump lines).

---

## Rule: NoHardcodedColors

**Category:** style
**Severity:** should
**Applies to:** rendering code (any `draw*` method)

### Description
Use `ColorSettings` for all element/scope/background colors rather than hardcoding hex or RGB. `ColorSettings` is wired onto `CircuitDocument` (`domain-core__element-base.md:861`) and its fields drive the theme — light mode, dark mode, printable mode.

### Examples
**Correct:**
```java
g.setColor(circuitDocument.getColorSettings().selectedColor);
g.setColor(getVoltageColor(g, volt));   // voltage-indexed color via CircuitElm helper
```
**Incorrect:**
```java
g.setColor(new Color(255, 0, 0));    // hardcoded red
g.setColor(Color.RED);                // constant bypassing theme
```

### Rationale
Hardcoded colors break in dark mode and printable export. `ColorSettings` is the single source of truth.

---

## Rule: NoPrintfStyleLogging

**Category:** style
**Severity:** must
**Applies to:** diagnostic output

### Description
Do not use `System.out.println`, `System.err.println`, `GWT.log` for new diagnostic code. Route through `cirSim.log(...)` → `LogManager` (see `error-handling.md` → `LogViaLogManager`). The legacy violation at `ChipElm.java:315` is grandfathered.

### Examples
**Correct:**
```java
cirSim.log("stamp: v=" + v + " n=" + n);
```

### Rationale
`LogManager` is the UI-visible log pipeline; anything outside it is invisible to user bug reports.
