package com.lushprojects.circuitjs1.client.solver;

/** [SP_SLV_01_02] The solve path of one stamp, chosen by {@link LinearSystem#selectPath}. */
public enum SolvePath {
    DENSE("dense"), SPARSE("sparse");

    /** Wire spelling. */
    public final String wire;

    SolvePath(String wire) {
        this.wire = wire;
    }
}
