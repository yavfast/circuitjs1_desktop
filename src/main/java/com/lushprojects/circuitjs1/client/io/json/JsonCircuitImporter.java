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

import com.google.gwt.json.client.*;
import com.lushprojects.circuitjs1.client.CirSim;
import com.lushprojects.circuitjs1.client.CircuitDocument;
import com.lushprojects.circuitjs1.client.io.CircuitFormat;
import com.lushprojects.circuitjs1.client.io.CircuitImporter;

/**
 * Imports circuit from JSON format (version 2.0).
 * 
 * This importer handles the new JSON format with explicit
 * element properties and pin connections.
 * 
 * Note: Full implementation is TODO. Currently only validates format.
 */
public class JsonCircuitImporter implements CircuitImporter {

    private final JsonCircuitFormat format;

    public JsonCircuitImporter(JsonCircuitFormat format) {
        this.format = format;
    }

    @Override
    public void importCircuit(String data, CircuitDocument document, int flags) {
        if (data == null || data.trim().isEmpty()) {
            CirSim.console("JSON import: empty data");
            return;
        }

        try {
            JSONValue parsed = JSONParser.parseStrict(data);
            if (parsed == null || parsed.isObject() == null) {
                CirSim.console("JSON import: invalid JSON structure");
                return;
            }

            JSONObject root = parsed.isObject();

            // Validate schema
            if (!validateSchema(root)) {
                CirSim.console("JSON import: invalid or unsupported schema");
                return;
            }

            // TODO: Full implementation
            // 1. Parse simulation parameters
            // 2. Parse elements and create CircuitElm instances
            // 3. Parse scopes
            // 4. Rebuild connections

            CirSim.console("JSON import: format validated, full import TODO");

        } catch (Exception e) {
            CirSim.console("JSON import error: " + e.getMessage());
        }
    }

    private boolean validateSchema(JSONObject root) {
        JSONValue schemaValue = root.get("schema");
        if (schemaValue == null || schemaValue.isObject() == null) {
            return false;
        }

        JSONObject schema = schemaValue.isObject();
        
        JSONValue formatValue = schema.get("format");
        if (formatValue == null || formatValue.isString() == null) {
            return false;
        }
        
        String formatStr = formatValue.isString().stringValue();
        if (!"circuitjs".equals(formatStr)) {
            return false;
        }

        JSONValue versionValue = schema.get("version");
        if (versionValue == null || versionValue.isString() == null) {
            return false;
        }

        String version = versionValue.isString().stringValue();
        // Accept version 2.x
        return version.startsWith("2.");
    }

    @Override
    public boolean canImport(String data) {
        if (data == null || data.trim().isEmpty()) {
            return false;
        }

        String trimmed = data.trim();
        
        // Quick check: must start with '{' for JSON object
        if (!trimmed.startsWith("{")) {
            return false;
        }

        try {
            JSONValue parsed = JSONParser.parseStrict(trimmed);
            if (parsed == null || parsed.isObject() == null) {
                return false;
            }

            JSONObject root = parsed.isObject();
            return validateSchema(root);
            
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public CircuitFormat getFormat() {
        return format;
    }
}
