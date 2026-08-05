package com.ulp.features.tests.dto;

import com.ulp.common.IConstant;
import com.ulp.features.tests.entity.Test;
import org.springframework.data.domain.Page;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static com.ulp.common.IConstant.EXAM_FILTER_ALL;
import static com.ulp.common.IConstant.EXAM_SORT_CLOSING;
import static com.ulp.common.IConstant.EXAM_SORT_RECENT;
import static com.ulp.common.IConstant.EXAM_SORT_TITLE;

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

    /** One chapter of the class subject shown in the exam-side bank picker. */
    public record BankChapterOption(Long id, String name) {
    }

    /** One option copied from an ACTIVE bank item. */
    public record BankOptionSnapshot(String content, boolean correct) {
    }

    /**
     * Snapshot payload returned by the bank picker query.
     *
     * @param source one of {@code HEAD_BANK} (department-owned, ownerId null) or
     *               {@code LECTURER_BANK} (the author's own private bank)
     */
    public record BankItemSnapshot(Long id, String source, String subjectLabel,
                                   String questionType, String content, String explanation,
                                   List<BankOptionSnapshot> options) {
    }

    /**
     * Why a picker result set came out the way it did, so the client can explain
     * an empty list instead of showing one generic line for every cause.
     *
     * @param subjectBound         false when the class has no subject, which
     *                             degrades the search to HEAD-bank-only
     * @param subjectLabel         the resolved subject, or null when unbound
     * @param classId              the class the scope was resolved from, so the
     *                             client can link to its edit screen
     * @param chapterFilterApplied whether a chapter or query narrowed the result
     */
    public record BankScopeInfo(boolean subjectBound, String subjectLabel,
                                Long classId, boolean chapterFilterApplied) {
    }

    /** Picker payload: matches plus enough scope to render a useful empty state. */
    public record BankSearchResult(List<BankItemSnapshot> items, BankScopeInfo scope) {
    }

    /** Chosen ACTIVE bank item ids to snapshot into a test (insert-from-bank). */
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

    /**
     * Normalised filter state for the exam lists. {@code status}/{@code type}
     * hold either a concrete enum value or {@link IConstant#EXAM_FILTER_ALL};
     * {@code sort} is one of the {@code EXAM_SORT_*} keys; {@code classId} is
     * either a class the caller may narrow to or {@code null} for "all classes".
     * {@link #of} sanitises raw request params so the service and template never
     * see unknown values.
     */
    public record ExamFilter(String keyword, String status, String type, String sort,
                             Long classId) {

        private static final Set<String> STATUSES =
                Set.of(Test.STATUS_DRAFT, Test.STATUS_PUBLISHED, Test.STATUS_ARCHIVED);
        private static final Set<String> TYPES =
                Set.of(Test.TYPE_MOCK, Test.TYPE_MODULE, Test.TYPE_PRACTICE);
        private static final Set<String> SORTS =
                Set.of(EXAM_SORT_RECENT, EXAM_SORT_TITLE, EXAM_SORT_CLOSING);

        /**
         * Builds a filter without the class dimension — for lists already scoped
         * to one class (the class-detail tests tab), where a class picker would
         * be meaningless.
         */
        public static ExamFilter of(String keyword, String status, String type, String sort) {
            return of(keyword, status, type, sort, null, List.of());
        }

        /**
         * Builds a filter from a raw, still-unparsed {@code classId} query param.
         * Binding it as text keeps a hand-typed {@code ?classId=abc} out of
         * Spring's type conversion, which would raise a bind error that the
         * catch-all advice renders as a 500; here it simply degrades to
         * "all classes" like any other unusable value.
         */
        public static ExamFilter ofRawClassId(String keyword, String status, String type,
                                              String sort, String classId,
                                              List<ClassOption> allowedClasses) {
            return of(keyword, status, type, sort, parseId(classId), allowedClasses);
        }

        /**
         * Builds a filter from raw query params, falling back to safe defaults.
         * {@code classId} survives only when {@code allowedClasses} contains it,
         * so a hand-typed id of a class the caller does not lead degrades to
         * "all classes" instead of leaking that class's exams.
         */
        public static ExamFilter of(String keyword, String status, String type, String sort,
                                    Long classId, List<ClassOption> allowedClasses) {
            return new ExamFilter(
                    keyword == null ? "" : keyword.trim(),
                    whitelist(status, STATUSES, EXAM_FILTER_ALL),
                    whitelist(type, TYPES, EXAM_FILTER_ALL),
                    whitelist(sort, SORTS, EXAM_SORT_RECENT),
                    whitelistClass(classId, allowedClasses));
        }

        /** True when no narrowing is applied — used to pick the empty-state copy. */
        public boolean isEmpty() {
            return keyword.isEmpty()
                    && EXAM_FILTER_ALL.equals(status)
                    && EXAM_FILTER_ALL.equals(type)
                    && classId == null;
        }

        /** Returns {@code null} for the "any" sentinel so JPQL can skip the predicate. */
        public String statusOrNull() {
            return EXAM_FILTER_ALL.equals(status) ? null : status;
        }

        /** Returns {@code null} for the "any" sentinel so JPQL can skip the predicate. */
        public String typeOrNull() {
            return EXAM_FILTER_ALL.equals(type) ? null : type;
        }

        /** Already null for "all classes"; named for symmetry with the sibling accessors. */
        public Long classIdOrNull() {
            return classId;
        }

        private static String whitelist(String value, Set<String> allowed, String fallback) {
            if (value == null) return fallback;
            String trimmed = value.trim();
            return allowed.contains(trimmed) ? trimmed : fallback;
        }

        /** Parses an id, treating anything non-numeric as "not supplied". */
        private static Long parseId(String raw) {
            if (raw == null || raw.isBlank()) return null;
            try {
                return Long.valueOf(raw.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        private static Long whitelistClass(Long classId, List<ClassOption> allowed) {
            if (classId == null || classId <= 0 || allowed == null) return null;
            return allowed.stream().anyMatch(c -> classId.equals(c.id())) ? classId : null;
        }
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
