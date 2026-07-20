package com.ulp.features.admin.controller;

import com.ulp.features.admin.dto.AdminDashboardDtos.DashboardStats;
import com.ulp.features.admin.dto.AdminDashboardDtos.RecentClass;
import com.ulp.features.admin.dto.AdminDashboardDtos.UserRoleCount;
import com.ulp.features.admin.service.AdminDashboardService;
import com.ulp.security.Roles;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;

import static com.ulp.common.IConstant.*;

/**
 * MVC controller for the system administration panel.
 * Access is restricted to the {@code ADMIN} role only — may be relaxed to include
 * {@code HEAD} in a future sprint once per-department dashboards are available.
 *
 * <p>URL pattern: {@code /admin/{tab}} — five sidebar tabs:
 * <ul>
 *   <li>{@code /dashboard} — platform statistics, role breakdown chart, and recent
 *       classes (Sprint 2 wireframe).</li>
 *   <li>{@code /settings}  — settings index page (links to Email, General, etc.).</li>
 *   <li>{@code /classes} — placeholder view; real data wired later.</li>
 *   <li>{@code /departments} — handled by
 *       {@link com.ulp.features.admin.departments.controller.AdminDepartmentsController}.</li>
 * </ul>
 *
 * <p>The {@code /admin/settings/email} sub-tab is handled by
 * {@link com.ulp.features.admin.settings.controller.EmailSettingsController}.
 */
@Controller
@RequestMapping("/admin")
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
public class AdminController {

    // ── Paths ─────────────────────────────────────────────────────
    private static final String REDIRECT_DASHBOARD = "redirect:/admin/dashboard";

    // ── View names ────────────────────────────────────────────────
    private static final String VIEW_DASHBOARD   = "admin/dashboard";
    private static final String VIEW_SETTINGS    = "admin/settings";
    private static final String VIEW_PLACEHOLDER = "admin/placeholder";

    // ── Local model attribute keys ────────────────────────────────
    private static final String ATTR_STATS           = "stats";
    private static final String ATTR_ROLES_BREAKDOWN = "rolesBreakdown";
    private static final String ATTR_RECENT_CLASSES  = "recentClasses";

    // ── Placeholder tab keys ──────────────────────────────────────
    private static final String TAB_CLASSES = "classes";

    // ── Placeholder labels (Vietnamese sidebar text) ──────────────
    private static final String LABEL_CLASSES = "Lớp học";

    /** Recent-classes panel size on the dashboard. */
    private static final int RECENT_CLASSES_LIMIT = 5;

    private final AdminDashboardService dashboardService;

    public AdminController(AdminDashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    /** Redirects /admin to the default dashboard tab. */
    @GetMapping
    public String root() {
        return REDIRECT_DASHBOARD;
    }

    /** Renders the admin dashboard with platform stats and recent classes. */
    @GetMapping("/dashboard")
    public String dashboard(Model model) {
        DashboardStats stats = dashboardService.stats();
        List<UserRoleCount> rolesBreakdown = dashboardService.usersByRole();
        List<RecentClass> recentClasses = dashboardService.recentClasses(RECENT_CLASSES_LIMIT);

        model.addAttribute(ATTR_STATS, stats);
        model.addAttribute(ATTR_ROLES_BREAKDOWN, rolesBreakdown);
        model.addAttribute(ATTR_RECENT_CLASSES, recentClasses);
        populateSidebar(model, TAB_DASHBOARD);
        return VIEW_DASHBOARD;
    }

    /** Renders the settings index page (links to Email, OAuth, General sub-tabs). */
    @GetMapping("/settings")
    public String settingsIndex(Model model) {
        populateSidebar(model, TAB_SETTINGS);
        return VIEW_SETTINGS;
    }

    /** Renders a placeholder view for tabs not yet implemented. */
    @GetMapping("/classes")
    public String placeholder(Model model) {
        populateSidebar(model, TAB_CLASSES);
        model.addAttribute(ATTR_PLACEHOLDER_TAB, TAB_CLASSES);
        model.addAttribute(ATTR_PLACEHOLDER_LABEL, LABEL_CLASSES);
        return VIEW_PLACEHOLDER;
    }

    private void populateSidebar(Model model, String activeTab) {
        model.addAttribute(ATTR_ACTIVE_TAB, activeTab);
    }
}
