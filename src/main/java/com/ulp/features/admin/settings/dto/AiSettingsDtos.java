package com.ulp.features.admin.settings.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * DTOs for the {@code /admin/settings/ai} screen.
 *
 * <p>{@link AiProviderForm} binds both the add and the edit form. On edit, a blank
 * {@code apiKey} — or the exact sentinel {@link #MASKED} — means "keep the stored key",
 * mirroring how {@code EmailSettingsService} treats the SMTP password.
 *
 * <p>{@link AiProviderRow} is the read model rendered into the table. It deliberately
 * carries no real key: the {@code maskedKey} field is a fixed run of dots, so the API
 * key never reaches the HTML source. The real value is fetched separately through
 * {@code GET /admin/settings/ai/{id}/key}, which returns {@link RevealKeyResult}.
 */
public class AiSettingsDtos {

    /**
     * Sentinel meaning "keep the stored API key". Shared with the service and the
     * templates so the literal is defined once.
     */
    public static final String MASKED = "********";

    /** Form-binding record for creating and editing a provider. */
    public record AiProviderForm(
            Long id,

            @NotBlank(message = "Tên provider là bắt buộc")
            @Size(max = 100, message = "Tên provider tối đa 100 ký tự")
            String name,

            @NotBlank(message = "Base URL là bắt buộc")
            @Size(max = 500, message = "Base URL tối đa 500 ký tự")
            @Pattern(regexp = "^https?://.+", message = "Base URL phải bắt đầu bằng http:// hoặc https://")
            String baseUrl,

            @NotBlank(message = "Model là bắt buộc")
            @Size(max = 150, message = "Model tối đa 150 ký tự")
            String model,

            /**
             * Required when creating. On edit a blank value or {@link #MASKED} keeps the
             * stored key, so the constraint cannot live here — the service enforces
             * "required on create" and reports it as an inline field error.
             */
            @Size(max = 500, message = "API key tối đa 500 ký tự")
            String apiKey,

            boolean enabled
    ) {
        /** Returns an empty form with {@code enabled} pre-checked, as the add panel expects. */
        public static AiProviderForm empty() {
            return new AiProviderForm(null, "", "", "", "", true);
        }
    }

    /**
     * One row of the provider table. {@code maskedKey} is always a constant dot run —
     * no character of the real key is ever placed in this DTO.
     */
    public record AiProviderRow(
            Long id,
            int index,
            String name,
            String model,
            String baseUrl,
            String maskedKey,
            boolean enabled
    ) {
    }

    /**
     * JSON body of {@code GET /admin/settings/ai/{id}/key} — the on-demand reveal used
     * by the eye and copy buttons.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RevealKeyResult(boolean ok, String apiKey, String error) {

        /** Builds a successful reveal carrying the plain key. */
        public static RevealKeyResult ok(String apiKey) {
            return new RevealKeyResult(true, apiKey, null);
        }

        /** Builds a failed reveal carrying a message safe to toast. */
        public static RevealKeyResult fail(String error) {
            return new RevealKeyResult(false, null, error);
        }
    }

    /**
     * JSON body of {@code POST /admin/settings/ai/{id}/test} — the outcome of the
     * single "ping" chat message sent to one specific provider.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TestResult(boolean ok, String error) {
    }
}
