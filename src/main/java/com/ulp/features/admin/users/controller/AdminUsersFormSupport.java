package com.ulp.features.admin.users.controller;

import com.ulp.features.admin.users.dto.DepartmentReference;
import com.ulp.security.Role;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

import static com.ulp.common.IConstant.*;

/**
 * Shared form-rendering helpers for the {@code /admin/users} CRUD and Edit
 * controllers. Extracted as a {@code @Component} (rather than a static
 * utility) so the future Sprint 6 wiring — {@code DepartmentRepository}
 * injection in place of {@link DepartmentReference#DEFAULT_LIST} — drops in
 * without touching either controller's call sites.
 */
@Component
class AdminUsersFormSupport {

    static final String URL_BASE = "/admin/users";

    /** View names — kept here so both controllers reference the same string. */
    static final String VIEW_LIST = "admin/users";
    static final String VIEW_FORM = "admin/users-form";

    // Model attribute keys shared by the create + edit form templates.
    private static final String ATTR_ROLES       = "roles";
    private static final String ATTR_DEPARTMENTS = "departments";

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
        // TODO Sprint 6: replace with DepartmentRepository.findAll() once Departments capability ships.
        model.addAttribute(ATTR_DEPARTMENTS, DepartmentReference.DEFAULT_LIST);
        model.addAttribute(ATTR_ACTIVE_TAB, TAB_USERS);
    }
}
