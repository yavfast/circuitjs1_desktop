package com.lushprojects.circuitjs1.client.agent;

import com.google.gwt.core.client.GWT;
import com.google.gwt.json.client.JSONNumber;
import com.google.gwt.json.client.JSONObject;
import com.google.gwt.json.client.JSONString;
import com.google.gwt.json.client.JSONValue;
import com.lushprojects.circuitjs1.client.CirSim;
import com.lushprojects.circuitjs1.client.CircuitDocument;
import com.lushprojects.circuitjs1.client.DocumentManager;
import com.lushprojects.circuitjs1.client.DocumentScope;
import com.lushprojects.circuitjs1.client.PathFileAdapter;
import com.lushprojects.circuitjs1.client.io.CircuitContentTest;
import com.lushprojects.circuitjs1.client.io.CircuitFormat;
import com.lushprojects.circuitjs1.client.io.CircuitFormatRegistry;
import com.lushprojects.circuitjs1.client.io.ImportReport;
import com.lushprojects.circuitjs1.client.util.EchoText;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * [SP_AGA_02_14] Path-based files: {@code openFile} and {@code saveFile}, through
 * {@link PathFileAdapter} under the file rules of [SP_AGA_03_09] (SP_MCP_DEC_03: circuit files
 * only).
 * <ul>
 * <li>Without the desktop runtime both return {@code file_unavailable}, before any argument
 *     check.</li>
 * <li>Allowed files: an absolute path ending in {@code .txt} or {@code .json} (any case); an
 *     existing file reached through a symbolic link must also have such a name.</li>
 * <li>{@code openFile} reads at most 10 MB and opens only content that passes the circuit test
 *     ({@link CircuitContentTest}); into a handle it runs the {@code importCircuit} processing (agent
 *     transaction), into {@code "new"} it loads the file like a user load into a new background
 *     document (undo history reset and seeded, no transaction). A rejected open changes nothing
 *     (a new document is discarded without a closed-tab entry) and reports codes, line numbers
 *     and counts only.</li>
 * <li>{@code saveFile} overwrites an existing file only when it is empty (or blank) or its
 *     content is a circuit; it seals the open agent transaction first (a save of the document,
 *     [SP_AGA_04_01]) and writes through a flushed staging file renamed over the target — the
 *     target it checked: a target that resolves differently at the write is {@code file_error}.</li>
 * <li>After a successful open or save the document's file path and name (its title) are set and
 *     its modified flag is cleared, inside {@code DocumentScope}, through the setters the user
 *     load uses ({@code BaseCirSim.setLastFileName}, {@code allowSave}, {@code setUnsavedChanges},
 *     which also refreshes the tab title; the window title and Save item of the visible tab are
 *     re-derived at the scope exit).</li>
 * </ul>
 */
final class FileOps {

    /** Largest file openFile reads, and largest existing file saveFile checks before overwriting. */
    static final double MAX_BYTES = 10 * 1024 * 1024;
    /** At most this many line numbers are listed per code in a content-free issue. */
    private static final int MAX_LINES_LISTED = 20;

    private FileOps() {
    }

    static void register(AgentApi api) {
        // openFile names its target with "into", not "doc": the busy rule (into a handle only) and
        // the transaction field (into a handle only) are applied by the handler
        api.register("openFile", AgentApi.DocPolicy.NONE, AgentApi.BusyPolicy.SERVED, FileOps::openFile);
        api.register("saveFile", AgentApi.DocPolicy.OPTIONAL, AgentApi.BusyPolicy.SERVED, FileOps::saveFile);
    }

    // ---------------------------------------------------------------- openFile

    static OperationResult openFile(AgentApi.Call call) {
        if (!PathFileAdapter.isAvailable()) {
            return unavailable();
        }
        String path = call.args.requireString("path");
        boolean activate = call.args.optBool("activate", false);
        CircuitDocument target = null;
        JSONValue into = call.args.raw("into");
        if (into != null) {
            if (into.isString() == null) {
                call.args.invalid("into", "must be \"new\" or a document handle",
                        "Pass \"new\" or one of the open documents: " + DocumentHandles.listOpen(call.sim) + ".");
            } else if (!"new".equals(into.isString().stringValue())) {
                String handle = into.isString().stringValue();
                target = DocumentHandles.find(call.sim, handle);
                if (target == null) {
                    return OperationResult.failure(DocumentHandles.unknown(call.sim, handle));
                }
            }
        }
        if (call.args.failed()) {
            return withTransaction(call.args.failure(), target);
        }
        if (target != null && target.isAgentBusy()) {
            return withTransaction(OperationResult.failure(AgentApi.busyIssue(target)), target);
        }
        Issue notAllowed = checkPath(path, "path");
        if (notAllowed != null) {
            return withTransaction(OperationResult.failure(notAllowed), target);
        }
        PathFileAdapter.Result file = PathFileAdapter.read(path, MAX_BYTES);
        OperationResult failed = readFailure(file, path);
        if (failed == null) {
            failed = checkRealPath(file);
        }
        if (failed == null) {
            CircuitContentTest.Result test = CircuitContentTest.test(file.text);
            if (!test.isCircuit()) {
                failed = OperationResult.failure(notACircuit(test, "is not a circuit file"));
            }
        }
        if (failed != null) {
            return withTransaction(failed, target);
        }
        OperationResult result = target != null ? openInto(call, target, file) : openNew(call, file);
        if (result.isOk() && activate) {
            CircuitDocument doc = target != null ? target : DocumentHandles.find(call.sim,
                    result.getData().get("doc").isString().stringValue());
            call.sim.documentManager.setActiveDocument(doc);
        }
        return withTransaction(result, target);
    }

    /** [SP_AGA_02_14] "Opening into a document": the importCircuit processing, then the file state. */
    private static OperationResult openInto(AgentApi.Call call, final CircuitDocument doc, PathFileAdapter.Result file) {
        OperationResult loaded = ImportOps.importString(call.sim, doc, file.text);
        if (!loaded.isOk()) {
            OperationResult rejected = withoutContent(loaded);
            ImportOps.keepLastImport(doc, rejected);
            return rejected;
        }
        ImportOps.keepLastImport(doc, loaded);
        // applied last: the modified flag the mutation set is cleared
        final String path = file.path;
        DocumentScope.run(call.sim, doc, () -> setFileState(call.sim, doc, path));
        return loaded.withData(openData(doc));
    }

    /** [SP_AGA_02_14] Into {@code "new"}: a user-like load into a new background document. */
    private static OperationResult openNew(AgentApi.Call call, PathFileAdapter.Result file) {
        final CirSim sim = call.sim;
        final String content = file.text;
        List<Issue> pre = new ArrayList<>();
        final String formatId = ImportOps.checkString(content, pre, null);
        if (!pre.isEmpty()) {
            return withoutContent(OperationResult.failure(pre));
        }
        DocumentManager dm = sim.documentManager;
        final CircuitDocument doc = dm.createDocument();
        final ImportReport report = new ImportReport();
        final String path = file.path;
        try {
            DocumentScope.run(sim, doc, () -> {
                doc.circuitLoader.readCircuit(content, formatId, 0, report);
                if (report.hasErrors()) {
                    return;
                }
                // as the user load (LoadFile.doLoad): history reset and seeded with the loaded state
                doc.undoManager.resetAndSeedFromCurrentCircuit();
                sim.needAnalyze();
                // nodes only, as importCircuit: the stamp waits for a run or reading
                doc.ensureNodesAnalysed();
                setFileState(sim, doc, path);
            });
        } catch (Throwable t) {
            report.restoreModels();
            dm.discardDocument(doc);
            // Shown and logged as before (RULE_ERR_004); the caller gets the message
            GWT.reportUncaughtException(t);
            return OperationResult.failure(Issue.of(IssueCode.INTERNAL_ERROR,
                    // no exception text: it could quote file content (the global handler shows it)
                    "Internal error while opening the file; no document was created.",
                    "Report the error; the call can be retried."));
        }
        List<Issue> issues = ImportOps.issuesOf(report);
        if (report.hasErrors()) {
            // [SP_AGA_03_04] model catalogue entries the text load changed go back; no document
            report.restoreModels();
            dm.discardDocument(doc);
            return withoutContent(OperationResult.failure(issues));
        }
        OperationResult result = OperationResult.success(openData(doc));
        for (Issue i : issues) {
            result.addIssue(i);
        }
        ImportOps.keepLastImport(doc, result);
        return result;
    }

    private static JSONObject openData(CircuitDocument doc) {
        JSONObject data = new JSONObject();
        data.put("doc", new JSONString(DocumentHandles.of(doc)));
        data.put("elements", new JSONNumber(doc.simulator.elmList.size()));
        return data;
    }

    /** openFile into a handle is mutating: its results carry the target's transaction state. */
    private static OperationResult withTransaction(OperationResult r, CircuitDocument target) {
        if (target != null) {
            r.setTransaction(AgentTransaction.toJson(target));
        }
        return r;
    }

    // ---------------------------------------------------------------- saveFile

    static OperationResult saveFile(AgentApi.Call call) {
        if (!PathFileAdapter.isAvailable()) {
            return unavailable();
        }
        String given = call.args.optString("path", null);
        String format = call.args.optEnum("format", new String[] { "text", "json" }, null);
        if (call.args.failed()) {
            return call.args.failure();
        }
        final CircuitDocument doc = call.doc;
        String path = given != null ? given : doc.circuitInfo.getFilePath();
        if (path == null) {
            return OperationResult.failure(Issue.of(IssueCode.NO_PATH,
                    "Document " + DocumentHandles.of(doc) + " has never been saved and no path was given.",
                    "Pass an absolute path ending in .txt or .json."));
        }
        Issue notAllowed = checkPath(path, given != null ? "path" : "the document's file path");
        if (notAllowed != null) {
            return OperationResult.failure(notAllowed);
        }
        final Checked checked = checkOverwrite(path);
        if (checked.refused != null) {
            return checked.refused;
        }
        if (format == null) {
            format = path.toLowerCase().endsWith(".json") ? "json" : "text";
        }
        final CircuitFormat f = CircuitFormatRegistry.getById(format);
        final String target = path;
        final CirSim sim = call.sim;
        return DocumentScope.call(sim, doc, () -> {
            // [SP_AGA_02_14] "Before saving" / [SP_AGA_04_01]: any save seals the open transaction
            doc.undoManager.sealTransaction();
            sim.enableUndoRedo();
            String content = f.createExporter().export(doc);
            // [SP_AGA_03_09] the write goes to the target checked above, or is refused
            PathFileAdapter.Result written = PathFileAdapter.write(target, content, checked.realPath);
            if (!written.isOk()) {
                return writeFailure(written);
            }
            setFileState(sim, doc, written.path);
            JSONObject data = new JSONObject();
            data.put("path", new JSONString(written.path));
            data.put("bytes", new JSONNumber(written.size));
            return OperationResult.success(data);
        });
    }

    /** Outcome of the overwrite check: the rejection, or the checked target. */
    private static final class Checked {
        final OperationResult refused;
        /** Resolved path of the existing file that was checked; null when no file existed. */
        final String realPath;

        Checked(OperationResult refused, String realPath) {
            this.refused = refused;
            this.realPath = realPath;
        }
    }

    private static Checked refuse(OperationResult r) {
        return new Checked(r, null);
    }

    /**
     * [SP_AGA_03_09] "Overwriting": an existing target must be a regular file that is empty or
     * blank, or whose content is a circuit. A missing file may be created. The checked target
     * (its resolved path) is what the write must still find.
     */
    private static Checked checkOverwrite(String path) {
        PathFileAdapter.Result st = PathFileAdapter.stat(path);
        switch (st.status) {
            case NOT_FOUND:
                return new Checked(null, null);
            case NOT_REGULAR:
                return refuse(OperationResult.failure(Issue.of(IssueCode.FILE_NOT_ALLOWED,
                        "The path " + path(st.path) + " exists and is not a regular file.",
                        "Save to a .txt or .json circuit file.")));
            case OK:
                break;
            default:
                return refuse(OperationResult.failure(fileError("Cannot check the existing file", st)));
        }
        OperationResult real = checkRealPath(st);
        if (real != null) {
            return refuse(real);
        }
        Checked ok = new Checked(null, st.realPath);
        if (st.size == 0) {
            return ok;
        }
        if (st.size > MAX_BYTES) {
            return refuse(OperationResult.failure(Issue.of(IssueCode.FILE_NOT_ALLOWED,
                    "The existing file " + path(st.path) + " is larger than 10 MB and is not overwritten.",
                    "Save to a new file.")));
        }
        PathFileAdapter.Result existing = PathFileAdapter.read(path, MAX_BYTES);
        if (!existing.isOk()) {
            OperationResult failed = readFailure(existing, path);
            return refuse(failed != null ? failed : OperationResult.failure(fileError("Cannot read the existing file", existing)));
        }
        if (!st.realPath.equals(existing.realPath)) {
            return refuse(OperationResult.failure(Issue.of(IssueCode.FILE_ERROR,
                    "The file " + path(st.path) + " was re-linked while it was checked.", "Retry the save.")));
        }
        if (existing.text.trim().isEmpty()) {
            return ok;
        }
        CircuitContentTest.Result test = CircuitContentTest.test(existing.text);
        if (!test.isCircuit()) {
            return refuse(OperationResult.failure(notACircuit(test, "exists and is not a circuit file; it is not overwritten")));
        }
        return ok;
    }

    // ---------------------------------------------------------------- shared

    /**
     * Sets the file state after a successful open or save: path, name (title), last file name,
     * Save item, modified flag cleared (also refreshes the tab and window title). Called with
     * {@code doc} bound ({@code DocumentScope}).
     */
    private static void setFileState(CirSim sim, CircuitDocument doc, String path) {
        String name = PathFileAdapter.baseName(path);
        doc.circuitInfo.setFile(path, name);
        sim.setLastFileName(name);
        sim.allowSave(true);
        sim.setUnsavedChanges(false);
    }

    /** [SP_AGA_03_09] "Allowed files": an absolute path ending in .txt or .json. */
    private static Issue checkPath(String path, String what) {
        if (path == null || path.isEmpty() || !PathFileAdapter.isAbsolute(path)) {
            return Issue.of(IssueCode.FILE_NOT_ALLOWED, "The " + what + " '" + path(path) + "' is not an absolute path.",
                    "Pass an absolute path ending in .txt or .json.");
        }
        if (!hasCircuitExtension(path)) {
            return Issue.of(IssueCode.FILE_NOT_ALLOWED, "The " + what + " '" + path(path) + "' does not end in .txt or .json.",
                    "Only circuit files (.txt, .json) can be opened or saved.");
        }
        return null;
    }

    /** An existing file reached through a symbolic link must have a circuit file name as well. */
    private static OperationResult checkRealPath(PathFileAdapter.Result file) {
        if (file.realPath != null && !hasCircuitExtension(file.realPath)) {
            return OperationResult.failure(Issue.of(IssueCode.FILE_NOT_ALLOWED,
                    "The path " + path(file.path) + " is a link to " + path(file.realPath) + ", which does not end in .txt or .json.",
                    "Only circuit files (.txt, .json) can be opened or saved."));
        }
        return null;
    }

    /** A path or file-system reason quoted in a message: bounded ([SP_AGA_01_07] bounded echo). */
    private static String path(String text) {
        return EchoText.clip(text, EchoText.MAX_PATH);
    }

    private static boolean hasCircuitExtension(String path) {
        String p = path.toLowerCase();
        return p.endsWith(".txt") || p.endsWith(".json");
    }

    /** @return the rejection of a failed read, or null when it succeeded */
    private static OperationResult readFailure(PathFileAdapter.Result file, String path) {
        switch (file.status) {
            case OK:
                return null;
            case UNAVAILABLE:
                return unavailable();
            case NOT_FOUND:
                return OperationResult.failure(Issue.of(IssueCode.FILE_NOT_FOUND,
                        "No file exists at " + path(file.path != null ? file.path : path) + ".",
                        "Check the path; it must be absolute."));
            case NOT_REGULAR:
                return OperationResult.failure(Issue.of(IssueCode.FILE_NOT_ALLOWED,
                        "The path " + path(file.path) + " is not a regular file.", "Pass a .txt or .json circuit file."));
            case TOO_LARGE:
                return OperationResult.failure(Issue.of(IssueCode.FILE_NOT_ALLOWED,
                        "The file " + path(file.path) + " is larger than 10 MB.", "Circuit files are at most 10 MB."));
            default:
                return OperationResult.failure(fileError("Cannot read the file", file));
        }
    }

    private static OperationResult writeFailure(PathFileAdapter.Result written) {
        if (written.status == PathFileAdapter.Status.UNAVAILABLE) {
            return unavailable();
        }
        if (written.status == PathFileAdapter.Status.NOT_REGULAR) {
            return OperationResult.failure(Issue.of(IssueCode.FILE_NOT_ALLOWED,
                    "The path " + path(written.path) + " is not a regular file.", "Save to a .txt or .json circuit file."));
        }
        return OperationResult.failure(fileError("Cannot write the file", written));
    }

    private static Issue fileError(String what, PathFileAdapter.Result r) {
        return Issue.of(IssueCode.FILE_ERROR, what + ": " + (r.reason != null ? path(r.reason) : "unknown reason") + ".",
                "Check the path and the file permissions.");
    }

    private static OperationResult unavailable() {
        return OperationResult.failure(Issue.of(IssueCode.FILE_UNAVAILABLE,
                "File access needs the desktop application; this runtime has no file system.",
                "Use importCircuit and exportCircuit with the circuit content instead."));
    }

    /** The file_not_allowed issue of content that fails the circuit test: line number and count only. */
    private static Issue notACircuit(CircuitContentTest.Result test, String what) {
        String detail = test.unknownLines > 0
                ? " (" + test.unknownLines + " line" + (test.unknownLines == 1 ? "" : "s")
                        + " of no circuit line type, the first at line " + test.firstUnknownLine + ")"
                : " (no element or options line)";
        return Issue.of(IssueCode.FILE_NOT_ALLOWED, "The file " + what + detail + ".",
                "Only circuit files (legacy text or JSON v2 with the circuitjs schema) are allowed.");
    }

    /**
     * [SP_AGA_03_09] "No content disclosure": the issues of a rejected open, reduced to codes,
     * line numbers and counts — one issue per code and severity, without element names.
     */
    static OperationResult withoutContent(OperationResult r) {
        Map<String, int[]> counts = new LinkedHashMap<>();
        Map<String, List<Integer>> lines = new LinkedHashMap<>();
        Map<String, Issue> first = new LinkedHashMap<>();
        for (Issue i : r.getIssues()) {
            String k = i.getCode().code() + "|" + i.getSeverity().wire();
            if (!counts.containsKey(k)) {
                counts.put(k, new int[1]);
                lines.put(k, new ArrayList<>());
                first.put(k, i);
            }
            counts.get(k)[0]++;
            int line = lineOf(i.getMessage());
            List<Integer> l = lines.get(k);
            if (line > 0 && !l.contains(line)) {
                l.add(line);
            }
        }
        List<Issue> out = new ArrayList<>();
        for (Map.Entry<String, int[]> e : counts.entrySet()) {
            Issue sample = first.get(e.getKey());
            int n = e.getValue()[0];
            List<Integer> l = lines.get(e.getKey());
            StringBuilder msg = new StringBuilder("The file content has ").append(n)
                    .append(n == 1 ? " problem" : " problems").append(" of type ").append(sample.getCode().code());
            if (!l.isEmpty()) {
                msg.append(l.size() == 1 ? " at line " : " at lines ");
                for (int j = 0; j < l.size() && j < MAX_LINES_LISTED; j++) {
                    msg.append(j > 0 ? ", " : "").append(l.get(j));
                }
                if (l.size() > MAX_LINES_LISTED) {
                    msg.append(" and ").append(l.size() - MAX_LINES_LISTED).append(" more");
                }
            }
            msg.append('.');
            out.add(Issue.of(sample.getCode(), sample.getSeverity(), msg.toString(),
                    "Fix the file at the reported lines; issue texts never quote the file content."));
        }
        return OperationResult.failure(out);
    }

    /** @return the line number of an issue message starting with "Line n:", else 0 */
    private static int lineOf(String message) {
        if (message == null || !message.startsWith("Line ")) {
            return 0;
        }
        int end = message.indexOf(':');
        if (end < 0) {
            return 0;
        }
        try {
            return Integer.parseInt(message.substring(5, end).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
