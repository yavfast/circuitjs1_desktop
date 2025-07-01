package com.lushprojects.circuitjs1.client;

import com.google.gwt.user.client.ui.CheckBox;

public class ScopeCheckBox extends CheckBox {
    public String menuCmd;

    public ScopeCheckBox(String text, String menu) {
        super(text);
        menuCmd = menu;
    }

    void setValue(boolean x) {
        if (getValue() == x)
            return;
        super.setValue(x);
    }
}
