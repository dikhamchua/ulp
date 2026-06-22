package com.ulp.admin.dto;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** DTOs cho man hinh Admin Dashboard. */
public class AdminDashboardDtos {

    /** 4 con so chinh hien thi tren 4 stat card. */
    public record DashboardStats(
            Long userCount,
            Long classCount,
            Long departmentCount,
            Long courseCount
    ) {}

    /** 1 dong trong chart user-by-role (donut). */
    public record UserRoleCount(String role, Long count) {
        /** Mau gradient cho slice donut, theo role. */
        public String color() {
            return switch (role) {
                case "STUDENT" -> "#42A5F5";
                case "LECTURER" -> "#26A69A";
                case "HEAD" -> "#7E57C2";
                case "ADMIN" -> "#EF5350";
                default -> "#9AA0AB";
            };
        }

        /** Label tieng Viet cho legend. */
        public String displayRole() {
            return switch (role) {
                case "STUDENT" -> "Sinh viên";
                case "LECTURER" -> "Giảng viên";
                case "HEAD" -> "Trưởng bộ môn";
                case "ADMIN" -> "Quản trị viên";
                default -> role;
            };
        }
    }

    /** 1 dong trong bang "5 lop moi nhat" / recent activity. */
    public record RecentClass(
            Long id,
            String name,
            String code,
            String status,
            LocalDateTime createdAt,
            String lecturerName
    ) {
        private static final DateTimeFormatter DF =
                DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.forLanguageTag("vi-VN"));

        public String displayCreatedAt() {
            return createdAt != null ? createdAt.format(DF) : "—";
        }

        /** Label tieng Viet cho status lop. */
        public String displayStatus() {
            return switch (status) {
                case "UPCOMING" -> "Sắp khai giảng";
                case "ACTIVE" -> "Đang hoạt động";
                case "COMPLETED" -> "Đã kết thúc";
                case "CANCELLED" -> "Đã huỷ";
                default -> status;
            };
        }
    }
}
