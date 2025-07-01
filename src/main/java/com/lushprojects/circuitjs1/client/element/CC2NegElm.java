package com.lushprojects.circuitjs1.client.element;

public class CC2NegElm extends CC2Elm {
    public CC2NegElm(int xx, int yy) {
        super(xx, yy, -1);
    }

    public Class getDumpClass() {
        return CC2Elm.class;
    }
}
