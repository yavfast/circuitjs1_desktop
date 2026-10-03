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
- **Capacitor.** `initial_voltage` defaults to 1 mV, not 0; `reset: true` starts from it. Use `PolarCapacitor` (`max_reverse_voltage`; `positive` is post 0 at `start`) only when reverse-voltage behaviour matters.
- **Diodes.** Measured: the `default` diode drops 0.57 V at 10 mA, the `default-led` (red) 1.78 V at 9.8 mA. The Zener voltage follows the model: the built-in `default-zener` is 5.6 V (5.61 V measured at 6.4 mA), and `zener_voltage` cannot be set. Other built-in diode models include `1N4148`, `1N4004`, `1N5711`. Another Zener voltage needs a custom model, which properties cannot create: say so and offer 5.6 V or a different topology.
- **Transistors.** The BJT `current` probe reads 0. Measure a transistor's current through the resistor in series with it (collector or emitter resistor). `power` works.
- **MOSFETs.** `beta` is the transconductance parameter (A/V²); `body_diode` adds the drain-source diode.
- **OpAmp.** An ideal op-amp with internal output limits `min_output`..`max_output` (±15 V by default) and no supply pins: no rails needed. `OpAmpReal` adds `V+`/`V-` supply pins.
- **Switch.** `state` is `"closed"` (conducting, the default) or `"open"`.
- **LogicInput.** Set the level with `position`: `1` high, `0` low (the default). `state` follows `position`: a `set` of `state` alone is put back, with a `value_adjusted` warning.
- **Gates and the 555.** Gate outputs drive their own logic levels (`high_voltage`). Give a chip output a load or a part to drive: a 555 `out` that only carries a label is an `isolated_group` error. Tie the 555 `ctl` to ground through 10 nF and `rst` to the supply.
- **Potentiometer.** `position` 0..1 moves the wiper; a grid-sized part (the geometry reference explains).
- **LabeledNode.** A `label` is a net name. `gnd`, texts starting with `$` and texts starting with `label:` are reserved.
- **IDs.** Give your own IDs (`R1`, `C_in`); generated ones use the type's prefix (`R`, `C`, `GND`, `TRA`, `U`, `TIM`…).

## Everything else

For every other type, and for the exact property list, defaults and slider ranges of any type, read `circuitjs://catalogue` (index) and `circuitjs://catalogue/{type}` (TypeInfo), or call `circuit_types {"filter": "<word>"}` / `circuit_types {"type": "<name>"}`.
