package com.ulp.features.classes.controller;

import com.ulp.entities.ClassEntity;
import com.ulp.entities.ClassInviteCode;
import com.ulp.features.classes.controller.support.ClassDetailModelSupport;
import com.ulp.features.classes.dto.ClassesDtos.ClassForm;
import com.ulp.features.classes.service.ClassMembersService;
import com.ulp.features.classes.service.ClassesService;
import com.ulp.features.classes.service.JoinClassService;
import com.ulp.features.classes.service.invites.InviteCodeService;
import com.ulp.features.classes.service.invites.InviteCodeValidationException;
import com.ulp.features.tests.service.LecturerExamService;
import com.ulp.security.Roles;
import com.ulp.security.UlpUserDetails;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
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
import static com.ulp.features.classes.controller.support.ClassDetailModelSupport.classUrl;
import static com.ulp.features.classes.controller.support.ClassDetailModelSupport.labelFor;

/**
 * Controller for the class detail screens (sidebar tabs introduced in Sprint 2 phase 2).
 * Split out of {@link ClassesController} during the file-size refactor so each controller
 * stays focused on a single responsibility.
 *
 * <p>Exposed endpoints:
 * <ul>
 *   <li>{@code GET  /lecturer/classes/{id}/board}    — announcement tab</li>
 *   <li>{@code GET  /lecturer/classes/{id}/members}  — member list tab</li>
 *   <li>{@code GET  /lecturer/classes/{id}/settings} — settings (info / invite sub-tabs)</li>
 *   <li>{@code GET  /lecturer/classes/{id}/schedule|roles|groups|...} — placeholder tabs</li>
 *   <li>{@code POST /lecturer/classes/{id}/invite/regenerate} — rotate active CODE/LINK</li>
 * </ul>
 *
 * <p>Authorization: class-level {@code @PreAuthorize} blocks STUDENT and anonymous
 * users. Ownership checks are enforced at the service layer using the
 * {@code (userId, role)} pair from {@link UlpUserDetails}.
 */
@Controller
@RequestMapping(BASE_LECTURER)
@PreAuthorize(Roles.PREAUTH_LECTURER_OR_ABOVE)
public class ClassDetailController {

    private final ClassesService classesService;
    private final ClassMembersService classMembersService;
    private final InviteCodeService inviteCodeService;
    private final ClassDetailModelSupport detailSupport;
    private final LecturerExamService examService;
    private final JoinClassService joinClassService;

    public ClassDetailController(ClassesService classesService,
                                 ClassMembersService classMembersService,
                                 InviteCodeService inviteCodeService,
                                 ClassDetailModelSupport detailSupport,
                                 LecturerExamService examService,
                                 JoinClassService joinClassService) {
        this.classesService = classesService;
        this.classMembersService = classMembersService;
        this.inviteCodeService = inviteCodeService;
        this.detailSupport = detailSupport;
        this.examService = examService;
        this.joinClassService = joinClassService;
    }

    /** Renders the class board (announcement) tab. */
    @GetMapping("/classes/{id}/board")
    public String detailBoard(@PathVariable Long id,
                              @AuthenticationPrincipal UlpUserDetails user,
                              Model model) {
        ClassEntity clazz = classesService.getViewable(id, user.getId(), user.getRole());
        detailSupport.populateDetail(model, clazz, TAB_BOARD, user.getId(), user.getRole());
        return VIEW_CLASS_DETAIL_BOARD;
    }

    /** Renders the class members tab with ACTIVE members and PENDING requests. */
    @GetMapping("/classes/{id}/members")
    public String detailMembers(@PathVariable Long id,
                                @AuthenticationPrincipal UlpUserDetails user,
                                Model model) {
        ClassMembersService.ClassMembersView view =
                classMembersService.listForClass(id, user.getId(), user.getRole());
        detailSupport.populateDetail(model, view.clazz(), TAB_MEMBERS,
                user.getId(), user.getRole());
        model.addAttribute(ATTR_MEMBERS, view.members());
        model.addAttribute(ATTR_MEMBER_TOTAL, view.total());
        model.addAttribute(ATTR_PENDING_MEMBERS, view.pendingMembers());
        model.addAttribute(ATTR_PENDING_TOTAL, view.pendingTotal());
        return VIEW_CLASS_DETAIL_MEMBERS;
    }

    /** Owner approves a PENDING join request. */
    @PostMapping("/classes/{id}/members/{userId}/approve")
    public String approveMember(@PathVariable Long id,
                                @PathVariable Long userId,
                                @AuthenticationPrincipal UlpUserDetails user,
                                RedirectAttributes ra) {
        try {
            joinClassService.approve(id, userId, user.getId(), user.getRole());
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_JOIN_APPROVED);
        } catch (EntityNotFoundException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
        } catch (AccessDeniedException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage());
        } catch (IllegalStateException ex) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, MSG_JOIN_APPROVE_FAILED + ex.getMessage());
        } catch (InviteCodeValidationException ex) {
            // Capacity full reuses CLASS_FULL message from the validator.
            ra.addFlashAttribute(ATTR_FLASH_ERROR, MSG_JOIN_CLASS_FULL);
        }
        return "redirect:" + classUrl(id) + "/" + TAB_MEMBERS;
    }

    /** Owner rejects a PENDING join request. */
    @PostMapping("/classes/{id}/members/{userId}/reject")
    public String rejectMember(@PathVariable Long id,
                               @PathVariable Long userId,
                               @AuthenticationPrincipal UlpUserDetails user,
                               RedirectAttributes ra) {
        try {
            joinClassService.reject(id, userId, user.getId(), user.getRole());
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_JOIN_REJECTED);
        } catch (EntityNotFoundException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
        } catch (AccessDeniedException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage());
        } catch (IllegalStateException ex) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, MSG_JOIN_REJECT_FAILED + ex.getMessage());
        }
        return "redirect:" + classUrl(id) + "/" + TAB_MEMBERS;
    }

    /** Renders the class exams tab — the exams belonging to this class. */
    @GetMapping("/classes/{id}/tests")
    public String detailTests(@PathVariable Long id,
                              @RequestParam(name = "page", defaultValue = "0") int page,
                              @AuthenticationPrincipal UlpUserDetails user,
                              Model model) {
        ClassEntity clazz = classesService.getViewable(id, user.getId(), user.getRole());
        detailSupport.populateDetail(model, clazz, TAB_TESTS, user.getId(), user.getRole());
        model.addAttribute(ATTR_EXAMS_PAGE, examService.listForClass(id, page));
        return VIEW_CLASS_DETAIL_TESTS;
    }

    /**
     * Rotates the active invite token of the requested type for the
     * given class. Returns a 302 redirect to the Settings tab (where
     * the invite panel now lives) with a flash success toast. Service-
     * level authorization rejects non-owning Lecturers with a 403; an
     * invalid {@code type} parameter returns 400 via
     * {@link ResponseStatusException}.
     */
    @PostMapping("/classes/{id}/invite/regenerate")
    public String regenerateInvite(@PathVariable Long id,
                                   @RequestParam("type") String type,
                                   @AuthenticationPrincipal UlpUserDetails user,
                                   RedirectAttributes ra) {
        // Whitelist: only CODE or LINK invite types are valid.
        if (!ClassInviteCode.TYPE_CODE.equals(type) && !ClassInviteCode.TYPE_LINK.equals(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    MSG_INVALID_INVITE_TYPE);
        }
        inviteCodeService.regenerateActive(id, type, user.getId(), user.getRole());
        ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_INVITE_REGENERATED);
        return "redirect:" + classUrl(id) + "/" + TAB_SETTINGS;
    }

    /**
     * Renders a placeholder view for class detail tabs not yet implemented (Sprint 3–5).
     * Handles: {@code /schedule}, {@code /roles}, {@code /groups}, {@code /assignments},
     * {@code /scores}, {@code /materials}.
     *
     * <p>Note: {@code /lessons} and {@code /assignments} are intentionally NOT mapped here —
     * they are owned by {@code SectionsController} and {@code LecturerAssignmentController}
     * respectively. Adding both mappings would raise
     * {@code IllegalStateException: Ambiguous mapping} at startup.
     */
    @GetMapping({"/classes/{id}/schedule", "/classes/{id}/roles",
                "/classes/{id}/groups",
                "/classes/{id}/scores",
                "/classes/{id}/materials"})
    public String detailPlaceholder(@PathVariable Long id,
                                    @AuthenticationPrincipal UlpUserDetails user,
                                    jakarta.servlet.http.HttpServletRequest request,
                                    Model model) {
        ClassEntity clazz = classesService.getViewable(id, user.getId(), user.getRole());
        // Extract the last URL segment as the active tab key (e.g. "schedule").
        String path = request.getRequestURI();
        String tab = path.substring(path.lastIndexOf('/') + 1);
        detailSupport.populateDetail(model, clazz, tab, user.getId(), user.getRole());
        model.addAttribute(ATTR_PLACEHOLDER_TAB, tab);
        model.addAttribute(ATTR_PLACEHOLDER_LABEL, labelFor(tab));
        return VIEW_CLASS_DETAIL_PLACEHOLDER;
    }

    /**
     * Renders the class settings tab, reusing the edit-class form.
     * Only the class owner (or HEAD/ADMIN) may access this endpoint.
     *
     * <p>The settings page hosts two sub-tabs that switch via the
     * {@code ?tab=info|invite} query parameter. Unknown values fall back
     * to {@code info} so that arbitrary URLs render the form rather than
     * raising a 4xx error.
     */
    @GetMapping("/classes/{id}/settings")
    public String detailSettings(@PathVariable Long id,
                                 @RequestParam(defaultValue = SUBTAB_INFO) String tab,
                                 @AuthenticationPrincipal UlpUserDetails user,
                                 Model model) {
        ClassEntity entity = classesService.getEditable(id, user.getId(), user.getRole());
        // Preserve flashed form values from a prior failed POST.
        if (!model.containsAttribute(ATTR_FORM)) {
            model.addAttribute(ATTR_FORM, ClassForm.fromEntity(entity));
        }
        detailSupport.populateDetail(model, entity, TAB_SETTINGS, user.getId(), user.getRole());

        // Whitelist sub-tab to {info, invite}; anything else falls back to "info".
        String activeDetailTab = SUBTAB_INVITE.equals(tab) ? SUBTAB_INVITE : SUBTAB_INFO;
        model.addAttribute(ATTR_ACTIVE_DETAIL_TAB, activeDetailTab);

        model.addAttribute(ATTR_MODE, MODE_EDIT);
        model.addAttribute(ATTR_FORM_ACTION, classUrl(id));
        model.addAttribute(ATTR_CLASS_ID, id);
        return VIEW_CLASS_DETAIL_SETTINGS;
    }
}
