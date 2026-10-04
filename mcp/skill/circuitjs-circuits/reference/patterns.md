# Patterns: verified small circuits

Each pattern is an AgentCircuit for `circuit_import`, read back from the app with `circuit_get` (trimmed to the keys that matter) and measured with the run shown. Expected values give the formula and the measured value; the measured value is what this simulator produced. All coordinates are whole cells, sources run from `start` on the ground side to `end` on the supply side, and every probed net is labelled.

To reuse a pattern: create a document, import the block, then change values with `set` edits or extend it with `add` edits. Keep the IDs; probes and checks refer to them.

## Contents

1. Voltage divider
2. RC low-pass filter
3. Series RLC resonance
4. Half-wave rectifier with filter capacitor
5. Common-emitter transistor stage
6. Inverting op-amp stage
7. Non-inverting op-amp stage
8. 555 astable oscillator
9. Logic gate driving an LED
10. LED with a defined model

## 1. Voltage divider

9 V across R1 = 20 kΩ over R2 = 10 kΩ; the output is the middle node.

```json
{"elements": [
  {"id":"V1","type":"VoltageSourceDC","start":{"x":0,"y":8},"end":{"x":0,"y":0},"properties":{"max_voltage":"9 V"}},
  {"id":"W1","type":"Wire","start":{"x":0,"y":0},"end":{"x":6,"y":0}},
  {"id":"R1","type":"Resistor","start":{"x":6,"y":0},"end":{"x":6,"y":4},"properties":{"resistance":"20 kOhm"}},
  {"id":"R2","type":"Resistor","start":{"x":6,"y":4},"end":{"x":6,"y":8},"properties":{"resistance":"10 kOhm"}},
  {"id":"W2","type":"Wire","start":{"x":0,"y":8},"end":{"x":6,"y":8}},
  {"id":"GND1","type":"Ground","start":{"x":0,"y":8},"end":{"x":0,"y":10}},
  {"id":"OUT","type":"LabeledNode","start":{"x":6,"y":4},"end":{"x":9,"y":4},"properties":{"label":"out"}}
]}
```

- **Expected.** V_out = V·R2/(R1 + R2) = 9 · 10k/30k = 3.0 V (measured 3.000 V); I = 9 V/30 kΩ = 0.3 mA (measured 0.300 mA through R2).
- **Run.** `{"span": "1 ms", "reset": true, "probes": [{"net": "out"}, {"element": "R2", "quantity": "current"}]}` → read `stats.final`.
- **Tune.** To reach another V_out, keep R1 + R2 in the same range and solve R2 = R1·V_out/(V − V_out); apply it with `{"op": "set", "id": "R2", "properties": {"resistance": "..."}}` and re-measure.

## 2. RC low-pass filter

A 1 V-amplitude 1 kHz sine into R1 = 1 kΩ and C1 = 100 nF.

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

- **Expected.** f_c = 1/(2π·R·C) = 1.59 kHz. Gain at f: 1/√(1 + (f/f_c)²) = 0.847 at 1 kHz, so `out` peakToPeak = 2 V · 0.847 = 1.693 V (measured 1.693 V). At f = f_c the gain is 0.707.
- **Run.** `{"span": "5 ms", "recordFrom": "2 ms", "reset": true, "probes": [{"net": "in"}, {"net": "out"}]}`; τ = R·C = 0.1 ms, so 2 ms skips the start-up transient. Read `peakToPeak` of both; `frequency` reads 1000 Hz.
- **Design.** For a cut-off f_c pick R, then C = 1/(2π·R·f_c) (1 kHz with 1 kΩ: 159 nF).

## 3. Series RLC resonance

A 1 V-amplitude sine at the resonant frequency drives L1 = 10 mH, C1 = 2.533 µF and R1 = 10 Ω in series; `out` is the voltage across R1.

```json
{"elements": [
  {"id":"V1","type":"VoltageSourceAC","start":{"x":0,"y":4},"end":{"x":0,"y":0},"properties":{"max_voltage":"1 V","frequency":"1 kHz"}},
  {"id":"L1","type":"Inductor","start":{"x":0,"y":0},"end":{"x":4,"y":0},"properties":{"inductance":"10 mH"}},
  {"id":"C1","type":"Capacitor","start":{"x":4,"y":0},"end":{"x":8,"y":0},"properties":{"capacitance":"2.533 uF"}},
  {"id":"R1","type":"Resistor","start":{"x":8,"y":0},"end":{"x":8,"y":4},"properties":{"resistance":"10 Ohm"}},
  {"id":"W1","type":"Wire","start":{"x":0,"y":4},"end":{"x":8,"y":4}},
  {"id":"GND1","type":"Ground","start":{"x":0,"y":4},"end":{"x":0,"y":6}},
  {"id":"OUT","type":"LabeledNode","start":{"x":8,"y":0},"end":{"x":11,"y":0},"properties":{"label":"out"}}
]}
```

- **Expected.** f_0 = 1/(2π·√(L·C)) = 1.000 kHz. At f_0 the reactances cancel: V_R = V_in (`out` peakToPeak measured 2.000 V), I = V/R = 0.1 A amplitude (L1 current peakToPeak 0.200 A). Q = (1/R)·√(L/C) = 6.28, so the capacitor sees Q·V_in: C1 voltage peakToPeak 12.56 V (measured 12.564 V).
- **Run.** `{"span": "40 ms", "recordFrom": "30 ms", "reset": true, "probes": [{"net": "out"}, {"element": "C1", "quantity": "voltage", "name": "vC"}, {"element": "L1", "quantity": "current", "name": "iL"}]}`. The envelope settles with τ = 2L/R = 2 ms; record after 5 τ or more.
- **Check resonance.** Re-run with the source `frequency` 10 % above and below: `out` falls on both sides.

## 4. Half-wave rectifier with filter capacitor

A 10 V-amplitude 50 Hz sine through diode D1 into C1 = 100 µF with a 1 kΩ load. The `simulation` key sets a 20 µs time step for this slow circuit (same results as 5 µs, 3 × faster).

```json
{"elements": [
  {"id":"V1","type":"VoltageSourceAC","start":{"x":0,"y":4},"end":{"x":0,"y":0},"properties":{"max_voltage":"10 V","frequency":"50 Hz"}},
  {"id":"D1","type":"Diode","start":{"x":0,"y":0},"end":{"x":4,"y":0}},
  {"id":"C1","type":"Capacitor","start":{"x":4,"y":0},"end":{"x":4,"y":4},"properties":{"capacitance":"100 uF"}},
  {"id":"W1","type":"Wire","start":{"x":4,"y":0},"end":{"x":8,"y":0}},
  {"id":"R1","type":"Resistor","start":{"x":8,"y":0},"end":{"x":8,"y":4},"properties":{"resistance":"1 kOhm"}},
  {"id":"W2","type":"Wire","start":{"x":0,"y":4},"end":{"x":4,"y":4}},
  {"id":"W3","type":"Wire","start":{"x":4,"y":4},"end":{"x":8,"y":4}},
  {"id":"GND1","type":"Ground","start":{"x":0,"y":4},"end":{"x":0,"y":6}},
  {"id":"OUT","type":"LabeledNode","start":{"x":8,"y":0},"end":{"x":11,"y":0},"properties":{"label":"out"}}
],
 "simulation": {"time_step":"20 us"}}
```

- **Expected.** Peak V_out ≈ V_in − V_D = 10 − 0.6 = 9.4 V (measured max 9.404 V). Ripple ≈ I_load/(f·C) = 8.65 mA/(50 Hz · 100 µF) = 1.73 V, an upper estimate because the capacitor recharges before the full period ends (measured peakToPeak 1.530 V, mean 8.647 V).
- **Run.** `{"span": "300 ms", "recordFrom": "200 ms", "reset": true, "probes": [{"net": "out"}]}`; R·C = 100 ms, so the first 200 ms are skipped. Ripple = `peakToPeak`, DC level = `mean`, ripple frequency = `frequency` (50 Hz).

## 5. Common-emitter transistor stage

An NPN stage on a 12 V rail: divider bias R1 = 47 kΩ / R2 = 10 kΩ, collector resistor RC = 4.7 kΩ, emitter resistors RE1 = 220 Ω (unbypassed) and RE2 = 820 Ω (bypassed by CE = 22 µF), input coupled through CIN = 1 µF from a 0.1 V-amplitude 1 kHz sine.

```json
{"elements": [
  {"id":"VCC","type":"Rail","start":{"x":8,"y":0},"end":{"x":8,"y":-2},"properties":{"max_voltage":"12 V"}},
  {"id":"W1","type":"Wire","start":{"x":2,"y":0},"end":{"x":8,"y":0}},
  {"id":"R1","type":"Resistor","start":{"x":2,"y":0},"end":{"x":2,"y":4},"properties":{"resistance":"47 kOhm"}},
  {"id":"R2","type":"Resistor","start":{"x":2,"y":4},"end":{"x":2,"y":8},"properties":{"resistance":"10 kOhm"}},
  {"id":"W2","type":"Wire","start":{"x":2,"y":8},"end":{"x":2,"y":12}},
  {"id":"RC","type":"Resistor","start":{"x":8,"y":0},"end":{"x":8,"y":3},"properties":{"resistance":"4.7 kOhm"}},
  {"id":"Q1","type":"TransistorNPN","start":{"x":4,"y":4},"end":{"x":8,"y":4}},
  {"id":"W3","type":"Wire","start":{"x":2,"y":4},"end":{"x":4,"y":4}},
  {"id":"RE1","type":"Resistor","start":{"x":8,"y":5},"end":{"x":8,"y":8},"properties":{"resistance":"220 Ohm"}},
  {"id":"RE2","type":"Resistor","start":{"x":8,"y":8},"end":{"x":8,"y":12},"properties":{"resistance":"820 Ohm"}},
  {"id":"W4","type":"Wire","start":{"x":8,"y":8},"end":{"x":10,"y":8}},
  {"id":"CE","type":"Capacitor","start":{"x":10,"y":8},"end":{"x":10,"y":12},"properties":{"capacitance":"22 uF"}},
  {"id":"W5","type":"Wire","start":{"x":8,"y":12},"end":{"x":10,"y":12}},
  {"id":"CIN","type":"Capacitor","start":{"x":-2,"y":4},"end":{"x":2,"y":4},"properties":{"capacitance":"1 uF"}},
  {"id":"VIN","type":"VoltageSourceAC","start":{"x":-2,"y":12},"end":{"x":-2,"y":8},"properties":{"max_voltage":"100 mV","frequency":"1 kHz"}},
  {"id":"W6","type":"Wire","start":{"x":-2,"y":8},"end":{"x":-2,"y":4}},
  {"id":"W7","type":"Wire","start":{"x":-2,"y":12},"end":{"x":2,"y":12}},
  {"id":"W8","type":"Wire","start":{"x":2,"y":12},"end":{"x":8,"y":12}},
  {"id":"GND1","type":"Ground","start":{"x":2,"y":12},"end":{"x":2,"y":14}},
  {"id":"IN","type":"LabeledNode","start":{"x":-2,"y":4},"end":{"x":-5,"y":4},"properties":{"label":"in"}},
  {"id":"OUT","type":"LabeledNode","start":{"x":8,"y":3},"end":{"x":11,"y":3},"properties":{"label":"out"}}
]}
```

- **Expected DC.** V_B ≈ 12 · 10k/57k = 2.1 V, I_E ≈ (V_B − 0.6 V)/(RE1 + RE2) ≈ 1.4 mA, V_C ≈ 12 − RC·I_E ≈ 5.7 V (measured `out` mean 5.695 V).
- **Expected gain.** |A_v| ≈ RC/(RE1 + r_e) with r_e = 26 mV/I_E ≈ 19 Ω: 4.7k/239 ≈ 19.7, inverted. Measured: `out` peakToPeak 4.020 V over `in` 0.200 V = 20.1.
- **Run.** `{"span": "60 ms", "recordFrom": "40 ms", "reset": true, "probes": [{"net": "in"}, {"net": "out"}]}`; the coupling and bypass capacitors settle within about 30 ms.
- **Checks.** The transistor's `current` probe reads I_C (positive, the same as the current of `RC`). If `out` sits near 12 V the transistor is off (check bias); near the emitter voltage it is saturated.

## 6. Inverting op-amp stage

Gain −R2/R1 = −10 with R1 = 10 kΩ and R2 = 100 kΩ; `in+` to ground. The ideal op-amp needs no supply pins.

```json
{"elements": [
  {"id":"V1","type":"VoltageSourceAC","start":{"x":0,"y":8},"end":{"x":0,"y":4},"properties":{"max_voltage":"100 mV","frequency":"1 kHz"}},
  {"id":"W1","type":"Wire","start":{"x":0,"y":4},"end":{"x":4,"y":4}},
  {"id":"R1","type":"Resistor","start":{"x":4,"y":4},"end":{"x":8,"y":4},"properties":{"resistance":"10 kOhm"}},
  {"id":"U1","type":"OpAmp","start":{"x":8,"y":5},"end":{"x":12,"y":5}},
  {"id":"W2","type":"Wire","start":{"x":8,"y":4},"end":{"x":8,"y":1}},
  {"id":"R2","type":"Resistor","start":{"x":8,"y":1},"end":{"x":12,"y":1},"properties":{"resistance":"100 kOhm"}},
  {"id":"W3","type":"Wire","start":{"x":12,"y":1},"end":{"x":12,"y":5}},
  {"id":"W4","type":"Wire","start":{"x":8,"y":6},"end":{"x":8,"y":8}},
  {"id":"W5","type":"Wire","start":{"x":0,"y":8},"end":{"x":8,"y":8}},
  {"id":"GND1","type":"Ground","start":{"x":0,"y":8},"end":{"x":0,"y":10}},
  {"id":"IN","type":"LabeledNode","start":{"x":0,"y":4},"end":{"x":0,"y":2},"properties":{"label":"in"}},
  {"id":"OUT","type":"LabeledNode","start":{"x":12,"y":5},"end":{"x":15,"y":5},"properties":{"label":"out"}}
]}
```

- **Expected.** A_v = −R2/R1 = −10: `in` peakToPeak 0.2 V → `out` 2.0 V (measured 1.9998 V), opposite in sign to `in` at every sample.
- **Run.** `{"span": "5 ms", "recordFrom": "2 ms", "reset": true, "probes": [{"net": "in"}, {"net": "out"}]}`. Gain = ratio of the `peakToPeak` values; the phase shows in the series (or in `final` of both).
- **Limits.** The output clips at `min_output`/`max_output` (±15 V): keep |A_v|·V_in below that.

## 7. Non-inverting op-amp stage

Gain 1 + R2/R1 = 11 with R1 = 1 kΩ to ground and R2 = 10 kΩ feedback, driven by a 0.5 V rail at `in+`.

```json
{"elements": [
  {"id":"U1","type":"OpAmp","start":{"x":8,"y":5},"end":{"x":12,"y":5}},
  {"id":"W1","type":"Wire","start":{"x":8,"y":4},"end":{"x":8,"y":1}},
  {"id":"R2","type":"Resistor","start":{"x":8,"y":1},"end":{"x":12,"y":1},"properties":{"resistance":"10 kOhm"}},
  {"id":"W2","type":"Wire","start":{"x":12,"y":1},"end":{"x":12,"y":5}},
  {"id":"R1","type":"Resistor","start":{"x":4,"y":4},"end":{"x":8,"y":4},"properties":{"resistance":"1 kOhm"}},
  {"id":"GND1","type":"Ground","start":{"x":4,"y":4},"end":{"x":4,"y":6}},
  {"id":"W3","type":"Wire","start":{"x":8,"y":6},"end":{"x":8,"y":9}},
  {"id":"VIN","type":"Rail","start":{"x":8,"y":9},"end":{"x":5,"y":9},"properties":{"max_voltage":"500 mV"}},
  {"id":"OUT","type":"LabeledNode","start":{"x":12,"y":5},"end":{"x":15,"y":5},"properties":{"label":"out"}}
]}
```

- **Expected.** V_out = V_in·(1 + R2/R1) = 0.5 · 11 = 5.5 V (measured 5.4994 V; the finite gain of 100000 takes the rest).
- **Run.** DC only, so use the operating point: `{"mode": "settle", "reset": true, "probes": [{"net": "out"}]}` → `reason: "settled"`, read `stats.final`.

## 8. 555 astable oscillator

A 555 on a 5 V rail with RA = 1 kΩ, RB = 10 kΩ, C1 = 1 µF; `ctl` to ground through C2 = 10 nF, `rst` to the supply, a 1 kΩ load on `out`.

```json
{"elements": [
  {"id":"VCC","type":"Rail","start":{"x":4,"y":0},"end":{"x":4,"y":-2},"properties":{"max_voltage":"5 V"}},
  {"id":"W1","type":"Wire","start":{"x":4,"y":0},"end":{"x":12,"y":0}},
  {"id":"W2","type":"Wire","start":{"x":12,"y":0},"end":{"x":16,"y":0}},
  {"id":"W3","type":"Wire","start":{"x":12,"y":0},"end":{"x":12,"y":2}},
  {"id":"W4","type":"Wire","start":{"x":16,"y":0},"end":{"x":16,"y":6}},
  {"id":"U1","type":"Timer555","start":{"x":8,"y":4},"end":{"x":12,"y":4}},
  {"id":"RA","type":"Resistor","start":{"x":4,"y":0},"end":{"x":4,"y":4},"properties":{"resistance":"1 kOhm"}},
  {"id":"W5","type":"Wire","start":{"x":4,"y":4},"end":{"x":4,"y":6}},
  {"id":"W6","type":"Wire","start":{"x":4,"y":6},"end":{"x":8,"y":6}},
  {"id":"RB","type":"Resistor","start":{"x":4,"y":6},"end":{"x":4,"y":10},"properties":{"resistance":"10 kOhm"}},
  {"id":"W7","type":"Wire","start":{"x":4,"y":10},"end":{"x":8,"y":10}},
  {"id":"W8","type":"Wire","start":{"x":8,"y":10},"end":{"x":8,"y":12}},
  {"id":"C1","type":"Capacitor","start":{"x":4,"y":10},"end":{"x":4,"y":14},"properties":{"capacitance":"1 uF"}},
  {"id":"W9","type":"Wire","start":{"x":4,"y":14},"end":{"x":4,"y":18}},
  {"id":"C2","type":"Capacitor","start":{"x":12,"y":14},"end":{"x":12,"y":18},"properties":{"capacitance":"10 nF"}},
  {"id":"W10","type":"Wire","start":{"x":14,"y":14},"end":{"x":14,"y":18}},
  {"id":"W11","type":"Wire","start":{"x":4,"y":18},"end":{"x":12,"y":18}},
  {"id":"W12","type":"Wire","start":{"x":12,"y":18},"end":{"x":14,"y":18}},
  {"id":"RL","type":"Resistor","start":{"x":16,"y":8},"end":{"x":16,"y":12},"properties":{"resistance":"1 kOhm"}},
  {"id":"W13","type":"Wire","start":{"x":16,"y":12},"end":{"x":16,"y":18}},
  {"id":"W14","type":"Wire","start":{"x":14,"y":18},"end":{"x":16,"y":18}},
  {"id":"GND1","type":"Ground","start":{"x":4,"y":18},"end":{"x":4,"y":20}},
  {"id":"OUT","type":"LabeledNode","start":{"x":16,"y":8},"end":{"x":19,"y":8},"properties":{"label":"out"}}
]}
```

- **Expected.** f = 1.44/((RA + 2·RB)·C) = 68.6 Hz (measured `frequency` 67.9 Hz); duty = (RA + RB)/(RA + 2·RB) = 0.524 (measured `dutyCycle` 0.527). C1 swings between V_cc/3 and 2·V_cc/3: measured 1.667–3.334 V. `out` swings 0–5 V.
- **Run.** `{"span": "200 ms", "recordFrom": "50 ms", "reset": true, "probes": [{"net": "out"}, {"element": "C1", "quantity": "voltage", "name": "vC"}]}`. The first cycle is longer (C1 charges from 0 V), so record from a few periods in; `frequency` needs at least two rising crossings.

## 9. Logic gate driving an LED

Two logic inputs (both high, `position: 1`) into an AND gate; its output drives a red LED through R1 = 330 Ω.

```json
{"elements": [
  {"id":"A","type":"LogicInput","start":{"x":4,"y":3},"end":{"x":1,"y":3},"properties":{"position":1}},
  {"id":"B","type":"LogicInput","start":{"x":4,"y":5},"end":{"x":1,"y":5},"properties":{"position":1}},
  {"id":"W1","type":"Wire","start":{"x":4,"y":3},"end":{"x":8,"y":3}},
  {"id":"W2","type":"Wire","start":{"x":4,"y":5},"end":{"x":8,"y":5}},
  {"id":"U1","type":"ANDGate","start":{"x":8,"y":4},"end":{"x":12,"y":4}},
  {"id":"R1","type":"Resistor","start":{"x":12,"y":4},"end":{"x":16,"y":4},"properties":{"resistance":"330 Ohm"}},
  {"id":"LED1","type":"LED","start":{"x":16,"y":4},"end":{"x":16,"y":8}},
  {"id":"GND1","type":"Ground","start":{"x":16,"y":8},"end":{"x":16,"y":10}},
  {"id":"OUT","type":"LabeledNode","start":{"x":12,"y":4},"end":{"x":12,"y":2},"properties":{"label":"out"}}
]}
```

- **Expected.** Both inputs high: `out` = 5 V, I_LED = (5 V − V_LED)/R1 = (5 − 1.78)/330 = 9.75 mA (measured 9.752 mA, LED voltage 1.782 V). Set one input low (`{"op": "set", "id": "B", "properties": {"position": 0}}`): `out` = 0 V, I_LED = 0.
- **Run.** `{"span": "1 ms", "reset": true, "probes": [{"net": "out"}, {"element": "LED1", "quantity": "current", "name": "iLED"}]}` → read `stats.final` (`min` is 0: the gate output switches on during the first steps).
- **LED current.** For a target current I from a supply V, R = (V − V_LED)/I with V_LED ≈ 1.8 V for the default red LED; confirm with the LED's `current` probe.

## 10. LED with a defined model

A green LED that drops 2.1 V at 20 mA (the built-in `default-led` is red, ≈ 1.8 V): the circuit defines the model in its `models` list and the LED names it. 5 V through R1 = 145 Ω.

```json
{"models": [
  {"kind":"diode","name":"led-green-2v1","parameters":{"forward_voltage":"2.1 V","forward_current":"20 mA"}}
],
"elements": [
  {"id":"V1","type":"VoltageSourceDC","start":{"x":0,"y":8},"end":{"x":0,"y":0},"properties":{"max_voltage":"5 V"}},
  {"id":"W1","type":"Wire","start":{"x":0,"y":0},"end":{"x":6,"y":0}},
  {"id":"R1","type":"Resistor","start":{"x":6,"y":0},"end":{"x":6,"y":4},"properties":{"resistance":"145 Ohm"}},
  {"id":"LED1","type":"LED","start":{"x":6,"y":4},"end":{"x":6,"y":8},"properties":{"model":"led-green-2v1","color_r":0,"color_g":1,"color_b":0}},
  {"id":"W2","type":"Wire","start":{"x":0,"y":8},"end":{"x":6,"y":8}},
  {"id":"GND1","type":"Ground","start":{"x":0,"y":8},"end":{"x":0,"y":10}},
  {"id":"LBL","type":"LabeledNode","start":{"x":6,"y":4},"end":{"x":9,"y":4},"properties":{"label":"led"}}
]}
```

- **Expected.** I = (5 V − 2.1 V)/145 Ω = 20 mA, so the LED sits at its model's point: V(`led`) = 2.1 V (measured 2.100 V; LED current 20.00 mA).
- **Run.** `{"span": "1 ms", "reset": true, "probes": [{"net": "led"}, {"element": "LED1", "quantity": "current", "name": "iLED"}]}` → read `stats.final`.
- **In an existing circuit.** Define and use the model in one batch: `{"edits": [{"op": "defineModel", "model": {"kind": "diode", "name": "led-green-2v1", "parameters": {"forward_voltage": "2.1 V", "forward_current": "20 mA"}}}, {"op": "set", "id": "LED1", "properties": {"model": "led-green-2v1"}}]}`. Running the same batch again answers `existing: true`.
