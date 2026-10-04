---
source: web research (multiple sources listed below)
fetched: 2026-10-04
trust: public
---

# Schematic drawing guidelines: digest for the layout checker and agent guidance

Scope: how to draw readable circuit diagrams (schematics). This is a digest of standards (IEC, IEEE, ГОСТ/ЕСКД)
and widely cited practitioner guides. Every rule says how strong it is, where it comes from, and whether
it can be checked automatically on the CircuitJS 16 px grid.

**Strength scale.** **M** = mandatory by a standard. **S** = strong convention: near-universal practice that
reviewers expect. **P** = style preference.
Source keys such as [G2701] refer to the list at the end.

**Grid assumption.** One grid cell = 16 px. A drawing scale of 1 cell ≈ 2.5 mm is assumed. This matches the
IEC 61082-1 module M = 2.5 mm [IEC61082] and the 0.1"/2.54 mm pin grid that CAD tools use [KLC].
At a coarser 1 cell ≈ 5 mm scale, every mm-based minimum below is already met at 1 cell.
Unit conversion: 3 mm → 2 cells (32 px) at 2.5 mm/cell, or 1 cell at 5 mm/cell. 2 mm → 1 cell in both cases.

## A. Wires (connecting lines)

1. **Wires are horizontal and vertical segments only. Keep bends to a minimum.** **M** (ESKD), **S** elsewhere.
   ГОСТ 2.701-2008 cl. 5.5.2 (= ГОСТ 2.701-84 cl. 2.5.2): «Линии связи должны состоять из горизонтальных и
   вертикальных отрезков и иметь наименьшее количество изломов и взаимных пересечений». Diagonal segments are
   allowed "in some cases" but must be kept short [G2701]. Horowitz & Hill: "Wires and components are aligned
   horizontally or vertically, unless there's a good reason" [HH].
   *Checkable: yes.* Flag any wire where dx≠0 and dy≠0. Count bends per net; warn when a 2-terminal
   connection has more than 2 bends.

2. **Crossing wires is allowed but should be minimised. A crossing with no dot means "not connected".** **M** (meaning) / **S** (minimise).
   ГОСТ 2.701 cl. 5.5.2 says to keep crossings to a minimum. ГОСТ 2.751-73 (tables 4–5) says unconnected lines cross
   at 90°; a semicircle "hump" is optional, only to show which wire is on top. IEC 60617 has separate symbols
   for a crossing and a junction. Modern practice: "Dots connect, crosses don't" [OL]. A crossing is not a defect.
   *Checkable: yes.* Count crossings: interiors of two wires from different nets intersecting at 90°.
   Report as info/warning, not an error. Rule 3 makes a crossing between different nets always unambiguous.

3. **Never route a wire straight through a junction point or through a post.** **S**.
   A wire end, or an element post, that lands on the interior of another wire is visually ambiguous.
   CircuitJS connects only at coinciding endpoints, so such a point looks connected but is not.
   KLC (KiCad Library Conventions): connection points sit outside the symbol body, and a wire must never have
   to cross the symbol to reach them [KLC].
   *Checkable: yes.* For each post/endpoint P, test whether P lies strictly inside a segment of another wire or
   element. Error if the nets differ (looks connected, is not). Warn if the nets are the same (split the wire there).

4. **Prefer T-junctions. Avoid 4-way (+) junctions; use two T-junctions offset by at least 1 cell.** **S** (contested, see C1).
   Olin Lathrop: "try to keep junctions to Ts, not 4-way crosses ... Make all junctions Ts with dots, and all
   crossing lines are therefore different nets without dots" [OL]. Horowitz & Hill: "Four wires must not
   connect at a point" [HH]. Wikipedia describes the staggered double T as the modern form [WIKI].
   IEC 60617 still defines a "double junction of conductors": S00021 (form 1, built from T-connections
   S00019) and S00022 (form 2, a cross with a junction dot) [IEC60617].
   *Checkable: yes.* Any node with 4 wire stubs meeting at one point from 4 directions is a 4-way junction → warning.
   Fix: shift one branch by ≥1 cell (≥2 cells preferred).

5. **Mark every real junction (3 or more conductors) with a dot.** **S** (IEEE/US practice) / optional at a T in ESKD and IEC (see C2).
   IEC 60617 S00019 (T-connection, form 1, no dot) and S00020 (form 2, with junction dot) are both valid [IEC60617].
   The ЕСКД note on ГОСТ 2.751 (as quoted by secondary sources) says a line with a single branch may be drawn
   without a dot [G2751s].
   *Checkable: yes.* Rendering is automatic in CircuitJS: posts with ≠2 connections get a dot (upstream
   behaviour, verify in updateCircuit). The checker only needs to confirm that junctions happen at endpoints (rule 3).

6. **Keep parallel wires of different nets ≥3 mm apart, i.e. ≥2 cells. Absolute floor: 1 cell; never collinear.** **M** (ESKD numbers).
   ГОСТ 2.701-2008 cl. 5.3.4 (= 2.701-84 cl. 2.3.4): parallel connecting lines ≥3.0 mm apart; separate symbols
   ≥2.0 mm apart; the gap between adjacent lines inside one symbol ≥1.0 mm [G2701]. ГОСТ 2.751-73 table 1 notes:
   lines leaving a group line in different directions ≥2 mm apart; a 45° kink ≥3 mm from the group line [G2751].
   IEC 61082-1 has a "Spacing of lines" figure (Fig. 34/37); the clause text was not accessible [IEC61082].
   *Checkable: yes.* Two parallel segments of different nets that overlap in projection: error at 0 cells
   (collinear overlap); warning at 1 cell when the shared run is ≥4 cells long; target 2 cells.

7. **Draw wires from a pin straight out for at least 1 grid cell before the first bend. Do not double back over the symbol.** **P**.
   Source: practitioner checklist [SCHZ].
   *Checkable: yes.* Look at the first segment leaving each post: its direction must continue the element's lead axis.

8. **Do not draw long wires across the sheet. Use net labels, power or ground symbols, or ports instead. Labels with the same name are connected.** **S**.
   SparkFun: "Nets with the same name are assumed to be connected" [SF]. Olin Lathrop: label every visually split
   net at each segment; keep names short and in UPPER CASE [OL]. ESKD allows line breaks within a sheet when they
   are marked with arrows and the destination is named near the break (ГОСТ 2.701 cl. 5.5.4) [G2701].
   ГОСТ 2.702-2011 cl. 5.4.13 lets lines be omitted when address information is given at the connection
   points [G2702].
   *Checkable: partly.* Flag wires longer than N cells (for example 20) or wires crossing more than K other nets
   (for example 3). Suggest a LabeledNode instead.

9. **Feedback paths run against the main flow (right to left), drawn above or below the forward path.** **S**.
   Olin Lathrop: feedback "should be shown sending information opposite of the main flow" [OL]. Practitioner
   checklist: route feedback right-to-left [SCHZ]. Op-amp feedback is usually drawn above the amplifier.
   *Checkable: weakly.* Detect a net from a stage output back to an earlier input; warn if it passes through the
   forward-path band instead of running above or below it.

## B. Placement and orientation of symbols

10. **Signal flow runs left→right, inputs on the left, outputs on the right. Number and sequence stages top→bottom, left→right.** **S** (IEC/IEEE) / required for designator numbering in ESKD.
    Wikipedia: "organized ... from left to right and top to bottom in the same sequence as the flow of the main
    signal or power path" [WIKI]. IEC 61082-1 has a dedicated figure, Fig. 20/21 "functional grouping and signal
    flow directions" [IEC61082]. ГОСТ 2.702-2011 cl. 5.3.10: designators are assigned «сверху вниз в направлении
    слева направо» [G2702]. Also stated by [HH] and [OL].
    *Checkable: partly.* Sources (V, signal inputs) should have smaller x than loads/outputs (scopes, output
    probes, speakers). Warn if the main output lies left of the main input.

11. **Higher potentials at the top, lower or negative at the bottom. Supply arrows point up; ground symbols point down. Current flows top→bottom.** **S**.
    Wikipedia: positive supplies "towards the top of the page, with grounds, negative supplies, or other return
    paths towards the bottom" [WIKI]. Olin Lathrop: "Power connections should go up to positive voltages and down
    to negative voltages" [OL]. SparkFun: positive nodes are an up arrow, ground is flat lines or a down triangle [SF].
    An upside-down ground triangle can be misread as a +V rail [HRB]. Also [HH].
    *Checkable: yes.* Ground element: its stub must point downward (post above the symbol). Rail/+V element: the
    symbol must sit above its post. For a 2-terminal DC source, the + terminal y should be ≤ the − terminal y
    (warning when the source is vertical and inverted). Warn when a ground symbol is placed above most of the
    circuit nodes it serves.

12. **Symbols are placed horizontally or vertically (rotation in multiples of 90°). A 45° placement is an exception, for example a bridge rectifier drawn as a diamond.** **M** (ESKD, IEC) for multiples of 90°; 45° allowed.
    ГОСТ 2.701-2008 cl. 5.4.4 (= 84 cl. 2.4.4): drawn as in the standard or rotated by a multiple of 90°; a multiple
    of 45° or a mirror image is allowed unless it harms readability. Symbols that contain text may be rotated only
    counter-clockwise by 90°/45° [G2701]. IEC 61082-1 §5.12.3 "Orientation of symbols", Fig. 12 shows
    turning/mirroring [IEC61082].
    *Checkable: yes.* Element axis must have dx=0 or dy=0. Warn on diagonals, except a 4-diode bridge pattern.

13. **No overlap: symbols, wires and text must not touch other symbols. Keep ≥2 mm (≥1 cell) between separate symbols.** **M** (ESKD 2 mm) / **S**.
    ГОСТ 2.701 cl. 5.3.4 [G2701]. Olin Lathrop: text must not "collide with other parts of the drawing" [OL].
    *Checkable: yes.* Bounding boxes of element bodies must not intersect; the gap must be ≥1 cell. A wire segment of
    another net must not cross an element body box (wire through symbol).

14. **Keep functionally related parts together. Leave whitespace between functional blocks. Do not fill the page.** **S**.
    [HH] "keep functional areas distinct"; IEC 61082-1 Fig. 62 "Grouping of symbols for functionally related
    components" [IEC61082]; [SCHZ].
    *Checkable: weakly.* Cluster elements by connectivity and measure the gap between clusters against the gap inside them.

15. **Draw common sub-circuits in their canonical form** (common-emitter stage, divider, op-amp inverting/non-inverting, H-bridge). **S** [OL].
    *Checkable: no* (pattern recognition; possibly later).

16. **Put decoupling and bypass capacitors next to the IC or stage they serve, not grouped in a corner.** **S** [OL], [SCHZ].
    *Checkable: partly.* A capacitor between a supply net and GND should lie within a few cells of a pin of the
    active device on that supply.

17. **IC/block pins go by function: power on top, ground at the bottom, inputs on the left, outputs on the right.** **S**.
    Olin Lathrop: draw pins by function, not physical pin order [OL]. KiCad KLC symbol rules [KLC].
    *Checkable: partly* (for chip/subcircuit elements with known pin roles).

## C. Text: designators, values, labels

18. **Every component has a reference designator and a value, placed next to its symbol.** **M** (designators in ESKD and IEC) / **S**.
    ГОСТ 2.702-2011 cl. 5.3.11: designators «рядом с УГО ... с правой стороны или над ними» (to the right or
    above) [G2702]. IEC 61082-1 §5.20, Fig. 42 "Location of reference designations at a symbol" [IEC61082].
    Horowitz & Hill: "All parts should have values or types indicated" [HH].
    *Checkable: yes.* The label box is within ~1–2 cells of the element body and nearer to it than to any other element.

19. **Text reads horizontally (left to right) and never crosses wires or symbols. Vertical text is avoided in electronics.** **S** (practice) / IEC/ISO: text readable from the bottom or right-hand side.
    Olin Lathrop: "Vertical text looks stupid and makes the schematic hard to read" [OL]. Practitioner checklist:
    "Keep all text horizontal" [SCHZ]. IEC 61082-1 §5.2 "Text orientation" (Fig. 3 viewing directions) allows
    reading from the bottom or the right [IEC61082]. ESKD allows text-bearing symbols rotated CCW only [G2701].
    *Checkable: yes.* Label bounding box ∩ (wires of any net ∪ other element bodies ∪ other labels) = ∅.
    Text angle is 0°; 90° CCW is tolerated.

20. **Placement by orientation: horizontal part → text above (or below). Vertical part → text to the right. Keep text clear of leads.** **P** (convergent: ESKD «справа или над»; [OL] "move the text ... so that it is easily readable, clearly belongs to that part").
    *Checkable: yes* (side test relative to the element axis).

21. **Value notation.** Use SI prefixes, or IEC 60062 RKM code, where the multiplier letter replaces the decimal point
    (4k7, 2R2, 1M5, 4n7). Choose values from E-series (IEC 60063: E6 ±20 %, E12 ±10 %, E24 ±5 %, E96 ±1 %).
    **S** (RKM is common in EU/ESKD-style drawings; "4.7k" is common in US drawings: both fine, but be consistent).
    Sources: [RKM], [ESER]. ГОСТ 2.702-2011 cl. 5.3.20 allows a simplified unit notation for R/C values next to
    the symbol [G2702].
    *Checkable: yes.* Value is parseable. Optionally warn when R/C is not an E24 value (info only).

22. **Net and pin names: short, meaningful, UPPER CASE.** Do not use generic auto names. **S** [OL], [SCHZ].
    *Checkable: yes* (regex/length on LabeledNode text; length ≤ ~8–10 characters).

## D. Polarity and state

23. **Polarised parts show polarity unambiguously.** Electrolytic capacitor: "+" at the positive plate. Diode/LED: the
    cathode bar is visible. Prefer drawing diodes so that conventional current flows top→bottom or left→right.
    **M** (symbol standards: IEC 60617, ГОСТ 2.728/2.730) / **S** (orientation) [SF], [SCHZ].
    *Checkable: partly.* Electrolytic: the + post must be at higher DC potential (simulation check) and drawn above
    or left when possible. Diode in a rectifier/supply: anode up or left (warning only).

24. **Switches, relays and contacts are drawn in the de-energised / "off" state.** **M** (ГОСТ 2.702-2011 cl. 5.3.3) [G2702]; **S** (IEC).
    *Checkable: partly* (initial switch state in the saved file).

## E. Contested points / source differences

- **C1. 4-way junction.** IEC 60617 S00022 explicitly allows a cross with a dot, and ESKD allows it as well.
  US practitioner guides ([OL], [HH], [SCHZ], [WIKI]) say to avoid it or forbid it, because dots get lost in
  copying. Recommendation for agents: never create one. Checker: warning, not error.
- **C2. Dot at a T-junction.** IEC 60617 form 1 (S00019) is a T without a dot, form 2 (S00020) has a dot. ESKD
  allows a single branch without a dot. IEEE/US practice ([OL]: "Draw a dot at every junction"; [SCHZ]) requires
  a dot. CircuitJS draws dots automatically, so this is moot for us.
- **C3. Crossings with humps.** Old US convention: the hump means "not connected" and a plain cross means
  "connected" [LEC]. Modern IEC/IEEE/ESKD: a plain cross means not connected; a hump is optional in ESKD (ГОСТ 2.751
  table 5 note) only to show which wire is on top. Our renderer has no hump, so the modern convention applies.
- **C4. Text orientation.** IEC/ISO allow text read from the right (vertical, rotated CCW). Electronics practice
  ([OL], [SCHZ]) wants all text horizontal. Recommendation: horizontal; vertical text is a warning.
- **C5. Designator side.** ESKD fixes it as right or above. US/IEC only require "near, clearly associated".
  Both are compatible with rule 20.
- **C6. Spacing numbers.** Only ESKD gives mm values (3 mm lines, 2 mm symbols, 1 mm inside a symbol). IEC 61082-1
  works on a module grid (M ≥ 2.5 mm). IEEE 315 and US guides give no numbers; they rely on the CAD grid
  (50/100 mil) [KLC].

## F. Quick map for the automated checker (grid terms, 1 cell = 16 px)

| # | Check | Severity |
|---|---|---|
| 1 | non-orthogonal wire | warn |
| 3 | endpoint/post on another wire's interior (different net) | error |
| 3 | endpoint on same-net wire interior (unsplit T) | warn |
| 4 | 4-way junction at one point | warn |
| 6 | collinear overlap of different nets | error |
| 6 | parallel different-net runs at 1 cell, overlap ≥4 cells | warn (target 2 cells) |
| 13 | element body boxes intersect or gap <1 cell | error / warn |
| 13 | wire of another net crosses an element body | error |
| 19 | label box intersects wire, element or label | warn |
| 11 | ground stub not pointing down; rail symbol below its post; vertical source inverted | warn |
| 12 | element axis diagonal (not a bridge) | warn |
| 2 | crossings count (per circuit) | info |
| 8 | wire length > ~20 cells, or crosses > 3 nets → suggest label | info |
| 10 | main output left of main input | info |
| 18/20 | label far from its element (> 2 cells) or ambiguous owner | warn |

## Sources (accessed 2026-10-04)

- [G2701] ГОСТ 2.701-2008 ЕСКД. Схемы. Общие требования (2008 clause numbers taken from a Wikisource summary; text verified in the 84 edition): https://ru.wikisource.org/wiki/ГОСТ_2.701—2008 ;
  ГОСТ 2.701-84 full text, cl. 2.3.4, 2.4.4, 2.5.1–2.5.4: https://files.stroyinf.ru/Data/205/20523.pdf
- [G2702] ГОСТ 2.702-2011 ЕСКД. Правила выполнения электрических схем: https://ru.wikisource.org/wiki/ГОСТ_2.702—2011
- [G2751] ГОСТ 2.751-73 Электрические связи, провода, кабели и шины: https://files.stroyinf.ru/Data2/1/4293793/4293793834.pdf
- [G2751s] Secondary quote of the ESKD "one branch without dot" note: https://www.nuvis.com.ua/GOST_2.721-74_ESKD.pdf ,
  https://allgosts.ru/01/080/gost_2.751-73 (not verified against the primary text; figures not extractable)
- ГОСТ 2.721-74 (general-use symbols, branch and crossing symbols): https://ru.wikisource.org/wiki/ГОСТ_2.721—74
- Secondary ESKD summaries: https://raschet.info/obshhie-trebovanija-pri-vypolnenii-shem/ , https://libr.aues.kz/facultet/frts/kaf_ig_pm/1/umm/aes_5.htm
- [IEC61082] IEC 61082-1:2006/2014 Preparation of documents used in electrotechnology, Part 1: Rules (TOC/preview only;
  clause titles §5.2 Text orientation, §5.12.3 Orientation of symbols, §5.20 Reference designations, §7.4 Circuit diagrams):
  https://cdn.standards.iteh.ai/samples/12806/d238628bfbf04cdab4d2325ed86f149c/IEC-61082-1-2006.pdf ,
  https://www.vde-verlag.de/iec-normen/preview-pdf/info_iec61082-1%7Bed3.0.RLV%7Den.pdf
- [IEC60617] IEC 60617 database snapshot 2003-01-02 (S00016 junction, S00019/S00020 T-connection, S00021/S00022 double junction):
  http://stigel.free.fr/COURS%20pdf%20ELEC/ELEC1_3_Repr%C3%A9sentation%20et%20schematisation/Symboles%20CEI/IEC60617_Snapshot_2003-01-02.pdf
- IEEE Std 315-1975 / ANSI Y32.2 (symbols and class designation letters; full text not retrieved): https://standards.globalspec.com/std/757254/IEEE%20315
- [OL] Olin Lathrop, "Rules and guidelines for drawing schematics?" (Codidact; also the canonical EE.SE answer):
  https://electrical.codidact.com/posts/278601 , https://electronics.stackexchange.com/questions/28251
- [HH] Horowitz & Hill, The Art of Electronics 2nd ed., Appendix E "How to draw schematic diagrams": https://xcircuit.sourceforge.net/goodschem/goodschem.html
- [WIKI] Wikipedia, "Circuit diagram": https://en.wikipedia.org/wiki/Circuit_diagram
- [SF] SparkFun, "How to Read a Schematic": https://learn.sparkfun.com/tutorials/how-to-read-a-schematic/all
- [SCHZ] Schemalyzer, "Schematic Design Best Practices: 30 Rules": https://www.schemalyzer.com/en/blog/schematic-review/best-practices/schematic-design-best-practices
- [KLC] KiCad Library Conventions / Eeschema docs (50 mil grid, pins outside body): https://klc.kicad.org/ , https://docs.kicad.org/7.0/en/eeschema/eeschema.html
- [HRB] Power rails and ground symbols: https://www.hamradiobase.com/electronics/power-rails-and-ground-symbols/
- [LEC] Lessons in Electric Circuits Vol. V ch. 9 "Wires and connections": https://www.ibiblio.org/kuphaldt/electricCircuits/Ref/REF_9.html
- [RKM] RKM code (IEC 60062): https://en.wikipedia.org/wiki/RKM_code
- [ESER] E series of preferred numbers (IEC 60063): https://en.wikipedia.org/wiki/E_series_of_preferred_numbers
