# Circuit Elements Documentation

This document provides a comprehensive list of all available circuit elements in CircuitJS1, organized by categories as they appear in the application menu.

## Table of Contents
1. [Basic Elements](#basic-elements)
2. [Passive Components](#passive-components)
3. [Inputs and Sources](#inputs-and-sources)
4. [Outputs and Labels](#outputs-and-labels)
5. [Active Components](#active-components)
6. [Active Building Blocks](#active-building-blocks)
7. [Logic Gates, Input and Output](#logic-gates-input-and-output)
8. [Digital Chips](#digital-chips)
9. [Analog and Hybrid Chips](#analog-and-hybrid-chips)
10. [Subcircuits](#subcircuits)
11. [Drag Tools](#drag-tools)
12. [Selection Tools](#selection-tools)

---

## Basic Elements

### Wire
- **Class:** `WireElm`
- **Description:** Basic electrical connection between components

### Resistor
- **Class:** `ResistorElm`
- **Description:** Basic resistive component that opposes current flow

---

## Passive Components

### Capacitive Elements
- **Capacitor**
  - **Class:** `CapacitorElm`
  - **Description:** Basic capacitor for storing electrical charge
- **Capacitor (polarized)**
  - **Class:** `PolarCapacitorElm`
  - **Description:** Polarized capacitor (electrolytic) with positive and negative terminals

### Inductive Elements
- **Inductor**
  - **Class:** `InductorElm`
  - **Description:** Coil that stores energy in a magnetic field

### Switching Elements
- **Switch**
  - **Class:** `SwitchElm`
  - **Description:** Basic on/off switch
- **Push Switch**
  - **Class:** `PushSwitchElm`
  - **Description:** Momentary contact switch
- **SPDT Switch**
  - **Class:** `Switch2Elm`
  - **Description:** Single Pole Double Throw switch
- **DPDT Switch**
  - **Class:** `DPDTSwitchElm`
  - **Description:** Double Pole Double Throw switch
- **Make-Before-Break Switch**
  - **Class:** `MBBSwitchElm`
  - **Description:** Switch that makes new connection before breaking old one
- **Cross Switch**
  - **Class:** `CrossSwitchElm`
  - **Description:** Four-terminal switching element

### Variable Elements
- **Potentiometer**
  - **Class:** `PotElm`
  - **Description:** Variable resistor with adjustable resistance

### Transformers and Transmission
- **Transformer**
  - **Class:** `TransformerElm`
  - **Description:** Basic two-winding transformer
- **Tapped Transformer**
  - **Class:** `TappedTransformerElm`
  - **Description:** Transformer with center tap
- **Custom Transformer**
  - **Class:** `CustomTransformerElm`
  - **Description:** Transformer with customizable parameters
- **Transmission Line**
  - **Class:** `TransLineElm`
  - **Description:** Electrical transmission line model

### Relay Components
- **Relay**
  - **Class:** `RelayElm`
  - **Description:** Complete relay with coil and contacts
- **Relay Coil**
  - **Class:** `RelayCoilElm`
  - **Description:** Electromagnetic coil part of relay
- **Relay Contact**
  - **Class:** `RelayContactElm`
  - **Description:** Contact part of relay

### Specialty Resistive Elements
- **Photoresistor**
  - **Class:** `LDRElm`
  - **Description:** Light-dependent resistor (LDR)
- **Thermistor**
  - **Class:** `ThermistorNTCElm`
  - **Description:** Temperature-dependent resistor (NTC type)
- **Memristor**
  - **Class:** `MemristorElm`
  - **Description:** Memory resistor with variable resistance based on history

### Protection Elements
- **Spark Gap**
  - **Class:** `SparkGapElm`
  - **Description:** Voltage-controlled switch for overvoltage protection
- **Fuse**
  - **Class:** `FuseElm`
  - **Description:** Current-limiting protection device

### Frequency Elements
- **Crystal**
  - **Class:** `CrystalElm`
  - **Description:** Quartz crystal oscillator

---

## Inputs and Sources

### Voltage Sources
- **Ground**
  - **Class:** `GroundElm`
  - **Description:** Reference point for voltage measurements
- **Voltage Source (2-terminal)**
  - **Class:** `DCVoltageElm`
  - **Description:** DC voltage source with two terminals
- **A/C Voltage Source (2-terminal)**
  - **Class:** `ACVoltageElm`
  - **Description:** AC voltage source with two terminals
- **Voltage Source (1-terminal)**
  - **Class:** `RailElm`
  - **Description:** DC voltage rail with one terminal
- **A/C Voltage Source (1-terminal)**
  - **Class:** `ACRailElm`
  - **Description:** AC voltage rail with one terminal
- **Square Wave Source (1-terminal)**
  - **Class:** `SquareRailElm`
  - **Description:** Square wave voltage source
- **Variable Voltage**
  - **Class:** `VarRailElm`
  - **Description:** Adjustable voltage source

### Signal Sources
- **Clock**
  - **Class:** `ClockElm`
  - **Description:** Digital clock signal generator
- **A/C Sweep**
  - **Class:** `SweepElm`
  - **Description:** Frequency sweep AC source
- **AM Source**
  - **Class:** `AMElm`
  - **Description:** Amplitude modulated signal source
- **FM Source**
  - **Class:** `FMElm`
  - **Description:** Frequency modulated signal source
- **Noise Generator**
  - **Class:** `NoiseElm`
  - **Description:** Random noise signal generator

### Current Sources
- **Current Source**
  - **Class:** `CurrentElm`
  - **Description:** Constant current source

### Input/Output Interfaces
- **Antenna**
  - **Class:** `AntennaElm`
  - **Description:** RF antenna element
- **Audio Input**
  - **Class:** `AudioInputElm`
  - **Description:** Audio input interface
- **Data Input**
  - **Class:** `DataInputElm`
  - **Description:** Digital data input
- **External Voltage (JavaScript)**
  - **Class:** `ExtVoltageElm`
  - **Description:** Voltage source controlled by JavaScript

---

## Outputs and Labels

### Display Elements
- **LED**
  - **Class:** `LEDElm`
  - **Description:** Light-emitting diode
- **Lamp**
  - **Class:** `LampElm`
  - **Description:** Incandescent lamp
- **LED Array**
  - **Class:** `LEDArrayElm`
  - **Description:** Array of LEDs
- **Decimal Display**
  - **Class:** `DecimalDisplayElm`
  - **Description:** Numeric display for digital values

### Annotation Elements
- **Text**
  - **Class:** `TextElm`
  - **Description:** Text label for circuit annotation
- **Box**
  - **Class:** `BoxElm`
  - **Description:** Rectangular box for grouping elements
- **Line**
  - **Class:** `LineElm`
  - **Description:** Line for circuit annotation
- **Labeled Node**
  - **Class:** `LabeledNodeElm`
  - **Description:** Named connection point

### Measurement Instruments
- **Analog Output**
  - **Class:** `OutputElm`
  - **Description:** Analog output interface
- **Voltmeter/Scope Probe**
  - **Class:** `ProbeElm`
  - **Description:** Voltage measurement probe
- **Ohmmeter**
  - **Class:** `OhmMeterElm`
  - **Description:** Resistance measurement instrument
- **Ammeter**
  - **Class:** `AmmeterElm`
  - **Description:** Current measurement instrument  
- **Wattmeter**
  - **Class:** `WattmeterElm`
  - **Description:** Power measurement instrument
- **Test Point**
  - **Class:** `TestPointElm`
  - **Description:** Circuit test point for measurements

### Data and Control
- **Data Export**
  - **Class:** `DataRecorderElm`
  - **Description:** Data recording and export functionality
- **Audio Output**
  - **Class:** `AudioOutputElm`
  - **Description:** Audio output interface
- **Stop Trigger**
  - **Class:** `StopTriggerElm`
  - **Description:** Simulation stop trigger

### Motors
- **DC Motor**
  - **Class:** `DCMotorElm`
  - **Description:** Direct current motor
- **3-Phase Motor**
  - **Class:** `ThreePhaseMotorElm`
  - **Description:** Three-phase AC motor

---

## Active Components

### Diodes
- **Diode**
  - **Class:** `DiodeElm`
  - **Description:** Basic semiconductor diode
- **Zener Diode**
  - **Class:** `ZenerElm`
  - **Description:** Voltage regulation diode
- **Varactor/Varicap**
  - **Class:** `VaractorElm`
  - **Description:** Variable capacitance diode
- **Tunnel Diode**
  - **Class:** `TunnelDiodeElm`
  - **Description:** Negative resistance diode

### Bipolar Transistors
- **Transistor (bipolar, NPN)**
  - **Class:** `NTransistorElm`
  - **Description:** NPN bipolar junction transistor
- **Transistor (bipolar, PNP)**
  - **Class:** `PTransistorElm`
  - **Description:** PNP bipolar junction transistor
- **Darlington Pair (NPN)**
  - **Class:** `NDarlingtonElm`
  - **Description:** NPN Darlington transistor pair
- **Darlington Pair (PNP)**
  - **Class:** `PDarlingtonElm`
  - **Description:** PNP Darlington transistor pair

### Field Effect Transistors
- **MOSFET (N-Channel)**
  - **Class:** `NMosfetElm`
  - **Description:** N-channel MOSFET
- **MOSFET (P-Channel)**
  - **Class:** `PMosfetElm`
  - **Description:** P-channel MOSFET
- **JFET (N-Channel)**
  - **Class:** `NJfetElm`
  - **Description:** N-channel junction FET
- **JFET (P-Channel)**
  - **Class:** `PJfetElm`
  - **Description:** P-channel junction FET

### Thyristors
- **SCR**
  - **Class:** `SCRElm`
  - **Description:** Silicon controlled rectifier
- **DIAC**
  - **Class:** `DiacElm`
  - **Description:** Bidirectional trigger diode
- **TRIAC**
  - **Class:** `TriacElm`
  - **Description:** Bidirectional thyristor

### Vacuum Tubes
- **Triode**
  - **Class:** `TriodeElm`
  - **Description:** Three-electrode vacuum tube

### Specialty Transistors
- **Unijunction Transistor**
  - **Class:** `UnijunctionElm`
  - **Description:** Single junction transistor with unique characteristics

---

## Active Building Blocks

### Operational Amplifiers
- **Op Amp (ideal, - on top)**
  - **Class:** `OpAmpElm`
  - **Description:** Ideal operational amplifier with inverting input on top
- **Op Amp (ideal, + on top)**
  - **Class:** `OpAmpSwapElm`
  - **Description:** Ideal operational amplifier with non-inverting input on top
- **Op Amp (real)**
  - **Class:** `OpAmpRealElm`
  - **Description:** Realistic operational amplifier model

### Analog Switches
- **Analog Switch (SPST)**
  - **Class:** `AnalogSwitchElm`
  - **Description:** Single pole single throw analog switch
- **Analog Switch (SPDT)**
  - **Class:** `AnalogSwitch2Elm`
  - **Description:** Single pole double throw analog switch

### Signal Processing
- **Tristate Buffer**
  - **Class:** `TriStateElm`
  - **Description:** Three-state digital buffer
- **Schmitt Trigger**
  - **Class:** `SchmittElm`
  - **Description:** Non-inverting Schmitt trigger
- **Schmitt Trigger (Inverting)**
  - **Class:** `InvertingSchmittElm`
  - **Description:** Inverting Schmitt trigger
- **Delay Buffer**
  - **Class:** `DelayBufferElm`
  - **Description:** Digital signal delay element
- **Comparator (Hi-Z/GND output)**
  - **Class:** `ComparatorElm`
  - **Description:** Voltage comparator with high-impedance/ground output

### Current Conveyors
- **CCII+**
  - **Class:** `CC2Elm`
  - **Description:** Positive second-generation current conveyor
- **CCII-**
  - **Class:** `CC2NegElm`
  - **Description:** Negative second-generation current conveyor

### Transconductance Elements
- **OTA (LM13700 style)**
  - **Class:** `OTAElm`
  - **Description:** Operational transconductance amplifier

### Controlled Sources
- **Voltage-Controlled Voltage Source (VCVS)**
  - **Class:** `VCVSElm`
  - **Description:** Voltage-controlled voltage source
- **Voltage-Controlled Current Source (VCCS)**
  - **Class:** `VCCSElm`
  - **Description:** Voltage-controlled current source
- **Current-Controlled Voltage Source (CCVS)**
  - **Class:** `CCVSElm`
  - **Description:** Current-controlled voltage source
- **Current-Controlled Current Source (CCCS)**
  - **Class:** `CCCSElm`
  - **Description:** Current-controlled current source

### Isolation and Protection
- **Optocoupler**
  - **Class:** `OptocouplerElm`
  - **Description:** Optical isolation device
- **Time Delay Relay**
  - **Class:** `TimeDelayRelayElm`
  - **Description:** Relay with time delay functionality
- **Motor Protection Switch**
  - **Class:** `MotorProtectionSwitchElm`
  - **Description:** Motor overcurrent protection device

### Integrated Circuits
- **LM317**
  - **Class:** `CustomCompositeElm:~LM317-v2`
  - **Description:** Adjustable voltage regulator IC
- **TL431**
  - **Class:** `CustomCompositeElm:~TL431`
  - **Description:** Programmable shunt regulator

### Custom Elements
- **Subcircuit Instance**
  - **Class:** `CustomCompositeElm`
  - **Description:** Instance of a user-defined subcircuit

---

## Logic Gates, Input and Output

### Digital I/O
- **Logic Input**
  - **Class:** `LogicInputElm`
  - **Description:** Digital logic input source
- **Logic Output**
  - **Class:** `LogicOutputElm`
  - **Description:** Digital logic output indicator

### Basic Logic Gates
- **Inverter**
  - **Class:** `InverterElm`
  - **Description:** NOT gate (inverter)
- **AND Gate**
  - **Class:** `AndGateElm`
  - **Description:** Logical AND gate
- **OR Gate**
  - **Class:** `OrGateElm`
  - **Description:** Logical OR gate
- **NAND Gate**
  - **Class:** `NandGateElm`
  - **Description:** Logical NAND gate (NOT-AND)
- **NOR Gate**
  - **Class:** `NorGateElm`
  - **Description:** Logical NOR gate (NOT-OR)
- **XOR Gate**
  - **Class:** `XorGateElm`
  - **Description:** Exclusive OR gate

---

## Digital Chips

### Flip-Flops
- **D Flip-Flop**
  - **Class:** `DFlipFlopElm`
  - **Description:** Data (D-type) flip-flop
- **JK Flip-Flop**
  - **Class:** `JKFlipFlopElm`
  - **Description:** JK flip-flop with set and reset
- **T Flip-Flop**
  - **Class:** `TFlipFlopElm`
  - **Description:** Toggle (T-type) flip-flop

### Display and Decoding
- **7 Segment LED**
  - **Class:** `SevenSegElm`
  - **Description:** Seven-segment LED display
- **7 Segment Decoder**
  - **Class:** `SevenSegDecoderElm`
  - **Description:** BCD to seven-segment decoder

### Data Routing
- **Multiplexer**
  - **Class:** `MultiplexerElm`
  - **Description:** Digital multiplexer (data selector)
- **Demultiplexer**
  - **Class:** `DeMultiplexerElm`
  - **Description:** Digital demultiplexer (data distributor)

### Shift Registers
- **SIPO shift register**
  - **Class:** `SipoShiftElm`
  - **Description:** Serial-in parallel-out shift register
- **PISO shift register**
  - **Class:** `PisoShiftElm`
  - **Description:** Parallel-in serial-out shift register

### Counters
- **Counter**
  - **Class:** `CounterElm`
  - **Description:** Basic digital counter
- **Counter w/ Load**
  - **Class:** `Counter2Elm`
  - **Description:** Counter with parallel load capability
- **Ring Counter**
  - **Class:** `DecadeElm`
  - **Description:** Decade/ring counter

### Memory and Logic
- **Latch**
  - **Class:** `LatchElm`
  - **Description:** Digital latch (transparent when enabled)
- **Sequence generator**
  - **Class:** `SeqGenElm`
  - **Description:** Programmable sequence generator
- **Static RAM**
  - **Class:** `SRAMElm`
  - **Description:** Static random access memory

### Arithmetic
- **Adder**
  - **Class:** `FullAdderElm`
  - **Description:** Full binary adder
- **Half Adder**
  - **Class:** `HalfAdderElm`
  - **Description:** Half binary adder

### Custom Logic
- **Custom Logic**
  - **Class:** `UserDefinedLogicElm`
  - **Description:** User-programmable logic element

---

## Analog and Hybrid Chips

### Timers
- **555 Timer**
  - **Class:** `TimerElm`
  - **Description:** 555 timer IC in various configurations
- **Monostable**
  - **Class:** `MonostableElm`
  - **Description:** Monostable multivibrator (one-shot)

### Signal Processing
- **Phase Comparator**
  - **Class:** `PhaseCompElm`
  - **Description:** Phase/frequency detector
- **VCO**
  - **Class:** `VCOElm`
  - **Description:** Voltage-controlled oscillator

### Conversion
- **DAC**
  - **Class:** `DACElm`
  - **Description:** Digital-to-analog converter
- **ADC**
  - **Class:** `ADCElm`
  - **Description:** Analog-to-digital converter

---

## Subcircuits

Subcircuits are user-defined circuit blocks that can be reused. The subcircuit menu is dynamically populated based on available custom composite models in the system.

- **Custom subcircuits are loaded dynamically**
- **Each subcircuit appears as:** `CustomCompositeElm:[SubcircuitName]`

---

## Drag Tools

These tools are used for manipulating circuit elements during editing:

### Drag All
- **Class:** `DragAll`
- **Shortcut:** Alt-drag
- **Description:** Drag all connected elements together

### Drag Row
- **Class:** `DragRow`
- **Shortcut:** Alt-Shift-drag
- **Description:** Drag entire row of elements

### Drag Column
- **Class:** `DragColumn`
- **Shortcut:** Alt-Meta-drag (Alt-Cmd-drag on Mac)
- **Description:** Drag entire column of elements

### Drag Selected
- **Class:** `DragSelected`
- **Description:** Drag only selected elements

### Drag Post
- **Class:** `DragPost`
- **Shortcut:** Ctrl-drag (Cmd-drag on Mac)
- **Description:** Drag individual connection points

---

## Selection Tools

### Select/Drag Sel
- **Class:** `Select`
- **Shortcut:** Space or Shift-drag
- **Description:** Selection tool for choosing circuit elements

---

## Notes

- Class names are used internally by the circuit simulator
- Some elements may have additional configuration options available through their edit dialogs
- Keyboard shortcuts are shown where available
- The availability of some elements may depend on the specific build configuration
- Custom subcircuits can extend the available element library

---

*This documentation is generated from the MenuManager.java composeMainMenu method and reflects the circuit elements available in CircuitJS1.*
