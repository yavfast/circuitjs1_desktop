package com.lushprojects.circuitjs1.client.dialog;

import com.google.gwt.user.client.Command;
import com.google.gwt.user.client.ui.HasHorizontalAlignment;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.VerticalPanel;
import com.lushprojects.circuitjs1.client.CirSim;
import com.lushprojects.circuitjs1.client.Scrollbar;
import com.lushprojects.circuitjs1.client.element.CircuitElm;

public class ControlsDialog extends Dialog {
    final CirSim cirSim;
    public VerticalPanel panel;
    
    // Time step values: discrete nice values from 1pS to 10uS
    // Each order of magnitude has 3 values: 1, 2, 5
    // Orders: 1e-12, 1e-11, 1e-10, 1e-9, 1e-8, 1e-7, 1e-6, 1e-5 (8 orders, but we go up to 10e-6)
    // Values: 1ps, 2ps, 5ps, 10ps, 20ps, 50ps, 100ps, 200ps, 500ps, 1ns, 2ns, 5ns, ...
    private static final double[] TIME_STEP_VALUES = {
        1e-12, 2e-12, 5e-12,                    // 1, 2, 5 pS
        1e-11, 2e-11, 5e-11,                    // 10, 20, 50 pS
        1e-10, 2e-10, 5e-10,                    // 100, 200, 500 pS
        1e-9,  2e-9,  5e-9,                     // 1, 2, 5 nS
        1e-8,  2e-8,  5e-8,                     // 10, 20, 50 nS
        1e-7,  2e-7,  5e-7,                     // 100, 200, 500 nS
        1e-6,  2e-6,  5e-6,                     // 1, 2, 5 µS
        1e-5                                     // 10 µS
    };
    private static final int TIME_STEP_RANGE = TIME_STEP_VALUES.length - 1;  // 21
    
    public Label timeStepLabel;

    public ControlsDialog(CirSim cs) {
        super(false, false); // autoHide, modal
        cirSim = cs;
        setText("Controls");
        panel = new VerticalPanel();
        setWidget(panel);

        timeStepLabel = new Label("Time Step");
        panel.add(timeStepLabel);
        cirSim.timeStepBar = new Scrollbar(cirSim, Scrollbar.HORIZONTAL, 
                timeStepToPosition(5e-6), 1, 0, TIME_STEP_RANGE, 
                new Command() {
                    @Override
                    public void execute() {
                        double ts = positionToTimeStep(cirSim.timeStepBar.getValue());
                        cirSim.getActiveDocument().simulator.maxTimeStep = ts;
                        cirSim.getActiveDocument().simulator.timeStep = ts;
                        updateTimeStepLabel();
                        cirSim.needAnalyze();
                    }
                });
        panel.add(cirSim.timeStepBar);

        panel.add(new Label("Simulation Speed"));
        cirSim.speedBar = new Scrollbar(cirSim, Scrollbar.HORIZONTAL, 10, 1, 1, 260);
        panel.add(cirSim.speedBar);

        panel.add(new Label("Current Speed"));
        cirSim.currentBar = new Scrollbar(cirSim, Scrollbar.HORIZONTAL, 50, 1, 1, 100);
        panel.add(cirSim.currentBar);

        cirSim.powerLabel = new Label("Power Brightness");
        panel.add(cirSim.powerLabel);
        cirSim.powerBar = new Scrollbar(cirSim, Scrollbar.HORIZONTAL, 50, 1, 1, 100);
        panel.add(cirSim.powerBar);

        // Align all labels to the center
        for (int i = 0; i < panel.getWidgetCount(); i++) {
            if (panel.getWidget(i) instanceof Label) {
                panel.setCellHorizontalAlignment(panel.getWidget(i), HasHorizontalAlignment.ALIGN_CENTER);
            }
        }
    }
    
    /**
     * Convert scrollbar position to time step value using discrete nice values
     */
    public static double positionToTimeStep(int position) {
        if (position < 0) position = 0;
        if (position > TIME_STEP_RANGE) position = TIME_STEP_RANGE;
        return TIME_STEP_VALUES[position];
    }
    
    /**
     * Convert time step value to scrollbar position (find nearest discrete value)
     */
    public static int timeStepToPosition(double timeStep) {
        // Find the closest value in TIME_STEP_VALUES
        int bestIndex = 0;
        double bestDiff = Double.MAX_VALUE;
        
        for (int i = 0; i < TIME_STEP_VALUES.length; i++) {
            // Use logarithmic distance for better matching
            double diff = Math.abs(Math.log10(timeStep) - Math.log10(TIME_STEP_VALUES[i]));
            if (diff < bestDiff) {
                bestDiff = diff;
                bestIndex = i;
            }
        }
        return bestIndex;
    }
    
    /**
     * Update time step scrollbar to match current simulator value
     */
    public void updateTimeStepBar() {
        double ts = cirSim.getActiveDocument().simulator.maxTimeStep;
        cirSim.timeStepBar.setValue(timeStepToPosition(ts));
        updateTimeStepLabel();
    }
    
    /**
     * Update time step label with current value
     */
    public void updateTimeStepLabel() {
        double ts = cirSim.getActiveDocument().simulator.maxTimeStep;
        timeStepLabel.setText("Time Step: " + CircuitElm.getTimeText(ts));
    }

    @Override
    protected String getOptionPrefix() {
        return "ControlsDialog";
    }
}
