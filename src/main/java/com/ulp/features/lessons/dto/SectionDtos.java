package com.ulp.features.lessons.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DTOs for the section CRUD feature on the lessons tab (ULP-4.0a).
 *
 * <p>All section-related transport types are co-located in this single file
 * — the contract surface is small and stays comprehensible side by side.
 */
public final class SectionDtos {

    private SectionDtos() {
        // utility holder
    }

    /** A single section as rendered in the lessons tab list. */
    public record SectionRow(Long id, String title, short displayOrder) {
    }

    /**
     * Form payload used by the create / rename endpoints. Carries the
     * minimum a lecturer can edit on a section today (title only).
     */
    public record SectionForm(
            @NotBlank(message = "Tiêu đề chương không được để trống")
            @Size(max = 200, message = "Tiêu đề tối đa 200 ký tự")
            String title
    ) {
    }

    /** Payload for the reorder endpoint — full ordered id list. */
    public record ReorderRequest(List<Long> orderedIds) {
    }

    /** Uniform AJAX response envelope used by every JSON endpoint here. */
    public record AjaxResult(boolean ok, String message, Object data) {
        public static AjaxResult success(Object data) {
            return new AjaxResult(true, null, data);
        }

        public static AjaxResult success() {
            return new AjaxResult(true, null, null);
        }

        public static AjaxResult failure(String message) {
            return new AjaxResult(false, message, null);
        }
    }

    /**
     * One audit-log row as rendered in the section history tab. Maps a
     * {@link com.ulp.entities.SectionActivity} entity onto the fields the
     * Thymeleaf template consumes — {@code typeLabel} is the Vietnamese
     * label shown in the {@code .detail-type-badge}, {@code description}
     * is the long form, and {@code createdAt} drives the timestamp column.
     */
    public record ActivityRow(
            Long id,
            String type,
            String typeLabel,
            String description,
            LocalDateTime createdAt
    ) {
    }
}

