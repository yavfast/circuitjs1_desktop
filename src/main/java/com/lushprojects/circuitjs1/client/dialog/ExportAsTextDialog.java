/*    
    Copyright (C) Paul Falstad and Iain Sharp
    
    This file is part of CircuitJS1.

    CircuitJS1 is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 2 of the License, or
    (at your option) any later version.

    CircuitJS1 is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with CircuitJS1.  If not, see <http://www.gnu.org/licenses/>.
*/

package com.lushprojects.circuitjs1.client.dialog;

import com.google.gwt.user.client.ui.Button;
import com.google.gwt.user.client.ui.HasHorizontalAlignment;
import com.google.gwt.user.client.ui.HorizontalPanel;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.TextArea;
import com.google.gwt.user.client.ui.VerticalPanel;
import com.lushprojects.circuitjs1.client.CirSim;
import com.lushprojects.circuitjs1.client.CircuitDocument;
import com.lushprojects.circuitjs1.client.util.Locale;

public class ExportAsTextDialog extends Dialog {

    VerticalPanel vp;
    CirSim sim;
    TextArea textArea;

    public ExportAsTextDialog(CirSim asim, String s) {
        super();
        closeOnEnter = false;
        sim = asim;
        TextArea ta;
        Button okButton, importButton, copyButton;

        vp = new VerticalPanel();
        setWidget(vp);
        setText(Locale.LS("Export as Text"));
        vp.add(new Label(Locale.LS("Text file for this circuit is...")));
        vp.add(ta = new TextArea());
        ta.setWidth("400px");
        ta.setHeight("300px");
        ta.setText(s);
        textArea = ta;

        HorizontalPanel hp = new HorizontalPanel();
        hp.setWidth("100%");
        hp.setHorizontalAlignment(HasHorizontalAlignment.ALIGN_LEFT);
        hp.setStyleName("topSpace");
        vp.add(hp);

        hp.add(okButton = new Button(Locale.LS("OK")));
        hp.add(copyButton = new Button(Locale.LS("Copy to Clipboard")));
        hp.setHorizontalAlignment(HasHorizontalAlignment.ALIGN_RIGHT);
        hp.add(importButton = new Button(Locale.LS("Re-Import")));

        okButton.addClickHandler(event -> closeDialog());

        importButton.addClickHandler(event -> {
            String s1;
            CircuitDocument circuitDocument = sim.activeDocument;
            circuitDocument.circuitEditor.pushUndo();
            closeDialog();

            s1 = textArea.getText();
            if (s1 != null) {
                circuitDocument.circuitLoader.readCircuit(s1);
                sim.allowSave(false);
            }
        });

        copyButton.addClickHandler(event -> {
            textArea.setFocus(true);
            textArea.selectAll();
            copyToClipboard();
            textArea.setSelectionRange(0, 0);
        });

        this.center();
    }

    private static native boolean copyToClipboard() /*-{
	    return $doc.execCommand('copy');
	}-*/;

}
