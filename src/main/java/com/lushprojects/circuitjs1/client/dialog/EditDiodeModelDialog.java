package com.lushprojects.circuitjs1.client.dialog;

import com.lushprojects.circuitjs1.client.CirSim;
import com.lushprojects.circuitjs1.client.element.DiodeElm;
import com.lushprojects.circuitjs1.client.DiodeModel;

public class EditDiodeModelDialog extends EditDialog {

    DiodeModel model;
    DiodeElm diodeElm;

    public EditDiodeModelDialog(DiodeModel dm, CirSim f, DiodeElm de) {
        super(dm, f);
        model = dm;
        diodeElm = de;
        applyButton.removeFromParent();
    }

    void apply() {
        super.apply();
        if (model.name == null || model.name.length() == 0)
            model.pickName();
        if (diodeElm != null)
            diodeElm.newModelCreated(model);
    }

}
