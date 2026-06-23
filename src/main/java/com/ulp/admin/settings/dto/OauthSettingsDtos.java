package com.ulp.admin.settings.dto;

import jakarta.validation.constraints.Size;

/**
 * DTOs for the {@code /admin/settings/oauth} screen.
 *
 * <p>{@link OauthSettingsForm} is the form-binding object for both the GET
 * (re-render) and POST (save) requests. All fields are optional from a
 * Bean-Validation point of view — leaving every field blank means
 * "OAuth login is disabled". The controller performs an additional
 * cross-field check (when {@code googleClientId} is non-blank the matching
 * {@code googleClientSecret} must also be present).
 *
 * <p>The {@code googleClientSecret} field is loaded and rendered in plain
 * form so admins can review what is currently stored. On save, a blank
 * value means "keep the existing secret unchanged".
 */
public class OauthSettingsDtos {

    /**
     * Form-binding record for {@code /admin/settings/oauth}.
     *
     * <p>Validation is intentionally lenient at the field level because the
     * empty-form state is valid (it means "disable OAuth login"). The size
     * caps protect against pathological payloads only.
     */
    public record OauthSettingsForm(
            @Size(max = 255, message = "Client ID quá dài")
            String googleClientId,

            /** Not validated — a blank value means "keep the existing secret". */
            @Size(max = 255, message = "Client Secret quá dài")
            String googleClientSecret,

            @Size(max = 255, message = "Scope quá dài")
            String googleScope
    ) {
    }
}
