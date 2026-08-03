package com.ulp.features.subjects.controller;

import com.ulp.features.head.dto.HeadDtos.DepartmentSummary;
import com.ulp.features.subjects.dto.SubjectDtos.SubjectForm;
import com.ulp.features.subjects.service.SubjectService;
import com.ulp.features.subjects.service.SubjectValidationException;
import com.ulp.security.Role;
import com.ulp.security.Roles;
import com.ulp.security.UlpUserDetails;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
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

import java.util.Set;

import static com.ulp.common.IConstant.*;

/**
 * HEAD-scoped subject catalog at {@code /head/subjects}.
 * Edit screen uses detail-page AJAX tabs (info / history).
 */
@Controller
@RequestMapping(URL_HEAD_SUBJECTS)
@PreAuthorize("hasRole('" + Roles.HEAD + "')")
public class HeadSubjectsController {

    private static final String REDIRECT_BASE = "redirect:" + URL_HEAD_SUBJECTS;
    private static final Set<String> VALID_DETAIL_TABS = Set.of(TAB_INFO, TAB_HISTORY);
    private static final int HISTORY_PAGE_SIZE = 20;

    private final SubjectService subjectService;

    public HeadSubjectsController(SubjectService subjectService) {
        this.subjectService = subjectService;
    }

    @GetMapping
    public String list(@AuthenticationPrincipal UlpUserDetails user, Model model) {
        boolean empty = subjectService.isEmptyDepartmentForHead(user.getId());
        model.addAttribute(ATTR_HEAD_EMPTY, empty);
        subjectService.resolveHeadDepartment(user.getId()).ifPresent(d ->
                model.addAttribute(ATTR_HEAD_DEPARTMENT,
                        new DepartmentSummary(d.getId(), d.getCode(), d.getName())));
        if (!empty) {
            model.addAttribute(ATTR_SUBJECTS,
                    subjectService.listForActor(user.getId(), Role.HEAD));
        }
        model.addAttribute(ATTR_ACTIVE_TAB, TAB_SUBJECTS);
        return VIEW_HEAD_SUBJECTS;
    }

    @GetMapping("/new")
    public String createForm(@AuthenticationPrincipal UlpUserDetails user,
                             Model model,
                             RedirectAttributes ra) {
        if (subjectService.isEmptyDepartmentForHead(user.getId())) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, MSG_HEAD_NO_DEPARTMENT);
            return REDIRECT_BASE;
        }
        if (!model.containsAttribute(ATTR_FORM)) {
            Long deptId = subjectService.resolveHeadDepartment(user.getId())
                    .map(d -> d.getId()).orElse(null);
            model.addAttribute(ATTR_FORM, SubjectForm.emptyForDepartment(deptId));
        }
        populateFormModel(model, user, MODE_CREATE, null, TAB_INFO);
        return VIEW_HEAD_SUBJECTS_FORM;
    }

    @PostMapping
    public String create(@Valid @ModelAttribute(ATTR_FORM) SubjectForm form,
                         BindingResult result,
                         @AuthenticationPrincipal UlpUserDetails user,
                         Model model,
                         RedirectAttributes ra) {
        if (result.hasErrors()) {
            populateFormModel(model, user, MODE_CREATE, null, TAB_INFO);
            return VIEW_HEAD_SUBJECTS_FORM;
        }
        try {
            String title = subjectService.create(form, user.getId(), Role.HEAD);
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_SUBJECT_CREATED + title);
            return REDIRECT_BASE;
        } catch (SubjectValidationException ex) {
            model.addAttribute(ATTR_FLASH_ERROR, ex.getMessage());
            populateFormModel(model, user, MODE_CREATE, null, TAB_INFO);
            return VIEW_HEAD_SUBJECTS_FORM;
        } catch (AccessDeniedException ex) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, ex.getMessage());
            return REDIRECT_BASE;
        }
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id,
                           @RequestParam(name = "tab", required = false, defaultValue = TAB_INFO) String tab,
                           @RequestParam(name = "page", required = false, defaultValue = "0") int page,
                           @AuthenticationPrincipal UlpUserDetails user,
                           Model model,
                           RedirectAttributes ra) {
        try {
            if (!model.containsAttribute(ATTR_FORM)) {
                model.addAttribute(ATTR_FORM,
                        subjectService.loadForm(id, user.getId(), Role.HEAD));
            }
            String activeTab = VALID_DETAIL_TABS.contains(tab) ? tab : TAB_INFO;
            populateFormModel(model, user, MODE_EDIT, id, activeTab);
            if (TAB_HISTORY.equals(activeTab)) {
                int safePage = Math.max(0, page);
                model.addAttribute(ATTR_ACTIVITIES_PAGE,
                        subjectService.listActivities(
                                id, user.getId(), Role.HEAD,
                                PageRequest.of(safePage, HISTORY_PAGE_SIZE)));
            }
            return VIEW_HEAD_SUBJECTS_FORM;
        } catch (EntityNotFoundException | AccessDeniedException ex) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, ex.getMessage());
            return REDIRECT_BASE;
        }
    }

    @PostMapping("/{id}/edit")
    public String update(@PathVariable Long id,
                         @Valid @ModelAttribute(ATTR_FORM) SubjectForm form,
                         BindingResult result,
                         @AuthenticationPrincipal UlpUserDetails user,
                         Model model,
                         RedirectAttributes ra) {
        if (result.hasErrors()) {
            populateFormModel(model, user, MODE_EDIT, id, TAB_INFO);
            return VIEW_HEAD_SUBJECTS_FORM;
        }
        try {
            subjectService.update(id, form, user.getId(), Role.HEAD);
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_SUBJECT_UPDATED);
            return "redirect:" + editUrl(id) + "?tab=" + TAB_INFO;
        } catch (SubjectValidationException ex) {
            model.addAttribute(ATTR_FLASH_ERROR, ex.getMessage());
            populateFormModel(model, user, MODE_EDIT, id, TAB_INFO);
            return VIEW_HEAD_SUBJECTS_FORM;
        } catch (EntityNotFoundException | AccessDeniedException ex) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, ex.getMessage());
            return REDIRECT_BASE;
        }
    }

    @PostMapping("/{id}/toggle")
    public String toggle(@PathVariable Long id,
                         @AuthenticationPrincipal UlpUserDetails user,
                         RedirectAttributes ra) {
        try {
            boolean now = subjectService.toggleActive(id, user.getId(), Role.HEAD);
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS,
                    now ? MSG_SUBJECT_ACTIVATED : MSG_SUBJECT_DEACTIVATED);
        } catch (EntityNotFoundException | AccessDeniedException | SubjectValidationException ex) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, ex.getMessage());
        }
        return REDIRECT_BASE;
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id,
                         @AuthenticationPrincipal UlpUserDetails user,
                         RedirectAttributes ra) {
        try {
            subjectService.softDelete(id, user.getId(), Role.HEAD);
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_SUBJECT_DELETED);
        } catch (EntityNotFoundException | AccessDeniedException | SubjectValidationException ex) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, ex.getMessage());
        }
        return REDIRECT_BASE;
    }

    private void populateFormModel(Model model, UlpUserDetails user,
                                   String mode, Long targetId, String detailTab) {
        subjectService.resolveHeadDepartment(user.getId()).ifPresent(d ->
                model.addAttribute(ATTR_HEAD_DEPARTMENT,
                        new DepartmentSummary(d.getId(), d.getCode(), d.getName())));
        model.addAttribute(ATTR_MODE, mode);
        model.addAttribute(ATTR_TARGET_ID, targetId);
        model.addAttribute(ATTR_ACTIVE_TAB, TAB_SUBJECTS);
        model.addAttribute(ATTR_ACTIVE_DETAIL_TAB, detailTab);
    }

    private static String editUrl(Long id) {
        return URL_HEAD_SUBJECTS + "/" + id + "/edit";
    }
}
