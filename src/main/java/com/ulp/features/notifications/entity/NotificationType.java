package com.ulp.features.notifications.entity;

import java.util.Set;

/**
 * String constants for the {@code notifications.type} column.
 *
 * <p>A whitelist set ({@link #EMAIL_TYPES}) declares which types trigger a
 * best-effort email; currently {@link #LESSON_PUBLISHED} and
 * {@link #ASSIGNMENT_PUBLISHED} qualify.
 * All types are stored as VARCHAR(30) in the DB (per V1 schema).
 */
public final class NotificationType {

    /** A student has been enrolled in a new class. */
    public static final String CLASS_ENROLLED = "CLASS_ENROLLED";

    /** A lesson has been published in a class the student is enrolled in. */
    public static final String LESSON_PUBLISHED = "LESSON_PUBLISHED";

    /** A generic system-generated notification (placeholder for future use). */
    public static final String SYSTEM = "SYSTEM";

    /** An assignment has been published; fan-out to all enrolled students. */
    public static final String ASSIGNMENT_PUBLISHED = "ASSIGNMENT_PUBLISHED";

    /**
     * A student's assignment submission has been graded.
     * Intentionally excluded from email — grading is frequent; avoid inbox spam.
     */
    public static final String ASSIGNMENT_GRADED = "ASSIGNMENT_GRADED";

    /** Class owner notified when a student requests to join via CODE/LINK. */
    public static final String JOIN_REQUEST = "JOIN_REQUEST";

    /** Student notified when the class owner approves their join request. */
    public static final String JOIN_APPROVED = "JOIN_APPROVED";

    /** Student notified when the class owner rejects their join request. */
    public static final String JOIN_REJECTED = "JOIN_REJECTED";

    /**
     * The set of notification types that trigger a best-effort email.
     * Join-approval lifecycle types and CLASS_ENROLLED / ASSIGNMENT_GRADED
     * are intentionally excluded (in-app only).
     */
    public static final Set<String> EMAIL_TYPES = Set.of(LESSON_PUBLISHED, ASSIGNMENT_PUBLISHED);

    /** Reference type constant for a class domain object. */
    public static final String REF_CLASS = "CLASS";

    /** Reference type constant for a lesson domain object. */
    public static final String REF_LESSON = "LESSON";

    /** Reference type constant for an assignment domain object. */
    public static final String REF_ASSIGNMENT = "ASSIGNMENT";

    private NotificationType() {
        // Constants-only class — no instances.
    }
}
