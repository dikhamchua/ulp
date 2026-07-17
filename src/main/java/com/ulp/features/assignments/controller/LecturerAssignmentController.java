package com.ulp.features.assignments.controller;

import com.ulp.entities.ClassEntity;
import com.ulp.features.assignments.dto.AssignmentDtos.AssignmentForm;
import com.ulp.features.assignments.dto.AssignmentDtos.GradeForm;
import com.ulp.features.assignments.service.LecturerAssignmentService;
import com.ulp.features.classes.controller.support.ClassDetailModelSupport;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.security.Roles;
import com.ulp.security.UlpUserDetails;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import static com.ulp.common.IConstant.*;

/**
 * Lecturer-facing controller for the assignments feature (Sprint 6, #70).
 *
 * <p>All endpoints are scoped to {@code /lecturer/classes/{classId}/assignments}.
 * Ownership is enforced in {@link LecturerAssignmentService} — non-owner receives 404
 * (no existence leak). Flash messages drain to UlpToast via the page-level JS.
 *
 * <p>PRG (Post-Redirect-Get) pattern is used for all mutations.
 */
@Controller
@RequestMapping(BASE_LECTURER + PATH_CLASSES + "/{classId}" + PATH_ASSIGNMENTS)
@PreAuthorize(Roles.PREAUTH_LECTURER_OR_ABOVE)
public class LecturerAssignmentController {

    private final LecturerAssignmentService assignmentService;
    private final ClassRepository classRepository;
    private final ClassDetailModelSupport modelSupport;

    public LecturerAssignmentController(LecturerAssignmentService assignmentService,
                                        ClassRepository classRepository,
                                        ClassDetailModelSupport modelSupport) {
        this.assignmentService = assignmentService;
        this.classRepository = classRepository;
        this.modelSupport = modelSupport;
    }

    /**
     * Loads the ClassEntity for sidebar rendering; throws 404 if the class
     * does not exist or does not belong to the authenticated lecturer.
     */
    private ClassEntity loadClass(Long classId, Long userId, com.ulp.security.Role role) {
        ClassEntity clazz = classRepository.findById(classId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        // Ownership check: ADMIN and HEAD may view any class; LECTURER only their own.
        if (role == com.ulp.security.Role.LECTURER && !userId.equals(clazz.getLecturerId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return clazz;
    }

    // ── List ──────────────────────────────────────────────────────────────

    /** Lists all assignments for the class (lecturer view). */
    @GetMapping
    public String list(@PathVariable Long classId,
                       @AuthenticationPrincipal UlpUserDetails user,
                       Model model) {
        ClassEntity clazz = loadClass(classId, user.getId(), user.getRole());
        try {
            model.addAttribute(ATTR_ASSIGNMENTS,
                    assignmentService.listForLecturer(classId, user.getId(), user.getRole()));
        } catch (EntityNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        modelSupport.populateDetail(model, clazz, TAB_ASSIGNMENTS, user.getId(), user.getRole());
        model.addAttribute(ATTR_CLASS_ID, classId);
        return VIEW_ASSIGNMENT_LIST;
    }

    // ── Create ────────────────────────────────────────────────────────────

    /** Renders the create-assignment form. */
    @GetMapping("/new")
    public String newForm(@PathVariable Long classId,
                          @AuthenticationPrincipal UlpUserDetails user,
                          Model model) {
        ClassEntity clazz = loadClass(classId, user.getId(), user.getRole());
        // Use null-check rather than containsAttribute: flash attributes can be present
        // in the model as null when a redirect does not set them, causing containsAttribute
        // to return true and preventing the empty default from being set.
        if (model.getAttribute(ATTR_ASSIGNMENT_FORM) == null) {
            model.addAttribute(ATTR_ASSIGNMENT_FORM, AssignmentForm.empty());
        }
        modelSupport.populateDetail(model, clazz, TAB_ASSIGNMENTS, user.getId(), user.getRole());
        model.addAttribute(ATTR_CLASS_ID, classId);
        model.addAttribute(ATTR_MODE, MODE_CREATE);
        return VIEW_ASSIGNMENT_FORM;
    }

    /** Handles assignment creation; redirects to list on success. */
    @PostMapping
    public String create(@PathVariable Long classId,
                         @AuthenticationPrincipal UlpUserDetails user,
                         @RequestParam(required = false) String title,
                         @RequestParam(required = false) String description,
                         @RequestParam(required = false) java.math.BigDecimal maxScore,
                         @RequestParam(required = false) String dueDate,
                         @RequestParam(required = false, defaultValue = "false") boolean allowLateSubmission,
                         RedirectAttributes ra) {
        try {
            java.time.LocalDateTime due = parseDateTime(dueDate);
            AssignmentForm form = new AssignmentForm(null, title, description, maxScore, due, allowLateSubmission);
            assignmentService.create(classId, form, user.getId(), user.getRole());
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_ASSIGNMENT_CREATED);
        } catch (EntityNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        } catch (IllegalArgumentException | IllegalStateException e) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, e.getMessage());
            ra.addFlashAttribute(ATTR_ASSIGNMENT_FORM,
                    new AssignmentForm(null, title, description, maxScore, parseDateTime(dueDate), allowLateSubmission));
            return "redirect:" + assignmentBaseUrl(classId) + "/new";
        }
        return "redirect:" + assignmentBaseUrl(classId);
    }

    // ── Edit ──────────────────────────────────────────────────────────────

    /** Renders the edit-assignment form. */
    @GetMapping("/{assignmentId}/edit")
    public String editForm(@PathVariable Long classId,
                           @PathVariable Long assignmentId,
                           @AuthenticationPrincipal UlpUserDetails user,
                           Model model) {
        ClassEntity clazz = loadClass(classId, user.getId(), user.getRole());
        try {
            // Preserve flashed form values from a prior failed POST.
            if (!model.containsAttribute(ATTR_ASSIGNMENT_FORM)) {
                model.addAttribute(ATTR_ASSIGNMENT_FORM,
                        assignmentService.getFormForEdit(classId, assignmentId, user.getId(), user.getRole()));
            }
        } catch (EntityNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        modelSupport.populateDetail(model, clazz, TAB_ASSIGNMENTS, user.getId(), user.getRole());
        model.addAttribute(ATTR_CLASS_ID, classId);
        model.addAttribute(ATTR_ASSIGNMENT, assignmentId);
        model.addAttribute(ATTR_MODE, MODE_EDIT);
        return VIEW_ASSIGNMENT_FORM;
    }

    /** Handles assignment update; redirects to list on success. */
    @PostMapping("/{assignmentId}/edit")
    public String update(@PathVariable Long classId,
                         @PathVariable Long assignmentId,
                         @AuthenticationPrincipal UlpUserDetails user,
                         @RequestParam(required = false) String title,
                         @RequestParam(required = false) String description,
                         @RequestParam(required = false) java.math.BigDecimal maxScore,
                         @RequestParam(required = false) String dueDate,
                         @RequestParam(required = false, defaultValue = "false") boolean allowLateSubmission,
                         RedirectAttributes ra) {
        try {
            java.time.LocalDateTime due = parseDateTime(dueDate);
            AssignmentForm form = new AssignmentForm(assignmentId, title, description, maxScore, due, allowLateSubmission);
            assignmentService.update(classId, assignmentId, form, user.getId(), user.getRole());
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_ASSIGNMENT_UPDATED);
        } catch (EntityNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        } catch (IllegalArgumentException | IllegalStateException e) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, e.getMessage());
            return "redirect:" + assignmentBaseUrl(classId) + "/" + assignmentId + "/edit";
        }
        return "redirect:" + assignmentBaseUrl(classId);
    }

    // ── Lifecycle: publish / close ────────────────────────────────────────

    /** Publishes a DRAFT assignment. */
    @PostMapping("/{assignmentId}/publish")
    public String publish(@PathVariable Long classId,
                          @PathVariable Long assignmentId,
                          @AuthenticationPrincipal UlpUserDetails user,
                          RedirectAttributes ra) {
        try {
            assignmentService.publish(classId, assignmentId, user.getId(), user.getRole());
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_ASSIGNMENT_PUBLISHED);
        } catch (EntityNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        } catch (IllegalStateException e) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, e.getMessage());
        }
        return "redirect:" + assignmentBaseUrl(classId);
    }

    /** Closes a PUBLISHED assignment. */
    @PostMapping("/{assignmentId}/close")
    public String close(@PathVariable Long classId,
                        @PathVariable Long assignmentId,
                        @AuthenticationPrincipal UlpUserDetails user,
                        RedirectAttributes ra) {
        try {
            assignmentService.close(classId, assignmentId, user.getId(), user.getRole());
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_ASSIGNMENT_CLOSED);
        } catch (EntityNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        } catch (IllegalStateException e) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, e.getMessage());
        }
        return "redirect:" + assignmentBaseUrl(classId);
    }

    // ── Submissions list ──────────────────────────────────────────────────

    /** Lists all submissions for an assignment. */
    @GetMapping("/{assignmentId}/submissions")
    public String submissions(@PathVariable Long classId,
                              @PathVariable Long assignmentId,
                              @AuthenticationPrincipal UlpUserDetails user,
                              Model model) {
        ClassEntity clazz = loadClass(classId, user.getId(), user.getRole());
        try {
            model.addAttribute(ATTR_SUBMISSIONS,
                    assignmentService.listSubmissions(classId, assignmentId, user.getId(), user.getRole()));
        } catch (EntityNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        modelSupport.populateDetail(model, clazz, TAB_ASSIGNMENTS, user.getId(), user.getRole());
        model.addAttribute(ATTR_CLASS_ID, classId);
        model.addAttribute(ATTR_ASSIGNMENT, assignmentId);
        return VIEW_ASSIGNMENT_SUBMISSIONS;
    }

    // ── Grade ─────────────────────────────────────────────────────────────

    /** Renders the grade form for a single submission. */
    @GetMapping("/{assignmentId}/submissions/{submissionId}/grade")
    public String gradeForm(@PathVariable Long classId,
                            @PathVariable Long assignmentId,
                            @PathVariable Long submissionId,
                            @AuthenticationPrincipal UlpUserDetails user,
                            Model model) {
        ClassEntity clazz = loadClass(classId, user.getId(), user.getRole());
        try {
            model.addAttribute(ATTR_SUBMISSION,
                    assignmentService.getSubmissionDetail(classId, assignmentId, submissionId, user.getId(), user.getRole()));
        } catch (EntityNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        modelSupport.populateDetail(model, clazz, TAB_ASSIGNMENTS, user.getId(), user.getRole());
        model.addAttribute(ATTR_CLASS_ID, classId);
        model.addAttribute(ATTR_ASSIGNMENT, assignmentId);
        // Preserve flashed grade form if validation failed.
        if (!model.containsAttribute(ATTR_GRADE_FORM)) {
            model.addAttribute(ATTR_GRADE_FORM, new GradeForm(null, null));
        }
        return VIEW_ASSIGNMENT_GRADE;
    }

    /** Handles grade submission; redirects to submissions list on success. */
    @PostMapping("/{assignmentId}/submissions/{submissionId}/grade")
    public String grade(@PathVariable Long classId,
                        @PathVariable Long assignmentId,
                        @PathVariable Long submissionId,
                        @AuthenticationPrincipal UlpUserDetails user,
                        @RequestParam(required = false) java.math.BigDecimal score,
                        @RequestParam(required = false) String feedback,
                        RedirectAttributes ra) {
        try {
            GradeForm form = new GradeForm(score, feedback);
            assignmentService.grade(classId, assignmentId, submissionId, form, user.getId(), user.getRole());
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_GRADE_SUCCESS);
        } catch (EntityNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, e.getMessage());
            return "redirect:" + assignmentBaseUrl(classId)
                    + "/" + assignmentId + "/submissions/" + submissionId + "/grade";
        }
        return "redirect:" + assignmentBaseUrl(classId) + "/" + assignmentId + "/submissions";
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /** Builds the base URL for this class's assignment routes. */
    private static String assignmentBaseUrl(Long classId) {
        return BASE_LECTURER + PATH_CLASSES + "/" + classId + PATH_ASSIGNMENTS;
    }

    /** Parses an HTML datetime-local string to LocalDateTime; returns null when blank. */
    private static java.time.LocalDateTime parseDateTime(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return java.time.LocalDateTime.parse(value);
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }
}
