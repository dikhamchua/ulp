package com.ulp.features.notifications.entity;

import java.util.Set;

/**
 * String constants for the {@code notifications.type} column.
 *
 * <p>A whitelist set ({@link #EMAIL_TYPES}) declares which types enqueue a
 * best-effort email through {@code MailJobQueue}. Delivery is never done on
 * the request thread — see {@code .claude/rules/mail-job-queue.md}.
 *
 * <p>{@link #LESSON_PUBLISHED} is intentionally <b>not</b> in {@link #EMAIL_TYPES}:
 * publishing a lesson only creates the in-app notification. Re-add it to the
 * set later if product asks for email, still via the mail job queue.
 *
 * <p>All types are stored as VARCHAR(30) in the DB (per V1 schema).
 */
public final class NotificationType {

    /** A student has been enrolled in a new class. */
    public static final String CLASS_ENROLLED = "CLASS_ENROLLED";

    /**
     * A lesson has been published in a class the student is enrolled in.
     * In-app only — not emailed (keeps "Xuất bản" fast).
     */
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
     * A flashcard deck started being shared to a class the student is enrolled in.
     * In-app only — deliberately not emailed, matching {@link #LESSON_PUBLISHED}:
     * study material appearing is ambient information, not inbox-worthy.
     */
    public static final String DECK_SHARED = "DECK_SHARED";

    /**
     * Department HEAD notified that a lecturer created a class needing review.
     * In-app only — class creation must never touch the SMTP path.
     */
    public static final String CLASS_PENDING_APPROVAL = "CLASS_PENDING_APPROVAL";

    /** Owning lecturer notified when the HEAD approves their class. In-app only. */
    public static final String CLASS_APPROVED = "CLASS_APPROVED";

    /** Owning lecturer notified when the HEAD rejects their class. In-app only. */
    public static final String CLASS_REJECTED = "CLASS_REJECTED";

    /**
     * Notification types that enqueue a background email via {@code MailJobQueue}.
     * Join-approval lifecycle, CLASS_ENROLLED, ASSIGNMENT_GRADED, DECK_SHARED,
     * LESSON_PUBLISHED, and the class-review lifecycle
     * (CLASS_PENDING_APPROVAL / CLASS_APPROVED / CLASS_REJECTED) are
     * intentionally excluded (in-app only).
     */
    public static final Set<String> EMAIL_TYPES = Set.of(ASSIGNMENT_PUBLISHED);

    /** Reference type constant for a class domain object. */
    public static final String REF_CLASS = "CLASS";

    /** Reference type constant for a lesson domain object. */
    public static final String REF_LESSON = "LESSON";

    /** Reference type constant for an assignment domain object. */
    public static final String REF_ASSIGNMENT = "ASSIGNMENT";

    /** Reference type constant for a flashcard deck domain object. */
    public static final String REF_DECK = "DECK";

    private NotificationType() {
        // Constants-only class — no instances.
    }
}
