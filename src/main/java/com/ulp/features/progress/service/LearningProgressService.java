package com.ulp.features.progress.service;

import com.ulp.entities.Enrollment;
import com.ulp.entities.LearningProgress;
import com.ulp.features.classes.repository.EnrollmentRepository;
import com.ulp.features.lessons.support.LessonAccessResolver;
import com.ulp.features.progress.repository.LearningProgressRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Write service backing student learning-progress recording (ULP-4.5).
 *
 * <p>Re-applies the same four authz gates as
 * {@code com.ulp.features.student.service.StudentLessonDetailService}
 * (that class is canonical for the gate order); any failure collapses to
 * an {@link EntityNotFoundException} with the message
 * {@code "Class not found or not accessible"} so lesson existence is never
 * leaked. The resolution + PUBLISHED gates are shared via
 * {@link LessonAccessResolver}; the ACTIVE-enrollment policy stays inline here.
 */
@Service
public class LearningProgressService {

    private static final String NF_MSG = "Class not found or not accessible";

    private final EnrollmentRepository enrollmentRepository;
    private final LearningProgressRepository progressRepository;
    private final LessonAccessResolver lessonAccessResolver;

    public LearningProgressService(EnrollmentRepository enrollmentRepository,
                                   LearningProgressRepository progressRepository,
                                   LessonAccessResolver lessonAccessResolver) {
        this.enrollmentRepository = enrollmentRepository;
        this.progressRepository = progressRepository;
        this.lessonAccessResolver = lessonAccessResolver;
    }

    /**
     * Idempotent upsert invoked after a student successfully opens a lesson.
     * Creates an {@code IN_PROGRESS} row when none exists; leaves an existing
     * row untouched. A concurrent first-open race on the unique key surfaces
     * as {@link DataIntegrityViolationException}, which is swallowed (the row
     * now exists — the desired end state).
     *
     * @throws EntityNotFoundException when any authz gate fails
     */
    @Transactional
    public void recordOpened(Long classId, Long lessonId, Long userId) {
        runGates(classId, lessonId, userId);
        if (progressRepository.findByUserIdAndLessonId(userId, lessonId).isPresent()) {
            return; // idempotent: never touch an existing row on open
        }
        try {
            progressRepository.saveAndFlush(new LearningProgress(userId, lessonId));
        } catch (DataIntegrityViolationException race) {
            // Concurrent first-open won the unique key — the row exists now.
        }
    }

    /**
     * Toggles the lesson's completion for the student. Absent row → create
     * directly as COMPLETED; COMPLETED → revert to IN_PROGRESS; otherwise →
     * mark COMPLETED.
     *
     * @return {@code true} when the lesson is now COMPLETED, {@code false}
     *         when it was reverted to IN_PROGRESS
     * @throws EntityNotFoundException when any authz gate fails
     */
    @Transactional
    public boolean toggleCompletion(Long classId, Long lessonId, Long userId) {
        runGates(classId, lessonId, userId);
        LearningProgress progress = progressRepository
                .findByUserIdAndLessonId(userId, lessonId)
                .orElseGet(() -> new LearningProgress(userId, lessonId));

        boolean nowCompleted;
        if (progress.isCompleted()) {
            progress.revertToInProgress();
            nowCompleted = false;
        } else {
            progress.markCompleted();
            nowCompleted = true;
        }
        progressRepository.saveAndFlush(progress);
        return nowCompleted;
    }

    /**
     * Runs the four authz gates in the canonical order: ACTIVE enrollment
     * (inline), then live class, section-belongs-to-class and PUBLISHED via the
     * shared {@link LessonAccessResolver}. Any failure collapses to 404.
     */
    private void runGates(Long classId, Long lessonId, Long userId) {
        // Gate 1: enrollment must be ACTIVE — REMOVED/COMPLETED → 404.
        enrollmentRepository.findByUserIdAndClassId(userId, classId)
                .filter(e -> Enrollment.STATUS_ACTIVE.equals(e.getStatus()))
                .orElseThrow(() -> new EntityNotFoundException(NF_MSG));

        // Gates 2-4: live class, section-belongs-to-class, lesson PUBLISHED.
        lessonAccessResolver.resolveInClass(classId, lessonId);
    }
}
