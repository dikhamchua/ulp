package com.ulp.features.classes.controller;

import com.ulp.security.Role;
import com.ulp.security.Roles;
import com.ulp.security.UlpUserDetails;
import com.ulp.features.classes.dto.ClassesDtos.ClassForm;
import com.ulp.features.classes.dto.ClassesDtos.ClassRow;
import com.ulp.features.classes.dto.ClassesDtos.InviteCodeView;
import com.ulp.features.classes.dto.ClassesDtos.InviteLinkView;
import com.ulp.entities.ClassEntity;
import com.ulp.entities.ClassInviteCode;
import com.ulp.features.classes.service.ClassMembersService;
import com.ulp.features.classes.service.ClassesService;
import com.ulp.features.classes.service.InviteCodeService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import static com.ulp.common.IConstant.*;

/**
 * Controller for the lecturer class management screens.
 * Only LECTURER, HEAD, and ADMIN roles may access these endpoints (see {@link Roles}).
 *
 * <p>Exposed endpoints:
 * <ul>
 *   <li>{@code GET  /lecturer/classes}             — list all classes for the current user (paginated)</li>
 *   <li>{@code GET  /lecturer/classes/new}         — render the create-class form</li>
 *   <li>{@code POST /lecturer/classes}             — submit the create-class form</li>
 *   <li>{@code GET  /lecturer/classes/{id}/edit}   — render the edit-class form</li>
 *   <li>{@code POST /lecturer/classes/{id}}        — submit the edit-class form</li>
 *   <li>{@code POST /lecturer/classes/{id}/delete} — soft-delete after confirm modal</li>
 * </ul>
 *
 * <p>Validation: {@code @Valid ClassForm + BindingResult}. Errors are rendered
 * inline beneath each field via {@code th:errors}; field input is preserved on re-render.
 *
 * <p>Authorization: class-level {@code @PreAuthorize} blocks STUDENT and anonymous
 * users. Owner check (a LECTURER may only edit their own class) is enforced at the
 * service layer based on the {@code (userId, role)} pair sourced from
 * {@link UlpUserDetails}. HTTP 404 and 403 are mapped via {@code GlobalExceptionHandler}.
 */
@Controller
@RequestMapping(BASE_LECTURER)
@PreAuthorize(Roles.PREAUTH_LECTURER_OR_ABOVE)
public class ClassesController {

    private final ClassesService classesService;
    private final ClassMembersService classMembersService;
    private final InviteCodeService inviteCodeService;

    public ClassesController(ClassesService classesService,
                             ClassMembersService classMembersService,
                             InviteCodeService inviteCodeService) {
        this.classesService = classesService;
        this.classMembersService = classMembersService;
        this.inviteCodeService = inviteCodeService;
    }

    /**
     * Lists all classes owned by or accessible to the authenticated user.
     *
     * <p>Pagination defaults: 20 rows per page, sorted by {@code createdAt DESC}.
     * Clients can override via {@code ?page=N&size=M&sort=...} query parameters.
     *
     * @param user     the authenticated user (id + role)
     * @param pageable resolved by Spring Data Web from query params with defaults
     * @param model    the Spring MVC model
     * @return the {@code classes/manage} view
     */
    @GetMapping("/classes")
    public String list(@AuthenticationPrincipal UlpUserDetails user,
                       @PageableDefault(size = DEFAULT_PAGE_SIZE, sort = "createdAt",
                               direction = Sort.Direction.DESC) Pageable pageable,
                       Model model) {
        Page<ClassRow> page = classesService.listForUser(user.getId(), user.getRole(), pageable);
        // Keep the existing template loop driven by ${classes} (a List). The Page
        // object is exposed separately as ${classesPage} for the pagination block.
        model.addAttribute(ATTR_CLASSES, page.getContent());
        model.addAttribute(ATTR_CLASSES_PAGE, page);
        return VIEW_CLASS_MANAGE;
    }

    /**
     * Renders the create-class form.
     * Preserves a previously bound {@code form} flash attribute on validation redirect.
     *
     * @param model the Spring MVC model
     * @return the {@code classes/form} view in create mode
     */
    @GetMapping("/classes/new")
    public String createForm(Model model) {
        // Preserve flashed form values from a prior failed POST.
        if (!model.containsAttribute(ATTR_FORM)) {
            model.addAttribute(ATTR_FORM, ClassForm.empty());
        }
        model.addAttribute(ATTR_MODE, MODE_CREATE);
        model.addAttribute(ATTR_FORM_ACTION, URL_CLASSES_LIST);
        return VIEW_CLASS_FORM;
    }

    /**
     * Handles create-class form submission.
     * Re-renders the form with inline errors on validation failure;
     * redirects to the class list with a success flash message on success.
     */
    @PostMapping("/classes")
    public String create(@Valid @ModelAttribute("form") ClassForm form,
                         BindingResult result,
                         @AuthenticationPrincipal UlpUserDetails user,
                         Model model,
                         RedirectAttributes ra) {
        // Validation failed — re-render with bound values + field errors.
        if (result.hasErrors()) {
            rebindDateRangeError(result);
            model.addAttribute(ATTR_MODE, MODE_CREATE);
            model.addAttribute(ATTR_FORM_ACTION, URL_CLASSES_LIST);
            return VIEW_CLASS_FORM;
        }
        ClassEntity saved = classesService.create(form, user.getId());
        ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_CLASS_CREATED + saved.getCode());
        return "redirect:" + URL_CLASSES_LIST;
    }

    /**
     * Renders the edit-class form for an existing class.
     * Only the class owner (or HEAD/ADMIN) may access this endpoint; the service
     * layer enforces the ownership check and throws if unauthorized.
     */
    @GetMapping("/classes/{id}/edit")
    public String editForm(@PathVariable Long id,
                           @AuthenticationPrincipal UlpUserDetails user,
                           Model model) {
        ClassEntity entity = classesService.getEditable(id, user.getId(), user.getRole());
        // Preserve flashed form values from a prior failed POST.
        if (!model.containsAttribute(ATTR_FORM)) {
            model.addAttribute(ATTR_FORM, ClassForm.fromEntity(entity));
        }
        model.addAttribute(ATTR_MODE, MODE_EDIT);
        model.addAttribute(ATTR_FORM_ACTION, classUrl(id));
        model.addAttribute(ATTR_CLASS_ID, id);
        return VIEW_CLASS_FORM;
    }

    /**
     * Handles edit-class form submission.
     * Re-renders the form with inline errors on validation failure;
     * redirects to the class list with a success flash message on success.
     */
    @PostMapping("/classes/{id}")
    public String update(@PathVariable Long id,
                         @Valid @ModelAttribute("form") ClassForm form,
                         BindingResult result,
                         @AuthenticationPrincipal UlpUserDetails user,
                         Model model,
                         RedirectAttributes ra) {
        // Validation failed — re-render with bound values + field errors.
        if (result.hasErrors()) {
            rebindDateRangeError(result);
            model.addAttribute(ATTR_MODE, MODE_EDIT);
            model.addAttribute(ATTR_FORM_ACTION, classUrl(id));
            model.addAttribute(ATTR_CLASS_ID, id);
            return VIEW_CLASS_FORM;
        }
        classesService.update(id, form, user.getId(), user.getRole());
        ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_CLASS_UPDATED);
        return "redirect:" + URL_CLASSES_LIST;
    }

    /**
     * Soft-deletes a class after the user confirms the action via the confirm modal.
     */
    @PostMapping("/classes/{id}/delete")
    public String delete(@PathVariable Long id,
                         @AuthenticationPrincipal UlpUserDetails user,
                         RedirectAttributes ra) {
        classesService.softDelete(id, user.getId(), user.getRole());
        ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_CLASS_DELETED);
        return "redirect:" + URL_CLASSES_LIST;
    }

    // ───────── Class detail page — sidebar tabs (Sprint 2 phase 2) ─────────

    /**
     * Redirects the root class-detail URL to the default {@code /board} tab.
     */
    @GetMapping("/classes/{id}")
    public String detailRoot(@PathVariable Long id) {
        return "redirect:" + classUrl(id) + "/" + TAB_BOARD;
    }

    /**
     * Renders the class board (announcement) tab.
     */
    @GetMapping("/classes/{id}/board")
    public String detailBoard(@PathVariable Long id,
                              @AuthenticationPrincipal UlpUserDetails user,
                              Model model) {
        ClassEntity clazz = classesService.getViewable(id, user.getId(), user.getRole());
        populateDetailModel(model, clazz, TAB_BOARD, user.getId(), user.getRole());
        return VIEW_CLASS_DETAIL_BOARD;
    }

    /**
     * Renders the class members tab with the full member list.
     */
    @GetMapping("/classes/{id}/members")
    public String detailMembers(@PathVariable Long id,
                                @AuthenticationPrincipal UlpUserDetails user,
                                Model model) {
        ClassMembersService.ClassMembersView view =
                classMembersService.listForClass(id, user.getId(), user.getRole());
        populateDetailModel(model, view.clazz(), TAB_MEMBERS, user.getId(), user.getRole());
        model.addAttribute(ATTR_MEMBERS, view.members());
        model.addAttribute(ATTR_MEMBER_TOTAL, view.total());
        return VIEW_CLASS_DETAIL_MEMBERS;
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
     * <p>Note: {@code /lessons} is intentionally NOT mapped here — it is owned
     * by {@code SectionsController} ({@code /lecturer/classes/{classId}/lessons})
     * starting with ULP-4.0a. Adding both mappings would raise
     * {@code IllegalStateException: Ambiguous mapping} at startup.
     */
    @GetMapping({"/classes/{id}/schedule", "/classes/{id}/roles",
                "/classes/{id}/groups", "/classes/{id}/assignments",
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
        populateDetailModel(model, clazz, tab, user.getId(), user.getRole());
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
        populateDetailModel(model, entity, TAB_SETTINGS, user.getId(), user.getRole());

        // Whitelist sub-tab to {info, invite}; anything else falls back to "info".
        String activeDetailTab = SUBTAB_INVITE.equals(tab) ? SUBTAB_INVITE : SUBTAB_INFO;
        model.addAttribute(ATTR_ACTIVE_DETAIL_TAB, activeDetailTab);

        model.addAttribute(ATTR_MODE, MODE_EDIT);
        model.addAttribute(ATTR_FORM_ACTION, classUrl(id));
        model.addAttribute(ATTR_CLASS_ID, id);
        return VIEW_CLASS_DETAIL_SETTINGS;
    }

    /**
     * Populates common model attributes required by the class-detail layout.
     *
     * <p>Beyond the basic {@code clazz} and {@code activeTab} pair consumed by
     * the sidebar fragment, this helper also injects the active invite tokens
     * ({@code activeCode}, {@code activeLink}) and the {@code canRegenerate}
     * flag so that EVERY detail tab — including the sidebar share-box and the
     * Settings tab invite panel — can render real invite data sourced from
     * {@code class_invite_codes} rather than the immutable {@code classes.code}.
     */
    private void populateDetailModel(Model model, ClassEntity clazz, String activeTab,
                                     Long userId, Role role) {
        model.addAttribute(ATTR_CLAZZ, clazz);
        model.addAttribute(ATTR_ACTIVE_TAB, activeTab);

        // Active invite tokens may be absent for legacy classes — render null.
        InviteCodeView activeCode = inviteCodeService.findActiveCode(clazz.getId())
                .map(ic -> new InviteCodeView(ic.getCode(), ic.getId(), ic.getUseCount()))
                .orElse(null);
        InviteLinkView activeLink = inviteCodeService.findActiveLink(clazz.getId())
                .map(ic -> new InviteLinkView(ic.getCode(),
                        inviteCodeService.buildLinkUrl(ic),
                        ic.getId(),
                        ic.getUseCount()))
                .orElse(null);
        boolean canRegenerate = classesService.isEditableBy(clazz, userId, role);

        model.addAttribute(ATTR_ACTIVE_CODE, activeCode);
        model.addAttribute(ATTR_ACTIVE_LINK, activeLink);
        model.addAttribute(ATTR_CAN_REGENERATE, canRegenerate);
    }

    /** Builds the canonical URL for a single class — used by redirects and form actions. */
    private static String classUrl(Long id) {
        return URL_CLASSES_LIST + "/" + id;
    }

    /** Maps a tab key to its Vietnamese sidebar label; unknown keys pass through. */
    private static String labelFor(String tab) {
        return switch (tab) {
            case TAB_BOARD       -> "Bảng tin";
            case TAB_SCHEDULE    -> "Lịch học";
            case TAB_MEMBERS     -> "Thành viên";
            case TAB_ROLES       -> "Vai trò lớp";
            case TAB_GROUPS      -> "Nhóm học tập";
            case TAB_ASSIGNMENTS -> "Bài tập";
            case TAB_SCORES      -> "Bảng điểm";
            case TAB_LESSONS     -> "Bài giảng";
            case TAB_MATERIALS   -> "Tài liệu";
            case TAB_SETTINGS    -> "Cài đặt lớp học";
            // Fallback so future tabs render their raw key until labelled.
            default -> tab;
        };
    }

    /**
     * Rebinds a cross-field date-range validation error to the {@code endDate} field.
     *
     * <p>{@code @AssertTrue isDateRangeValid()} produces a global error whose field name
     * is {@code dateRangeValid}. This method promotes it to a field error on {@code endDate}
     * so the Thymeleaf template can render it inline beneath the correct input.
     */
    private void rebindDateRangeError(BindingResult result) {
        result.getFieldErrors("dateRangeValid").forEach(err ->
                result.rejectValue("endDate", "dateRange.invalid", err.getDefaultMessage())
        );
    }
}
