package com.ulp.features.classes.imports.dto;

import java.util.List;

/**
 * Outcome of a confirmed import.
 *
 * <p>Counts are mutually exclusive — every input row appears in exactly one of
 * {@code imported}, {@code reactivated}, {@code skippedDuplicate} (silent skip
 * for already-active members), {@code skippedError} (rows blocked by hard
 * errors), or {@code failed} (rows that errored at persist time).
 *
 * @param totalProcessed   total number of rows considered during this confirm
 * @param imported         newly inserted enrollments
 * @param reactivated      previously REMOVED enrollments flipped back to ACTIVE
 * @param skippedDuplicate rows skipped because the student was already ACTIVE
 * @param skippedError     rows blocked by validation errors (when skip-errors is on)
 * @param failed           rows that errored at persist time (DB constraint, etc.)
 * @param rows             full per-row report — caller may surface this in the UI
 */
public record ImportResult(int totalProcessed,
                           int imported,
                           int reactivated,
                           int skippedDuplicate,
                           int skippedError,
                           int failed,
                           List<ImportRow> rows) {

    public ImportResult {
        rows = rows == null ? List.of() : List.copyOf(rows);
    }
}
