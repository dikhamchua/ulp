package com.ulp.admin.settings.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * DTOs cho man hinh /admin/settings/email.
 *
 * <p>{@link EmailSettingsForm} la form binding cho GET/POST. Field
 * {@code password} la {@link String} khong co constraint — empty hoac
 * placeholder {@link #MASKED} co nghia "giu nguyen mat khau cu".
 *
 * <p>{@link TestResult} la JSON response cua POST /test.
 */
public class EmailSettingsDtos {

    /**
     * Placeholder hien thi thay cho gia tri that cua secret settings.
     * Shared constant — service/test deu reference de tranh duplicate
     * literal {@code "********"}.
     */
    public static final String MASKED = "********";

    /** Form binding cho /admin/settings/email (GET re-render + POST save). */
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

            /** Khong validate — empty hoac MASKED nghia la "giu nguyen". */
            String password,

            @NotBlank(message = "From Name là bắt buộc")
            @Size(max = 100)
            String fromName,

            @NotBlank(message = "From Email là bắt buộc")
            @Email(message = "From Email không hợp lệ")
            String fromEmail,

            /** Optional — chap nhan empty; neu co thi phai la email hop le. */
            @Email(message = "Reply-To không hợp lệ")
            String replyTo
    ) {
    }

    /**
     * Ket qua POST /admin/settings/email/test — serialize ra JSON.
     * {@code @JsonInclude(NON_NULL)} de spec scenario "ok=true" tra ve
     * {@code {"ok":true}} chu khong phai {@code {"ok":true,"error":null}}.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TestResult(boolean ok, String error) {
    }
}

