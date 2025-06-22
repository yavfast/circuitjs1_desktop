package com.lushprojects.circuitjs1.client;

public class CircuitElmCreator {

    public static CircuitElm createCe(int tint, int x1, int y1, int x2, int y2, int f, StringTokenizer st) {
        switch (tint) {
            case 'A':
                return new AntennaElm(x1, y1, x2, y2, f, st);
            case 'I':
                return new InverterElm(x1, y1, x2, y2, f, st);
            case 'L':
                return new LogicInputElm(x1, y1, x2, y2, f, st);
            case 'M':
                return new LogicOutputElm(x1, y1, x2, y2, f, st);
            case 'O':
                return new OutputElm(x1, y1, x2, y2, f, st);
            case 'R':
                return new RailElm(x1, y1, x2, y2, f, st);
            case 'S':
                return new Switch2Elm(x1, y1, x2, y2, f, st);
            case 'T':
                return new TransformerElm(x1, y1, x2, y2, f, st);
            case 'a':
                return new OpAmpElm(x1, y1, x2, y2, f, st);
            case 'b':
                return new BoxElm(x1, y1, x2, y2, f, st);
            case 'c':
                return new CapacitorElm(x1, y1, x2, y2, f, st);
            case 'd':
                return new DiodeElm(x1, y1, x2, y2, f, st);
            case 'f':
                return new MosfetElm(x1, y1, x2, y2, f, st);
            case 'g':
                return new GroundElm(x1, y1, x2, y2, f, st);
            case 'i':
                return new CurrentElm(x1, y1, x2, y2, f, st);
            case 'j':
                return new JfetElm(x1, y1, x2, y2, f, st);
            case 'l':
                return new InductorElm(x1, y1, x2, y2, f, st);
            case 'm':
                return new MemristorElm(x1, y1, x2, y2, f, st);
            case 'n':
                return new NoiseElm(x1, y1, x2, y2, f, st);
            case 'p':
                return new ProbeElm(x1, y1, x2, y2, f, st);
            case 'r':
                return new ResistorElm(x1, y1, x2, y2, f, st);
            case 's':
                return new SwitchElm(x1, y1, x2, y2, f, st);
            case 't':
                return new TransistorElm(x1, y1, x2, y2, f, st);
            case 'v':
                return new VoltageElm(x1, y1, x2, y2, f, st);
            case 'w':
                return new WireElm(x1, y1, x2, y2, f, st);
            case 'x':
                return new TextElm(x1, y1, x2, y2, f, st);
            case 'z':
                return new ZenerElm(x1, y1, x2, y2, f, st);
            case 150:
                return new AndGateElm(x1, y1, x2, y2, f, st);
            case 151:
                return new NandGateElm(x1, y1, x2, y2, f, st);
            case 152:
                return new OrGateElm(x1, y1, x2, y2, f, st);
            case 153:
                return new NorGateElm(x1, y1, x2, y2, f, st);
            case 154:
                return new XorGateElm(x1, y1, x2, y2, f, st);
            case 155:
                return new DFlipFlopElm(x1, y1, x2, y2, f, st);
            case 156:
                return new JKFlipFlopElm(x1, y1, x2, y2, f, st);
            case 157:
                return new SevenSegElm(x1, y1, x2, y2, f, st);
            case 158:
                return new VCOElm(x1, y1, x2, y2, f, st);
            case 159:
                return new AnalogSwitchElm(x1, y1, x2, y2, f, st);
            case 160:
                return new AnalogSwitch2Elm(x1, y1, x2, y2, f, st);
            case 161:
                return new PhaseCompElm(x1, y1, x2, y2, f, st);
            case 162:
                return new LEDElm(x1, y1, x2, y2, f, st);
            case 163:
                return new RingCounterElm(x1, y1, x2, y2, f, st);
            case 164:
                return new CounterElm(x1, y1, x2, y2, f, st);
            case 165:
                return new TimerElm(x1, y1, x2, y2, f, st);
            case 166:
                return new DACElm(x1, y1, x2, y2, f, st);
            case 167:
                return new ADCElm(x1, y1, x2, y2, f, st);
            case 168:
                return new LatchElm(x1, y1, x2, y2, f, st);
            case 169:
                return new TappedTransformerElm(x1, y1, x2, y2, f, st);
            case 170:
                return new SweepElm(x1, y1, x2, y2, f, st);
            case 171:
                return new TransLineElm(x1, y1, x2, y2, f, st);
            case 172:
                return new VarRailElm(x1, y1, x2, y2, f, st);
            case 173:
                return new TriodeElm(x1, y1, x2, y2, f, st);
            case 174:
                return new PotElm(x1, y1, x2, y2, f, st);
            case 175:
                return new TunnelDiodeElm(x1, y1, x2, y2, f, st);
            case 176:
                return new VaractorElm(x1, y1, x2, y2, f, st);
            case 177:
                return new SCRElm(x1, y1, x2, y2, f, st);
            case 178:
                return new RelayElm(x1, y1, x2, y2, f, st);
            case 179:
                return new CC2Elm(x1, y1, x2, y2, f, st);
            case 180:
                return new TriStateElm(x1, y1, x2, y2, f, st);
            case 181:
                return new LampElm(x1, y1, x2, y2, f, st);
            case 182:
                return new SchmittElm(x1, y1, x2, y2, f, st);
            case 183:
                return new InvertingSchmittElm(x1, y1, x2, y2, f, st);
            case 184:
                return new MultiplexerElm(x1, y1, x2, y2, f, st);
            case 185:
                return new DeMultiplexerElm(x1, y1, x2, y2, f, st);
            case 186:
                return new PisoShiftElm(x1, y1, x2, y2, f, st);
            case 187:
                return new SparkGapElm(x1, y1, x2, y2, f, st);
            case 188:
                return new SeqGenElm(x1, y1, x2, y2, f, st);
            case 189:
                return new SipoShiftElm(x1, y1, x2, y2, f, st);
            case 193:
                return new TFlipFlopElm(x1, y1, x2, y2, f, st);
            case 194:
                return new MonostableElm(x1, y1, x2, y2, f, st);
            case 195:
                return new HalfAdderElm(x1, y1, x2, y2, f, st);
            case 196:
                return new FullAdderElm(x1, y1, x2, y2, f, st);
            case 197:
                return new SevenSegDecoderElm(x1, y1, x2, y2, f, st);
            case 200:
                return new AMElm(x1, y1, x2, y2, f, st);
            case 201:
                return new FMElm(x1, y1, x2, y2, f, st);
            case 203:
                return new DiacElm(x1, y1, x2, y2, f, st);
            case 206:
                return new TriacElm(x1, y1, x2, y2, f, st);
            case 207:
                return new LabeledNodeElm(x1, y1, x2, y2, f, st);
            case 208:
                return new CustomLogicElm(x1, y1, x2, y2, f, st);
            case 209:
                return new PolarCapacitorElm(x1, y1, x2, y2, f, st);
            case 210:
                return new DataRecorderElm(x1, y1, x2, y2, f, st);
            case 211:
                return new AudioOutputElm(x1, y1, x2, y2, f, st);
            case 212:
                return new VCVSElm(x1, y1, x2, y2, f, st);
            case 213:
                return new VCCSElm(x1, y1, x2, y2, f, st);
            case 214:
                return new CCVSElm(x1, y1, x2, y2, f, st);
            case 215:
                return new CCCSElm(x1, y1, x2, y2, f, st);
            case 216:
                return new OhmMeterElm(x1, y1, x2, y2, f, st);
            case 350:
                return new ThermistorNTCElm(x1, y1, x2, y2, f, st);
            case 368:
                return new TestPointElm(x1, y1, x2, y2, f, st);
            case 370:
                return new AmmeterElm(x1, y1, x2, y2, f, st);
            case 374:
                return new LDRElm(x1, y1, x2, y2, f, st);
            case 400:
                return new DarlingtonElm(x1, y1, x2, y2, f, st);
            case 401:
                return new ComparatorElm(x1, y1, x2, y2, f, st);
            case 402:
                return new OTAElm(x1, y1, x2, y2, f, st);
            case 403:
                return new ScopeElm(x1, y1, x2, y2, f, st);
            case 404:
                return new FuseElm(x1, y1, x2, y2, f, st);
            case 405:
                return new LEDArrayElm(x1, y1, x2, y2, f, st);
            case 406:
                return new CustomTransformerElm(x1, y1, x2, y2, f, st);
            case 407:
                return new OptocouplerElm(x1, y1, x2, y2, f, st);
            case 408:
                return new StopTriggerElm(x1, y1, x2, y2, f, st);
            case 409:
                return new OpAmpRealElm(x1, y1, x2, y2, f, st);
            case 410:
                return new CustomCompositeElm(x1, y1, x2, y2, f, st);
            case 411:
                return new AudioInputElm(x1, y1, x2, y2, f, st);
            case 412:
                return new CrystalElm(x1, y1, x2, y2, f, st);
            case 413:
                return new SRAMElm(x1, y1, x2, y2, f, st);
            case 414:
                return new TimeDelayRelayElm(x1, y1, x2, y2, f, st);
            case 415:
                return new DCMotorElm(x1, y1, x2, y2, f, st);
            case 416:
                return new MBBSwitchElm(x1, y1, x2, y2, f, st);
            case 417:
                return new UnijunctionElm(x1, y1, x2, y2, f, st);
            case 418:
                return new ExtVoltageElm(x1, y1, x2, y2, f, st);
            case 419:
                return new DecimalDisplayElm(x1, y1, x2, y2, f, st);
            case 420:
                return new WattmeterElm(x1, y1, x2, y2, f, st);
            case 421:
                return new Counter2Elm(x1, y1, x2, y2, f, st);
            case 422:
                return new DelayBufferElm(x1, y1, x2, y2, f, st);
            case 423:
                return new LineElm(x1, y1, x2, y2, f, st);
            case 424:
                return new DataInputElm(x1, y1, x2, y2, f, st);
            case 425:
                return new RelayCoilElm(x1, y1, x2, y2, f, st);
            case 426:
                return new RelayContactElm(x1, y1, x2, y2, f, st);
            case 427:
                return new ThreePhaseMotorElm(x1, y1, x2, y2, f, st);
            case 428:
                return new MotorProtectionSwitchElm(x1, y1, x2, y2, f, st);
            case 429:
                return new DPDTSwitchElm(x1, y1, x2, y2, f, st);
            case 430:
                return new CrossSwitchElm(x1, y1, x2, y2, f, st);
        }
        return null;
    }

    public static CircuitElm constructElement(String n, int x1, int y1) {
        if (n == "GroundElm")
            return (CircuitElm) new GroundElm(x1, y1);
        if (n == "ResistorElm")
            return (CircuitElm) new ResistorElm(x1, y1);
        if (n == "RailElm")
            return (CircuitElm) new RailElm(x1, y1);
        if (n == "SwitchElm")
            return (CircuitElm) new SwitchElm(x1, y1);
        if (n == "Switch2Elm")
            return (CircuitElm) new Switch2Elm(x1, y1);
        if (n == "MBBSwitchElm")
            return (CircuitElm) new MBBSwitchElm(x1, y1);
        if (n == "NTransistorElm" || n == "TransistorElm")
            return (CircuitElm) new NTransistorElm(x1, y1);
        if (n == "PTransistorElm")
            return (CircuitElm) new PTransistorElm(x1, y1);
        if (n == "WireElm")
            return (CircuitElm) new WireElm(x1, y1);
        if (n == "CapacitorElm")
            return (CircuitElm) new CapacitorElm(x1, y1);
        if (n == "PolarCapacitorElm")
            return (CircuitElm) new PolarCapacitorElm(x1, y1);
        if (n == "InductorElm")
            return (CircuitElm) new InductorElm(x1, y1);
        if (n == "DCVoltageElm" || n == "VoltageElm")
            return (CircuitElm) new DCVoltageElm(x1, y1);
        if (n == "VarRailElm")
            return (CircuitElm) new VarRailElm(x1, y1);
        if (n == "PotElm")
            return (CircuitElm) new PotElm(x1, y1);
        if (n == "OutputElm")
            return (CircuitElm) new OutputElm(x1, y1);
        if (n == "CurrentElm")
            return (CircuitElm) new CurrentElm(x1, y1);
        if (n == "ProbeElm")
            return (CircuitElm) new ProbeElm(x1, y1);
        if (n == "DiodeElm")
            return (CircuitElm) new DiodeElm(x1, y1);
        if (n == "ZenerElm")
            return (CircuitElm) new ZenerElm(x1, y1);
        if (n == "ACVoltageElm")
            return (CircuitElm) new ACVoltageElm(x1, y1);
        if (n == "ACRailElm")
            return (CircuitElm) new ACRailElm(x1, y1);
        if (n == "SquareRailElm")
            return (CircuitElm) new SquareRailElm(x1, y1);
        if (n == "SweepElm")
            return (CircuitElm) new SweepElm(x1, y1);
        if (n == "LEDElm")
            return (CircuitElm) new LEDElm(x1, y1);
        if (n == "AntennaElm")
            return (CircuitElm) new AntennaElm(x1, y1);
        if (n == "LogicInputElm")
            return (CircuitElm) new LogicInputElm(x1, y1);
        if (n == "LogicOutputElm")
            return (CircuitElm) new LogicOutputElm(x1, y1);
        if (n == "TransformerElm")
            return (CircuitElm) new TransformerElm(x1, y1);
        if (n == "TappedTransformerElm")
            return (CircuitElm) new TappedTransformerElm(x1, y1);
        if (n == "TransLineElm")
            return (CircuitElm) new TransLineElm(x1, y1);
        if (n == "RelayElm")
            return (CircuitElm) new RelayElm(x1, y1);
        if (n == "RelayCoilElm")
            return (CircuitElm) new RelayCoilElm(x1, y1);
        if (n == "RelayContactElm")
            return (CircuitElm) new RelayContactElm(x1, y1);
        if (n == "ThreePhaseMotorElm")
            return (CircuitElm) new ThreePhaseMotorElm(x1, y1);
        if (n == "MemristorElm")
            return (CircuitElm) new MemristorElm(x1, y1);
        if (n == "SparkGapElm")
            return (CircuitElm) new SparkGapElm(x1, y1);
        if (n == "ClockElm")
            return (CircuitElm) new ClockElm(x1, y1);
        if (n == "AMElm")
            return (CircuitElm) new AMElm(x1, y1);
        if (n == "FMElm")
            return (CircuitElm) new FMElm(x1, y1);
        if (n == "LampElm")
            return (CircuitElm) new LampElm(x1, y1);
        if (n == "PushSwitchElm")
            return (CircuitElm) new PushSwitchElm(x1, y1);
        if (n == "OpAmpElm")
            return (CircuitElm) new OpAmpElm(x1, y1);
        if (n == "OpAmpSwapElm")
            return (CircuitElm) new OpAmpSwapElm(x1, y1);
        if (n == "NMosfetElm" || n == "MosfetElm")
            return (CircuitElm) new NMosfetElm(x1, y1);
        if (n == "PMosfetElm")
            return (CircuitElm) new PMosfetElm(x1, y1);
        if (n == "NJfetElm" || n == "JfetElm")
            return (CircuitElm) new NJfetElm(x1, y1);
        if (n == "PJfetElm")
            return (CircuitElm) new PJfetElm(x1, y1);
        if (n == "AnalogSwitchElm")
            return (CircuitElm) new AnalogSwitchElm(x1, y1);
        if (n == "AnalogSwitch2Elm")
            return (CircuitElm) new AnalogSwitch2Elm(x1, y1);
        if (n == "SchmittElm")
            return (CircuitElm) new SchmittElm(x1, y1);
        if (n == "InvertingSchmittElm")
            return (CircuitElm) new InvertingSchmittElm(x1, y1);
        if (n == "TriStateElm")
            return (CircuitElm) new TriStateElm(x1, y1);
        if (n == "SCRElm")
            return (CircuitElm) new SCRElm(x1, y1);
        if (n == "DiacElm")
            return (CircuitElm) new DiacElm(x1, y1);
        if (n == "TriacElm")
            return (CircuitElm) new TriacElm(x1, y1);
        if (n == "TriodeElm")
            return (CircuitElm) new TriodeElm(x1, y1);
        if (n == "VaractorElm")
            return (CircuitElm) new VaractorElm(x1, y1);
        if (n == "TunnelDiodeElm")
            return (CircuitElm) new TunnelDiodeElm(x1, y1);
        if (n == "CC2Elm")
            return (CircuitElm) new CC2Elm(x1, y1);
        if (n == "CC2NegElm")
            return (CircuitElm) new CC2NegElm(x1, y1);
        if (n == "InverterElm")
            return (CircuitElm) new InverterElm(x1, y1);
        if (n == "NandGateElm")
            return (CircuitElm) new NandGateElm(x1, y1);
        if (n == "NorGateElm")
            return (CircuitElm) new NorGateElm(x1, y1);
        if (n == "AndGateElm")
            return (CircuitElm) new AndGateElm(x1, y1);
        if (n == "OrGateElm")
            return (CircuitElm) new OrGateElm(x1, y1);
        if (n == "XorGateElm")
            return (CircuitElm) new XorGateElm(x1, y1);
        if (n == "DFlipFlopElm")
            return (CircuitElm) new DFlipFlopElm(x1, y1);
        if (n == "JKFlipFlopElm")
            return (CircuitElm) new JKFlipFlopElm(x1, y1);
        if (n == "SevenSegElm")
            return (CircuitElm) new SevenSegElm(x1, y1);
        if (n == "MultiplexerElm")
            return (CircuitElm) new MultiplexerElm(x1, y1);
        if (n == "DeMultiplexerElm")
            return (CircuitElm) new DeMultiplexerElm(x1, y1);
        if (n == "SipoShiftElm")
            return (CircuitElm) new SipoShiftElm(x1, y1);
        if (n == "PisoShiftElm")
            return (CircuitElm) new PisoShiftElm(x1, y1);
        if (n == "PhaseCompElm")
            return (CircuitElm) new PhaseCompElm(x1, y1);
        if (n == "CounterElm")
            return (CircuitElm) new CounterElm(x1, y1);

        // if you take out RingCounterElm, it will break subcircuits
        // if you take out DecadeElm, it will break the menus and people's saved shortcuts
        if (n == "DecadeElm" || n == "RingCounterElm")
            return (CircuitElm) new RingCounterElm(x1, y1);

        if (n == "TimerElm")
            return (CircuitElm) new TimerElm(x1, y1);
        if (n == "DACElm")
            return (CircuitElm) new DACElm(x1, y1);
        if (n == "ADCElm")
            return (CircuitElm) new ADCElm(x1, y1);
        if (n == "LatchElm")
            return (CircuitElm) new LatchElm(x1, y1);
        if (n == "SeqGenElm")
            return (CircuitElm) new SeqGenElm(x1, y1);
        if (n == "VCOElm")
            return (CircuitElm) new VCOElm(x1, y1);
        if (n == "BoxElm")
            return (CircuitElm) new BoxElm(x1, y1);
        if (n == "LineElm")
            return (CircuitElm) new LineElm(x1, y1);
        if (n == "TextElm")
            return (CircuitElm) new TextElm(x1, y1);
        if (n == "TFlipFlopElm")
            return (CircuitElm) new TFlipFlopElm(x1, y1);
        if (n == "SevenSegDecoderElm")
            return (CircuitElm) new SevenSegDecoderElm(x1, y1);
        if (n == "FullAdderElm")
            return (CircuitElm) new FullAdderElm(x1, y1);
        if (n == "HalfAdderElm")
            return (CircuitElm) new HalfAdderElm(x1, y1);
        if (n == "MonostableElm")
            return (CircuitElm) new MonostableElm(x1, y1);
        if (n == "LabeledNodeElm")
            return (CircuitElm) new LabeledNodeElm(x1, y1);

        // if you take out UserDefinedLogicElm, it will break people's saved shortcuts
        if (n == "UserDefinedLogicElm" || n == "CustomLogicElm")
            return (CircuitElm) new CustomLogicElm(x1, y1);

        if (n == "TestPointElm")
            return new TestPointElm(x1, y1);
        if (n == "AmmeterElm")
            return new AmmeterElm(x1, y1);
        if (n == "DataRecorderElm")
            return (CircuitElm) new DataRecorderElm(x1, y1);
        if (n == "AudioOutputElm")
            return (CircuitElm) new AudioOutputElm(x1, y1);
        if (n == "NDarlingtonElm" || n == "DarlingtonElm")
            return (CircuitElm) new NDarlingtonElm(x1, y1);
        if (n == "PDarlingtonElm")
            return (CircuitElm) new PDarlingtonElm(x1, y1);
        if (n == "ComparatorElm")
            return (CircuitElm) new ComparatorElm(x1, y1);
        if (n == "OTAElm")
            return (CircuitElm) new OTAElm(x1, y1);
        if (n == "NoiseElm")
            return (CircuitElm) new NoiseElm(x1, y1);
        if (n == "VCVSElm")
            return (CircuitElm) new VCVSElm(x1, y1);
        if (n == "VCCSElm")
            return (CircuitElm) new VCCSElm(x1, y1);
        if (n == "CCVSElm")
            return (CircuitElm) new CCVSElm(x1, y1);
        if (n == "CCCSElm")
            return (CircuitElm) new CCCSElm(x1, y1);
        if (n == "OhmMeterElm")
            return (CircuitElm) new OhmMeterElm(x1, y1);
        if (n == "ScopeElm")
            return (CircuitElm) new ScopeElm(x1, y1);
        if (n == "FuseElm")
            return (CircuitElm) new FuseElm(x1, y1);
        if (n == "LEDArrayElm")
            return (CircuitElm) new LEDArrayElm(x1, y1);
        if (n == "CustomTransformerElm")
            return (CircuitElm) new CustomTransformerElm(x1, y1);
        if (n == "OptocouplerElm")
            return (CircuitElm) new OptocouplerElm(x1, y1);
        if (n == "StopTriggerElm")
            return (CircuitElm) new StopTriggerElm(x1, y1);
        if (n == "OpAmpRealElm")
            return (CircuitElm) new OpAmpRealElm(x1, y1);
        if (n == "CustomCompositeElm")
            return (CircuitElm) new CustomCompositeElm(x1, y1);
        if (n == "AudioInputElm")
            return (CircuitElm) new AudioInputElm(x1, y1);
        if (n == "CrystalElm")
            return (CircuitElm) new CrystalElm(x1, y1);
        if (n == "SRAMElm")
            return (CircuitElm) new SRAMElm(x1, y1);
        if (n == "TimeDelayRelayElm")
            return (CircuitElm) new TimeDelayRelayElm(x1, y1);
        if (n == "DCMotorElm")
            return (CircuitElm) new DCMotorElm(x1, y1);
        if (n == "LDRElm")
            return (CircuitElm) new LDRElm(x1, y1);
        if (n == "ThermistorNTCElm")
            return (CircuitElm) new ThermistorNTCElm(x1, y1);
        if (n == "UnijunctionElm")
            return (CircuitElm) new UnijunctionElm(x1, y1);
        if (n == "ExtVoltageElm")
            return (CircuitElm) new ExtVoltageElm(x1, y1);
        if (n == "DecimalDisplayElm")
            return (CircuitElm) new DecimalDisplayElm(x1, y1);
        if (n == "WattmeterElm")
            return (CircuitElm) new WattmeterElm(x1, y1);
        if (n == "Counter2Elm")
            return (CircuitElm) new Counter2Elm(x1, y1);
        if (n == "DelayBufferElm")
            return (CircuitElm) new DelayBufferElm(x1, y1);
        if (n == "DataInputElm")
            return (CircuitElm) new DataInputElm(x1, y1);
        if (n == "MotorProtectionSwitchElm")
            return (CircuitElm) new MotorProtectionSwitchElm(x1, y1);
        if (n == "DPDTSwitchElm")
            return (CircuitElm) new DPDTSwitchElm(x1, y1);
        if (n == "CrossSwitchElm")
            return (CircuitElm) new CrossSwitchElm(x1, y1);

        // handle CustomCompositeElm:modelname
        if (n.startsWith("CustomCompositeElm:")) {
            int ix = n.indexOf(':') + 1;
            String name = n.substring(ix);
            return (CircuitElm) new CustomCompositeElm(x1, y1, name);
        }
        return null;
    }

}
