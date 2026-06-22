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
 * Controller cho man hinh quan tri he thong (Admin).
 * Chi ADMIN moi truy cap duoc — co the noi long sang HEAD o sprint sau
 * khi co dashboard bo mon rieng.
 *
 * <p>URL pattern: {@code /admin/{tab}} — 5 mục sidebar:
 * <ul>
 *   <li>{@code /dashboard} — stat + chart + recent (Sprint 2 wireframe).</li>
 *   <li>{@code /users}, {@code /departments}, {@code /classes}, {@code /settings}
 *       — placeholder, Sprint 6 noi data that.</li>
 * </ul>
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

    @GetMapping({"/users", "/departments", "/classes", "/settings"})
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
            case "dashboard" -> "Dashboard";
            case "users" -> "Tài khoản";
            case "departments" -> "Bộ môn";
            case "classes" -> "Lớp học";
            case "settings" -> "Cài đặt hệ thống";
            default -> tab;
        };
    }
}
