# UI-DRAG-ELEMENT-MOVE-GEOM (archived)

## Summary
Fixed element move/drag so the whole symbol moves (body + posts), not just pin endpoints, after HARD MODE `ElmGeometry` refactor.

Key change: `ElmGeometry` now triggers `owner.setPoints()` after move/drag-style endpoint mutations (e.g. `translate`, `dragTo`, `movePoint`, `flip*`), ensuring element-specific drawing geometry is recomputed.

Regression fix: removed the auto-callback from `ElmGeometry.setEndpoints/setX2/setY2` because some elements (notably `TransformerElm`) call `setEndpoints()` from inside `setPoints()`, which created recursion and broke rendering.

## Key files
- src/main/java/com/lushprojects/circuitjs1/client/element/ElmGeometry.java
- src/main/java/com/lushprojects/circuitjs1/client/element/CircuitElm.java
- src/main/java/com/lushprojects/circuitjs1/client/element/TransformerElm.java

## Verification
- Build: `mvn -DskipTests package` (includes GWT compile)
- DevMode validation via JS API: loaded standard `transformer.txt`, moved transformer + voltage elements and confirmed x/y and x2/y2 deltas match.
