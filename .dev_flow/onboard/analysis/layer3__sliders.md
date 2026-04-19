# Layer 3 — Sliders Sub-Unit (Adjustable Sliders)

## Purpose

Provides the "adjustable sliders" concept: interactive horizontal scrollbar widgets, collected in the `SlidersDialog` floating panel, each bound to a single numeric `EditInfo` field on a `CircuitElm`. Users drag a slider and the bound element parameter updates live during simulation (triggers re-analysis + repaint). The binding may be *owned* (this adjustable owns a concrete `Scrollbar`) or *shared* (this adjustable piggybacks on another adjustable's slider, so two elements move together). Persistent state (element ref, item index, min/max, label, shared-ref, flags) is round-tripped in the `.txt` circuit dump via record prefix `38`.

**File locations (verified):**
- `src/main/java/com/lushprojects/circuitjs1/client/Adjustable.java`
- `src/main/java/com/lushprojects/circuitjs1/client/AdjustableManager.java`
- `src/main/java/com/lushprojects/circuitjs1/client/dialog/SliderDialog.java`
- NOTE: No root-level `client/SliderDialog.java` exists. The queue entry listing "SliderDialog.java (root)" is stale/incorrect — only the `dialog/` variant exists. There is exactly one `SliderDialog` class, in package `com.lushprojects.circuitjs1.client.dialog`. (Not to be confused with `SlidersDialog` — plural — which is the host panel containing the live slider rows; `SliderDialog` — singular — is the *editor* popup for configuring which fields of an element become sliders.)

---

## Per-File Key Entities

### Adjustable.java (254 lines)

Represents one slider ↔ (Elm, editItem) binding. Extends `BaseCirSimDelegate`, implements GWT `Command` (called on slider value change).

| Member | Line | Role |
|---|---|---|
| `CircuitElm elm` | 18 | Target element whose `EditInfo` field is bound |
| `double minValue, maxValue` | 24–25 | Numeric domain of the slider (mapped linearly 0–100) |
| `int flags` + `FLAG_SHARED=1` | 26, 32 | Only flag is "dumped as shared" (used for backcompat parsing) |
| `String sliderText` | 27 | Display label |
| `Adjustable sharedSlider` | 30 | If non-null → this adjustable re-uses another adjustable's slider instead of owning one |
| `int editItem` | 35 | Index into `ce.getEditInfo(i)` selecting which field to drive |
| `Label label, valueLabel` | 41 | Title + formatted value readout |
| `Scrollbar slider` | 42 | The GWT scrollbar widget (`Scrollbar.HORIZONTAL`, range 0–100) |
| `Button editAdjustableButton, editElementButton` | 43 | Gear (⚙ opens `SlidersDialog` editor for this elm) + pencil (✎ opens full element edit dialog) |
| `Widget row` | 44 | The row container inside the SlidersDialog |
| `boolean settingValue` | 45 | Re-entrancy guard: true while `setSliderValue` is pushing a value, so `execute()` does not loop |
| `Adjustable(CirSim, CircuitElm, int item)` | 47 | Fresh ctor; seeds min/max from `EditInfo.minVal/maxVal` if they define a valid non-degenerate range |
| `Adjustable(StringTokenizer, CirSim)` | 62 | Undump ctor; backward-compat path for missing `flags` (`"F"`-prefixed token, line 71); resolves `sharedSlider` by index |
| `createSlider()` / `createSlider(double)` | 92, 112 | Build widgets; calls `deleteSlider()` first to prevent duplicates on re-import/refresh (line 103); returns false if `sliderText` empty |
| `addSliderToDialog(...)` | 139 | Inserts row into `CirSim.slidersDialog`; auto-shows dialog and sets its position/height |
| `removeSliderFromDialog(Widget)` | 152 | Removes row; hides dialog if emptied |
| `setSliderValue(double)` | 162 | External assignment; delegates to sharedSlider; uses `settingValue` guard |
| `execute()` | 174 | GWT `Command` callback from `Scrollbar`; fan-outs to every adjustable whose `sharedSlider == this` (so shared followers update too) |
| `executeSlider()` | 187 | Actual work: `renderer().needsAnalysis()`, push new value into `EditInfo`, `elm.setEditValue`, `updateValueLabel`, `repaint()` |
| `updateValueLabel()` | 196 | Formats via `CircuitElm.getUnitText(value, unit)` if `EditInfo.unit` set, else 2-decimal fallback |
| `getSliderValue()` | 209 | Maps slider's 0–100 → [minValue, maxValue]; reads from sharedSlider if present |
| `deleteSlider()` | 214 | Remove from dialog + null out all widget refs (clean re-create possible) |
| `setMouseElm(CircuitElm)` | 229 | Called by `AdjustableManager.setMouseElm`; just repaints slider (highlight tracking) |
| `sliderBeingShared()` | 234 | True if any other adjustable points here as `sharedSlider` |
| `dump()` | 244 | Serialize: `<elmIndex> F<flags> <editItem> <min> <max> <sharedIdx-or-(-1)> <escapedLabel>` — note always writes `F1` regardless of actual flags |

### AdjustableManager.java (166 lines)

Per-`CircuitDocument` collection + lifecycle orchestrator. Extends `BaseCirSimDelegate`.

| Member | Line | Role |
|---|---|---|
| `ArrayList<Adjustable> adjustables` | 12 | The authoritative list; position used as serialization handle |
| `AdjustableManager(BaseCirSim, CircuitDocument)` | 14 | Document-scoped |
| `addAdjustable(StringTokenizer)` | 23 | Called by circuit-parser on `38` records; discards adjustable whose elm couldn't be resolved |
| `findAdjustable(CircuitElm, int item)` | 31 | Lookup by (elm, editItem) key |
| `dump()` | 41 | Emits one `"38 " + adj.dump() + "\n"` line per adjustable for file save |
| `createSliders()` | 50 | Master rebuild: dedupe → `addMissingVarRailVoltageAdjustables` → dedupe → per-adjustable `createSlider()`, dropping any whose `createSlider` returns false |
| `dedupeAdjustables()` | 61 | Removes duplicates keyed by `elmIndex:editItem:sharedIndex`; also drops adjustables whose `elm` is null or missing from `simulator().elmList` |
| `addMissingVarRailVoltageAdjustables()` | 85 | Auto-binds a slider for every `VarRailElm` (uses `VarRailElm.EDIT_VOLTAGE`, `waveformInstance.bias`/`.maxVoltage`) — this is the "implicit" adjustable for variable rails |
| `updateSliders()` | 103 | `clearSlidersDialog()` + `createSliders()` — used after edits that may change available items |
| `reset()` | 108 | Clear list + clear dialog (called on new circuit) |
| `clearSlidersDialog()` | 113 | Clears + hides the `CirSim.slidersDialog` |
| `deleteSliders(CircuitElm)` | 123 | Called when an element is deleted; reverse iteration for safe removal |
| `setMouseElm(CircuitElm)` | 137 | Forwards to each adjustable (for draw-time highlight) |
| `reorderAdjustables()` | 149 | Stable-partition: owners first, sharers after, so undump sequence can resolve `sharedSlider` indexes forward-referentially |

### dialog/SliderDialog.java (255 lines)

The *editor* popup (title: "Add Sliders") invoked from an element's context menu. Extends `Dialog`. Note: distinct from `SlidersDialog` (the live panel).

| Member | Line | Role |
|---|---|---|
| `CircuitElm elm; CirSim sim` | 47–48 | Target element + sim ref |
| `Button applyButton, okButton, cancelButton` | 49 | Dialog actions |
| `EditInfo einfos[]` + `einfocount` | 50–51 | Cached `getEditInfo(i)` snapshot, up to 10 |
| `final int barmax = 1000` | 52 | (Unused in current flow; vestigial) |
| `VerticalPanel vp; HorizontalPanel hp` | 53–54 | Layout |
| `NumberFormat noCommaFormat` | 55 | `"####.##########"` (unused here; likely vestigial) |
| `SliderDialog(CircuitElm, CirSim)` | 57 | Builds action row, `buildDialog()`, centers |
| `buildDialog()` | 95 | Iterates editItems; for each with `ei.canCreateAdjustable()`: renders a `Checkbox` (enabled = an Adjustable exists). If exists: optional `Choice` dropdown "New Slider" / "Share Slider: <label>" (offered only if this slider is not itself being shared by others), plus `minBox`/`maxBox`/`labelBox` text fields preloaded with current `adj.minValue/maxValue/sliderText` via `EditDialog.unitString` |
| `findAdjustable(int item)` | 165 | Thin wrapper over `adjustableManager.findAdjustable(elm, item)` |
| `apply()` | 170 | Writes back `labelBox/minBox/maxBox` into the adjustable, then `adj.setSliderValue(ei.value)` to re-project the current value into slider space. Catches & consoles exceptions per-item |
| `itemStateChanged(GwtEvent)` | 195 | Checkbox toggle → add/remove `Adjustable` (creates slider immediately with label defaulted from `ei.name` minus trailing `(…)`); Choice change → switch between owned slider and shared-slider binding (finds target by index, null's own `sharedSlider` or sets it + deletes own row). Any change triggers `reorderAdjustables()` + rebuild of this dialog's body |
| `clearDialog()` | 250 | Removes all widgets above `hp` (action row) before rebuild |

---

## Adjustable Binding Mechanism

1. **Element reference** — `elm` is a direct `CircuitElm` pointer; resolved from serialized `elmIndex` at load time via `simulator().getElm(index)` / `locateElm(elm)`. If the element is deleted, `AdjustableManager.deleteSliders(elm)` purges.
2. **Field selector** — `editItem` is an integer index into the element's `EditInfo[]` produced by `elm.getEditInfo(i)`. The element is authoritative: adjustable simply calls `elm.setEditValue(editItem, ei)` with the mutated `EditInfo.value` (Adjustable.java:191).
3. **Value range** — `[minValue, maxValue]` seeded from `EditInfo.minVal/maxVal` when available (Adjustable.java:55–57), then overridable by the user via `SliderDialog`'s min/max text fields. `VarRailElm` auto-bindings override from the waveform instance's `bias`/`maxVoltage`.
4. **Slider widget** — a `Scrollbar` with fixed integer range 0–100, unit 1; the Adjustable itself is passed as the change Command.
5. **Forward mapping** (value → slider int): `intValue = (value - minValue) * 100 / (maxValue - minValue)` (Adjustable.java:117, 167)
6. **Reverse mapping** (slider int → value): `minValue + (maxValue - minValue) * val / 100` (Adjustable.java:211)
7. **Sharing** — If `sharedSlider != null`: this adjustable owns no widget; `getSliderValue()` reads peer's slider; `setSliderValue` delegates; `execute()` on the *owner* iterates the manager list and fans out `executeSlider()` to each follower (Adjustable.java:178–184).
8. **Live update path** — user drag → `Scrollbar` invokes `Adjustable.execute()` → fan-out → `executeSlider()` → `renderer().needsAnalysis()` + `elm.setEditValue` + `repaint()`.

---

## Manager Lifecycle

| Event | Handler | File:Line |
|---|---|---|
| Circuit load: `38 ...` record | `addAdjustable(StringTokenizer)` pushes undumped `Adjustable` | AdjustableManager.java:23 |
| After load / after edits | `createSliders()` — dedupes, injects missing `VarRailElm` voltage adjustables, then materializes each widget | AdjustableManager.java:50 |
| After add-slider from `SliderDialog` checkbox | Directly `adjustables.add(adj)`; `adj.createSlider(ei.value)` invoked first | SliderDialog.java:205–208 |
| Remove single slider (uncheck) | `adj.deleteSlider()`; `adjustables.remove(adj)` | SliderDialog.java:209–213 |
| Element deletion | `deleteSliders(CircuitElm)` walks list reversed, `deleteSlider()` + remove | AdjustableManager.java:123 |
| New circuit / reset | `reset()` clears list + `clearSlidersDialog()` | AdjustableManager.java:108 |
| Structural refresh | `updateSliders()` = clearDialog + recreate | AdjustableManager.java:103 |
| Share topology change | `reorderAdjustables()` — owners first, sharers last, for save-order determinism | AdjustableManager.java:149 |
| Mouse-over element | `setMouseElm(ce)` → each adjustable `slider.draw()` | AdjustableManager.java:137 |

**Uniqueness rule:** `(elmIndex, editItem, sharedIndex)` via `dedupeAdjustables()` (line 78). Duplicate with null elm or elm no longer in `elmList` is also pruned.

**VarRail implicit binding:** every `VarRailElm` gets a voltage slider auto-created (if not already present) with bounds from its waveform — see AdjustableManager.java:85–101.

---

## Serialization (slider state)

**Record format (record-type `38`):**

```
38 <elmIndex> F<flags> <editItem> <minValue> <maxValue> <sharedIndex> <escapedSliderText>
```

- Emitted by `AdjustableManager.dump()` (line 41) concatenating `"38 " + adj.dump()`.
- `adj.dump()` (Adjustable.java:244) always writes `" F1 "` (note: hardcoded, regardless of actual flag value — existing fixture in the code).
- `sharedIndex` is `adjustables.indexOf(sharedSlider)` or `-1`. Because owners are re-ordered first (`reorderAdjustables`), when undumping in order, the target of any `sharedIndex` is already present in `adjustables` at parse time (see `Adjustable(StringTokenizer, CirSim)` at line 81).
- Backcompat: historical dumps omitted the `flags` token; the undump detects a leading `F` and reads flags from it, else treats the token as `editItem` (lines 68–76).
- Label escaped/unescaped via `CustomLogicModel.escape/unescape`.
- Elm sentinel: leading `-1` elm index aborts undump cleanly (line 65).

---

## Public Contracts (surface API)

**Adjustable (consumed by dialog code, elements, manager):**
- `getElm()`, `getEditItem()`
- `createSlider()`, `createSlider(double value)`
- `setSliderValue(double)`, `getSliderValue()`
- `deleteSlider()`
- `sliderBeingShared()`
- `execute()` — GWT Command callback
- fields `minValue`, `maxValue`, `sliderText`, `sharedSlider` mutable by dialogs
- ctor `(CirSim, CircuitElm, int item)`, ctor `(StringTokenizer, CirSim)`

**AdjustableManager (the manager API called from CirSim and parsers):**
- `getAdjustables()`
- `addAdjustable(StringTokenizer)`
- `findAdjustable(CircuitElm, int item)`
- `createSliders()`, `updateSliders()`, `reset()`, `clearSlidersDialog()`
- `deleteSliders(CircuitElm)`
- `setMouseElm(CircuitElm)`
- `reorderAdjustables()`
- `dump()`

**SliderDialog (editor popup):**
- ctor `(CircuitElm, CirSim)` — constructs, centers, shows
- `apply()` — write field text back into Adjustable instances

---

## Integration Points

| Other subsystem | Touch-point | Reference |
|---|---|---|
| `dialog.SlidersDialog` (host panel) | receives per-row widget via `addSlider(...)`; `cirSim.slidersDialog` field | Adjustable.java:141–150 |
| `CirSim` | owns `slidersDialog`; `updateSlidersDialogPosition()` / `setSlidersDialogHeight()` | Adjustable.java:146–148 |
| `CircuitDocument` | owns `adjustableManager`; accessed via `getActiveDocument()` (Adjustable) or `elm.getCircuitDocument()` (SliderDialog) | AdjustableManager.java:14; SliderDialog.java:129, 166 |
| `CircuitElm` | `getEditInfo(i)`, `setEditValue(i, ei)`, `getUnitText(v, unit)` | Adjustable.java:54, 191, 201 |
| `EditInfo` | `value`, `minVal`, `maxVal`, `unit`, `canCreateAdjustable()`, `checkbox`/`choice`/`minBox`/`maxBox`/`labelBox` UI fields | SliderDialog.java:103, 111+ |
| `Scrollbar` | horizontal scrollbar widget; owner passes `this` as Command and `elm` for highlight tracking | Adjustable.java:118 |
| `CircuitEditor` | `doSliders(elm)` (gear button), `doEditElementOptions(elm)` (pencil) | Adjustable.java:123, 130 |
| `Simulator` | `elmList`, `getElm(i)`, `locateElm(elm)` for index ↔ ref resolution | Adjustable.java:87, 250; AdjustableManager.java:71, 87 |
| `VarRailElm` (passive / source) | implicit auto-binding via `EDIT_VOLTAGE`, `waveformInstance.bias`, `.maxVoltage` | AdjustableManager.java:85–101 |
| `CustomLogicModel` | static `escape()`/`unescape()` for the label token | Adjustable.java:83, 251 |
| `renderer().needsAnalysis()` | flags circuit for re-analysis on each value change | Adjustable.java:188 |
| `Locale.LS` | all user-visible strings localized | Adjustable.java:113; SliderDialog.java:59, 106, 144, 147, 152 |
| `EditDialog.unitString` / `parseUnits` | unit-aware text parsing shared with main element edit dialog | SliderDialog.java:157–158, 184, 186 |
| `BaseCirSimDelegate` | base class providing `cirSim`, `simulator()`, `circuitEditor()`, `renderer()`, `getActiveDocument()` accessors | Adjustable.java:17; AdjustableManager.java:10 |

**Record type `38`** is owned by this subsystem (both read via `addAdjustable` and written via `dump`); other record types are unrelated.

---

## Issues / Observations

1. **Hardcoded `F1` in dump** (Adjustable.java:250) — the persisted `flags` token is always `"F1"` even when the adjustable has no `FLAG_SHARED` or has other bits. Since `FLAG_SHARED` is the only defined flag and the undump path unconditionally reads `sharedSlider` only when bit `1` is set (line 79), writing `F1` always + writing `sharedIndex` always effectively couples the two: a non-shared adjustable still has its `-1` sharedIndex parsed (line 80) because flags=1. This works but diverges from the in-memory `flags` value; `FLAG_SHARED` is therefore dead/dummy for runtime reasoning.

2. **Debug `CirSim.console("slidertext ...")` left in** (SliderDialog.java:181) — verbose apply-time logging.

3. **Vestigial fields** in `SliderDialog`: `barmax`, `noCommaFormat` appear declared but unused in the visible logic (all numeric parsing delegates to `EditDialog.parseUnits/unitString`).

4. **Fixed array cap of 10** (`einfos = new EditInfo[10]`, SliderDialog.java:64) — silently caps browsable edit items per element. Loops iterate until null-sentinel but the array size limits how many items can be stored for apply. Elements with >10 edit items would `ArrayIndexOutOfBoundsException` inside `buildDialog`.

5. **`execute()` re-entrancy is correct but subtle** — `settingValue` flag protects `setSliderValue→slider.setValue→execute` loop; the peer fan-out uses a document-global list walk each time (O(N) per drag tick), which is fine at typical scales.

6. **Shared-slider dropdown semantics** (SliderDialog.java:133–137) — uses `break` when encountering a `sharedSlider != null` entry in the manager list. This relies on `reorderAdjustables()` keeping owners strictly before sharers; a correctness invariant baked into the manager.

7. **Exception-swallowing in undump ctor** (Adjustable.java:84–89) — both the parse block and the elm lookup have bare `catch (Exception)`, so malformed records silently produce a partially-initialized Adjustable. `AdjustableManager.addAdjustable` then filters those with `adj.elm == null`, but leaves other parse failures behind.

8. **No cast-safety on `(CirSim) this.cirSim`** in Adjustable.java:140/153 and AdjustableManager.java:24/86/114 — the subsystem implicitly assumes the runtime is the concrete `CirSim`, not just `BaseCirSim`; this is consistent project-wide but couples the code to the full app variant.

9. **`VarRailElm` implicit creation** (AdjustableManager.java:85–101) — creating these in `createSliders()` means they're synthesized on every load/refresh, not persisted as record `38` entries. Dedup by (elmIndex, editItem, sharedIndex) keeps them from multiplying.

10. **`SliderDialog` vs `SlidersDialog` naming is confusing** — both touch the same subsystem (`SliderDialog` = popup editor for configuring which fields are sliders on one element; `SlidersDialog` = the floating list panel holding all live sliders). Only the former is in scope here.

---

## Concept Boundary

These 3 files form a single cohesive concept — **"adjustable-sliders"**. The concept consists of:
- a **binding** (`Adjustable`) between a UI widget and an element's numeric `EditInfo` field,
- a **collection + lifecycle manager** (`AdjustableManager`) scoped to one `CircuitDocument`, responsible for dedupe, ordering, auto-seeding VarRail sliders, dump/undump of record `38`, and element-deletion cleanup,
- an **editor UI** (`SliderDialog`) for choosing which fields become sliders and their min/max/label/sharing.

The concept's boundary is clean: all in-subsystem coupling is internal; outward coupling is only via well-defined extension points (`CircuitElm.getEditInfo` / `setEditValue`, the `SlidersDialog.addSlider/removeSlider` host API, and the `38` record format in the save file). Any element type with an `EditInfo` that returns `canCreateAdjustable() == true` automatically participates — no per-element code is needed (the `VarRailElm` auto-adjustable is the only hardcoded exception).
