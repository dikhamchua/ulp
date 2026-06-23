package com.ulp.auth.oauth;

import com.ulp.admin.settings.service.OauthSettingsService;
import com.ulp.shared.settings.SystemSettingGroups;
import com.ulp.shared.settings.service.SystemSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Map;

/**
 * {@link ClientRegistrationRepository} backed by the {@code system_settings}
 * database table. The Google client id / secret / scope are read fresh on
 * every lookup so changes saved through {@code /admin/settings/oauth} take
 * effect immediately — no application restart required.
 *
 * <p>Behaviour:
 * <ul>
 *   <li>{@link #findByRegistrationId(String)} with {@code "google"} returns a
 *       fully built {@link ClientRegistration} when the client id is
 *       configured, or {@code null} when it is empty (Spring Security treats
 *       a {@code null} registration as "provider not available" and replies
 *       with HTTP 404 to {@code /oauth2/authorization/google}).</li>
 *   <li>Any other registration id returns {@code null}.</li>
 * </ul>
 *
 * <p>Defining this bean turns off Spring Boot's
 * {@code OAuth2ClientAutoConfiguration} for the {@code ClientRegistrationRepository}
 * component — we therefore lose the auto-bound property-based registration
 * (which was previously the only way to enable Google login). That trade-off
 * is intentional: configuration now lives in the database and is editable
 * through the admin UI rather than {@code application-local.properties}.
 *
 * <p>Performance: each call reads the {@code OAUTH} group through the cached
 * {@link SystemSettingsService}. Hot reads (login page render, OAuth callback)
 * hit Caffeine in memory; only cold misses or post-eviction reads issue a SQL
 * query. {@code OauthSettingsService.save} evicts the {@code OAUTH} entry on
 * admin save so credential changes propagate immediately.
 */
@Component
public class DbClientRegistrationRepository implements ClientRegistrationRepository {

    private static final Logger log = LoggerFactory.getLogger(DbClientRegistrationRepository.class);

    private static final String GROUP = SystemSettingGroups.OAUTH;
    private static final String GOOGLE = "google";

    private final SystemSettingsService settingsService;

    public DbClientRegistrationRepository(SystemSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    /**
     * Resolves the {@link ClientRegistration} for the given registration id.
     *
     * <p>Only {@code "google"} is supported in this MVP. Returning
     * {@code null} is a contract honoured by Spring Security's OAuth2
     * filters — the framework will respond with HTTP 404 to the
     * authorization redirect, which is the expected behaviour when
     * Google login is not yet configured.
     *
     * @param registrationId the requested registration id (case-sensitive)
     * @return the resolved registration, or {@code null} when the provider
     *         is unknown or not yet configured
     */
    @Override
    public ClientRegistration findByRegistrationId(String registrationId) {
        if (!GOOGLE.equals(registrationId)) {
            return null;
        }

        Map<String, String> cfg = settingsService.loadGroupAsMap(GROUP);
        String clientId = trim(cfg.get(OauthSettingsService.KEY_GOOGLE_CLIENT_ID));
        String clientSecret = trim(cfg.get(OauthSettingsService.KEY_GOOGLE_CLIENT_SECRET));
        if (clientId.isEmpty() || clientSecret.isEmpty()) {
            // Google sign-in is half-configured or disabled — let Spring
            // Security return 404 rather than start a flow that will then
            // fail at token exchange with a confusing OAuth error.
            return null;
        }

        String scopeRaw = trim(cfg.get(OauthSettingsService.KEY_GOOGLE_SCOPE));
        if (scopeRaw.isEmpty()) {
            scopeRaw = OauthSettingsService.DEFAULT_SCOPE;
        }
        String[] scopes = Arrays.stream(scopeRaw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);

        return CommonOAuth2Provider.GOOGLE
                .getBuilder(GOOGLE)
                .clientId(clientId)
                .clientSecret(clientSecret)
                .scope(scopes)
                .build();
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
