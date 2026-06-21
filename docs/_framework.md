# Framework Map — CircuitJS1 Desktop Mod

The project's living **architectural spine**: core abstractions, layers, extension points, shared utilities, and conventions. It is an **overview** — it links *down* to `.dev_flow/rules/` and `.dev_flow/skills/` (which hold the enforceable detail) and to the `docs/` concept/spec set; it inlines none of it. Loaded as context on code-touch phases.

> Created 2026-06-21 by `/dev-flow audit code`, distilled from `.dev_flow/onboard/{layers,dependency_graph,project_structure}.md` (authoritative) + `CLAUDE.md`. The "Risk boundaries" section records what the defects audit surfaced. Maintained by [onboard](../.claude/skills/dev-flow/phases/onboard.md) and the [audit code scope](../.claude/skills/dev-flow/references/code-audit.md) — not hand-authored per feature.

## What this is
Offline analog/digital circuit simulator. **Java** → **JavaScript (GWT 2.12)** → desktop app via **NW.js** (Node-backed Chromium). A layered, multi-document refactor of Falstad/Sharp's monolithic `CirSim`. Toolchain: JDK 17+, Maven, Node/npm. ~275 Java files, ~68k LOC.

## Core abstractions
| Abstraction | Role | Detail in |
|---|---|---|
| `BaseCirSim` / `CirSim` | App-session shell; owns session-scoped managers + global model catalogs | [app-controller](./app-controller.concept.md), RULE_ARCH_006 |
| `CircuitDocument` | One per open tab/circuit; owns element list, simulator, editor, scopes, undo | [document-model](./document-model.concept.md), RULE_ARCH_006 |
| `BaseCirSimDelegate` | Base for managers/subsystems; resolves `simulator()`/`renderer()`/… through the active document | [document-model](./document-model.concept.md) |
| `CircuitSimulator` | MNA matrix build/solve, Newton iteration, time-step control | [INTERNALS.md](../INTERNALS.md), `simulator/` skills |
| `CircuitElm` (`extends BaseCircuitElm implements Editable`) | Abstract base of all 146 elements; `stamp()` + `doStep()` contract; geometry via `ElmGeometry`/`geom()` | [element-base](./element-base.concept.md), RULE_STRUCT_001/002 |
| `*Model` catalogs | Reusable, named, serializable parameter sets (diode/transistor/logic/composite) | RULE_STRUCT_005, RULE_ARCH_007 |
| `EditInfo` / `Editable` | Element parameter UI contract (`getEditInfo`/`setEditValue`) | [edit-info-contract](./edit-info-contract.concept.md), RULE_STRUCT_004 |

## Layers (dependency direction: L0 ← L1 ← L2 ← L3; new code must not import downstream — RULE_ARCH_001)
- **L0 — leaf utilities & primitives** · `client/util/` + root math/geometry/rendering (`Point`, `Rectangle`, `Polygon`, `Graphics`, `Color`, `CircuitMath`, `StringTokenizer`, `Expr`/`ExprParser`). Stateless.
- **L1 — reusable UI primitives** · `client/ui/tabs/` + small GWT-wrapped widgets (`Checkbox`, `Choice`, `Scrollbar`).
- **L2 — domain core** · `client/element/` (155 element classes incl. waveforms) + `client/dialog/` + shared `*Model` + `client/io/` (text + json). Two documented circular SCCs (element↔dialog, io↔io/text|json).
- **L3 — application shell** · `CirSim`/`BaseCirSim`, `CircuitDocument`, the `*Manager`s, editor/renderer, scopes, entry point.

**Frozen layer inversions (do not add a 4th — RULE_ARCH_002):** `util/PerfMonitor`→`element/BaseCircuitElm`; `element`↔`dialog`; `element`↔`element/waveform`. See `.dev_flow/onboard/issues.md`.

## Extension points (the seams)
- **New element** → subclass `CircuitElm`, build only via `CircuitElmCreator` (text) / `CircuitElementFactory` (JSON) — RULE_ARCH_005. Skill: `elements/`.
- **New file format** → self-register with `CircuitFormatRegistry` via a static initializer (registry never imports formats) — RULE_ARCH_004. Skill: `io/`.
- **New parameter UI** → the `Editable`/`EditInfo` contract; dialogs open via `DialogManager`, never `new`ed — RULE_ARCH_003.
- **New shared parameter set** → a `*Model` with a global registry — RULE_STRUCT_005.
- **Java↔JS** → clustered `native` JSNI in a small set of adapter files only — RULE_STRUCT_008 / RULE_ARCH_008.

## Shared utilities & conventions → enforceable detail
- Naming → [`.dev_flow/rules/naming.md`](../.dev_flow/rules/naming.md) (Elm/Dialog/Manager/Model suffixes, dump-type uniqueness)
- Structure → [`.dev_flow/rules/structure.md`](../.dev_flow/rules/structure.md) · Architecture → [`.dev_flow/rules/architecture.md`](../.dev_flow/rules/architecture.md)
- Error handling → [`.dev_flow/rules/error-handling.md`](../.dev_flow/rules/error-handling.md) (no throw from Newton loop; importers skip-and-log; log via `LogManager`)
- Style → [`.dev_flow/rules/style.md`](../.dev_flow/rules/style.md) (Java 17; GWT-forbidden APIs; `Locale.LS`; no hardcoded colors) · Testing → [`.dev_flow/rules/testing.md`](../.dev_flow/rules/testing.md)
- Skills index → [`.dev_flow/skills/_index.yaml`](../.dev_flow/skills/_index.yaml) (gwt · simulator · elements · io · editor)
- Doc catalog → [`_index.md`](./_index.md) · Epics: [domain-core](./domain-core.epic.md) · [editor](./editor.epic.md) · [simulator](./simulator.epic.md) · [visualization](./visualization.epic.md)

## Risk boundaries (surfaced by `audit code` — defects run 2026-06-21)
Two subsystems concentrate risk; treat changes there with extra care:
1. **IO import boundary** (`io/json/` + `io/text/` + `StringTokenizer` + `CircuitElementFactory`) — the untrusted-input surface **and** the locus of most correctness defects (malformed-input handling, per-element isolation, token-index contract). See plan ITEM-03/04/05/06.
2. **JSNI / NW.js boundary** (entry point, `$wnd.CircuitJS1.*` bridge, remote-debug agent, `LoadFile`/`ImportFromDropbox`, host page + manifests) — the security boundary; the window has Node integration and no CSP, so any injection here is host-level, not mere XSS. The **remote-debug agent's client-side `eval` is intentional, localhost-only diagnostic functionality by design** — a [settled decision](./remote_dbg_concept.md#trust-model--design-decision-settled-2026-06-21), not a defect. The live concern is the **untrusted circuit-file surface** (plan ITEM-02/07). See proposed `security.md` rules.

_(No `security.md` rule file or `security/` skill domain exists yet — both are **proposed** by the 2026-06-21 audit, pending the rule/skill gate.)_
