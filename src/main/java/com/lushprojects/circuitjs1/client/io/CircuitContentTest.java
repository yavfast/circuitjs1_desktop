package com.lushprojects.circuitjs1.client.io;

import com.google.gwt.json.client.JSONBoolean;
import com.google.gwt.json.client.JSONNull;
import com.google.gwt.json.client.JSONNumber;
import com.google.gwt.json.client.JSONObject;
import com.google.gwt.json.client.JSONString;
import com.lushprojects.circuitjs1.client.CircuitElmCreator;
import com.lushprojects.circuitjs1.client.StringTokenizer;
import com.lushprojects.circuitjs1.client.io.text.TextCircuitImporter;

/**
 * [SP_AGA_03_09] "Circuit test": whether a file's content is a circuit. A side-effect-free parse:
 * it creates no elements and no documents and writes no model catalogue.
 * <ul>
 * <li>JSON: the content parses as a JSON object and passes the JSON importer's schema validation
 *     ({@code schema.format = "circuitjs"}, {@code schema.version} starting with {@code 2.}).</li>
 * <li>Text: every non-empty line, tokenised and normalised exactly as the text importer does it
 *     ({@code TextCircuitImporter.processCircuitLine}, {@link CircuitElmCreator#dumpTypeId}), is a
 *     line type the importer recognises: options {@code $}, an element dump type
 *     ({@link CircuitElmCreator#isKnownDumpType}) followed by the four coordinates and the flags
 *     as whole numbers, scope {@code o}, hint {@code h}, adjustable
 *     {@code 38}, the model prefixes {@code 34}, {@code 32}, {@code !}, {@code .} (first token only;
 *     the model line is not undumped) or an ignored line {@code %}, {@code ?}, {@code B}. A line
 *     with no token (only delimiters) is not a line type and counts as unknown. The content is a
 *     circuit only with zero unknown lines and at least one element or options line.</li>
 * </ul>
 * Format detection by the first character alone is not this test.
 * <p>
 * Lives in {@code io/} (L2): the agent's file contracts and the editor's system-clipboard paste
 * ({@code ClipboardManager}) both use it, so the editor does not depend on the Agent API module.
 */
public final class CircuitContentTest {

    /** Outcome of the test, with the line counts of the text branch. */
    public static final class Result {
        /** {@code "json"}, {@code "text"}, or null when the content is no circuit. */
        public String kind;
        public int elementLines;
        public int optionsLines;
        public int auxLines;
        public int modelLines;
        public int ignoredLines;
        public int unknownLines;
        /** 1-based number of the first unknown line, 0 when none. */
        public int firstUnknownLine;

        private Result(String kind) {
            this.kind = kind;
        }

        public boolean isCircuit() {
            return kind != null;
        }

        /** Harness diagnostic form ({@code CircuitJS1Agent.debugCircuitTest}). */
        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            o.put("circuit", JSONBoolean.getInstance(isCircuit()));
            o.put("kind", kind == null ? JSONNull.getInstance() : new JSONString(kind));
            o.put("elementLines", new JSONNumber(elementLines));
            o.put("optionsLines", new JSONNumber(optionsLines));
            o.put("auxLines", new JSONNumber(auxLines));
            o.put("modelLines", new JSONNumber(modelLines));
            o.put("ignoredLines", new JSONNumber(ignoredLines));
            o.put("unknownLines", new JSONNumber(unknownLines));
            o.put("firstUnknownLine", new JSONNumber(firstUnknownLine));
            return o;
        }
    }

    private CircuitContentTest() {
    }

    /**
     * @return whether {@code content} is a circuit (JSON v2 or legacy text); also the test of a
     *         system-clipboard paste ({@code ClipboardManager})
     */
    public static boolean isCircuit(String content) {
        return test(content).isCircuit();
    }

    /** Runs the test; see the class comment. */
    public static Result test(String content) {
        if (content == null || content.trim().isEmpty()) {
            return new Result(null);
        }
        CircuitFormat json = CircuitFormatRegistry.getById("json");
        // canImport is the importer's own check: a JSON object with the circuitjs 2.x schema
        if (json != null && json.createImporter().canImport(content)) {
            return new Result("json");
        }
        return testText(content);
    }

    private static Result testText(String content) {
        Result r = new Result(null);
        String[] lines = content.split(TextCircuitImporter.LINE_BREAKS);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.trim().isEmpty()) {
                continue;
            }
            StringTokenizer st = new StringTokenizer(line, TextCircuitImporter.DELIMITERS);
            if (!st.hasMoreTokens()) {
                unknown(r, i + 1);
                continue;
            }
            String token = st.nextToken();
            switch (CircuitElmCreator.dumpTypeId(token)) {
                case '$':
                    r.optionsLines++;
                    break;
                case 'o': // scope
                case 'h': // hint
                case 38: // adjustable ('&')
                    r.auxLines++;
                    break;
                case 34: // diode model ('"')
                case 32: // transistor model
                case '!': // custom logic model
                case '.': // custom composite model
                    r.modelLines++;
                    break;
                case '%':
                case '?':
                case 'B':
                    r.ignoredLines++;
                    break;
                default:
                    if (CircuitElmCreator.isKnownDumpType(token) && hasElementFields(st)) {
                        r.elementLines++;
                    } else {
                        unknown(r, i + 1);
                    }
                    break;
            }
        }
        if (r.unknownLines == 0 && (r.elementLines > 0 || r.optionsLines > 0)) {
            r.kind = "text";
        }
        return r;
    }

    /**
     * An element line carries the four endpoint coordinates and the flags as whole numbers after
     * its type, as every element dump writes them (the importer reads them first, before the
     * element's own fields). Without this, prose whose lines start with an element letter
     * ({@code "This ..."}, {@code "remember ..."}) would count as element lines.
     */
    private static boolean hasElementFields(StringTokenizer st) {
        for (int k = 0; k < 5; k++) {
            if (!st.hasMoreTokens() || !isWholeNumber(st.nextToken())) {
                return false;
            }
        }
        return true;
    }

    private static boolean isWholeNumber(String token) {
        int start = token.startsWith("-") ? 1 : 0;
        if (token.length() == start || token.length() > 11) {
            return false;
        }
        for (int i = start; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static void unknown(Result r, int line) {
        if (r.unknownLines == 0) {
            r.firstUnknownLine = line;
        }
        r.unknownLines++;
    }
}
