package com.lushprojects.circuitjs1.client.dialog;

import com.lushprojects.circuitjs1.client.CirSim;
import com.lushprojects.circuitjs1.client.element.TransistorElm;
import com.lushprojects.circuitjs1.client.TransistorModel;

public class EditTransistorModelDialog extends EditDialog {

    TransistorModel model;
    TransistorElm transistorElm;

    public EditTransistorModelDialog(TransistorModel dm, CirSim f, TransistorElm te) {
        super(dm, f);
        model = dm;
        transistorElm = te;
        applyButton.removeFromParent();
    }

    void apply() {
        super.apply();
//	if (model.name == null || model.name.length() == 0)
//	    model.pickName();
        if (transistorElm != null)
            transistorElm.newModelCreated(model);
    }

}
