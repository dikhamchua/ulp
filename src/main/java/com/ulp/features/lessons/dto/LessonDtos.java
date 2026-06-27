package com.ulp.features.lessons.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DTOs for the lesson CRUD feature on the lessons tab (ULP-4.0b).
 *
 * <p>The AJAX response envelope is reused from
 * {@link com.ulp.features.lessons.dto.SectionDtos.AjaxResult} to keep a
 * single transport contract across the entire lessons tab.
 */
public final class LessonDtos {

    private LessonDtos() {
        // utility holder
    }

    /** A single lesson as rendered in the lessons-tab list. */
    public record LessonRow(Long id, String title, String status, short displayOrder) {
    }

    /**
     * Form payload used by the create / edit endpoints. {@code contentHtml}
     * is the raw Quill output — the service runs it through
     * {@link com.ulp.common.HtmlSanitizer} before persistence.
     */
    public record LessonForm(
            @NotBlank(message = "Tiêu đề bài giảng không được để trống")
            @Size(max = 300, message = "Tiêu đề tối đa 300 ký tự")
            String title,

            @Pattern(regexp = "DRAFT|PUBLISHED",
                    message = "Trạng thái không hợp lệ")
            String status,

            String contentHtml
    ) {
    }

    /** Payload for the reorder endpoint — full ordered id list. */
    public record LessonReorderRequest(List<Long> orderedIds) {
    }

    /**
     * One audit-log row as rendered in the lesson history tab. Maps a
     * {@link com.ulp.entities.LessonActivity} entity onto the fields the
     * Thymeleaf template consumes.
     */
    public record LessonActivityRow(
            Long id,
            String type,
            String typeLabel,
            String description,
            LocalDateTime createdAt
    ) {
    }

    /**
     * One attachment row as rendered in the lesson edit page and returned by
     * the upload / list endpoints (ULP-4.0c).
     *
     * @param id               attachment row id
     * @param originalFilename filename as the lecturer uploaded it
     * @param mimeType         resolved MIME type
     * @param sizeBytes        file size in bytes (JS formats to KB/MB)
     * @param uploadedAt       insert timestamp
     * @param downloadUrl      canonical download URL (lecturer + student share it)
     */
    public record LessonAttachmentRow(
            Long id,
            String originalFilename,
            String mimeType,
            long sizeBytes,
            LocalDateTime uploadedAt,
            String downloadUrl
    ) {
    }
}
