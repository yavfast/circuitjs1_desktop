package com.lushprojects.circuitjs1.client.element;

/**
 * The element classes' remembered last-used values: the editor seeds a newly placed element
 * with the model, gate options, ground symbol or sampling rate the user chose last. The agent
 * catalogue measures built-in defaults ([SP_AGA_01_05] "Defaults"), so it resets these to their
 * initial values for the measurement and restores the user's values afterwards:
 *
 * <pre>
 * LastUsedValues saved = LastUsedValues.resetToInitial();
 * try { ... } finally { saved.restore(); }
 * </pre>
 *
 * A new {@code static last*} field read by a placement constructor must be added here.
 */
public final class LastUsedValues {

    private final String diodeModel, ledModel, zenerModel, transistorModel, customLogicModel, compositeModel;
    private final double gateHighVoltage, mosfetBeta;
    private final boolean gateSchmitt;
    private final int groundSymbol, audioOutputRate, audioInputRate;

    private LastUsedValues() {
        diodeModel = DiodeElm.lastModelName;
        ledModel = LEDElm.lastLEDModelName;
        zenerModel = ZenerElm.lastZenerModelName;
        transistorModel = TransistorElm.lastModelName;
        customLogicModel = CustomLogicElm.lastModelName;
        compositeModel = CustomCompositeElm.lastModelName;
        gateHighVoltage = GateElm.lastHighVoltage;
        gateSchmitt = GateElm.lastSchmitt;
        mosfetBeta = MosfetElm.lastBeta;
        groundSymbol = GroundElm.lastSymbolType;
        audioOutputRate = AudioOutputElm.lastSamplingRate;
        audioInputRate = AudioInputElm.lastSamplingRate;
    }

    /**
     * Captures the current values, then sets every one to the value it has at application start.
     *
     * @return the captured values; call {@link #restore()} on it when done
     */
    public static LastUsedValues resetToInitial() {
        LastUsedValues saved = new LastUsedValues();
        DiodeElm.lastModelName = "default";
        LEDElm.lastLEDModelName = "default-led";
        ZenerElm.lastZenerModelName = "default-zener";
        TransistorElm.lastModelName = "default";
        CustomLogicElm.lastModelName = "default";
        CustomCompositeElm.lastModelName = "default";
        GateElm.lastHighVoltage = 5;
        GateElm.lastSchmitt = false;
        MosfetElm.lastBeta = 0;
        GroundElm.lastSymbolType = 0;
        AudioOutputElm.lastSamplingRate = 8000;
        AudioInputElm.lastSamplingRate = 0;
        return saved;
    }

    /** Puts the captured values back. */
    public void restore() {
        DiodeElm.lastModelName = diodeModel;
        LEDElm.lastLEDModelName = ledModel;
        ZenerElm.lastZenerModelName = zenerModel;
        TransistorElm.lastModelName = transistorModel;
        CustomLogicElm.lastModelName = customLogicModel;
        CustomCompositeElm.lastModelName = compositeModel;
        GateElm.lastHighVoltage = gateHighVoltage;
        GateElm.lastSchmitt = gateSchmitt;
        MosfetElm.lastBeta = mosfetBeta;
        GroundElm.lastSymbolType = groundSymbol;
        AudioOutputElm.lastSamplingRate = audioOutputRate;
        AudioInputElm.lastSamplingRate = audioInputRate;
    }
}
