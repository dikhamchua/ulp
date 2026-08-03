package com.ulp.features.subjects.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * DTOs for subject catalog screens and class-form subject picker.
 */
public final class SubjectDtos {

    private SubjectDtos() {
    }

    /**
     * Create/edit form for HEAD and ADMIN subject management.
     *
     * <p>{@code departmentId} is required on ADMIN create; HEAD create ignores
     * the submitted value and stamps the resolved HEAD department instead.
     */
    public record SubjectForm(
            @NotBlank(message = "Mã môn học không được để trống")
            @Size(max = 30, message = "Mã môn học tối đa 30 ký tự")
            String code,

            @NotBlank(message = "Tên môn học không được để trống")
            @Size(max = 300, message = "Tên môn học tối đa 300 ký tự")
            String title,

            @Size(max = 65535, message = "Mô tả quá dài")
            String description,

            boolean active,

            Long departmentId
    ) {
        public static SubjectForm empty() {
            return new SubjectForm("", "", "", true, null);
        }

        public static SubjectForm emptyForDepartment(Long departmentId) {
            return new SubjectForm("", "", "", true, departmentId);
        }
    }

    /** List-row projection for subject management tables. */
    public record SubjectRow(
            Long id,
            String code,
            String title,
            String description,
            boolean active,
            Long departmentId,
            String departmentCode,
            String departmentName
    ) {
    }

    /**
     * Dropdown option for the lecturer class form.
     *
     * @param label display text like {@code [CNTT] PRJ301 — Lập trình Java}
     */
    public record SubjectOption(
            Long id,
            Long departmentId,
            String code,
            String title,
            String departmentCode,
            String label
    ) {
    }

    /** Lightweight department option for ADMIN create form. */
    public record DepartmentOption(Long id, String code, String name) {
    }

    /** One live sample-chapter row on the subject outline tab. */
    public record ChapterRow(
            Long id,
            String title,
            short displayOrder
    ) {
    }

    /**
     * Create/rename form for a sample chapter title.
     *
     * <p>Used with {@code @Valid} on POST handlers; blank titles stay as field errors.
     */
    public record ChapterTitleForm(
            @NotBlank(message = "Tên chương không được để trống")
            @Size(max = 200, message = "Tên chương tối đa 200 ký tự")
            String title
    ) {
        public static ChapterTitleForm empty() {
            return new ChapterTitleForm("");
        }
    }
}
