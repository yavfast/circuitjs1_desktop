package com.lushprojects.circuitjs1.client.solver;

/**
 * [SP_SLV_01_10] Observable state of one document's solver (Agent API Diagnostics and
 * {@code simControl solver}, live tests). The document fills the modes, {@link LinearSystem} the
 * rest.
 */
public final class SolverInfo {
    /** Session default. */
    public SolverMode mode;
    /** Document override, null when none. */
    public SolverMode override;
    /** Override when set, else the session default. */
    public SolverMode effectiveMode;
    /** Path of the current stamp; null before the first stamp or after a stop dropped the system. */
    public SolvePath path;
    /** Full system size n. */
    public int fullSize;
    /** Reduced size m. */
    public int size;
    /** Positions of the reduced pattern. */
    public int nonZeros;
    /** nnz(L) + nnz(U) of the last sparse factorization; m² on the dense path. */
    public int factorNonZeros;
    /** Symbolic analyses since the engine analysis started. */
    public int symbolicCount;
    /** Full factorizations since the engine analysis started (both paths). */
    public int fullFactorCount;
    /** Accepted refactorizations since the engine analysis started (sparse path). */
    public int refactorCount;
}
