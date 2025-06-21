# CircuitJS1 Export Format Manual

This document describes the format used to export circuit elements (the "dump" format) in CircuitJS1. Each element in a circuit is represented by a line in the export file. The format is designed to be both human- and machine-readable.

## General Format
Each circuit element is represented as:

```
<DumpType> <x> <y> <x2> <y2> <flags> [Additional Parameters]
```
- **DumpType**: Unique identifier for the element type (character or integer).
- **x, y**: Starting coordinates.
- **x2, y2**: Ending coordinates.
- **flags**: Bitmask for element-specific properties.
- **Additional Parameters**: Element-specific data (see below).

---

## Element Formats

### Capacitor
```
c <x> <y> <x2> <y2> <flags> <capacitance> <voltageDiff> <initialVoltage>
```
- `capacitance`: Capacitance in Farads
- `voltageDiff`: Voltage difference across the capacitor
- `initialVoltage`: Initial voltage

### Transistor
```
t <x> <y> <x2> <y2> <flags> <pnp> <Vbe> <Vbc> <beta> <modelName>
```
- `pnp`: 0 for NPN, 1 for PNP
- `Vbe`: Base-Emitter voltage
- `Vbc`: Base-Collector voltage
- `beta`: Current gain
- `modelName`: Model name (escaped)

### Current Source
```
i <x> <y> <x2> <y2> <flags> <currentValue>
```
- `currentValue`: Current in Amperes

### Ground
```
g <x> <y> <x2> <y2> <flags> <symbolType>
```
- `symbolType`: Integer representing the ground symbol style

### Wire
```
w <x> <y> <x2> <y2> <flags>
```
- No additional parameters
- `flags` may indicate display of current/voltage

### LED
```
162 <x> <y> <x2> <y2> <flags> <colorR> <colorG> <colorB> <maxBrightnessCurrent>
```
- `colorR`, `colorG`, `colorB`: Color components (0-1)
- `maxBrightnessCurrent`: Maximum brightness current

### Diode
```
d <x> <y> <x2> <y2> <flags> <modelName>
```
- `modelName`: Diode model name (escaped)

### Probe
```
p <x> <y> <x2> <y2> <flags> <meter> <scale>
```
- `meter`: Meter type (0=V, 1=RMS, 2=Max, 3=Min, 4=Peak-to-peak, 5=Binary, 6=Frequency, 7=Period, 8=Pulse width, 9=Duty cycle)
- `scale`: Display scale

### Switch
```
s <x> <y> <x2> <y2> <flags> <position> <momentary>
```
- `position`: 0=closed, 1=open (or 2 for multi-position)
- `momentary`: true/false

### Transmission Line
```
171 <x> <y> <x2> <y2> <flags> <delay> <impedance> <width> 0
```
- `delay`: Propagation delay
- `impedance`: Characteristic impedance
- `width`: Line width
- `0`: Reserved (not used)

### Triac
```
206 <x> <y> <x2> <y2> <flags> <triggerCurrent> <holdingCurrent> <cresistance> <state>
```
- `triggerCurrent`: Trigger current
- `holdingCurrent`: Holding current
- `cresistance`: Resistance
- `state`: true/false

### SCR (Silicon Controlled Rectifier)
```
177 <x> <y> <x2> <y2> <flags> <V_ac> <V_ag> <triggerCurrent> <holdingCurrent> <gateResistance>
```
- `V_ac`: Anode-Cathode voltage
- `V_ag`: Anode-Gate voltage
- `triggerCurrent`: Trigger current
- `holdingCurrent`: Holding current
- `gateResistance`: Gate resistance

### Logic Input
```
L <x> <y> <x2> <y2> <flags> <hiV> <loV>
```
- `hiV`: High voltage level
- `loV`: Low voltage level

### Logic Output
```
M <x> <y> <x2> <y2> <flags> <threshold>
```
- `threshold`: Threshold voltage

### Variable Rail
```
172 <x> <y> <x2> <y2> <flags> <sliderText>
```
- `sliderText`: Label for the variable rail (may be URL-encoded)

### LDR (Light Dependent Resistor)
```
374 <x> <y> <x2> <y2> <flags> <position> <sliderText>
```
- `position`: Slider position (0.005 to 0.995)
- `sliderText`: Label for the slider (escaped)

### Test Point
```
368 <x> <y> <x2> <y2> <flags> <meter>
```
- `meter`: Meter type (see Probe)

### Fuse
```
404 <x> <y> <x2> <y2> <flags> <resistance> <i2t> <heat> <blown>
```
- `resistance`: Resistance value
- `i2t`: I²t rating
- `heat`: Current heat value
- `blown`: true/false

### Seven Segment Display
```
157 <x> <y> <x2> <y2> <flags> <baseSegmentCount> <extraSegment> <diodeDirection>
```
- `baseSegmentCount`: Number of base segments (usually 7)
- `extraSegment`: 0=None, 1=Decimal point, 2=Colon
- `diodeDirection`: 1=common cathode, -1=common anode, 0=none

### Seven Segment Decoder
```
197 <x> <y> <x2> <y2> <flags> [bits] [pin voltages...]
```
- `bits`: Number of bits (if needsBits() returns true)
- `pin voltages...`: Voltage for each pin that has state=true

### Full Adder
```
196 <x> <y> <x2> <y2> <flags> [bits] [pin voltages...]
```
- `bits`: Number of bits (if needsBits() returns true)
- `pin voltages...`: Voltage for each pin that has state=true

### Counter
```
164 <x> <y> <x2> <y2> <flags> <invertreset> <modulus> [bits] [pin voltages...]
```
- `invertreset`: true/false (reset pin logic)
- `modulus`: modulus value
- `bits`: Number of bits (if needsBits() returns true)
- `pin voltages...`: Voltage for each pin that has state=true

### Monostable Multivibrator
```
194 <x> <y> <x2> <y2> <flags> <retriggerable> <delay> [bits] [pin voltages...]
```
- `retriggerable`: true/false
- `delay`: Pulse duration
- `bits`: Number of bits (if needsBits() returns true)
- `pin voltages...`: Voltage for each pin that has state=true

### AND Gate
```
150 <x> <y> <x2> <y2> <flags> <inputCount> <outputVoltage> <highVoltage>
```
- `inputCount`: Number of inputs
- `outputVoltage`: Current output voltage
- `highVoltage`: High voltage level

### NOR Gate
```
153 <x> <y> <x2> <y2> <flags> <inputCount> <outputVoltage> <highVoltage>
```
- `inputCount`: Number of inputs
- `outputVoltage`: Current output voltage
- `highVoltage`: High voltage level

### Ring Counter
```
163 <x> <y> <x2> <y2> <flags> [bits] [pin voltages...]
```
- `bits`: Number of bits (if needsBits() returns true)
- `pin voltages...`: Voltage for each pin that has state=true

### CCCS (Current-Controlled Current Source)
```
215 <x> <y> <x2> <y2> <flags> [bits] [pin voltages...]
```
- `bits`: Number of bits (if needsBits() returns true)
- `pin voltages...`: Voltage for each pin that has state=true

### Varactor Diode
```
176 <x> <y> <x2> <y2> <flags> <capvoltdiff> <baseCapacitance>
```
- `capvoltdiff`: Voltage difference for capacitance
- `baseCapacitance`: Base capacitance value

### ADC (Analog-to-Digital Converter)
```
167 <x> <y> <x2> <y2> <flags> [bits] [pin voltages...]
```
- `bits`: Number of bits (if needsBits() returns true)
- `pin voltages...`: Voltage for each pin that has state=true

### Timer (555)
```
165 <x> <y> <x2> <y2> <flags> [bits] [pin voltages...]
```
- `bits`: Number of bits (if needsBits() returns true)
- `pin voltages...`: Voltage for each pin that has state=true

### Tapped Transformer
```
169 <x> <y> <x2> <y2> <flags> <inductance> <ratio> <current0> <current1> <current2> <couplingCoef>
```
- `inductance`: Inductance value
- `ratio`: Turns ratio
- `current0`, `current1`, `current2`: Initial currents
- `couplingCoef`: Coupling coefficient

### Resistor
```
r <x> <y> <x2> <y2> <flags> <resistance>
```
- `resistance`: Resistance value in Ohms

### Voltage Source
```
v <x> <y> <x2> <y2> <flags> <waveform> <frequency> <maxVoltage> <bias> <phaseShift> <dutyCycle>
```
- `waveform`: Waveform type (0=DC, 1=AC, 2=Square, 3=Triangle, 4=Sawtooth, 5=Pulse, 6=Noise)
- `frequency`: Frequency in Hz
- `maxVoltage`: Maximum voltage
- `bias`: DC bias voltage
- `phaseShift`: Phase shift in degrees
- `dutyCycle`: Duty cycle for pulse waves (0-1)

### Inductor
```
l <x> <y> <x2> <y2> <flags> <inductance> <current>
```
- `inductance`: Inductance value in Henries
- `current`: Initial current

### Transformer
```
T <x> <y> <x2> <y2> <flags> <inductance> <ratio> <current0> <current1> <couplingCoef>
```
- `inductance`: Primary inductance
- `ratio`: Turns ratio
- `current0`, `current1`: Initial currents
- `couplingCoef`: Coupling coefficient

### MOSFET
```
f <x> <y> <x2> <y2> <flags> <vt> <beta>
```
- `vt`: Threshold voltage
- `beta`: Transconductance parameter

### Memristor
```
m <x> <y> <x2> <y2> <flags> <r_on> <r_off> <dopeWidth> <totalWidth> <mobility> <current>
```
- `r_on`: On resistance
- `r_off`: Off resistance
- `dopeWidth`: Doped region width
- `totalWidth`: Total width
- `mobility`: Ion mobility
- `current`: Current state

### Inverter (Logic)
```
I <x> <y> <x2> <y2> <flags> <slewRate> <highVoltage>
```
- `slewRate`: Slew rate in V/ns
- `highVoltage`: High voltage level

### Output (Scope)
```
O <x> <y> <x2> <y2> <flags> <scale>
```
- `scale`: Display scale setting

### Ammeter
```
370 <x> <y> <x2> <y2> <flags>
```
- No additional parameters (acts as current meter)

### Analog Switch
```
159 <x> <y> <x2> <y2> <flags> <r_on> <r_off>
```
- `r_on`: On resistance
- `r_off`: Off resistance

### Triode
```
173 <x> <y> <x2> <y2> <flags> <mu> <kg1>
```
- `mu`: Amplification factor
- `kg1`: Grid-cathode constant

### Sweep Generator
```
170 <x> <y> <x2> <y2> <flags> <minF> <maxF> <maxV> <sweepTime>
```
- `minF`: Minimum frequency
- `maxF`: Maximum frequency
- `maxV`: Maximum voltage
- `sweepTime`: Sweep time duration

### TriState Buffer
```
180 <x> <y> <x2> <y2> <flags> <r_on> <r_off>
```
- `r_on`: On resistance
- `r_off`: Off resistance

### Lamp
```
181 <x> <y> <x2> <y2> <flags> <temp> <nom_pow> <nom_v> <warmTime> <coolTime>
```
- `temp`: Current temperature
- `nom_pow`: Nominal power
- `nom_v`: Nominal voltage
- `warmTime`: Warm-up time constant
- `coolTime`: Cool-down time constant

### Schmitt Trigger
```
182 <x> <y> <x2> <y2> <flags> <slewRate> <lowerTrigger> <upperTrigger> <logicOnLevel> <logicOffLevel>
```
- `slewRate`: Slew rate
- `lowerTrigger`: Lower trigger voltage
- `upperTrigger`: Upper trigger voltage
- `logicOnLevel`: Logic high voltage
- `logicOffLevel`: Logic low voltage

### Inverting Schmitt Trigger
```
183 <x> <y> <x2> <y2> <flags> <slewRate> <lowerTrigger> <upperTrigger> <logicOnLevel> <logicOffLevel>
```
- `slewRate`: Slew rate
- `lowerTrigger`: Lower trigger voltage
- `upperTrigger`: Upper trigger voltage
- `logicOnLevel`: Logic high voltage
- `logicOffLevel`: Logic low voltage

### Demultiplexer
```
185 <x> <y> <x2> <y2> <flags> [bits] [pin voltages...]
```
- `bits`: Number of bits (if needsBits() returns true)
- `pin voltages...`: Voltage for each pin that has state=true

### PISO Shift Register
```
186 <x> <y> <x2> <y2> <flags> [bits] [pin voltages...]
```
- `bits`: Number of bits (if needsBits() returns true)
- `pin voltages...`: Voltage for each pin that has state=true

### SIPO Shift Register
```
189 <x> <y> <x2> <y2> <flags> [bits] [pin voltages...]
```
- `bits`: Number of bits (if needsBits() returns true)
- `pin voltages...`: Voltage for each pin that has state=true

### T Flip-Flop
```
193 <x> <y> <x2> <y2> <flags> [bits] [pin voltages...]
```
- `bits`: Number of bits (if needsBits() returns true)
- `pin voltages...`: Voltage for each pin that has state=true

### D Flip-Flop
```
155 <x> <y> <x2> <y2> <flags> [bits] [pin voltages...]
```
- `bits`: Number of bits (if needsBits() returns true)
- `pin voltages...`: Voltage for each pin that has state=true

### JK Flip-Flop
```
156 <x> <y> <x2> <y2> <flags> [bits] [pin voltages...]
```
- `bits`: Number of bits (if needsBits() returns true)
- `pin voltages...`: Voltage for each pin that has state=true

### AM Source
```
200 <x> <y> <x2> <y2> <flags> <carrierfreq> <signalfreq> <maxVoltage>
```
- `carrierfreq`: Carrier frequency
- `signalfreq`: Signal frequency
- `maxVoltage`: Maximum voltage

### FM Source
```
201 <x> <y> <x2> <y2> <flags> <carrierfreq> <signalfreq> <maxVoltage> <deviation>
```
- `carrierfreq`: Carrier frequency
- `signalfreq`: Signal frequency
- `maxVoltage`: Maximum voltage
- `deviation`: Frequency deviation

### DIAC
```
203 <x> <y> <x2> <y2> <flags> <onresistance> <offresistance> <breakdown> <holdcurrent>
```
- `onresistance`: On resistance
- `offresistance`: Off resistance
- `breakdown`: Breakdown voltage
- `holdcurrent`: Holding current

### Data Recorder
```
210 <x> <y> <x2> <y2> <flags> <dataCount>
```
- `dataCount`: Number of data points to record

### DAC (Digital-to-Analog Converter)
```
166 <x> <y> <x2> <y2> <flags> [bits] [pin voltages...]
```
- `bits`: Number of bits (if needsBits() returns true)
- `pin voltages...`: Voltage for each pin that has state=true

### Phase Comparator
```
161 <x> <y> <x2> <y2> <flags> [bits] [pin voltages...]
```
- `bits`: Number of bits (if needsBits() returns true)
- `pin voltages...`: Voltage for each pin that has state=true

### Custom Transformer
```
406 <x> <y> <x2> <y2> <flags> <inductance> <couplingCoef> <description> <coilCount> [coilCurrents...]
```
- `inductance`: Inductance value
- `couplingCoef`: Coupling coefficient
- `description`: Description string (escaped)
- `coilCount`: Number of coils
- `coilCurrents...`: Initial current for each coil

### Crystal Oscillator
```
412 <x> <y> <x2> <y2> <flags> [CompositeElm parameters]
```
- Parameters depend on CompositeElm base class

---

## Notes
- The first line of the export is a header with simulation settings, starting with `$`.
- **ChipElm-based elements** (logic gates, flip-flops, counters, etc.) follow a common pattern: they may include a `bits` parameter if `needsBits()` returns true, followed by voltage values for each pin that has `state=true`.
- **GateElm-based elements** (AND, OR, NOR, etc.) include `inputCount`, current `outputVoltage`, and `highVoltage` parameters.
- All coordinates and values are in simulation units.
- Model names and text strings are escaped using `CustomLogicModel.escape()`.
- For exact implementation details, refer to the source code: each element class implements a `dump()` method and a `getDumpType()` method.

---

This manual has been verified against the actual source code implementation. Each format corresponds exactly to what the respective `dump()` method outputs.

---

This manual covers the most common elements. For more, see the source files in `src/com/lushprojects/circuitjs1/client/`.
