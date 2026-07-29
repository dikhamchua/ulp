package com.ulp.features.head.controller;

import com.ulp.features.head.dto.HeadDtos.ApprovalQueueView;
import com.ulp.features.head.dto.HeadDtos.DashboardView;
import com.ulp.features.head.dto.HeadDtos.ReportView;
import com.ulp.features.head.service.HeadClassApprovalService;
import com.ulp.features.head.service.HeadDashboardService;
import com.ulp.features.head.service.HeadReportService;
import com.ulp.security.Roles;
import com.ulp.security.UlpUserDetails;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import static com.ulp.common.IConstant.*;

/**
 * HEAD product shell: dashboard, class approval queue, and department report.
 */
@Controller
@RequestMapping(BASE_HEAD)
@PreAuthorize("hasRole('" + Roles.HEAD + "')")
public class HeadController {

    private final HeadDashboardService dashboardService;
    private final HeadClassApprovalService approvalService;
    private final HeadReportService reportService;

    public HeadController(HeadDashboardService dashboardService,
                          HeadClassApprovalService approvalService,
                          HeadReportService reportService) {
        this.dashboardService = dashboardService;
        this.approvalService = approvalService;
        this.reportService = reportService;
    }

    @GetMapping({"", "/"})
    public String dashboard(@AuthenticationPrincipal UlpUserDetails user, Model model) {
        DashboardView view = dashboardService.load(user.getId());
        model.addAttribute(ATTR_HEAD_DEPARTMENT, view.department());
        model.addAttribute(ATTR_HEAD_KPIS, view.kpis());
        model.addAttribute(ATTR_HEAD_RECENT, view.recentClasses());
        model.addAttribute(ATTR_HEAD_EMPTY, view.emptyDepartment());
        model.addAttribute(ATTR_ACTIVE_TAB, "dashboard");
        return VIEW_HEAD_DASHBOARD;
    }

    @GetMapping("/approvals")
    public String approvals(@AuthenticationPrincipal UlpUserDetails user, Model model) {
        ApprovalQueueView view = approvalService.load(user.getId());
        model.addAttribute(ATTR_HEAD_DEPARTMENT, view.department());
        model.addAttribute(ATTR_HEAD_PENDING_CLASSES, view.pendingClasses());
        model.addAttribute(ATTR_HEAD_EMPTY, view.emptyDepartment());
        model.addAttribute(ATTR_ACTIVE_TAB, "approvals");
        return VIEW_HEAD_APPROVALS;
    }

    @PostMapping("/approvals/{classId}/approve")
    public String approveClass(@PathVariable Long classId,
                               @AuthenticationPrincipal UlpUserDetails user,
                               RedirectAttributes ra) {
        try {
            String className = approvalService.approve(user.getId(), classId);
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_HEAD_CLASS_APPROVED + className);
        } catch (IllegalStateException ex) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, ex.getMessage());
        }
        // AccessDeniedException / EntityNotFoundException bubble to global handler (403/404).
        return "redirect:" + URL_HEAD_APPROVALS;
    }

    @PostMapping("/approvals/{classId}/reject")
    public String rejectClass(@PathVariable Long classId,
                              @RequestParam(required = false) String note,
                              @AuthenticationPrincipal UlpUserDetails user,
                              RedirectAttributes ra) {
        try {
            String className = approvalService.reject(user.getId(), classId, note);
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_HEAD_CLASS_REJECTED + className);
        } catch (IllegalStateException ex) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, ex.getMessage());
        }
        return "redirect:" + URL_HEAD_APPROVALS;
    }

    @GetMapping("/report")
    public String report(@AuthenticationPrincipal UlpUserDetails user, Model model) {
        ReportView view = reportService.load(user.getId());
        model.addAttribute(ATTR_HEAD_DEPARTMENT, view.department());
        model.addAttribute(ATTR_HEAD_REPORT_ROWS, view.rows());
        model.addAttribute(ATTR_HEAD_EMPTY, view.emptyDepartment());
        model.addAttribute(ATTR_ACTIVE_TAB, "report");
        return VIEW_HEAD_REPORT;
    }

    // The department question bank management screen is served by
    // HeadQuestionBankController at /head/question-bank; no handler here.

}
