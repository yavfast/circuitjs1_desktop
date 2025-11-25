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

import com.lushprojects.circuitjs1.client.dialog.EditDialogLoadFile;
import com.lushprojects.circuitjs1.client.element.SRAMElm;

public class SRAMLoadFile extends EditDialogLoadFile {

    private final BaseCirSim cirSim;

    public SRAMLoadFile(BaseCirSim cirSim) {
        this.cirSim = cirSim;
    }

    public final native void handle()
	/*-{
		var that = this;
		var oFiles = $doc.getElementById("EditDialogLoadFileElement").files,
		nFiles = oFiles.length;
		if (nFiles>=1) {
			if (oFiles[0].size >= 128000) {
				@com.lushprojects.circuitjs1.client.dialog.EditDialogLoadFile::doErrorCallback(Ljava/lang/String;)("Cannot load: That file is too large!");
				return;
			}
			
			var reader = new FileReader();
			reader.onload = function(e) {
				var arr = new Uint8Array(reader.result);
				var str = "0:";
				for (var i = 0; i < arr.length; i++)
					str += " " + arr[i];
				that.@com.lushprojects.circuitjs1.client.SRAMLoadFile::handleLoad(Ljava/lang/String;)(str);
			};
	
			reader.readAsArrayBuffer(oFiles[0]);
		}
	}-*/;

    private void handleLoad(String data) {
        SRAMElm.contentsOverride = data;
        cirSim.dialogManager.resetEditDialog();
        SRAMElm.contentsOverride = null;
    }
}
