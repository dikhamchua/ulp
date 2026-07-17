package com.ulp.features.tests.dto;

import org.springframework.data.domain.Page;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Student-facing view-model, form and API DTOs for the MCQ exam feature.
 * Records only — no entity ever leaves the service layer.
 */
public final class TestDtos {

    private TestDtos() {
        // holder for records
    }

    // ── Student list ─────────────────────────────────────────────────

    /** A row on the student exam list. */
    public record ExamListRow(
            Long id, String title, String type, String status, String className,
            boolean practice, Integer durationMinutes, String timeMode,
            LocalDateTime endAt, int totalQuestions,
            String lastAttemptStatus, Integer bestScorePercent) {
    }

    /** The paginated student exam list. */
    public record StudentExamList(Page<ExamListRow> exams) {
    }

    /**
     * Class-scoped student tests view ({@code GET /my/classes/{classId}/tests}):
     * class metadata for the shared sidebar plus the paginated, title-filtered
     * PUBLISHED exams of that one class.
     */
    public record ClassTestsView(Long classId, String className, String classCode,
                                 String lecturerName, String query,
                                 Page<ExamListRow> exams) {
    }

    // ── Taking screen ────────────────────────────────────────────────

    /** An option as shown while taking (never carries is_correct). */
    public record TakeOptionView(Long id, String content) {
    }

    /** A question as shown while taking. */
    public record TakeQuestionView(Long id, String type, String content,
                                   BigDecimal points, List<TakeOptionView> options) {
    }

    /** The full taking view: attempt, exam meta, remaining seconds, questions. */
    public record TakeView(Long attemptId, Long testId, String title, String description,
                           String timeMode, long remainingSeconds,
                           String mediaType, String mediaUrl, String mediaEmbedUrl,
                           List<TakeQuestionView> questions) {
    }

    /**
     * Lecturer read-only preview of the student taking screen. No attempt is
     * created; options never expose correctness so the layout matches students.
     */
    public record PreviewView(Long testId, String title, String description, String timeMode,
                              Integer durationMinutes, LocalDateTime startAt, LocalDateTime endAt,
                              String mediaType, String mediaUrl, String mediaEmbedUrl,
                              List<TakeQuestionView> questions) {
    }

    // ── Submit / heartbeat payloads ──────────────────────────────────

    /** One answered question in a submit payload. */
    public record AnswerItem(Long questionId, List<Long> selectedOptionIds) {
    }

    /** The submit-all-at-once request body. */
    public record SubmitRequest(List<AnswerItem> answers) {
    }

    /** Submit response payload: where to redirect for the result. */
    public record SubmitResult(Long testId, Long attemptId, String status) {
    }

    // ── Result summary ───────────────────────────────────────────────

    /** The post-submit result summary. */
    public record ResultView(Long testId, Long attemptId, String title,
                             BigDecimal score, BigDecimal totalPoints,
                             boolean hasThreshold, boolean passed, BigDecimal passingScore,
                             int correctCount, int totalQuestions,
                             int timeSpentSeconds, String status) {
    }

    // ── Per-question review ──────────────────────────────────────────

    /** An option in the review, with correctness + whether the student chose it. */
    public record ReviewOptionView(Long id, String content, boolean correct, boolean selected) {
    }

    /** A question in the review. */
    public record ReviewQuestionView(Long id, String type, String content, String explanation,
                                     boolean correct, List<ReviewOptionView> options) {
    }

    /** The full review view. */
    public record ReviewView(Long testId, Long classId, Long attemptId, String title,
                             int correctCount, int totalQuestions, BigDecimal score,
                             boolean lecturerView, String studentName,
                             List<ReviewQuestionView> questions) {
    }

    // ── Practice generation ──────────────────────────────────────────

    /** A source scope option (class or exam) the student may sample from. */
    public record PracticeSource(Long id, String label, boolean isClass) {
    }

    /** Form data for the practice-new page. */
    public record PracticeView(List<PracticeSource> classes, List<PracticeSource> exams) {
    }

    /** Submitted practice-generation request (source scope + desired count). */
    public record PracticeForm(Long sourceClassId, Long sourceTestId, int count) {
    }

    // ── Readiness ────────────────────────────────────────────────────

    /** One accessible MOCK/MODULE exam and the student's best result. */
    public record ReadinessExamRow(Long id, String title, boolean done, int bestScorePercent) {
    }

    /** The readiness dashboard view-model. */
    public record ReadinessView(int score, String band,
                                int doneCount, int totalCount,
                                List<ReadinessExamRow> exams) {
    }
}
