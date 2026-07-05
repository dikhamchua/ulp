package com.ulp.features.notifications.entity;

import java.util.Set;

/**
 * String constants for the {@code notifications.type} column.
 *
 * <p>A whitelist set ({@link #EMAIL_TYPES}) declares which types trigger a
 * best-effort email; currently only {@link #LESSON_PUBLISHED} qualifies.
 * All types are stored as VARCHAR(30) in the DB (per V1 schema).
 */
public final class NotificationType {

    /** A student has been enrolled in a new class. */
    public static final String CLASS_ENROLLED = "CLASS_ENROLLED";

    /** A lesson has been published in a class the student is enrolled in. */
    public static final String LESSON_PUBLISHED = "LESSON_PUBLISHED";

    /** A generic system-generated notification (placeholder for future use). */
    public static final String SYSTEM = "SYSTEM";

    /**
     * The set of notification types that trigger a best-effort email.
     * Only {@link #LESSON_PUBLISHED} is whitelisted — {@link #CLASS_ENROLLED}
     * is intentionally excluded to avoid emailing on every enrollment.
     */
    public static final Set<String> EMAIL_TYPES = Set.of(LESSON_PUBLISHED);

    /** Reference type constant for a class domain object. */
    public static final String REF_CLASS = "CLASS";

    /** Reference type constant for a lesson domain object. */
    public static final String REF_LESSON = "LESSON";

    private NotificationType() {
        // Constants-only class — no instances.
    }
}
