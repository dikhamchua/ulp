package com.ulp.features.student.controller;

import com.ulp.security.UlpUserDetails;
import com.ulp.entities.ClassEntity;
import com.ulp.features.classes.service.invites.InviteCodeValidationException;
import com.ulp.features.classes.service.JoinClassService;
import com.ulp.features.classes.service.JoinClassService.AlreadyJoined;
import com.ulp.features.classes.service.JoinClassService.JoinResult;
import com.ulp.features.classes.service.JoinClassService.Success;
import com.ulp.features.student.dto.StudentClassesDtos.EnrolledClassRow;
import com.ulp.features.student.dto.StudentClassesDtos.JoinForm;
import com.ulp.features.student.service.StudentClassesService;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

import static com.ulp.common.IConstant.*;

/**
 * Student-facing controller for the {@code /my/classes} surface.
 *
 * <p>Authentication is required (gated by {@code SecurityConfig});
 * any authenticated user — STUDENT, LECTURER, HEAD, or ADMIN — can
 * use these endpoints. The intent is that lecturers can test the
 * join flow from their own account without role gymnastics.
 */
@Controller
@RequestMapping("/my")
@PreAuthorize("isAuthenticated()")
public class StudentClassesController {

    // ── View names ────────────────────────────────────────────────
    private static final String VIEW_MY_CLASSES = "student/my-classes";
    private static final String VIEW_JOIN_CLASS = "student/join-class";

    // ── Paths ─────────────────────────────────────────────────────
    private static final String REDIRECT_MY_CLASSES = "redirect:/my/classes";

    // ── Local model attribute keys ────────────────────────────────
    private static final String ATTR_ROWS = "rows";

    // ── Flash messages ────────────────────────────────────────────
    private static final String MSG_LEFT_CLASS          = "Đã rời lớp ";
    private static final String MSG_CANNOT_LEAVE_DONE   = "Không thể rời lớp đã hoàn thành";

    private final StudentClassesService studentClassesService;
    private final JoinClassService joinClassService;

    public StudentClassesController(StudentClassesService studentClassesService,
                                    JoinClassService joinClassService) {
        this.studentClassesService = studentClassesService;
        this.joinClassService = joinClassService;
    }

    /** Renders the list of the caller's ACTIVE enrolled classes. */
    @GetMapping("/classes")
    public String list(@AuthenticationPrincipal UlpUserDetails user, Model model) {
        List<EnrolledClassRow> rows = studentClassesService.listEnrolledClasses(user.getId());
        model.addAttribute(ATTR_ROWS, rows);
        return VIEW_MY_CLASSES;
    }

    /** Renders the join form. */
    @GetMapping("/classes/join")
    public String joinForm(Model model) {
        if (!model.containsAttribute(ATTR_FORM)) {
            model.addAttribute(ATTR_FORM, JoinForm.empty());
        }
        return VIEW_JOIN_CLASS;
    }

    /**
     * Submits the join form. On success redirects to
     * {@code /my/classes} with a success/info toast. Validation /
     * business errors are re-rendered with inline field messages
     * sourced directly from {@link InviteCodeValidationException#getMessage()}.
     */
    @PostMapping("/classes/join")
    public String join(@Valid @ModelAttribute("form") JoinForm form,
                       BindingResult result,
                       @AuthenticationPrincipal UlpUserDetails user,
                       Model model,
                       RedirectAttributes ra) {
        if (result.hasErrors()) {
            return VIEW_JOIN_CLASS;
        }
        try {
            JoinResult outcome = joinClassService.join(form.code(), user.getId());
            return redirectAfterJoin(outcome, ra);
        } catch (InviteCodeValidationException ex) {
            result.rejectValue("code", "invite.invalid", ex.getMessage());
            return VIEW_JOIN_CLASS;
        }
    }

    /**
     * Leaves a class. Refusing to operate on classes the caller is
     * not actively enrolled in (404). COMPLETED classes raise 409 +
     * an error toast.
     */
    @PostMapping("/classes/{id}/leave")
    public String leave(@PathVariable Long id,
                        @AuthenticationPrincipal UlpUserDetails user,
                        RedirectAttributes ra) {
        try {
            ClassEntity clazz = joinClassService.leave(id, user.getId());
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_LEFT_CLASS + clazz.getName());
            return REDIRECT_MY_CLASSES;
        } catch (EntityNotFoundException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
        } catch (IllegalStateException ex) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, MSG_CANNOT_LEAVE_DONE);
            return REDIRECT_MY_CLASSES;
        }
    }

    /**
     * Maps a {@link JoinResult} to its redirect, attaching the right flash key:
     * {@code flashSuccess} for fresh joins, {@code flashInfo} for already-enrolled.
     */
    private String redirectAfterJoin(JoinResult outcome, RedirectAttributes ra) {
        if (outcome instanceof Success s) {
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_JOINED_CLASS + s.clazz().getName());
        } else if (outcome instanceof AlreadyJoined a) {
            ra.addFlashAttribute(ATTR_FLASH_INFO, MSG_ALREADY_IN_CLASS + a.clazz().getName());
        }
        return REDIRECT_MY_CLASSES;
    }
}
