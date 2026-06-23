package com.ulp.classes.controller;

import com.ulp.auth.Roles;
import com.ulp.classes.dto.ClassesDtos.ClassForm;
import com.ulp.classes.entity.ClassEntity;
import com.ulp.classes.service.ClassMembersService;
import com.ulp.classes.service.ClassesService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;

/**
 * Controller for the lecturer class management screens.
 * Only LECTURER, HEAD, and ADMIN roles may access these endpoints (see {@link Roles}).
 *
 * <p>Exposed endpoints:
 * <ul>
 *   <li>{@code GET  /lecturer/classes}             — list all classes for the current user</li>
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
 * service layer. HTTP 404 and 403 are mapped via {@code GlobalExceptionHandler}.
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
     * @param principal the currently authenticated user
     * @param model     the Spring MVC model
     * @return the {@code classes/manage} view
     */
    @GetMapping("/classes")
    public String list(Principal principal, Model model) {
        model.addAttribute("classes", classesService.listForUser(principal));
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
     *
     * @param form      the submitted and validated class form data
     * @param result    the binding/validation result
     * @param principal the currently authenticated user (becomes the class owner)
     * @param model     the Spring MVC model
     * @param ra        redirect attributes for flash messages
     * @return redirect to {@code /lecturer/classes} on success, or the form view on error
     */
    @PostMapping("/classes")
    public String create(@Valid @ModelAttribute("form") ClassForm form,
                         BindingResult result,
                         Principal principal,
                         Model model,
                         RedirectAttributes ra) {
        if (result.hasErrors()) {
            rebindDateRangeError(result);
            model.addAttribute("mode", "create");
            model.addAttribute("formAction", "/lecturer/classes");
            return "classes/form";
        }
        ClassEntity saved = classesService.create(form, principal);
        ra.addFlashAttribute("flashSuccess", "Đã tạo lớp " + saved.getCode());
        return "redirect:/lecturer/classes";
    }

    /**
     * Renders the edit-class form for an existing class.
     * Only the class owner (or HEAD/ADMIN) may access this endpoint; the service
     * layer enforces the ownership check and throws if unauthorized.
     *
     * @param id        the class ID to edit
     * @param principal the currently authenticated user
     * @param model     the Spring MVC model
     * @return the {@code classes/form} view in edit mode
     */
    @GetMapping("/classes/{id}/edit")
    public String editForm(@PathVariable Long id, Principal principal, Model model) {
        ClassEntity entity = classesService.getEditable(id, principal);
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
     *
     * @param id        the class ID to update
     * @param form      the submitted and validated class form data
     * @param result    the binding/validation result
     * @param principal the currently authenticated user (ownership is verified by the service)
     * @param model     the Spring MVC model
     * @param ra        redirect attributes for flash messages
     * @return redirect to {@code /lecturer/classes} on success, or the form view on error
     */
    @PostMapping("/classes/{id}")
    public String update(@PathVariable Long id,
                         @Valid @ModelAttribute("form") ClassForm form,
                         BindingResult result,
                         Principal principal,
                         Model model,
                         RedirectAttributes ra) {
        if (result.hasErrors()) {
            rebindDateRangeError(result);
            model.addAttribute("mode", "edit");
            model.addAttribute("formAction", "/lecturer/classes/" + id);
            model.addAttribute("classId", id);
            return "classes/form";
        }
        classesService.update(id, form, principal);
        ra.addFlashAttribute("flashSuccess", "Đã cập nhật lớp");
        return "redirect:/lecturer/classes";
    }

    /**
     * Soft-deletes a class after the user confirms the action via the confirm modal.
     *
     * @param id        the class ID to delete
     * @param principal the currently authenticated user (ownership is verified by the service)
     * @param ra        redirect attributes for the success flash message
     * @return redirect to {@code /lecturer/classes}
     */
    @PostMapping("/classes/{id}/delete")
    public String delete(@PathVariable Long id,
                         Principal principal,
                         RedirectAttributes ra) {
        classesService.softDelete(id, principal);
        ra.addFlashAttribute("flashSuccess", "Đã xoá lớp");
        return "redirect:/lecturer/classes";
    }

    // ───────── Class detail page — sidebar tabs (Sprint 2 phase 2) ─────────
    //
    // URL pattern: /lecturer/classes/{id}/{tab}
    //   - /board     : Board (default tab when entering /lecturer/classes/{id})
    //   - /members   : Members (wired with real data)
    //   - /schedule, /roles, /groups, /assignments, /scores, /lessons,
    //     /materials  : placeholder (Sprint 3-5)
    //   - /settings  : edit-class form, reusing classes/form.html
    //
    // All endpoints share the same class-detail layout (sidebar + main).

    /**
     * Redirects the root class-detail URL to the default {@code /board} tab.
     *
     * @param id the class ID
     * @return redirect to {@code /lecturer/classes/{id}/board}
     */
    @GetMapping("/classes/{id}")
    public String detailRoot(@PathVariable Long id) {
        return "redirect:/lecturer/classes/" + id + "/board";
    }

    /**
     * Renders the class board (announcement) tab.
     *
     * @param id        the class ID
     * @param principal the currently authenticated user
     * @param model     the Spring MVC model
     * @return the {@code classes/detail-board} view
     */
    @GetMapping("/classes/{id}/board")
    public String detailBoard(@PathVariable Long id, Principal principal, Model model) {
        ClassEntity clazz = classesService.getViewable(id, principal);
        populateDetailModel(model, clazz, "board");
        return "classes/detail-board";
    }

    /**
     * Renders the class members tab with the full member list.
     *
     * @param id        the class ID
     * @param principal the currently authenticated user
     * @param model     the Spring MVC model
     * @return the {@code classes/detail-members} view
     */
    @GetMapping("/classes/{id}/members")
    public String detailMembers(@PathVariable Long id, Principal principal, Model model) {
        ClassMembersService.ClassMembersView view = classMembersService.listForClass(id, principal);
        populateDetailModel(model, view.clazz(), "members");
        model.addAttribute("members", view.members());
        model.addAttribute("memberTotal", view.total());
        return "classes/detail-members";
    }

    /**
     * Renders a placeholder view for class detail tabs not yet implemented (Sprint 3–5).
     * Handles: {@code /schedule}, {@code /roles}, {@code /groups}, {@code /assignments},
     * {@code /scores}, {@code /lessons}, {@code /materials}.
     *
     * @param id        the class ID
     * @param principal the currently authenticated user
     * @param request   the HTTP request used to extract the active tab name from the URL path
     * @param model     the Spring MVC model
     * @return the {@code classes/detail-placeholder} view
     */
    @GetMapping({"/classes/{id}/schedule", "/classes/{id}/roles",
                "/classes/{id}/groups", "/classes/{id}/assignments",
                "/classes/{id}/scores", "/classes/{id}/lessons",
                "/classes/{id}/materials"})
    public String detailPlaceholder(@PathVariable Long id,
                                    Principal principal,
                                    jakarta.servlet.http.HttpServletRequest request,
                                    Model model) {
        ClassEntity clazz = classesService.getViewable(id, principal);
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
     *
     * @param id        the class ID
     * @param principal the currently authenticated user
     * @param model     the Spring MVC model
     * @return the {@code classes/detail-settings} view
     */
    @GetMapping("/classes/{id}/settings")
    public String detailSettings(@PathVariable Long id, Principal principal, Model model) {
        ClassEntity entity = classesService.getEditable(id, principal);
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
     *
     * @param result the {@link BindingResult} from the current form submission
     */
    private void rebindDateRangeError(BindingResult result) {
        result.getFieldErrors("dateRangeValid").forEach(err ->
                result.rejectValue("endDate", "dateRange.invalid", err.getDefaultMessage())
        );
    }
}
