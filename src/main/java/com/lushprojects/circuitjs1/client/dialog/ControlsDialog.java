package com.lushprojects.circuitjs1.client.dialog;

import com.google.gwt.user.client.ui.DialogBox;
import com.google.gwt.user.client.ui.HasHorizontalAlignment;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.VerticalPanel;
import com.lushprojects.circuitjs1.client.CirSim;
import com.lushprojects.circuitjs1.client.Scrollbar;

public class ControlsDialog extends DialogBox {
    final CirSim cirSim;
    VerticalPanel panel;

    public ControlsDialog(CirSim cs) {
        super(false, false); // autoHide, modal
        cirSim = cs;
        setText("Controls");
        panel = new VerticalPanel();
        setWidget(panel);

        panel.add(new Label("Simulation Speed"));
        cirSim.speedBar = new Scrollbar(Scrollbar.HORIZONTAL, 10, 1, 1, 260);
        panel.add(cirSim.speedBar);

        panel.add(new Label("Current Speed"));
        cirSim.currentBar = new Scrollbar(Scrollbar.HORIZONTAL, 50, 1, 1, 100);
        panel.add(cirSim.currentBar);

        cirSim.powerLabel = new Label("Power Brightness");
        panel.add(cirSim.powerLabel);
        cirSim.powerBar = new Scrollbar(Scrollbar.HORIZONTAL, 50, 1, 1, 100);
        panel.add(cirSim.powerBar);

        // Align all labels to the center
        for (int i = 0; i < panel.getWidgetCount(); i++) {
            if (panel.getWidget(i) instanceof Label) {
                panel.setCellHorizontalAlignment(panel.getWidget(i), HasHorizontalAlignment.ALIGN_CENTER);
            }
        }
    }
}
