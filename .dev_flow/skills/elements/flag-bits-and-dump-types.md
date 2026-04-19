---
skill: flag-bits-and-dump-types
domain: elements
topics: [flags, dump-type, flag-small, flip, back-euler, iec]
source: onboard
updated: 2026-04-18
---

# Flag Bits and Dump-Type Codes

## Context

Every `CircuitElm` carries a `public int flags` (CircuitElm.java:127)
that is dumped as the 6th token on every legacy text-format line and
mirrored by `_flags` in JSON. Each element class reserves its own bit
positions as `FLAG_*` constants. There is **no central registry** of
either flag bits or dump-type codes — collisions and bit-overlap within
a class are a real risk.

## Key concepts

**Flag constants** (UPPER_SNAKE_CASE, RULE_NAMING_006):

| Constant | Value | Defined in | Meaning |
|---|---:|---|---|
| `FLAG_SMALL` | 1 | `ChipElm.java` | Compact pin pitch |
| `FLAG_SMALL` | 2 | `CustomCompositeElm.java` | (Different bit; name collision across classes) |
| `FLAG_FLIP_X` | 1<<10 (1024) | `ChipElm` | Mirror along X |
| `FLAG_FLIP_Y` | 1<<11 (2048) | `ChipElm` | Mirror along Y |
| `FLAG_FLIP_XY` | 1<<12 (4096) | `ChipElm` | Diagonal flip |
| `FLAG_CUSTOM_VOLTAGE` | 1<<13 (8192) | `ChipElm` | Non-default logic-high rail |
| `FLAG_BACK_EULER` | 2 | `Inductor.java` (helper) | Backward-Euler integration (absence = trapezoidal) |
| `FLAG_IEC` | 2 | `SwitchElm.java` | Use IEC-style switch symbol |
| `FLAG_LABEL` | 4 | `SwitchElm.java` | Has a label for linked switches |
| `FLAG_ESCAPE` | 1 | `CompositeElm.java` | Use `CustomLogicModel.escape` when dumping children |

Each element class redeclares its own `FLAG_*` bits relative to its
meaning; bit positions **do not coordinate across the hierarchy**.
`CompositeElm.FLAG_ESCAPE = 1` and `ChipElm.FLAG_SMALL = 1` occupy the
same bit but are on separate inheritance branches, so there is no
conflict in practice — **until** you author a composite-chip hybrid.

**Default flags** come from `CircuitElm.getDefaultFlags()`
(`CircuitElm.java:334`, default 0). Override to set flags at
construction.

**Dump-type codes** (returned by `getDumpType()`, RULE_NAMING_009):
- Single ASCII char < 127 → serialised as the char itself
  (`'s'`=115 SwitchElm, `'r'`=114 ResistorElm, `'w'`=119 WireElm,
  `'c'`=99 CapacitorElm, `'l'`=108 InductorElm, `'d'`=100 DiodeElm,
  `'v'`=118 VoltageElm, `'g'`=103 GroundElm, …).
- Numeric short string / int ≥ 127 → serialized as the integer
  (`208` CustomLogicElm, `410` CustomCompositeElm, `34` DiodeModel,
  `32` TransistorModel, `38` Adjustable, …).
- Special markers (handled before element dispatch in
  `TextCircuitImporter.processCircuitLine`):
  `'o'` scope, `'h'` hint, `'$'` options, `'!'` CustomLogicModel,
  `'.'` CustomCompositeModel, `'%'`/`'?'`/`'B'` legacy (ignored).

**No central registry for either.** Issue #9 in element-base analysis:
"A `DumpTypes` constants file would be clearer and prevent collisions".
Treat every new dump-type choice as a project-wide decision — grep
existing `getDumpType()` returns before adding.

**JSON type names** (RULE_NAMING_010, `CircuitElm.java:1534`) default
to class name minus `Elm` (`ResistorElm` → `"Resistor"`). Use PascalCase
with convention suffixes (`VoltageSourceAC`). **Aliases** live in
`CircuitElementFactory.init()` for historical renames — add an alias
rather than dropping a legacy key.

## Usage in this project

- `ChipElm.FLAG_FLIP_X/Y/XY` are checked in `setPoints` / `draw` to
  mirror pin placement and chip label. Tests for these: lay down a gate,
  flip in editor, save, reload → pins must be identical.
- `SwitchElm.FLAG_LABEL` toggles the label-string branch in
  `SwitchElm.dump()` (see `domain-core__element-base.md` §2.11).
  Linked-switch grouping uses the label to coordinate toggles across
  the document.
- `Inductor.FLAG_BACK_EULER = 2` picks integration scheme inside
  `Inductor.stamp` (`Inductor.java:65`). All inductor-hosting elements
  (`InductorElm`, `TransformerElm`, `DCMotorElm`, `RelayCoilElm`, …)
  pass the same flags through.
- `CompositeElm.FLAG_ESCAPE` enables nested-safe composite dumps so
  subcircuits can nest.

## Pitfalls

1. **No central registry.** When adding a new `FLAG_*`, check existing
   constants **on your concrete class's inheritance path** to avoid
   overlap. For chips, start at `1 << 14` to be safe (bits 10-13 are
   ChipElm-reserved).
2. **Dump-type collisions are also uncentralised** (element-base issue
   #9). `CustomCompositeElm` uses `410`, `CustomLogicElm` uses `208`,
   `DiodeModel` uses `34` (model prefix, not element), `TransistorModel`
   `32`, `Adjustable` `38`. Grep `.dev_flow/onboard/analysis/` for
   `getDumpType()` citations before assigning.
3. **Single-char dump-types below ASCII-32 would clash with legacy
   marker chars** (`'$'`, `'!'`, `'.'`, `'%'`, `'?'`, `'B'`, `'o'`,
   `'h'`). Avoid.
4. **`FLAG_*` bitmasks are OR-composed but never ANDed across classes**
   — moving a flag from one class to a parent without renumbering all
   subclass flags silently breaks dumps from old circuits.
5. **JSON `_flags` persists the same int** via `getJsonFlags` /
   `applyJsonFlags` (`CircuitElm.java:1764, 1866`). Rename-safe as long
   as class-path is preserved.
6. **ChipElm flags bits 10-13 are reserved** — subclasses that add
   chip-specific flags must use bit 14+. A number of one-off chip
   subclasses violate this by re-using bit 1 for a custom meaning; safe
   only because `FLAG_SMALL` is not used on those specific chips.

## References

- `.dev_flow/onboard/analysis/domain-core__element-base.md` §"ChipElm
  flag constants", §"SwitchElm", §"Inductor", §"Issues / Questions"
  items 8 and 9
- `src/main/java/com/lushprojects/circuitjs1/client/element/ChipElm.java`
  L(FLAG_* block)
- `src/main/java/com/lushprojects/circuitjs1/client/element/SwitchElm.java`
  L33+
- `src/main/java/com/lushprojects/circuitjs1/client/element/Inductor.java`
  L24+, L65
- `src/main/java/com/lushprojects/circuitjs1/client/element/CompositeElm.java`
  L31+
- Rules: RULE_NAMING_006, RULE_NAMING_009, RULE_NAMING_010
- Sibling skill: `element-authoring.md`
