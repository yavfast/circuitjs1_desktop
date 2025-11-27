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

package com.lushprojects.circuitjs1.client.io.text;

import com.lushprojects.circuitjs1.client.io.CircuitExporter;
import com.lushprojects.circuitjs1.client.io.CircuitFormat;
import com.lushprojects.circuitjs1.client.io.CircuitImporter;

/**
 * Original CircuitJS1 text format.
 * This is the default format used by CircuitJS1 for saving and loading circuits.
 */
public class TextCircuitFormat implements CircuitFormat {

    @Override
    public String getId() {
        return "text";
    }

    @Override
    public String getName() {
        return "CircuitJS1 Text Format";
    }

    @Override
    public String[] getFileExtensions() {
        return new String[] { ".txt", ".circuitjs" };
    }

    @Override
    public String getMimeType() {
        return "text/plain";
    }

    @Override
    public String getVersion() {
        return "1.0";
    }

    @Override
    public CircuitExporter createExporter() {
        return new TextCircuitExporter(this);
    }

    @Override
    public CircuitImporter createImporter() {
        return new TextCircuitImporter(this);
    }
}
