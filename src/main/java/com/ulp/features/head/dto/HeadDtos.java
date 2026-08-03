package com.ulp.features.head.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * DTOs for HEAD department product screens.
 */
public final class HeadDtos {

    private HeadDtos() {
    }

    public record DepartmentSummary(Long id, String code, String name) {
    }

    public record DashboardKpis(
            long classCount,
            long lecturerCount,
            long studentCount,
            long courseCount
    ) {
    }

    public record RecentClassRow(
            Long id,
            String name,
            String code,
            String status,
            String lecturerName,
            LocalDateTime createdAt
    ) {
    }

    public record DashboardView(
            DepartmentSummary department,
            DashboardKpis kpis,
            List<RecentClassRow> recentClasses,
            boolean emptyDepartment
    ) {
    }

    /**
     * One DRAFT class awaiting the department HEAD's review.
     *
     * @param crossDepartmentLecturer true when the lecturer's department differs
     *                                from the class department (or lecturer has none)
     * @param subjectTitle            optional subject display title when bound
     */
    public record PendingClassRow(
            Long classId,
            String className,
            String classCode,
            String lecturerName,
            LocalDateTime createdAt,
            boolean crossDepartmentLecturer,
            String subjectTitle
    ) {
    }

    /** Payload for the HEAD class-approval queue screen. */
    public record ApprovalQueueView(
            DepartmentSummary department,
            List<PendingClassRow> pendingClasses,
            boolean emptyDepartment
    ) {
    }

    public record ReportClassRow(
            Long classId,
            String className,
            String classCode,
            long activeEnrollments,
            BigDecimal avgTestScore,
            BigDecimal avgAssignmentScore
    ) {
    }

    public record ReportView(
            DepartmentSummary department,
            List<ReportClassRow> rows,
            boolean emptyDepartment
    ) {
    }

    public record QuestionBankTestRow(
            Long testId,
            String title,
            String className,
            String type,
            String status
    ) {
    }

    public record QuestionBankTestsView(
            DepartmentSummary department,
            List<QuestionBankTestRow> tests,
            boolean emptyDepartment
    ) {
    }
}
