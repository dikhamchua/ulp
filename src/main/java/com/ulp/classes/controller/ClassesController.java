package com.ulp.classes.controller;

import com.ulp.auth.Roles;
import com.ulp.auth.service.UlpUserDetails;
import com.ulp.classes.dto.ClassesDtos.ClassForm;
import com.ulp.classes.dto.ClassesDtos.ClassRow;
import com.ulp.classes.entity.ClassEntity;
import com.ulp.classes.service.ClassMembersService;
import com.ulp.classes.service.ClassesService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
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
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

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
@RequestMapping("/lecturer")
@PreAuthorize(Roles.PREAUTH_LECTURER_OR_ABOVE)
public class ClassesController {

    private final ClassesService classesService;
    private final ClassMembersService classMembersService;

    public ClassesController(ClassesService classesService,
                             ClassMembersService classMembersService) {
        this.classesService = classesService;
        this.classMembersService = classMembersService;
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
                       @PageableDefault(size = 20, sort = "createdAt",
                               direction = Sort.Direction.DESC) Pageable pageable,
                       Model model) {
        Page<ClassRow> page = classesService.listForUser(user.getId(), user.getRole(), pageable);
        // Keep the existing template loop driven by ${classes} (a List). The Page
        // object is exposed separately as ${classesPage} for the pagination block.
        model.addAttribute("classes", page.getContent());
        model.addAttribute("classesPage", page);
        return "classes/manage";
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
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", ClassForm.empty());
        }
        model.addAttribute("mode", "create");
        model.addAttribute("formAction", "/lecturer/classes");
        return "classes/form";
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
        if (result.hasErrors()) {
            rebindDateRangeError(result);
            model.addAttribute("mode", "create");
            model.addAttribute("formAction", "/lecturer/classes");
            return "classes/form";
        }
        ClassEntity saved = classesService.create(form, user.getId());
        ra.addFlashAttribute("flashSuccess", "Đã tạo lớp " + saved.getCode());
        return "redirect:/lecturer/classes";
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
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", ClassForm.fromEntity(entity));
        }
        model.addAttribute("mode", "edit");
        model.addAttribute("formAction", "/lecturer/classes/" + id);
        model.addAttribute("classId", id);
        return "classes/form";
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
        if (result.hasErrors()) {
            rebindDateRangeError(result);
            model.addAttribute("mode", "edit");
            model.addAttribute("formAction", "/lecturer/classes/" + id);
            model.addAttribute("classId", id);
            return "classes/form";
        }
        classesService.update(id, form, user.getId(), user.getRole());
        ra.addFlashAttribute("flashSuccess", "Đã cập nhật lớp");
        return "redirect:/lecturer/classes";
    }

    /**
     * Soft-deletes a class after the user confirms the action via the confirm modal.
     */
    @PostMapping("/classes/{id}/delete")
    public String delete(@PathVariable Long id,
                         @AuthenticationPrincipal UlpUserDetails user,
                         RedirectAttributes ra) {
        classesService.softDelete(id, user.getId(), user.getRole());
        ra.addFlashAttribute("flashSuccess", "Đã xoá lớp");
        return "redirect:/lecturer/classes";
    }

    // ───────── Class detail page — sidebar tabs (Sprint 2 phase 2) ─────────

    /**
     * Redirects the root class-detail URL to the default {@code /board} tab.
     */
    @GetMapping("/classes/{id}")
    public String detailRoot(@PathVariable Long id) {
        return "redirect:/lecturer/classes/" + id + "/board";
    }

    /**
     * Renders the class board (announcement) tab.
     */
    @GetMapping("/classes/{id}/board")
    public String detailBoard(@PathVariable Long id,
                              @AuthenticationPrincipal UlpUserDetails user,
                              Model model) {
        ClassEntity clazz = classesService.getViewable(id, user.getId(), user.getRole());
        populateDetailModel(model, clazz, "board");
        return "classes/detail-board";
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
        populateDetailModel(model, view.clazz(), "members");
        model.addAttribute("members", view.members());
        model.addAttribute("memberTotal", view.total());
        return "classes/detail-members";
    }

    /**
     * Renders a placeholder view for class detail tabs not yet implemented (Sprint 3–5).
     * Handles: {@code /schedule}, {@code /roles}, {@code /groups}, {@code /assignments},
     * {@code /scores}, {@code /lessons}, {@code /materials}.
     */
    @GetMapping({"/classes/{id}/schedule", "/classes/{id}/roles",
                "/classes/{id}/groups", "/classes/{id}/assignments",
                "/classes/{id}/scores", "/classes/{id}/lessons",
                "/classes/{id}/materials"})
    public String detailPlaceholder(@PathVariable Long id,
                                    @AuthenticationPrincipal UlpUserDetails user,
                                    jakarta.servlet.http.HttpServletRequest request,
                                    Model model) {
        ClassEntity clazz = classesService.getViewable(id, user.getId(), user.getRole());
        String path = request.getRequestURI();
        String tab = path.substring(path.lastIndexOf('/') + 1);
        populateDetailModel(model, clazz, tab);
        model.addAttribute("placeholderTab", tab);
        model.addAttribute("placeholderLabel", labelFor(tab));
        return "classes/detail-placeholder";
    }

    /**
     * Renders the class settings tab, reusing the edit-class form.
     * Only the class owner (or HEAD/ADMIN) may access this endpoint.
     */
    @GetMapping("/classes/{id}/settings")
    public String detailSettings(@PathVariable Long id,
                                 @AuthenticationPrincipal UlpUserDetails user,
                                 Model model) {
        ClassEntity entity = classesService.getEditable(id, user.getId(), user.getRole());
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", ClassForm.fromEntity(entity));
        }
        populateDetailModel(model, entity, "settings");
        model.addAttribute("mode", "edit");
        model.addAttribute("formAction", "/lecturer/classes/" + id);
        model.addAttribute("classId", id);
        return "classes/detail-settings";
    }

    /** Populates common model attributes required by the class-detail layout (sidebar). */
    private void populateDetailModel(Model model, ClassEntity clazz, String activeTab) {
        model.addAttribute("clazz", clazz);
        model.addAttribute("activeTab", activeTab);
    }

    private static String labelFor(String tab) {
        return switch (tab) {
            case "board" -> "Bảng tin";
            case "schedule" -> "Lịch học";
            case "members" -> "Thành viên";
            case "roles" -> "Vai trò lớp";
            case "groups" -> "Nhóm học tập";
            case "assignments" -> "Bài tập";
            case "scores" -> "Bảng điểm";
            case "lessons" -> "Bài giảng";
            case "materials" -> "Tài liệu";
            case "settings" -> "Cài đặt lớp học";
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
