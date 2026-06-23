package com.ulp.admin.settings.service;

import com.ulp.admin.settings.dto.OauthSettingsDtos.OauthSettingsForm;
import com.ulp.shared.settings.SystemSettingGroups;
import com.ulp.shared.settings.entity.SystemSetting;
import com.ulp.shared.settings.repository.SystemSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Service for managing OAuth provider settings (Google client id / secret /
 * scope) in the admin panel: loading the current configuration, persisting
 * changes, and reporting whether Google sign-in is currently enabled.
 *
 * <p>Secrets are stored and returned in plain form so the admin can review
 * them in the UI. The page is protected by {@code @PreAuthorize("hasRole('ADMIN')")},
 * which is the only access control around the credential.
 *
 * <p>{@link #save} is {@code @Transactional} — every upsert runs inside a
 * single transaction and is rolled back atomically on failure.
 *
 * <p>Database schema note: the {@code oauth.google.client_id} and
 * {@code oauth.google.client_secret} rows were seeded by {@code V1__init_schema}.
 * The {@code oauth.google.scope} row is created lazily on first save if it
 * does not yet exist.
 */
@Service
public class OauthSettingsService {

    private static final Logger log = LoggerFactory.getLogger(OauthSettingsService.class);

    private static final String GROUP = SystemSettingGroups.OAUTH;

    public static final String KEY_GOOGLE_CLIENT_ID = "oauth.google.client_id";
    public static final String KEY_GOOGLE_CLIENT_SECRET = "oauth.google.client_secret";
    public static final String KEY_GOOGLE_SCOPE = "oauth.google.scope";

    /** Default Google OAuth scopes used when the admin leaves the field blank. */
    public static final String DEFAULT_SCOPE = "openid,profile,email";

    private final SystemSettingsRepository repository;

    public OauthSettingsService(SystemSettingsRepository repository) {
        this.repository = repository;
    }

    /**
     * Loads the current OAuth settings from the database.
     *
     * <p>The Google client secret is returned as-is (no masking) so the
     * admin can see exactly what is currently stored. Treat the rendered
     * page as confidential — only ADMIN-role users can access it.
     *
     * @return an {@link OauthSettingsForm} populated with the current settings
     */
    @Transactional(readOnly = true)
    public OauthSettingsForm load() {
        Map<String, String> cfg = repository.loadGroupAsMap(GROUP);
        return new OauthSettingsForm(
                cfg.getOrDefault(KEY_GOOGLE_CLIENT_ID, ""),
                cfg.getOrDefault(KEY_GOOGLE_CLIENT_SECRET, ""),
                cfg.getOrDefault(KEY_GOOGLE_SCOPE, DEFAULT_SCOPE)
        );
    }

    /**
     * Persists updated OAuth settings to the database.
     *
     * <p>Atomicity is guaranteed by {@code @Transactional}: if any upsert
     * fails, all writes in this call are rolled back.
     *
     * <p>Secret handling: when {@code form.googleClientSecret()} is
     * {@code null} or blank, the {@code oauth.google.client_secret} row is
     * skipped — the stored value and {@code updated_by} are left unchanged.
     * This preserves the existing secret when the admin clears the field on
     * the form intentionally; to overwrite with an empty secret the row must
     * be edited directly in the database.
     *
     * @param form          the submitted settings form
     * @param currentUserId ID of the admin user performing the save
     */
    @Transactional
    public void save(OauthSettingsForm form, Long currentUserId) {
        Map<String, String> incoming = new LinkedHashMap<>();
        incoming.put(KEY_GOOGLE_CLIENT_ID, nullSafeTrim(form.googleClientId()));
        incoming.put(KEY_GOOGLE_SCOPE, nullSafeTrim(form.googleScope()));

        // Secret: only update when the user submits a non-blank value
        if (form.googleClientSecret() != null && !form.googleClientSecret().isBlank()) {
            incoming.put(KEY_GOOGLE_CLIENT_SECRET, form.googleClientSecret().trim());
        }

        upsertAll(incoming, currentUserId);
    }

    /**
     * Reports whether Google sign-in is currently fully configured (both
     * client id AND client secret are non-blank in the database).
     *
     * <p>Used by the login template to decide whether to render the "Sign in
     * with Google" button, and by {@code DbClientRegistrationRepository} to
     * decide whether to materialise the Google {@code ClientRegistration}.
     * Both checks must agree, otherwise the user is redirected to Google
     * for an authorization that will then fail at token exchange.
     *
     * <p>Uses two indexed lookups by primary key (cheaper than loading the
     * whole {@code OAUTH} group).
     *
     * @return {@code true} when both {@code oauth.google.client_id} and
     *         {@code oauth.google.client_secret} are non-blank
     */
    @Transactional(readOnly = true)
    public boolean isGoogleEnabled() {
        return hasNonBlankSetting(KEY_GOOGLE_CLIENT_ID)
                && hasNonBlankSetting(KEY_GOOGLE_CLIENT_SECRET);
    }

    /**
     * Reports whether a non-blank Google client secret is already stored in
     * the database.
     *
     * <p>The form treats a blank submitted secret as "keep the existing
     * value". Callers (the controller's cross-field validation) use this to
     * tell apart "admin left the field blank because a secret already exists"
     * (valid) from "admin left the field blank but no secret was ever saved"
     * (invalid, half-configured).
     *
     * @return {@code true} when {@code oauth.google.client_secret} is non-blank
     */
    @Transactional(readOnly = true)
    public boolean hasStoredGoogleSecret() {
        return hasNonBlankSetting(KEY_GOOGLE_CLIENT_SECRET);
    }

    // ─────────────────────────────────────────────────────────────────

    private boolean hasNonBlankSetting(String key) {
        return repository.findBySettingKey(key)
                .map(s -> s.getSettingValue() != null && !s.getSettingValue().isBlank())
                .orElse(false);
    }

    private void upsertAll(Map<String, String> incoming, Long currentUserId) {
        Map<String, SystemSetting> existing = new HashMap<>();
        for (SystemSetting s : repository.findBySettingGroup(GROUP)) {
            existing.put(s.getSettingKey(), s);
        }

        for (Map.Entry<String, String> entry : incoming.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();

            SystemSetting row = existing.get(key);
            if (row == null) {
                row = new SystemSetting(key, value, GROUP);
            } else {
                row.setSettingValue(value);
            }
            row.setUpdatedBy(currentUserId);
            repository.save(row);
        }

        log.info("OAuth settings saved by user {} (updated {} keys{})",
                currentUserId, incoming.size(),
                incoming.containsKey(KEY_GOOGLE_CLIENT_SECRET) ? ", incl. secret" : ", secret unchanged");
        if (incoming.containsKey(KEY_GOOGLE_CLIENT_SECRET)) {
            // Heads-up: any OAuth2AuthorizedClient instances already in the
            // in-memory authorized-client store keep working with their
            // original access tokens until those tokens expire (Google
            // default: 1 hour). New sign-ins after this point use the new
            // credentials. See docs/decisions/0009-db-backed-oauth-registration.md
            // for the rationale.
            log.warn("Google client secret was changed — already-authorized "
                    + "clients will keep using their existing access tokens "
                    + "until expiry (up to 1 hour).");
        }
    }

    private static String nullSafeTrim(String raw) {
        return raw == null ? "" : raw.trim();
    }
}
