package com.lushprojects.circuitjs1.client.agent;

import com.google.gwt.json.client.JSONArray;
import com.google.gwt.json.client.JSONException;
import com.google.gwt.json.client.JSONNumber;
import com.google.gwt.json.client.JSONObject;
import com.google.gwt.json.client.JSONParser;
import com.google.gwt.json.client.JSONString;
import com.google.gwt.json.client.JSONValue;
import com.lushprojects.circuitjs1.client.CircuitDocument;
import com.lushprojects.circuitjs1.client.ElementIdRegistry;
import com.lushprojects.circuitjs1.client.element.CircuitElm;
import com.lushprojects.circuitjs1.client.io.ImportReport;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * [SP_AGA_02_03] importCircuit: replace a document's circuit in one call, from an AgentCircuit,
 * a JSON v2 circuit text or a legacy text circuit.
 * <ol>
 * <li>An AgentCircuit is validated completely ([SP_AGA_03_01]–[SP_AGA_03_03], 1/16-cell lattice)
 *     and converted to JSON v2; a JSON v2 text is checked for its schema and for fractional pixel
 *     coordinates ({@code off_lattice}) before anything is loaded.</li>
 * <li>The content is loaded through the format registry inside a {@link Mutation}. The importers
 *     report every skipped, failed or adjusted item to an {@link ImportReport}; any error item
 *     rejects the import: the model catalogue entries the text import changed are put back, then
 *     the snapshot is restored ([SP_AGA_03_04]).</li>
 * <li>The grid is pinned by the content: the importers apply the grid option the content selects
 *     (the options line, {@code display.small_grid}, else 16) before creating elements
 *     ([SP_AGA_03_01]).</li>
 * </ol>
 * The issues of every call are kept on the document for {@code getDiagnostics.lastImport}.
 */
final class ImportOps {

    /** Maximum length of a circuit string (10 MB). */
    static final int MAX_TEXT = 10 * 1024 * 1024;
    /** Maximum number of element IDs listed in the result. */
    static final int MAX_IDS = 200;

    private ImportOps() {
    }

    static void register(AgentApi api) {
        api.register("importCircuit", AgentApi.DocPolicy.OPTIONAL, AgentApi.BusyPolicy.REJECTED, ImportOps::importCircuit);
    }

    static OperationResult importCircuit(AgentApi.Call call) {
        JSONValue circuit = call.args.raw("circuit");
        if (circuit == null) {
            call.args.invalid("circuit", "is required", "Pass an AgentCircuit object, a JSON v2 circuit text or a legacy text circuit.");
            return call.args.failure();
        }
        final Catalogue cat = call.sim.getAgentCatalogue();
        List<Issue> issues = new ArrayList<>();
        final String content;
        final String formatId;
        final AgentCircuitConverter.Converted agent;
        if (circuit.isObject() != null) {
            agent = AgentCircuitConverter.convertCircuit(circuit.isObject(), cat, issues);
            content = agent == null ? null : agent.json;
            formatId = "json";
        } else if (circuit.isString() != null) {
            agent = null;
            String text = circuit.isString().stringValue();
            if (text.length() > MAX_TEXT) {
                call.args.invalid("circuit", "is longer than 10 MB", "Split the circuit or use applyEdits.");
                return call.args.failure();
            }
            if (text.trim().isEmpty()) {
                call.args.invalid("circuit", "is empty", "Pass the circuit text.");
                return call.args.failure();
            }
            if (text.trim().startsWith("{")) {
                checkJsonText(text, issues);
                formatId = "json";
            } else {
                checkLegacyText(text, issues);
                formatId = "text";
            }
            content = text;
        } else {
            call.args.invalid("circuit", "must be an AgentCircuit object or a circuit string",
                    "Pass {elements: [...]}, a JSON v2 text or a legacy text circuit.");
            return call.args.failure();
        }
        final CircuitDocument doc = call.doc;
        if (!issues.isEmpty()) {
            OperationResult rejected = OperationResult.failure(issues);
            keepLastImport(doc, rejected);
            return rejected;
        }
        OperationResult result = Mutation.run(call.sim, doc, ctx -> load(ctx, content, formatId, agent, cat));
        keepLastImport(doc, result);
        return result;
    }

    private static OperationResult load(Mutation.Context ctx, String content, String formatId,
            AgentCircuitConverter.Converted agent, Catalogue cat) {
        CircuitDocument doc = ctx.doc;
        final ImportReport report = new ImportReport();
        // Model catalogue entries changed by a text import go back before the snapshot reload
        ctx.onRollback(report::restoreModels);
        doc.circuitLoader.readCircuit(content, formatId, 0, report);
        ctx.checkForcedFailure("after the load of importCircuit");

        List<Issue> issues = new ArrayList<>();
        for (ImportReport.Item item : report.getItems()) {
            issues.add(toIssue(item));
        }
        if (report.hasErrors()) {
            throw new Mutation.Rejected(issues);
        }
        if (agent != null) {
            // [SP_AGA_03_03] "Ranges": values the elements clamped are reported with the effective value
            Map<String, CircuitElm> byId = CircuitView.byId(doc);
            for (int i = 0; i < agent.specs.size(); i++) {
                AgentCircuitConverter.Spec spec = agent.specs.get(i);
                CircuitElm elm = byId.get(agent.ids.get(i));
                if (elm == null) {
                    continue;
                }
                if (spec.flags != null) {
                    // explicit flags win over the TypeInfo defaults of property-backed bits
                    EditOps.applyExplicitFlags(elm, spec.flags, issues);
                }
                Map<String, Object> after = PropertyValues.current(elm);
                for (Map.Entry<String, Object> p : spec.given.entrySet()) {
                    Catalogue.PropertyInfo info = spec.type.property(p.getKey());
                    Object effective = after.get(p.getKey());
                    if (!PropertyValues.same(p.getValue(), effective, info == null ? null : info.unit)) {
                        issues.add(Issue.of(IssueCode.VALUE_ADJUSTED, elm.getElementId() + "." + p.getKey() + " is "
                                + PropertyValues.display(effective) + " instead of the requested "
                                + PropertyValues.display(p.getValue()) + ".",
                                "The element clamps or derives this value; use the effective value.")
                                .elements(elm.getElementId()));
                    }
                }
            }
        }
        Mutation.finish(ctx);

        List<CircuitElm> elms = doc.simulator.elmList;
        JSONObject data = new JSONObject();
        data.put("elements", new JSONNumber(elms.size()));
        if (elms.size() <= MAX_IDS) {
            JSONArray ids = new JSONArray();
            for (int i = 0; i < elms.size(); i++) {
                ids.set(i, new JSONString(elms.get(i).getElementId()));
            }
            data.put("ids", ids);
        }
        OperationResult result = OperationResult.success(data);
        for (Issue i : issues) {
            result.addIssue(i);
        }
        return result;
    }

    /**
     * JSON v2 text checks before loading: valid JSON, the circuitjs 2.x schema, and whole-pixel
     * coordinates within range in every pin position and {@code p1}/{@code p2} ([SP_AGA_03_01]:
     * fractional pixels are {@code off_lattice}, never truncated).
     */
    private static void checkJsonText(String text, List<Issue> issues) {
        JSONValue parsed;
        try {
            parsed = JSONParser.parseStrict(text);
        } catch (JSONException | IllegalArgumentException e) {
            issues.add(Issue.of(IssueCode.IMPORT_SCHEMA_INVALID, "The circuit text is not valid JSON.",
                    "Pass a JSON v2 circuit ({schema: {format: \"circuitjs\", version: \"2.0\"}, elements: {...}})."));
            return;
        }
        JSONObject root = parsed == null ? null : parsed.isObject();
        JSONObject schema = root == null || root.get("schema") == null ? null : root.get("schema").isObject();
        JSONString format = schema == null || schema.get("format") == null ? null : schema.get("format").isString();
        JSONString version = schema == null || schema.get("version") == null ? null : schema.get("version").isString();
        if (format == null || !"circuitjs".equals(format.stringValue()) || version == null
                || !version.stringValue().startsWith("2.")) {
            issues.add(Issue.of(IssueCode.IMPORT_SCHEMA_INVALID, "The JSON circuit has no circuitjs 2.x schema.",
                    "Add \"schema\": {\"format\": \"circuitjs\", \"version\": \"2.0\"}."));
            return;
        }
        JSONObject elements = root.get("elements") == null ? null : root.get("elements").isObject();
        if (elements == null) {
            return;
        }
        for (String key : elements.keySet()) {
            JSONObject e = elements.get(key).isObject();
            if (e == null) {
                continue; // reported by the importer (import_element_skipped)
            }
            String subject = ElementIdRegistry.isValidId(key) ? key : null;
            JSONObject pins = e.get("pins") == null ? null : e.get("pins").isObject();
            if (pins != null) {
                for (String pin : pins.keySet()) {
                    JSONObject p = pins.get(pin).isObject();
                    JSONObject pos = p == null || p.get("position") == null ? null : p.get("position").isObject();
                    checkPixel(pos, "elements." + key + ".pins." + pin + ".position", issues, subject, "x", "y");
                }
            }
            for (String pk : new String[] { "p1", "p2" }) {
                JSONObject p = e.get(pk) == null ? null : e.get(pk).isObject();
                checkPixel(p, "elements." + key + "." + pk, issues, subject, "x", "y");
            }
            // bounds feed the geometry of single-post elements without _endpoint (factory)
            JSONObject b = e.get("bounds") == null ? null : e.get("bounds").isObject();
            checkPixel(b, "elements." + key + ".bounds", issues, subject, "left", "top", "right", "bottom");
        }
    }

    private static void checkPixel(JSONObject pos, String where, List<Issue> issues, String subject, String... axes) {
        if (pos == null) {
            return;
        }
        for (String axis : axes) {
            JSONValue v = pos.get(axis);
            JSONNumber n = v == null ? null : v.isNumber();
            if (n == null) {
                continue; // the importer skips non-numeric coordinates as before
            }
            double d = n.doubleValue();
            if (Math.abs(d) > CellGeometry.MAX_CELLS * CellGeometry.CELL_PX) {
                issues.add(CellGeometry.withSubject(Issue.of(IssueCode.INVALID_VALUE, "Coordinate '" + where + "." + axis
                        + "' is out of range.", "Keep coordinates within ±4096 cells (±65536 px)."), subject));
            } else if (d != Math.rint(d)) {
                issues.add(CellGeometry.withSubject(Issue.of(IssueCode.OFF_LATTICE, "Coordinate '" + where + "." + axis
                        + "' = " + d + " px is not a whole pixel.", "JSON v2 coordinates are whole pixels (1/16 cell)."),
                        subject));
            }
        }
    }

    /** Line types of the text format that carry no element coordinates. */
    private static final String[] NON_ELEMENT_LINES = { "$", "o", "h", "%", "?", "B", "!", ".", "&", "34", "32", "38" };

    /**
     * Legacy text check before loading ([SP_AGA_03_01]): the coordinates of every element line lie
     * within ±4096 cells (±65536 px); out of range is {@code invalid_value} with the line number.
     * Text coordinates are whole pixels, so they are always on the import lattice.
     */
    private static void checkLegacyText(String text, List<Issue> issues) {
        String[] lines = text.split("\r\n|\n|\r");
        int limit = (int) (CellGeometry.MAX_CELLS * CellGeometry.CELL_PX);
        for (int i = 0; i < lines.length; i++) {
            String[] tok = lines[i].trim().split("[ +\t]+");
            if (tok.length < 5 || tok[0].isEmpty() || isNonElementLine(tok[0])) {
                continue;
            }
            for (int k = 1; k <= 4; k++) {
                int v;
                try {
                    v = Integer.parseInt(tok[k]);
                } catch (NumberFormatException e) {
                    break; // left to the importer, which reports the line
                }
                if (Math.abs(v) > limit) {
                    issues.add(Issue.of(IssueCode.INVALID_VALUE, "Line " + (i + 1) + ": coordinate " + k
                            + " is out of range.", "Keep coordinates within ±4096 cells (±65536 px)."));
                    break;
                }
            }
        }
    }

    private static boolean isNonElementLine(String type) {
        for (String t : NON_ELEMENT_LINES) {
            if (t.equals(type)) {
                return true;
            }
        }
        return false;
    }

    private static final Map<String, IssueCode> CODES = new HashMap<>();

    /** @return the Issue of an importer report item (code, severity, message, element key) */
    static Issue toIssue(ImportReport.Item item) {
        if (CODES.isEmpty()) {
            for (IssueCode c : IssueCode.values()) {
                CODES.put(c.code(), c);
            }
        }
        IssueCode code = CODES.get(item.code);
        if (code == null) {
            code = IssueCode.IMPORT_ELEMENT_SKIPPED;
        }
        Issue.Severity severity = item.severity == ImportReport.Severity.ERROR ? Issue.Severity.ERROR
                : item.severity == ImportReport.Severity.WARNING ? Issue.Severity.WARNING : Issue.Severity.INFO;
        String message = item.message;
        if (!message.endsWith(".")) {
            message = message + ".";
        }
        message = Character.toUpperCase(message.charAt(0)) + message.substring(1);
        Issue issue = Issue.of(code, severity, message, hintFor(code));
        // only element-scoped items name an element; a setting key ("time_step") is no ElementId
        boolean elementScoped = code == IssueCode.IMPORT_ELEMENT_SKIPPED || code == IssueCode.IMPORT_WIRE_SKIPPED
                || code == IssueCode.IMPORT_GEOMETRY_ADJUSTED || code == IssueCode.IDS_REGENERATED;
        if (elementScoped && item.key != null && ElementIdRegistry.isValidId(item.key)) {
            issue.elements(item.key);
        }
        return issue;
    }

    private static String hintFor(IssueCode code) {
        switch (code) {
            case IMPORT_ELEMENT_SKIPPED:
                return "Fix or remove the line or element; describeType and EXPORT_OLD.md list the valid forms.";
            case IMPORT_SCHEMA_INVALID:
                return "Pass a JSON v2 circuit with \"schema\": {\"format\": \"circuitjs\", \"version\": \"2.0\"}.";
            case SCOPE_LIMIT:
                return "At most 20 scope views are kept; remove the others.";
            case IMPORT_WIRE_SKIPPED:
                return "Point connected_to at an existing element and pin.";
            case IMPORT_SETTING_INVALID:
                return "Use a positive value with its unit, e.g. \"5 us\".";
            case IMPORT_GEOMETRY_ADJUSTED:
                return "Give the geometry once, by the pins or by p1/p2.";
            case IDS_REGENERATED:
                return "Use unique keys matching ^[A-Za-z][A-Za-z0-9_]{0,31}$.";
            default:
                return "See the issue message.";
        }
    }

    /** Keeps the issues of this import on the document ([SP_AGA_01_11] lastImport). */
    private static void keepLastImport(CircuitDocument doc, OperationResult result) {
        JSONArray list = new JSONArray();
        for (Issue i : result.getIssues()) {
            list.set(list.size(), i.toJson());
        }
        doc.setLastImportIssues(list);
    }
}
