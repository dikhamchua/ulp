package com.ulp.admin.controller;

import com.ulp.admin.dto.AdminDashboardDtos.DashboardStats;
import com.ulp.admin.dto.AdminDashboardDtos.RecentClass;
import com.ulp.admin.dto.AdminDashboardDtos.UserRoleCount;
import com.ulp.admin.service.AdminDashboardService;
import com.ulp.auth.Roles;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;

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
 *   <li>{@code /users}, {@code /departments}, {@code /classes}
 *       — placeholder views; real data wired in Sprint 6.</li>
 * </ul>
 *
 * <p>The {@code /admin/settings/email} sub-tab is handled by
 * {@link com.ulp.admin.settings.controller.EmailSettingsController}.
 */
@Controller
@RequestMapping("/admin")
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
public class AdminController {

    private final AdminDashboardService dashboardService;

    public AdminController(AdminDashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping
    public String root() {
        return "redirect:/admin/dashboard";
    }

    @GetMapping("/dashboard")
    public String dashboard(Model model) {
        DashboardStats stats = dashboardService.stats();
        List<UserRoleCount> rolesBreakdown = dashboardService.usersByRole();
        List<RecentClass> recentClasses = dashboardService.recentClasses(5);

        model.addAttribute("stats", stats);
        model.addAttribute("rolesBreakdown", rolesBreakdown);
        model.addAttribute("recentClasses", recentClasses);
        populateSidebar(model, "dashboard");
        return "admin/dashboard";
    }

    @GetMapping("/settings")
    public String settingsIndex(Model model) {
        populateSidebar(model, "settings");
        return "admin/settings";
    }

    @GetMapping({"/departments", "/classes"})
    public String placeholder(HttpServletRequest request, Model model) {
        String path = request.getRequestURI();
        String tab = path.substring(path.lastIndexOf('/') + 1);
        populateSidebar(model, tab);
        model.addAttribute("placeholderTab", tab);
        model.addAttribute("placeholderLabel", labelFor(tab));
        return "admin/placeholder";
    }

    private void populateSidebar(Model model, String activeTab) {
        model.addAttribute("activeTab", activeTab);
    }

    private static String labelFor(String tab) {
        return switch (tab) {
            case "departments" -> "Bộ môn";
            case "classes" -> "Lớp học";
            default -> tab;
        };
    }
}
