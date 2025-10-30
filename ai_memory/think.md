# Slider Dialog Refactoring

**Goal:** Move sliders from the main side panel to a non-modal dialog that appears on the right side of the window.

**Plan:**
1.  **Create `SlidersDialog.java`**: A new `DialogBox` subclass, configured as non-modal (`setModal(false)`). It will contain a `VerticalPanel` to hold the sliders.
2.  **Integrate into `CirSim.java`**:
    *   Remove the old `VerticalPanel` and `ScrollPanel` for sliders.
    *   Add an instance of `SlidersDialog`.
    *   Create helper methods: `addSliderToDialog`, `removeSliderFromDialog`, `clearSlidersDialog`, and `updateSlidersDialogPosition`.
3.  **Modify `Adjustable.java`**:
    *   Change `createSlider` to call `cirSim.addSliderToDialog`.
    *   Change `deleteSlider` to call `cirSim.removeSliderFromDialog`.
4.  **Manage Dialog Lifecycle**:
    *   In `CircuitLoader.java`, modify `resetCircuitState` to call a new method `adjustableManager.reset()` which will clear the dialog.
    *   Ensure the dialog is shown/hidden automatically based on whether there are any sliders.
    *   Position the dialog on window resize and on show.
5.  **Final Cleanup**: Replace all remaining calls to the old `setSlidersPanelHeight` with `setSlidersDialogHeight`.

**Status:** Completed.