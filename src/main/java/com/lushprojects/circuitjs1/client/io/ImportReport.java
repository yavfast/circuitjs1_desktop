package com.lushprojects.circuitjs1.client.io;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * [SP_AGA_03_04] "Import reporting": collects every item a text or JSON import skipped, failed
 * or adjusted, with a stable code, a severity and its location — the line number (text) or the
 * element key / index (JSON). The caller passes one report to the importer
 * ({@link CircuitImporter#importCircuit(String, com.lushprojects.circuitjs1.client.CircuitDocument, int, ImportReport)});
 * user loads pass none, so every importer treats a null report as "do not collect" and keeps
 * logging to the console as before.
 * <p>
 * A text import also records here how to undo its changes to the session model catalogues
 * (diode, transistor, custom logic, composite models): one restorer per entry it creates or
 * replaces. A caller that rejects the import runs {@link #restoreModels()}, so no other document
 * sees a model change.
 * <p>
 * Lives in {@code io/} (L2): the agent package (L3) consumes it, importers never import the agent.
 */
public final class ImportReport {

    /** Codes of the SP_AGA_03_04 table (plus the schema failure), as written on the wire. */
    public static final String ELEMENT_SKIPPED = "import_element_skipped";
    public static final String SCHEMA_INVALID = "import_schema_invalid";
    public static final String SCOPE_LIMIT = "scope_limit";
    public static final String WIRE_SKIPPED = "import_wire_skipped";
    public static final String SETTING_INVALID = "import_setting_invalid";
    public static final String GEOMETRY_ADJUSTED = "import_geometry_adjusted";
    public static final String IDS_REGENERATED = "ids_regenerated";

    public enum Severity {
        ERROR, WARNING, INFO
    }

    /** One reported item. */
    public static final class Item {
        public final String code;
        public final Severity severity;
        public final String message;
        /** 1-based line number of a text import, or 0 when not applicable. */
        public final int line;
        /** JSON element key (or {@code #<index>} of a scope/adjustable entry), or null. */
        public final String key;

        Item(String code, Severity severity, String message, int line, String key) {
            this.code = code;
            this.severity = severity;
            this.message = message;
            this.line = line;
            this.key = key;
        }
    }

    private final List<Item> items = new ArrayList<>();
    private final List<Runnable> modelRestorers = new ArrayList<>();

    /** Adds an item reported at a text line (1-based). */
    public void addAtLine(String code, Severity severity, String message, int line) {
        items.add(new Item(code, severity, message, line, null));
    }

    /** Adds an item reported for a JSON element key (null when the item has no key). */
    public void addForKey(String code, Severity severity, String message, String key) {
        items.add(new Item(code, severity, message, 0, key));
    }

    /** @return every item in report order (read-only) */
    public List<Item> getItems() {
        return Collections.unmodifiableList(items);
    }

    /** @return true when any item is an error (the caller rejects the import) */
    public boolean hasErrors() {
        for (Item i : items) {
            if (i.severity == Severity.ERROR) {
                return true;
            }
        }
        return false;
    }

    /**
     * Records how to put one model catalogue entry back as it was before this import changed it.
     * Called by the text importer before it undumps a model line.
     */
    public void addModelRestorer(Runnable restorer) {
        if (restorer != null) {
            modelRestorers.add(restorer);
        }
    }

    /** Restores every recorded model catalogue entry, newest change first. */
    public void restoreModels() {
        for (int i = modelRestorers.size() - 1; i >= 0; i--) {
            modelRestorers.get(i).run();
        }
        modelRestorers.clear();
    }
}
