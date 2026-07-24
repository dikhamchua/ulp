package com.ulp.features.admin.settings.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * DTOs for the {@code /admin/settings/general} screen.
 *
 * <p>{@link GeneralSettingsForm} is the form-binding object for both the GET
 * (re-render) and POST (save) requests. It maps the four {@code GENERAL}-group
 * rows seeded by {@code V1__init_schema}: {@code site.name},
 * {@code site.description}, {@code site.logo_url}, {@code site.contact_email}.
 */
public class GeneralSettingsDtos {

    /**
     * Form-binding record for {@code /admin/settings/general}.
     *
     * <p>Only {@code siteName} is required — it is the human-facing platform
     * title. The other fields are optional; blank values are stored as-is so
     * an admin can intentionally clear the logo or contact email. The size
     * caps protect against pathological payloads.
     */
    public record GeneralSettingsForm(
            @NotBlank(message = "Tên hệ thống là bắt buộc")
            @Size(max = 255, message = "Tên hệ thống quá dài")
            String siteName,

            @Size(max = 500, message = "Mô tả quá dài")
            String siteDescription,

            @Size(max = 500, message = "Đường dẫn logo quá dài")
            String siteLogoUrl,

            /** Optional — validated only when non-blank (see empty-string carve-out). */
            @Email(message = "Email liên hệ không hợp lệ")
            @Size(max = 255, message = "Email liên hệ quá dài")
            String siteContactEmail
    ) {
    }
}
