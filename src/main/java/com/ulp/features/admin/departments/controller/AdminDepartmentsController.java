package com.ulp.features.admin.departments.controller;

import com.ulp.features.admin.departments.dto.DepartmentDtos.DepartmentFilter;
import com.ulp.features.admin.departments.dto.DepartmentDtos.DepartmentForm;
import com.ulp.features.admin.departments.service.DepartmentQueryService;
import com.ulp.features.admin.departments.service.DepartmentService;
import com.ulp.features.admin.departments.service.DepartmentValidationException;
import com.ulp.security.Roles;
import com.ulp.security.UlpUserDetails;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
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
 * MVC controller for {@code /admin/departments} — ADMIN-only department CRUD
 * and head assignment.
 */
@Controller
@RequestMapping(URL_ADMIN_DEPARTMENTS)
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
public class AdminDepartmentsController {

    private static final String REDIRECT_BASE = "redirect:" + URL_ADMIN_DEPARTMENTS;
    private static final String ATTR_FILTER = "filter";
    private static final int HISTORY_PAGE_SIZE = 20;
    private static final Set<String> VALID_DETAIL_TABS = Set.of(TAB_INFO, TAB_HISTORY);

    private final DepartmentQueryService queryService;
    private final DepartmentService departmentService;

    public AdminDepartmentsController(DepartmentQueryService queryService,
                                      DepartmentService departmentService) {
        this.queryService = queryService;
        this.departmentService = departmentService;
    }

    /** Lists departments with optional search, status, and sort filters. */
    @GetMapping
    public String list(@RequestParam(required = false) String q,
                       @RequestParam(required = false) String status,
                       @RequestParam(required = false) String sort,
                       Model model) {
        DepartmentFilter filter = new DepartmentFilter(q, status, sort);
        model.addAttribute(ATTR_DEPARTMENTS, queryService.list(filter));
        model.addAttribute(ATTR_FILTER, filter);
        model.addAttribute(ATTR_ACTIVE_TAB, TAB_DEPARTMENTS);
        return VIEW_ADMIN_DEPARTMENTS;
    }

    @GetMapping("/new")
    public String createForm(Model model) {
        if (!model.containsAttribute(ATTR_FORM)) {
            model.addAttribute(ATTR_FORM, DepartmentForm.empty());
        }
        populateFormModel(model, MODE_CREATE, null, TAB_INFO);
        return VIEW_ADMIN_DEPARTMENTS_FORM;
    }

    @PostMapping
    public String create(@Valid @ModelAttribute("form") DepartmentForm form,
                         BindingResult result,
                         @AuthenticationPrincipal UlpUserDetails actor,
                         Model model,
                         RedirectAttributes ra) {
        if (result.hasErrors()) {
            populateFormModel(model, MODE_CREATE, null, TAB_INFO);
            return VIEW_ADMIN_DEPARTMENTS_FORM;
        }
        try {
            String savedName = departmentService.create(form, actorId(actor));
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_DEPARTMENT_CREATED + savedName);
            return REDIRECT_BASE;
        } catch (DepartmentValidationException ex) {
            model.addAttribute(ATTR_FLASH_ERROR, ex.getMessage());
            populateFormModel(model, MODE_CREATE, null, TAB_INFO);
            return VIEW_ADMIN_DEPARTMENTS_FORM;
        }
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id,
                           @RequestParam(name = "tab", required = false, defaultValue = TAB_INFO) String tab,
                           @RequestParam(name = "page", required = false, defaultValue = "0") int page,
                           Model model,
                           RedirectAttributes ra) {
        DepartmentForm existing = queryService.loadForm(id);
        if (existing == null) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, MSG_DEPARTMENT_NOT_FOUND);
            return REDIRECT_BASE;
        }
        if (!model.containsAttribute(ATTR_FORM)) {
            model.addAttribute(ATTR_FORM, existing);
        }
        // Invalid tab values silently fall back to info.
        String activeTab = VALID_DETAIL_TABS.contains(tab) ? tab : TAB_INFO;
        populateFormModel(model, MODE_EDIT, id, activeTab);
        if (TAB_HISTORY.equals(activeTab)) {
            int safePage = Math.max(0, page);
            model.addAttribute(ATTR_ACTIVITIES_PAGE,
                    queryService.listActivities(id, PageRequest.of(safePage, HISTORY_PAGE_SIZE)));
        }
        return VIEW_ADMIN_DEPARTMENTS_FORM;
    }

    @PostMapping("/{id}/edit")
    public String update(@PathVariable Long id,
                         @Valid @ModelAttribute("form") DepartmentForm form,
                         BindingResult result,
                         @AuthenticationPrincipal UlpUserDetails actor,
                         Model model,
                         RedirectAttributes ra) {
        if (result.hasErrors()) {
            populateFormModel(model, MODE_EDIT, id, TAB_INFO);
            return VIEW_ADMIN_DEPARTMENTS_FORM;
        }
        try {
            departmentService.update(id, form, actorId(actor));
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_DEPARTMENT_UPDATED);
            return "redirect:" + editUrl(id) + "?tab=" + TAB_INFO;
        } catch (DepartmentValidationException ex) {
            model.addAttribute(ATTR_FLASH_ERROR, ex.getMessage());
            populateFormModel(model, MODE_EDIT, id, TAB_INFO);
            return VIEW_ADMIN_DEPARTMENTS_FORM;
        }
    }

    @PostMapping("/{id}/toggle")
    public String toggle(@PathVariable Long id,
                         @AuthenticationPrincipal UlpUserDetails actor,
                         RedirectAttributes ra) {
        try {
            boolean nowActive = departmentService.toggleActive(id, actorId(actor));
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS,
                    nowActive ? MSG_DEPARTMENT_ACTIVATED : MSG_DEPARTMENT_DEACTIVATED);
        } catch (DepartmentValidationException ex) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, ex.getMessage());
        }
        return REDIRECT_BASE;
    }

    private void populateFormModel(Model model, String mode, Long targetId, String detailTab) {
        model.addAttribute(ATTR_MODE, mode);
        model.addAttribute(ATTR_TARGET_ID, targetId);
        model.addAttribute(ATTR_HEAD_CANDIDATES, queryService.headCandidates());
        model.addAttribute(ATTR_ACTIVE_TAB, TAB_DEPARTMENTS);
        model.addAttribute(ATTR_ACTIVE_DETAIL_TAB, detailTab);
    }

    /** Builds the canonical edit URL for a department. */
    private static String editUrl(Long id) {
        return URL_ADMIN_DEPARTMENTS + "/" + id + "/edit";
    }

    private static Long actorId(UlpUserDetails actor) {
        return actor == null ? null : actor.getId();
    }
}
