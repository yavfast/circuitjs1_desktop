---
skill: element-authoring
domain: elements
topics: [circuit-elm, chip-elm, composite-elm, dump, json, factory, recipe]
source: onboard
updated: 2026-04-18
---

# Authoring a New Circuit Element

## Context

The project has ~135 concrete `*Elm` classes. Adding one is not hard,
but the contract is spread across simulation, topology, drawing,
editing, and **two** persistence formats. This skill is the end-to-end
recipe, with the rules it must obey.

## Key concepts

**Pick a base class** (`domain-core__element-base.md` §"Class hierarchy",
`element/CircuitElm.java:50`):

| Base | When to use |
|---|---|
| `CircuitElm` | Plain 2-terminal + arbitrary custom base |
| `ChipElm` (L32) | Multi-pin IC (gates, FFs, counters, MUX) |
| `CompositeElm` (L31) | Subcircuit of existing elements |
| `SwitchElm` (L33) | Switch-like (closed/open, multi-position) |
| `GraphicElm` (L26) | Zero-electrical annotation (text, box, line) |

**Required overrides** (defaults at `CircuitElm.java` lines noted):

| Override | Default | When mandatory |
|---|---:|---|
| `getDumpType()` | L320 throws `IllegalStateException` | Always (must be unique, RULE_NAMING_009) |
| `getDumpClass()` | L330 returns `getClass()` | When dump type changed |
| `getPostCount()` | L893 = 2 | If ≠ 2 |
| `getInternalNodeCount()` | L863 = 0 | If element needs internal nodes |
| `getVoltageSourceCount()` | L857 = 0 | If element drives voltage |
| `stamp()` | L578 no-op | Linear part stamping |
| `doStep()` | L582 no-op | Nonlinear or dynamic update |
| `nonLinear()` | L889 = false | Return true → forces Newton iteration |
| `setVoltageSource(n, vs)` | L876 single slot | Multi-source elements |
| `draw(Graphics)` | L558 no-op | Every visible element |
| `getEditInfo(n)` / `setEditValue(n, ei)` | L1200/L1204 | If user-editable parameters (RULE_STRUCT_004) |
| `getInfo(String[])` | L1128 | For info panel |
| `dump()` / undump-ctor | L419 (`type x1 y1 x2 y2 flags`) | Extend to persist state |
| `getJsonTypeName` / `getJsonProperties` / `getJsonState` / `applyJsonProperties` / `applyJsonState` | L1534+ | Full JSON round-trip |

**Ordering invariant (simulator call sequence).** `setNode(p, n)` →
`setVoltageSource(n, vs)` → `stamp()` → per-step `startIteration()` →
Newton `doStep()` → `setNodeVoltage(n, c)` → `setCurrent(vn, c)` →
`stepFinished()`. Do **not** depend on element construction order.

**Element ID must come from `CircuitDocument`.** Use `generateElementId()`
via `getIdPrefix()` (override for meaningful prefix: `R`, `C`, `Q`, ...).
Dump-type code and ID prefix are independent; dump type is a single char
or short numeric, ID prefix is a readable token.

**Constructor signature.** Every concrete element exposes **two**
constructors:
1. `(CircuitDocument doc, int x, int y)` — editor placement.
2. `(CircuitDocument doc, int xa, int ya, int xb, int yb, int f,
   StringTokenizer st)` — text-format reconstruction, called by
   `CircuitElmCreator.createCe(...)`.

**Registration in both formats (RULE_ARCH_004, RULE_ARCH_005).**
1. **Text format:** `CircuitElmCreator` (at the root package) dispatches
   on `getDumpType()` to the reconstruction constructor. Add a case
   there.
2. **JSON format:** `io/json/CircuitElementFactory.init()` has an
   explicit `register("JsonName", YourElm::new)` call for every type
   (~150 entries, `CircuitElementFactory.java:65-288`). `init()` is
   lazy, idempotent. Add your entry — and make sure `getJsonTypeName()`
   returns the exact same key.

**Geometry goes through `ElmGeometry`** (RULE_STRUCT_002). Never touch
`x1/y1/x2/y2` as fields — use `geom().setEndpoints(...)`,
`geom().translate(...)`, etc. `ElmGeometry.java:6-10` has the warning.

## Usage in this project

- Minimal 2-terminal passive: copy `ResistorElm` as the template.
- Multi-pin IC: copy a small gate from `element/` (e.g. a simple logic
  gate) — note `setupPins()` is abstract, `execute()` reads pin inputs
  and writes pin outputs; `ChipElm.stamp()` and `doStep()` already
  handle the voltage-source bookkeeping.
- Subcircuit-style element: see `CompositeElm` (L31) — net-list stored
  as child dumps, BFS-memoized `getConnection`. Use `CustomCompositeElm`
  as the concrete example; dump type `410`.
- Shared model parameters (reusable diode/transistor specs): use the
  `*Model` pattern (RULE_STRUCT_005, RULE_ARCH_007). Do not inline
  model fields on the element if they are reusable.
- **Drop an example circuit** under `src/main/java/.../public/circuits/`
  when adding a new element (RULE_TEST_004).

## Pitfalls

1. **Forgetting `getDumpType()` override** compiles silently but throws
   `IllegalStateException` at first dump (L320-327). The project uses
   this pattern because GWT's abstract-method check misbehaves for
   `OTAElm` — the checker is at runtime, not compile.
2. **Dump-type collisions are not detected centrally** (issue #9 in
   element-base analysis). Manually grep
   `.dev_flow/onboard/analysis/` or existing `*Elm.getDumpType()`
   returns before choosing.
3. **Multi-source elements must override `setVoltageSource` and
   `setCurrent`** — default stores into single `voltSource` field and
   routes current by sole-source assumption (`CircuitElm.java:876, 563`).
   Forgetting is a silent wrong-current bug.
4. **Multi-post `getCurrentIntoNode(int n)`** defaults to ±current for
   2-terminal only (L1330). Override for chip-like elements.
5. **JSON alias drift.** Historical renames (`MosfetN`/`NMosfet`) are
   handled by registering multiple factory keys mapped to the same
   constructor. If you rename an element class, add an alias — do not
   remove the old key.
6. **No `java.io.*`, no reflection** (RULE_STYLE_002). Use
   `StringTokenizer` for parsing, GWT JSON for JSON.
7. **User-visible strings via `Locale.LS`** (RULE_STYLE_003) for info
   panel / edit dialog labels; numbers via `BaseCircuitElm.getUnitText`
   / `getVoltageText` / etc. (RULE_STYLE_004).
8. **Do not `new *Dialog` directly** (RULE_ARCH_003). Use
   `Editable.getEditInfo(n)` / `setEditValue(n, ei)` contract.

## References

- `.dev_flow/onboard/analysis/domain-core__element-base.md` (full
  override-point table, §"Override points", §"State Transitions")
- `src/main/java/com/lushprojects/circuitjs1/client/element/CircuitElm.java`
  L320 (dump-type), L558 (draw), L578 (stamp), L582 (doStep), L857
  (VS count), L889 (nonLinear), L1200 (getEditInfo), L1534+ (JSON)
- `src/main/java/com/lushprojects/circuitjs1/client/element/ChipElm.java`
  L32, L306, L335
- `src/main/java/com/lushprojects/circuitjs1/client/io/json/CircuitElementFactory.java`
  L65, L314
- Rules: RULE_NAMING_002, RULE_NAMING_009, RULE_NAMING_010,
  RULE_STRUCT_001, RULE_STRUCT_002, RULE_STRUCT_004, RULE_ARCH_004,
  RULE_ARCH_005, RULE_ARCH_007, RULE_ERR_001, RULE_ERR_002,
  RULE_TEST_004
- Sibling skills: `flag-bits-and-dump-types.md`, `mna-stamping.md`,
  `io/text-format.md`, `io/json-format.md`
