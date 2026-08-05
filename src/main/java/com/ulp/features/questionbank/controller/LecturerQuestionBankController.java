package com.ulp.features.questionbank.controller;

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

import static com.ulp.common.IConstant.ATTR_CANCEL_URL;
import static com.ulp.common.IConstant.ATTR_FLASH_ERROR;
import static com.ulp.common.IConstant.ATTR_FLASH_SUCCESS;
import static com.ulp.common.IConstant.ATTR_FORM;
import static com.ulp.common.IConstant.ATTR_FORM_ACTION;
import static com.ulp.common.IConstant.ATTR_MODE;
import static com.ulp.common.IConstant.ATTR_QB_CHAPTERS_JSON;
import static com.ulp.common.IConstant.ATTR_QB_DETAIL;
import static com.ulp.common.IConstant.ATTR_QB_EMPTY_DEPARTMENT;
import static com.ulp.common.IConstant.ATTR_QB_ITEMS;
import static com.ulp.common.IConstant.ATTR_QB_QUERY;
import static com.ulp.common.IConstant.ATTR_QB_SELECTED_CHAPTER_ID;
import static com.ulp.common.IConstant.ATTR_QB_SELECTED_SUBJECT_ID;
import static com.ulp.common.IConstant.ATTR_QB_SUBJECT_OPTIONS;
import static com.ulp.common.IConstant.BASE_LECTURER_QUESTION_BANK;
import static com.ulp.common.IConstant.MODE_CREATE;
import static com.ulp.common.IConstant.MODE_EDIT;
import static com.ulp.common.IConstant.MSG_QB_ARCHIVED;
import static com.ulp.common.IConstant.MSG_QB_CREATED;
import static com.ulp.common.IConstant.MSG_QB_UNARCHIVED;
import static com.ulp.common.IConstant.MSG_QB_UPDATED;
import static com.ulp.common.IConstant.URL_LECTURER_QUESTION_BANK;
import static com.ulp.common.IConstant.VIEW_QB_DETAIL;
import static com.ulp.common.IConstant.VIEW_QB_FORM;
import static com.ulp.common.IConstant.VIEW_QB_LIST;

/**
 * Lecturer screens for the private question bank: list only the actor's own
 * items (owner_id = actor.id), filter by subject/chapter/query, and create /
 * edit / archive those items. Everything is owner-only; the access policy
 * rejects items owned by someone else.
 */
@Controller
@RequestMapping(BASE_LECTURER_QUESTION_BANK)
@PreAuthorize(Roles.PREAUTH_LECTURER_OR_ABOVE)
public class LecturerQuestionBankController {

    private final QuestionBankItemService itemService;
    private final QuestionBankReviewService reviewService;

    public LecturerQuestionBankController(QuestionBankItemService itemService,
                                          QuestionBankReviewService reviewService) {
        this.itemService = itemService;
        this.reviewService = reviewService;
    }

    @GetMapping
    public String list(@RequestParam(name = "subjectId", required = false) Long subjectId,
                       @RequestParam(name = "chapterId", required = false) Long chapterId,
                       @RequestParam(name = "q", required = false) String q,
                       @AuthenticationPrincipal UlpUserDetails user,
                       Model model) {
        boolean hasDepartment = itemService.hasDepartment(user.getId(), user.getRole());
        model.addAttribute(ATTR_QB_EMPTY_DEPARTMENT, !hasDepartment);
        model.addAttribute(ATTR_QB_ITEMS, itemService.list(user.getId(), user.getRole(), subjectId, chapterId, q));
        model.addAttribute(ATTR_QB_SUBJECT_OPTIONS, hasDepartment
                ? itemService.subjectsFor(user.getId(), user.getRole())
                : List.of());
        addChaptersJson(model, user);
        model.addAttribute(ATTR_QB_SELECTED_SUBJECT_ID, subjectId);
        model.addAttribute(ATTR_QB_SELECTED_CHAPTER_ID, chapterId);
        model.addAttribute(ATTR_QB_QUERY, q);
        return VIEW_QB_LIST;
    }

    @GetMapping("/new")
    public String createForm(@AuthenticationPrincipal UlpUserDetails user, Model model) {
        if (!model.containsAttribute(ATTR_FORM)) {
            model.addAttribute(ATTR_FORM, QuestionBankItemForm.empty());
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
            return redirectDetail(id);
        } catch (QuestionBankValidationException | AccessDeniedException ex) {
            model.addAttribute(ATTR_FLASH_ERROR, ex.getMessage());
            populateForm(model, user, MODE_CREATE);
            return VIEW_QB_FORM;
        }
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id,
                         @AuthenticationPrincipal UlpUserDetails user,
                         Model model,
                         RedirectAttributes ra) {
        try {
            model.addAttribute(ATTR_QB_DETAIL, itemService.detail(user.getId(), user.getRole(), id));
            return VIEW_QB_DETAIL;
        } catch (QuestionBankValidationException | AccessDeniedException ex) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, ex.getMessage());
            return redirectList();
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
            return redirectList();
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
            return redirectDetail(id);
        } catch (QuestionBankValidationException | AccessDeniedException ex) {
            model.addAttribute(ATTR_FLASH_ERROR, ex.getMessage());
            populateForm(model, user, MODE_EDIT);
            return VIEW_QB_FORM;
        }
    }

    @PostMapping("/{id}/archive")
    public String archive(@PathVariable Long id,
                          @AuthenticationPrincipal UlpUserDetails user,
                          RedirectAttributes ra) {
        try {
            reviewService.archive(user.getId(), id);
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_QB_ARCHIVED);
        } catch (QuestionBankValidationException | AccessDeniedException ex) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, ex.getMessage());
        }
        return redirectList();
    }

    @PostMapping("/{id}/unarchive")
    public String unarchive(@PathVariable Long id,
                            @AuthenticationPrincipal UlpUserDetails user,
                            RedirectAttributes ra) {
        try {
            reviewService.unarchive(user.getId(), id);
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_QB_UNARCHIVED);
        } catch (QuestionBankValidationException | AccessDeniedException ex) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, ex.getMessage());
        }
        return redirectList();
    }

    private void populateForm(Model model, UlpUserDetails user, String mode) {
        boolean hasDepartment = itemService.hasDepartment(user.getId(), user.getRole());
        model.addAttribute(ATTR_MODE, mode);
        model.addAttribute(ATTR_FORM_ACTION, URL_LECTURER_QUESTION_BANK);
        model.addAttribute(ATTR_CANCEL_URL, URL_LECTURER_QUESTION_BANK);
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

    private static String redirectList() {
        return "redirect:" + URL_LECTURER_QUESTION_BANK;
    }

    private static String redirectDetail(Long id) {
        return "redirect:" + URL_LECTURER_QUESTION_BANK + "/" + id;
    }
}
