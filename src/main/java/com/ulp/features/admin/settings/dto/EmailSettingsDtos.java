package com.ulp.features.admin.settings.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * DTOs for the {@code /admin/settings/email} screen.
 *
 * <p>{@link EmailSettingsForm} is the form-binding object used for both the
 * GET (re-render) and POST (save) requests. The {@code password} field is a
 * plain {@link String} with no validation constraint — an empty value or the
 * sentinel {@link #MASKED} means "keep the existing password unchanged".
 *
 * <p>{@link TestResult} is the JSON response body returned by
 * {@code POST /admin/settings/email/test}.
 */
public class EmailSettingsDtos {

    /**
     * Placeholder displayed in place of the actual value for secret settings.
     * Shared constant — both the service and test code reference this to avoid
     * duplicating the literal {@code "********"}.
     */
    public static final String MASKED = "********";

    /** Form-binding record for {@code /admin/settings/email} (GET re-render and POST save). */
    public record EmailSettingsForm(
            @NotBlank(message = "Host là bắt buộc")
            @Size(max = 255)
            String host,

            @NotNull(message = "Port là bắt buộc")
            @Min(value = 1, message = "Port phải từ 1 đến 65535")
            @Max(value = 65535, message = "Port phải từ 1 đến 65535")
            Integer port,

            @NotBlank(message = "Encryption là bắt buộc")
            @Pattern(regexp = "none|tls|ssl", message = "Encryption phải là none, tls hoặc ssl")
            String encryption,

            @NotBlank(message = "Username là bắt buộc")
            @Size(max = 255)
            String username,

            /** Not validated — an empty value or {@link #MASKED} means "keep the existing password". */
            String password,

            @NotBlank(message = "From Name là bắt buộc")
            @Size(max = 100)
            String fromName,

            @NotBlank(message = "From Email là bắt buộc")
            @Email(message = "From Email không hợp lệ")
            String fromEmail,

            /** Optional — accepts empty; if provided, must be a valid email address. */
            @Email(message = "Reply-To không hợp lệ")
            String replyTo
    ) {
    }

    /**
     * Result of {@code POST /admin/settings/email/test} — serialized as JSON.
     * {@code @JsonInclude(NON_NULL)} ensures that a success response serializes
     * to {@code {"ok":true}} rather than {@code {"ok":true,"error":null}}.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TestResult(boolean ok, String error) {
    }
}

