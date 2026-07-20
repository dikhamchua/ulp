package com.ulp.features.admin.departments.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * DTOs for the {@code /admin/departments} screen.
 */
public final class DepartmentDtos {

    private DepartmentDtos() {
    }

    /**
     * Optional list filters bound from GET query params.
     * Blank values mean "no filter" / default sort.
     */
    public record DepartmentFilter(String q, String status, String sort) {

        public static DepartmentFilter empty() {
            return new DepartmentFilter(null, null, null);
        }
    }

    /**
     * List-row projection for the departments table.
     *
     * @param headLabel display name of the assigned head, or null when unassigned
     */
    public record DepartmentRow(
            Long id,
            String code,
            String name,
            String description,
            boolean active,
            Long headUserId,
            String headLabel,
            LocalDateTime createdAt
    ) {
    }

    /**
     * Create/edit form. Head assignment is a separate action on the list/form.
     */
    public record DepartmentForm(
            @NotBlank(message = "Tên bộ môn không được để trống")
            @Size(max = 200, message = "Tên bộ môn tối đa 200 ký tự")
            String name,

            @NotBlank(message = "Mã bộ môn không được để trống")
            @Size(max = 20, message = "Mã bộ môn tối đa 20 ký tự")
            String code,

            @Size(max = 65535, message = "Mô tả quá dài")
            String description,

            boolean active,

            Long headUserId
    ) {
        public static DepartmentForm empty() {
            return new DepartmentForm(null, null, null, true, null);
        }
    }

    /** Dropdown option for head candidate picker. */
    public record HeadCandidate(Long id, String fullName, String email, String role) {
    }

    /** Lightweight option for admin user-form department dropdown. */
    public record DepartmentOption(Long id, String code, String name) {
    }
}
