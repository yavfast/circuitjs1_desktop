package com.lushprojects.circuitjs1.client.agent;

/**
 * Stable issue codes of the Agent API: solver and operation codes of [SP_AGA_03_06] and the
 * connectivity codes of [SP_AGA_03_05]. Each code carries its default severity; a producer may
 * give another severity where the spec says so (a solver code reached as a stop is an error).
 */
public enum IssueCode {
    // Solver message codes (matched by message-key prefix)
    SINGULAR_MATRIX("singular_matrix", Issue.Severity.ERROR),
    SOURCE_OR_WIRE_LOOP("source_or_wire_loop", Issue.Severity.ERROR),
    GROUND_PATH_NO_RESISTANCE("ground_path_no_resistance", Issue.Severity.ERROR),
    WIRE_LOOP("wire_loop", Issue.Severity.WARNING),
    CONVERGENCE_FAILED("convergence_failed", Issue.Severity.ERROR),
    ANALYSIS_FAILED("analysis_failed", Issue.Severity.ERROR),
    MATRIX_ERROR("matrix_error", Issue.Severity.ERROR),
    SOLVER_STOP("solver_stop", Issue.Severity.ERROR),
    SOLVER_WARNING("solver_warning", Issue.Severity.WARNING),

    // Connectivity codes ([SP_AGA_03_05]; reserved_label and source_or_wire_loop are shared)
    DANGLING_POST("dangling_post", Issue.Severity.ERROR),
    POST_ON_WIRE_BODY("post_on_wire_body", Issue.Severity.ERROR),
    OVERLAPPING_ELEMENTS("overlapping_elements", Issue.Severity.WARNING),
    NO_GROUND("no_ground", Issue.Severity.WARNING),
    ISOLATED_GROUP("isolated_group", Issue.Severity.ERROR),
    BAD_CONNECTION("bad_connection", Issue.Severity.WARNING),
    SINGLE_LABEL("single_label", Issue.Severity.INFO),
    CURRENT_SOURCE_NO_PATH("current_source_no_path", Issue.Severity.WARNING),

    // Operation codes
    NOT_READY("not_ready", Issue.Severity.ERROR),
    UNKNOWN_DOCUMENT("unknown_document", Issue.Severity.ERROR),
    UNKNOWN_TYPE("unknown_type", Issue.Severity.ERROR),
    UNKNOWN_ELEMENT("unknown_element", Issue.Severity.ERROR),
    UNKNOWN_POST("unknown_post", Issue.Severity.ERROR),
    UNKNOWN_NET("unknown_net", Issue.Severity.ERROR),
    UNKNOWN_PROPERTY("unknown_property", Issue.Severity.ERROR),
    UNKNOWN_CHECKPOINT("unknown_checkpoint", Issue.Severity.ERROR),
    INVALID_VALUE("invalid_value", Issue.Severity.ERROR),
    VALUE_ADJUSTED("value_adjusted", Issue.Severity.WARNING),
    OFF_LATTICE("off_lattice", Issue.Severity.ERROR),
    ZERO_LENGTH("zero_length", Issue.Severity.ERROR),
    NOT_AXIS_ALIGNED("not_axis_aligned", Issue.Severity.ERROR),
    ID_INVALID("id_invalid", Issue.Severity.ERROR),
    ID_TAKEN("id_taken", Issue.Severity.ERROR),
    IDS_REGENERATED("ids_regenerated", Issue.Severity.WARNING),
    SCOPE_REMOVED("scope_removed", Issue.Severity.INFO),
    RESERVED_LABEL("reserved_label", Issue.Severity.WARNING),
    BUSY("busy", Issue.Severity.ERROR),
    SCOPE_LIMIT("scope_limit", Issue.Severity.ERROR),
    IMPORT_SCHEMA_INVALID("import_schema_invalid", Issue.Severity.ERROR),
    IMPORT_ELEMENT_SKIPPED("import_element_skipped", Issue.Severity.ERROR),
    IMPORT_WIRE_SKIPPED("import_wire_skipped", Issue.Severity.WARNING),
    IMPORT_SETTING_INVALID("import_setting_invalid", Issue.Severity.WARNING),
    IMPORT_GEOMETRY_ADJUSTED("import_geometry_adjusted", Issue.Severity.WARNING),
    NOTHING_TO_UNDO("nothing_to_undo", Issue.Severity.ERROR),
    NOTHING_TO_REDO("nothing_to_redo", Issue.Severity.ERROR),
    UNSAVED_CHANGES("unsaved_changes", Issue.Severity.ERROR),
    RENDER_FAILED("render_failed", Issue.Severity.ERROR),
    FILE_UNAVAILABLE("file_unavailable", Issue.Severity.ERROR),
    FILE_NOT_ALLOWED("file_not_allowed", Issue.Severity.ERROR),
    FILE_NOT_FOUND("file_not_found", Issue.Severity.ERROR),
    FILE_ERROR("file_error", Issue.Severity.ERROR),
    NO_PATH("no_path", Issue.Severity.ERROR),
    INTERNAL_ERROR("internal_error", Issue.Severity.ERROR),

    // Run end causes
    BUDGET_EXHAUSTED("budget_exhausted", Issue.Severity.WARNING),
    SETTLE_TIMEOUT("settle_timeout", Issue.Severity.WARNING),
    STOP_TRIGGER("stop_trigger", Issue.Severity.WARNING),
    CANCELLED("cancelled", Issue.Severity.WARNING);

    private final String code;
    private final Issue.Severity defaultSeverity;

    IssueCode(String code, Issue.Severity defaultSeverity) {
        this.code = code;
        this.defaultSeverity = defaultSeverity;
    }

    /** @return the wire form of the code (lower snake case) */
    public String code() {
        return code;
    }

    public Issue.Severity defaultSeverity() {
        return defaultSeverity;
    }
}
