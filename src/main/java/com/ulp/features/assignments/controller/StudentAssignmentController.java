package com.ulp.features.assignments.controller;

import com.ulp.entities.ClassEntity;
import com.ulp.features.assignments.dto.AssignmentDtos.SubmitForm;
import com.ulp.features.assignments.service.StudentAssignmentService;
import com.ulp.features.classes.controller.support.ClassDetailModelSupport;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.security.UlpUserDetails;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.http.HttpStatus;
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
 * Student-facing controller for the assignments feature (Sprint 6, #70).
 *
 * <p>All endpoints are scoped to {@code /classes/{classId}/assignments} under
 * the student's authenticated context. Enrollment is enforced in
 * {@link StudentAssignmentService} — non-enrolled students receive 404 (no existence
 * leak). Flash messages drain to UlpToast via the page-level JS.
 *
 * <p>PRG (Post-Redirect-Get) pattern is used for all mutations.
 */
@Controller
@RequestMapping(PATH_CLASSES + "/{classId}" + PATH_ASSIGNMENTS)
public class StudentAssignmentController {

    private final StudentAssignmentService assignmentService;
    private final ClassRepository classRepository;
    private final ClassDetailModelSupport modelSupport;

    public StudentAssignmentController(StudentAssignmentService assignmentService,
                                       ClassRepository classRepository,
                                       ClassDetailModelSupport modelSupport) {
        this.assignmentService = assignmentService;
        this.classRepository = classRepository;
        this.modelSupport = modelSupport;
    }

    /**
     * Loads the ClassEntity for sidebar rendering; throws 404 if the class
     * does not exist (soft-deleted rows are excluded by @SQLRestriction).
     */
    private ClassEntity loadClass(Long classId) {
        return classRepository.findById(classId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    // ── List ──────────────────────────────────────────────────────────────

    /** Lists all published/closed assignments for the class (student view). */
    @GetMapping
    public String list(@PathVariable Long classId,
                       @AuthenticationPrincipal UlpUserDetails user,
                       Model model) {
        ClassEntity clazz = loadClass(classId);
        try {
            model.addAttribute(ATTR_ASSIGNMENTS,
                    assignmentService.listPublishedForStudent(classId, user.getId()));
        } catch (EntityNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        modelSupport.populateDetail(model, clazz, TAB_ASSIGNMENTS, user.getId(), user.getRole());
        return VIEW_STUDENT_ASSIGNMENT_LIST;
    }

    // ── View detail ───────────────────────────────────────────────────────

    /** Shows assignment detail and existing submission for the student. */
    @GetMapping("/{assignmentId}")
    public String detail(@PathVariable Long classId,
                         @PathVariable Long assignmentId,
                         @AuthenticationPrincipal UlpUserDetails user,
                         Model model) {
        ClassEntity clazz = loadClass(classId);
        try {
            model.addAttribute(ATTR_ASSIGNMENT,
                    assignmentService.getForStudent(classId, assignmentId, user.getId()));
        } catch (EntityNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        modelSupport.populateDetail(model, clazz, TAB_ASSIGNMENTS, user.getId(), user.getRole());
        return VIEW_STUDENT_ASSIGNMENT_DETAIL;
    }

    // ── Submit ────────────────────────────────────────────────────────────

    /** Handles student submission; redirects to detail page on success. */
    @PostMapping("/{assignmentId}/submit")
    public String submit(@PathVariable Long classId,
                         @PathVariable Long assignmentId,
                         @AuthenticationPrincipal UlpUserDetails user,
                         @RequestParam(required = false) String content,
                         RedirectAttributes ra) {
        try {
            assignmentService.submit(classId, assignmentId, new SubmitForm(content), user.getId());
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_SUBMIT_SUCCESS);
        } catch (EntityNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        } catch (IllegalStateException | IllegalArgumentException e) {
            // Both user-facing rejection types (not-published, late-not-allowed, after-graded)
            // must surface as a flash error toast, not a 500.
            ra.addFlashAttribute(ATTR_FLASH_ERROR, e.getMessage());
        }
        return "redirect:" + studentAssignmentUrl(classId, assignmentId);
    }

    // ── Feedback ──────────────────────────────────────────────────────────

    /** Shows the graded feedback page for the student. */
    @GetMapping("/{assignmentId}/feedback")
    public String feedback(@PathVariable Long classId,
                           @PathVariable Long assignmentId,
                           @AuthenticationPrincipal UlpUserDetails user,
                           Model model) {
        ClassEntity clazz = loadClass(classId);
        try {
            model.addAttribute(ATTR_ASSIGNMENT,
                    assignmentService.getForStudent(classId, assignmentId, user.getId()));
        } catch (EntityNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        modelSupport.populateDetail(model, clazz, TAB_ASSIGNMENTS, user.getId(), user.getRole());
        return VIEW_STUDENT_ASSIGNMENT_FEEDBACK;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /** Builds the canonical URL for a single student assignment. */
    private static String studentAssignmentUrl(Long classId, Long assignmentId) {
        return PATH_CLASSES + "/" + classId + PATH_ASSIGNMENTS + "/" + assignmentId;
    }
}

