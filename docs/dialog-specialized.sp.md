# Specialized Dialogs — Specification  {#SP_DSP}

> **Code:** SP_DSP
> **Status:** active
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_DSP](./dialog-specialized.concept.md)
> **Depends on specs:** [SP_EIC](./edit-info-contract.sp.md), SP_SHM (shared-models), SP_ADJ (adjustable-sliders, pending), SP_SCP (scope-viz, pending)
> **Used by specs:** — (will be filled by higher layers)
> **Plan:** [dialog-specialized.plan.md](./dialog-specialized.plan.md)
>
> Backing analysis: `.dev_flow/onboard/analysis/domain-core__dialog-specialized.md`.
>
> Defines the 10 bespoke dialogs with their integration pattern, data source/sink, key widgets, and commit semantics.

## 01. Data Structures  {#SP_DSP_01}

> Implements: [C_DSP_02](./dialog-specialized.concept.md#C_DSP_02)

### 01_01. Per-dialog catalog  {#SP_DSP_01_01}

| Dialog | Pattern | Data source (read) | Data sink (write) | Key widgets | File:lines |
|--------|---------|-------------------|-------------------|-------------|------------|
| `ControlsDialog` | 2 | `simulator.maxTimeStep`; `TIME_STEP_VALUES[22]` | `simulator.maxTimeStep/timeStep`; allocates `cirSim.{timeStepBar, speedBar, currentBar, powerBar}` | 4× `Scrollbar` + dynamic timestep `Label` | `ControlsDialog.java:33-75, 80-103, 108-119` |
| `EditCompositeModelDialog` | 2 | `simulator.getCircuitAsComposite()` → `CustomCompositeModel`; `model.extList/sizeX/Y/showLabel/saved` | `model.*`, `ExtListEntry.pos/side` live, `CustomCompositeElm.lastModelName`, `simulator.updateModels`, `dumpCircuit → model.modelCircuit` | HTML5 `Canvas` 400×400, `TextBox` name, 2× `Checkbox`, 4× `Button` w/h | `EditCompositeModelDialog.java:76-122, 134-237, 254-267, 271-284, 286-302, 306-369` |
| `EditDiodeModelDialog` | 1 | inherited `EditDialog` row polling on `DiodeModel` | model fields via `setEditValue`; `model.pickName` if unnamed; `DiodeElm.newModelCreated(model)` | inherited form; Apply removed | `EditDiodeModelDialog.java:12-25` |
| `EditTransistorModelDialog` | 1 | inherited row polling on `TransistorModel` | model fields; `TransistorElm.newModelCreated(model)`; `pickName()` **commented out** | inherited form; Apply removed | `EditTransistorModelDialog.java:12-25` |
| `ScopePropertiesDialog` | 2 | `Scope.visiblePlots/manScale/maxScale/trigger/history/...`; `ScopeCheckBox` menu cmds | `scope.set*/handleMenu`, `scope.setText`, `scope.saveAsDefault` | `Grid(25..27, 3)`, `FlowPanel`, `Scrollbar`, 3× `RadioButton`, ~20 `CheckBox`/`ScopeCheckBox`, 3× `ListBox`, 7× `TextBox` | `ScopePropertiesDialog.java:243-632, 701-752, 754-821, 835-883, 917-933, 935-961, 963-967` |
| `ScrollValuePopup` | 3 | `elm.getEditInfo(0)` single row; `e12[]` × decades | `ei.value` → `setEditValue(0, ei)`; `needAnalyze`; `pushUndo` on open | 5× `Label` rolling strip; mouse-wheel | `ScrollValuePopup.java:59-93, 95-139, 142-155, 157-181, 200-213, 217-225` |
| `SearchDialog` | 2 | `sim.menuManager.mainMenuItems` (stop at multi-char shortcut) | `item.getScheduledCommand().execute()` | `TextBox` (maxLength 15), `ListBox` (10 visible) | `SearchDialog.java:52-107, 109-122, 124-145` |
| `SliderDialog` | 2 | `elm.getEditInfo(i)` (skip `!canCreateAdjustable`); `adjustableManager.findAdjustable(elm, i)` | `adjustableManager.adjustables`±; `adj.{min,max,sliderText,sharedSlider,createSlider,deleteSlider}` | hijacks `ei.{checkbox,choice,minBox,maxBox,labelBox}` | `SliderDialog.java:57-93, 95-163, 170-193, 195-248` |
| `SlidersDialog` | 2 | passive — fed by `AdjustableManager.addSlider(...)` | `Scrollbar` callbacks registered by caller | `VerticalPanel` rows (title, value, `Scrollbar`, 2× edit Button) | `SlidersDialog.java:17-24, 27-46, 53-97` |
| `SubcircuitDialog` | 2 | `CustomCompositeModel.getModelList()` (non-builtin) | `model.remove()` → local-storage unregister | `ListBox` (5 visible), Delete + Done | `SubcircuitDialog.java:23-70, 72-88` |

### 01_02. Integration patterns (legend)  {#SP_DSP_01_02}

| Pattern | Members |
|---------|---------|
| 1 — `EditDialog` subclass + post-apply hook | `EditDiodeModelDialog`, `EditTransistorModelDialog` |
| 2 — Hand-rolled `Dialog` subclass | `ControlsDialog`, `EditCompositeModelDialog`, `ScopePropertiesDialog`, `SearchDialog`, `SliderDialog`, `SlidersDialog`, `SubcircuitDialog` |
| 3 — `PopupPanel` escape hatch | `ScrollValuePopup` |

### 01_03. Persistence / side-effect matrix  {#SP_DSP_01_03}

| Dialog | Simulator touch | Undo push | LocalStorage touch |
|--------|-----------------|-----------|---------------------|
| `ControlsDialog` | `maxTimeStep`, `needAnalyze` | no | position/collapse via `getOptionPrefix="ControlsDialog"` |
| `EditCompositeModelDialog` | `updateModels`, `needAnalyze` | no | `model.setSaved` (subcircuit:\<name\>) |
| `EditDiodeModelDialog` | via inherited `EditDialog.apply → needAnalyze` | no | — |
| `EditTransistorModelDialog` | same | no | — |
| `SliderDialog` | none direct (via Adjustable) | no | — |
| `SlidersDialog` | none | no | position/collapse `"SlidersDialog"` |
| `ScrollValuePopup` | `needAnalyze` | **yes** (on open) | — |
| `ScopePropertiesDialog` | none (scope is display) | no | `scope.saveAsDefault` |
| `SearchDialog` | indirect via `ScheduledCommand` | — | — |
| `SubcircuitDialog` | none | no | `model.remove` unregisters key |

### 01_04. Constants (dialog-specific)  {#SP_DSP_01_04}

- `ControlsDialog.TIME_STEP_VALUES[22]` — 1pS → 10µS; 1/2/5 per decade.
- `ScrollValuePopup.e12[12]` — E12 preferred series.
- `ScrollValuePopup` decade ranges: R `1e-1..1e7`; C `1e-11..1e-3`; L `1e-6..1e0`.
- `EditCompositeModelDialog` chip anchor: `(50,50)-(200,50)`; canvas 400×400.
- `ScopePropertiesDialog` adaptive thresholds: `displayAll = height>600`, `displayScales = height>470`.
- `SearchDialog.textBox.maxLength = 15`; `listBox.visibleItemCount = 10`.

## 02. Contracts  {#SP_DSP_02}

### 02_01. Pattern 1 — Model-catalog post-apply  {#SP_DSP_02_01}

```
ctor: super(model); applyButton.removeFromParent()

apply() override (on OK):
    super.apply()                         # EditDialog row polling
    IF this is DiodeDialog AND model.name is unnamed:
        model.pickName()                 # auto-name
    elm.newModelCreated(model)           # force getModelWithNameOrCopy rebind
    closeDialog()
```

### 02_02. Pattern 2 — EditCompositeModelDialog create/commit  {#SP_DSP_02_02}

```
createModel():
    model = new CustomCompositeModel(simulator.getCircuitAsComposite())
    sort extList by name
    validate: no pin-on-same-node, ≥1 ext pin (else throw)
    auto sizeX/Y from pin-counts per side

createDialog():
    IF model.name == null: add TextBox + Cancel
    ELSE: OK-only (name immutable)
    Canvas + showLabel/saveAcrossSessions checkboxes + 4× w/h buttons

mouseMove:
    overlapPin = hitTest(ptr)
    swap pos with dragging pin in extList
    rebuild chip from model

enterPressed (OK / Enter):
    model.setName(name); model.setSaved(saveCheck.getState())
    CustomCompositeElm.lastModelName = name
    simulator.updateModels(); needAnalyze()
```

### 02_03. Pattern 2 — ControlsDialog log-scale mapping  {#SP_DSP_02_03}

```
positionToTimeStep(p): return TIME_STEP_VALUES[p]   # O(1) array lookup

timeStepToPosition(ts):                              # O(22), log-space scan
    best = min_i | log10(TIME_STEP_VALUES[i]) - log10(ts) |
    return best

updateTimeStepBar():                                 # called by simulator on auto-adjust
    bar.setValue(timeStepToPosition(simulator.maxTimeStep))
    updateLabel()
```

### 02_04. Pattern 2 — SliderDialog rebuild-on-change  {#SP_DSP_02_04}

```
buildDialog():
    FOR i = 0..; ei = elm.getEditInfo(i); break if null:
        IF !ei.canCreateAdjustable(): continue
        ei.checkbox = new Checkbox(...)       # HIJACK
        adj = adjustableManager.findAdjustable(elm, i)
        IF adj != null:
            ei.choice = Choice("New Slider", "Share: ...")   # HIJACK
            ei.{minBox, maxBox, labelBox} = TextBoxes         # HIJACK (populated from adj)

apply():
    FOR each hijacked ei:
        IF ei.checkbox.getState() AND adj == null: create Adjustable
        IF !ei.checkbox.getState() AND adj != null: remove Adjustable
        adj.min = parseUnits(ei.minBox); adj.max = parseUnits(ei.maxBox); adj.sliderText = ei.labelBox.getText()
    adjustableManager.reorderAdjustables()

itemStateChanged: apply + clearDialog + buildDialog    # same pattern as EditDialog
```

### 02_05. Pattern 3 — ScrollValuePopup  {#SP_DSP_02_05}

```
ctor(elm):
    circuitEditor.pushUndo()
    inf = elm.getEditInfo(0)
    values[] = sorted(e12 × decades, insert current value if absent)
    currentidx = indexOf(inf.value, values)
    render 5 Labels; center at currentidx

wheel(deltaY):
    currentidx += sign(deltaY) * wheelSensitivity
    clamp; setElmValue(currentidx)

setElmValue(i):
    ei.value = values[i]; elm.setEditValue(0, ei); sim.needAnalyze()

close(keep):
    IF !keep: setElmValue(originalIdx)  # revert (via setEditValue again)
    hide()
```

### 02_06. ScopePropertiesDialog apply  {#SP_DSP_02_06}

```
apply():
    FOR each ScopeCheckBox cb: scope.handleMenu(cb.menuCmd, cb.state)
    IF manualMode: scope.setManualScale(parseUnits(manualScaleBox.getText()))
    IF triggerEnabled: applyTriggerHistory(...)
    scope.setText(labelBox.getText())
closeDialog override:
    apply(); super.closeDialog()          # titlebar X auto-applies — inconsistent

refreshDraw (every sim step):
    update manual-scale UI only when NOT in manual mode (preserve user typing)
```

## 03. Validation Rules  {#SP_DSP_03}

### 03_01. Input Validation  {#SP_DSP_03_01}

- `EditCompositeModelDialog.createModel` validates: no two pins on same node; at least one external pin. Else throws.
- `EditTransistorModelDialog` does NOT call `model.pickName()` — unnamed transistor models edited here may have `name==null` (silent catalog-map corruption).
- `SliderDialog.einfos[10]` hard cap (inherited).
- `SliderDialog.apply` silent try/catch on parse (like `EditDialog.apply`).
- `ScrollValuePopup` assumes `getEditInfo(0)` is numeric; NPE otherwise.
- `ScrollValuePopup` pushes undo on open; `close(false)` revert does not push a second undo.
- `ScopePropertiesDialog.closeDialog` auto-applies (titlebar-X = Save, not Dismiss).
- `SubcircuitDialog.handleDelete` prompts `Window.confirm`; no refresh of `MenuManager` after delete.
- `SearchDialog` substring match is simple `contains` (no fuzzy ranking); early-break at shortcut > 1 char may truncate corpus.
- `ControlsDialog` stashes widgets on `cirSim` (global state).

## 04. State Transitions  {#SP_DSP_04}

### 04_01. EditCompositeModelDialog lifecycle  {#SP_DSP_04_01}

```
idle ──menu──▶ createModel (validate) ──▶ createDialog (name empty path)
                                           │
                                           ▼
                              Canvas + name + checkboxes
                                           │
                                   user drags pin / types name
                                           │
                                           ▼
                               enterPressed / OK
                                           │
                          model.setName + setSaved; updateModels; needAnalyze
                                           │
                                           ▼
                                        closed
```

### 04_02. SliderDialog lifecycle  {#SP_DSP_04_02}

```
idle ──right-click elm──▶ buildDialog (hijacked EditInfo rows)
                             │
                             ▼
              user toggles checkbox / choice share target
                             │
                  itemStateChanged → apply + clearDialog + buildDialog
                             │
                             ▼
                           apply / OK / Cancel
```

## 05. Verification Criteria  {#SP_DSP_05}

### 05_01. Functional Expectations  {#SP_DSP_05_01}

| Contract | Scenario | Input | Expected |
|----------|----------|-------|----------|
| Diode model edit | unnamed model | click Create Simple Model | `pickName()` sets name; `newModelCreated` rebinds DiodeElm |
| Transistor model edit | unnamed model | same | bug: name stays null (pickName commented) |
| Composite create | current circuit w/ ≥1 ext pin | menu Create Subcircuit | dialog opens, canvas draws chip, save persists to local storage |
| ControlsDialog timestep | drag slider to 1µS | — | `simulator.maxTimeStep = 1e-6`; label updates |
| Slider sync | existing Adjustable | drag scope property | SlidersDialog row reflects live value |
| ScrollValuePopup wheel | right-click 10k resistor, wheel down | — | value traverses E12 series; element updates each tick |
| Search | type "resi" | — | ListBox shows matching top-level menu items |
| Scope property | click manual mode + type "500m" | — | `scope.setManualScale(0.5)` |

### 05_02. Invariant Checks  {#SP_DSP_05_02}

| Invariant | Verification |
|-----------|--------------|
| Exactly one `ControlsDialog` instance registered in `cirSim` bars | `cirSim.timeStepBar` reference-equal to dialog's scrollbar |
| SliderDialog hijacks only non-standard EditInfo fields | `minBox/maxBox/labelBox` only used here |
| ScrollValuePopup 5-slot label strip | always centred on `currentidx` |
| Composite pin validation runs before createDialog | no dialog shown for invalid circuit |
| EditDiodeModelDialog has no Apply button | `applyButton.getParent() == null` after ctor |

### 05_03. Integration Scenarios  {#SP_DSP_05_03}

| Scenario | Preconditions | Steps | Expected |
|----------|---------------|-------|----------|
| Create and save subcircuit | circuit with external pins | Create Subcircuit → name + save checkbox + OK | model persisted; `CustomCompositeElm.lastModelName` set |
| Delete subcircuit | saved custom model | SubcircuitDialog → select → Delete → confirm | model removed from catalog + local storage |
| Slider share | two elements + Adjustable on one | SliderDialog on the other → Share → existing slider | `adj.sharedSlider` set; redundant slider deleted |
| Scope transistor | attach scope to BJT | open ScopePropertiesDialog | checkbox set shows Ib/Ic/Ie/Vbe/Vbc/Vce rather than voltage/current |
| Search execute | menu with "New Blank Circuit" | type "new" → Enter | command executed |

### 05_04. Edge Cases  {#SP_DSP_05_04}

| Case | Input | Expected |
|------|-------|----------|
| Unnamed transistor model edit | — | name stays null (bug) |
| >10 adjustable-eligible rows | synthetic | AIOOBE on `einfos[10]` |
| ScrollValuePopup on Choice row | — | NPE on `inf.value` |
| ScopePropertiesDialog titlebar X | open, change manual scale, click X | auto-applied (unexpected) |
| Composite dialog with unsaved `labelCheck` toggle | — | `showLabel` committed immediately, `saved` only on Enter |
| SearchDialog menu with early multi-char shortcut | — | rest of menu invisible |
| ControlsDialog re-created | hypothetical | old Scrollbars orphaned in `cirSim` |

## 06. Constants  {#SP_DSP_06}

Grouped per-dialog in §01_04. Additional:
- `ControlsDialog.getOptionPrefix() = "ControlsDialog"`.
- `SlidersDialog.getOptionPrefix() = "SlidersDialog"`.
- `SubcircuitDialog` is modal (`setGlassEnabled(true)`); no position persistence.
- `EditCompositeModelDialog.closeOnEnter = true`.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
