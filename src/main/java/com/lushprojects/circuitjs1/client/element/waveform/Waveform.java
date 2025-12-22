package com.lushprojects.circuitjs1.client.element.waveform;

import com.lushprojects.circuitjs1.client.element.VoltageElm;
import com.lushprojects.circuitjs1.client.element.RailElm;
import com.lushprojects.circuitjs1.client.element.VarRailElm;

import com.lushprojects.circuitjs1.client.Graphics;
import com.lushprojects.circuitjs1.client.Point;
import com.lushprojects.circuitjs1.client.dialog.EditInfo;

public abstract class Waveform {
    public static final int FLAG_COS = 2;
    public static final int FLAG_PULSE_DUTY = 4;
    public static final int WF_DC = 0;
    public static final int WF_AC = 1;
    public static final int WF_SQUARE = 2;
    public static final int WF_TRIANGLE = 3;
    public static final int WF_SAWTOOTH = 4;
    public static final int WF_PULSE = 5;
    public static final int WF_NOISE = 6;
    public static final int WF_VAR = 7;

    public double frequency = 40;
    public double maxVoltage = 5;
    public double freqTimeZero = 0;
    public double bias = 0;
    public double phaseShift = 0;
    public double dutyCycle = 0.5;
    public double noiseValue = 0;

    public static Waveform create(int type, Waveform old) {
        Waveform wf;
        switch (type) {
            case WF_DC: wf = new DCWaveform(); break;
            case WF_AC: wf = new ACWaveform(); break;
            case WF_SQUARE: wf = new SquareWaveform(); break;
            case WF_TRIANGLE: wf = new TriangleWaveform(); break;
            case WF_SAWTOOTH: wf = new SawtoothWaveform(); break;
            case WF_PULSE: wf = new PulseWaveform(); break;
            case WF_NOISE: wf = new NoiseWaveform(); break;
            case WF_VAR: wf = new VarWaveform(); break;
            default: wf = new DCWaveform(); break;
        }
        if (old != null) {
            wf.copyFrom(old);
        }
        return wf;
    }

    public abstract int getType();

    public abstract double getVoltage(VoltageElm elm);

    public abstract void draw(Graphics g, Point center, VoltageElm elm);

    public void drawRail(Graphics g, RailElm elm) {
        elm.drawWaveform(g, elm.point2);
    }

    public abstract void getInfo(VoltageElm elm, String[] arr, int i);

    public abstract EditInfo getEditInfo(VoltageElm elm, int n);

    public abstract void setEditValue(VoltageElm elm, int n, EditInfo ei);

    public abstract String getJsonTypeName();

    public void getJsonProperties(VoltageElm elm, java.util.Map<String, Object> props) {
        props.put("max_voltage", elm.getUnitText(maxVoltage, "V"));
        if (bias != 0) {
            props.put("dc_offset", elm.getUnitText(bias, "V"));
        }
        props.put("frequency", elm.getUnitText(frequency, "Hz"));
        if (phaseShift != 0) {
            props.put("phase_shift", phaseShift * 180 / Math.PI);
        }
    }

    public void applyJsonProperties(VoltageElm elm, java.util.Map<String, Object> properties) {
        maxVoltage = elm.getJsonDouble(properties, "max_voltage", 5);
        bias = elm.getJsonDouble(properties, "dc_offset", 0);
        frequency = elm.getJsonDouble(properties, "frequency", 40);
        phaseShift = elm.getJsonDouble(properties, "phase_shift", 0) * Math.PI / 180;
        dutyCycle = elm.getJsonDouble(properties, "duty_cycle", 0.5);
    }

    public void copyFrom(Waveform other) {
        if (other == null) return;
        this.frequency = other.frequency;
        this.maxVoltage = other.maxVoltage;
        this.freqTimeZero = other.freqTimeZero;
        this.bias = other.bias;
        this.phaseShift = other.phaseShift;
        this.dutyCycle = other.dutyCycle;
        this.noiseValue = other.noiseValue;
    }

    public String getJsonRailTypeName() {
        return "Rail";
    }

    public boolean isDC() {
        return false;
    }

    public boolean isPulse() {
        return false;
    }

    public void stamp(VoltageElm elm) {
        elm.simulator().stampVoltageSource(elm.getNode(0), elm.getNode(1), elm.voltSource);
    }

    public void stampRail(RailElm elm) {
        elm.simulator().stampVoltageSource(0, elm.getNode(0), elm.voltSource);
    }

    /**
     * Called each simulation step. Default behavior updates the voltage source for all non-DC waveforms.
     */
    public void doStep(VoltageElm elm) {
        if (!isDC()) {
            elm.simulator().updateVoltageSource(elm.getNode(0), elm.getNode(1), elm.voltSource, elm.getVoltage());
        }
    }

    /**
     * Whether the waveform icon should include a circle outline.
     */
    public boolean hasCircle() {
        return true;
    }

    /**
     * Whether to display frequency under the waveform icon in the element view.
     */
    public boolean showFrequency() {
        return true;
    }

    /**
     * Whether the element should use short leads (battery-style) instead of the waveform circle spacing.
     */
    public boolean usesShortLeads() {
        return isDC();
    }

    public void stepFinished(VoltageElm elm) {
    }

    protected double w(VoltageElm elm) {
        return 2 * Math.PI * (elm.simulator().t - freqTimeZero) * frequency + phaseShift;
    }
}
