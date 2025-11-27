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

import com.lushprojects.circuitjs1.client.io.text.TextCircuitFormat;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Registry for circuit file formats.
 * Manages available formats and provides format detection.
 */
public class CircuitFormatRegistry {

    private static final Map<String, CircuitFormat> formats = new LinkedHashMap<>();

    /** Default format ID */
    public static final String DEFAULT_FORMAT_ID = "text";

    static {
        // Register built-in formats
        register(new TextCircuitFormat());
        // JSON format will be registered when implemented:
        // register(new JsonCircuitFormat());
    }

    /**
     * Register a circuit format.
     * @param format Format to register
     */
    public static void register(CircuitFormat format) {
        formats.put(format.getId(), format);
    }

    /**
     * Unregister a circuit format.
     * @param formatId Format ID to unregister
     */
    public static void unregister(String formatId) {
        formats.remove(formatId);
    }

    /**
     * Get format by ID.
     * @param id Format ID
     * @return Format or null if not found
     */
    public static CircuitFormat getById(String id) {
        return formats.get(id);
    }

    /**
     * Get default format.
     * @return Default format (text)
     */
    public static CircuitFormat getDefault() {
        return formats.get(DEFAULT_FORMAT_ID);
    }

    /**
     * Get format by file extension.
     * @param filename Filename or extension
     * @return Matching format or default if not found
     */
    public static CircuitFormat getByExtension(String filename) {
        if (filename == null || filename.isEmpty()) {
            return getDefault();
        }

        String ext = getExtension(filename).toLowerCase();
        for (CircuitFormat format : formats.values()) {
            if (format.supportsExtension(ext)) {
                return format;
            }
        }
        return getDefault();
    }

    /**
     * Detect format from data content.
     * @param data Raw circuit data
     * @return Detected format or null if unknown
     */
    public static CircuitFormat detectFormat(String data) {
        if (data == null || data.isEmpty()) {
            return null;
        }

        for (CircuitFormat format : formats.values()) {
            try {
                if (format.createImporter().canImport(data)) {
                    return format;
                }
            } catch (Exception e) {
                // Ignore detection errors, try next format
            }
        }
        return null;
    }

    /**
     * Detect format from data, with fallback to default.
     * @param data Raw circuit data
     * @return Detected format or default format
     */
    public static CircuitFormat detectFormatOrDefault(String data) {
        CircuitFormat format = detectFormat(data);
        return format != null ? format : getDefault();
    }

    /**
     * Get all registered formats.
     * @return Collection of all formats
     */
    public static Collection<CircuitFormat> getAllFormats() {
        return formats.values();
    }

    /**
     * Check if a format is registered.
     * @param formatId Format ID
     * @return true if registered
     */
    public static boolean isRegistered(String formatId) {
        return formats.containsKey(formatId);
    }

    /**
     * Extract file extension from filename.
     * @param filename Filename
     * @return Extension including dot, or empty string
     */
    public static String getExtension(String filename) {
        if (filename == null) {
            return "";
        }
        int lastDot = filename.lastIndexOf('.');
        if (lastDot < 0) {
            return "";
        }
        // Handle double extensions like .circuit.json
        int prevDot = filename.lastIndexOf('.', lastDot - 1);
        if (prevDot >= 0 && lastDot - prevDot < 10) {
            return filename.substring(prevDot);
        }
        return filename.substring(lastDot);
    }

    /**
     * Create exporter for given format ID.
     * @param formatId Format ID
     * @return Exporter or null if format not found
     */
    public static CircuitExporter createExporter(String formatId) {
        CircuitFormat format = getById(formatId);
        return format != null ? format.createExporter() : null;
    }

    /**
     * Create importer for given format ID.
     * @param formatId Format ID
     * @return Importer or null if format not found
     */
    public static CircuitImporter createImporter(String formatId) {
        CircuitFormat format = getById(formatId);
        return format != null ? format.createImporter() : null;
    }
}
