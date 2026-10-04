# Geometry: cells, posts and connections

## Contents

- Units and axes
- Defining points: `single`, `two_point`, `derived` (NPN, op-amp, 555 worked examples)
- Grid-sized parts
- Connection rule
- Labels and net names
- Layout style
- Worked example: RC low-pass with every coordinate

## Units and axes

- **Cell.** Every coordinate is in grid cells: 1 cell = 16 editor pixels, whatever grid the user has chosen. Range ±4096.
- **Axes.** x grows to the right, y grows **downwards** (a part "above" another has a smaller y).
- **Half-cell lattice.** In `add`, `move` and `by` every coordinate must be a multiple of 0.5. Anything else is rejected with `off_lattice`; input is never rounded or snapped.
- **Whole cells for new parts.** Put every `start` and `end` you write on whole cells. Half cells exist for circuits drawn by hand: imports accept any multiple of 1/16 cell, so an example circuit read back can show values such as `12.5`. Copy them unchanged; do not "fix" them.
- **Derived posts may sit on half cells.** Some derived parts compute posts off the whole-cell grid even from whole-cell points: a `CustomTransformer` with a primary and three secondary sections (`description` `"1:100,40+40"`) at `start` y = 0 has posts at y = −2.5, −4.5 and −6.5. Wire to the `posts` of the reply as they are; a half-cell wire end is fine.
- **Zero length.** `end` equal to `start` is rejected with `zero_length`. So is an `end` that would put the posts of a multi-post part on one point (a transformer or box given no width or height). Leave `end` out to get the type's default size.
- **Axis-bound parts.** Transistors, MOSFETs, JFETs, op-amps, gates, chips, switches with several throws, relays, the tapped transformer, the transmission line and the other parts the editor places only horizontally or vertically reject an `end` that is neither on the row nor on the column of `start` with `not_axis_aligned`. Use `start` + `defaultSize` (or omit `end`). `Transformer` and `CustomTransformer` take a diagonal `end`: it is the corner of their box.
- **Ends a part sets itself.** Some parts keep their own end: a `CustomTransformer` takes its height from its `description`, a `Potentiometer`, `SCR` or `Triac` snaps its end onto its axis or its length step (`end` (6, 1) becomes (6, 0)). When the reply's `end` differs from the one you gave, a `value_adjusted` warning names the effective end; read the posts from the reply.

## Defining points

Every element has two defining points, `start` and `end`. What they mean depends on the type's `geometry`, which `circuit_types {"type": "<name>"}` returns together with `pins` (in post order), `defaultSize` and, for derived types, `derivedPostsAtDefault`.

### `single`: one post

The only post is at `start`; `end` only orients the symbol and its lead. Ground, labelled nodes, rails and logic inputs/outputs are single.
- `{"type": "Ground", "start": {"x": 0, "y": 8}, "end": {"x": 0, "y": 10}}`: the post is at (0, 8); the symbol hangs below it.
- `{"type": "LabeledNode", "start": {"x": 4, "y": 0}, "end": {"x": 7, "y": 0}, "properties": {"label": "out"}}`: the post is at (4, 0), the text sits to the right.

### `two_point`: posts at the two points

Post 0 is at `start` and post 1 at `end`: resistors, capacitors, inductors, wires, diodes, LEDs, sources, switches and the inverter. The pin names follow post order (`pin1`/`pin2`, `a`/`b`, `anode`/`cathode`, `minus`/`plus`, `in`/`out`), so a diode drawn from `start` to `end` conducts from `start` to `end`.

**Polarity of sources and polarised parts.** The pin names state it: a voltage source's `plus` (post 1, at `end`) is its positive terminal; a current source drives its current out of `out` (post 1, at `end`); a polarised capacitor's `positive` is post 0 (at `start`). In every pattern of this skill a source runs from `start` on the ground side to `end` on the supply side, which gives the stated positive values. Still read the circuit once (`circuit_read` the net at the source's `plus`) when a result looks wrong, and check the sign conventions of `current` and `power` on a known case before relying on a sign.

### `derived`: posts computed from the two points

Transistors, MOSFETs, op-amps, potentiometers and chips compute their posts from `start` and `end`. `derivedPostsAtDefault` gives each pin's offset from `start` when `end = start + defaultSize`. The posts of the reply are authoritative: place the part, then read `posts` from the `circuit_edit` (or `circuit_get`) reply before wiring to it. The three examples below are real replies, trimmed to `pin` and `at`.

**Horizontal NPN transistor.** The base is at `start`; collector and emitter sit one cell above and below `end`.

```json
{"op": "add", "element": {"id": "Q1", "type": "TransistorNPN", "start": {"x": 0, "y": 0}, "end": {"x": 4, "y": 0}}}
```
Reply posts: `base` (0, 0), `collector` (4, −1), `emitter` (4, 1).

- Drawn downwards instead (`start` (0, 10), `end` (0, 14)), the posts are `base` (0, 10), `collector` (1, 14), `emitter` (−1, 14): the offsets turn with the element.
- A longer element moves only the far side: `end` (6, 0) gives `collector` (6, −1), `emitter` (6, 1).
- `flags: 1` swaps the collector and emitter sides: `collector` (4, 1), `emitter` (4, −1).
- `TransistorPNP` at default size has `collector` (4, 1), `emitter` (4, −1). `NMOS` and `NJFET` have `gate` (0, 0), `source` (4, 1), `drain` (4, −1); `PMOS` and `PJFET` have `gate` (0, 0), `drain` (4, 1), `source` (4, −1) (source on top: the high-side orientation).

**Op-amp.** The inputs sit one cell above and below `start`; the output is at `end`.

```json
{"op": "add", "element": {"id": "U1", "type": "OpAmp", "start": {"x": 10, "y": 0}, "end": {"x": 14, "y": 0}}}
```
Reply posts: `in-` (10, −1), `in+` (10, 1), `out` (14, 0).

- `start` itself is not a post: nothing connects at (10, 0).
- A longer op-amp (`end` (16, 0)) keeps the inputs at x = 10 and moves `out` to (16, 0).

**8-pin chip (555 timer).** Chips draw a body around their pins; `start` is a corner reference, not a pin. Leave `end` out to get the default size.

```json
{"op": "add", "element": {"id": "U2", "type": "Timer555", "start": {"x": 20, "y": 0}}}
```
Reply: `end` (24, 0); posts `dis` (20, 2), `tr` (20, 6), `th` (20, 8), `Vcc` (24, −2), `ctl` (24, 10), `out` (28, 4), `rst` (28, 2), `gnd` (26, 10).

- Inputs are on the left side (x = start.x), `Vcc` on top, `ctl` and `gnd` at the bottom, `out` and `rst` on the right.
- Other chips follow the same idea with their own offsets: always read the reply.

## Grid-sized parts

- **Standard grid only.** Catalogue sizes and derived pin offsets describe standard-grid (16 px) documents.
- **Small-grid documents.** In a document using the small grid, some parts place their posts differently: potentiometer, SCR, triac, tapped transformer, transmission line, wattmeter and the real op-amp's rail posts.
- **Rule.** Always take post positions from the `posts` of the `circuit_edit`/`circuit_get` reply before wiring, never from the catalogue offsets alone. `circuit_get` → `simulation.display.small_grid` tells which grid a document uses.

## Connection rule

- **Only coincident posts connect.** Two posts join when they are at exactly the same point; any number of posts may meet at one point.
- **A post on a wire body does not connect.** A post lying inside a wire segment (not at its end) is reported as `post_on_wire_body` (error), usually with `dangling_post` and `bad_connection` for the same post. Split the wire at the junction into two wires that meet there, or end the wire at the post.
- **Crossing bodies never connect.** Two wires (or a wire and a part) that cross between their posts do not join: a crossing is not a junction. Two wires crossing, or a wire crossing a part's lead, raise no issue. A wire running through a part's symbol, two symbols overlapping, or a post lying on a part's symbol or lead raise `symbol_overlap` (warning): fix the drawing, since wires and symbols may meet only at posts.
- **Wires.** A wire's two ends are one node. A chain of wires end-to-end is one net.

T-junction, wrong and right:

```json
{"id": "W1", "type": "Wire", "start": {"x": 0, "y": 0}, "end": {"x": 8, "y": 0}},
{"id": "R1", "type": "Resistor", "start": {"x": 4, "y": 0}, "end": {"x": 4, "y": 4}}
```
`R1.pin1` lies on the body of `W1`: `post_on_wire_body`. Fix it by splitting `W1`:

```json
{"id": "W1", "type": "Wire", "start": {"x": 0, "y": 0}, "end": {"x": 4, "y": 0}},
{"id": "W2", "type": "Wire", "start": {"x": 4, "y": 0}, "end": {"x": 8, "y": 0}},
{"id": "R1", "type": "Resistor", "start": {"x": 4, "y": 0}, "end": {"x": 4, "y": 4}}
```

## Labels and net names

- **Same name, same net.** Every `LabeledNode` whose `label` text is the same joins one net, wherever it is drawn. Use it to connect distant points without wires.
- **Label name = net name.** A labelled net is named by its label text (`out`), so probes such as `{"net": "out"}` keep working after edits. The ground net is always `gnd`.
- **Unlabelled nets** are named `$1`, `$2`, …; those numbers change when the circuit changes. Label every net you will probe or report.
- **Reserved texts.** Do not use `gnd`, a text starting with `$` or one starting with `label:` (`reserved_label`).
- **One label alone** gives `single_label` (info): expected for a probe label, harmless.

## Layout style

- **Flow.** Signal flows left to right: inputs and sources on the left, outputs on the right.
- **Rails.** The supply rail sits at the top (small y) and ground at the bottom (large y).
- **Part size.** Parts are 3–4 cells long.
- **Spacing.** Keep 3 cells between parallel parts (4 when values are long, such as `2.2mF`): a value text needs about 1.5 cells beside its symbol, so 2 cells let it touch the neighbour.
- **Wires.** Wires run horizontally or vertically, from post to post.
- **Ground.** A `Ground` lead points down (`end` 2 cells below `start`).
- **Label leads.** A label's lead points away from the parts (right for outputs, left for inputs).

## Worked example: RC low-pass with every coordinate

A 1 V-amplitude 1 kHz sine drives R1 = 1 kΩ into C1 = 100 nF to ground. Cut-off f_c = 1/(2π·R·C) = 1.59 kHz.

```
 (0,-2) in
   |
 (0,0)---R1---(4,0)---- out (7,0)
   |            |
   V1          C1
   |            |
 (0,4)---W1---(4,4)
   |
  GND (0,6)
```

```json
{"elements": [
  {"id":"V1","type":"VoltageSourceAC","start":{"x":0,"y":4},"end":{"x":0,"y":0},"properties":{"max_voltage":"1 V","frequency":"1 kHz"}},
  {"id":"R1","type":"Resistor","start":{"x":0,"y":0},"end":{"x":4,"y":0},"properties":{"resistance":"1 kOhm"}},
  {"id":"C1","type":"Capacitor","start":{"x":4,"y":0},"end":{"x":4,"y":4},"properties":{"capacitance":"100 nF"}},
  {"id":"W1","type":"Wire","start":{"x":0,"y":4},"end":{"x":4,"y":4}},
  {"id":"GND1","type":"Ground","start":{"x":0,"y":4},"end":{"x":0,"y":6}},
  {"id":"IN","type":"LabeledNode","start":{"x":0,"y":0},"end":{"x":0,"y":-2},"properties":{"label":"in"}},
  {"id":"OUT","type":"LabeledNode","start":{"x":4,"y":0},"end":{"x":7,"y":0},"properties":{"label":"out"}}
]}
```

Where the posts meet:
- (0, 0): `V1.#1`, `R1.pin1`, `IN.node` — net `in`.
- (4, 0): `R1.pin2`, `C1.pin1`, `OUT.node` — net `out`.
- (4, 4): `C1.pin2`, `W1.b`.
- (0, 4): `V1.#0`, `W1.a`, `GND1.gnd` — net `gnd`.

The import reports 0 errors and only `single_label` infos. `circuit_run {"span": "5 ms", "recordFrom": "2 ms", "reset": true, "probes": [{"net": "in"}, {"net": "out"}]}` measures `in` peakToPeak 2 V and `out` peakToPeak 1.693 V; the formula gives 2/√(1 + (1 kHz / 1.59 kHz)²) = 1.693 V.
