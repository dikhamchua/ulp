package com.ulp.shared.settings.service;

import com.ulp.shared.config.CacheConfig;
import com.ulp.shared.settings.repository.SystemSettingsRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Read-through cache facade over {@link SystemSettingsRepository#loadGroupAsMap(String)}.
 *
 * <p>Hot-path callers ({@code DbConfiguredMailSender}, {@code DbClientRegistrationRepository})
 * inject this service instead of the repository directly so each group lookup
 * hits MySQL at most once per cache TTL (configured in {@link CacheConfig}).
 * Non-hot callers (admin pages that display settings) may continue to use the
 * repository default method directly.
 *
 * <p>Write-through invalidation is performed by:
 * <ul>
 *   <li>{@link com.ulp.admin.settings.service.EmailSettingsService#save} —
 *       evicts the {@code SMTP} entry.</li>
 *   <li>{@link com.ulp.admin.settings.service.OauthSettingsService#save} —
 *       evicts the {@code OAUTH} entry.</li>
 * </ul>
 *
 * <p>Cache contract: each setting group is cached under its own group-name key,
 * so groups never interfere with each other. The 5-minute TTL in
 * {@link CacheConfig} is a safety net only — admin saves trigger immediate
 * eviction so the next read always observes the latest values.
 *
 * <p>Note on Spring Cache proxies: the {@code @Cacheable} annotation only takes
 * effect when this bean is invoked through the Spring-managed proxy. Internal
 * self-invocation would bypass the cache; all callers must inject the service
 * rather than calling each other's methods.
 */
@Service
public class SystemSettingsService {

    private final SystemSettingsRepository repository;

    public SystemSettingsService(SystemSettingsRepository repository) {
        this.repository = repository;
    }

    /**
     * Loads all rows in the given system_settings group, returning them as a
     * flat {@code Map<key, value>}. Results are cached per group name; entries
     * are evicted automatically after the configured TTL or explicitly when
     * an admin save mutates the group.
     *
     * @param group the setting group name (e.g. {@code "SMTP"}, {@code "OAUTH"});
     *              must not be {@code null}
     * @return a map of setting keys to values (never {@code null}; values are
     *         normalised to an empty string if originally {@code null} in the DB)
     */
    @Cacheable(value = CacheConfig.CACHE_SETTINGS_GROUP, key = "#group")
    @Transactional(readOnly = true)
    public Map<String, String> loadGroupAsMap(String group) {
        return repository.loadGroupAsMap(group);
    }
}
