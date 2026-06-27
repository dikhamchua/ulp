package com.ulp.features.admin.users.controller;

import com.ulp.entities.User;
import com.ulp.features.admin.users.dto.CreateUserForm;
import com.ulp.features.admin.users.dto.StatusFilter;
import com.ulp.features.admin.users.dto.UserFilter;
import com.ulp.features.admin.users.dto.UserRow;
import com.ulp.features.admin.users.service.AdminUsersReadService;
import com.ulp.features.admin.users.service.AdminUsersWriteService;
import com.ulp.features.admin.users.service.EmailAlreadyUsedException;
import com.ulp.security.Role;
import com.ulp.security.Roles;
import com.ulp.security.UlpUserDetails;
import com.ulp.utils.StringUtils;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import static com.ulp.common.IConstant.*;
import static com.ulp.features.admin.users.controller.AdminUsersFormSupport.URL_BASE;
import static com.ulp.features.admin.users.controller.AdminUsersFormSupport.VIEW_FORM;
import static com.ulp.features.admin.users.controller.AdminUsersFormSupport.VIEW_LIST;

/**
 * MVC controller for the list + create endpoints of the {@code /admin/users}
 * screen.
 *
 * <p>Edit-form endpoints live on {@link AdminUsersEditController}; lifecycle
 * endpoints (activate / deactivate / lock / unlock / reset-password / delete
 * / restore) live on {@link AdminUsersLifecycleController}. All three share
 * the same {@code /admin/users} base mapping and ADMIN role precondition.
 *
 * <p>CSRF protection is provided by Spring Security for every POST.
 * Validation errors render inline on the form templates.
 */
@Controller
@RequestMapping("/admin/users")
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
public class AdminUsersController {

    private static final String REDIRECT_BASE = "redirect:" + URL_BASE;

    // ── Local model attribute keys (specific to this controller) ──
    private static final String ATTR_PAGE            = "page";
    private static final String ATTR_FILTER          = "filter";
    private static final String ATTR_ROLES           = "roles";
    private static final String ATTR_STATUSES        = "statuses";
    private static final String ATTR_CURRENT_USER_ID = "currentUserId";

    // ── Flash messages (Vietnamese UI text) ───────────────────────
    private static final String MSG_USER_CREATED    = "Đã tạo tài khoản ";
    private static final String MSG_EMAIL_DUPLICATE = "Email đã được sử dụng";

    private final AdminUsersReadService readService;
    private final AdminUsersWriteService writeService;
    private final AdminUsersFormSupport formSupport;

    public AdminUsersController(AdminUsersReadService readService,
                                AdminUsersWriteService writeService,
                                AdminUsersFormSupport formSupport) {
        this.readService = readService;
        this.writeService = writeService;
        this.formSupport = formSupport;
    }

    /** Lists users with optional filters (search, role, status, sort). */
    @GetMapping
    public String list(@RequestParam(required = false) String q,
                       @RequestParam(required = false) String role,
                       @RequestParam(required = false) String status,
                       @RequestParam(required = false) String sort,
                       Pageable pageable,
                       @AuthenticationPrincipal UlpUserDetails user,
                       Model model) {
        UserFilter filter = new UserFilter(
                q,
                StringUtils.blankToNull(role),
                StatusFilter.normalize(status),
                StringUtils.blankToNull(sort)
        );
        Page<UserRow> page = readService.list(filter, pageable);

        model.addAttribute(ATTR_PAGE, page);
        model.addAttribute(ATTR_FILTER, filter);
        model.addAttribute(ATTR_ROLES, Role.values());
        model.addAttribute(ATTR_STATUSES, StatusFilter.values());
        model.addAttribute(ATTR_CURRENT_USER_ID, user.getId());
        model.addAttribute(ATTR_ACTIVE_TAB, TAB_USERS);
        return VIEW_LIST;
    }

    /** Renders the create-user form, preserving flashed values from a failed POST. */
    @GetMapping("/new")
    public String createForm(Model model) {
        if (!model.containsAttribute(ATTR_FORM)) {
            model.addAttribute(ATTR_FORM, CreateUserForm.empty());
        }
        formSupport.populateFormModel(model, MODE_CREATE, null);
        return VIEW_FORM;
    }

    /** Submits the create-user form; re-renders inline on error, redirects to list on success. */
    @PostMapping
    public String create(@Valid @ModelAttribute("form") CreateUserForm form,
                         BindingResult result,
                         @AuthenticationPrincipal UlpUserDetails user,
                         Model model,
                         RedirectAttributes ra) {
        if (result.hasErrors()) {
            formSupport.populateFormModel(model, MODE_CREATE, null);
            return VIEW_FORM;
        }
        try {
            User saved = writeService.create(form, user.getId());
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_USER_CREATED + saved.getEmail());
            return REDIRECT_BASE;
        } catch (EmailAlreadyUsedException ex) {
            result.rejectValue("email", "email.duplicate", MSG_EMAIL_DUPLICATE);
            formSupport.populateFormModel(model, MODE_CREATE, null);
            return VIEW_FORM;
        }
    }
}
