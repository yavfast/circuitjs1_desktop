# Onboard Issues Log

Ambiguities, cycles, and open questions discovered during project analysis.
Each issue is a candidate for manual decision before or during deeper phases.

---

## #1 — `util/PerfMonitor` imports `element/BaseCircuitElm`

**Location:** `src/main/java/com/lushprojects/circuitjs1/client/util/PerfMonitor.java:3`

Single cross-layer inversion: a Layer 0 utility imports a Layer 2 class.

**Impact:** prevents strict monotonic layering — handled for layering purposes
by flagging as a known inversion (util stays at Layer 0; the import is noted).

**Suggested resolution:** either (a) keep `PerfMonitor` at `util/` and have it
accept a generic `Object`/interface instead of `BaseCircuitElm`, or (b) move
`PerfMonitor` to `client/` root alongside `LogManager`. **Not required for
onboard** — flagged for future cleanup.

---

## #2 — Strong coupling: `element` ↔ `dialog` (120 import edges)

`element` → `dialog` (104) and `dialog` → `element` (16). Element classes
reference dialog types directly to wire up their edit UI, while edit dialogs
reach into element internals.

**Impact:** package boundaries do not reflect the actual design. The two
packages are *one logical subsystem* — "circuit elements and their edit UI".

**Suggested handling:** documented in `layers.md` as SCC-A ("domain core").
Generate a **single concept + spec pair** covering both packages rather than
two artificially separated ones.

---

## #3 — Parent-child cycle: `element` ↔ `element/waveform`

`element/waveform/*` classes reference voltage-source elements in `element/`,
while voltage-source elements own waveform instances.

**Impact:** identical structural problem as #2, smaller scale.

**Suggested handling:** fold `element/waveform` into the domain-core concept.

---

## #4 — Registration cycle: `io` ↔ `io/text` and `io` ↔ `io/json`

`CircuitFormatRegistry` (in `io/`) registers the concrete `TextCircuitFormat`
and `JsonCircuitFormat` at load time, creating a 1-edge back-link from the
framework package to its subpackages.

**Impact:** standard registration-pattern cycle, not a design problem.

**Suggested handling:** document in the io-framework concept. No refactor
required.

---

## #5 — Empty packages: `client/options/`, `client/ui/`

Directories exist but contain no `.java` files. Likely placeholders for future
reorganization (e.g., moving `OptionsManager` into `client/options/`,
consolidating widgets under `client/ui/`).

**Impact:** none on analysis; documented in `project_structure.md`.

**Suggested handling:** leave as-is. Any future reorganization would trigger
the propagate phase.

---

## #6 — Dialog vs. element inheritance for "Editable"

`dialog/Editable` is a marker interface implemented by `element/*Elm` classes.
This is what drives the 16 `dialog → element` imports (generic access) and
part of the 104 reverse imports (specific dialog construction).

**Impact:** informs the concept — the edit UI contract is defined in the
dialog package but consumed by the element package. Will be captured in the
domain-core concept as a key mechanism.

---

## #7 — Legacy `client/Diode.java` vs. `element/DiodeElm.java`

`client/Diode.java` at the project root is a numerical diode model used by
the simulator core, distinct from the `element/DiodeElm.java` UI/element
class. Naming collision.

**Impact:** minor — may be confusing to newcomers. Already noted in
`project_structure.md`.

**Suggested handling:** document in the simulator-core concept. Renaming
would touch too many callers; leave as-is and note the distinction.

---

## #8 — Concrete element grouping requires verification

`queue.yaml` proposes 15 element categories (passives, sources, diodes, etc.).
This grouping is based on filename patterns and domain knowledge, not
confirmed class-hierarchy analysis.

**Impact:** analysis output for the `element-categories` sub-unit depends on
accurate bucketing. Mis-categorization will surface during per-category
analysis (Step 4) and can be corrected there.

**Suggested handling:** treat the current categories as a first-pass draft;
finalize during Layer 2 analysis by inspecting each concrete element's base
class and the master element registry (likely `CircuitElm.getClass(...)` or
a factory table).

---

## #9 — No unit-test harness

The project has no `src/test/` JUnit harness. Automated verification relies
on (a) build checks (`npm run check`), (b) scripted import/export roundtrips
over `src/main/java/.../public/circuits/*`, and (c) manual devmode runs.

**Impact:** dev-flow Phase 5 (Test) has no unit/mock layer. Phase 7 (Verify)
is the primary functional-correctness gate — relying on roundtrip tests and
devmode. The rules-extraction step will not produce a `testing.md` rules file
(no patterns to extract from tests that don't exist).

**Suggested handling:** during doc generation, mark Test phase expectations
as "scripted roundtrip + manual devmode" and avoid prescribing unit tests in
generated plans unless the user opts in.

---

## Rules-extraction inconsistencies (added by OnboardRulesExtractor)

The following inconsistencies surfaced while extracting coding rules from the
37 analysis files. They are not cycles or layering violations (those are
tracked separately above) but pattern drift that rules documents had to flag
as `should` instead of `must`.

### RI-1 — Locale.LS is not uniformly applied to user-visible strings

- `Checkbox.java` wraps its label in `Locale.LS(...)`.
- `CheckboxMenuItem.java:91` builds its shortcut hint via direct string
  concatenation, bypassing `Locale.LS`.
- Similar drift across menu items / tooltips in `root-widgets.md`.

**Rule impact:** `RULE_STYLE_003` (Locale.LS for user strings) is severity
`should`, not `must`. New code must localise; legacy sites are grandfathered.

### RI-2 — String comparison with `==` instead of `.equals()`

- `CheckboxMenuItem.java:91` uses `shortcut.length()==1` (ints, benign) but
  the analysis flagged other string-reference comparisons in the same file
  family.
- `ModDialog` contains `==` on strings (cited in `root-widgets.md`).

**Rule impact:** `RULE_STYLE_006` is `should`, not `must`.

### RI-3 — DialogManager `activeDialog` tracking is inconsistent

- `showEditDiodeModelDialog` (`DialogManager.java:164-168`) and
  `showEditTransistorModelDialog` (`:170-174`) do **not** assign
  `activeDialog`, so `dialogIsShowing()` / `closeDialog()` cannot see them.
- `showAboutBox` (`:71-73`) intentionally bypasses the slot because
  `AboutBox` extends `PopupPanel` directly.

**Rule impact:** `RULE_STRUCT_003` (dialogs extend `Dialog` base) is `must`,
but the existing outliers mean enforcement is by convention, not by tooling.

### RI-4 — DialogManager `show()` calling convention is split

Some factories in `DialogManager` call `activeDialog.show()` internally
(Shortcuts, Subcircuit, Search, EditOptions, EditElement, Slider, ExportAs*,
EditCompositeModel); others rely on the caller to invoke `.show()` externally
(Help, License, Mod, ImportFromText, ScopeProperties). Callers must know
which pattern applies per dialog. Documented in
`layer3__cross-cutting-managers.md` issue #10.

**Rule impact:** no corresponding rule was written — this is a call-site
contract, not a coding pattern. Flagged for future consolidation.

### RI-5 — `util/Log` is dormant but present

`util/Log.log(String...)` has zero active callers (`util.md`) and routes to
`CirSim.console`, not `LogManager`. Keeping it in the tree risks new code
accidentally calling it and silently missing `ShowLogDialog`.

**Rule impact:** `RULE_ERR_005` explicitly calls out `util/Log` as dormant.
Candidate for removal.

### RI-6 — Legacy `System.out.println` at `ChipElm.java:315`

`ChipElm.setVoltageSource(j, vs)` prints `"setVoltageSource failed for <elm>"`
via `System.out.println`, not `LogManager`. `domain-core__element-base.md`
issue #7 flags this.

**Rule impact:** `RULE_STYLE_008` forbids printf-style logging for new code
but notes this violation as grandfathered.

### RI-7 — `BaseCircuitElm` at `element/` package is a layer quirk

Static formatting helpers (`formatNumber`, `getUnitText`, etc.) live in
`element/BaseCircuitElm` rather than `util/`. This is why `util/PerfMonitor`
must import `element/BaseCircuitElm` (Issue #1 above). `RULE_STRUCT_007`
(new utilities live in `util/`) documents this — do not use
`BaseCircuitElm` as a precedent for adding more utilities to `element/`.

### RI-8 — `CirSim.resetSimulation` duplicates `BaseCirSim.resetAction`

The two reset paths diverge in minor ways (`layer3__simulator-core.md:556-560`).
Documented for future consolidation; no rule was written because neither path
is "correct" today.

### RI-9 — Magic numeric dump types (208, 410, `"34 "`, `"32 "`, `". "`, `"! "`)

No central registry enforces uniqueness of dump-type codes across elements
and models. `RULE_NAMING_009` (dump type identifier uniqueness) is `must`,
but the enforcement is manual. Flagged in `domain-core__element-base.md`
issue #9 and `domain-core__shared-models.md` issue #6.

