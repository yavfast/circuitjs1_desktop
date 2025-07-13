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

package com.lushprojects.circuitjs1.client;

import com.google.gwt.event.dom.client.ChangeEvent;
import com.google.gwt.event.dom.client.ChangeHandler;
import com.google.gwt.user.client.ui.FileUpload;

public class LoadFile extends FileUpload implements ChangeHandler {

    final CirSim sim;

    static LoadFile sLoadFile;

    static public native boolean isSupported() /*-{
        return !!($wnd.File && $wnd.FileReader);
    }-*/;

    public static void doLoadCallback(String s, String t) {
        sLoadFile.doLoad(s, t);
    }

    public void doLoad(String s, String t) {
        sim.circuitEditor.pushUndo();
        sim.circuitLoader.readCircuit(s);
        sim.createNewLoadFile();
        sim.setCircuitTitle(t);
        sim.setLastFileName(t);
        sim.setUnsavedChanges(false);
    }

    LoadFile(CirSim s) {
        super();
        sim = s;
        this.setName("Import");
        this.getElement().setId("LoadFileElement");
        this.addChangeHandler(this);
        this.addStyleName("offScreen");
        sLoadFile = this;
    }


    public void onChange(ChangeEvent e) {
        doLoad();
    }

    public native String getPath() /*-{
		return $doc.getElementById("LoadFileElement").value;
	}-*/;

    public native String getFileName() /*-{
		return $doc.getElementById("LoadFileElement").files[0].name;
	}-*/;

    public native void click()/*-{
		$doc.getElementById("LoadFileElement").click();
	}-*/;

    static public native void doLoad() /*-{
        var oFiles = $doc.getElementById("LoadFileElement").files,
        nFiles = oFiles.length;
        if (nFiles>=1) {
            if (oFiles[0].size >= 128000)
                alert("File too large!");
            else {
            var reader = new FileReader();
            reader.onload = function(e) {
                var text = reader.result;
                @com.lushprojects.circuitjs1.client.LoadFile::doLoadCallback(Ljava/lang/String;Ljava/lang/String;)(text, oFiles[0].name);
            };

            reader.readAsText(oFiles[0]);
            }
        }
    }-*/;

}
