# Adjustable Sliders — Live Parameter Binding  {#C_ADJ}

> **Code:** C_ADJ
> **Status:** active
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
> **Author:** onboard-doc-gen
>
> **Depends on:** C_LUW (root-widgets), [C_ELB](./element-base.concept.md), C_EIC (edit-info-contract, pending), C_IOF (io-framework)
> **Used by:** [C_EDI](./canvas-editor.concept.md), [C_MEN](./menus-actions.concept.md)
> **Spike:** —
> **Specification:** [SP_ADJ](./adjustable-sliders.sp.md)
> **Plan:** [adjustable-sliders.plan.md](./adjustable-sliders.plan.md)
>
> Backing analysis: `.dev_flow/onboard/analysis/layer3__sliders.md`.
>
> Interactive horizontal `Scrollbar` widgets collected in `SlidersDialog`, each bound to a single numeric `EditInfo` field on a `CircuitElm`. Dragging a slider updates the element parameter live (forces re-analysis + repaint). Bindings can be owned (this adjustable owns its slider) or shared (a slider drives multiple elements together). Persistence uses record prefix `38` in the text circuit dump.

## 1. Philosophy  {#C_ADJ_01}

### 1.1. Core Principle  {#C_ADJ_01_01}

Any `EditInfo` that returns `canCreateAdjustable() == true` automatically becomes slider-able, with no per-element code required. `VarRailElm` is the only hardcoded exception — every `VarRailElm` auto-gets a voltage slider from its `waveformInstance`'s `bias`/`maxVoltage`.

### 1.2. Design Constraints  {#C_ADJ_01_02}

- **Document-scoped manager.** `AdjustableManager` lives on `CircuitDocument`; the `SlidersDialog` host panel is per-sim.
- **Linear 0..100 mapping.** Slider integer range is fixed; value↔slider mapping is linear between `minValue/maxValue`.
- **Sharing requires ordering.** Owners must appear in the dump before followers so undump can resolve `sharedSlider` indexes in one pass (`reorderAdjustables()` stable-partitions).
- **Re-entrancy guard.** `settingValue` flag prevents `setSliderValue → slider.setValue → execute` loops.
- **Auto-bindings are synthesized, not persisted.** `VarRailElm` voltage sliders are recreated on every load; dedup prevents multiplication.

## 2. Domain Model  {#C_ADJ_02}

### 2.1. Key Entities  {#C_ADJ_02_01}

```
Adjustable extends BaseCirSimDelegate implements GWT Command
  CircuitElm  elm
  int         editItem                    -- index into elm.getEditInfo(i)
  double      minValue, maxValue
  String      sliderText
  Adjustable  sharedSlider                -- non-null => shares peer's widget
  int         flags; FLAG_SHARED=1        -- serialized-only, diverges from runtime
  Scrollbar   slider                      -- Scrollbar.HORIZONTAL, range 0..100
  Label       label, valueLabel
  Button      editAdjustableButton, editElementButton
  boolean     settingValue                -- re-entrancy guard

AdjustableManager extends BaseCirSimDelegate  (per CircuitDocument)
  ArrayList<Adjustable> adjustables

SliderDialog (in dialog/) extends Dialog   -- editor popup ("Add Sliders")
  CircuitElm elm
  EditInfo[10] einfos
  VerticalPanel vp; HorizontalPanel hp
```

### 2.2. Data Flows  {#C_ADJ_02_02}

```
User drag on Scrollbar
  -> Adjustable.execute()                       -- GWT Command callback
       -> fan-out: for each a in adjustables where a.sharedSlider == this:
            a.executeSlider()
       -> this.executeSlider()
  executeSlider():
    renderer().needsAnalysis()
    ei = elm.getEditInfo(editItem)
    ei.value = getSliderValue()                  -- minValue + (max-min)*int/100
    elm.setEditValue(editItem, ei)
    updateValueLabel()
    cirSim.repaint()

Circuit load:
  parser hits "38 …" line
    -> adjustableManager.addAdjustable(tokenizer)
         -> new Adjustable(tokenizer, cirSim)
         -> if adj.elm == null: discard
            else adjustables.add(adj)
  after load: createSliders()
    dedupeAdjustables
    addMissingVarRailVoltageAdjustables
    dedupeAdjustables
    for each adj: createSlider() (drop if label empty)

Circuit save:
  adjustableManager.dump()
    -> reorderAdjustables()
    -> for each adj: "38 " + adj.dump() + "\n"
```

## 3. Mechanisms  {#C_ADJ_03}

### 3.1. Core Algorithm  {#C_ADJ_03_01}

**Value mapping.** Forward: `intValue = (value - minValue) * 100 / (maxValue - minValue)`. Reverse: `value = minValue + (maxValue - minValue) * intValue / 100`. When `sharedSlider != null`, reads/writes delegate to peer.

**Dedup key.** `(elmIndex, editItem, sharedIndex)`. Also drops adjustables with null elm or elm no longer in `simulator().elmList`.

**Reorder invariant.** `reorderAdjustables()` stable-partitions: owners (`sharedSlider == null`) first, sharers after. This makes `sharedIndex` a forward-safe reference in the dump.

**Re-create slider.** `createSlider()` calls `deleteSlider()` first so re-imports don't duplicate widgets. Returns false when `sliderText` is empty (caller drops the Adjustable).

### 3.2. Edge Cases  {#C_ADJ_03_02}

- Hardcoded `F1` flags in dump (Adjustable.java:250) regardless of actual flags; the `-1` sentinel sharedIndex works because flags=1 unconditionally triggers the sharedIndex parse branch.
- `einfos[10]` array cap — elements with >10 edit items would AIOOB inside `buildDialog`.
- Exception-swallowing undump — malformed records produce partial Adjustables; null-elm ones get filtered by `addAdjustable`.
- Shared-slider dropdown uses `break` on first `sharedSlider != null` — relies on owner-first ordering.

## 4. Integration Points  {#C_ADJ_04}

### 4.1. Dependencies  {#C_ADJ_04_01}

- **C_LUW (root-widgets)** — `Scrollbar.HORIZONTAL` widget.
- **[C_ELB](./element-base.concept.md)** — `getEditInfo(i)`, `setEditValue(i, ei)`, `getUnitText(v, unit)`.
- **C_EIC (edit-info-contract)** — `EditInfo` (value/minVal/maxVal/unit/canCreateAdjustable), `EditDialog.unitString/parseUnits`.
- **C_IOF** — record `38` round-trip; `CustomLogicModel.escape/unescape` for labels.
- **[C_USR](./user-preferences.concept.md)** — none directly.
- **C_DOC** — `adjustableManager` lives on `CircuitDocument`.

### 4.2. API Surface  {#C_ADJ_04_02}

- `Adjustable`: ctors `(CirSim, elm, item)` and `(StringTokenizer, CirSim)`; `createSlider()`/`createSlider(double)`; `setSliderValue/getSliderValue`; `deleteSlider`; `sliderBeingShared`; `execute()`; mutable fields `minValue/maxValue/sliderText/sharedSlider`; `dump()`.
- `AdjustableManager`: `addAdjustable(StringTokenizer)`, `findAdjustable(elm, item)`, `createSliders`, `updateSliders`, `reset`, `clearSlidersDialog`, `deleteSliders(elm)`, `setMouseElm(elm)`, `reorderAdjustables`, `dump`.
- `SliderDialog`: ctor `(elm, sim)`, `apply()` — write back min/max/label; reprojects current value into slider space.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
