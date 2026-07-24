package com.ulp.features.tests.dto;

import org.springframework.data.domain.Page;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Lecturer-facing DTOs: exam authoring form, exam list, live monitor snapshot,
 * and submissions overview. Records only — no entity leaves the service layer.
 */
public final class LecturerTestDtos {

    private LecturerTestDtos() {
        // holder for records
    }

    // ── Authoring form ───────────────────────────────────────────────

    /** A class the lecturer may attach an exam to. */
    public record ClassOption(Long id, String name) {
    }

    /** An option row inside the question builder. */
    public record OptionForm(Long id, String content, boolean correct) {
    }

    /** A question row inside the question builder. */
    public record QuestionForm(Long id, String type, String content, String explanation,
                               BigDecimal points, List<OptionForm> options) {
    }

    /** The full create/edit exam form payload (JSON-bound from the builder). */
    public record ExamForm(Long id, String title, String description, Long classId,
                           String type, String status, String timeMode,
                           Integer durationMinutes, LocalDateTime startAt, LocalDateTime endAt,
                           BigDecimal passingScore, boolean shuffleQuestions, boolean shuffleOptions,
                           String mediaType, String mediaUrl,
                           List<QuestionForm> questions, boolean questionBankLocked) {
    }

    /** One active department category shown in the exam-side bank picker. */
    public record BankCategoryOption(Long id, String name) {
    }

    /** One option copied from an approved bank item. */
    public record BankOptionSnapshot(String content, boolean correct) {
    }

    /** Snapshot payload returned by the approved bank picker query. */
    public record BankItemSnapshot(Long id, String categoryName, String questionType,
                                   String content, String explanation,
                                   List<BankOptionSnapshot> options) {
    }

    /** Chosen approved bank item ids to snapshot into a test (insert-from-bank). */
    public record BankInsertRequest(List<Long> itemIds) {
    }

    /** Result of an insert-from-bank operation: how many questions were copied. */
    public record BankInsertResult(int insertedCount) {
    }

    /** Save response: the persisted exam id + where to go next. */
    public record SaveResult(Long id) {
    }

    // ── Exam list ────────────────────────────────────────────────────

    /** A row on the lecturer exam list. */
    public record LecturerExamRow(Long id, String title, String type, String status,
                                  String className, int totalQuestions, LocalDateTime endAt) {
    }

    /** Minimal exam header for the monitor page (avoids leaking the entity). */
    public record ExamHeader(Long id, String title, String status, String timeMode,
                             LocalDateTime endAt, Integer totalQuestions) {
    }

    // ── Live monitor ─────────────────────────────────────────────────

    /** One student's live state in the monitor. */
    public record MonitorStudentRow(String name, String email, String state,
                                    LocalDateTime lastActivity, boolean active) {
    }

    /** The monitor snapshot returned as JSON to the polling client. */
    public record MonitorSnapshot(int submittedCount, int inProgressCount, int activeCount,
                                  String examStatus, Long remainingSeconds,
                                  List<MonitorStudentRow> students) {
    }

    // ── Submissions overview ─────────────────────────────────────────

    /** One attempt row on the submissions screen. */
    public record SubmissionRow(Long attemptId, String studentName, String email,
                                BigDecimal score, Integer correctCount, Integer totalQuestions,
                                LocalDateTime submittedAt, int attemptCount, boolean late) {
    }

    /** Summary stats + paginated attempts for the submissions screen. */
    public record SubmissionsView(Long testId, String title, int submittedCount, int lateCount,
                                  String examStatus, Page<SubmissionRow> attempts, String query) {
    }
}
