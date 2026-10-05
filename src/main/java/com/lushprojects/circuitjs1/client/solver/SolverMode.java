package com.lushprojects.circuitjs1.client.solver;

/**
 * [SP_SLV_01_01] Solver mode: {@code AUTO} (dense up to {@link LinearSystem#DENSE_MAX_SIZE} reduced
 * unknowns, sparse above), {@code DENSE}, {@code SPARSE}. The session default is a preference; a
 * document override is never persisted. Wire and storage spelling: lower case.
 */
public enum SolverMode {
    AUTO("auto"), DENSE("dense"), SPARSE("sparse");

    /** Wire and storage spelling. */
    public final String wire;

    SolverMode(String wire) {
        this.wire = wire;
    }

    /** The mode spelled {@code s} (case-sensitive), or null. */
    public static SolverMode parse(String s) {
        for (SolverMode m : values()) {
            if (m.wire.equals(s)) {
                return m;
            }
        }
        return null;
    }

    /** [SP_SLV_03_01] A stored preference: absent or unrecognized reads as {@code AUTO}. */
    public static SolverMode fromStored(String s) {
        SolverMode m = parse(s);
        return m != null ? m : AUTO;
    }
}
