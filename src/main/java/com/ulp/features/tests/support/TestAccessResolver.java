package com.ulp.features.tests.support;

import com.ulp.entities.ClassEntity;
import com.ulp.entities.Enrollment;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.features.classes.repository.EnrollmentRepository;
import com.ulp.features.tests.entity.Test;
import com.ulp.features.tests.entity.TestAttempt;
import com.ulp.features.tests.repository.TestAttemptRepository;
import com.ulp.features.tests.repository.TestRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * Resolves a {@link Test} for a caller and enforces the exam authorization
 * policy (mirrors {@code DeckAccessResolver}).
 *
 * <ul>
 *   <li><b>Student</b> may take an exam iff it is PUBLISHED + not deleted + they
 *       are ACTIVE-enrolled in its class, OR they own the PRACTICE test.
 *       Otherwise 404 (existence never leaked).</li>
 *   <li><b>Lecturer</b> (role already gated by {@code @PreAuthorize}) may manage
 *       an exam iff {@code created_by == user} OR they lead the exam's class.
 *       A non-owner gets 403; a missing/deleted exam gets 404.</li>
 *   <li>Attempt/review access is strictly per-user.</li>
 * </ul>
 */
@Component
public class TestAccessResolver {

    /** Canonical not-found message; identical for every inaccessible exam. */
    public static final String NF_MSG = "Không tìm thấy bài test hoặc bạn không có quyền truy cập";
    /** Canonical attempt not-found message. */
    public static final String ATTEMPT_NF_MSG = "Không tìm thấy lượt làm bài";

    private final TestRepository testRepository;
    private final TestAttemptRepository attemptRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final ClassRepository classRepository;

    public TestAccessResolver(TestRepository testRepository,
                              TestAttemptRepository attemptRepository,
                              EnrollmentRepository enrollmentRepository,
                              ClassRepository classRepository) {
        this.testRepository = testRepository;
        this.attemptRepository = attemptRepository;
        this.enrollmentRepository = enrollmentRepository;
        this.classRepository = classRepository;
    }

    /**
     * Returns the exam if the student may take/view it; otherwise 404 so
     * existence is not leaked.
     */
    public Test requireAttemptable(Long testId, Long userId) {
        Test test = loadOrNotFound(testId);
        // Own PRACTICE test: always accessible to its owner.
        if (test.isPractice() && userId.equals(test.getCreatedBy())) {
            return test;
        }
        // Published class exam: accessible to ACTIVE-enrolled students.
        if (test.isPublished() && isActiveEnrolled(userId, test.getClassId())) {
            return test;
        }
        throw new EntityNotFoundException(NF_MSG);
    }

    /**
     * Returns the exam if the acting lecturer owns it (created it or leads its
     * class); a non-owner referencing an existing exam gets 403, a
     * missing/deleted exam gets 404.
     */
    public Test requireManageable(Long testId, Long userId) {
        Test test = loadOrNotFound(testId);
        if (userId.equals(test.getCreatedBy()) || leadsClass(userId, test.getClassId())) {
            return test;
        }
        throw new AccessDeniedException(NF_MSG);
    }

    /** Returns the caller's own attempt; otherwise 404 (never leaks another user's). */
    public TestAttempt requireOwnAttempt(Long attemptId, Long userId) {
        return attemptRepository.findByIdAndUserId(attemptId, userId)
                .orElseThrow(() -> new EntityNotFoundException(ATTEMPT_NF_MSG));
    }

    /** Loads an attempt for a lecturer who owns the given exam (submissions review). */
    public TestAttempt requireAttemptForManageable(Long testId, Long attemptId, Long userId) {
        requireManageable(testId, userId);
        TestAttempt attempt = attemptRepository.findById(attemptId)
                .orElseThrow(() -> new EntityNotFoundException(ATTEMPT_NF_MSG));
        if (!attempt.getTestId().equals(testId)) {
            throw new EntityNotFoundException(ATTEMPT_NF_MSG);
        }
        return attempt;
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private Test loadOrNotFound(Long testId) {
        Test test = testRepository.findById(testId)
                .orElseThrow(() -> new EntityNotFoundException(NF_MSG));
        // Guard the persistence-context cache against a just-soft-deleted test.
        if (test.isDeleted()) {
            throw new EntityNotFoundException(NF_MSG);
        }
        return test;
    }

    private boolean isActiveEnrolled(Long userId, Long classId) {
        if (classId == null) return false;
        return enrollmentRepository.findByUserIdAndClassId(userId, classId)
                .map(e -> Enrollment.STATUS_ACTIVE.equals(e.getStatus()))
                .orElse(false);
    }

    private boolean leadsClass(Long userId, Long classId) {
        if (classId == null) return false;
        return classRepository.findById(classId)
                .map(ClassEntity::getLecturerId)
                .map(userId::equals)
                .orElse(false);
    }
}
