package com.ulp.features.assignments.service;

import com.ulp.features.assignments.dto.AssignmentDtos.AssignmentForm;
import com.ulp.features.assignments.dto.AssignmentDtos.AssignmentRow;
import com.ulp.features.assignments.dto.AssignmentDtos.GradeForm;
import com.ulp.features.assignments.dto.AssignmentDtos.SubmissionDetail;
import com.ulp.features.assignments.dto.AssignmentDtos.SubmissionRow;
import com.ulp.features.assignments.entity.Assignment;
import com.ulp.features.assignments.entity.AssignmentFeedback;
import com.ulp.features.assignments.entity.AssignmentStatus;
import com.ulp.features.assignments.entity.AssignmentSubmission;
import com.ulp.features.assignments.repository.AssignmentFeedbackRepository;
import com.ulp.features.assignments.repository.AssignmentRepository;
import com.ulp.features.assignments.repository.AssignmentSubmissionRepository;
import com.ulp.features.notifications.entity.NotificationType;
import com.ulp.features.notifications.service.NotificationService;
import com.ulp.security.Role;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static com.ulp.common.IConstant.MSG_ASSIGNMENT_INVALID_TRANSITION;
import static com.ulp.common.IConstant.MSG_ASSIGNMENT_NOT_FOUND;
import static com.ulp.common.IConstant.MSG_GRADE_SCORE_INVALID;
import static com.ulp.common.IConstant.MSG_NOTIF_ASSIGNMENT_GRADED_BODY_MID;
import static com.ulp.common.IConstant.MSG_NOTIF_ASSIGNMENT_GRADED_BODY_PREFIX;
import static com.ulp.common.IConstant.MSG_NOTIF_ASSIGNMENT_GRADED_TITLE;
import static com.ulp.common.IConstant.MSG_NOTIF_ASSIGNMENT_PUBLISHED_BODY_PREFIX;
import static com.ulp.common.IConstant.MSG_NOTIF_ASSIGNMENT_PUBLISHED_BODY_SUFFIX;
import static com.ulp.common.IConstant.MSG_NOTIF_ASSIGNMENT_PUBLISHED_TITLE;

/**
 * Lecturer assignment workflow: create/edit/publish/close/grade.
 * Ownership and form helpers live in {@link AssignmentAccessSupport}.
 */
@Service
public class LecturerAssignmentService {

    private final AssignmentRepository assignmentRepository;
    private final AssignmentSubmissionRepository submissionRepository;
    private final AssignmentFeedbackRepository feedbackRepository;
    private final AssignmentAccessSupport access;
    private final NotificationService notificationService;

    public LecturerAssignmentService(AssignmentRepository assignmentRepository,
                                     AssignmentSubmissionRepository submissionRepository,
                                     AssignmentFeedbackRepository feedbackRepository,
                                     AssignmentAccessSupport access,
                                     NotificationService notificationService) {
        this.assignmentRepository = assignmentRepository;
        this.submissionRepository = submissionRepository;
        this.feedbackRepository = feedbackRepository;
        this.access = access;
        this.notificationService = notificationService;
    }

    /**
     * Lists all non-deleted assignments for a class visible to the lecturer.
     * Ownership enforced: non-owner gets empty list via 404-safe class check.
     */
    @Transactional(readOnly = true)
    public List<AssignmentRow> listForLecturer(Long classId, Long userId, Role role) {
        access.requireEditableClass(classId, userId, role);
        List<Assignment> assignments = assignmentRepository.findAllByClassIdNotDeleted(classId);
        return assignments.stream().map(a -> {
            long subCount = submissionRepository.countByAssignmentId(a.getId());
            return access.toRow(a, subCount);
        }).toList();
    }

    /**
     * Creates a new assignment in DRAFT status for the given class.
     *
     * @throws IllegalArgumentException when validation fails
     */
    @Transactional
    public Long create(Long classId, AssignmentForm form, Long userId, Role role) {
        access.requireEditableClass(classId, userId, role);
        access.validateForm(form);

        Assignment a = new Assignment();
        a.setClassId(classId);
        a.setStatus(AssignmentStatus.DRAFT);
        a.setCreatedBy(userId);
        a.setCreatedAt(LocalDateTime.now());
        a.setUpdatedAt(LocalDateTime.now());
        access.applyForm(a, form);
        return assignmentRepository.save(a).getId();
    }

    /**
     * Updates an existing assignment. Only DRAFT or PUBLISHED assignments can be edited.
     *
     * @throws IllegalArgumentException when validation fails
     */
    @Transactional
    public void update(Long classId, Long assignmentId, AssignmentForm form, Long userId, Role role) {
        access.requireEditableClass(classId, userId, role);
        access.validateForm(form);
        Assignment a = access.requireAssignment(classId, assignmentId);
        access.applyForm(a, form);
        a.setUpdatedAt(LocalDateTime.now());
        assignmentRepository.save(a);
    }

    /**
     * Publishes a DRAFT assignment (DRAFT → PUBLISHED).
     * Fan-out: creates an ASSIGNMENT_PUBLISHED notification for every active-enrolled student.
     *
     * @throws IllegalStateException when the assignment is not in DRAFT
     */
    @Transactional
    public void publish(Long classId, Long assignmentId, Long userId, Role role) {
        access.requireEditableClass(classId, userId, role);
        Assignment a = access.requireAssignment(classId, assignmentId);
        if (!AssignmentStatus.DRAFT.equals(a.getStatus())) {
            throw new IllegalStateException(MSG_ASSIGNMENT_INVALID_TRANSITION);
        }
        a.setStatus(AssignmentStatus.PUBLISHED);
        a.setUpdatedAt(LocalDateTime.now());
        assignmentRepository.save(a);
        fanOutAssignmentPublished(classId, a);
    }

    /**
     * Closes a PUBLISHED assignment (PUBLISHED → CLOSED).
     *
     * @throws IllegalStateException when the assignment is not in PUBLISHED
     */
    @Transactional
    public void close(Long classId, Long assignmentId, Long userId, Role role) {
        access.requireEditableClass(classId, userId, role);
        Assignment a = access.requireAssignment(classId, assignmentId);
        if (!AssignmentStatus.PUBLISHED.equals(a.getStatus())) {
            throw new IllegalStateException(MSG_ASSIGNMENT_INVALID_TRANSITION);
        }
        a.setStatus(AssignmentStatus.CLOSED);
        a.setUpdatedAt(LocalDateTime.now());
        assignmentRepository.save(a);
    }

    /** Returns all submissions for a given assignment on the lecturer's grade screen. */
    @Transactional(readOnly = true)
    public List<SubmissionRow> listSubmissions(Long classId, Long assignmentId, Long userId, Role role) {
        access.requireEditableClass(classId, userId, role);
        access.requireAssignment(classId, assignmentId);
        return submissionRepository.findAllByAssignmentId(assignmentId).stream()
                .map(s -> {
                    String studentName = access.resolveUserName(s.getUserId());
                    String studentEmail = access.resolveUserEmail(s.getUserId());
                    BigDecimal score = feedbackRepository.findBySubmissionId(s.getId())
                            .map(AssignmentFeedback::getScore).orElse(null);
                    return new SubmissionRow(s.getId(), studentName, studentEmail,
                            s.getStatus(), s.isLate(), s.getSubmittedAt(), score);
                }).toList();
    }

    /** Returns the submission detail for the grade form. */
    @Transactional(readOnly = true)
    public SubmissionDetail getSubmissionDetail(Long classId, Long assignmentId,
                                                Long submissionId, Long userId, Role role) {
        access.requireEditableClass(classId, userId, role);
        Assignment a = access.requireAssignment(classId, assignmentId);
        AssignmentSubmission sub = submissionRepository.findById(submissionId)
                .filter(s -> s.getAssignmentId().equals(assignmentId))
                .orElseThrow(() -> new EntityNotFoundException(MSG_ASSIGNMENT_NOT_FOUND));
        Optional<AssignmentFeedback> fb = feedbackRepository.findBySubmissionId(submissionId);
        return new SubmissionDetail(
                sub.getId(), assignmentId,
                access.resolveUserName(sub.getUserId()), access.resolveUserEmail(sub.getUserId()),
                sub.getContent(), sub.getStatus(), sub.isLate(), sub.getSubmittedAt(),
                fb.map(AssignmentFeedback::getScore).orElse(null),
                fb.map(AssignmentFeedback::getFeedback).orElse(null),
                a.getMaxScore());
    }

    /**
     * Grades a submission: validates score range, upserts the feedback row,
     * sets submission GRADED, and emits ASSIGNMENT_GRADED to the student.
     *
     * @throws IllegalArgumentException when score is out of range
     */
    @Transactional
    public void grade(Long classId, Long assignmentId, Long submissionId,
                      GradeForm form, Long graderId, Role role) {
        access.requireEditableClass(classId, graderId, role);
        Assignment a = access.requireAssignment(classId, assignmentId);
        AssignmentSubmission sub = submissionRepository.findById(submissionId)
                .filter(s -> s.getAssignmentId().equals(assignmentId))
                .orElseThrow(() -> new EntityNotFoundException(MSG_ASSIGNMENT_NOT_FOUND));

        // Validate score is in [0, maxScore].
        if (form.score() == null
                || form.score().compareTo(BigDecimal.ZERO) < 0
                || form.score().compareTo(a.getMaxScore()) > 0) {
            throw new IllegalArgumentException(MSG_GRADE_SCORE_INVALID);
        }

        // Upsert the single feedback row.
        AssignmentFeedback fb = feedbackRepository.findBySubmissionId(submissionId)
                .orElseGet(AssignmentFeedback::new);
        fb.setSubmissionId(submissionId);
        fb.setGradedBy(graderId);
        fb.setScore(form.score());
        fb.setFeedback(form.feedback());
        if (fb.getCreatedAt() == null) {
            fb.setCreatedAt(LocalDateTime.now());
        }
        fb.setUpdatedAt(LocalDateTime.now());
        feedbackRepository.save(fb);

        // Set submission to GRADED.
        sub.setStatus(AssignmentStatus.SUB_GRADED);
        sub.setUpdatedAt(LocalDateTime.now());
        submissionRepository.save(sub);

        // Notify the student of their grade.
        try {
            notificationService.create(
                    sub.getUserId(),
                    MSG_NOTIF_ASSIGNMENT_GRADED_TITLE,
                    MSG_NOTIF_ASSIGNMENT_GRADED_BODY_PREFIX + a.getTitle()
                            + MSG_NOTIF_ASSIGNMENT_GRADED_BODY_MID + form.score(),
                    NotificationType.ASSIGNMENT_GRADED,
                    NotificationType.REF_ASSIGNMENT,
                    assignmentId);
        } catch (Exception ignored) {
            // Notification failure must not roll back the grade.
        }
    }

    /** Loads an assignment as a form for editing (lecturer only). */
    @Transactional(readOnly = true)
    public AssignmentForm getFormForEdit(Long classId, Long assignmentId, Long userId, Role role) {
        access.requireEditableClass(classId, userId, role);
        Assignment a = access.requireAssignment(classId, assignmentId);
        return new AssignmentForm(a.getId(), a.getTitle(), a.getDescription(),
                a.getMaxScore(), a.getDueDate(), a.isAllowLateSubmission());
    }

    /**
     * Fan-out: sends ASSIGNMENT_PUBLISHED notification to every ACTIVE-enrolled student.
     * Best-effort — failure for one student does not abort others.
     */
    private void fanOutAssignmentPublished(Long classId, Assignment a) {
        try {
            access.enrollments()
                    .findAllByClassIdAndStatusOrderByJoinedAtDesc(classId, "ACTIVE")
                    .forEach(e -> {
                        try {
                            notificationService.create(
                                    e.getUser().getId(),
                                    MSG_NOTIF_ASSIGNMENT_PUBLISHED_TITLE,
                                    MSG_NOTIF_ASSIGNMENT_PUBLISHED_BODY_PREFIX + a.getTitle()
                                            + MSG_NOTIF_ASSIGNMENT_PUBLISHED_BODY_SUFFIX,
                                    NotificationType.ASSIGNMENT_PUBLISHED,
                                    NotificationType.REF_ASSIGNMENT,
                                    a.getId());
                        } catch (Exception ignored) {
                            // Failure for one student must not stop other notifications.
                        }
                    });
        } catch (Exception ignored) {
            // Fan-out failure must not roll back the publish transaction.
        }
    }
}
