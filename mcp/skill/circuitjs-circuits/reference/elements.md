# Common element types

The most-used types of the catalogue. Names are the canonical names that `circuit_types` returns; aliases also work as input. Every type here has the default size `end − start` = (4, 0) cells; derived pin offsets are in the geometry reference. Property values are numbers or unit strings (`"4.7k"`, `"10 uF"`, `"5 V"`); records return them as unit strings (`"4.7 kOhm"`).

## Contents

- Table
- Notes per type
- Everything else

## Table

| Type (aliases) | Pins (post order) | Geometry | Key properties (unit) | Typical value |
|---|---|---|---|---|
| `Resistor` | `pin1`, `pin2` | two_point | `resistance` (Ohm) | `"10k"` |
| `Capacitor` | `pin1`, `pin2` | two_point | `capacitance` (F), `initial_voltage` (V), `series_resistance` (Ohm) | `"100n"` |
| `Inductor` | `pin1`, `pin2` | two_point | `inductance` (H), `initial_current` (A) | `"10 mH"` |
| `Wire` | `a`, `b` | two_point | — | — |
| `Ground` | `gnd` | single | `symbol` (text) | — |
| `VoltageSourceDC` (`DCVoltage`, `VoltageSourceVar`) | `minus`, `plus` | two_point | `max_voltage` (V) | `"9 V"` |
| `VoltageSourceAC` (`ACVoltage`) | `minus`, `plus` | two_point | `max_voltage` (V, amplitude), `frequency` (Hz), `dc_offset` (V), `phase_shift` | `"1 V"`, `"1 kHz"` |
| `CurrentSource` (`Current`) | `in`, `out` | two_point | `current` (A) | `"10 mA"` |
| `Diode` | `anode`, `cathode` | two_point | `model` (text) | `"default"` |
| `LED` | `anode`, `cathode` | two_point | `model` (text), `color_r`/`color_g`/`color_b`, `max_brightness_current` (A) | `"default-led"` (red) |
| `ZenerDiode` (`Zener`) | `anode`, `cathode` | two_point | `model` (text); `zener_voltage` (V, read-only) | `"default-zener"` (5.6 V) |
| `TransistorNPN` | `base`, `collector`, `emitter` | derived | `beta`, `model` (text) | `100` |
| `TransistorPNP` | `base`, `collector`, `emitter` | derived | `beta`, `model` (text) | `100` |
| `NMOS` (`MosfetN`, `NMosfet`) | `gate`, `source`, `drain` | derived | `threshold_voltage` (V), `beta`, `body_diode` | `"1.5 V"` |
| `PMOS` (`MosfetP`, `PMosfet`) | `gate`, `source`, `drain` | derived | `threshold_voltage` (V), `beta`, `body_diode` | `"1.5 V"` |
| `NJFET` (`JfetN`) | `gate`, `source`, `drain` | derived | `threshold_voltage` (V, the pinch-off magnitude), `beta` | `"4 V"`, `0.00125` |
| `Transformer` | `p1`, `s1`, `p2`, `s2` | derived | `inductance` (H, primary), `ratio` (N2/N1), `coupling`, `primary_resistance`, `secondary_resistance` (Ohm), `reverse_polarity` | `"4 H"`, `1` |
| `OpAmp` (`OpAmpSwap`) | `in-`, `in+`, `out` | derived | `gain`, `max_output` (V), `min_output` (V), `gbw` (Hz) | `100000`, `"15 V"` |
| `Switch` | `a`, `b` | two_point | `state` (`"closed"`/`"open"`), `momentary` | `"open"` |
| `Potentiometer` | `a`, `b`, `wiper` | derived | `max_resistance` (Ohm), `position` (0..1) | `"10k"`, `0.5` |
| `LabeledNode` | `node` | single | `label` (text) | `"out"` |
| `Rail` | `output` | single | `max_voltage` (V) | `"5 V"` |
| `LogicInput` | `output` | single | `position` (0 low, 1 high), `high_voltage` (V), `low_voltage` (V) | `1` |
| `LogicOutput` | `input` | single | `threshold` (V) | `"2.5 V"` |
| `ANDGate` (`AndGate`) | `in1`, `in2`, `out` | derived | `input_count`, `high_voltage` (V) | `2` |
| `ORGate` (`OrGate`) | `in1`, `in2`, `out` | derived | `input_count`, `high_voltage` (V) | `2` |
| `Inverter` | `in`, `out` | two_point | `high_voltage` (V), `slew_rate` | `"5 V"` |
| `Timer555` (`Timer`) | `dis`, `tr`, `th`, `Vcc`, `ctl`, `out`, `rst`, `gnd` | derived | `high_voltage` (V), `has_reset`, `has_ground_pin` | defaults |

## Notes per type

- **Sources.** `max_voltage` of `VoltageSourceDC` is its voltage. For `VoltageSourceAC` it is the **amplitude** (peak), so a 1 V source gives a 2 V peak-to-peak sine. Square, triangle, sawtooth and pulse sources are `VoltageSourceSquare`, `VoltageSourceTriangle`, `VoltageSourceSawtooth` and `VoltageSourcePulse`, with the same keys (`duty_cycle` matters for square and pulse). A square source swings ±`max_voltage` around `dc_offset`: `max_voltage` 2.5 V with `dc_offset` 2.5 V gives a 0–5 V step.
- **Polarity.** The pin names state it. A voltage source's `plus` is its positive terminal: `plus` is `max_voltage` above `minus`, and the source's `voltage` reads `plus` minus `minus`. A `CurrentSource` pushes `current` out of `out` (the arrow head) into the circuit and takes it back at `in`, so across a load `out` is the high end. `PolarCapacitor`: `positive`, `negative`. `OpAmp`: `in-` is always the inverting input, also with `swap_inputs` (which only mirrors the drawing). Post 0 is at `start`, so the patterns' orientation (`start` on the ground side, `end` on the supply side) puts `plus` / `out` at `end`.
- **Rail.** A single-post source between its post and ground: the cleanest supply. Put it at the top with `end` 2 cells above `start`.
- **Capacitor.** `initial_voltage` defaults to 1 mV, not 0; `reset: true` starts from it. A real part of 1 µF and above is usually an electrolytic: draw a supply filter, decoupling or emitter-bypass capacitor with a DC bias larger than its AC swing as `PolarCapacitor` (`positive` is post 0 at `start`, on the higher DC potential), so the schematic shows its "+". Its `max_reverse_voltage` defaults to 1 V and the model clamps beyond it, so keep `Capacitor` for an unbiased coupling capacitor whose voltage swings both ways (or raise `max_reverse_voltage`).
- **Diodes.** Measured: the `default` diode drops 0.57 V at 10 mA, the `default-led` (red) 1.78 V at 9.8 mA. The Zener voltage follows the model: the built-in `default-zener` is 5.6 V (5.61 V measured at 6.4 mA), and `zener_voltage` cannot be set. The `model` key takes only a model the app holds: `circuit_types {"type": "Diode"}` lists them as the key's `choices` (built-in: `default`, `spice-default`, `1N4148`, `1N4004`, `1N5711` and `1N5712` Schottky, `default-zener`, `default-led`). A `ZenerDiode` takes only the models with a breakdown voltage (its own `choices`). Any other name, such as a part number like "BAT54", is rejected with `invalid_value`; it is never replaced by the default silently. Another Zener voltage or a part not listed needs a custom model, which properties cannot create: say so and pick the closest listed model or a different topology. Transistor `model` keys work the same way.
- **Transistors.** A BJT's `current` is its collector current: positive into `collector` for a conducting NPN, negative for a PNP. A MOSFET's `current` is its drain current. `power` is the whole device's dissipation.
- **Element probes.** A type's TypeInfo lists `quantities`: the `voltage`, `current` and `power` an `{element, quantity}` probe or read accepts, all three or none. Two-post parts, transistors, MOSFETs, JFETs, triacs and crystals have them; transformers, chips, switches with more than two posts and other multi-post parts have none (`invalid_value`): probe their posts or nets instead, and get a transformer's loss from the power balance of the parts around it.
- **MOSFETs.** `beta` is the transconductance parameter (A/V²); `body_diode` adds the drain-source diode.
- **JFETs.** The channel is the MOSFET square-law model with a negative threshold: `threshold_voltage` is the pinch-off magnitude |Vp| as a positive number (a negative value is stored as its magnitude, with `value_adjusted`). The device conducts at Vgs = 0 with Idss = `beta`·Vp²/2 and Ron = 1/(`beta`·|Vp|) at small Vds. Gate-source and gate-drain junctions are default diodes (they conduct when the gate is forward biased); there are no junction capacitances, so add capacitors where an oscillator depends on them.
- **Transformer.** The windings are `p1`–`p2` (primary, `inductance`) and `s1`–`s2` (secondary, `inductance`·`ratio`²). `ratio` is N2/N1, secondary turns per primary turn: `10` steps up 1:10, `0.1` steps down 10:1. `p1` and `s1` are the in-phase (dotted) ends: with the secondary open, V(`s1`) − V(`s2`) = `coupling`·`ratio`·(V(`p1`) − V(`p2`)) (measured: 1 V step, `ratio` 2, `coupling` 0.999 → 1.998 V). `reverse_polarity` swaps where `s1` and `s2` are drawn; `s1` stays the in-phase end. Placed horizontally, `p1` is at `start`, `s1` at `end`, `p2` and `s2` 2 cells below them. `TappedTransformer` (`pri1`, `pri2`, `sec1`, `tap`, `sec2`; `ratio` = whole secondary over primary, tap in the middle) and `CustomTransformer` (`description` sets the windings, pins `pin1`…) follow the same in-phase rule.
- **OpAmp.** An ideal op-amp with internal output limits `min_output`..`max_output` (±15 V by default) and no supply pins: no rails needed. `OpAmpReal` adds `V+`/`V-` supply pins.
- **Switch.** `state` is `"closed"` (conducting, the default) or `"open"`.
- **LogicInput.** Set the level with `position`: `1` high, `0` low (the default). `state` is read-only (it follows `position`).
- **Gates and the 555.** Gate outputs drive their own logic levels (`high_voltage`). A chip output may drive only a label. Tie the 555 `ctl` to ground through 10 nF and `rst` to the supply.
- **Potentiometer.** `position` 0..1 moves the wiper; a grid-sized part (the geometry reference explains).
- **LabeledNode.** A `label` is a net name. `gnd`, texts starting with `$` and texts starting with `label:` are reserved.
- **IDs.** Give your own IDs (`R1`, `C_in`); generated ones use the type's prefix (`R`, `C`, `GND`, `TRA`, `U`, `TIM`…).

## Everything else

For every other type, and for the exact property list, defaults and slider ranges of any type, read `circuitjs://catalogue` (index) and `circuitjs://catalogue/{type}` (TypeInfo), or call `circuit_types {"filter": "<word>"}` / `circuit_types {"type": "<name>"}`.
