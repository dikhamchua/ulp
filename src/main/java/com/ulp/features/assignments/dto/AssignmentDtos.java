package com.ulp.features.assignments.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Data transfer objects for the assignments feature.
 *
 * <p>All records are immutable value objects; no entity reference leaks past this class.
 * The outer class is a namespace — instantiate the inner records directly.
 */
public final class AssignmentDtos {

    private AssignmentDtos() {
        // Namespace only — no instances.
    }

    // ── Lecturer-facing DTOs ───────────────────────────────────────────────

    /**
     * Row shown in the lecturer's assignment list.
     *
     * @param id              the assignment id
     * @param title           the assignment title
     * @param status          DRAFT / PUBLISHED / CLOSED
     * @param dueDate         optional due date
     * @param maxScore        maximum achievable score
     * @param submissionCount total submissions received so far
     */
    public record AssignmentRow(
            Long id,
            String title,
            String status,
            LocalDateTime dueDate,
            BigDecimal maxScore,
            long submissionCount
    ) {}

    /**
     * Full assignment detail used by the lecturer form (create and edit).
     *
     * @param id                  null for new assignments
     * @param title               required, max 300 chars
     * @param description         required
     * @param maxScore            must be ≥ 0
     * @param dueDate             optional
     * @param allowLateSubmission whether to accept late submissions
     */
    public record AssignmentForm(
            Long id,
            String title,
            String description,
            BigDecimal maxScore,
            LocalDateTime dueDate,
            boolean allowLateSubmission
    ) {
        /** Factory for an empty form used when rendering the create page. */
        public static AssignmentForm empty() {
            return new AssignmentForm(null, "", "", BigDecimal.valueOf(100), null, false);
        }
    }

    /**
     * Single submission row shown on the lecturer's submissions list.
     *
     * @param submissionId the submission id
     * @param studentName  full name of the student
     * @param studentEmail email of the student
     * @param status       DRAFT / SUBMITTED / GRADED
     * @param isLate       true when submitted after the due date
     * @param submittedAt  when the student last submitted
     * @param score        non-null only when status = GRADED
     */
    public record SubmissionRow(
            Long submissionId,
            String studentName,
            String studentEmail,
            String status,
            boolean isLate,
            LocalDateTime submittedAt,
            BigDecimal score
    ) {}

    /**
     * Full submission detail used by the grade page.
     *
     * @param submissionId the submission id
     * @param assignmentId the parent assignment id
     * @param studentName  full name of the student
     * @param studentEmail email of the student
     * @param content      the student's text/link answer
     * @param status       DRAFT / SUBMITTED / GRADED
     * @param isLate       true when submitted after due date
     * @param submittedAt  when the student last submitted
     * @param score        existing score, or null if not yet graded
     * @param feedback     existing feedback, or null if not yet graded
     * @param maxScore     the assignment's max score (for validation hint)
     */
    public record SubmissionDetail(
            Long submissionId,
            Long assignmentId,
            String studentName,
            String studentEmail,
            String content,
            String status,
            boolean isLate,
            LocalDateTime submittedAt,
            BigDecimal score,
            String feedback,
            BigDecimal maxScore
    ) {}

    /**
     * Form binding for the grade action.
     *
     * @param score    score to assign (0 ≤ score ≤ maxScore)
     * @param feedback optional lecturer feedback text
     */
    public record GradeForm(
            BigDecimal score,
            String feedback
    ) {}

    // ── Student-facing DTOs ───────────────────────────────────────────────

    /**
     * Row in the student's assignment list.
     *
     * @param id             the assignment id
     * @param title          assignment title
     * @param status         PUBLISHED or CLOSED
     * @param dueDate        optional due date
     * @param maxScore       max achievable score
     * @param submissionStatus the student's own submission status, or null if not yet submitted
     * @param isLate         true when the student's submission was late
     */
    public record StudentAssignmentRow(
            Long id,
            String title,
            String status,
            LocalDateTime dueDate,
            BigDecimal maxScore,
            String submissionStatus,
            boolean isLate
    ) {}

    /**
     * Full assignment detail shown to a student when they open one assignment.
     *
     * @param id                  assignment id
     * @param title               assignment title
     * @param description         assignment description
     * @param status              PUBLISHED or CLOSED
     * @param dueDate             optional due date
     * @param maxScore            max achievable score
     * @param allowLateSubmission whether late submission is allowed
     * @param submissionId        id of the student's existing submission, or null
     * @param submissionContent   content of existing submission, or null
     * @param submissionStatus    DRAFT/SUBMITTED/GRADED, or null
     * @param isLate              true when the existing submission was late
     * @param score               the grade, or null when not yet graded
     * @param feedback            the feedback text, or null when not yet graded
     */
    public record StudentAssignmentDetail(
            Long id,
            String title,
            String description,
            String status,
            LocalDateTime dueDate,
            BigDecimal maxScore,
            boolean allowLateSubmission,
            Long submissionId,
            String submissionContent,
            String submissionStatus,
            boolean isLate,
            BigDecimal score,
            String feedback
    ) {}

    /**
     * Form binding for the student submit action.
     *
     * @param content text/link content the student submits
     */
    public record SubmitForm(String content) {}

    // ── Shared DTOs ───────────────────────────────────────────────────────

    /**
     * Unread / pending badge count passed to the class sidebar.
     *
     * @param pendingSubmissions number of SUBMITTED (un-graded) submissions
     *                           the lecturer still needs to grade
     */
    public record AssignmentBadge(long pendingSubmissions) {}
}
