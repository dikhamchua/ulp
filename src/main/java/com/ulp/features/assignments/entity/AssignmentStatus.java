package com.ulp.features.assignments.entity;

/**
 * String constants for the {@code assignments.status} column.
 * Lifecycle: DRAFT → PUBLISHED → CLOSED.
 * Transitions are enforced in the service layer.
 */
public final class AssignmentStatus {

    /** Newly created; not visible to students yet. */
    public static final String DRAFT = "DRAFT";

    /** Visible to enrolled students; accepts submissions. */
    public static final String PUBLISHED = "PUBLISHED";

    /** No further submissions accepted. */
    public static final String CLOSED = "CLOSED";

    // ── Submission status constants ────────────────────────────────────

    /** Submission saved but not yet formally submitted. */
    public static final String SUB_DRAFT = "DRAFT";

    /** Student has formally submitted their work. */
    public static final String SUB_SUBMITTED = "SUBMITTED";

    /** Lecturer has graded the submission. */
    public static final String SUB_GRADED = "GRADED";

    private AssignmentStatus() {
        // Constants-only class — no instances.
    }
}
