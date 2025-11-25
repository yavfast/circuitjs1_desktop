package com.lushprojects.circuitjs1.client;

public class ExprState {
    public double[] values;
    public double[] lastValues;
    public double lastOutput;
    public double t;
    public double timeStep;

    public ExprState(int xx) {
        //n = xx;
        values = new double[9];
        lastValues = new double[9];
        values[4] = Math.E;
    }

    public void updateLastValues(double lastOut) {
        lastOutput = lastOut;
        int i;
        for (i = 0; i != values.length; i++)
            lastValues[i] = values[i];
    }

    public void reset() {
        for (int i = 0; i != values.length; i++)
            lastValues[i] = 0;
        lastOutput = 0;
        timeStep = 0;
    }
}
