# Adjustable Sliders — Specification  {#SP_ADJ}

> **Code:** SP_ADJ
> **Status:** active
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_ADJ](./adjustable-sliders.concept.md)
> **Depends on specs:** SP_LUW, [SP_ELB](./element-base.sp.md), SP_EIC, SP_IOF
> **Used by specs:** [SP_EDI](./canvas-editor.sp.md), [SP_MEN](./menus-actions.sp.md)
> **Plan:** [adjustable-sliders.plan.md](./adjustable-sliders.plan.md)

## 01. Data Structures  {#SP_ADJ_01}

### 01_01. Adjustable  {#SP_ADJ_01_01}

| Field | Type | Required | Default | Constraints |
|-------|------|----------|---------|-------------|
| elm | CircuitElm | yes | — | must be in `simulator().elmList` |
| editItem | int | yes | — | `0 <= editItem < getEditInfoCount(elm)` |
| minValue | double | yes | from `EditInfo.minVal` | `minValue != maxValue` |
| maxValue | double | yes | from `EditInfo.maxVal` | — |
| sliderText | String | yes | `EditInfo.name` minus trailing parens | non-empty |
| sharedSlider | Adjustable | no | null | if set, this adjustable owns no Scrollbar |
| flags | int | yes | 1 | bit 0 = FLAG_SHARED (dump-only) |
| slider | Scrollbar | no (if shared) | — | HORIZONTAL, range 0..100, unit 1 |
| settingValue | boolean | yes | false | re-entrancy guard |

### 01_02. AdjustableManager  {#SP_ADJ_01_02}

| Field | Type | Default |
|-------|------|---------|
| adjustables | ArrayList<Adjustable> | empty |

Uniqueness key: `(elmIndex, editItem, sharedIndex)`.

### 01_03. Record `38` format  {#SP_ADJ_01_03}

    38 <elmIndex> F<flags> <editItem> <minValue> <maxValue> <sharedIndex> <escapedSliderText>

- `elmIndex`: `simulator().locateElm(adj.elm)` or `-1` sentinel to abort.
- `F<flags>`: always written as `F1` regardless of runtime flags.
- `sharedIndex`: `adjustables.indexOf(sharedSlider)` or `-1`.
- Label: `CustomLogicModel.escape(sliderText)`.

## 02. Contracts  {#SP_ADJ_02}

### 02_01. executeSlider  {#SP_ADJ_02_01}

    FUNCTION executeSlider():
        ei = elm.getEditInfo(editItem)
        ei.value = getSliderValue()
        renderer().needsAnalysis()
        elm.setEditValue(editItem, ei)
        updateValueLabel()
        cirSim.repaint()

### 02_02. execute (fan-out)  {#SP_ADJ_02_02}

    FUNCTION execute():
        IF settingValue: RETURN
        FOR each a in adjustableManager.adjustables:
            IF a.sharedSlider == this: a.executeSlider()
        executeSlider()

### 02_03. getSliderValue / setSliderValue  {#SP_ADJ_02_03}

    FUNCTION getSliderValue():
        s = sharedSlider != null ? sharedSlider.slider : slider
        RETURN minValue + (maxValue - minValue) * s.getValue() / 100

    FUNCTION setSliderValue(v):
        settingValue = true
        int intV = (v - minValue) * 100 / (maxValue - minValue)
        target = sharedSlider != null ? sharedSlider : this
        target.slider.setValue(intV)
        settingValue = false

### 02_04. createSliders  {#SP_ADJ_02_04}

    FUNCTION createSliders():
        dedupeAdjustables
        addMissingBuiltInAdjustables        -- one per HasBuiltInSlider element, range from its EditInfo
        dedupeAdjustables
        FOR each adj in adjustables:
            IF NOT adj.createSlider():
                adjustables.remove(adj)
        createControlRows                   -- one row per HasControlWidget element; old rows removed first

Called after every load, including paste (`RC_RETAIN`). `ensureBuiltInSlider(elm, refresh)` adds a missing built-in adjustable and refreshes its label; `deleteSliders(elm)` also removes the element's control row. Mouse wheel over a `HasBuiltInSlider` element is forwarded to its adjustable (`onBuiltInSliderWheel`). Slider position = `round((value - min) * 100 / (max - min))`.

### 02_05. reorderAdjustables  {#SP_ADJ_02_05}

Stable-partition: all `sharedSlider == null` first (owners), then sharers. Preserves intra-group order.

## 03. Validation Rules  {#SP_ADJ_03}

- `minValue != maxValue` (else degenerate scaling).
- `sliderText` non-empty (else `createSlider` returns false).
- `sharedSlider` must refer to an adjustable already present in `adjustables` at undump time.
- Dedup by `(elmIndex, editItem, sharedIndex)`; null elm or absent elm drops entry.

## 04. State Transitions  {#SP_ADJ_04}

| From | To | Trigger | Side effect |
|------|----|---------|-------------|
| unbound | owned | checkbox toggle in SliderDialog | `adjustables.add(adj)`; `adj.createSlider(ei.value)` |
| owned | shared | Choice change to "Share Slider: X" | `sharedSlider = X`; `deleteSlider()` |
| shared | owned | Choice change to "New Slider" | `sharedSlider = null`; `createSlider` |
| any | removed | checkbox unchecked / element deleted | `deleteSlider()`; `adjustables.remove(adj)` |

## 05. Verification Criteria  {#SP_ADJ_05}

### 05_01. Functional  {#SP_ADJ_05_01}

| Contract | Scenario | Input | Expected |
|----------|----------|-------|----------|
| executeSlider | drag to midpoint | slider=50 | ei.value = (min+max)/2; elm updated |
| execute (shared) | owner drag | 2 followers | all 3 elements updated |
| dump/undump roundtrip | share topology | 3 adj, 2 share owner 0 | owners-first order preserved; followers resolved |

### 05_02. Invariants  {#SP_ADJ_05_02}

| Invariant | Verification |
|-----------|--------------|
| re-entrancy guard | `settingValue` true inside `setSliderValue`; `execute` no-op |
| unique per key | no two adjustables share `(elmIndex, editItem, sharedIndex)` |
| built-in auto-bound | every `HasBuiltInSlider` element has an adjustable for `getBuiltInSliderItem()` after `createSliders` (pot item 3, LDR 1, NTC 5, VarRail 3) |
| control rows | exactly one Sliders-dialog row per `HasControlWidget` element of the active document |

### 05_03. Edge Cases  {#SP_ADJ_05_03}

| Case | Input | Expected |
|------|-------|----------|
| element deleted mid-session | `deleteSliders(elm)` | all bindings removed reverse-iter |
| >10 edit items | element with 12 items | AIOOB in SliderDialog (known bug) |
| label empty | sliderText="" | createSlider returns false; dropped |
| malformed `38` line | bad tokenizer | partial Adjustable; dropped if elm=null |

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
| 2026-09-30 | `HasBuiltInSlider` / `HasControlWidget`; createSliders on paste; rounding slider position. |
