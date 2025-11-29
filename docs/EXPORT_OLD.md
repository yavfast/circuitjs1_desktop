# CircuitJS1 Export Format Manual

This document describes the format used to export circuit elements (the "dump" format) in CircuitJS1. Each element in a circuit is represented by a line in the export file. The format is designed to be both human- and machine-readable.

## Simulation Settings Header

The first line of every export file contains simulation settings and starts with `$`:

```
$ <flags> <maxTimeStep> <iterCount> <currentBar> <voltageRange> <powerBar> <minTimeStep>
```

- **flags**: Bitmask for simulation options:
  - Bit 0 (1): Show dots for current flow
  - Bit 1 (2): Small grid enabled
  - Bit 2 (4): Hide voltage values (inverted - 0 means show volts)
  - Bit 3 (8): Show power values
  - Bit 4 (16): Hide component values (inverted - 0 means show values)
  - Bit 5 (32): Linear scale (used in afilter)
  - Bit 6 (64): Adjust time step automatically
- **maxTimeStep**: Maximum time step for simulation
- **iterCount**: Iteration count (speed setting)
- **currentBar**: Current bar scale setting
- **voltageRange**: Voltage range for display gradient on wires
- **powerBar**: Power bar scale setting
- **minTimeStep**: Minimum time step for simulation

Example: `$ 1 5.0E-6 15 50 5.0 26 5.0E-9`

## General Format
Each circuit element is represented as:

```
<DumpType> <x> <y> <x2> <y2> <flags> [Additional Parameters] [# Description]
```
- **DumpType**: Unique identifier for the element type (character or integer). If the type identifier is a numeric value less than 127, it is equivalent to its corresponding ASCII character. For example, a Resistor can be represented by `r` or its ASCII value `114`.
- **x, y**: Starting coordinates.
- **x2, y2**: Ending coordinates.
- **flags**: Bitmask for element-specific properties.
- **Additional Parameters**: Element-specific data (see below).
- **Description** (Optional): Description or label for the element.

---

## Element Formats

### Antenna (AntennaElm)
```
A <x> <y> <x2> <y2> <flags>
```
- A simple antenna element.

### Box (Graphical) (BoxElm)
```
b <x> <y> <x2> <y2> <flags>
```
- A graphical box for annotation. No electrical properties.

### Capacitor (CapacitorElm)
```
c <x> <y> <x2> <y2> <flags> <capacitance> <voltageDiff> [initialVoltage]
```
- `capacitance`: Capacitance in Farads
- `voltageDiff`: The present voltage difference across the capacitor.
- `initialVoltage`: Initial voltage to be set on simulation reset (optional).

### Transistor (TransistorElm)
```
t <x> <y> <x2> <y2> <flags> <pnp> <Vbe> <Vbc> <beta> <modelName>
```
- `pnp`: 0 for NPN, 1 for PNP
- `Vbe`: Base-Emitter voltage
- `Vbc`: Base-Collector voltage
- `beta`: Current gain
- `modelName`: Model name (escaped)

### Current Source (CurrentElm)
```
i <x> <y> <x2> <y2> <flags> <currentValue>
```
- `currentValue`: Current in Amperes

### Ground (GroundElm)
```
g <x> <y> <x2> <y2> <flags> <symbolType>
```
- `symbolType`: Integer representing the ground symbol style

### Wire (WireElm)
```
w <x> <y> <x2> <y2> <flags>
```
- No additional parameters
- `flags` may indicate display of current/voltage

### Text Label (TextElm)
```
x <x> <y> <x2> <y2> <flags> <size> <text>
```
- `size`: Font size.
- `text`: The text to display (escaped).

### Zener Diode (ZenerElm)
```
z <x> <y> <x2> <y2> <flags> <breakdown> [modelName]
```
- `breakdown`: Breakdown voltage (numeric).
- `modelName`: An optional model name can be provided as a string.

### Operational Amplifier (Op-Amp) (OpAmpElm)
```
a <x> <y> <x2> <y2> <flags> <maxout> <minout> <gbw>
```
- `maxout`: Maximum output voltage.
- `minout`: Minimum output voltage.
- `gbw`: Gain-bandwidth product.

### LED (LEDElm)
```
162 <x> <y> <x2> <y2> <flags> (<colorR> <colorG> <colorB> | <modelName>) <maxBrightnessCurrent> [forwardVoltage]
```
- This element can be defined in two ways:
  - By color components: `colorR`, `colorG`, `colorB` (numeric values from 0-1).
  - By model name: `modelName` (string).
- `maxBrightnessCurrent`: Maximum brightness current.
- `forwardVoltage`: Optional forward voltage drop.

### Diode (DiodeElm)
```
d <x> <y> <x2> <y2> <flags> <modelName>
```
- `modelName`: Diode model name (escaped)

### JFET Transistor (JfetElm)
```
j <x> <y> <x2> <y2> <flags> <pnp>
```
- `pnp`: 1 for P-channel, -1 for N-channel.

### Inductor (InductorElm)
```
l <x> <y> <x2> <y2> <flags> <inductance> <current> [initialCurrent]
```
- `inductance`: Inductance value in Henries
- `current`: The present current value through the inductor.
- `initialCurrent`: Initial current to be set on simulation reset (optional).

### Noise Source (NoiseElm)
```
n <x> <y> <x2> <y2> <flags> <maxVoltage>
```
- `maxVoltage`: Maximum voltage level.

### Resistor (ResistorElm)
```
r <x> <y> <x2> <y2> <flags> <resistance>
```
- `resistance`: Resistance value in Ohms

### Voltage Rail (RailElm)
```
R <x> <y> <x2> <y2> <flags> <waveform> <frequency> <maxVoltage> <bias> <phaseShift> <dutyCycle>
```
- This is a single-terminal voltage source. Parameters are identical to the standard Voltage Source (`v`).

### 2-Position Switch (SPDT) (Switch2Elm)
```
S <x> <y> <x2> <y2> <flags> <position>
```
- `position`: Switch position (0 or 1).

### Transistor (TransistorElm)
```
t <x> <y> <x2> <y2> <flags> <pnp> <Vbe> <Vbc> <beta> <modelName>
```
- `pnp`: 0 for NPN, 1 for PNP
- `Vbe`: Base-Emitter voltage
- `Vbc`: Base-Collector voltage
- `beta`: Current gain
- `modelName`: Model name (escaped)

### Probe (ProbeElm)
```
p <x> <y> <x2> <y2> <flags> <meter> <scale>
```
- `meter`: Meter type (0=V, 1=RMS, 2=Max, 3=Min, 4=Peak-to-peak, 5=Binary, 6=Frequency, 7=Period, 8=Pulse width, 9=Duty cycle)
- `scale`: Display scale

### Switch (SwitchElm)
```
s <x> <y> <x2> <y2> <flags> <position> <momentary>
```
- `position`: 0=closed, 1=open (or 2 for multi-position)
- `momentary`: true/false

### Voltage-Controlled Oscillator (VCO) (VCOElm)
```
158 <x> <y> <x2> <y2> <flags> <min_freq> <max_freq> <waveshape>
```
- `min_freq`: Minimum frequency.
- `max_freq`: Maximum frequency.
- `waveshape`: Output waveform type.

### Analog Switch (SPDT) (AnalogSwitch2Elm)
```
160 <x> <y> <x2> <y2> <flags> <r_on> <r_off>
```
- `r_on`: On resistance.
- `r_off`: Off resistance.

### Ring Counter (RingCounterElm)
```
163 <x> <y> <x2> <y2> <flags> [bits] [pin voltages...]
```
- `bits`: Number of bits (if needsBits() returns true)
- `pin voltages...`: Voltage for each pin that has state=true

### Transmission Line (TransLineElm)
```
171 <x> <y> <x2> <y2> <flags> <delay> <impedance> <width> 0
```
- `delay`: Propagation delay
- `impedance`: Characteristic impedance
- `width`: Line width
- `0`: Reserved (not used)

### Triac (TriacElm)
```
206 <x> <y> <x2> <y2> <flags> <triggerCurrent> <holdingCurrent> <cresistance> <state>
```
- `triggerCurrent`: Trigger current
- `holdingCurrent`: Holding current
- `cresistance`: Resistance
- `state`: true/false

### SCR (Silicon Controlled Rectifier) (SCRElm)
```
177 <x> <y> <x2> <y2> <flags> <V_ac> <V_ag> <triggerCurrent> <holdingCurrent> <gateResistance>
```
- `V_ac`: Anode-Cathode voltage
- `V_ag`: Anode-Gate voltage
- `triggerCurrent`: Trigger current
- `holdingCurrent`: Holding current
- `gateResistance`: Gate resistance

### Relay (RelayElm)
```
178 <x> <y> <x2> <y2> <flags> <inductance> <onCurrent> <coilCurrent> <poleCount> <contactResistance>
```
- `inductance`: Coil inductance.
- `onCurrent`: Current required to activate the relay.
- `coilCurrent`: Current through the coil.
- `poleCount`: Number of poles.
- `contactResistance`: Resistance of the contacts when closed.

### Current Conveyor (CCII+) (CC2Elm)
```
179 <x> <y> <x2> <y2> <flags> <current>
```
- `current`: Current value.

### Logic Input (LogicInputElm)
```
L <x> <y> <x2> <y2> <flags> <hiV> <loV>
```
- `hiV`: High voltage level
- `loV`: Low voltage level

### Logic Output (LogicOutputElm)
```
M <x> <y> <x2> <y2> <flags> <threshold>
```
- `threshold`: Threshold voltage

### Variable Rail (VarRailElm)
```
172 <x> <y> <x2> <y2> <flags> <sliderText>
```
- `sliderText`: Label for the variable rail (may be URL-encoded)

### LDR (Light Dependent Resistor) (LDRElm)
```
374 <x> <y> <x2> <y2> <flags> <position> <sliderText>
```
- `position`: Slider position (0.005 to 0.995)
- `sliderText`: Label for the slider (escaped)

### Test Point (TestPointElm)
```
368 <x> <y> <x2> <y2> <flags> <meter>
```
- `meter`: Meter type (see Probe)

### Fuse (FuseElm)
```
404 <x> <y> <x2> <y2> <flags> <resistance> <i2t> <heat> <blown>
```
- `resistance`: Resistance value
- `i2t`: I²t rating
- `heat`: Current heat value
- `blown`: true/false

### PISO Shift Register (PisoShiftElm)
```
186 <x> <y> <x2> <y2> <flags> [bits] [pin voltages...]
```
- `bits`: Number of bits (if needsBits() returns true)
- `pin voltages...`: Voltage for each pin that has state=true

### Seven Segment Display (SevenSegElm)
```
157 <x> <y> <x2> <y2> <flags> <baseSegmentCount> <extraSegment> <diodeDirection>
```
- `baseSegmentCount`: Number of base segments (usually 7)
- `extraSegment`: 0=None, 1=Decimal point, 2=Colon
- `diodeDirection`: 1=common cathode, -1=common anode, 0=none

### Seven Segment Decoder (SevenSegDecoderElm)
```
197 <x> <y> <x2> <y2> <flags> [bits] [pin voltages...]
```
- `bits`: Number of bits (if needsBits() returns true)
- `pin voltages...`: Voltage for each pin that has state=true

### Half Adder (HalfAdderElm)
```
195 <x> <y> <x2> <y2> <flags> [bits] [pin voltages...]
```
- `bits`: Number of bits.
- `pin voltages...`: Voltage for each pin that has state=true.

### Full Adder (FullAdderElm)
```
196 <x> <y> <x2> <y2> <flags> [bits] [pin voltages...]
```
- `bits`: Number of bits (if needsBits() returns true)
- `pin voltages...`: Voltage for each pin that has state=true

### Counter (CounterElm)
```
164 <x> <y> <x2> <y2> <flags> <invertreset> <modulus> [bits] [pin voltages...]
```
- `invertreset`: true/false (reset pin logic)
- `modulus`: modulus value
- `bits`: Number of bits (if needsBits() returns true)
- `pin voltages...`: Voltage for each pin that has state=true

### Monostable Multivibrator (MonostableElm)
```
194 <x> <y> <x2> <y2> <flags> <retriggerable> <delay> [bits] [pin voltages...]
```
- `retriggerable`: true/false
- `delay`: Pulse duration
- `bits`: Number of bits (if needsBits() returns true)
- `pin voltages...`: Voltage for each pin that has state=true

### AND Gate (AndGateElm)
```
150 <x> <y> <x2> <y2> <flags> <inputCount> <outputVoltage> <highVoltage>
```
- `inputCount`: Number of inputs
- `outputVoltage`: Current output voltage
- `highVoltage`: High voltage level

### NAND Gate (NandGateElm)
```
151 <x> <y> <x2> <y2> <flags> <inputCount> <outputVoltage> <highVoltage>
```
- See AND Gate for parameter descriptions.

### OR Gate (OrGateElm)
```
152 <x> <y> <x2> <y2> <flags> <inputCount> <outputVoltage> <highVoltage>
```
- See AND Gate for parameter descriptions.

### NOR Gate (NorGateElm)
```
153 <x> <y> <x2> <y2> <flags> <inputCount> <outputVoltage> <highVoltage>
```
- `inputCount`: Number of inputs
- `outputVoltage`: Current output voltage
- `highVoltage`: High voltage level

### XOR Gate (XorGateElm)
```
154 <x> <y> <x2> <y2> <flags> <inputCount> <outputVoltage> <highVoltage>
```
- See AND Gate for parameter descriptions.

### D Flip-Flop (DFlipFlopElm)
```
155 <x> <y> <x2> <y2> <flags> [bits] [pin voltages...]
```
- `bits`: Number of bits (if needsBits() returns true)
- `pin voltages...`: Voltage for each pin that has state=true

### JK Flip-Flop (JKFlipFlopElm)
```
156 <x> <y> <x2> <y2> <flags> [bits] [pin voltages...]
```
- `bits`: Number of bits (if needsBits() returns true)
- `pin voltages...`: Voltage for each pin that has state=true

### AM Source (AMElm)
```
200 <x> <y> <x2> <y2> <flags> <carrierfreq> <signalfreq> <maxVoltage>
```
- `carrierfreq`: Carrier frequency
- `signalfreq`: Signal frequency
- `maxVoltage`: Maximum voltage

### FM Source (FMElm)
```
201 <x> <y> <x2> <y2> <flags> <carrierfreq> <signalfreq> <maxVoltage> <deviation>
```
- `carrierfreq`: Carrier frequency
- `signalfreq`: Signal frequency
- `maxVoltage`: Maximum voltage
- `deviation`: Frequency deviation

### DIAC (DiacElm)
```
203 <x> <y> <x2> <y2> <flags> <onresistance> <offresistance> <breakdown> <holdcurrent>
```
- `onresistance`: On resistance
- `offresistance`: Off resistance
- `breakdown`: Breakdown voltage
- `holdcurrent`: Holding current

### Data Recorder (DataRecorderElm)
```
210 <x> <y> <x2> <y2> <flags> <dataCount>
```
- `dataCount`: Number of data points to record

### DAC (Digital-to-Analog Converter) (DACElm)
```
166 <x> <y> <x2> <y2> <flags> [bits] [pin voltages...]
```
- `bits`: Number of bits (if needsBits() returns true)
- `pin voltages...`: Voltage for each pin that has state=true

### Phase Comparator (PhaseCompElm)
```
161 <x> <y> <x2> <y2> <flags> [bits] [pin voltages...]
```
- `bits`: Number of bits (if needsBits() returns true)
- `pin voltages...`: Voltage for each pin that has state=true

### Latch (LatchElm)
```
168 <x> <y> <x2> <y2> <flags> [bits] [pin voltages...]
```
- `bits`: Number of bits.
- `pin voltages...`: Voltage for each pin that has state=true.

### Tapped Transformer (TappedTransformerElm)
```
169 <x> <y> <x2> <y2> <flags> <inductance> <ratio> <current0> <current1> <current2> <couplingCoef>
```
- `inductance`: Inductance value
- `ratio`: Turns ratio
- `current0`, `current1`, `current2`: Initial currents
- `couplingCoef`: Coupling coefficient

### Potentiometer (PotElm)
```
174 <x> <y> <x2> <y2> <flags> <maxResistance> <position> <resistance>
```
- `maxResistance`: Maximum resistance.
- `position`: Wiper position (0 to 1).
- `resistance`: Current resistance of the wiper.

### Tunnel Diode (TunnelDiodeElm)
```
175 <x> <y> <x2> <y2> <flags> <peakCurrent> <valleyCurrent> <peakVoltage> <valleyVoltage>
```
- `peakCurrent`: Peak current (Ip).
- `valleyCurrent`: Valley current (Iv).
- `peakVoltage`: Peak voltage (Vp).
- `valleyVoltage`: Valley voltage (Vv).

### Varactor Diode (VaractorElm)
```
176 <x> <y> <x2> <y2> <flags> <capvoltdiff> <baseCapacitance>
```
- `capvoltdiff`: Voltage difference for capacitance
- `baseCapacitance`: Base capacitance value

### ADC (Analog-to-Digital Converter) (ADCElm)
```
167 <x> <y> <x2> <y2> <flags> [bits] [pin voltages...]
```
- `bits`: Number of bits (if needsBits() returns true)
- `pin voltages...`: Voltage for each pin that has state=true

### Timer (555) (TimerElm)
```
165 <x> <y> <x2> <y2> <flags> [bits] [pin voltages...]
```
- `bits`: Number of bits (if needsBits() returns true)
- `pin voltages...`: Voltage for each pin that has state=true

### Resistor (ResistorElm)
```
r <x> <y> <x2> <y2> <flags> <resistance>
```
- `resistance`: Resistance value in Ohms

### Voltage Source (VoltageElm)
```
v <x> <y> <x2> <y2> <flags> <waveform> <frequency> <maxVoltage> <bias> <phaseShift> <dutyCycle>
```
- `waveform`: Waveform type (0=DC, 1=AC, 2=Square, 3=Triangle, 4=Sawtooth, 5=Pulse, 6=Noise)
- `frequency`: Frequency in Hz
- `maxVoltage`: Maximum voltage
- `bias`: DC bias voltage
- `phaseShift`: Phase shift in degrees
- `dutyCycle`: Duty cycle for pulse waves (0-1)

### Inductor (InductorElm)
```
l <x> <y> <x2> <y2> <flags> <inductance> <current> [initialCurrent]
```
- `inductance`: Inductance value in Henries
- `current`: The present current value through the inductor.
- `initialCurrent`: Initial current to be set on simulation reset (optional).

### Transformer (TransformerElm)
```
T <x> <y> <x2> <y2> <flags> <inductance> <ratio> <current0> <current1> <couplingCoef>
```
- `inductance`: Primary inductance
- `ratio`: Turns ratio
- `current0`, `current1`: Initial currents
- `couplingCoef`: Coupling coefficient

### MOSFET (MosfetElm)
```
f <x> <y> <x2> <y2> <flags> <vt> <beta>
```
- `vt`: Threshold voltage
- `beta`: Transconductance parameter

### Memristor (MemristorElm)
```
m <x> <y> <x2> <y2> <flags> <r_on> <r_off> <dopeWidth> <totalWidth> <mobility> <current>
```
- `r_on`: On resistance
- `r_off`: Off resistance
- `dopeWidth`: Doped region width
- `totalWidth`: Total width
- `mobility`: Ion mobility
- `current`: Current state

### Inverter (Logic) (InverterElm)
```
I <x> <y> <x2> <y2> <flags> <slewRate> <highVoltage>
```
- `slewRate`: Slew rate in V/ns
- `highVoltage`: High voltage level

### Output (Scope) (OutputElm)
```
O <x> <y> <x2> <y2> <flags> <scale>
```
- `scale`: Display scale setting

### Ammeter (AmmeterElm)
```
370 <x> <y> <x2> <y2> <flags>
```
- No additional parameters (acts as current meter)

### Analog Switch (AnalogSwitchElm)
```
159 <x> <y> <x2> <y2> <flags> <r_on> <r_off>
```
- `r_on`: On resistance
- `r_off`: Off resistance

### Triode (TriodeElm)
```
173 <x> <y> <x2> <y2> <flags> <mu> <kg1>
```
- `mu`: Amplification factor
- `kg1`: Grid-cathode constant

### Sweep Generator (SweepElm)
```
170 <x> <y> <x2> <y2> <flags> <minF> <maxF> <maxV> <sweepTime>
```
- `minF`: Minimum frequency
- `maxF`: Maximum frequency
- `maxV`: Maximum voltage
- `sweepTime`: Sweep time duration

### TriState Buffer (TriStateElm)
```
180 <x> <y> <x2> <y2> <flags> <r_on> <r_off>
```
- `r_on`: On resistance
- `r_off`: Off resistance

### Lamp (LampElm)
```
181 <x> <y> <x2> <y2> <flags> <temp> <nom_pow> <nom_v> <warmTime> <coolTime>
```
- `temp`: Current temperature
- `nom_pow`: Nominal power
- `nom_v`: Nominal voltage
- `warmTime`: Warm-up time constant
- `coolTime`: Cool-down time constant

### Schmitt Trigger (SchmittElm)
```
182 <x> <y> <x2> <y2> <flags> <slewRate> <lowerTrigger> <upperTrigger> <logicOnLevel> <logicOffLevel>
```
- `slewRate`: Slew rate
- `lowerTrigger`: Lower trigger voltage
- `upperTrigger`: Upper trigger voltage
- `logicOnLevel`: Logic high voltage
- `logicOffLevel`: Logic low voltage

### Inverting Schmitt Trigger (InvertingSchmittElm)
```
183 <x> <y> <x2> <y2> <flags> <slewRate> <lowerTrigger> <upperTrigger> <logicOnLevel> <logicOffLevel>
```
- `slewRate`: Slew rate
- `lowerTrigger`: Lower trigger voltage
- `upperTrigger`: Upper trigger voltage
- `logicOnLevel`: Logic high voltage
- `logicOffLevel`: Logic low voltage

### Multiplexer (MultiplexerElm)
```
184 <x> <y> <x2> <y2> <flags> [bits] [pin voltages...]
```
- `bits`: Number of bits.
- `pin voltages...`: Voltage for each pin that has state=true

### Demultiplexer (DeMultiplexerElm)
```
185 <x> <y> <x2> <y2> <flags> [bits] [pin voltages...]
```
- `bits`: Number of bits (if needsBits() returns true)
- `pin voltages...`: Voltage for each pin that has state=true

### Spark Gap (SparkGapElm)
```
187 <x> <y> <x2> <y2> <flags> <breakdown> <resistance>
```
- `breakdown`: Breakdown voltage.
- `resistance`: On-state resistance.

### Sequence Generator (SeqGenElm)
```
188 <x> <y> <x2> <y2> <flags> [bits] [pin voltages...]
```
- `bits`: Number of bits.
- `pin voltages...`: Voltage for each pin that has state=true

### SIPO Shift Register (SipoShiftElm)
```
189 <x> <y> <x2> <y2> <flags> [bits] [pin voltages...]
```
- `bits`: Number of bits (if needsBits() returns true)
- `pin voltages...`: Voltage for each pin that has state=true

### T Flip-Flop (TFlipFlopElm)
```
193 <x> <y> <x2> <y2> <flags> [bits] [pin voltages...]
```
- `bits`: Number of bits (if needsBits() returns true)
- `pin voltages...`: Voltage for each pin that has state=true

### D Flip-Flop (DFlipFlopElm)
```
155 <x> <y> <x2> <y2> <flags> [bits] [pin voltages...]
```
- `bits`: Number of bits (if needsBits() returns true)
- `pin voltages...`: Voltage for each pin that has state=true

### JK Flip-Flop (JKFlipFlopElm)
```
156 <x> <y> <x2> <y2> <flags> [bits] [pin voltages...]
```
- `bits`: Number of bits (if needsBits() returns true)
- `pin voltages...`: Voltage for each pin that has state=true

### AM Source (AMElm)
```
200 <x> <y> <x2> <y2> <flags> <carrierfreq> <signalfreq> <maxVoltage>
```
- `carrierfreq`: Carrier frequency
- `signalfreq`: Signal frequency
- `maxVoltage`: Maximum voltage

### FM Source (FMElm)
```
201 <x> <y> <x2> <y2> <flags> <carrierfreq> <signalfreq> <maxVoltage> <deviation>
```
- `carrierfreq`: Carrier frequency
- `signalfreq`: Signal frequency
- `maxVoltage`: Maximum voltage
- `deviation`: Frequency deviation

### DIAC (DiacElm)
```
203 <x> <y> <x2> <y2> <flags> <onresistance> <offresistance> <breakdown> <holdcurrent>
```
- `onresistance`: On resistance
- `offresistance`: Off resistance
- `breakdown`: Breakdown voltage
- `holdcurrent`: Holding current

### Data Recorder (DataRecorderElm)
```
210 <x> <y> <x2> <y2> <flags> <dataCount>
```
- `dataCount`: Number of data points to record

### Audio Output (AudioOutputElm)
```
211 <x> <y> <x2> <y2> <flags>
```
- Plays audio based on the input voltage. No parameters.

### VCVS (Voltage-Controlled Voltage Source) (VCVSElm)
```
212 <x> <y> <x2> <y2> <flags> <gain>
```
- `gain`: Voltage gain.

### VCCS (Voltage-Controlled Current Source) (VCCSElm)
```
213 <x> <y> <x2> <y2> <flags> <gain>
```
- `gain`: Transconductance (A/V).

### CCVS (Current-Controlled Voltage Source) (CCVSElm)
```
214 <x> <y> <x2> <y2> <flags> <gain>
```
- `gain`: Transresistance (V/A).

### CCCS (Current-Controlled Current Source) (CCCSElm)
```
215 <x> <y> <x2> <y2> <flags> <gain>
```
- `gain`: Current gain.

### Ohmmeter (OhmMeterElm)
```
216 <x> <y> <x2> <y2> <flags>
```
- Measures resistance. No parameters.

### Thermistor (NTC) (ThermistorNTCElm)
```
350 <x> <y> <x2> <y2> <flags> <r_nom> <t_nom> <beta>
```
- `r_nom`: Nominal resistance.
- `t_nom`: Nominal temperature.
- `beta`: Beta coefficient.

### Test Point (TestPointElm)
```
368 <x> <y> <x2> <y2> <flags> <meter>
```
- `meter`: Meter type (see Probe)

### Darlington Pair (DarlingtonElm)
```
400 <x> <y> <x2> <y2> <flags> <pnp> <beta>
```
- `pnp`: 1 for PNP, -1 for NPN.
- `beta`: Combined current gain.

### Comparator (ComparatorElm)
```
401 <x> <y> <x2> <y2> <flags> <slewRate> <hysteresis>
```
- `slewRate`: Slew rate.
- `hysteresis`: Hysteresis voltage.

### OTA (Operational Transconductance Amplifier) (OTAElm)
```
402 <x> <y> <x2> <y2> <flags> <gain>
```
- `gain`: Transconductance gain.

### Scope (In-Circuit) (ScopeElm)
```
403 <x> <y> <x2> <y2> <flags> <scope_data>
```
- This element embeds a scope directly into the circuit canvas.
- `scope_data`: A string representing the state of the embedded scope. It is a serialization of the scope's parameters, with spaces replaced by underscores. For example, it includes information about the trigger, time scale, and displayed waveforms.

### LED Array (LEDArrayElm)
```
405 <x> <y> <x2> <y2> <flags> <count> <led_data>
```
- `count`: Number of LEDs in the array.
- `led_data`: Data for each LED.

### Custom Transformer (CustomTransformerElm)
```
406 <x> <y> <x2> <y2> <flags> <inductance> <couplingCoef> <description> <coilCount> [coilCurrents...]
```
- `inductance`: Inductance value
- `couplingCoef`: Coupling coefficient
- `description`: Description string (escaped)
- `coilCount`: Number of coils
- `coilCurrents...`: Initial current for each coil

### Optocoupler (OptocouplerElm)
```
407 <x> <y> <x2> <y2> <flags> <ctr>
```
- `ctr`: Current Transfer Ratio.

### Stop Trigger (StopTriggerElm)
```
408 <x> <y> <x2> <y2> <flags> <mode> <level>
```
- `mode`: Trigger mode.
- `level`: Trigger voltage level.

### Ideal Op-Amp (OpAmpRealElm)
```
409 <x> <y> <x2> <y2> <flags>
```
- An ideal op-amp with infinite gain. No parameters.

### Custom Composite Element (CustomCompositeElm)
```
410 <x> <y> <x2> <y2> <flags> <model_name>
```
- `model_name`: The name of the custom composite model to use.

### Audio Input (AudioInputElm)
```
411 <x> <y> <x2> <y2> <flags>
```
- Provides an audio signal from a file or microphone. No parameters.

### Crystal Oscillator (CrystalElm)
```
412 <x> <y> <x2> <y2> <flags> [CompositeElm parameters]
```
- Parameters depend on CompositeElm base class

### SRAM (SRAMElm)
```
413 <x> <y> <x2> <y2> <flags> [bits] [pin voltages...]
```
- `bits`: Number of bits (if needsBits() returns true)
- `pin voltages...`: Voltage for each pin that has state=true

### Time Delay Relay (TimeDelayRelayElm)
```
414 <x> <y> <x2> <y2> <flags> <delay> <onCurrent> <offCurrent>
```
- `delay`: Time delay
- `onCurrent`: Turn-on current
- `offCurrent`: Turn-off current

### DC Motor (DCMotorElm)
```
415 <x> <y> <x2> <y2> <flags> <inductance> <resistance> <torqueConstant> <backEmfConstant>
```
- `inductance`: Motor inductance
- `resistance`: Motor resistance
- `torqueConstant`: Torque constant
- `backEmfConstant`: Back EMF constant

### Make-Before-Break Switch (MBBSwitchElm)
```
416 <x> <y> <x2> <y2> <flags> <position>
```
- `position`: Switch position

### Unijunction Transistor (UnijunctionElm)
```
417 <x> <y> <x2> <y2> <flags> <resistance> <ratio> <current> <gateVoltage>
```
- `resistance`: Interbase resistance (Rbb).
- `ratio`: Intrinsic standoff ratio (η).
- `current`: Emitter current.
- `gateVoltage`: Voltage at the gate.

### External Voltage Source (ExtVoltageElm)
```
418 <x> <y> <x2> <y2> <flags> <name>
```
- `name`: Name of the external voltage source.

### Decimal Display (DecimalDisplayElm)
```
419 <x> <y> <x2> <y2> <flags> [pin voltages...]
```
- `pin voltages...`: Voltage for each pin.

### Wattmeter (WattmeterElm)
```
420 <x> <y> <x2> <y2> <flags> <resistance> <loadCurrent> <loadVoltage>
```
- `resistance`: Internal resistance.
- `loadCurrent`: Current through the load.
- `loadVoltage`: Voltage across the load.

### Counter (Alternate) (Counter2Elm)
```
421 <x> <y> <x2> <y2> <flags> <max_state>
```
- `max_state`: Maximum state of the counter.

### Delay Buffer (DelayBufferElm)
```
422 <x> <y> <x2> <y2> <flags> <delay>
```
- `delay`: Propagation delay.

### Line (Graphical) (LineElm)
```
423 <x> <y> <x2> <y2> <flags>
```
- A graphical line for annotation. No electrical properties.

### Data Input (DataInputElm)
```
424 <x> <y> <x2> <y2> <flags> <sequence>
```
- `sequence`: A string of '0's and '1's representing the data sequence.

### Relay Coil (RelayCoilElm)
```
425 <x> <y> <x2> <y2> <flags> <inductance> <on_current> <off_current> <id>
```
- `inductance`: Coil inductance.
- `on_current`: Current required to activate the relay.
- `off_current`: Current at which the relay deactivates.
- `id`: Unique identifier for the coil.

### Relay Contact (RelayContactElm)
```
426 <x> <y> <x2> <y2> <flags> <coil_id> <is_normally_open>
```
- `coil_id`: ID of the corresponding relay coil.
- `is_normally_open`: 1 if normally open, 0 if normally closed.

### Three-Phase Motor (ThreePhaseMotorElm)
```
427 <x> <y> <x2> <y2> <flags> <inductance> <resistance> <torque_constant> <back_emf_constant>
```
- `inductance`: Motor inductance.
- `resistance`: Motor resistance.
- `torque_constant`: Torque constant.
- `back_emf_constant`: Back EMF constant.

### Motor Protection Switch (MotorProtectionSwitchElm)
```
428 <x> <y> <x2> <y2> <flags> <setting>
```
- `setting`: Current setting for thermal protection.

### DPDT Switch (DPDTSwitchElm)
```
429 <x> <y> <x2> <y2> <flags> <position>
```
- `position`: Switch position.

### Cross Switch (CrossSwitchElm)
```
430 <x> <y> <x2> <y2> <flags> <position>
```
- `position`: Switch position (0 for straight, 1 for crossed).

### Polarized Capacitor (PolarCapacitorElm)
```
209 <x> <y> <x2> <y2> <flags> <capacitance> <voltageDiff>
```
- `capacitance`: Capacitance in Farads.
- `voltageDiff`: The present voltage difference across the capacitor.

### Labeled Node (LabeledNodeElm)
```
207 <x> <y> <x2> <y2> <flags> <name>
```
- `name`: The label or name for the node.

---

## Configuration and Model Definitions

Besides circuit elements, the export format includes lines for defining models, setting up scopes, and other configurations. These lines do not represent a visual component on the schematic but provide data for the simulation.

### Scope Settings
```
o [parameters...]
```
- `o`: Defines settings for a scope plot. The position of the scope is determined by its order in the file.
- `parameters...`: Parameters defining the scope's state and appearance, parsed by the `Scope.undump()` method.

### Hint
```
h <type> <item1> <item2>
```
- `h`: Provides a hint to the UI, for example to highlight certain elements.
- `type`: The type of hint.
- `item1`, `item2`: Element identifiers for the hint.

### Custom Logic Model
```
! [model data...]
```
- `!`: Defines a custom logic model using `CustomLogicModel.undumpModel()`.
- `model data...`: The definition of the model.

### Diode Model
```
34 [model data...]
```
- `34`: Defines a model for a diode using `DiodeModel.undumpModel()`.
- `model data...`: The parameters for the diode model (e.g., from a SPICE .model line).

### Transistor Model
```
32 [model data...]
```
- `32`: Defines a model for a transistor using `TransistorModel.undumpModel()`.
- `model data...`: The parameters for the transistor model.

### Adjustable Element
```
38 [parameters...]
```
- `38`: Defines an adjustable value (slider) using `adjustableManager.addAdjustable()`.
- `parameters...`: Parameters for the adjustable element, such as its name, value, and linkage to a circuit element.

### Custom Composite Model
```
. [model data...]
```
- `.`: Defines a custom composite model (subcircuit) using `CustomCompositeModel.undumpModel()`.
- `model data...`: The definition of the composite model.

### AFilter-Specific Data
```
% [data...]
? [data...]
B [data...]
```
- These prefixes are used for data specific to the `afilter` application and are ignored by the standard circuit simulator.

---

## Notes

- **ChipElm-based elements** (logic gates, flip-flops, counters, etc.) follow a common pattern: they may include a `bits` parameter if `needsBits()` returns true, followed by voltage values for each pin that has `state=true`.
- **GateElm-based elements** (AND, OR, NOR, etc.) include `inputCount`, current `outputVoltage`, and `highVoltage` parameters.
- All coordinates and values are in simulation units.
- Model names and text strings are escaped using `CircuitElm.escape()`.
- For exact implementation details, refer to the source code: each element class implements a `dump()` method and a `getDumpType()` method.

---

This manual has been verified against the actual source code implementation (as of June 2025). Each format corresponds exactly to what the respective `dump()` method outputs. This version includes all known circuit elements from the CircuitJS1 source code.

---

For implementation details and the most current information, see the source files in `src/com/lushprojects/circuitjs1/client/element`.
