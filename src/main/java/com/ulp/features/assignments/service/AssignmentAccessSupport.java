package com.ulp.features.assignments.service;

import com.ulp.features.assignments.dto.AssignmentDtos.AssignmentForm;
import com.ulp.features.assignments.dto.AssignmentDtos.AssignmentRow;
import com.ulp.features.assignments.entity.Assignment;
import com.ulp.features.assignments.repository.AssignmentRepository;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.features.classes.repository.EnrollmentRepository;
import com.ulp.security.Role;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

import static com.ulp.common.IConstant.MSG_ASSIGNMENT_MAX_SCORE_NEGATIVE;
import static com.ulp.common.IConstant.MSG_ASSIGNMENT_NOT_FOUND;
import static com.ulp.common.IConstant.MSG_ASSIGNMENT_TITLE_BLANK;
import static com.ulp.common.IConstant.MSG_NOT_ENROLLED;

/**
 * Shared assignment access, mapping, and form helpers used by lecturer and
 * student assignment services. Keeps ownership/enrollment checks in one place.
 */
@Component
public class AssignmentAccessSupport {

    private final AssignmentRepository assignmentRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final UserRepository userRepository;
    private final ClassRepository classRepository;

    public AssignmentAccessSupport(AssignmentRepository assignmentRepository,
                                   EnrollmentRepository enrollmentRepository,
                                   UserRepository userRepository,
                                   ClassRepository classRepository) {
        this.assignmentRepository = assignmentRepository;
        this.enrollmentRepository = enrollmentRepository;
        this.userRepository = userRepository;
        this.classRepository = classRepository;
    }

    /**
     * Verifies the caller owns (or has admin access to) the class. Non-owner → 404 no-leak.
     * Mirrors the ownership check in {@code ClassesService.getEditable}.
     */
    public void requireEditableClass(Long classId, Long userId, Role role) {
        classRepository.findById(classId)
                .filter(c -> !c.isDeleted())
                .filter(c -> role == Role.LECTURER
                        ? c.getLecturerId().equals(userId)
                        : true) // HEAD and ADMIN may access any class
                .orElseThrow(() -> new EntityNotFoundException(MSG_ASSIGNMENT_NOT_FOUND));
    }

    /** Validates that the student is ACTIVE-enrolled in the class. */
    public void requireActiveEnrollment(Long classId, Long userId) {
        enrollmentRepository.findByUserIdAndClassId(userId, classId)
                .filter(e -> "ACTIVE".equals(e.getStatus()))
                .orElseThrow(() -> new EntityNotFoundException(MSG_NOT_ENROLLED));
    }

    /** Loads a non-deleted assignment scoped to a class or throws 404. */
    public Assignment requireAssignment(Long classId, Long assignmentId) {
        return assignmentRepository.findByIdAndClassIdNotDeleted(assignmentId, classId)
                .orElseThrow(() -> new EntityNotFoundException(MSG_ASSIGNMENT_NOT_FOUND));
    }

    /** Maps Assignment entity + submission count to an AssignmentRow DTO. */
    public AssignmentRow toRow(Assignment a, long subCount) {
        return new AssignmentRow(a.getId(), a.getTitle(), a.getStatus(),
                a.getDueDate(), a.getMaxScore(), subCount);
    }

    /** Applies form fields to an Assignment entity. */
    public void applyForm(Assignment a, AssignmentForm form) {
        a.setTitle(form.title());
        a.setDescription(form.description() != null ? form.description() : "");
        a.setMaxScore(form.maxScore() != null ? form.maxScore() : BigDecimal.valueOf(100));
        a.setDueDate(form.dueDate());
        a.setAllowLateSubmission(form.allowLateSubmission());
    }

    /** Validates the assignment form fields. */
    public void validateForm(AssignmentForm form) {
        if (form.title() == null || form.title().isBlank()) {
            throw new IllegalArgumentException(MSG_ASSIGNMENT_TITLE_BLANK);
        }
        if (form.maxScore() != null && form.maxScore().compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException(MSG_ASSIGNMENT_MAX_SCORE_NEGATIVE);
        }
    }

    /** Resolves a user's display name; falls back to "Sinh viên" when not found. */
    public String resolveUserName(Long userId) {
        return userRepository.findById(userId)
                .map(u -> u.getFullName())
                .orElse("Sinh viên");
    }

    /** Resolves a user's email; falls back to empty string when not found. */
    public String resolveUserEmail(Long userId) {
        return userRepository.findById(userId)
                .map(u -> u.getEmail())
                .orElse("");
    }

    public AssignmentRepository assignments() {
        return assignmentRepository;
    }

    public EnrollmentRepository enrollments() {
        return enrollmentRepository;
    }
}
