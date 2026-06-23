package com.ulp.admin.dto;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** DTOs for the Admin Dashboard screen. */
public class AdminDashboardDtos {

    /** The four key metrics displayed on the four stat cards. */
    public record DashboardStats(
            Long userCount,
            Long classCount,
            Long departmentCount,
            Long courseCount
    ) {}

    /** A single row in the user-by-role donut chart. */
    public record UserRoleCount(String role, Long count) {
        /**
         * Returns the gradient color for this role's donut slice.
         *
         * @return a hex color string corresponding to the role
         */
        public String color() {
            return switch (role) {
                case "STUDENT" -> "#42A5F5";
                case "LECTURER" -> "#26A69A";
                case "HEAD" -> "#7E57C2";
                case "ADMIN" -> "#EF5350";
                default -> "#9AA0AB";
            };
        }

        /**
         * Returns the display label for this role, suitable for use in the chart legend.
         *
         * @return a human-readable role name
         */
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

    /** A single row in the "5 most recent classes" / recent-activity table. */
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

        /**
         * Returns {@code createdAt} formatted as {@code dd/MM/yyyy HH:mm},
         * or {@code "—"} if the value is {@code null}.
         *
         * @return formatted creation date/time string
         */
        public String displayCreatedAt() {
            return createdAt != null ? createdAt.format(DF) : "—";
        }

        /**
         * Returns the display label for the class status, suitable for UI rendering.
         *
         * @return a human-readable status string
         */
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
