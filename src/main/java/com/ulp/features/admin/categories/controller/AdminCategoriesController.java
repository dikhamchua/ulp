package com.ulp.features.admin.categories.controller;

import com.ulp.features.admin.categories.dto.CategoryDtos.CategoryForm;
import com.ulp.features.admin.categories.service.CategoryService;
import com.ulp.features.admin.categories.service.CategoryValidationException;
import com.ulp.security.Roles;
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

import static com.ulp.common.IConstant.*;

/**
 * MVC controller for the {@code /admin/categories} screen — ADMIN-only CRUD +
 * active toggle for the two-level course-category tree.
 *
 * <p>Endpoints: list (tree), create (form + submit), edit (form + submit),
 * delete, and toggle-active. Field-level validation errors re-render the form
 * inline; hierarchy/delete-guard breaches from the service surface as error
 * toasts via the flash → toast pattern. CSRF protects every POST.
 */
@Controller
@RequestMapping("/admin/categories")
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
public class AdminCategoriesController {

    private static final String REDIRECT_BASE = "redirect:/admin/categories";

    private final CategoryService categoryService;

    public AdminCategoriesController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    /** Renders the full two-level tree. */
    @GetMapping
    public String list(Model model) {
        model.addAttribute(ATTR_CATEGORY_TREE, categoryService.tree());
        model.addAttribute(ATTR_ACTIVE_TAB, TAB_CATEGORIES);
        return VIEW_ADMIN_CATEGORIES;
    }

    /** Renders the create form, preserving flashed values from a failed POST. */
    @GetMapping("/new")
    public String createForm(Model model) {
        if (!model.containsAttribute(ATTR_FORM)) {
            model.addAttribute(ATTR_FORM, CategoryForm.empty());
        }
        populateFormModel(model, MODE_CREATE, null, false);
        return VIEW_ADMIN_CATEGORIES_FORM;
    }

    /** Submits the create form; re-renders inline on error, redirects on success. */
    @PostMapping
    public String create(@Valid @ModelAttribute("form") CategoryForm form,
                         BindingResult result,
                         Model model,
                         RedirectAttributes ra) {
        if (result.hasErrors()) {
            populateFormModel(model, MODE_CREATE, null, false);
            return VIEW_ADMIN_CATEGORIES_FORM;
        }
        try {
            String savedName = categoryService.create(form);
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_CATEGORY_CREATED + savedName);
            return REDIRECT_BASE;
        } catch (CategoryValidationException ex) {
            // Hierarchy breach (e.g. parent is itself a child) — re-render with a toast.
            model.addAttribute(ATTR_FLASH_ERROR, ex.getMessage());
            populateFormModel(model, MODE_CREATE, null, false);
            return VIEW_ADMIN_CATEGORIES_FORM;
        }
    }

    /** Renders the edit form for an existing category. */
    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model, RedirectAttributes ra) {
        CategoryForm existing = categoryService.loadForm(id);
        if (existing == null) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, MSG_CATEGORY_NOT_FOUND);
            return REDIRECT_BASE;
        }
        if (!model.containsAttribute(ATTR_FORM)) {
            model.addAttribute(ATTR_FORM, existing);
        }
        populateFormModel(model, MODE_EDIT, id, categoryService.hasChildren(id));
        return VIEW_ADMIN_CATEGORIES_FORM;
    }

    /** Submits the edit form; re-renders inline on error, redirects on success. */
    @PostMapping("/{id}/edit")
    public String update(@PathVariable Long id,
                         @Valid @ModelAttribute("form") CategoryForm form,
                         BindingResult result,
                         Model model,
                         RedirectAttributes ra) {
        if (result.hasErrors()) {
            populateFormModel(model, MODE_EDIT, id, categoryService.hasChildren(id));
            return VIEW_ADMIN_CATEGORIES_FORM;
        }
        try {
            categoryService.update(id, form);
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_CATEGORY_UPDATED);
            return REDIRECT_BASE;
        } catch (CategoryValidationException ex) {
            model.addAttribute(ATTR_FLASH_ERROR, ex.getMessage());
            populateFormModel(model, MODE_EDIT, id, categoryService.hasChildren(id));
            return VIEW_ADMIN_CATEGORIES_FORM;
        }
    }

    /** Hard-deletes a category; guard breaches surface as an error toast. */
    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        try {
            categoryService.delete(id);
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_CATEGORY_DELETED);
        } catch (CategoryValidationException ex) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, ex.getMessage());
        }
        return REDIRECT_BASE;
    }

    /** Toggles the active flag; reports the new state as a confirmation toast. */
    @PostMapping("/{id}/toggle")
    public String toggle(@PathVariable Long id, RedirectAttributes ra) {
        try {
            boolean nowActive = categoryService.toggleActive(id);
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS,
                    nowActive ? MSG_CATEGORY_ACTIVATED : MSG_CATEGORY_DEACTIVATED);
        } catch (CategoryValidationException ex) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, ex.getMessage());
        }
        return REDIRECT_BASE;
    }

    /** Adds the shared form-model attributes (mode, parent options, guards). */
    private void populateFormModel(Model model, String mode, Long targetId, boolean hasChildren) {
        model.addAttribute(ATTR_MODE, mode);
        model.addAttribute(ATTR_TARGET_ID, targetId);
        model.addAttribute(ATTR_HAS_CHILDREN, hasChildren);
        model.addAttribute(ATTR_CATEGORY_PARENTS, categoryService.parentOptions());
        model.addAttribute(ATTR_ACTIVE_TAB, TAB_CATEGORIES);
    }
}
