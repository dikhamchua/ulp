package com.ulp.features.admin.permissions.controller;

import com.ulp.features.admin.permissions.service.PermissionMatrixService;
import com.ulp.security.UlpUserDetails;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import static com.ulp.common.IConstant.*;

/**
 * MVC controller for the role x permission matrix screen at {@code /admin/permissions}.
 *
 * <p>Authorization is layered: the {@code /admin/**} matcher in {@code SecurityConfig}
 * still restricts this controller to the ADMIN role, and the permission check below adds
 * a second, finer gate. {@code system.permissions} lives in the SYSTEM group, which the
 * permission guard refuses to detach from ADMIN — so this screen can never be locked
 * away from every administrator.
 *
 * <p>A guard rejection is caught here and redirected back as a flash error rather than
 * left to the global 403 handler: the admin needs to stay on the matrix and see why the
 * cell would not change. The rejected row is untouched and nothing is audited.
 */
@Controller
@RequestMapping(URL_ADMIN_PERMISSIONS)
@PreAuthorize("hasAuthority('PERM_system.permissions')")
public class AdminPermissionsController {

    private static final String REDIRECT_BASE = "redirect:" + URL_ADMIN_PERMISSIONS;

    private final PermissionMatrixService matrixService;

    public AdminPermissionsController(PermissionMatrixService matrixService) {
        this.matrixService = matrixService;
    }

    /** Renders the role x permission matrix grouped by permission group. */
    @GetMapping
    public String matrix(Model model) {
        model.addAttribute(ATTR_PERMISSION_MATRIX, matrixService.loadMatrix());
        model.addAttribute(ATTR_ACTIVE_TAB, TAB_PERMISSIONS);
        return VIEW_ADMIN_PERMISSIONS_ROLES;
    }

    /**
     * Attaches or detaches one role x permission cell.
     *
     * <p>{@code granted} carries the checkbox's new state: true attaches, false detaches.
     */
    @PostMapping
    public String toggle(@RequestParam String roleCode,
                         @RequestParam String featureKey,
                         @RequestParam(defaultValue = "false") boolean granted,
                         @AuthenticationPrincipal UlpUserDetails user,
                         RedirectAttributes ra) {
        try {
            if (granted) {
                matrixService.attach(roleCode, featureKey, user.getId());
                ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_PERMISSION_ATTACHED);
            } else {
                matrixService.detach(roleCode, featureKey, user.getId());
                ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_PERMISSION_DETACHED);
            }
        } catch (AccessDeniedException ex) {
            // Core-permission rejection: keep the admin on the matrix with the reason.
            ra.addFlashAttribute(ATTR_FLASH_ERROR, ex.getMessage());
        }
        return REDIRECT_BASE;
    }
}
