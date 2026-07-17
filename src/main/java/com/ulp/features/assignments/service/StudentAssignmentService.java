package com.ulp.features.assignments.service;

import com.ulp.features.assignments.dto.AssignmentDtos.StudentAssignmentDetail;
import com.ulp.features.assignments.dto.AssignmentDtos.StudentAssignmentRow;
import com.ulp.features.assignments.dto.AssignmentDtos.SubmitForm;
import com.ulp.features.assignments.entity.Assignment;
import com.ulp.features.assignments.entity.AssignmentFeedback;
import com.ulp.features.assignments.entity.AssignmentStatus;
import com.ulp.features.assignments.entity.AssignmentSubmission;
import com.ulp.features.assignments.repository.AssignmentFeedbackRepository;
import com.ulp.features.assignments.repository.AssignmentRepository;
import com.ulp.features.assignments.repository.AssignmentSubmissionRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static com.ulp.common.IConstant.MSG_ASSIGNMENT_INVALID_TRANSITION;
import static com.ulp.common.IConstant.MSG_ASSIGNMENT_NOT_FOUND;
import static com.ulp.common.IConstant.MSG_SUBMIT_AFTER_GRADED;
import static com.ulp.common.IConstant.MSG_SUBMIT_LATE;

/**
 * Student assignment workflow: list published work, view detail, submit.
 * Enrollment checks live in {@link AssignmentAccessSupport}.
 */
@Service
public class StudentAssignmentService {

    private final AssignmentRepository assignmentRepository;
    private final AssignmentSubmissionRepository submissionRepository;
    private final AssignmentFeedbackRepository feedbackRepository;
    private final AssignmentAccessSupport access;

    public StudentAssignmentService(AssignmentRepository assignmentRepository,
                                    AssignmentSubmissionRepository submissionRepository,
                                    AssignmentFeedbackRepository feedbackRepository,
                                    AssignmentAccessSupport access) {
        this.assignmentRepository = assignmentRepository;
        this.submissionRepository = submissionRepository;
        this.feedbackRepository = feedbackRepository;
        this.access = access;
    }

    /**
     * Lists published (and closed) assignments for a class the student is enrolled in.
     * Returns 404 when the student is not ACTIVE-enrolled.
     */
    @Transactional(readOnly = true)
    public List<StudentAssignmentRow> listPublishedForStudent(Long classId, Long userId) {
        access.requireActiveEnrollment(classId, userId);
        return assignmentRepository.findPublishedByClassId(classId).stream()
                .map(a -> {
                    Optional<AssignmentSubmission> sub =
                            submissionRepository.findByAssignmentIdAndUserId(a.getId(), userId);
                    return new StudentAssignmentRow(
                            a.getId(), a.getTitle(), a.getStatus(),
                            a.getDueDate(), a.getMaxScore(),
                            sub.map(AssignmentSubmission::getStatus).orElse(null),
                            sub.map(AssignmentSubmission::isLate).orElse(false));
                }).toList();
    }

    /**
     * Returns the full assignment detail for a student (includes their own submission + feedback).
     */
    @Transactional(readOnly = true)
    public StudentAssignmentDetail getForStudent(Long classId, Long assignmentId, Long userId) {
        access.requireActiveEnrollment(classId, userId);
        Assignment a = assignmentRepository.findByIdAndClassIdNotDeleted(assignmentId, classId)
                .filter(asgn -> !AssignmentStatus.DRAFT.equals(asgn.getStatus()))
                .orElseThrow(() -> new EntityNotFoundException(MSG_ASSIGNMENT_NOT_FOUND));
        Optional<AssignmentSubmission> sub =
                submissionRepository.findByAssignmentIdAndUserId(assignmentId, userId);
        Optional<AssignmentFeedback> fb = sub
                .flatMap(s -> feedbackRepository.findBySubmissionId(s.getId()));
        return new StudentAssignmentDetail(
                a.getId(), a.getTitle(), a.getDescription(), a.getStatus(),
                a.getDueDate(), a.getMaxScore(), a.isAllowLateSubmission(),
                sub.map(AssignmentSubmission::getId).orElse(null),
                sub.map(AssignmentSubmission::getContent).orElse(null),
                sub.map(AssignmentSubmission::getStatus).orElse(null),
                sub.map(AssignmentSubmission::isLate).orElse(false),
                fb.map(AssignmentFeedback::getScore).orElse(null),
                fb.map(AssignmentFeedback::getFeedback).orElse(null));
    }

    /**
     * Submits or re-submits a student's work for a published assignment.
     *
     * <p>Rules:
     * <ul>
     *   <li>Assignment must be PUBLISHED.</li>
     *   <li>If now &gt; due_date and allow_late=false → reject.</li>
     *   <li>If now &gt; due_date and allow_late=true → is_late=true.</li>
     *   <li>Re-submission before GRADED → upsert (no dup row).</li>
     *   <li>Refuse edit after GRADED.</li>
     * </ul>
     *
     * @throws IllegalStateException    when the assignment is not PUBLISHED
     * @throws IllegalArgumentException when late and not allowed, or after GRADED
     */
    @Transactional
    public void submit(Long classId, Long assignmentId, SubmitForm form, Long userId) {
        access.requireActiveEnrollment(classId, userId);
        Assignment a = assignmentRepository.findByIdAndClassIdNotDeleted(assignmentId, classId)
                .orElseThrow(() -> new EntityNotFoundException(MSG_ASSIGNMENT_NOT_FOUND));
        if (!AssignmentStatus.PUBLISHED.equals(a.getStatus())) {
            throw new IllegalStateException(MSG_ASSIGNMENT_INVALID_TRANSITION);
        }

        // Check existing submission.
        Optional<AssignmentSubmission> existing =
                submissionRepository.findByAssignmentIdAndUserId(assignmentId, userId);
        if (existing.isPresent() && AssignmentStatus.SUB_GRADED.equals(existing.get().getStatus())) {
            throw new IllegalArgumentException(MSG_SUBMIT_AFTER_GRADED);
        }

        // Late-submission logic.
        boolean isLate = false;
        if (a.getDueDate() != null && LocalDateTime.now().isAfter(a.getDueDate())) {
            if (!a.isAllowLateSubmission()) {
                throw new IllegalArgumentException(MSG_SUBMIT_LATE);
            }
            isLate = true;
        }

        // Upsert submission.
        AssignmentSubmission sub = existing.orElseGet(AssignmentSubmission::new);
        sub.setAssignmentId(assignmentId);
        sub.setUserId(userId);
        sub.setContent(form.content());
        sub.setStatus(AssignmentStatus.SUB_SUBMITTED);
        sub.setLate(isLate);
        sub.setSubmittedAt(LocalDateTime.now());
        sub.setUpdatedAt(LocalDateTime.now());
        submissionRepository.save(sub);
    }
}
