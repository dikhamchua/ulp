package com.ulp.features.admin.settings.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * DTOs for the {@code /admin/settings/ai/prompts} screen.
 *
 * <p>{@link AiSystemPromptForm} binds both the add and the edit form; an {@code id} of
 * {@code null} means "create". Unlike the provider form there is no masked field —
 * a prompt body carries no secret, so the stored value round-trips verbatim.
 *
 * <p>{@link AiSystemPromptRow} is the read model rendered into the catalog table. Its
 * {@code contentPreview} is truncated by the service, not the template, so the
 * truncation rule lives in one place and stays testable.
 */
public class AiSystemPromptDtos {

    /** Form-binding record for creating and editing a system prompt. */
    public record AiSystemPromptForm(
            Long id,

            @NotBlank(message = "Tên prompt là bắt buộc")
            @Size(max = 100, message = "Tên prompt tối đa 100 ký tự")
            String name,

            @Size(max = 500, message = "Mô tả tối đa 500 ký tự")
            String description,

            @NotBlank(message = "Nội dung prompt là bắt buộc")
            @Size(max = 20000, message = "Nội dung prompt tối đa 20000 ký tự")
            String content,

            boolean enabled
    ) {
        /** Returns an empty form with {@code enabled} pre-checked, as the add panel expects. */
        public static AiSystemPromptForm empty() {
            return new AiSystemPromptForm(null, "", "", "", true);
        }
    }

    /**
     * One row of the prompt catalog table. {@code contentPreview} is a shortened,
     * single-line form of the stored body — the full text is only shown in the edit panel.
     */
    public record AiSystemPromptRow(
            Long id,
            int index,
            String name,
            String description,
            String contentPreview,
            boolean enabled
    ) {
    }
}
