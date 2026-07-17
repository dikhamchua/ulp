package com.ulp.features.assignments.service;

import com.ulp.entities.Enrollment;
import com.ulp.features.assignments.dto.AssignmentDtos.AssignmentForm;
import com.ulp.features.assignments.dto.AssignmentDtos.AssignmentRow;
import com.ulp.features.assignments.dto.AssignmentDtos.GradeForm;
import com.ulp.features.assignments.dto.AssignmentDtos.StudentAssignmentDetail;
import com.ulp.features.assignments.dto.AssignmentDtos.StudentAssignmentRow;
import com.ulp.features.assignments.dto.AssignmentDtos.SubmissionDetail;
import com.ulp.features.assignments.dto.AssignmentDtos.SubmissionRow;
import com.ulp.features.assignments.dto.AssignmentDtos.SubmitForm;
import com.ulp.features.assignments.entity.Assignment;
import com.ulp.features.assignments.entity.AssignmentFeedback;
import com.ulp.features.assignments.entity.AssignmentStatus;
import com.ulp.features.assignments.entity.AssignmentSubmission;
import com.ulp.features.assignments.repository.AssignmentFeedbackRepository;
import com.ulp.features.assignments.repository.AssignmentRepository;
import com.ulp.features.assignments.repository.AssignmentSubmissionRepository;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.features.classes.repository.EnrollmentRepository;
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
import static com.ulp.common.IConstant.MSG_NOT_ENROLLED;
import static com.ulp.common.IConstant.MSG_SUBMIT_AFTER_GRADED;
import static com.ulp.common.IConstant.MSG_SUBMIT_LATE;

/**
 * Application service for the assignments feature (Sprint 6, #70).
 *
 * <p>Owns both the lecturer workflow (create/edit/publish/close/grade) and the
 * student workflow (list/submit/view feedback). All state-machine rules, late-
 * submission logic, and score range validation are enforced here, not in the
 * controllers.
 *
 * <p>Entity never leaks past this layer — every public method returns a DTO.
 */
@Service
public class AssignmentService {

    private final AssignmentRepository assignmentRepository;
    private final AssignmentSubmissionRepository submissionRepository;
    private final AssignmentFeedbackRepository feedbackRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final UserRepository userRepository;
    private final ClassRepository classRepository;
    private final NotificationService notificationService;

    public AssignmentService(
            AssignmentRepository assignmentRepository,
            AssignmentSubmissionRepository submissionRepository,
            AssignmentFeedbackRepository feedbackRepository,
            EnrollmentRepository enrollmentRepository,
            UserRepository userRepository,
            ClassRepository classRepository,
            NotificationService notificationService) {
        this.assignmentRepository = assignmentRepository;
        this.submissionRepository = submissionRepository;
        this.feedbackRepository = feedbackRepository;
        this.enrollmentRepository = enrollmentRepository;
        this.userRepository = userRepository;
        this.classRepository = classRepository;
        this.notificationService = notificationService;
    }

    // ── Lecturer: list ─────────────────────────────────────────────────────

    /**
     * Lists all non-deleted assignments for a class visible to the lecturer.
     * Ownership enforced: non-owner gets empty list via 404-safe class check.
     *
     * @param classId the class id
     * @param userId  the caller's user id
     * @param role    the caller's role
     * @return list of assignment rows for the class
     */
    @Transactional(readOnly = true)
    public List<AssignmentRow> listForLecturer(Long classId, Long userId, Role role) {
        requireEditableClass(classId, userId, role);
        List<Assignment> assignments = assignmentRepository.findAllByClassIdNotDeleted(classId);
        return assignments.stream().map(a -> {
            long subCount = submissionRepository.findAllByAssignmentId(a.getId()).size();
            return toRow(a, subCount);
        }).toList();
    }

    // ── Lecturer: create / edit ─────────────────────────────────────────────

    /**
     * Creates a new assignment in DRAFT status for the given class.
     *
     * <p>Validates title non-blank and max_score ≥ 0. Ownership enforced.
     *
     * @param classId the class id
     * @param form    the form data
     * @param userId  creator's user id
     * @param role    creator's role
     * @return the created assignment id
     * @throws IllegalArgumentException when validation fails
     */
    @Transactional
    public Long create(Long classId, AssignmentForm form, Long userId, Role role) {
        requireEditableClass(classId, userId, role);
        validateForm(form);

        Assignment a = new Assignment();
        a.setClassId(classId);
        a.setStatus(AssignmentStatus.DRAFT);
        a.setCreatedBy(userId);
        a.setCreatedAt(LocalDateTime.now());
        a.setUpdatedAt(LocalDateTime.now());
        applyForm(a, form);
        return assignmentRepository.save(a).getId();
    }

    /**
     * Updates an existing assignment. Only DRAFT or PUBLISHED assignments can be edited.
     *
     * @param classId      the class id
     * @param assignmentId the assignment id
     * @param form         the updated form data
     * @param userId       the caller's user id
     * @param role         the caller's role
     * @throws IllegalArgumentException when validation fails
     */
    @Transactional
    public void update(Long classId, Long assignmentId, AssignmentForm form, Long userId, Role role) {
        requireEditableClass(classId, userId, role);
        validateForm(form);
        Assignment a = requireAssignment(classId, assignmentId);
        applyForm(a, form);
        a.setUpdatedAt(LocalDateTime.now());
        assignmentRepository.save(a);
    }

    // ── Lecturer: lifecycle transitions ────────────────────────────────────

    /**
     * Publishes a DRAFT assignment (DRAFT → PUBLISHED).
     * Fan-out: creates an ASSIGNMENT_PUBLISHED notification for every active-enrolled student.
     *
     * @param classId      the class id
     * @param assignmentId the assignment id
     * @param userId       the caller's user id
     * @param role         the caller's role
     * @throws IllegalStateException when the assignment is not in DRAFT
     */
    @Transactional
    public void publish(Long classId, Long assignmentId, Long userId, Role role) {
        requireEditableClass(classId, userId, role);
        Assignment a = requireAssignment(classId, assignmentId);
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
     * @param classId      the class id
     * @param assignmentId the assignment id
     * @param userId       the caller's user id
     * @param role         the caller's role
     * @throws IllegalStateException when the assignment is not in PUBLISHED
     */
    @Transactional
    public void close(Long classId, Long assignmentId, Long userId, Role role) {
        requireEditableClass(classId, userId, role);
        Assignment a = requireAssignment(classId, assignmentId);
        if (!AssignmentStatus.PUBLISHED.equals(a.getStatus())) {
            throw new IllegalStateException(MSG_ASSIGNMENT_INVALID_TRANSITION);
        }
        a.setStatus(AssignmentStatus.CLOSED);
        a.setUpdatedAt(LocalDateTime.now());
        assignmentRepository.save(a);
    }

    // ── Lecturer: submissions list + grade ─────────────────────────────────

    /**
     * Returns all submissions for a given assignment on the lecturer's grade screen.
     *
     * @param classId      the class id
     * @param assignmentId the assignment id
     * @param userId       the caller's user id
     * @param role         the caller's role
     * @return list of submission rows
     */
    @Transactional(readOnly = true)
    public List<SubmissionRow> listSubmissions(Long classId, Long assignmentId, Long userId, Role role) {
        requireEditableClass(classId, userId, role);
        requireAssignment(classId, assignmentId);
        return submissionRepository.findAllByAssignmentId(assignmentId).stream()
                .map(s -> {
                    String studentName = resolveUserName(s.getUserId());
                    String studentEmail = resolveUserEmail(s.getUserId());
                    BigDecimal score = feedbackRepository.findBySubmissionId(s.getId())
                            .map(AssignmentFeedback::getScore).orElse(null);
                    return new SubmissionRow(s.getId(), studentName, studentEmail,
                            s.getStatus(), s.isLate(), s.getSubmittedAt(), score);
                }).toList();
    }

    /**
     * Returns the submission detail for the grade form.
     *
     * @param classId      the class id
     * @param assignmentId the assignment id
     * @param submissionId the submission id
     * @param userId       the caller's user id
     * @param role         the caller's role
     * @return full submission detail
     */
    @Transactional(readOnly = true)
    public SubmissionDetail getSubmissionDetail(Long classId, Long assignmentId,
                                                Long submissionId, Long userId, Role role) {
        requireEditableClass(classId, userId, role);
        Assignment a = requireAssignment(classId, assignmentId);
        AssignmentSubmission sub = submissionRepository.findById(submissionId)
                .filter(s -> s.getAssignmentId().equals(assignmentId))
                .orElseThrow(() -> new EntityNotFoundException(MSG_ASSIGNMENT_NOT_FOUND));
        Optional<AssignmentFeedback> fb = feedbackRepository.findBySubmissionId(submissionId);
        return new SubmissionDetail(
                sub.getId(), assignmentId,
                resolveUserName(sub.getUserId()), resolveUserEmail(sub.getUserId()),
                sub.getContent(), sub.getStatus(), sub.isLate(), sub.getSubmittedAt(),
                fb.map(AssignmentFeedback::getScore).orElse(null),
                fb.map(AssignmentFeedback::getFeedback).orElse(null),
                a.getMaxScore());
    }

    /**
     * Grades a submission: validates score range, upserts the feedback row,
     * sets submission GRADED, and emits ASSIGNMENT_GRADED to the student.
     *
     * @param classId      the class id
     * @param assignmentId the assignment id
     * @param submissionId the submission id
     * @param form         the grade form (score + feedback)
     * @param graderId     the grading lecturer's user id
     * @param role         the grader's role
     * @throws IllegalArgumentException when score is out of range
     */
    @Transactional
    public void grade(Long classId, Long assignmentId, Long submissionId,
                      GradeForm form, Long graderId, Role role) {
        requireEditableClass(classId, graderId, role);
        Assignment a = requireAssignment(classId, assignmentId);
        AssignmentSubmission sub = submissionRepository.findById(submissionId)
                .filter(s -> s.getAssignmentId().equals(assignmentId))
                .orElseThrow(() -> new EntityNotFoundException(MSG_ASSIGNMENT_NOT_FOUND));

        // Validate score is in [0, maxScore].
        if (form.score() == null
                || form.score().compareTo(BigDecimal.ZERO) < 0
                || form.score().compareTo(a.getMaxScore()) > 0) {
            throw new IllegalArgumentException(
                    "Điểm phải nằm trong khoảng 0 đến " + a.getMaxScore());
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
                    "Bài tập đã được chấm điểm",
                    "Bài tập \"" + a.getTitle() + "\" của bạn đã được chấm. Điểm: " + form.score(),
                    NotificationType.ASSIGNMENT_GRADED,
                    NotificationType.REF_ASSIGNMENT,
                    assignmentId);
        } catch (Exception ignored) {
            // Notification failure must not roll back the grade.
        }
    }

    // ── Student: list + submit + view feedback ──────────────────────────────

    /**
     * Lists published (and closed) assignments for a class the student is enrolled in.
     * Returns 404 when the student is not ACTIVE-enrolled.
     *
     * @param classId the class id
     * @param userId  the student's user id
     * @return list of student-facing assignment rows
     */
    @Transactional(readOnly = true)
    public List<StudentAssignmentRow> listPublishedForStudent(Long classId, Long userId) {
        requireActiveEnrollment(classId, userId);
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
     *
     * @param classId      the class id
     * @param assignmentId the assignment id
     * @param userId       the student's user id
     * @return the student assignment detail
     */
    @Transactional(readOnly = true)
    public StudentAssignmentDetail getForStudent(Long classId, Long assignmentId, Long userId) {
        requireActiveEnrollment(classId, userId);
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
     * @param classId      the class id
     * @param assignmentId the assignment id
     * @param form         the submission content
     * @param userId       the student's user id
     * @throws IllegalStateException    when the assignment is not PUBLISHED
     * @throws IllegalArgumentException when late and not allowed, or after GRADED
     */
    @Transactional
    public void submit(Long classId, Long assignmentId, SubmitForm form, Long userId) {
        requireActiveEnrollment(classId, userId);
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

    // ── Lecturer helper: load assignment for form ───────────────────────────

    /**
     * Loads an assignment as a form for editing (lecturer only).
     *
     * @param classId      the class id
     * @param assignmentId the assignment id
     * @param userId       the caller's user id
     * @param role         the caller's role
     * @return the populated form DTO
     */
    @Transactional(readOnly = true)
    public AssignmentForm getFormForEdit(Long classId, Long assignmentId, Long userId, Role role) {
        requireEditableClass(classId, userId, role);
        Assignment a = requireAssignment(classId, assignmentId);
        return new AssignmentForm(a.getId(), a.getTitle(), a.getDescription(),
                a.getMaxScore(), a.getDueDate(), a.isAllowLateSubmission());
    }

    // ── Internal helpers ───────────────────────────────────────────────────

    /**
     * Verifies the caller owns (or has admin access to) the class. Non-owner → 404 no-leak.
     * Mirrors the ownership check in {@code ClassesService.getEditable}.
     */
    private void requireEditableClass(Long classId, Long userId, Role role) {
        classRepository.findById(classId)
                .filter(c -> !c.isDeleted())
                .filter(c -> role == Role.LECTURER
                        ? c.getLecturerId().equals(userId)
                        : true) // HEAD and ADMIN may access any class
                .orElseThrow(() -> new EntityNotFoundException(MSG_ASSIGNMENT_NOT_FOUND));
    }

    /** Validates that the student is ACTIVE-enrolled in the class. */
    private void requireActiveEnrollment(Long classId, Long userId) {
        enrollmentRepository.findByUserIdAndClassId(userId, classId)
                .filter(e -> "ACTIVE".equals(e.getStatus()))
                .orElseThrow(() -> new EntityNotFoundException(MSG_NOT_ENROLLED));
    }

    /** Loads a non-deleted assignment scoped to a class or throws 404. */
    private Assignment requireAssignment(Long classId, Long assignmentId) {
        return assignmentRepository.findByIdAndClassIdNotDeleted(assignmentId, classId)
                .orElseThrow(() -> new EntityNotFoundException(MSG_ASSIGNMENT_NOT_FOUND));
    }

    /** Maps Assignment entity + submission count to an AssignmentRow DTO. */
    private AssignmentRow toRow(Assignment a, long subCount) {
        return new AssignmentRow(a.getId(), a.getTitle(), a.getStatus(),
                a.getDueDate(), a.getMaxScore(), subCount);
    }

    /** Applies form fields to an Assignment entity. */
    private void applyForm(Assignment a, AssignmentForm form) {
        a.setTitle(form.title());
        a.setDescription(form.description() != null ? form.description() : "");
        a.setMaxScore(form.maxScore() != null ? form.maxScore() : BigDecimal.valueOf(100));
        a.setDueDate(form.dueDate());
        a.setAllowLateSubmission(form.allowLateSubmission());
    }

    /** Validates the assignment form fields. */
    private void validateForm(AssignmentForm form) {
        if (form.title() == null || form.title().isBlank()) {
            throw new IllegalArgumentException("Tiêu đề không được để trống");
        }
        if (form.maxScore() != null && form.maxScore().compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Điểm tối đa không được âm");
        }
    }

    /**
     * Fan-out: sends ASSIGNMENT_PUBLISHED notification to every ACTIVE-enrolled student.
     * Best-effort — failure for one student does not abort others.
     */
    private void fanOutAssignmentPublished(Long classId, Assignment a) {
        try {
            enrollmentRepository
                    .findAllByClassIdAndStatusOrderByJoinedAtDesc(classId, "ACTIVE")
                    .forEach(e -> {
                        try {
                            notificationService.create(
                                    e.getUser().getId(),
                                    "Bài tập mới được xuất bản",
                                    "Bài tập \"" + a.getTitle() + "\" vừa được xuất bản.",
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

    /** Resolves a user's display name; falls back to "Sinh viên" when not found. */
    private String resolveUserName(Long userId) {
        return userRepository.findById(userId)
                .map(u -> u.getFullName())
                .orElse("Sinh viên");
    }

    /** Resolves a user's email; falls back to empty string when not found. */
    private String resolveUserEmail(Long userId) {
        return userRepository.findById(userId)
                .map(u -> u.getEmail())
                .orElse("");
    }

}
