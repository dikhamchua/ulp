package com.ulp.features.student.controller;

import com.ulp.security.UlpUserDetails;
import com.ulp.entities.ClassEntity;
import com.ulp.features.classes.service.invites.InviteCodeValidationException;
import com.ulp.features.classes.service.JoinClassService;
import com.ulp.features.classes.service.JoinClassService.AlreadyJoined;
import com.ulp.features.classes.service.JoinClassService.JoinResult;
import com.ulp.features.classes.service.JoinClassService.PendingRequested;
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
 * <p>Authentication is required; any authenticated user can use these endpoints
 * so lecturers can exercise the join flow from their own account.
 */
@Controller
@RequestMapping("/my")
@PreAuthorize("isAuthenticated()")
public class StudentClassesController {

    private static final String VIEW_MY_CLASSES = "student/my-classes";
    private static final String VIEW_JOIN_CLASS = "student/join-class";
    private static final String REDIRECT_MY_CLASSES = "redirect:/my/classes";
    private static final String ATTR_ROWS = "rows";
    private static final String MSG_LEFT_CLASS = "Đã rời lớp ";
    private static final String MSG_CANNOT_LEAVE_DONE = "Không thể rời lớp đã hoàn thành";

    private final StudentClassesService studentClassesService;
    private final JoinClassService joinClassService;

    public StudentClassesController(StudentClassesService studentClassesService,
                                    JoinClassService joinClassService) {
        this.studentClassesService = studentClassesService;
        this.joinClassService = joinClassService;
    }

    /** Lists ACTIVE enrollments and PENDING join requests. */
    @GetMapping("/classes")
    public String list(@AuthenticationPrincipal UlpUserDetails user, Model model) {
        List<EnrolledClassRow> rows = studentClassesService.listEnrolledClasses(user.getId());
        List<EnrolledClassRow> pending = studentClassesService.listPendingClasses(user.getId());
        model.addAttribute(ATTR_ROWS, rows);
        model.addAttribute(ATTR_PENDING_ROWS, pending);
        return VIEW_MY_CLASSES;
    }

    @GetMapping("/classes/join")
    public String joinForm(Model model) {
        if (!model.containsAttribute(ATTR_FORM)) {
            model.addAttribute(ATTR_FORM, JoinForm.empty());
        }
        return VIEW_JOIN_CLASS;
    }

    /**
     * Submits the join form. CODE/LINK creates a PENDING request (not ACTIVE).
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

    private String redirectAfterJoin(JoinResult outcome, RedirectAttributes ra) {
        if (outcome instanceof Success s) {
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_JOINED_CLASS + s.clazz().getName());
        } else if (outcome instanceof AlreadyJoined a) {
            ra.addFlashAttribute(ATTR_FLASH_INFO, MSG_ALREADY_IN_CLASS + a.clazz().getName());
        } else if (outcome instanceof PendingRequested p) {
            if (p.alreadyPending()) {
                ra.addFlashAttribute(ATTR_FLASH_INFO,
                        MSG_JOIN_ALREADY_PENDING + p.clazz().getName() + MSG_JOIN_ALREADY_PENDING_SUFFIX);
            } else {
                ra.addFlashAttribute(ATTR_FLASH_INFO,
                        MSG_JOIN_REQUEST_SENT + p.clazz().getName() + MSG_JOIN_REQUEST_PENDING_SUFFIX);
            }
        }
        return REDIRECT_MY_CLASSES;
    }
}
