package com.ulp.features.classes.controller;

import com.ulp.entities.ClassEntity;
import com.ulp.entities.User;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.classes.dto.ClassesDtos.ClassForm;
import com.ulp.features.classes.dto.ClassesDtos.ClassRow;
import com.ulp.features.classes.service.ClassesService;
import com.ulp.features.subjects.service.SubjectService;
import com.ulp.features.subjects.service.SubjectValidationException;
import com.ulp.security.Roles;
import com.ulp.security.UlpUserDetails;
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

import static com.ulp.common.IConstant.*;
import static com.ulp.features.classes.controller.support.ClassDetailModelSupport.classUrl;

/**
 * Controller for the lecturer class CRUD screens (list, create, edit, delete).
 * Only LECTURER, HEAD, and ADMIN roles may access these endpoints (see {@link Roles}).
 *
 * <p>Create/edit forms require a subject; class department is stamped from that
 * subject (not from the lecturer's department).
 */
@Controller
@RequestMapping(BASE_LECTURER)
@PreAuthorize(Roles.PREAUTH_LECTURER_OR_ABOVE)
public class ClassesController {

    private final ClassesService classesService;
    private final SubjectService subjectService;
    private final UserRepository userRepository;

    public ClassesController(ClassesService classesService,
                             SubjectService subjectService,
                             UserRepository userRepository) {
        this.classesService = classesService;
        this.subjectService = subjectService;
        this.userRepository = userRepository;
    }

    /**
     * Lists all classes owned by or accessible to the authenticated user.
     *
     * <p>Pagination defaults: 20 rows per page, sorted by {@code createdAt DESC}.
     */
    @GetMapping("/classes")
    public String list(@AuthenticationPrincipal UlpUserDetails user,
                       @PageableDefault(size = DEFAULT_PAGE_SIZE, sort = "createdAt",
                               direction = Sort.Direction.DESC) Pageable pageable,
                       Model model) {
        Page<ClassRow> page = classesService.listForUser(user.getId(), user.getRole(), pageable);
        model.addAttribute(ATTR_CLASSES, page.getContent());
        model.addAttribute(ATTR_CLASSES_PAGE, page);
        return VIEW_CLASS_MANAGE;
    }

    /**
     * Renders the create-class form.
     * Preserves a previously bound {@code form} flash attribute on validation redirect.
     */
    @GetMapping("/classes/new")
    public String createForm(@AuthenticationPrincipal UlpUserDetails user, Model model) {
        // Preserve flashed form values from a prior failed POST.
        if (!model.containsAttribute(ATTR_FORM)) {
            model.addAttribute(ATTR_FORM, ClassForm.empty());
        }
        model.addAttribute(ATTR_MODE, MODE_CREATE);
        model.addAttribute(ATTR_FORM_ACTION, URL_CLASSES_LIST);
        populateSubjectPicker(model, user);
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
        if (result.hasErrors()) {
            rebindDateRangeError(result);
            model.addAttribute(ATTR_MODE, MODE_CREATE);
            model.addAttribute(ATTR_FORM_ACTION, URL_CLASSES_LIST);
            populateSubjectPicker(model, user);
            return VIEW_CLASS_FORM;
        }
        try {
            ClassEntity saved = classesService.create(form, user.getId());
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_CLASS_CREATED + saved.getCode());
            return "redirect:" + URL_CLASSES_LIST;
        } catch (SubjectValidationException ex) {
            result.rejectValue("subjectId", "subject.invalid", ex.getMessage());
            model.addAttribute(ATTR_MODE, MODE_CREATE);
            model.addAttribute(ATTR_FORM_ACTION, URL_CLASSES_LIST);
            populateSubjectPicker(model, user);
            return VIEW_CLASS_FORM;
        }
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
        populateSubjectPicker(model, user);
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
        if (result.hasErrors()) {
            rebindDateRangeError(result);
            model.addAttribute(ATTR_MODE, MODE_EDIT);
            model.addAttribute(ATTR_FORM_ACTION, classUrl(id));
            model.addAttribute(ATTR_CLASS_ID, id);
            populateSubjectPicker(model, user);
            return VIEW_CLASS_FORM;
        }
        try {
            classesService.update(id, form, user.getId(), user.getRole());
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_CLASS_UPDATED);
            return "redirect:" + URL_CLASSES_LIST;
        } catch (SubjectValidationException ex) {
            result.rejectValue("subjectId", "subject.invalid", ex.getMessage());
            model.addAttribute(ATTR_MODE, MODE_EDIT);
            model.addAttribute(ATTR_FORM_ACTION, classUrl(id));
            model.addAttribute(ATTR_CLASS_ID, id);
            populateSubjectPicker(model, user);
            return VIEW_CLASS_FORM;
        }
    }

    /** Soft-deletes a class after the user confirms the action via the confirm modal. */
    @PostMapping("/classes/{id}/delete")
    public String delete(@PathVariable Long id,
                         @AuthenticationPrincipal UlpUserDetails user,
                         RedirectAttributes ra) {
        classesService.softDelete(id, user.getId(), user.getRole());
        ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_CLASS_DELETED);
        return "redirect:" + URL_CLASSES_LIST;
    }

    /** Redirects the root class-detail URL to the default {@code /board} tab. */
    @GetMapping("/classes/{id}")
    public String detailRoot(@PathVariable Long id) {
        return "redirect:" + classUrl(id) + "/" + TAB_BOARD;
    }

    /**
     * Rebinds a cross-field date-range validation error to the {@code endDate} field.
     */
    private void rebindDateRangeError(BindingResult result) {
        result.getFieldErrors("dateRangeValid").forEach(err ->
                result.rejectValue("endDate", "dateRange.invalid", err.getDefaultMessage())
        );
    }

    /** Loads active subject options and the caller's department for the cross-dept warning. */
    private void populateSubjectPicker(Model model, UlpUserDetails user) {
        model.addAttribute(ATTR_SUBJECT_OPTIONS, subjectService.listActiveOptions());
        Long lecturerDeptId = userRepository.findById(user.getId())
                .map(User::getDepartmentId)
                .orElse(null);
        model.addAttribute(ATTR_LECTURER_DEPARTMENT_ID, lecturerDeptId);
    }
}
