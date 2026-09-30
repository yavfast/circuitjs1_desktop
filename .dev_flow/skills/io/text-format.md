---
skill: text-format
domain: io
topics: [falstad, text-format, dump-type, options-line, scope-line, hint-line]
source: onboard
updated: 2026-09-30
---

# Legacy Text Format

## Context

The text format is the original Falstad dump — **default format**
(`CircuitFormatRegistry.DEFAULT_FORMAT_ID = "text"`) and the format
used by `UndoManager` for snapshots. It is line-oriented, whitespace-
separated, loosely typed. `docs/EXPORT_OLD.md` has the complete element
grammar; this skill covers the framework-level structure and the
project-specific parsing quirks.

## Key concepts

**File structure** (`TextCircuitExporter.export`, `.java:52`):

```
$ flags maxTimeStep iterCount currentBar voltageRange powerBar minTimeStep   <- options (always first)
<model dumps>                                                                 <- optional, per-model lines
<element dumps>                                                               <- one per CircuitElm
<scope dumps>                                                                 <- `o ...` lines
<adjustable dumps>                                                            <- `38 ...` lines
h hintType hintItem1 hintItem2                                                <- optional hint line
```

Each element dump is built by `CircuitElm.dumpElm(ce)`
(`CircuitElm.java`): `<dumpType> x1 y1 x2 y2 flags [extra...] [# desc]`.
The optional trailing `# description` is stripped by `dumpElm` when a
description is set.

**Line dispatch** (`TextCircuitImporter.processCircuitLine`, L180-213)
is three cascading switches:

| Token | Handler |
|---|---|
| `o` | `new Scope(...).undump(tokenizer)` |
| `h` | `readHint(tokenizer)` |
| `$` | `readOptions(tokenizer)` |
| `!` | `CustomLogicModel.undumpModel(...)` |
| `34` | `DiodeModel.undumpModel(...)` |
| `32` | `TransistorModel.undumpModel(...)` |
| `38` | `adjustableManager.addAdjustable(...)` |
| `.` | `CustomCompositeModel.undumpModel(...)` |
| `%` / `?` / `B` | ignored (legacy afilter) |
| any other | `createStandardElement` → `CircuitElmCreator.createCe(...)` |

**Element reconstruction** is **not** inside `io/text/` — it goes through
the root-package `CircuitElmCreator`. The io module is **not
self-contained for the text path**. New element types register their
dump-type here (RULE_ARCH_005).

**Options line** (`dumpOptions()` in `ActionManager.java:591`). Fields:
`flags maxTimeStep iterCount currentBar voltageRange powerBar
minTimeStep`. `flags` is a bitmask over: dots / smallGrid / !volts /
power / !showValues / autoTimeStep (negations because bit 0 = "not
set"). `minTimeStep` and `powerBar` are wrapped in try/catch in
`readOptions` (L363) for backward compat with older dumps that lack
them.

**Selection dump** (`exportSelection`): writes only the element lines
for `ce.selected`, no options/scopes/hints. This is what copy/paste
uses.

**Tracking flag**, `clearDumpedFlags`: every `export()` and
`exportSelection()` call resets the four model-dump flags at the top
(`.java:62-66, :119-123`) so each export is self-contained.

**Reset behavior on import** (`resetCircuitState`, L112):
- Clears error/stop, deletes all elements, resets timings, menu states,
  viewport, scopes, voltage range to 5 V, current/power/speed sliders
  to 50/50/117.
- `RC_RETAIN` flag skips this reset (used by paste, paste-from-clipboard,
  undo/redo loads via `loadUndoItem`).

**Subcircuit-only mode** (`RC_SUBCIRCUITS`): short-circuits everything
except `.` lines — used by `CustomCompositeElm` to inline a subcircuit
model without touching the host document.

**Finalisation** (L374): `setPowerBarEnable`, slider creation,
`needAnalyze()`, optional `centreCircuit`, `updateModels` for
subcircuit mode, `AudioInputElm.clearCache()`,
`DataInputElm.clearCache()`, `setSlidersDialogHeight`.

## Usage in this project

- `CircuitLoader.readCircuit` auto-detects format via
  `CircuitFormatRegistry.detectFormatOrDefault` — falls back to text.
- `UndoManager.pushUndo` dumps via `actionManager().dumpCircuit()` which
  defaults to text (fast path; JSON would be slower for the high-
  frequency snapshot case).
- Every element class must implement two constructors: the normal one
  for editor placement, and `(CircuitDocument, int xa, int ya, int xb,
  int yb, int f, StringTokenizer st)` for text undump.

**Registering a new element type (RULE_ARCH_005):**
1. Override `getDumpType()` (RULE_NAMING_009; unique char or short int).
2. Override `dump()` if extra fields are needed: call `super.dump()` and
   append via `dumpValues(...)`.
3. Add a case to `CircuitElmCreator.createCe(...)` to construct the
   element from the tokenizer.
4. Round-trip verify with `npm run buildgwt` + `npm run test:live` (RULE_TEST_006).

## Pitfalls

1. **`canImport` is permissive** (`TextCircuitImporter.java:83`): starts
   with `$`, letter, or digit → yes. Because `LinkedHashMap` iteration
   puts text before JSON in `detectFormat`, arbitrary text blobs with
   leading letters are classified as circuits. Benign in practice but
   fragile.
2. **Parse errors are logged per line, not thrown** (RULE_ERR_003). A
   single corrupt line does not abort the load — but empty `catch {}`
   is still a violation of RULE_ERR_006 (log with reason).
3. **No version field is emitted or checked.** `TextCircuitFormat` has
   `version = "1.0"` as metadata only; import never verifies.
4. **Model lines precede element lines but are not required to.** The
   order in `export` is models-then-elements, but the importer dispatches
   per line, so a model referenced by an element *before* its own line
   would fail. Elements do a late bind via `updateModels()`.
5. **Extra tokens are silently accepted.** `StringTokenizer` stops when
   it stops caring — an element that adds a new dump field but fails to
   update the count does not throw; fields just read as defaults.
6. **Do not log via `System.out` or `GWT.log`** (RULE_STYLE_008,
   RULE_ERR_005) — route through `cirSim.log` / `LogManager`.

7. **Numbers in the dump must be lossless.** `CircuitElm.dumpValue(double)` writes whole numbers as integers and everything else with `Double.toString` (shortest round-trip form). Until 2026-09-30 it rounded to 4 decimals / 6 digits and `formatNumber` dropped the sign of values in (-1, 0) — every save, undo step and paste corrupted values. `npm run test:live textfid` checks this.
8. **The options line needs its own newline.** `ActionManager.dumpOptions()` returns `$ …` without `\n`; a caller that appends element lines must add it (the copy/duplicate path glued the first element onto the options line and dropped it).

## References

- `.dev_flow/onboard/analysis/io-framework.md` §"io-text"
- `docs/EXPORT_OLD.md` — full element-line grammar
- `src/main/java/com/lushprojects/circuitjs1/client/io/text/TextCircuitImporter.java`
  L64, L83, L112, L180, L374
- `src/main/java/com/lushprojects/circuitjs1/client/io/text/TextCircuitExporter.java`
  L52, L62, L146
- `src/main/java/com/lushprojects/circuitjs1/client/ActionManager.java`
  L591 (`dumpOptions`)
- Rules: RULE_ARCH_004, RULE_ARCH_005, RULE_NAMING_009, RULE_ERR_003,
  RULE_ERR_005, RULE_ERR_006, RULE_STYLE_008, RULE_TEST_003
- Sibling skill: `json-format.md`, `elements/element-authoring.md`
