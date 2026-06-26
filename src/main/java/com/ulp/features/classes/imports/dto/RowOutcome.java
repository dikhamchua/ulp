package com.ulp.features.classes.imports.dto;

/**
 * Per-row outcome bucket: every confirmed row contributes to exactly one of
 * these counters. The {@link #plus(RowOutcome)} helper lets the main loop
 * accumulate without juggling five primitives.
 */
public record RowOutcome(int imported, int reactivated,
                         int skippedDup, int skippedErr, int failed) {

    public static final RowOutcome ZERO         = new RowOutcome(0, 0, 0, 0, 0);
    public static final RowOutcome IMPORTED     = new RowOutcome(1, 0, 0, 0, 0);
    public static final RowOutcome REACTIVATED  = new RowOutcome(0, 1, 0, 0, 0);
    public static final RowOutcome SKIPPED_DUP  = new RowOutcome(0, 0, 1, 0, 0);
    public static final RowOutcome SKIPPED_ERR  = new RowOutcome(0, 0, 0, 1, 0);
    public static final RowOutcome FAILED       = new RowOutcome(0, 0, 0, 0, 1);

    public RowOutcome plus(RowOutcome o) {
        return new RowOutcome(imported + o.imported,
                reactivated + o.reactivated,
                skippedDup + o.skippedDup,
                skippedErr + o.skippedErr,
                failed + o.failed);
    }
}
