# Module Analysis: domain-core / element-categories / graphic-and-magnetics

> **Path:** src/main/java/com/lushprojects/circuitjs1/client/element/ (7 files, two clusters)
> **Layer:** 2 (SCC-A)
> **Analyzed:** 2026-04-18
> **Files:** 7 source files, 0 test files

## Purpose

Two thematically distinct but small clusters packaged together for
onboard convenience:

1. **Graphic overlay cluster (3)** — `BoxElm`, `TextElm`, `LineElm`
   extend `GraphicElm` and have **no circuit participation**: zero
   posts, zero voltage sources, zero internal nodes, no `stamp()`, no
   `doStep()`. They are pure canvas decorations (dashed rectangle,
   multi-line text label, arbitrary diagonal line) that float on top of
   the schematic and are persisted with the rest of the document.
2. **Coupled-magnetic + transmission cluster (4)** — `TransformerElm`,
   `TappedTransformerElm`, `CustomTransformerElm`, `TransLineElm`
   are multi-port electromagnetic elements. The three transformers stamp
   coupled inductors using inverted mutual-inductance matrices (Norton
   companion, trapezoidal / backward-Euler, like `Inductor` but extended
   to N coils). `TransLineElm` is a lossless 2-port-pair transmission
   line implemented as two **voltage sources driven by ring-buffered
   past voltages** plus characteristic-impedance resistors.

Neither cluster uses the `Inductor` helper directly — the transformers
predate it and implement their own companion-model inversion (TransformerElm
captures `a1..a4`; TappedTransformerElm `a[9]`; CustomTransformerElm
builds and inverts a full `coilCount × coilCount` inductance matrix via
`CircuitMath.invertMatrix`). They do share the `Inductor.FLAG_BACK_EULER`
constant for the trapezoidal/back-Euler selector flag.

## Graphic sub-section

### Per-element catalog

| Element | Extends | Posts | V-src | Int.nodes | Dump-type | Parameters | File:lines |
|---|---|---|---|---|---|---|---|
| BoxElm | GraphicElm | 0 | 0 | 0 | `'b'` (98) | geometry only (x, y, x2, y2) | BoxElm.java:29-136 |
| TextElm | GraphicElm | 0 | 0 | 0 | `'x'` (120) | `text` (String, \n-split), `size` (pt), flags `FLAG_BAR=2`, `FLAG_ESCAPE=4` | TextElm.java:34-216 |
| LineElm | GraphicElm | 0 | 0 | 0 | `423` | geometry only (x, y, x2, y2) | LineElm.java:28-102 |

All three report `getPostCount() == 0` (inherited from GraphicElm:36-38);
their `draw()` is invoked by the renderer but `stamp()`, `doStep()`,
`startIteration()`, `stepFinished()`, `setNode`, `setVoltageSource` are
never called meaningfully because the simulator only schedules elements
with posts.

### GraphicElm interaction (no stamping, no node participation)

- `GraphicElm` itself is a pass-through subclass of `CircuitElm` —
  it forwards the two `CircuitElm` constructor signatures, overrides
  `getPostCount() → 0`, and forces `getJsonStartPoint()` /
  `getJsonEndPoint()` to always emit (x, y) / (x2, y2) because there
  are no pins from which coordinates could otherwise be derived
  (`GraphicElm.java:36-54`).
- Because `getPostCount()==0`, `allocNodes()` inside `CircuitElm`
  produces a zero-length `nodeStates[]`, so there is no
  `getNodeVoltage(n)` access path inside these elements' `draw()`
  methods — they rely solely on their own geometry (`getX()`, `getY()`,
  `getX2()`, `getY2()`) plus the `selectColor()` / `neutralColor()` /
  `elementColor()` base helpers.
- The simulator's analysis phase skips them: `getConnection()` /
  `hasGroundConnection()` defaults are irrelevant because their posts
  are never enumerated.
- Persistence uses the **standard** `super.dump()` = `type x1 y1 x2 y2
  flags` (via `CircuitElm.java:419`) — no element-specific tokens for
  BoxElm/LineElm, and `size text` appended for TextElm
  (`TextElm.java:87-91`).

### Text dynamic content (static, NOT live values)

TextElm renders **static user-authored text only** — it does **not**
reference any live circuit values. Evidence:

- Constructor 1 (`TextElm.java:44`) sets `text = "hello"` literally.
- Constructor 2 (`TextElm.java:54-63`) reads the token stream;
  `FLAG_ESCAPE=4` selects between old-style (space-joined tokens,
  `%2b`→`+` workaround) and new-style escape via
  `CustomLogicModel.unescape(text)` — no placeholder substitution
  happens.
- `split()` (`TextElm.java:67-85`) only interprets `\n` as a line
  break — no `${voltage}` / `${current}` / `{V0}` style templating
  or pattern substitution anywhere.
- `draw()` (`TextElm.java:101-136`) feeds each line through
  `Locale.LS(s)` (translation only) then `g.drawString(s, ...)`.
  It does not query `getNodeVoltage()`, `getCurrent()`, or any
  simulator state.
- JSON export exposes `text`, `size`, `draw_bar` under properties
  (`TextElm.java:209-215`); `applyJsonProperties` imports the same.

If live-value labels are required, that role is served by
`DataRecorderElm`, `VoltMeterElm`, `OutputElm`, `AmmeterElm`, etc.,
not by TextElm.

Display-only flags:
- `FLAG_BAR=2` draws a horizontal overline above each line
  (TextElm.java:125-128) — used for negated signal names.
- `FLAG_ESCAPE=4` is a dump-format marker only; it is force-set by
  `dump()` on every write (`:88`).

Shortcut: `'t'` (`TextElm.java:175-177`). BoxElm and LineElm return
`0` — no keyboard shortcut.

Additional quirks:
- **TextElm.draw() writes back into geometry**: the final lines
  `geom().setX2(...)` / `geom().setY2(...)` (`:133-134`) sync the
  bounding box into the geometry's endpoints so selection/move
  tracks the rendered extent. Unusual — most elements treat
  geometry as input to `draw`, not output.
- **BoxElm dashed outline**: `g.setLineDash(16, 6)` before drawing
  the rectangle, restored to solid after (`BoxElm.java:67, 76`).
- **BoxElm minimum size**: `creationFailed()` (`BoxElm.java:56-58`)
  rejects boxes smaller than 32 px in either axis; LineElm rejects
  lines shorter than 16 px (`LineElm.java:55-57`).
- **BoxElm hit-test is edge-only**: `getMouseDistance`
  (`BoxElm.java:94-113`) tests only the four edges, not the
  interior — click-through into any element drawn beneath the box.

## Magnetics sub-section

### Per-element catalog

| Element | Extends | Posts | V-src | Int.nodes | Inductor helper? | Dump-type | File:lines |
|---|---|---|---|---|---|---|---|
| TransformerElm | CircuitElm | 4 | 0 | 2 (priInt, secInt) | **No** — private a1..a4 inversion | `'T'` (84) | TransformerElm.java:31-789 |
| TappedTransformerElm | CircuitElm | 5 | 0 | 3 (priInt, secInt1, secInt2) | **No** — private a[9] inversion | `169` | TappedTransformerElm.java:30-801 |
| CustomTransformerElm | CircuitElm | `nodeCount` (variable, description-driven) | 0 | `coilCount` (one per winding) | **No** — uses `CircuitMath.invertMatrix` on `coilCount×coilCount` | `406` | CustomTransformerElm.java:34-1210 |
| TransLineElm | CircuitElm | 4 | **2** | 2 | N/A (delay-line, not magnetic) | `171` | TransLineElm.java:32-409 |

Legend: posts = `getPostCount()`; v-src = `getVoltageSourceCount()`;
int.nodes = `getInternalNodeCount()`.

Key notes:
- **None of the transformers use the `Inductor` helper class**
  documented in `element-base.md` — only InductorElm does. The three
  transformer files all borrow `Inductor.FLAG_BACK_EULER` (= 2) as
  the flag bit selecting backward-Euler vs trapezoidal but otherwise
  implement the companion model inline (TransformerElm:272-274,
  TappedTransformerElm:577-579, CustomTransformerElm:586-588).
- **Internal nodes** in all three transformers hold the post-resistor
  end of each winding (primary-side series resistance stamped between
  external node and internal node, then the pure inductor between
  internal node and the opposite external node). See
  `TransformerElm.java:481-494` for the template.
- **Zero voltage sources** in all three transformers — coupled inductors
  are Norton-style (conductances + voltage-controlled current sources),
  not Thévenin.

### Mutual inductance stamping

All three transformers implement the same core algorithm; CustomTransformerElm
is the N-coil generalization.

**TransformerElm (2 coils)** — `stamp()` at `TransformerElm.java:451-517`:

```
L1 = inductance
L2 = inductance * ratio²
M  = couplingCoef * sqrt(L1 * L2)
deti = 1 / (L1*L2 - M²)           // inverse determinant of 2×2 [[L1,M],[M,L2]]
ts = trap? Δt/2 : Δt
a1 =  L2 * deti * ts               // [row 0] coefficient on V_pri
a2 = -M  * deti * ts               //         coefficient on V_sec
a3 = -M  * deti * ts               // [row 1] coefficient on V_pri
a4 =  L1 * deti * ts               //         coefficient on V_sec
```

These are then stamped as:
- `stampConductance(priInt, node2, a1)` — self-conductance primary
- `stampVCCurrentSource(priInt, node2, secInt, node3, a2)` — cross-coupling
- `stampVCCurrentSource(secInt, node3, priInt, node2, a3)` — symmetric
- `stampConductance(secInt, node3, a4)` — self-conductance secondary

Plus `stampRightSide` on all 6 nodes (4 external + 2 internal) and
`stampResistor(node0, priInt, primaryResistance)` / likewise secondary
(or a `stampConductance(... , 1e8)` when R == 0 to keep the node referenced).

`startIteration()` (`:519-529`) updates the per-step current source value:
- trap: `curSourceValue_k = current_k + a_{k,0} * Vd1 + a_{k,1} * Vd2`
- back-Euler: `curSourceValue_k = current_k`

`doStep()` (`:533-537`) stamps those current sources.

`calculateCurrent()` (`:539-544`): `current_k = a_{k,0} Vd1 + a_{k,1} Vd2 + curSourceValue_k`.

`reset()` (`:432-447`) zeros all 6 nodes (including two internal) and
both currents. `setNodeVoltageDirect` avoids triggering
`calculateCurrent` mid-analysis.

**TappedTransformerElm (3 coils: primary + 2 halves of tapped secondary)** —
`stamp()` at `:497-575` uses a 3×3 coupling matrix stored in `a[9]`
(row-major). Key subtlety: the two halves of the secondary are themselves
mutually coupled with `m2 = couplingCoef * l2` (winding 2 and 3 share
the same core and effectively the same magnetic flux, so coupling ≈
self-inductance of either half — see comment at
`TappedTransformerElm.java:545-548`). The pre-inverted matrix entries are:

```
l2' = l2 + m2                    // "effective" secondary self-coupling
det = l1 * l2' - 2 * m1²         // determinant of 3×3 after symmetric reduction
a[0] = l2'
a[1] = a[2] = a[3] = a[6] = -m1
a[4] = a[8] = (l1*l2 - m1²) / (l2 - m2)
a[5] = a[7] = (m1² - l1*m2) / (l2 - m2)
// then divide all by det and multiply by ts
```

`startIteration()` / `calculateCurrent()` loop over 3×3 explicitly
(`:581-592, :602-614`). Tap-wire current is derived: `current[3] =
current[1] - current[2]` (`:613`).

**CustomTransformerElm (N coils)** — `stamp()` at
`CustomTransformerElm.java:882-954` builds the full `coilCount × coilCount`
inductance matrix and calls `CircuitMath.invertMatrix` (line 918):

```
xformMatrix[i][i] = windings[i].inductance        // diagonal = self-L
xformMatrix[i][j] = xformMatrix[j][i]
                  = couplingCoef * sqrt(L_i * L_j) * p_i * p_j   // polarity-signed
CircuitMath.invertMatrix(xformMatrix, coilCount)
// multiply by ts during the stamp loop below
```

Windings are declared in a DSL string `description` parsed by
`parseDescription(String)` (`:436-541`): comma separates coils,
`+` makes two adjacent coils share a node (tap), `:` separates
primary from secondary side. Negative numeric turns = reversed
polarity. Example: `"1,1:1+1"` → primary with two independent coils,
secondary with two tap-connected coils. Each winding's self-inductance
is `turns² * baseInductance` (`:492`).

Rendering chooses coil side (primary vs secondary) based on where
the coil falls in the winding list relative to `:`; the description
parser sets `primaryCoils` to the index of the first secondary coil
(`:503-504`).

**Shared convergence guard**: all three transformers depend on
`couplingCoef ∈ (0,1)` strictly — edit setters reject `≤0` or `≥1`
and JSON import clamps out-of-range values to `0.99`
(TransformerElm:602, TappedTransformerElm:678,
CustomTransformerElm:151-153 and 1151-1153). At `couplingCoef == 1`
the matrix is singular (`det == 0`).

### Transmission line delay mechanism (ring buffer of past voltages)

`TransLineElm` is NOT electromagnetic — it is a lossless two-conductor
delay line modelled using **Bergeron's method**: each end of the line
is driven by a voltage source whose value equals the past voltage
seen at the **opposite** end `τ` seconds ago.

**State:**
- `voltageL[lenSteps]` — ring buffer of left-to-right incident samples
- `voltageR[lenSteps]` — ring buffer of right-to-left incident samples
- `ptr` — current write index
- `lenSteps = (int)(delay / maxTimeStep)`, clamped to `MAX_DELAY_STEPS = 100_000`
  (TransLineElm.java:38, 104-108). Over-clamp sets
  `simulator().converged = false` and truncates `delay`.

**Topology (`getPostCount()=4`, `getVoltageSourceCount()=2`,
`getInternalNodeCount()=2`):**
```
post 2 (sig_in)  --- Rz --- int0 --- [Vsrc1 = -voltageR[t-τ]] --- post 0 (gnd_in)
post 3 (sig_out) --- Rz --- int1 --- [Vsrc2 = -voltageL[t-τ]] --- post 1 (gnd_out)
```
where `Rz = imped` (characteristic impedance, default 75 Ω). Each side
presents a matched-impedance Thévenin source: reflections are absorbed
when terminated in `imped`. Posts 0/1 are the "ground"/return conductors
which, when left unconnected, auto-connect to ground (post ordering
chosen on purpose per the comment at `:142-145`).

**stamp()** (`:218-224`):
```
stampVoltageSource(int0, post0, voltSource1)    // forward source
stampVoltageSource(int1, post1, voltSource2)    // backward source
stampResistor(post2, int0, imped)               // matching resistor fwd
stampResistor(post3, int1, imped)               // matching resistor back
```

**startIteration()** (`:226-245`) samples each side's incident voltage
into the ring buffer at `ptr`:
```
voltageL[ptr] = (v2 - v0) + (v2 - v4)   // (port-2 v diff) + (int0 v diff)
voltageR[ptr] = (v3 - v1) + (v3 - v5)
```
This captures the sum of the external terminal voltage drop and the
internal matching-resistor drop — i.e. **2× the forward-travelling
wave voltage**.

**doStep()** (`:247-259`) drives each voltage source with the
**opposite-end sample from the previous τ**:
```
nextPtr = (ptr + 1) % lenSteps
updateVoltageSource(int0, post0, voltSource1, -voltageR[nextPtr])
updateVoltageSource(int1, post1, voltSource2, -voltageL[nextPtr])
```
`nextPtr` (not `ptr - lenSteps`) works because the ring wraps — the
oldest slot equals the slot immediately **after** the write pointer.

**stepFinished()** (`:261-266`) advances `ptr` exactly once per
`simulator().timeStepCount` tick. The `lastStepCount` guard prevents
double-advance if `stepFinished` is called multiple times per step
(e.g. re-convergence).

**Convergence guard** (`:254-258`): if `|V_gnd| > 1e-5` at either end,
the solver is marked non-converged — historically a hard stop,
softened to a convergence hint per comment. The ground-return
conductor voltages should be ≈0.

**Topology reports:**
- `getConnection(n1, n2) == false` always (`:281-290`) — TransLineElm
  is never collapsed into a wire; each of its 4 posts is an independent
  connection point.
- `hasGroundConnection(n) == false` — no post is hard-grounded, even
  though the naming suggests so.

**Length display**: `getInfo` computes `length = 0.65 * c * delay`
(`:295-296`) — hard-coded RG-58 velocity factor, for UI only.

## Validation Rules

- **TransformerElm edit-value guards** (`:597-622`):
  - `inductance > 0`, `ratio > 0` (stored as `1/ei.value`),
    `0 < couplingCoef < 1`, `primaryResistance >= 0`,
    `secondaryResistance >= 0`. Zero resistance stamps a `1e8`
    conductance stub to keep the node referenced (`:487-489`).
- **TappedTransformerElm** (`:673-692`): same pattern as above, with
  three winding resistances.
- **CustomTransformerElm**:
  - `parseDescription` returns `false` on unparseable string,
    `n == 0` token, or multiple `:` separators (`:484-523`).
    `applyJsonProperties` falls back to the previous valid description
    if the imported one is rejected (`:1169-1173`).
  - `couplingCoef` clamped to `0.99` if `≤0` or `≥1`
    (`:201-202, :1151-1153`).
  - `windingResistance >= 0` enforced (`:238-239, :1156-1157`).
- **TransLineElm**:
  - `delay > 0` and `imped > 0` required in `setEditValue`
    (`:309-315`); either triggers a full `reset()` which reallocates
    buffers.
  - `MAX_DELAY_STEPS = 100_000` cap (`:38, 104-108`) — beyond this,
    delay is truncated and the solver is nudged with `converged = false`.
  - `reset()` guards against `maxTimeStep == 0` (`:96-97`) to avoid
    division by zero during construction before the simulator is fully
    initialized.
- **BoxElm/LineElm** zero-size rejection via `creationFailed()`
  (BoxElm.java:56-58 = min 32 px; LineElm.java:55-57 = min 16 px
  Euclidean).
- **TextElm** has no numeric validation — any `size` from the
  EditInfo dialog is accepted (`:159-160`).

## State Transitions

### Transformer step (applies to all three magnetic elements)

```
  analyzeCircuit  ──────► stamp()
                          • build L matrix, invert (per-element specific)
                          • scale by ts = trap? Δt/2 : Δt
                          • stampConductance for each diagonal entry
                          • stampVCCurrentSource for each off-diagonal
                          • stampResistor for each winding-R + stampRightSide
                          • one internal node per coil to isolate the inductor
                            from its series resistor

  every Δt ──► startIteration()
                • sample Vd_k = V(int_k) - V(external_end_k)
                • curSourceValue_k = current_k [+ Σ a[k,j] * Vd_j if trapezoidal]
               ──► doStep()
                • stampCurrentSource(int_k, end_k, curSourceValue_k) for each coil
               (Newton may loop back to doStep)
               ──► solve
               ──► setNodeVoltage → calculateCurrent()
                • current_k = curSourceValue_k + Σ a[k,j] * Vd_j
               ──► stepFinished() (default no-op — currents already settled)
```

### TransLineElm step

```
  analyzeCircuit  ──► stamp()
                      • 2 voltage sources (one per direction)
                      • 2 matching resistors (imped)

  every Δt ──► startIteration()
                • voltageL[ptr] ← (v2-v0) + (v2-v4)   // forward wave
                • voltageR[ptr] ← (v3-v1) + (v3-v5)   // backward wave
               ──► doStep()
                • Vsrc1 ← -voltageR[(ptr+1) % lenSteps]   // oldest fwd-dir sample
                • Vsrc2 ← -voltageL[(ptr+1) % lenSteps]
               ──► stepFinished()
                • ptr ← (ptr+1) % lenSteps (once per sim step)
```

## Integration Points

### Depends on

- **element-base**: `CircuitElm` (all 7 files), `GraphicElm` (BoxElm,
  TextElm, LineElm), `Inductor.FLAG_BACK_EULER` (only the constant is
  imported by the three transformers — TransformerElm:273,
  TappedTransformerElm:578, CustomTransformerElm:587).
- **`CircuitSimulator` methods used by the magnetics cluster**:
  - TransformerElm/Tapped/Custom: `stampResistor`, `stampConductance`,
    `stampVCCurrentSource`, `stampCurrentSource`, `stampRightSide`,
    `timeStep`.
  - TransLineElm: `stampVoltageSource`, `stampResistor`,
    `updateVoltageSource`, `maxTimeStep`, `timeStepCount`, `converged`.
- **`CircuitMath.invertMatrix`** — only CustomTransformerElm
  (`:918`). The fixed-size variants hard-code the inversion.
- **`CustomLogicModel.escape` / `unescape`** — TextElm (escaping
  user text with embedded spaces/newlines) and CustomTransformerElm
  (escaping the `description` string).
- **`io.json.UnitParser.parse`** — TransformerElm, TappedTransformerElm,
  CustomTransformerElm for H-prefixed inductance strings in JSON.
- **Root utilities**: `Point`, `Rectangle`, `Graphics`, `Color`,
  `Font`, `StringTokenizer`, `CircuitDocument`, `Checkbox`,
  `util.Locale`.

### Used by

- **`CircuitElementFactory`** via dump-type dispatch: `'b'` (BoxElm),
  `'x'` (TextElm), `423` (LineElm), `'T'` (TransformerElm), `169`
  (TappedTransformerElm), `406` (CustomTransformerElm), `171`
  (TransLineElm).
- **`CircuitRenderer`** via `draw()`; `CircuitEditor` via
  `getMouseDistance`, `drag`, `movePoint`.
- **Transformer shortcut** `'T'` registered via
  `TransformerElm.getShortcut()` (`:655-657`); BoxElm/LineElm both
  return `0` (no shortcut); TextElm returns `'t'` (`:175-177`).

### External deps

- GWT: `com.google.gwt.user.client.Window` (CustomTransformerElm only,
  for error dialogs — `:24`).
- JDK: `java.util.{Vector, Map, LinkedHashMap, ArrayList, List}`.

## Issues / Questions

1. **Transformers bypass the `Inductor` helper**. Every claim that
   "TransformerElm uses 2 Inductor helpers + mutual inductance" is
   **factually wrong** for this codebase. The three transformer
   classes inline their own companion-model inversion. Only the
   `Inductor.FLAG_BACK_EULER` bit is shared. This is a refactoring
   opportunity — `Inductor` could be generalized to expose a
   `stampCoupled(matrix, ts)` API and eliminate ~200 LOC of duplicated
   stamp/startIteration/calculateCurrent across the three files.
2. **Near-identical stamp/startIteration/calculateCurrent loops** in
   TransformerElm, TappedTransformerElm, CustomTransformerElm (with
   only matrix-size differences). CustomTransformerElm's N-coil
   implementation is strictly more general and could subsume the
   other two (at a small performance cost for the hot 2×2 case).
3. **`couplingCoef == 1` is a singularity**. All three transformers
   clamp via edit-time bounds but none refuse it at runtime — if an
   older dump file happens to contain `1.0` the `invertMatrix`/
   `1/(l1*l2-m*m)` computation produces `Infinity` and propagates
   into the MNA matrix. TransformerElm has no defensive clamp in
   `stamp()` itself.
4. **TransLineElm marks `converged=false` from `reset()` on buffer
   clamp** (`:107`) — `reset()` is also called on circuit load, so
   loading a saved file with excessive delay produces a spurious
   convergence-fail notification on the very first step. Cosmetic
   but confusing.
5. **TransLineElm hard-codes the RG-58 velocity factor** (0.65 in
   `getInfo`) for the "length" display — it is not a property of the
   simulation, only a UI hint. Users modelling other cable types see
   an incorrect length label.
6. **CustomTransformerElm JSON state omits tap overrides per step** —
   `getJsonState()` exports `coilCurrent{i}` only (`:1194-1200`);
   tap offsets go through `getJsonProperties` (`:1124-1138`). Split
   is correct by convention but the two read paths interleave
   awkwardly during `applyJsonProperties` (which both rebuilds
   from `description` and restores tap overrides).
7. **Dump type `'x'` for TextElm collides with the character `'x'`**
   if ever used as a token elsewhere in the dump format. Same
   concern for `'T'` (TransformerElm), `'b'` (BoxElm). The single-
   char/numeric split in the dispatch table prevents actual
   collisions but the choice is fragile. (Same issue family as
   `element-base.md` Issue #9 — dump-type registry.)
8. **TextElm cannot display live circuit values** — if a user
   expects something like a dynamic voltage label, there is no
   substitution mechanism. The comment at `TextElm.java:169` suggests
   an abandoned `FLAG_CENTER` flag, implying the design was never
   fully fleshed out in this direction.
9. **BoxElm/LineElm have no `creationFailed` message** — a too-small
   drag silently deletes the element without user feedback.
10. **TransLineElm `voltSource1` and `voltSource2` not clamped in
    `setVoltageSource(n, v)`** (`:204-209`) — if `n > 1` is ever
    passed, the `else` branch silently overwrites `voltSource2`.
    Defensive `assert n < 2` would catch this.
11. **CustomTransformerElm has a JSNI `console()` helper** (`:40-43`)
    gated by a `DEBUG_RESIZE_HANDLES` flag (`:38`) — dev-only
    instrumentation left in production; should be removed or
    replaced with the standard `CirSim.console` logger.
12. **TextElm's `draw()` mutates geometry** (`:133-134`) — reading
    position during a draw to derive bounding box is unusual and
    makes draw()-free geometry queries inconsistent. The bbox is
    re-derived on every paint, not at `setPoints()`.
13. **TappedTransformerElm.reset zeros 8 nodes** (indices 0..7 at
    `:478-485`) even though `getPostCount()+getInternalNodeCount()
    == 5+3 == 8` — correct by accident; any future change to either
    count will silently de-sync.
14. **TransformerElm.reset zeros 6 nodes** (indices 0..5 at
    `:439-444`) while `getPostCount()+getInternalNodeCount() == 4+2
    == 6` — same brittleness as #13. A `for (i = 0; i <
    getPostCount()+getInternalNodeCount(); i++)` loop would remove
    the magic number.

## Concept boundaries — recommendation

**Split into two concepts** — the clusters share the package but have
orthogonal responsibilities, contract surfaces, and audience:

1. **`graphic-overlay-elements`** — covers `BoxElm`, `TextElm`,
   `LineElm` + their shared `GraphicElm` base. Focus: drawing-only
   decoration, zero electrical participation, static content,
   (x, y)-based persistence. Small (≈300 LOC total), self-contained.

2. **`magnetics-and-transmission`** — covers `TransformerElm`,
   `TappedTransformerElm`, `CustomTransformerElm`, `TransLineElm`.
   Focus: multi-port stamping, coupled-inductor matrix inversion,
   internal winding-resistance nodes, Bergeron delay-line method,
   companion-model trapezoidal/back-Euler selection. Large (≈2500
   LOC total), cohesive.

**Rationale for the split**:
- The graphic cluster has nothing in common with the magnetics cluster
  beyond sharing the `element/` package — different authors, different
  test concerns, different review expertise.
- Each concept internally shares the pattern it documents (GraphicElm
  overlay vs. MNA coupled-inductor Norton stamp).
- Downstream feature work (e.g. "add live-value templating to TextElm"
  vs "add 4-winding transformer support") is always scoped to one
  concept, not both.

**Rationale NOT to split further** (e.g., four magnetic concepts or
three graphic concepts):
- The three transformers share the exact algorithmic pattern and
  differ only in winding count; documenting them separately forces
  triplicate coverage of the companion-model derivation.
- TransLineElm belongs with the transformers because it is the only
  other multi-port "propagation" element in the package and readers
  looking for "how do I model something with delay" benefit from
  finding it adjacent to the magnetically-coupled case.
- The three graphic elements are trivially small and cohesive — a
  per-element concept would be noise.
