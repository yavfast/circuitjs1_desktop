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

package com.lushprojects.circuitjs1.client.io.json;

import com.lushprojects.circuitjs1.client.io.CircuitExporter;
import com.lushprojects.circuitjs1.client.io.CircuitFormat;
import com.lushprojects.circuitjs1.client.io.CircuitImporter;

/**
 * JSON circuit format implementation.
 * 
 * This format provides a human-readable, self-documenting representation
 * of circuit data with explicit element properties, pin connections,
 * and simulation parameters.
 * 
 * File extensions: .json, .circuitjs.json
 * MIME type: application/json
 * 
 * Format version: 2.0
 */
public class JsonCircuitFormat implements CircuitFormat {

    public static final String FORMAT_ID = "json";
    public static final String FORMAT_NAME = "CircuitJS JSON";
    public static final String FORMAT_VERSION = "2.0";
    public static final String[] EXTENSIONS = {".json", ".circuitjs.json"};
    public static final String MIME_TYPE = "application/json";

    @Override
    public String getId() {
        return FORMAT_ID;
    }

    @Override
    public String getName() {
        return FORMAT_NAME;
    }

    @Override
    public String getVersion() {
        return FORMAT_VERSION;
    }

    @Override
    public String[] getFileExtensions() {
        return EXTENSIONS;
    }

    @Override
    public String getMimeType() {
        return MIME_TYPE;
    }

    @Override
    public CircuitExporter createExporter() {
        return new JsonCircuitExporter(this);
    }

    @Override
    public CircuitImporter createImporter() {
        return new JsonCircuitImporter(this);
    }
}
