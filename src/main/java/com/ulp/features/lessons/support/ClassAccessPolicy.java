package com.ulp.features.lessons.support;

import com.ulp.entities.ClassEntity;
import com.ulp.entities.Enrollment;
import com.ulp.features.classes.repository.EnrollmentRepository;
import com.ulp.security.Role;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Component;

/**
 * Shared class-access policy for student-facing lesson surfaces (lessons list,
 * lesson detail, comments). Centralizes the "who may view/moderate a class's
 * lesson thread" rule so callers no longer repeat it inline.
 *
 * <p>The rule: ADMIN/HEAD bypass enrollment (they moderate any class), the
 * owning lecturer passes, and otherwise an ACTIVE enrollment is required. Any
 * other caller collapses to a single {@link EntityNotFoundException} with the
 * canonical message so class existence is never leaked.
 *
 * <p>This is intentionally separate from {@link LessonAccessResolver}, which
 * owns only lesson resolution + the PUBLISHED gate; access policy stays here so
 * each caller can order the two gates as its no-leak contract requires.
 */
@Component
public class ClassAccessPolicy {

    private static final String NF_MSG = "Class not found or not accessible";

    private final EnrollmentRepository enrollmentRepository;

    public ClassAccessPolicy(EnrollmentRepository enrollmentRepository) {
        this.enrollmentRepository = enrollmentRepository;
    }

    /** ADMIN and HEAD are privileged moderators across every class. */
    public boolean isPrivileged(Role role) {
        return role == Role.ADMIN || role == Role.HEAD;
    }

    /** A moderator is the owning lecturer or any ADMIN/HEAD. */
    public boolean isModerator(ClassEntity clazz, Long userId, Role role) {
        return clazz.getLecturerId().equals(userId) || isPrivileged(role);
    }

    /**
     * Admits the caller to the given live class or throws a no-leak 404.
     * ADMIN/HEAD bypass enrollment so they can moderate the thread, the owning
     * lecturer passes, otherwise an ACTIVE enrollment is required.
     *
     * @throws EntityNotFoundException when no rule admits the caller
     */
    public void requireModeratorOrEnrolled(ClassEntity clazz, Long userId, Role role) {
        // ADMIN/HEAD may open any live class to moderate its lesson thread.
        if (isPrivileged(role)) {
            return;
        }
        boolean lecturer = clazz.getLecturerId().equals(userId);
        boolean enrolled = enrollmentRepository
                .findByUserIdAndClassId(userId, clazz.getId())
                .filter(e -> Enrollment.STATUS_ACTIVE.equals(e.getStatus()))
                .isPresent();
        if (!lecturer && !enrolled) {
            throw new EntityNotFoundException(NF_MSG);
        }
    }
}
