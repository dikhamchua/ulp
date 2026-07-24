package com.ulp.features.admin.settings.service;

import com.ulp.config.CacheConfig;
import com.ulp.entities.SystemSetting;
import com.ulp.features.admin.settings.SystemSettingGroups;
import com.ulp.features.admin.settings.dto.GeneralSettingsDtos.GeneralSettingsForm;
import com.ulp.features.admin.settings.repository.SystemSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Service for managing General settings (platform name, description, logo URL,
 * contact email) in the admin panel: loading the current configuration and
 * persisting changes.
 *
 * <p>All four keys ({@code site.name}, {@code site.description},
 * {@code site.logo_url}, {@code site.contact_email}) were seeded by
 * {@code V1__init_schema} in the {@code GENERAL} group. This service upserts
 * them in place — no lazy row creation is needed for the MVP.
 *
 * <p>{@link #save} is {@code @Transactional} — every upsert runs inside a
 * single transaction and is rolled back atomically on failure — and evicts the
 * {@code GENERAL} entry from the {@code settingsGroup} cache so the next read
 * observes the new values immediately.
 */
@Service
public class GeneralSettingsService {

    private static final Logger log = LoggerFactory.getLogger(GeneralSettingsService.class);

    private static final String GROUP = SystemSettingGroups.GENERAL;

    public static final String KEY_SITE_NAME = "site.name";
    public static final String KEY_SITE_DESCRIPTION = "site.description";
    public static final String KEY_SITE_LOGO_URL = "site.logo_url";
    public static final String KEY_SITE_CONTACT_EMAIL = "site.contact_email";

    private final SystemSettingsRepository repository;

    public GeneralSettingsService(SystemSettingsRepository repository) {
        this.repository = repository;
    }

    /**
     * Loads the current General settings from the database.
     *
     * @return a {@link GeneralSettingsForm} populated with the stored values;
     *         missing keys fall back to an empty string
     */
    @Transactional(readOnly = true)
    public GeneralSettingsForm load() {
        Map<String, String> cfg = repository.loadGroupAsMap(GROUP);
        return new GeneralSettingsForm(
                cfg.getOrDefault(KEY_SITE_NAME, ""),
                cfg.getOrDefault(KEY_SITE_DESCRIPTION, ""),
                cfg.getOrDefault(KEY_SITE_LOGO_URL, ""),
                cfg.getOrDefault(KEY_SITE_CONTACT_EMAIL, "")
        );
    }

    /**
     * Persists updated General settings to the database.
     *
     * <p>Atomicity is guaranteed by {@code @Transactional}: if any upsert fails,
     * all writes in this call are rolled back. Unlike OAuth/SMTP secrets, every
     * field here is written verbatim (blank values are stored as-is, letting an
     * admin intentionally clear the logo or contact email).
     *
     * @param form          the submitted settings form
     * @param currentUserId ID of the admin user performing the save
     */
    @Transactional
    @CacheEvict(value = CacheConfig.CACHE_SETTINGS_GROUP, key = "'GENERAL'")
    public void save(GeneralSettingsForm form, Long currentUserId) {
        Map<String, String> incoming = new LinkedHashMap<>();
        incoming.put(KEY_SITE_NAME, nullSafeTrim(form.siteName()));
        incoming.put(KEY_SITE_DESCRIPTION, nullSafeTrim(form.siteDescription()));
        incoming.put(KEY_SITE_LOGO_URL, nullSafeTrim(form.siteLogoUrl()));
        incoming.put(KEY_SITE_CONTACT_EMAIL, nullSafeTrim(form.siteContactEmail()));

        upsertAll(incoming, currentUserId);
    }

    // ─────────────────────────────────────────────────────────────────

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

        log.info("General settings saved by user {} (updated {} keys)",
                currentUserId, incoming.size());
    }

    private static String nullSafeTrim(String raw) {
        return raw == null ? "" : raw.trim();
    }
}
