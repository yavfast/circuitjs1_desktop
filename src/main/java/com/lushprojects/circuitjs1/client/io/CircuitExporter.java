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
import com.lushprojects.circuitjs1.client.element.CircuitElm;

import java.util.List;

/**
 * Interface for exporting circuits to a specific format.
 */
public interface CircuitExporter {

    /**
     * Export the entire circuit document.
     * @param document Circuit document to export
     * @return Exported data as string
     */
    String export(CircuitDocument document);

    /**
     * Export only selected elements.
     * @param document Circuit document containing the elements
     * @param selection List of selected elements to export
     * @return Exported data as string
     */
    default String exportSelection(CircuitDocument document, List<CircuitElm> selection) {
        // Default implementation exports the full circuit
        // Subclasses can override for selection-specific export
        return export(document);
    }

    /**
     * Export the entire circuit document with optional simulation state.
     * @param document Circuit document to export
     * @param includeState If true, include simulation state (pin voltages, currents, etc.)
     * @return Exported data as string
     */
    default String export(CircuitDocument document, boolean includeState) {
        // Default implementation exports without state
        return export(document);
    }

    /**
     * Get the format this exporter produces.
     * @return Associated circuit format
     */
    CircuitFormat getFormat();
}
