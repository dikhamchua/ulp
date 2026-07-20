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

    public record LecturerOption(Long id, String fullName, String email) {
    }

    public record AssignClassRow(
            Long classId,
            String className,
            String classCode,
            Long lecturerId,
            String lecturerName
    ) {
    }

    public record AssignView(
            DepartmentSummary department,
            List<AssignClassRow> classRows,
            List<LecturerOption> lecturers,
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
}
