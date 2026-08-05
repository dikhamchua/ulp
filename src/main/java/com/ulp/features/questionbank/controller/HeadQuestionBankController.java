package com.ulp.features.questionbank.controller;

import com.ulp.entities.Department;
import com.ulp.features.head.dto.HeadDtos.DepartmentSummary;
import com.ulp.features.head.service.HeadDepartmentResolver;
import com.ulp.features.questionbank.dto.QuestionBankItemForm;
import com.ulp.features.questionbank.service.QuestionBankItemService;
import com.ulp.features.questionbank.service.QuestionBankReviewService;
import com.ulp.features.questionbank.service.QuestionBankValidationException;
import com.ulp.security.Roles;
import com.ulp.security.UlpUserDetails;
import jakarta.validation.Valid;
import org.springframework.security.access.AccessDeniedException;
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
import static com.ulp.common.IConstant.ATTR_CANCEL_URL;
import static com.ulp.common.IConstant.ATTR_FLASH_ERROR;
import static com.ulp.common.IConstant.ATTR_FLASH_SUCCESS;
import static com.ulp.common.IConstant.ATTR_FORM;
import static com.ulp.common.IConstant.ATTR_FORM_ACTION;
import static com.ulp.common.IConstant.ATTR_HEAD_DEPARTMENT;
import static com.ulp.common.IConstant.ATTR_MODE;
import static com.ulp.common.IConstant.ATTR_QB_CHAPTERS;
import static com.ulp.common.IConstant.ATTR_QB_CHAPTERS_JSON;
import static com.ulp.common.IConstant.ATTR_QB_EMPTY_DEPARTMENT;
import static com.ulp.common.IConstant.ATTR_QB_ITEMS;
import static com.ulp.common.IConstant.ATTR_QB_QUERY;
import static com.ulp.common.IConstant.ATTR_QB_SELECTED_CHAPTER_ID;
import static com.ulp.common.IConstant.ATTR_QB_SELECTED_SUBJECT_ID;
import static com.ulp.common.IConstant.ATTR_QB_SUBJECT_OPTIONS;
import static com.ulp.common.IConstant.ATTR_QB_SUBJECTS;
import static com.ulp.common.IConstant.BASE_HEAD_QUESTION_BANK;
import static com.ulp.common.IConstant.MODE_CREATE;
import static com.ulp.common.IConstant.MODE_EDIT;
import static com.ulp.common.IConstant.MSG_QB_ARCHIVED;
import static com.ulp.common.IConstant.MSG_QB_CREATED;
import static com.ulp.common.IConstant.MSG_QB_UNARCHIVED;
import static com.ulp.common.IConstant.MSG_QB_UPDATED;
import static com.ulp.common.IConstant.URL_HEAD_QUESTION_BANK_MANAGE;
import static com.ulp.common.IConstant.VIEW_QB_FORM;
import static com.ulp.common.IConstant.VIEW_QB_MANAGE;

/**
 * HEAD management screen for the department question bank, organised by subject:
 * pick a subject → view its chapters and ACTIVE/ARCHIVED items → create/edit/
 * archive questions (created ACTIVE). No category CRUD, no approve/reject.
 */
@Controller
@RequestMapping(BASE_HEAD_QUESTION_BANK)
@PreAuthorize("hasRole('" + Roles.HEAD + "')")
public class HeadQuestionBankController {

    private static final String TAB_QUESTION_BANK = "question-bank";

    private final QuestionBankItemService itemService;
    private final QuestionBankReviewService reviewService;
    private final HeadDepartmentResolver departmentResolver;

    public HeadQuestionBankController(QuestionBankItemService itemService,
                                      QuestionBankReviewService reviewService,
                                      HeadDepartmentResolver departmentResolver) {
        this.itemService = itemService;
        this.reviewService = reviewService;
        this.departmentResolver = departmentResolver;
    }

    /** Manage screen: filterable table of HEAD-bank items (subject/chapter/query). */
    @GetMapping
    public String manage(@RequestParam(name = "subjectId", required = false) Long subjectId,
                         @RequestParam(name = "chapterId", required = false) Long chapterId,
                         @RequestParam(name = "q", required = false) String q,
                         @AuthenticationPrincipal UlpUserDetails user,
                         Model model) {
        model.addAttribute(ATTR_ACTIVE_TAB, TAB_QUESTION_BANK);
        addDepartmentChrome(user, model);
        boolean empty = !itemService.hasDepartment(user.getId(), user.getRole());
        model.addAttribute(ATTR_QB_EMPTY_DEPARTMENT, empty);
        if (empty) {
            return VIEW_QB_MANAGE;
        }
        model.addAttribute(ATTR_QB_SUBJECT_OPTIONS, itemService.subjectsFor(user.getId(), user.getRole()));
        model.addAttribute(ATTR_QB_SELECTED_SUBJECT_ID, subjectId);
        model.addAttribute(ATTR_QB_SELECTED_CHAPTER_ID, chapterId);
        model.addAttribute(ATTR_QB_QUERY, q);
        model.addAttribute(ATTR_QB_ITEMS, itemService.listHead(user.getId(), user.getRole(), subjectId, chapterId, q));
        addChaptersJson(model, user);
        return VIEW_QB_MANAGE;
    }

    @GetMapping("/new")
    public String createForm(@RequestParam(name = "subjectId", required = false) Long subjectId,
                             @AuthenticationPrincipal UlpUserDetails user,
                             Model model) {
        if (!model.containsAttribute(ATTR_FORM)) {
            QuestionBankItemForm form = QuestionBankItemForm.empty();
            form.setSubjectId(subjectId);
            model.addAttribute(ATTR_FORM, form);
        }
        populateForm(model, user, MODE_CREATE);
        return VIEW_QB_FORM;
    }

    @PostMapping
    public String create(@Valid @ModelAttribute(ATTR_FORM) QuestionBankItemForm form,
                         BindingResult result,
                         @AuthenticationPrincipal UlpUserDetails user,
                         Model model,
                         RedirectAttributes ra) {
        form.ensureMinOptions(4);
        if (result.hasErrors()) {
            populateForm(model, user, MODE_CREATE);
            return VIEW_QB_FORM;
        }
        try {
            Long id = itemService.save(user.getId(), user.getRole(), form);
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_QB_CREATED);
            return redirectManage(new SubjectFilters(form.getSubjectId(), null, null), ra);
        } catch (QuestionBankValidationException | AccessDeniedException ex) {
            model.addAttribute(ATTR_FLASH_ERROR, ex.getMessage());
            populateForm(model, user, MODE_CREATE);
            return VIEW_QB_FORM;
        }
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id,
                           @AuthenticationPrincipal UlpUserDetails user,
                           Model model,
                           RedirectAttributes ra) {
        try {
            if (!model.containsAttribute(ATTR_FORM)) {
                model.addAttribute(ATTR_FORM, itemService.loadForm(user.getId(), user.getRole(), id));
            }
            populateForm(model, user, MODE_EDIT);
            return VIEW_QB_FORM;
        } catch (QuestionBankValidationException | AccessDeniedException ex) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, ex.getMessage());
            return redirectManage(new SubjectFilters(null, null, null), ra);
        }
    }

    @PostMapping("/{id}/edit")
    public String update(@PathVariable Long id,
                         @Valid @ModelAttribute(ATTR_FORM) QuestionBankItemForm form,
                         BindingResult result,
                         @AuthenticationPrincipal UlpUserDetails user,
                         Model model,
                         RedirectAttributes ra) {
        form.setId(id);
        form.ensureMinOptions(4);
        if (result.hasErrors()) {
            populateForm(model, user, MODE_EDIT);
            return VIEW_QB_FORM;
        }
        try {
            itemService.save(user.getId(), user.getRole(), form);
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_QB_UPDATED);
            return redirectManage(new SubjectFilters(form.getSubjectId(), null, null), ra);
        } catch (QuestionBankValidationException | AccessDeniedException ex) {
            model.addAttribute(ATTR_FLASH_ERROR, ex.getMessage());
            populateForm(model, user, MODE_EDIT);
            return VIEW_QB_FORM;
        }
    }

    @PostMapping("/{id}/archive")
    public String archive(@PathVariable Long id,
                          SubjectFilters filters,
                          @AuthenticationPrincipal UlpUserDetails user,
                          RedirectAttributes ra) {
        try {
            reviewService.archive(user.getId(), id);
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_QB_ARCHIVED);
        } catch (QuestionBankValidationException | AccessDeniedException ex) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, ex.getMessage());
        }
        return redirectManage(filters, ra);
    }

    @PostMapping("/{id}/unarchive")
    public String unarchive(@PathVariable Long id,
                            SubjectFilters filters,
                            @AuthenticationPrincipal UlpUserDetails user,
                            RedirectAttributes ra) {
        try {
            reviewService.unarchive(user.getId(), id);
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_QB_UNARCHIVED);
        } catch (QuestionBankValidationException | AccessDeniedException ex) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, ex.getMessage());
        }
        return redirectManage(filters, ra);
    }

    /** Head sidebar chrome: resolved department label (null renders "Chưa gán bộ môn"). */
    private void addDepartmentChrome(UlpUserDetails user, Model model) {
        Department department = departmentResolver.resolve(user.getId()).orElse(null);
        model.addAttribute(ATTR_HEAD_DEPARTMENT, department == null
                ? null
                : new DepartmentSummary(department.getId(), department.getCode(), department.getName()));
    }

    private void populateForm(Model model, UlpUserDetails user, String mode) {
        boolean hasDepartment = itemService.hasDepartment(user.getId(), user.getRole());
        model.addAttribute(ATTR_MODE, mode);
        model.addAttribute(ATTR_FORM_ACTION, URL_HEAD_QUESTION_BANK_MANAGE);
        model.addAttribute(ATTR_CANCEL_URL, URL_HEAD_QUESTION_BANK_MANAGE);
        model.addAttribute(ATTR_QB_SUBJECT_OPTIONS, hasDepartment
                ? itemService.subjectsFor(user.getId(), user.getRole())
                : List.of());
        addChaptersJson(model, user);
        model.addAttribute(ATTR_QB_EMPTY_DEPARTMENT, !hasDepartment);
    }

    private void addChaptersJson(Model model, UlpUserDetails user) {
        // Pass the Map object, not a pre-serialized String: Thymeleaf inline-JS
        // serializes it via Jackson. A pre-serialized String is escaped a second
        // time into a JS string literal, breaking the dependent chapter dropdown.
        model.addAttribute(ATTR_QB_CHAPTERS_JSON,
                itemService.chaptersBySubjectFor(user.getId(), user.getRole()));
    }

    /** Filter state carried through HEAD single/bulk posts so the redirect keeps context. */
    public record SubjectFilters(Long subjectId, Long chapterId, String q) {
    }

    /**
     * Redirects back onto the manage screen preserving the active filters. Shared
     * with the bulk controller.
     */
    static String redirectManage(SubjectFilters filters, RedirectAttributes ra) {
        if (filters != null && filters.subjectId() != null) {
            ra.addAttribute("subjectId", filters.subjectId());
        }
        if (filters != null && filters.chapterId() != null) {
            ra.addAttribute("chapterId", filters.chapterId());
        }
        if (filters != null && filters.q() != null && !filters.q().isBlank()) {
            ra.addAttribute("q", filters.q());
        }
        return "redirect:" + URL_HEAD_QUESTION_BANK_MANAGE;
    }
}
