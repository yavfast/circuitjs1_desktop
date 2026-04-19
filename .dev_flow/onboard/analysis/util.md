# Module Analysis: util

> **Path:** src/main/java/com/lushprojects/circuitjs1/client/util/
> **Layer:** 0
> **Analyzed:** 2026-04-18
> **Files:** 3 source files, 0 test files

## Purpose

The `util` package is the lowest-layer (Layer 0) utility module of the CircuitJS1
application. It collects three independent, cross-cutting helpers used throughout
the client code: i18n string lookup (`Locale`), a minimal logging façade (`Log`),
and a lightweight hierarchical profiler used by the renderer (`PerfMonitor`).

The three classes have no conceptual relationship to each other beyond "generic
utility" — they are grouped by accident of extraction rather than by a shared
domain. `Locale` and `Log` are pure façades delegating to a static map / to
`CirSim.console`; `PerfMonitor` is a small self-contained data structure built
around a parent/child timing tree plus a GWT JSNI clock.

Despite living at Layer 0 in the dependency graph, two of the three classes
violate strict layering by importing into higher layers: `Log` imports
`CirSim` (Layer 3 root) to route its output, and `PerfMonitor` imports
`element.BaseCircuitElm` (Layer 2) for number formatting. These inversions are
the single reason `util` appears in a cycle in the project dependency graph.

## Key Entities

### Locale
- **Type:** class (non-instantiable by convention — all members static; no private
  constructor, so still nominally instantiable)
- **File:** src/main/java/com/lushprojects/circuitjs1/client/util/Locale.java:27
- **Fields:**

  | Field | Type | Default | Notes |
  |---|---|---|---|
  | `localizationMap` | `public static HashMap<String, String>` | `null` | Populated externally by `circuitjs1.java:196` (`Locale.localizationMap = localizationMap;`) after locale file fetch. No null-guard in `LS`. |
  | `ohmString` | `public static String` | `"\u03a9"` (Ω) | Unit glyph constant. |
  | `muString` | `public static String` | `"\u03bc"` (μ) | Unit glyph constant. |

- **Invariants:** `localizationMap` must be assigned before any call to `LS`;
  otherwise `LS` throws `NullPointerException` (no guard). In practice the GWT
  entry point initializes it at startup.

### Log
- **Type:** class (utility; all methods static)
- **File:** src/main/java/com/lushprojects/circuitjs1/client/util/Log.java:5
- **Fields:** none.
- **Invariants:** none.

### PerfMonitor
- **Type:** class (instantiable; one inner static class `PerfEntry`)
- **File:** src/main/java/com/lushprojects/circuitjs1/client/util/PerfMonitor.java:8
- **Fields:**

  | Field | Type | Default | Notes |
  |---|---|---|---|
  | `rootCtxName` | `String` (package-private) | `null` | Name of the outermost context; set only on the first `startContext` after `reset()`. |
  | `rootCtx` | `PerfEntry` (package-private) | `null` | Root of the perf tree. |
  | `ctx` | `PerfEntry` (package-private) | `null` | Current frame pointer (stack top). |

- **Invariants:**
  - When `ctx == null`, `rootCtx` is either `null` (fresh / reset) or completed
    (set once on the first `startContext`).
  - `stopContext()` is a no-op if `ctx == null` (silent on underflow).
  - `addChild` on `PerfEntry` is idempotent per name: repeated `startContext`
    with the same name under the same parent **does not** create a new entry
    and does **not** descend into the existing one — it silently drops the
    sample (see Issues).

### PerfMonitor.PerfEntry
- **Type:** inner static class (package-private)
- **File:** src/main/java/com/lushprojects/circuitjs1/client/util/PerfMonitor.java:87
- **Fields:**

  | Field | Type | Default | Notes |
  |---|---|---|---|
  | `parent` | `public final PerfEntry` | ctor arg | Link to parent context (null for root). |
  | `children` | `public final HashMap<String, PerfEntry>` | `new HashMap<>()` | Named children. |
  | `startTime` | `public float` | `0` | Set from `getTime()` at context start. |
  | `endTime` | `public float` | `0` | Set from `getTime()` on `stopContext`. |
  | `length` | `public float` | `0` | `endTime - startTime`; reported in `buildString`. |

- **Invariants:** `length == endTime - startTime` after `stopContext`; before
  `stopContext` these three fields are uncommitted.

## Public Contracts

### Locale.LS
- **File:** src/main/java/com/lushprojects/circuitjs1/client/util/Locale.java:34
- **Input:** `String s` — key (may be null, empty, or suffixed with `~`
  disambiguator).
- **Output:** `String` — localized form, or the original key if no translation
  found.
- **Errors:** `NullPointerException` if `localizationMap` is uninitialized
  (the null-check is on `s`, not on the map).
- **Logic summary:** Null passthrough; empty passthrough (comment notes the
  trailing `~` check would misfire on empty). Look up `s` directly first. If
  missing and `s` ends with `~`, strip the trailing `~` and look up again.
  The trailing-`~` convention lets authors distinguish homographs in English
  that need different translations. Returns the last-resort fallback (stripped
  key) if no mapping found.

### Locale.LSHTML
- **File:** src/main/java/com/lushprojects/circuitjs1/client/util/Locale.java:61
- **Input:** `String s`.
- **Output:** `com.google.gwt.safehtml.shared.SafeHtml`.
- **Errors:** Same NPE risk as `LS`.
- **Logic summary:** Wraps `LS(s)` in `SafeHtmlUtils.fromTrustedString`; trusts
  the translator not to inject markup — translations are treated as safe HTML.

### Log.log
- **File:** src/main/java/com/lushprojects/circuitjs1/client/util/Log.java:7
- **Input:** `String... msg` — variadic parts to concatenate.
- **Output:** `void`.
- **Errors:** `NullPointerException` if any element of `msg` is null
  (StringBuilder.append(null) actually appends "null", so no throw — only a
  null varargs array would NPE).
- **Logic summary:** Concatenates all arguments into one `StringBuilder` (1024
  char initial capacity) and forwards to `CirSim.console(String)`. No level,
  no timestamp, no category.

### PerfMonitor.<init>
- **File:** src/main/java/com/lushprojects/circuitjs1/client/util/PerfMonitor.java:14
- **Input:** none.
- **Output:** new instance with all fields null.
- **Logic summary:** Empty constructor; fields are initialized lazily by
  `startContext`. Equivalent to the default constructor.

### PerfMonitor.reset
- **File:** src/main/java/com/lushprojects/circuitjs1/client/util/PerfMonitor.java:18
- **Input:** none.
- **Output:** `void`.
- **Logic summary:** Clears `ctx`, `rootCtx`, `rootCtxName` to null. Discards
  any prior perf tree wholesale.

### PerfMonitor.startContext
- **File:** src/main/java/com/lushprojects/circuitjs1/client/util/PerfMonitor.java:24
- **Input:** `String name` — context label.
- **Output:** `void`.
- **Errors:** silently discards samples on duplicate-name siblings (see Issues).
- **Logic summary:** Creates a new `PerfEntry` with `parent = ctx` and records
  `startTime`. If there is no current context, the new entry becomes `ctx` and,
  on first use after reset, also becomes `rootCtx` (with `name` captured as
  `rootCtxName`). Otherwise it is attached as a named child of the current
  context, but only if the name is not already present under that parent — if
  the name collides, the entry is discarded and `ctx` is not updated.

### PerfMonitor.stopContext
- **File:** src/main/java/com/lushprojects/circuitjs1/client/util/PerfMonitor.java:39
- **Input:** none.
- **Output:** `void`.
- **Logic summary:** If `ctx != null`, records `endTime`, computes `length`,
  and pops up to `ctx.parent`. No-op if `ctx == null` (silent on stack
  underflow).

### PerfMonitor.buildString (static)
- **File:** src/main/java/com/lushprojects/circuitjs1/client/util/PerfMonitor.java:53
- **Input:** `PerfMonitor mon`.
- **Output:** `StringBuilder` — multiline textual dump.
- **Errors:** `NullPointerException` if `mon.rootCtx == null` (no guard).
- **Logic summary:** Recurses from `rootCtx` via `buildStringInternal`, emitting
  one line per entry: `"-"*depth + name + ": " + formatted length + "\n"`.
  Uses `BaseCircuitElm.formatNumber(length, 2)` to format the float — the only
  cross-layer call from this module.

### PerfMonitor.getTime (private native)
- **File:** src/main/java/com/lushprojects/circuitjs1/client/util/PerfMonitor.java:74
- **Input:** none.
- **Output:** `float` — millisecond timestamp.
- **Logic summary:** GWT JSNI method; prefers `window.performance.now()`, falls
  back to `window.performance.webkitNow()`, then `new Date().getTime()`. Return
  type is `float` (source is JS `Number`/double) — precision loss possible but
  irrelevant for ms-scale render diagnostics.

### PerfMonitor.PerfEntry.addChild
- **File:** src/main/java/com/lushprojects/circuitjs1/client/util/PerfMonitor.java:101
- **Input:** `String name`, `PerfEntry entry`.
- **Output:** `boolean` — true if inserted, false if name already present.
- **Logic summary:** Map-insert only when absent; does not merge or overwrite.

### PerfMonitor.PerfEntry.getChild
- **File:** src/main/java/com/lushprojects/circuitjs1/client/util/PerfMonitor.java:109
- **Input:** `String name`.
- **Output:** `PerfEntry` or `null`.
- **Logic summary:** Plain map lookup. Currently unused by the rest of the
  codebase.

## Validation Rules

- `Locale.LS`: explicit null-check on input key; explicit empty-string guard
  (comment explains that the `ix != s.length() - 1` branch would misread
  empty strings).
- `Locale.LS`: trailing-`~` stripping is enabled only when `~` is the last
  character (`ix == s.length() - 1`); `~` anywhere earlier leaves the string
  alone.
- `Log.log`: none (no validation).
- `PerfMonitor.stopContext`: null-check on `ctx` → silent no-op when stack is
  empty.
- `PerfMonitor.PerfEntry.addChild`: duplicate-name rejection via
  `containsKey` → insert-once semantics.
- No range, argument, or type checks beyond those listed.

## State Transitions

**Locale** and **Log** are stateless per call (global map plus static methods).

**PerfMonitor** is a simple stack-with-tree lifecycle:

```
    reset()                   startContext(root)            startContext(child)
  (ctx=null,       ─────►   (ctx=root, rootCtx=root,   ─►  (ctx=child, root unchanged)
   root=null)                rootCtxName=name)
       ▲                             │                              │
       │ reset()                     │ stopContext() when depth=1   │ stopContext()
       │                             ▼                              ▼
       └────────────────── (ctx=null, root retained) ◄── (ctx=parent)
```

- After the outermost `stopContext`, `ctx` becomes `null` but `rootCtx` is
  retained so `buildString` can still emit the last frame's report.
- `reset()` is the only way to drop the retained `rootCtx`.
- Duplicate-name siblings in the same context silently fail to push — the
  monitor keeps the first observation, ignores subsequent ones.

## Integration Points

- **Depends on (inbound imports from project modules):**
  - `Log.java` → `com.lushprojects.circuitjs1.client.CirSim` (Layer 3 root) —
    for `CirSim.console(String)` output sink.
  - `PerfMonitor.java` → `com.lushprojects.circuitjs1.client.element.BaseCircuitElm`
    (Layer 2) — for `BaseCircuitElm.formatNumber(float, int)` at line 65. This
    is the **known Layer 0 → Layer 2 inversion**.
  - `Locale.java` → no project imports (only GWT `SafeHtml`).

- **Used by (callers across the project):**
  - `Locale` / `Locale.LS` / `Locale.LSHTML` / `Locale.ohmString` /
    `Locale.muString` / `Locale.localizationMap`: **81 Java files** reference
    `util.Locale` (grep `util\.Locale`). Distributed across:
    - `client/` root (14 files: `ActionManager`, `MenuManager`, `CirSim`,
      `CircuitRenderer`, `CircuitSimulator`, `TransistorModel`, `DiodeModel`,
      `ScopePopupMenu`, `Scope`, `Toolbar`, `Adjustable`, `CircuitLoader`,
      `ScopePlot`, `Checkbox`, `Choice`, `circuitjs1`) — the entry point also
      **writes** `Locale.localizationMap` after loading the locale asset.
    - `dialog/` (≈16 files: `EditOptions`, `EditDialog`, `ExportAs*Dialog`,
      `SliderDialog`, `SearchDialog`, `ShortcutsDialog`, `ShowLogDialog`,
      `LicenseDialog`, `HelpDialog`, `EditInfo`, etc.).
    - `element/` (≈48 files: every element that surfaces user-visible strings
      — `ResistorElm`, `CapacitorElm`, `DiodeElm`, `MosfetElm`, `JfetElm`,
      `RelayElm`, `LEDElm`, `LampElm`, `DCMotorElm`, `ProbeElm`,
      `BaseCircuitElm`, etc.).
    - `element/waveform/` (`DCWaveform`, `VarWaveform`).
    - `circuitjs1.java` is the sole writer of `Locale.localizationMap`
      (circuitjs1.java:196).
  - `Log.log`: **0 active callers**. The only reference in the codebase is a
    commented-out line in `dialog/Dialog.java:141`
    (`// Log.log("Restore position: ", posStr);`). The class is effectively
    dead code at present — every other logging call site uses
    `CirSim.console(...)` directly (e.g. `UndoManager.java:89`,
    `ClipboardManager.java:53/92/105`, `TransistorModel.java:55`).
  - `PerfMonitor`: **1 caller** — `client/CircuitRenderer.java`:
    - `CircuitRenderer.java:188` — `private final PerfMonitor perfmon = new PerfMonitor();`
    - `CircuitRenderer.java:199` — `perfmon.reset();` (per-frame)
    - `CircuitRenderer.java:200,221,314` — `perfmon.startContext("render()" | "graphics" | "elm.draw()")`
    - `CircuitRenderer.java:223,232,328` — matching `perfmon.stopContext()`
    - `CircuitRenderer.java:418` — `PerfMonitor.buildString(perfmon).toString()` used to surface perf data (likely to an overlay or debug pane).
    The duplicate-name quirk matters here: per-frame element draw iterations
    that reuse `"elm.draw()"` in the same parent context cause only the **first**
    element's time to be recorded per frame (see Issues).

- **External deps:**
  - `java.util.HashMap`, `java.util.Set` (Locale, PerfMonitor).
  - `com.google.gwt.safehtml.shared.SafeHtml`,
    `com.google.gwt.safehtml.shared.SafeHtmlUtils` (Locale).
  - GWT JSNI / browser `window.performance` API (PerfMonitor native method).
  - No JDK I/O, no threading primitives.

## Existing Documentation

- **Locale.java** (lines 1-18): GPL v2+ copyright banner (Paul Falstad and
  Iain Sharp / CircuitJS1).
- **Locale.LS** (lines 38, 46-48): two inline comments — one explaining the
  empty-string early return (`"empty strings trip up the 'if (ix != s.length() - 1)' below"`)
  and one explaining the trailing-`~` convention
  (`"use trailing ~ to differentiate strings that are the same in English but need different translations. remove these if there's no translation."`).
- **PerfMonitor.getTime** (line 75): URL comment pointing at
  `https://stackoverflow.com/questions/6875625` for the
  `performance.now` / `webkitNow` / `Date.getTime` fallback pattern.
- **Log.java**, **PerfMonitor.java** outside of `getTime`, and **PerfEntry**:
  no javadoc, no block comments, no `@deprecated`, no license header.

## Issues / Questions

- **Layer-0 → Layer-3 violation (Log):** `Log.log` imports `CirSim` to reach
  `CirSim.console`. This breaks the "util depends on nothing" invariant for
  Layer 0. Since `Log` has **zero active callers**, the cleanest fix is
  either to delete the class or to inject the console sink (e.g. a
  `Consumer<String>` or an `interface ConsoleSink` wired at startup) so that
  `util` no longer imports `client.CirSim`.
- **Layer-0 → Layer-2 violation (PerfMonitor → BaseCircuitElm):** The single
  import `com.lushprojects.circuitjs1.client.element.BaseCircuitElm` is used
  **only at PerfMonitor.java:65**, inside `buildStringInternal`, to call
  `BaseCircuitElm.formatNumber(entry.length, 2)`. `formatNumber` (defined at
  `element/BaseCircuitElm.java:125`) is a generic scientific-notation
  number formatter (`value`, `decimalPlaces`) that has no genuine element
  semantics — it just happens to live on `BaseCircuitElm`. The inversion is
  purely for code reuse. Three straightforward remediations:
  1. Move `formatNumber` (and its siblings at BaseCircuitElm.java:38/48/58/81/135)
     into `util` (e.g. a `NumberFormat` class) and have `BaseCircuitElm`
     depend on it instead.
  2. Inline a minimal formatter inside `PerfMonitor` (the perf dump does not
     need the full element formatter — millisecond rendering is trivial).
  3. Move `PerfMonitor` out of `util` into the `client/` root (where its sole
     caller lives) — trading a layer issue for a package-placement one.
- **`Locale.localizationMap` is nullable and publicly mutable:** No
  encapsulation; any file could overwrite the map mid-run. The map is
  initialized externally by `circuitjs1.java:196`, and no synchronization
  exists (acceptable only because GWT is single-threaded).
- **`LS` NPE on uninitialized map:** If `LS` is called before the entry
  point populates `localizationMap`, it throws. There is no fallback
  "just return the key" path for that state.
- **`PerfMonitor.startContext` silently drops duplicates:** Because
  `addChild` refuses to insert on name collision (PerfMonitor.java:102) and
  `startContext` does not descend into the existing sibling (PerfMonitor.java:33),
  a second `startContext("elm.draw()")` under the same parent (as would
  naturally happen in a loop over N elements) creates an orphan `PerfEntry`
  whose `parent` is set but which is never attached, and leaves `ctx`
  unchanged. The unmatched `stopContext()` that follows then pops the
  **outer** context, corrupting the stack. This is a latent bug relative to
  how `CircuitRenderer` uses it (see `CircuitRenderer.java:314/328` inside
  the element-draw loop). It may be benign in practice because only the
  first iteration registers — but means perf data for per-element timing is
  effectively "time of the first element" not "aggregate".
- **`PerfEntry` fields are `public`** (PerfMonitor.java:89-94): not
  immutable; external code could mutate `startTime`/`endTime`/`length`/`children`.
- **`PerfMonitor.buildString` NPE** when `rootCtx == null` (e.g. if called
  before any `startContext` or after a bare `reset()`).
- **Unused method:** `PerfEntry.getChild` (line 109) has no callers.
- **No license headers** on `Log.java` and `PerfMonitor.java`, unlike
  `Locale.java`. Minor compliance concern.

## Suggested Concept Boundaries

The module packs three unrelated concerns. For concept generation, prefer
**three separate concepts** rather than a single "util" concept:

1. **Locale / i18n dictionary** — static translation map with key-fallback
   and trailing-`~` disambiguation, plus `SafeHtml` bridge. Used by
   ≈81 files; stable surface.
2. **Log façade** — one-line concept; currently dead code with a layer
   violation. Consider marking it "deprecated / remove-or-inject" rather
   than normalizing it.
3. **PerfMonitor / hierarchical frame profiler** — per-frame render timing
   tree used only by `CircuitRenderer`. Includes the BaseCircuitElm
   inversion and the duplicate-name bug. Its real home may be alongside
   `CircuitRenderer` rather than in `util`.

Grouping them as one concept would obscure both the layer inversions and the
fact that `Log` is not in active use.
