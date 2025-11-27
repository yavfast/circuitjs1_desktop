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

package com.lushprojects.circuitjs1.client.io;

import com.lushprojects.circuitjs1.client.CircuitDocument;

/**
 * Interface for importing circuits from a specific format.
 */
public interface CircuitImporter {

    /** Flag: retain current circuit state (for paste operations) */
    int RC_RETAIN = 1;

    /** Flag: import only subcircuits/models */
    int RC_SUBCIRCUITS = 2;

    /** Flag: don't center circuit after import */
    int RC_NO_CENTER = 4;

    /** Flag: keep current title */
    int RC_KEEP_TITLE = 8;

    /**
     * Import circuit data into document.
     * @param data Raw circuit data (text or JSON string)
     * @param document Target document
     * @param flags Import flags (RC_RETAIN, RC_SUBCIRCUITS, etc.)
     */
    void importCircuit(String data, CircuitDocument document, int flags);

    /**
     * Import circuit data with default flags.
     * @param data Raw circuit data
     * @param document Target document
     */
    default void importCircuit(String data, CircuitDocument document) {
        importCircuit(data, document, 0);
    }

    /**
     * Check if data appears to be in this format.
     * Used for auto-detection of format.
     * @param data Raw data to check
     * @return true if data appears to be in this format
     */
    boolean canImport(String data);

    /**
     * Get the format this importer handles.
     * @return Associated circuit format
     */
    CircuitFormat getFormat();
}
