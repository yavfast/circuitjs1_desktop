# Project Knowledge Base

*   The UI for adjustable element sliders has been refactored. Sliders are no longer displayed on the main side panel but in a dedicated, non-modal `SlidersDialog` to improve layout and user experience.
*   The `SlidersDialog` appears automatically on the right side of the screen when a circuit with adjustable elements is loaded and hides when they are removed or a new circuit is loaded.
*   The `AdjustableManager` now has a `reset()` method to clear sliders, which is called during the circuit loading process.