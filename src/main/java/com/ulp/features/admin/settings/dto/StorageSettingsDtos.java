package com.ulp.features.admin.settings.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * DTOs for the {@code /admin/settings/storage} screen.
 *
 * <p>{@link StorageSettingsForm} binds the GET/POST form.
 * {@code secretAccessKey} empty or {@link #MASKED} means "keep existing".
 *
 * <p>{@link TestResult} is the JSON body of {@code POST .../test}.
 */
public class StorageSettingsDtos {

    /** Placeholder for secret settings — same sentinel as SMTP password. */
    public static final String MASKED = "********";

    /** Form-binding record for storage settings. */
    public record StorageSettingsForm(
            @NotBlank(message = "Nhà cung cấp là bắt buộc")
            @Pattern(regexp = "local|r2", message = "Nhà cung cấp phải là local hoặc r2")
            String provider,

            @Size(max = 64)
            String accountId,

            @Size(max = 255)
            String accessKeyId,

            /** Not validated — blank or {@link #MASKED} keeps the stored secret. */
            String secretAccessKey,

            @Size(max = 255)
            String bucket,

            @Size(max = 512)
            String endpoint,

            @Size(max = 64)
            String region
    ) {
    }

    /** Result of {@code POST /admin/settings/storage/test}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TestResult(boolean ok, String error) {
    }
}
