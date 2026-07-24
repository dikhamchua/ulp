package com.ulp.features.questionbank.controller;

import com.ulp.entities.Department;
import com.ulp.features.head.dto.HeadDtos.DepartmentSummary;
import com.ulp.features.head.service.HeadDepartmentResolver;
import com.ulp.features.questionbank.dto.QuestionBankCategoryForm;
import com.ulp.features.questionbank.service.QuestionBankCategoryService;
import com.ulp.features.questionbank.service.QuestionBankItemService;
import com.ulp.features.questionbank.service.QuestionBankReviewService;
import com.ulp.features.questionbank.service.QuestionBankValidationException;
import com.ulp.security.Roles;
import com.ulp.security.UlpUserDetails;
import jakarta.validation.Valid;
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
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

import static com.ulp.common.IConstant.ATTR_ACTIVE_TAB;
import static com.ulp.common.IConstant.ATTR_HEAD_DEPARTMENT;
import static com.ulp.common.IConstant.ATTR_QB_CATEGORIES;
import static com.ulp.common.IConstant.ATTR_QB_CATEGORY_DETAIL;
import static com.ulp.common.IConstant.ATTR_QB_CATEGORY_FORM;
import static com.ulp.common.IConstant.ATTR_QB_CATEGORY_ID;
import static com.ulp.common.IConstant.ATTR_QB_EDIT_CATEGORY_ID;
import static com.ulp.common.IConstant.ATTR_QB_EMPTY_DEPARTMENT;
import static com.ulp.common.IConstant.ATTR_QB_QUERY;
import static com.ulp.common.IConstant.ATTR_QB_SELECTED_CONTRIBUTOR_ID;
import static com.ulp.common.IConstant.ATTR_QB_SELECTED_ITEM;
import static com.ulp.common.IConstant.ATTR_QB_SELECTED_ITEM_ID;
import static com.ulp.common.IConstant.ATTR_QB_SELECTED_STATUS;
import static com.ulp.common.IConstant.BASE_HEAD_QUESTION_BANK;
import static com.ulp.common.IConstant.MSG_QB_APPROVED;
import static com.ulp.common.IConstant.MSG_QB_ARCHIVED;
import static com.ulp.common.IConstant.MSG_QB_CATEGORY_CREATED;
import static com.ulp.common.IConstant.MSG_QB_CATEGORY_DELETED;
import static com.ulp.common.IConstant.MSG_QB_CATEGORY_TOGGLED;
import static com.ulp.common.IConstant.MSG_QB_CATEGORY_UPDATED;
import static com.ulp.common.IConstant.MSG_QB_REJECTED;
import static com.ulp.common.IConstant.MSG_QB_UNARCHIVED;
import static com.ulp.common.IConstant.PARAM_QB_SELECTED;
import static com.ulp.common.IConstant.URL_HEAD_QUESTION_BANK_MANAGE;
import static com.ulp.common.IConstant.VIEW_QB_CATEGORY_DETAIL;
import static com.ulp.common.IConstant.VIEW_QB_MANAGE;

/**
 * HEAD management screen for the department-scoped shared question bank as a
 * master-detail flow: the master lists categories (with an open/close toggle
 * and CRUD), while the detail of one category lists its questions with
 * per-item review and bulk approve/reject/archive actions.
 */
@Controller
@RequestMapping(BASE_HEAD_QUESTION_BANK)
@PreAuthorize("hasRole('" + Roles.HEAD + "')")
public class HeadQuestionBankController {

    private static final String TAB_QUESTION_BANK = "question-bank";

    private final QuestionBankItemService itemService;
    private final QuestionBankReviewService reviewService;
    private final QuestionBankCategoryService categoryService;
    private final HeadDepartmentResolver departmentResolver;

    public HeadQuestionBankController(QuestionBankItemService itemService,
                                      QuestionBankReviewService reviewService,
                                      QuestionBankCategoryService categoryService,
                                      HeadDepartmentResolver departmentResolver) {
        this.itemService = itemService;
        this.reviewService = reviewService;
        this.categoryService = categoryService;
        this.departmentResolver = departmentResolver;
    }

    /** Master screen: category list + create/edit form. No question inbox here. */
    @GetMapping
    public String manage(@RequestParam(name = ATTR_QB_EDIT_CATEGORY_ID, required = false) Long editCategoryId,
                         @AuthenticationPrincipal UlpUserDetails user,
                         Model model) {
        model.addAttribute(ATTR_ACTIVE_TAB, TAB_QUESTION_BANK);
        addDepartmentChrome(user, model);
        boolean empty = !itemService.hasDepartment(user.getId(), user.getRole());
        model.addAttribute(ATTR_QB_EMPTY_DEPARTMENT, empty);
        if (empty) {
            // No resolved department: render the empty state without querying categories.
            if (!model.containsAttribute(ATTR_QB_CATEGORY_FORM)) {
                model.addAttribute(ATTR_QB_CATEGORY_FORM, QuestionBankCategoryForm.empty());
            }
            return VIEW_QB_MANAGE;
        }
        model.addAttribute(ATTR_QB_CATEGORIES, categoryService.rowsForCurator(user.getId()));
        if (!model.containsAttribute(ATTR_QB_CATEGORY_FORM)) {
            model.addAttribute(ATTR_QB_CATEGORY_FORM,
                    editCategoryId == null
                            ? QuestionBankCategoryForm.empty()
                            : categoryService.loadForm(user.getId(), editCategoryId));
        }
        model.addAttribute(ATTR_QB_EDIT_CATEGORY_ID, editCategoryId);
        return VIEW_QB_MANAGE;
    }

    /** Detail screen: the questions of one category, with filters and bulk actions. */
    @GetMapping("/categories/{categoryId}")
    public String categoryDetail(@PathVariable Long categoryId,
                                 @RequestParam(name = "status", required = false) String status,
                                 @RequestParam(name = "contributorId", required = false) Long contributorId,
                                 @RequestParam(name = "q", required = false) String q,
                                 @RequestParam(name = PARAM_QB_SELECTED, required = false) Long selected,
                                 @AuthenticationPrincipal UlpUserDetails user,
                                 Model model,
                                 RedirectAttributes ra) {
        try {
            model.addAttribute(ATTR_ACTIVE_TAB, TAB_QUESTION_BANK);
            addDepartmentChrome(user, model);
            model.addAttribute(ATTR_QB_CATEGORY_ID, categoryId);
            model.addAttribute(ATTR_QB_CATEGORY_DETAIL,
                    itemService.categoryDetail(user.getId(), user.getRole(), categoryId, status, contributorId, q));
            model.addAttribute(ATTR_QB_SELECTED_STATUS, status);
            model.addAttribute(ATTR_QB_SELECTED_CONTRIBUTOR_ID, contributorId);
            model.addAttribute(ATTR_QB_QUERY, q);
            model.addAttribute(ATTR_QB_SELECTED_ITEM_ID, selected);
            model.addAttribute(ATTR_QB_SELECTED_ITEM, selected == null
                    ? null
                    : itemService.detail(user.getId(), user.getRole(), selected));
            return VIEW_QB_CATEGORY_DETAIL;
        } catch (QuestionBankValidationException ex) {
            // Cross-department or missing category: never expose the payload.
            ra.addFlashAttribute("flashError", ex.getMessage());
            return redirectMaster();
        }
    }

    /** Head sidebar chrome: resolved department label (null renders "Chưa gán bộ môn"). */
    private void addDepartmentChrome(UlpUserDetails user, Model model) {
        Department department = departmentResolver.resolve(user.getId()).orElse(null);
        model.addAttribute(ATTR_HEAD_DEPARTMENT, department == null
                ? null
                : new DepartmentSummary(department.getId(), department.getCode(), department.getName()));
    }

    @PostMapping("/categories")
    public String createCategory(@Valid @ModelAttribute(ATTR_QB_CATEGORY_FORM) QuestionBankCategoryForm form,
                                 BindingResult result,
                                 @AuthenticationPrincipal UlpUserDetails user,
                                 Model model,
                                 RedirectAttributes ra) {
        if (result.hasErrors()) {
            return manage(null, user, model);
        }
        try {
            categoryService.create(user.getId(), form);
            ra.addFlashAttribute("flashSuccess", MSG_QB_CATEGORY_CREATED);
            return redirectMaster();
        } catch (QuestionBankValidationException ex) {
            model.addAttribute("flashError", ex.getMessage());
            return manage(null, user, model);
        }
    }

    @PostMapping("/categories/{id}/edit")
    public String updateCategory(@PathVariable Long id,
                                 @Valid @ModelAttribute(ATTR_QB_CATEGORY_FORM) QuestionBankCategoryForm form,
                                 BindingResult result,
                                 @AuthenticationPrincipal UlpUserDetails user,
                                 Model model,
                                 RedirectAttributes ra) {
        if (result.hasErrors()) {
            model.addAttribute(ATTR_QB_EDIT_CATEGORY_ID, id);
            return manage(id, user, model);
        }
        try {
            categoryService.update(user.getId(), id, form);
            ra.addFlashAttribute("flashSuccess", MSG_QB_CATEGORY_UPDATED);
            return redirectMaster();
        } catch (QuestionBankValidationException ex) {
            model.addAttribute("flashError", ex.getMessage());
            model.addAttribute(ATTR_QB_EDIT_CATEGORY_ID, id);
            return manage(id, user, model);
        }
    }

    @PostMapping("/categories/{id}/toggle")
    public String toggleCategory(@PathVariable Long id,
                                 @AuthenticationPrincipal UlpUserDetails user,
                                 RedirectAttributes ra) {
        try {
            categoryService.toggle(user.getId(), id);
            ra.addFlashAttribute("flashSuccess", MSG_QB_CATEGORY_TOGGLED);
        } catch (QuestionBankValidationException ex) {
            ra.addFlashAttribute("flashError", ex.getMessage());
        }
        return redirectMaster();
    }

    @PostMapping("/categories/{id}/delete")
    public String deleteCategory(@PathVariable Long id,
                                 @AuthenticationPrincipal UlpUserDetails user,
                                 RedirectAttributes ra) {
        try {
            categoryService.delete(user.getId(), id);
            ra.addFlashAttribute("flashSuccess", MSG_QB_CATEGORY_DELETED);
        } catch (QuestionBankValidationException ex) {
            ra.addFlashAttribute("flashError", ex.getMessage());
        }
        return redirectMaster();
    }

    @PostMapping("/{id}/approve")
    public String approve(@PathVariable Long id,
                          ReviewFilters filters,
                          @AuthenticationPrincipal UlpUserDetails user,
                          RedirectAttributes ra) {
        reviewService.approve(user.getId(), id);
        ra.addFlashAttribute("flashSuccess", MSG_QB_APPROVED);
        return redirectDetail(filters, ra);
    }

    @PostMapping("/{id}/reject")
    public String reject(@PathVariable Long id,
                         @RequestParam(name = "note", required = false) String note,
                         ReviewFilters filters,
                         @AuthenticationPrincipal UlpUserDetails user,
                         RedirectAttributes ra) {
        reviewService.reject(user.getId(), id, note);
        ra.addFlashAttribute("flashSuccess", MSG_QB_REJECTED);
        return redirectDetail(filters, ra);
    }

    @PostMapping("/{id}/archive")
    public String archive(@PathVariable Long id,
                          @RequestParam(name = "note", required = false) String note,
                          ReviewFilters filters,
                          @AuthenticationPrincipal UlpUserDetails user,
                          RedirectAttributes ra) {
        reviewService.archive(user.getId(), id, note);
        ra.addFlashAttribute("flashSuccess", MSG_QB_ARCHIVED);
        return redirectDetail(filters, ra);
    }

    @PostMapping("/{id}/unarchive")
    public String unarchive(@PathVariable Long id,
                            ReviewFilters filters,
                            @AuthenticationPrincipal UlpUserDetails user,
                            RedirectAttributes ra) {
        reviewService.unarchive(user.getId(), id);
        ra.addFlashAttribute("flashSuccess", MSG_QB_UNARCHIVED);
        return redirectDetail(filters, ra);
    }

    /** Filter state carried through a review POST so the redirect keeps context. */
    public record ReviewFilters(String status, Long categoryId, Long contributorId, String q) {
    }

    /**
     * Redirects back onto the category detail preserving the active filters. Uses
     * {@code filters.categoryId()} as the target category (single-review POSTs
     * carry the category through the filter form). Shared with the bulk controller.
     */
    static String redirectDetail(ReviewFilters filters, RedirectAttributes ra) {
        return redirectDetail(filters.categoryId(), filters, ra);
    }

    static String redirectDetail(Long categoryId, ReviewFilters filters, RedirectAttributes ra) {
        if (filters.status() != null && !filters.status().isBlank()) {
            ra.addAttribute("status", filters.status());
        }
        if (filters.contributorId() != null) {
            ra.addAttribute("contributorId", filters.contributorId());
        }
        if (filters.q() != null && !filters.q().isBlank()) {
            ra.addAttribute("q", filters.q());
        }
        return "redirect:" + URL_HEAD_QUESTION_BANK_MANAGE + "/categories/" + categoryId;
    }

    /** Redirect back onto the master category list after a category action. */
    private static String redirectMaster() {
        return "redirect:" + URL_HEAD_QUESTION_BANK_MANAGE;
    }
}
