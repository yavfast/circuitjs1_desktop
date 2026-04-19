# Module Analysis: root-utils

> **Path:** src/main/java/com/lushprojects/circuitjs1/client/ (17 selected files)
> **Layer:** 0
> **Analyzed:** 2026-04-18
> **Files:** 17 source files, 0 test files

## Purpose

These 17 files form the foundational utility / primitive layer of the CircuitJS1
client — everything the rest of the app builds on top of, with **no** inbound
dependencies on other CircuitJS1 packages. They exist at the `client/` root
(instead of under `client/util/`) for historical reasons: the project predates
the introduction of the `util/` subpackage, which today holds only `Locale`,
`Log`, and `PerfMonitor`. The files here are a mix of:

- **GWT-compatibility shims** reimplementing parts of `java.awt` (`Point`,
  `Rectangle`, `Polygon`, `Color`, `Font`, `Graphics`) because the original
  Falstad applet used AWT and GWT cannot compile `java.awt.*`.
- **Third-party ports** retained verbatim (`StringTokenizer` from GNU Classpath,
  `Rectangle`/`Polygon` from OpenJDK 6).
- **Numerical / DSP helpers** (`CircuitMath`, `FFT`, `RandomUtils`) extracted
  from the simulator core.
- **Expression engine** (`Expr`, `ExprParser`, `ExprState`) — a self-contained
  mini-language interpreter used by custom-function voltage/current sources.
- **Platform and constants** (`PlatformUtils`, `GWTUtils`, `CircuitConst`) —
  browser/NW.js bridges and symbolic flags.

All 17 files live in the `com.lushprojects.circuitjs1.client` package — none
declare themselves in any subpackage.

## Sub-clusters

1. **Geometry primitives** (4 files) — `Point`, `Rectangle`, `IntPair`,
   `Polygon`. Plain value/collection types for integer-plane geometry. No
   dependencies; pure Java. Used pervasively by elements, editor, renderer.

2. **Rendering primitives** (3 files) — `Color`, `Font`, `Graphics`. Thin
   wrappers over GWT `Context2d`. `Graphics` is the central canvas-drawing
   façade; `Color`/`Font` model visual attributes.

3. **Math / DSP** (3 files) — `CircuitMath` (LU solver + scope metrics), `FFT`
   (radix-2 decimation-in-time), `RandomUtils` (singleton `java.util.Random`).

4. **String / parser utility** (1 file) — `StringTokenizer` (GNU Classpath
   port of `java.util.StringTokenizer` with extra position accessors).

5. **Expression engine** (3 files) — `Expr` (AST node + tree-walker
   interpreter), `ExprParser` (recursive-descent parser with lowercase
   normalization), `ExprState` (runtime evaluation context: variables `a`–`i`,
   `lastOutput`, `t`, `timeStep`).

6. **Platform / GWT shims / constants** (3 files) — `PlatformUtils` (URL
   opening via NW.js/window.open), `GWTUtils` (CSS-on-Widget helpers),
   `CircuitConst` (bitflags & hint codes shared across circuit loading).

## Key Entities

### Geometry cluster

#### Point
- **Type:** class
- **File:** src/main/java/com/lushprojects/circuitjs1/client/Point.java:22
- **Fields:** public `int x, y`.
- **Constructors:** `Point()`, `Point(int,int)`, `Point(Point)`.
- **Methods:** `setLocation(Point)`, `move(int dx, int dy)`, `equals`,
  `hashCode` (`41*(41+x)+y`), `toString`.
- **Invariants:** none; mutable value type.

#### Rectangle
- **Type:** class
- **File:** src/main/java/com/lushprojects/circuitjs1/client/Rectangle.java:21
- **Fields:** public `int x, y, width, height`.
- **Notes:** Port from OpenJDK 6 `java.awt.Rectangle`. Handles "empty"
  rectangles (`width|height < 0`) per AWT semantics.
- **Methods:** `setBounds`, `translate`, `contains(int,int)`,
  `contains(Rectangle)`, `intersects(Rectangle)`, `union(Rectangle)` (returns
  new, does not mutate), `equals`, `toString`.
- **Invariants:** negative `width`/`height` signal "non-existent"; methods
  short-circuit on that condition. `union` clamps results to `Integer.MAX_VALUE`.

#### IntPair
- **Type:** class (immutable)
- **File:** src/main/java/com/lushprojects/circuitjs1/client/IntPair.java:5
- **Fields:** private `final int first, second`.
- **Methods:** `getFirst`, `getSecond`, `equals`, `hashCode` (via
  `Objects.hash`), `toString`.
- **Invariants:** immutable; value-equal.

#### Polygon
- **Type:** class
- **File:** src/main/java/com/lushprojects/circuitjs1/client/Polygon.java:21
- **Fields:** public `int npoints`, `int[] xpoints`, `int[] ypoints`.
  Constant `MIN_LENGTH = 4` for initial array size.
- **Methods:** `Polygon()`, `addPoint(int x, int y)` (auto-grows arrays by
  powers of 2 via `Integer.highestOneBit`). Private `expand` reallocates.
- **Invariants:** `xpoints.length == ypoints.length`; `npoints <= xpoints.length`.
  Note: unlike AWT `Polygon`, does **not** track bounding box (the commented
  `updateBounds` call is dead code).

### Rendering cluster

#### Color
- **Type:** class (mostly immutable; fields are private but not `final`)
- **File:** src/main/java/com/lushprojects/circuitjs1/client/Color.java:22
- **Fields:** private `int r, g, b`; private `String colorText` (for
  name-only / hex-text colors that bypass RGB decomposition).
- **Constants:** 13 named colors (white, lightGray, gray, dark_gray/darkGray,
  black, red, pink, orange, yellow, green, magenta, cyan, blue) plus `NONE =
  new Color("")` (empty colorText sentinel).
- **Constructors:**
  - `Color(String)` — parses `#RRGGBB` format (7 chars, hex). Any other
    input stores the string verbatim without parsing RGB.
  - `Color(int r, int g, int b)` — direct RGB.
  - `Color(Color c1, Color c2, double mix)` — linear blend.
- **Methods:** `getRed`, `getGreen`, `getBlue`, `getHexValue` (returns stored
  `colorText` if present, else `#rrggbb` with zero-padding), `toString`.
- **Invariants:** When `colorText != null` AND doesn't match `#RRGGBB`,
  `r/g/b` remain 0 — so `NONE` reports `r=0,g=0,b=0`. Ambiguity: named colors
  from `colorText` never populate RGB.

#### Font
- **Type:** class
- **File:** src/main/java/com/lushprojects/circuitjs1/client/Font.java:23
- **Fields:** package-private `final String fontname` (prebuilt CSS string),
  `final int size`.
- **Constants:** `NORMAL=0`, `BOLD=1`, `ITALIC=2` (bitflags).
- **Constructor:** `Font(String name, int style, int size)` — builds a CSS
  short-form font string like `"bold italic 12px sans-serif"`. Maps Java
  `"SansSerif"` → CSS `"sans-serif"`. (Uses `==` on strings, line 33: likely
  interned-only comparison.)

#### Graphics
- **Type:** class (GWT canvas façade)
- **File:** src/main/java/com/lushprojects/circuitjs1/client/Graphics.java:27
- **Fields:** private `final Context2d context`, `int currentFontSize`,
  `int savedFontSize`; forced-color stack (`forcedColorDepth`,
  `forcedColorHex`); style tracking (`lastStrokeStyleObject`,
  `lastStrokeStyleString`, `lastFillStyleString`, `lastStrokeIsObject`).
  Static `boolean isFullScreen`.
- **Invariants:**
  - `forcedColorDepth >= 0`; when `> 0`, all style setters stash the requested
    style in `last*` fields but push `forcedColorHex` to the canvas instead.
  - `save/restore` mirror only `currentFontSize`; all other canvas state is
    delegated to `Context2d.save/restore`.

### Math / DSP cluster

#### CircuitMath
- **Type:** class (static utility)
- **File:** src/main/java/com/lushprojects/circuitjs1/client/CircuitMath.java:3
- **Fields:** private `static volatile int lastLuFailColumn`,
  `lastLuFailRow`, `double lastLuFailPivotAbs` (diagnostics for failed
  LU factorization).
- **Nested result types:** `FreqData`, `WaveformMetrics`, `DutyCycleInfo`
  (public static, plain data classes).

#### FFT
- **Type:** class (package-private)
- **File:** src/main/java/com/lushprojects/circuitjs1/client/FFT.java:22
- **Fields:** `int size`, `int bits` (= log2(size)), `double[] cosTable`,
  `double[] sinTable` (both length `size/2`), `double[] winTable` (length
  `size`, sine window scaled by 1.5707963… for unity gain).
- **Invariants:** `size` must be a power of 2 (bit-reversal loop and
  `n2 <<= 1` require it). `bits` is computed but not validated.

#### RandomUtils
- **Type:** class (static utility)
- **File:** src/main/java/com/lushprojects/circuitjs1/client/RandomUtils.java:5
- **Fields:** `private final static Random random = new Random()` (singleton).

#### StringTokenizer
- **Type:** class implementing `Enumeration<Object>`
- **File:** src/main/java/com/lushprojects/circuitjs1/client/StringTokenizer.java:64
- **Fields:** `int pos`, `int start` (unused externally — always 0 unless
  set elsewhere? Only exposed via `getStartTokenIdx()`), `final String str`,
  `final int len`, `String delim`, `final boolean retDelims`.
- **Note:** GNU Classpath port; adds project-specific helpers
  (`tryNextToken`, `getEndTokenIdx`, `getStartTokenIdx`, `getOriginalString`).
  The `start` field is never updated in `nextToken` (appears dead / unused).

### Expression engine cluster

#### Expr
- **Type:** class (AST node + interpreter)
- **File:** src/main/java/com/lushprojects/circuitjs1/client/Expr.java:5
- **Fields:** package-private `Vector<Expr> children`, `double value`, `int type`.
- **Constants (opcodes):** 50+ `static final int E_*` constants
  — operators (`E_ADD=1, E_SUB=2, …`), functions (`E_SIN, E_COS, …, E_SINH,
  E_COSH, E_TANH, E_FLOOR, E_CEIL, E_ASIN, E_ACOS, E_ATAN`), special
  identifiers (`E_T=3` for simulation time, `E_VAL=6` for literals,
  `E_LASTOUTPUT=30`, `E_TIMESTEP=31`), variables:
  - `E_A = 50` — base of variable block `a..i`
  - `E_DADT = E_A + 10 = 60` — derivative block `da/dt..di/dt`
  - `E_LASTA = E_DADT + 10 = 70` — `lasta..lasti`
  Layout is **positionally significant** (eval uses `type - E_A` etc.).
- **Methods:** constructors, `eval(ExprState)` — single giant switch.
  Static helpers: `pwl(ExprState, Vector<Expr>)` (piecewise-linear),
  `posmod(double, double)` (always-positive modulo used by `E_TRIANGLE`,
  `E_SAWTOOTH`).

#### ExprParser
- **Type:** class (recursive-descent parser)
- **File:** src/main/java/com/lushprojects/circuitjs1/client/ExprParser.java:3
- **Fields:** package-private `String text, token`, `int pos, tlen`,
  `String err`.
- **Grammar (precedence low → high):** `parse` (ternary `?:`) → `parseOr`
  (`||`) → `parseAnd` (`&&`) → `parseEquals` (`==`) → `parseCompare`
  (`<= >= != < >`) → `parseAdd` (`+ -`) → `parseMult` (`* /`) → `parseUminus`
  (`+ - !`) → `parsePow` (`^`) → `parseTerm` (literal / ident / function /
  parens).
- **Pre-processing:** input is lowercased in constructor
  (`text = s.toLowerCase()`), so identifiers are case-insensitive.
- **Identifier vocabulary in `parseTerm`:** `t`, `a..i`, `lasta..lasti`,
  `da/dt..di/dt` (as tokens `dadt..didt` with 4-char pattern starting `d`,
  ending `dt`), `lastoutput`, `timestep`, `pi`; functions `sin cos asin
  acos atan sinh cosh tanh abs exp log sqrt tan tri saw floor ceil min max
  pwl mod step select clamp pwr pwrs`.

#### ExprState
- **Type:** class (mutable runtime state)
- **File:** src/main/java/com/lushprojects/circuitjs1/client/ExprState.java:3
- **Fields:** public `double[] values`, `double[] lastValues` (both length 9,
  indexed `a=0 … i=8`), `double lastOutput`, `double t`, `double timeStep`.
- **Constructor:** `ExprState(int xx)` — `xx` is **unused** (historical
  signature). Pre-initializes `values[4] = Math.E` (so variable `e` defaults
  to Euler's number).

### Platform cluster

#### PlatformUtils
- **Type:** class (static utility)
- **File:** src/main/java/com/lushprojects/circuitjs1/client/PlatformUtils.java:9
- **Uses:** `com.google.gwt.core.client.GWT` (for logging).
- **JSNI:** `openURLWithJavaScript` tries NW.js `nw.Shell.openExternal`, falls
  back to `window.open`. `getPlatformInfo` returns serialized JSON.

#### GWTUtils
- **Type:** class (static utility)
- **File:** src/main/java/com/lushprojects/circuitjs1/client/GWTUtils.java:8
- **Uses:** `com.google.gwt.user.client.ui.Widget`. Pure convenience methods
  for setting CSS properties on widgets.

#### CircuitConst
- **Type:** interface (symbolic constants only)
- **File:** src/main/java/com/lushprojects/circuitjs1/client/CircuitConst.java:3
- **Constants:**
  - Restore/load flags: `RC_RETAIN=1`, `RC_NO_CENTER=2`, `RC_SUBCIRCUITS=4`,
    `RC_KEEP_TITLE=8` (bitflags).
  - Component-auto-hint types: `HINT_LC=1`, `HINT_RC=2`, `HINT_3DB_C=3`,
    `HINT_TWINT=4`, `HINT_3DB_L=5`.

## Public Contracts

### Geometry

#### Point.move
- **File:** src/main/java/com/lushprojects/circuitjs1/client/Point.java:65
- **Input:** `int dx, int dy`
- **Output:** void — mutates `x, y`.
- **Logic:** `x += dx; y += dy`.

#### Rectangle.contains(int, int)
- **File:** src/main/java/com/lushprojects/circuitjs1/client/Rectangle.java:64
- **Input:** `int X, int Y`.
- **Output:** `boolean`.
- **Errors:** none; returns `false` for negative-dim rectangles.
- **Logic:** Port of AWT `Rectangle.contains`, uses long-arithmetic trick to
  handle `x+width` overflow.

#### Rectangle.union(Rectangle)
- **File:** src/main/java/com/lushprojects/circuitjs1/client/Rectangle.java:118
- **Input:** other rect.
- **Output:** new `Rectangle` spanning both; handles empty-rectangle edge
  cases by returning a copy of the non-empty operand. Clamps overflow to
  `Integer.MAX_VALUE`.

#### Polygon.addPoint
- **File:** src/main/java/com/lushprojects/circuitjs1/client/Polygon.java:40
- **Input:** `int x, int y`.
- **Output:** void — mutates.
- **Logic:** Appends; on array exhaustion, doubles capacity (rounded to next
  power of 2 via `Integer.highestOneBit`).

### Rendering — Graphics (full API surface)

#### Graphics.pushForcedColor / popForcedColor
- **File:** Graphics.java:79 / :91
- **Purpose:** Stack-based override of stroke/fill to a single color. Used for
  error highlighting (e.g., to paint a failing element red). Nested pushes are
  counted; only the outermost pop restores tracked styles.

#### Graphics.setColor(Color) / setColor(String)
- **File:** Graphics.java:108 / :114
- **Input:** color object or hex/css string.
- **Output:** void — sets both stroke and fill.
- **Logic:** If forced-color active, tracks requested style but paints forced
  color on the canvas instead.

#### Graphics.clipRect / save / restore
- **File:** :130 / :141 / :136
- Delegates to `Context2d.clip` / `save` / `restore`; `save`/`restore` also
  save `currentFontSize`.

#### Graphics.fillRect / drawRect / fillOval / drawCircle / arc(…) / ellipse (native)
- **File:** :147 / :151 / :155 / :162 / :168 / :172 / :176
- Wrappers over `Context2d`. `ellipse` is a JSNI method that guards `rx/ry >= 0`.
  `fillOval` uses hardcoded `3.14159` (not `Math.PI`) in its arc call —
  minor precision inconsistency.

#### Graphics.drawString / measureWidth
- **File:** :184 / :188
- Thin wrappers over `fillText` / `measureText().getWidth()`.

#### Graphics.drawLine(int×4) / drawLine(Point, Point)
- **File:** :204 / :211
- Uses `beginPath/moveTo/lineTo/stroke` sequence.

#### Graphics.drawPolyline(int[], int[], int)
- **File:** :218
- **Validation:** `n <= 0`, null arrays, or any `NaN` coordinate → no-op.
  Always `closePath()`s — so "polyline" is actually a closed polygon outline.

#### Graphics.fillPolygon(Polygon)
- **File:** :237
- **Validation:** null, `npoints < 3`, null internal arrays, any NaN,
  or all-same-point polygons → no-op.

#### Graphics.drawLock
- **File:** :284
- Draws a padlock icon (3px red stroke) — used in locked-circuit UI.

#### Graphics.distanceSq (static)
- **File:** :298
- Returns squared int distance — avoids sqrt.

#### Graphics.setLineDash (native static)
- **File:** :308
- JSNI; empty array when `a == 0`, else `[a, b]`.

#### Graphics.viewFullScreen / exitFullScreen
- **File:** :316 / :335
- JSNI helpers covering browser-prefixed fullscreen APIs
  (standard, moz, webkit, ms). Sets static `isFullScreen` flag.

#### Graphics.setTransform / transform / scale / translate
- **File:** :354 / :358 / :362 / :366
- Pass-through to `Context2d` affine ops.

#### Graphics.drawImage(CanvasElement, double, double)
- **File:** :370
- Delegates to canvas `drawImage`.

#### Graphics.setStrokeStyle(FillStrokeStyle) / setStrokeStyle(String)
- **File:** :402 / :412
- Like `setColor` but only for stroke. Respects forced-color override.

#### Graphics.bezierCurveTo / beginPath / closePath / stroke / moveTo / lineTo / fill
- Final block of pass-through methods.

### Math

#### CircuitMath.isConverged(double, double)
- **File:** CircuitMath.java:23
- **Input:** two successive Newton-iteration values.
- **Output:** `boolean`.
- **Logic:** Relative error < 1%; returns `true` when both values are ~0.

#### CircuitMath.lu_factor(double[][], int, int[])
- **File:** CircuitMath.java:72
- **Input:** `a` — matrix modified in place; `n` — size; `ipvt` — pivot vector
  to be filled.
- **Output:** `boolean` — true on success, false on singular matrix.
- **Errors:** on failure, sets static diagnostics `lastLuFailColumn`,
  `lastLuFailRow`, `lastLuFailPivotAbs`.
- **Logic:** Crout LU with partial pivoting. Edge cases: `n=0` succeeds
  trivially, `n=1` handles 1×1. Pivot threshold `1e-14`.

#### CircuitMath.lu_solve(double[][], int, int[], double[])
- **File:** CircuitMath.java:36
- **Input:** Factorized `a`, size `n`, pivot indices `ipvt`, RHS `b` (modified
  in-place).
- **Output:** void; `b` contains solution.
- **Logic:** Forward-substitution with row swaps, then back-substitution.
- **Note:** Does no singularity check — relies on prior `lu_factor` success.

#### CircuitMath.invertMatrix(double[][], int)
- **File:** CircuitMath.java:161
- Computes in-place matrix inverse via `lu_factor` + n `lu_solve` calls on
  identity columns. Does **not** check `lu_factor` return value.

#### CircuitMath.calculateAverage
- **File:** CircuitMath.java:190
- Ring-buffer-aware average of `(minV + maxV)` over `width` samples.
- Requires `scopePointCount` is a power of 2 (uses `& (scopePointCount-1)`).

#### CircuitMath.calculateFrequency → FreqData
- **File:** CircuitMath.java:219
- Rising-edge detection around `averageValue * 0.05` threshold, rejecting
  periods shorter than 12 samples. Computes mean period + SD; returns
  zero-frequency `FreqData` if SD > 2 or no periods found.

#### CircuitMath.calculateWaveformMetrics → WaveformMetrics
- **File:** CircuitMath.java:295
- Two-pass: pass 1 finds full cycles (rising edges across midpoint); pass 2
  computes RMS + average over that block. `valid=false` when < 1 full cycle.

#### CircuitMath.calculateDutyCycle → DutyCycleInfo
- **File:** CircuitMath.java:375
- Finds rising/falling edges; averages pulse-width / period over complete
  cycles; returns duty cycle as integer percentage.

### FFT

#### FFT(int n) constructor
- **File:** FFT.java:29
- **Input:** `n` — window size (power of 2).
- **Side effects:** precomputes `cosTable`, `sinTable`, `winTable` (sine window
  × 1.5707963 gain compensation for unity overall gain).

#### FFT.fft(double[] real, double[] imag, boolean windowed)
- **File:** FFT.java:59
- **Input:** two equal-length arrays (in place), `windowed` flag.
- **Output:** void — in-place transform.
- **Logic:** Standard radix-2 decimation-in-time. Bit-reversal swap, then
  log2(n) butterfly stages using precomputed twiddle factors.

#### FFT.getSize / magnitude
- **File:** :107 / :111
- `magnitude` returns `sqrt(real² + imag²) / size` — note division by `size`
  (not `size/2`), which is a documentation/consistency trap.

### StringTokenizer

#### StringTokenizer(String [, String [, boolean]])
- **File:** StringTokenizer.java:105 / :118 / :134
- Same contract as `java.util.StringTokenizer`.

#### hasMoreTokens / nextToken / nextToken(String) / countTokens
- Mirrors `java.util.StringTokenizer` 1.4 semantics; throws
  `NoSuchElementException`.

#### tryNextToken (project-local)
- **File:** :194
- Returns `null` instead of throwing when exhausted — convenience for parsers.

#### getEndTokenIdx / getStartTokenIdx / getOriginalString
- **File:** :201 / :205 / :209
- Positional accessors for custom error messages. Note: `start` field is
  declared but never written — `getStartTokenIdx` always returns 0.

### Expression engine

#### new Expr(Expr, Expr, int) / new Expr(int, double) / new Expr(int)
- **File:** Expr.java:6 / :14 / :19
- Construct AST nodes. The (left, right, type) form creates a `children`
  `Vector`; the parser may add more (ternary, variadic `min/max/pwl/clamp`).

#### Expr.eval(ExprState)
- **File:** Expr.java:24
- **Input:** current `ExprState`.
- **Output:** `double`.
- **Errors:** For unknown `type` codes < `E_A`, logs `CirSim.console("unknown\n")`
  and returns 0. Division by zero, log of non-positive, etc. propagate as IEEE
  NaN / Infinity.
- **Logic:** Recursive tree walk. `E_TERNARY` uses lazy evaluation; variable
  lookups dispatch on `type >= E_LASTA` / `>= E_DADT` / `>= E_A`, picking slot
  `type - E_A` (0..8). `E_DADT` guards `timeStep==0` with `1e-12`.
  `E_TRIANGLE`/`E_SAWTOOTH` use `posmod` to normalize negative inputs.

#### Expr.pwl (static)
- **File:** Expr.java:166
- Piecewise-linear interpolation over `(x0,y0), (x1,y1), …` from args 1+.

#### new ExprParser(String)
- **File:** ExprParser.java:300
- **Input:** source text (lowercased on entry).
- **Side effects:** initializes `pos`, `err=null`; primes first token.

#### ExprParser.parseExpression()
- **File:** ExprParser.java:68
- **Output:** `Expr` (root of AST). Empty input yields `Expr(E_VAL, 0.)`.
  Leftover tokens after `parse()` set error "unexpected token: …".

#### ExprParser.gotError()
- **File:** ExprParser.java:308
- Returns first error message recorded during parsing, or `null`.

#### ExprState(int)
- **File:** ExprState.java:10
- Allocates `values`/`lastValues` of length 9 and sets `values[4] = Math.E`.
  Parameter `xx` is ignored (legacy).

#### ExprState.updateLastValues(double lastOut)
- **File:** ExprState.java:17
- Copies current `values[]` into `lastValues[]` and records `lastOutput`.
  Called once per simulation step by custom-function elements
  (`VCVSElm`, `VCCSElm`, `CCVSElm`, `CCCSElm`).

#### ExprState.reset()
- **File:** ExprState.java:24
- Zeros `lastValues`, `lastOutput`, `timeStep`. Note: does **not** restore
  `values[4] = Math.E`, so `e` stays whatever the last update set it to —
  potential subtle bug on re-initialization.

### Platform

#### PlatformUtils.openURL(String)
- **File:** PlatformUtils.java:18
- Tries `openURLWithJavaScript` (NW.js `nw.Shell.openExternal` or
  `window.open`); on exception falls back to an unimplemented
  `openURLWithSystemCommand`. `AI_TODO`/`AI_THINK` comments mark future
  Desktop-API / `xdg-open` integration.

#### PlatformUtils.getPlatformInfo() / isURLOpeningSupported()
- **File:** :81 / :98
- JSNI detection of NW.js + `nw.Shell` availability as JSON.

#### GWTUtils.setStyle(Widget, String, String) / setStyles(Widget, String...) / …
- **File:** GWTUtils.java:16 / :25 / 30+ more helpers
- Thin CSS sugar over `widget.getElement().getStyle()`. `setStyles` throws
  `IllegalArgumentException` on odd number of arguments.

### CircuitConst

Interface-as-constants-bag. No methods — constants only; implementers/
importers get the flags via `implements`/`static imports`.

## Validation Rules

- **Rectangle:** methods treat `width<0` or `height<0` as "empty"; `union`
  clamps overflow; `contains(int,int)` short-circuits on empty rect.
- **Polygon.addPoint:** capacity grows by power-of-2 via
  `Integer.highestOneBit`.
- **Color:** `#RRGGBB` parser requires exactly 7 chars starting with `#`;
  non-matching strings store `colorText` but leave RGB at 0.
- **Graphics.drawPolyline/fillPolygon:** guard against null arrays,
  `n <= 0`/`npoints < 3`, NaN coordinates, and "all points identical"
  (fillPolygon only).
- **Graphics.ellipse:** JSNI guard `rx >= 0 && ry >= 0`.
- **CircuitMath.lu_factor:** pivot threshold `1e-14`; records diagnostics on
  failure; `n < 0` returns false; `n == 0` returns true trivially; `n == 1`
  handles degenerate case.
- **CircuitMath.calculateFrequency:** period < 12 samples filtered out; SD > 2
  or no periods → frequency zeroed.
- **CircuitMath.calculateAverage/scope metrics:** relies on ring-buffer size
  being a power of 2 (`& (size-1)`).
- **Expr.eval (E_DADT):** guards `timeStep == 0` with `1e-12`.
- **GWTUtils.setStyles:** throws `IllegalArgumentException` on odd-length
  varargs.
- **PlatformUtils.openURL:** null / empty URL → `false`.

## State Transitions

Most entities are **pure value types or stateless static utilities** — no
state machines. The exceptions:

- **`ExprState` (mutable runtime environment for Expr interpretation).**
  Lifecycle:
  1. Construction — `values[0..8] = 0` except `values[4] = Math.E`; arrays
     pre-sized to 9.
  2. Per-step update by the caller — fill `values[0..8]`, `t`, `timeStep`
     externally; call `eval(…)`.
  3. `updateLastValues(lastOut)` — freezes current `values` into `lastValues`
     for next-step `dadt` / `lasta` access.
  4. `reset()` — returns to a fresh-session state (but does **not** restore
     `values[4] = Math.E`, see Issues).

- **`Graphics` forced-color stack.** Pushes increment
  `forcedColorDepth`; pops decrement. On transition `1 → 0`, tracked
  `last*` styles are flushed back to the canvas (`restoreTrackedStyles`).

- **`Polygon`** grows dynamically as `addPoint` is called; `xpoints`/`ypoints`
  length never shrinks.

- **`StringTokenizer`** advances `pos` monotonically as tokens are consumed;
  delimiter set is mutable via `nextToken(String)` or implicit; mirrors
  `java.util.StringTokenizer` semantics.

- **`CircuitMath` static diagnostics** (`lastLuFailColumn/Row/PivotAbs`) —
  updated on each `lu_factor` call; `volatile` — global singleton state.

## Integration Points

### Depends on

All 17 files **live** in `com.lushprojects.circuitjs1.client`. Their *imports*
of other project code:

- `Expr.java` — imports nothing from the project directly, but calls
  `CirSim.console(...)` at line 161 (same-package access, no import
  needed — still a reverse dependency on the top-level `CirSim` class).
  This is the one leak in this module.
- All other 16 files have **no project-internal imports**.

External imports:

- `Graphics.java`: `com.google.gwt.canvas.dom.client.CanvasGradient`,
  `Context2d`, `FillStrokeStyle`, `com.google.gwt.dom.client.CanvasElement`.
- `PlatformUtils.java`: `com.google.gwt.core.client.GWT`.
- `GWTUtils.java`: `com.google.gwt.user.client.ui.Widget`.
- `IntPair.java`: `java.util.Objects`.
- `StringTokenizer.java`: `java.util.Enumeration`, `java.util.NoSuchElementException`.
- `RandomUtils.java`: `java.util.Random`.
- `Expr.java`: `java.util.Vector`.

### Used by

Based on grep of class-name references across the client tree:

- **Point / Rectangle / Color / Font / Graphics** — used virtually everywhere:
  ~100 files in `element/`, ~30 in `dialog/`, plus root-level (`CircuitElm`,
  `CircuitRenderer`, `Scope`, `CirSim`, etc.). These are the load-bearing
  primitives.
- **Polygon** — referenced in `Graphics.fillPolygon`, `element/CompositeElm`,
  and several `element/*Elm` drawing paths.
- **IntPair** — only `element/CompositeElm.java` uses it (ports/pins map).
- **CircuitMath** — used by `CirSim`, `CircuitSimulator`, `Scope`,
  `ScopePlot`, multiple element Elm classes (anywhere LU or scope metrics
  are needed).
- **FFT** — only `Scope.java` (spectrum view).
- **RandomUtils** — used by noise/random waveform elements
  (`element/waveform/Noise…`), `SparkGapElm`, seed utilities.
- **StringTokenizer** — pervasive in text-format I/O
  (`io/text/TextCircuitImporter`, element `setParam`/legacy parsers).
- **Expr / ExprParser / ExprState** — only 4 element classes:
  `VCCSElm`, `VCVSElm`, `CCCSElm`, `CCVSElm` (controlled sources with
  user-defined expressions).
- **PlatformUtils** — only `ActionManager`, `MenuManager` (for the
  "open help URL" family of actions).
- **GWTUtils** — `CircuitRenderer`, `CircuitEditor`, `UndoManager`,
  `CircuitLoader`, `JsonCircuitImporter` (CSS flex layouts).
- **CircuitConst** — implemented or imported by most top-level classes that
  deal with load flags (`CirSim`, editor, importers, `CircuitLoader`,
  `UndoManager`, `ActionManager`).

### External deps

- GWT Canvas API (`com.google.gwt.canvas.dom.client.*`, `dom.client.CanvasElement`) — only `Graphics`.
- GWT Core (`com.google.gwt.core.client.GWT`) — only `PlatformUtils`.
- GWT UI (`com.google.gwt.user.client.ui.Widget`) — only `GWTUtils`.
- JSNI browser APIs: `window.open`, `nw.Shell`, `requestFullscreen` +
  vendor-prefixed variants, `navigator`.
- `java.util.*`: `Vector`, `Random`, `Objects`, `Enumeration`, `NoSuchElementException`.

## Existing Documentation

- **Javadoc present on:** `StringTokenizer` (full GNU Classpath docs),
  `PlatformUtils` (class + `openURL`, `isURLOpeningSupported`), `GWTUtils`
  (every public method), `Graphics.pushForcedColor/popForcedColor`,
  `CircuitMath.calculateFrequency/WaveformMetrics/DutyCycle`.
- **Inline rationale comments:** `FFT` cites Douglas L. Jones / MEAPsoft as
  the algorithm source. `Rectangle` / `Polygon` cite OpenJDK 6 grepcode
  URLs. `Color` explains `colorText` fallback. `CircuitMath.lu_factor`
  documents the diagnostics fields.
- **AI_TODO / AI_THINK markers:** `PlatformUtils.openURL` (Desktop API
  fallback, platform-specific system commands).
- **No module-level README or cross-file overview** — cluster membership
  is implicit from file location.

## Issues / Questions

1. **`Expr.eval` → `CirSim.console(...)` is a reverse dependency**:
   the expression engine (otherwise self-contained) calls into the top-level
   simulator for an error log. Replacing with a simple `GWT.log` or a
   pluggable logger would cut the tie.
2. **`ExprState.reset()` does not restore `values[4] = Math.E`** — after a
   reset, variable `e` resolves to 0 until the next `updateLastValues`. Looks
   like a bug; the constructor initializes it but `reset` does not.
3. **`StringTokenizer.start` field is never assigned**, so `getStartTokenIdx`
   always returns 0. Either dead code or an incomplete feature.
4. **`Color(String)` silently accepts invalid input** — any string not
   matching `#RRGGBB` leaves `r=g=b=0`. No error is reported. `NONE`
   deliberately exploits this but real call sites lose information.
5. **`Font` uses `==` on `String`** (line 33: `if (name == "SansSerif")`).
   Works by string interning for the specific literal but is semantically
   wrong — should be `.equals`.
6. **`Graphics.fillOval` uses literal `3.14159`** instead of `Math.PI`;
   `drawCircle` uses `Math.PI`. Inconsistency and a tiny accuracy loss.
7. **`Graphics.drawPolyline` always closes the path** — so it's actually a
   polygon outline. Misleading method name.
8. **`Polygon` has no `getBounds()`** (AWT has it); the commented
   `updateBounds` hints this was intended. Consumers compute bounds manually.
9. **`CircuitMath.invertMatrix` ignores `lu_factor` return value** — if the
   matrix is singular, it silently proceeds and `lu_solve` will divide by a
   near-zero pivot. No tests guard against this.
10. **`IntPair` is only used in one place** (`CompositeElm`). Borderline
    dead code; could be inlined or replaced with `int[2]` for hot paths.
11. **`FFT` is package-private** but its sole external consumer is `Scope`
    (in the same package) — intentional. Could be documented as internal.
12. **`CircuitConst` is an interface used as a constants bag**, a pre-Java-5
    antipattern; could be a `final class` with `private` constructor. Minor
    modernization opportunity.
13. **`RandomUtils.getRand(int x)` is not thread-safe if shared**: uses
    `Random.nextInt()` (which is thread-safe), but the `-q` trick on
    `Integer.MIN_VALUE` returns a still-negative value (classic bug). For
    `x = 0` throws `ArithmeticException`. Also uses negation not modulo bias
    correction.
14. **`Graphics` is a God-class** (~430 LOC, 60+ methods) mixing forced-color
    state, lifecycle, 2D primitives, text, images, fullscreen, and
    transforms. Splitting is a candidate modernization.
15. **`Graphics.isFullScreen` is a public static mutable boolean** — global
    state; callers reading this have no guarantee it matches the actual
    browser fullscreen state (user can exit with Esc).

## Suggested Concept Boundaries

**Recommendation: SPLIT into five concepts, not one.**

Rationale:
- These 17 files were grouped by *physical location* (the client package
  root), not by *conceptual cohesion*. Their actual consumer graphs differ
  wildly: everyone uses `Point`/`Rectangle`/`Graphics`; only `Scope` uses
  `FFT`; only four elements use `Expr*`.
- Sub-clusters are internally cohesive and have no cross-cluster coupling:
  - Geometry depends on nothing inside this module.
  - Rendering primitives depend on geometry (`Polygon`, `Point`, `Color`)
    plus GWT canvas.
  - Expression engine depends on nothing in this module (apart from the leak
    to `CirSim.console`).
  - Math/DSP depend on nothing in this module.
  - Platform/constants depend on nothing in this module.
- A single "math-and-primitives" concept would be a 17-entry grab-bag with
  no shared invariants and no meaningful "purpose" statement beyond "stuff
  at the client root".

Proposed concept boundaries:

| Concept | Files | Rationale |
|---|---|---|
| `geometry` | `Point`, `Rectangle`, `IntPair`, `Polygon` | Pure value types. Zero dependencies. Universal consumer. |
| `rendering-primitives` | `Color`, `Font`, `Graphics` | GWT canvas façade + visual attributes. Depends on `Polygon`/`Point`. |
| `math-DSP` | `CircuitMath`, `FFT`, `RandomUtils` | Numerical helpers. `StringTokenizer` does **not** belong here. |
| `string-tokenizer` (or merge into `text-io`) | `StringTokenizer` | Orthogonal to math; its consumers are text-format importers. Consider relocating to the `io/text` cluster since it has only a single usage family. |
| `expression-engine` | `Expr`, `ExprParser`, `ExprState` | Self-contained mini-language. Used by exactly 4 custom-source elements. Should be a first-class concept so the opcode-packing invariants (`E_A + 10`, etc.) are visible. |
| `platform-bridge` | `PlatformUtils`, `GWTUtils` | GWT-specific glue, browser/NW.js integration. Likely to grow over time. |
| `circuit-constants` | `CircuitConst` | Bitflags / hint IDs. Shared vocabulary, not a "util". Could also be colocated with I/O (`RC_*` flags) or with `circuit-loader`. |

If the pipeline requires coarser granularity, a reasonable minimum is **three
concepts**: `geometry-and-rendering` (7 files), `math-and-parsing` (4 files
including StringTokenizer), `expression-and-platform` (6 files). But the
five-way split is cleaner and matches the actual import graph best.
