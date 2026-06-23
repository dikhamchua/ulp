package com.ulp.shared.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Application-wide Spring Cache configuration backed by Caffeine.
 *
 * <p>Defines a single {@link CacheManager} bean used by {@code @Cacheable}
 * and {@code @CacheEvict} annotations across the codebase. The cache is
 * intentionally bounded and short-lived so it can never grow without
 * limit and so admin-driven changes propagate quickly even if a write-through
 * eviction path is ever missed.
 *
 * <p>Registered caches:
 * <ul>
 *   <li>{@code settingsGroup} — caches the result of
 *       {@link com.ulp.shared.settings.service.SystemSettingsService#loadGroupAsMap(String)}
 *       keyed by setting group name (e.g. {@code SMTP}, {@code OAUTH}).
 *       Write-through invalidation is performed by
 *       {@link com.ulp.admin.settings.service.EmailSettingsService#save} and
 *       {@link com.ulp.admin.settings.service.OauthSettingsService#save}.</li>
 * </ul>
 *
 * <p>Tuning rationale:
 * <ul>
 *   <li>{@code expireAfterWrite = 5 minutes} — safety net for entries that
 *       might otherwise leak if an eviction path is forgotten. Five minutes
 *       is well inside acceptable propagation latency for admin-edited values.</li>
 *   <li>{@code maximumSize = 50} — covers the four known setting groups
 *       ({@code GENERAL}, {@code SMTP}, {@code OAUTH}, {@code AI}) with
 *       comfortable headroom for future additions, while keeping the cache
 *       footprint tiny.</li>
 * </ul>
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /** Spring Cache name for the system_settings group lookups. */
    public static final String CACHE_SETTINGS_GROUP = "settingsGroup";

    /** Maximum number of entries the cache may hold before LRU eviction kicks in. */
    private static final long CACHE_MAX_SIZE = 50L;

    /** Maximum age of a cache entry before it is automatically evicted. */
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    /**
     * Builds the single {@link CacheManager} for the application.
     *
     * <p>The {@code settingsGroup} cache name is registered explicitly so
     * that {@code @Cacheable("settingsGroup")} / {@code @CacheEvict} usages
     * fail fast at startup if the cache name is ever mistyped (instead of
     * silently being created on first use).
     *
     * @return a {@link CaffeineCacheManager} pre-configured with TTL and max size
     */
    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager(CACHE_SETTINGS_GROUP);
        manager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(CACHE_TTL)
                .maximumSize(CACHE_MAX_SIZE));
        return manager;
    }
}
