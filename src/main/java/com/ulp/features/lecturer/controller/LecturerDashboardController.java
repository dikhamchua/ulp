package com.ulp.features.lecturer.controller;

import com.ulp.features.lecturer.dto.LecturerDashboardDtos.TeachingDashboardView;
import com.ulp.features.lecturer.service.LecturerDashboardService;
import com.ulp.security.Roles;
import com.ulp.security.UlpUserDetails;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.ulp.common.IConstant.ATTR_PAGER_PARAMS;
import static com.ulp.common.IConstant.ATTR_TEACHING_CLASS_ROWS;
import static com.ulp.common.IConstant.ATTR_TEACHING_QUERY;
import static com.ulp.common.IConstant.ATTR_TEACHING_SIZE;
import static com.ulp.common.IConstant.ATTR_TEACHING_STATS;
import static com.ulp.common.IConstant.BASE_LECTURER;
import static com.ulp.common.IConstant.DEFAULT_TEACHING_PAGE_SIZE;
import static com.ulp.common.IConstant.URL_LECTURER_DASHBOARD;
import static com.ulp.common.IConstant.VIEW_LECTURER_DASHBOARD;

/**
 * Renders the lecturer teaching dashboard (ULP-9.1).
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET /lecturer} → redirect to {@code /lecturer/dashboard}</li>
 *   <li>{@code GET /lecturer/dashboard?q=&page=&size=} → KPI cards + searchable table</li>
 * </ul>
 *
 * <p>Class-level {@code @PreAuthorize} blocks STUDENT / anonymous callers.
 * Ownership scope is enforced inside {@link LecturerDashboardService}.
 */
@Controller
@RequestMapping(BASE_LECTURER)
@PreAuthorize(Roles.PREAUTH_LECTURER_OR_ABOVE)
public class LecturerDashboardController {

    private static final String REDIRECT_DASHBOARD = "redirect:" + URL_LECTURER_DASHBOARD;

    private final LecturerDashboardService dashboardService;

    public LecturerDashboardController(LecturerDashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    /** Redirects {@code /lecturer} to the teaching dashboard. */
    @GetMapping
    public String root() {
        return REDIRECT_DASHBOARD;
    }

    /**
     * Renders the teaching dashboard for the authenticated lecturer / head / admin.
     * KPI cards ignore search/page; the class table is filtered and paginated.
     */
    @GetMapping("/dashboard")
    public String dashboard(@RequestParam(name = "q", defaultValue = "") String q,
                            @RequestParam(name = "page", defaultValue = "0") int page,
                            @RequestParam(name = "size",
                                    defaultValue = "" + DEFAULT_TEACHING_PAGE_SIZE) int size,
                            @AuthenticationPrincipal UlpUserDetails user,
                            Model model) {
        TeachingDashboardView view = dashboardService.getDashboard(
                user.getId(), user.getRole(), q, page, size);
        // Clamped size from the page object keeps pager links consistent.
        int pageSize = view.classes().getSize();
        model.addAttribute(ATTR_TEACHING_STATS, view.stats());
        model.addAttribute(ATTR_TEACHING_CLASS_ROWS, view.classes());
        model.addAttribute(ATTR_TEACHING_QUERY, q);
        model.addAttribute(ATTR_TEACHING_SIZE, pageSize);
        // Preserve search/size across pager links (page injected by PageWindow).
        model.addAttribute(ATTR_PAGER_PARAMS, pagerParams(q, pageSize));
        return VIEW_LECTURER_DASHBOARD;
    }

    private static Map<String, Object> pagerParams(String q, int size) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (q != null && !q.isBlank()) {
            params.put("q", q.trim());
        }
        // Always keep size so pager never jumps to another default.
        params.put("size", size);
        return params;
    }
}
