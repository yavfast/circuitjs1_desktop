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

/**
 * Describes a circuit file format.
 * Each format provides exporters and importers for reading/writing circuits.
 */
public interface CircuitFormat {

    /**
     * Get format identifier.
     * @return Unique format ID, e.g., "text", "json"
     */
    String getId();

    /**
     * Get human-readable format name.
     * @return Display name, e.g., "CircuitJS1 Text Format"
     */
    String getName();

    /**
     * Get supported file extensions.
     * @return Array of extensions including dot, e.g., [".txt", ".circuitjs"]
     */
    String[] getFileExtensions();

    /**
     * Get default file extension for this format.
     * @return Default extension, e.g., ".txt"
     */
    default String getDefaultExtension() {
        String[] extensions = getFileExtensions();
        return extensions.length > 0 ? extensions[0] : "";
    }

    /**
     * Get MIME type for this format.
     * @return MIME type, e.g., "text/plain" or "application/json"
     */
    String getMimeType();

    /**
     * Get format version.
     * @return Version string, e.g., "1.0" or "2.0"
     */
    String getVersion();

    /**
     * Create an exporter for this format.
     * @return New exporter instance
     */
    CircuitExporter createExporter();

    /**
     * Create an importer for this format.
     * @return New importer instance
     */
    CircuitImporter createImporter();

    /**
     * Check if this format supports the given file extension.
     * @param extension File extension (with or without dot)
     * @return true if supported
     */
    default boolean supportsExtension(String extension) {
        if (extension == null) {
            return false;
        }
        String ext = extension.startsWith(".") ? extension : "." + extension;
        for (String supported : getFileExtensions()) {
            if (supported.equalsIgnoreCase(ext)) {
                return true;
            }
        }
        return false;
    }
}
