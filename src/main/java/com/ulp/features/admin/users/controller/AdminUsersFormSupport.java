package com.ulp.features.admin.users.controller;

import com.ulp.features.admin.departments.dto.DepartmentDtos.DepartmentOption;
import com.ulp.features.admin.departments.service.DepartmentQueryService;
import com.ulp.security.Role;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

import java.util.List;

import static com.ulp.common.IConstant.*;

/**
 * Shared form-rendering helpers for the {@code /admin/users} CRUD and Edit
 * controllers. Loads live department options from {@link DepartmentQueryService}.
 */
@Component
class AdminUsersFormSupport {

    static final String URL_BASE = "/admin/users";

    /** View names — kept here so both controllers reference the same string. */
    static final String VIEW_LIST = "admin/users";
    static final String VIEW_FORM = "admin/users-form";

    // Model attribute keys shared by the create + edit form templates.
    private static final String ATTR_ROLES = "roles";

    private final DepartmentQueryService departmentQueryService;

    AdminUsersFormSupport(DepartmentQueryService departmentQueryService) {
        this.departmentQueryService = departmentQueryService;
    }

    /** Builds the canonical URL for a single admin user. */
    static String userUrl(Long id) {
        return URL_BASE + "/" + id;
    }

    /**
     * Populates the common form-page model attributes (mode, action URL,
     * roles dropdown, departments dropdown, active sidebar tab). Used by
     * both the Create and Edit flows.
     */
    void populateFormModel(Model model, String mode, Long userId) {
        model.addAttribute(ATTR_MODE, mode);
        model.addAttribute(ATTR_FORM_ACTION,
                MODE_CREATE.equals(mode) ? URL_BASE : userUrl(userId));
        model.addAttribute(ATTR_ROLES, Role.values());
        List<DepartmentOption> departments = departmentQueryService.options();
        model.addAttribute(ATTR_DEPARTMENTS, departments);
        model.addAttribute(ATTR_ACTIVE_TAB, TAB_USERS);
    }
}
