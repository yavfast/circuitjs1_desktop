package com.lushprojects.circuitjs1.client;

import com.lushprojects.circuitjs1.client.element.*;

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

    public static void readDescription(CircuitElm ce, StringTokenizer st) {
        // After all element parameters are processed, check if there are remaining tokens
        // that could contain a description starting with '#'

        while (st.hasMoreTokens()) {
            String token = st.nextToken();
            // If we encounter a token starting with '#', treat it and everything after as description
            if (token.startsWith("#")) {
                // Use getStartTokenIdx to get position in original string
                int hashIndex = st.getStartTokenIdx();

                if (hashIndex != -1) {
                    String originalStr = st.getOriginalString();
                    // Extract description from '#' to end of string
                    String description = originalStr.substring(hashIndex + 1).trim();
                    ce.setDescription(description);
                }
                return;
            }
        }
    }

    public static CircuitElm constructElement(String n, int x1, int y1) {
        switch (n) {
            case "GroundElm":
                return new GroundElm(x1, y1);
            case "ResistorElm":
                return new ResistorElm(x1, y1);
            case "RailElm":
                return new RailElm(x1, y1);
            case "SwitchElm":
                return new SwitchElm(x1, y1);
            case "Switch2Elm":
                return new Switch2Elm(x1, y1);
            case "MBBSwitchElm":
                return new MBBSwitchElm(x1, y1);
            case "NTransistorElm":
            case "TransistorElm":
                return new NTransistorElm(x1, y1);
            case "PTransistorElm":
                return new PTransistorElm(x1, y1);
            case "WireElm":
                return new WireElm(x1, y1);
            case "CapacitorElm":
                return new CapacitorElm(x1, y1);
            case "PolarCapacitorElm":
                return new PolarCapacitorElm(x1, y1);
            case "InductorElm":
                return new InductorElm(x1, y1);
            case "DCVoltageElm":
            case "VoltageElm":
                return new DCVoltageElm(x1, y1);
            case "VarRailElm":
                return new VarRailElm(x1, y1);
            case "PotElm":
                return new PotElm(x1, y1);
            case "OutputElm":
                return new OutputElm(x1, y1);
            case "CurrentElm":
                return new CurrentElm(x1, y1);
            case "ProbeElm":
                return new ProbeElm(x1, y1);
            case "DiodeElm":
                return new DiodeElm(x1, y1);
            case "ZenerElm":
                return new ZenerElm(x1, y1);
            case "ACVoltageElm":
                return new ACVoltageElm(x1, y1);
            case "ACRailElm":
                return new ACRailElm(x1, y1);
            case "SquareRailElm":
                return new SquareRailElm(x1, y1);
            case "SweepElm":
                return new SweepElm(x1, y1);
            case "LEDElm":
                return new LEDElm(x1, y1);
            case "AntennaElm":
                return new AntennaElm(x1, y1);
            case "LogicInputElm":
                return new LogicInputElm(x1, y1);
            case "LogicOutputElm":
                return new LogicOutputElm(x1, y1);
            case "TransformerElm":
                return new TransformerElm(x1, y1);
            case "TappedTransformerElm":
                return new TappedTransformerElm(x1, y1);
            case "TransLineElm":
                return new TransLineElm(x1, y1);
            case "RelayElm":
                return new RelayElm(x1, y1);
            case "RelayCoilElm":
                return new RelayCoilElm(x1, y1);
            case "RelayContactElm":
                return new RelayContactElm(x1, y1);
            case "ThreePhaseMotorElm":
                return new ThreePhaseMotorElm(x1, y1);
            case "MemristorElm":
                return new MemristorElm(x1, y1);
            case "SparkGapElm":
                return new SparkGapElm(x1, y1);
            case "ClockElm":
                return new ClockElm(x1, y1);
            case "AMElm":
                return new AMElm(x1, y1);
            case "FMElm":
                return new FMElm(x1, y1);
            case "LampElm":
                return new LampElm(x1, y1);
            case "PushSwitchElm":
                return new PushSwitchElm(x1, y1);
            case "OpAmpElm":
                return new OpAmpElm(x1, y1);
            case "OpAmpSwapElm":
                return new OpAmpSwapElm(x1, y1);
            case "NMosfetElm":
            case "MosfetElm":
                return new NMosfetElm(x1, y1);
            case "PMosfetElm":
                return new PMosfetElm(x1, y1);
            case "NJfetElm":
            case "JfetElm":
                return new NJfetElm(x1, y1);
            case "PJfetElm":
                return new PJfetElm(x1, y1);
            case "AnalogSwitchElm":
                return new AnalogSwitchElm(x1, y1);
            case "AnalogSwitch2Elm":
                return new AnalogSwitch2Elm(x1, y1);
            case "SchmittElm":
                return new SchmittElm(x1, y1);
            case "InvertingSchmittElm":
                return new InvertingSchmittElm(x1, y1);
            case "TriStateElm":
                return new TriStateElm(x1, y1);
            case "SCRElm":
                return new SCRElm(x1, y1);
            case "DiacElm":
                return new DiacElm(x1, y1);
            case "TriacElm":
                return new TriacElm(x1, y1);
            case "TriodeElm":
                return new TriodeElm(x1, y1);
            case "VaractorElm":
                return new VaractorElm(x1, y1);
            case "TunnelDiodeElm":
                return new TunnelDiodeElm(x1, y1);
            case "CC2Elm":
                return new CC2Elm(x1, y1);
            case "CC2NegElm":
                return new CC2NegElm(x1, y1);
            case "InverterElm":
                return new InverterElm(x1, y1);
            case "NandGateElm":
                return new NandGateElm(x1, y1);
            case "NorGateElm":
                return new NorGateElm(x1, y1);
            case "AndGateElm":
                return new AndGateElm(x1, y1);
            case "OrGateElm":
                return new OrGateElm(x1, y1);
            case "XorGateElm":
                return new XorGateElm(x1, y1);
            case "DFlipFlopElm":
                return new DFlipFlopElm(x1, y1);
            case "JKFlipFlopElm":
                return new JKFlipFlopElm(x1, y1);
            case "SevenSegElm":
                return new SevenSegElm(x1, y1);
            case "MultiplexerElm":
                return new MultiplexerElm(x1, y1);
            case "DeMultiplexerElm":
                return new DeMultiplexerElm(x1, y1);
            case "SipoShiftElm":
                return new SipoShiftElm(x1, y1);
            case "PisoShiftElm":
                return new PisoShiftElm(x1, y1);
            case "PhaseCompElm":
                return new PhaseCompElm(x1, y1);
            case "CounterElm":
                return new CounterElm(x1, y1);

            // if you take out RingCounterElm, it will break subcircuits
            // if you take out DecadeElm, it will break the menus and people's saved shortcuts
            case "DecadeElm":
            case "RingCounterElm":
                return new RingCounterElm(x1, y1);
            case "TimerElm":
                return new TimerElm(x1, y1);
            case "DACElm":
                return new DACElm(x1, y1);
            case "ADCElm":
                return new ADCElm(x1, y1);
            case "LatchElm":
                return new LatchElm(x1, y1);
            case "SeqGenElm":
                return new SeqGenElm(x1, y1);
            case "VCOElm":
                return new VCOElm(x1, y1);
            case "BoxElm":
                return new BoxElm(x1, y1);
            case "LineElm":
                return new LineElm(x1, y1);
            case "TextElm":
                return new TextElm(x1, y1);
            case "TFlipFlopElm":
                return new TFlipFlopElm(x1, y1);
            case "SevenSegDecoderElm":
                return new SevenSegDecoderElm(x1, y1);
            case "FullAdderElm":
                return new FullAdderElm(x1, y1);
            case "HalfAdderElm":
                return new HalfAdderElm(x1, y1);
            case "MonostableElm":
                return new MonostableElm(x1, y1);
            case "LabeledNodeElm":
                return new LabeledNodeElm(x1, y1);

            // if you take out UserDefinedLogicElm, it will break people's saved shortcuts
            case "UserDefinedLogicElm":
            case "CustomLogicElm":
                return new CustomLogicElm(x1, y1);
            case "TestPointElm":
                return new TestPointElm(x1, y1);
            case "AmmeterElm":
                return new AmmeterElm(x1, y1);
            case "DataRecorderElm":
                return new DataRecorderElm(x1, y1);
            case "AudioOutputElm":
                return new AudioOutputElm(x1, y1);
            case "NDarlingtonElm":
            case "DarlingtonElm":
                return new NDarlingtonElm(x1, y1);
            case "PDarlingtonElm":
                return new PDarlingtonElm(x1, y1);
            case "ComparatorElm":
                return new ComparatorElm(x1, y1);
            case "OTAElm":
                return new OTAElm(x1, y1);
            case "NoiseElm":
                return new NoiseElm(x1, y1);
            case "VCVSElm":
                return new VCVSElm(x1, y1);
            case "VCCSElm":
                return new VCCSElm(x1, y1);
            case "CCVSElm":
                return new CCVSElm(x1, y1);
            case "CCCSElm":
                return new CCCSElm(x1, y1);
            case "OhmMeterElm":
                return new OhmMeterElm(x1, y1);
            case "ScopeElm":
                return new ScopeElm(x1, y1);
            case "FuseElm":
                return new FuseElm(x1, y1);
            case "LEDArrayElm":
                return new LEDArrayElm(x1, y1);
            case "CustomTransformerElm":
                return new CustomTransformerElm(x1, y1);
            case "OptocouplerElm":
                return new OptocouplerElm(x1, y1);
            case "StopTriggerElm":
                return new StopTriggerElm(x1, y1);
            case "OpAmpRealElm":
                return new OpAmpRealElm(x1, y1);
            case "CustomCompositeElm":
                return new CustomCompositeElm(x1, y1);
            case "AudioInputElm":
                return new AudioInputElm(x1, y1);
            case "CrystalElm":
                return new CrystalElm(x1, y1);
            case "SRAMElm":
                return new SRAMElm(x1, y1);
            case "TimeDelayRelayElm":
                return new TimeDelayRelayElm(x1, y1);
            case "DCMotorElm":
                return new DCMotorElm(x1, y1);
            case "LDRElm":
                return new LDRElm(x1, y1);
            case "ThermistorNTCElm":
                return new ThermistorNTCElm(x1, y1);
            case "UnijunctionElm":
                return new UnijunctionElm(x1, y1);
            case "ExtVoltageElm":
                return new ExtVoltageElm(x1, y1);
            case "DecimalDisplayElm":
                return new DecimalDisplayElm(x1, y1);
            case "WattmeterElm":
                return new WattmeterElm(x1, y1);
            case "Counter2Elm":
                return new Counter2Elm(x1, y1);
            case "DelayBufferElm":
                return new DelayBufferElm(x1, y1);
            case "DataInputElm":
                return new DataInputElm(x1, y1);
            case "MotorProtectionSwitchElm":
                return new MotorProtectionSwitchElm(x1, y1);
            case "DPDTSwitchElm":
                return new DPDTSwitchElm(x1, y1);
            case "CrossSwitchElm":
                return new CrossSwitchElm(x1, y1);
        }

        // handle CustomCompositeElm:modelname
        if (n.startsWith("CustomCompositeElm:")) {
            int ix = n.indexOf(':') + 1;
            String name = n.substring(ix);
            return new CustomCompositeElm(x1, y1, name);
        }
        return null;
    }

}
