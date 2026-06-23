package com.ulp.shared.settings.service;

import com.ulp.admin.settings.dto.EmailSettingsDtos.EmailSettingsForm;
import com.ulp.admin.settings.dto.OauthSettingsDtos.OauthSettingsForm;
import com.ulp.admin.settings.service.EmailSettingsService;
import com.ulp.admin.settings.service.OauthSettingsService;
import com.ulp.auth.repository.UserRepository;
import com.ulp.shared.config.CacheConfig;
import com.ulp.shared.settings.SystemSettingGroups;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the {@code settingsGroup} read-through cache exposed
 * by {@link SystemSettingsService}.
 *
 * <p>Covers spec scenarios from
 * {@code specs/system-settings-cache/spec.md}:
 * <ul>
 *   <li>Repeated reads within TTL hit the cache (no extra SQL).</li>
 *   <li>Different groups are cached independently.</li>
 *   <li>SMTP save evicts the SMTP entry while OAUTH stays intact.</li>
 *   <li>OAUTH save evicts the OAUTH entry while SMTP stays intact.</li>
 * </ul>
 *
 * <p>Uses Hibernate {@code Statistics.getPrepareStatementCount()} to count
 * actual SQL prepares — a more robust signal than counting query strings,
 * since Hibernate may not log every statement at INFO level.
 */
@SpringBootTest
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Transactional
class SystemSettingsCacheTest {

    @Autowired private SystemSettingsService settingsService;
    @Autowired private EmailSettingsService emailSettingsService;
    @Autowired private OauthSettingsService oauthSettingsService;
    @Autowired private CacheManager cacheManager;
    @Autowired private UserRepository userRepository;
    @PersistenceContext private EntityManager em;

    private Statistics stats;
    private Long adminId;

    @BeforeEach
    void setUp() {
        stats = em.getEntityManagerFactory()
                .unwrap(SessionFactory.class)
                .getStatistics();
        stats.clear();
        // Start each test with a cold cache so query-count assertions are
        // independent of test ordering.
        Cache cache = cacheManager.getCache(CacheConfig.CACHE_SETTINGS_GROUP);
        if (cache != null) cache.clear();

        adminId = userRepository.findByEmailIgnoreCase("admin@ulp.edu.vn")
                .map(u -> u.getId())
                .orElseThrow();
    }

    @Test
    void cache_is_registered_under_settings_group_name() {
        Cache cache = cacheManager.getCache(CacheConfig.CACHE_SETTINGS_GROUP);
        assertThat(cache)
                .as("settingsGroup cache must be registered by CacheConfig")
                .isNotNull();
    }

    @Test
    void repeated_reads_within_ttl_hit_the_cache() {
        long base = stats.getPrepareStatementCount();

        Map<String, String> first = settingsService.loadGroupAsMap(SystemSettingGroups.SMTP);
        long afterFirst = stats.getPrepareStatementCount();
        Map<String, String> second = settingsService.loadGroupAsMap(SystemSettingGroups.SMTP);
        long afterSecond = stats.getPrepareStatementCount();

        assertThat(first).isNotNull();
        assertThat(second).isEqualTo(first);
        // Second call must not fire any further SQL — exact prepare-count delta is 0.
        assertThat(afterSecond - afterFirst)
                .as("second cache hit must issue zero SQL prepares")
                .isZero();
        // The first call did at least one prepare (the cold read).
        assertThat(afterFirst - base).isGreaterThanOrEqualTo(1);
    }

    @Test
    void different_groups_are_cached_independently() {
        settingsService.loadGroupAsMap(SystemSettingGroups.SMTP);
        long afterSmtp = stats.getPrepareStatementCount();
        settingsService.loadGroupAsMap(SystemSettingGroups.OAUTH);
        long afterOauth = stats.getPrepareStatementCount();

        // OAUTH was a cold read, so the prepare count must strictly increase.
        assertThat(afterOauth - afterSmtp)
                .as("OAUTH is a separate cache key — first read must hit DB")
                .isGreaterThanOrEqualTo(1);

        // Repeating each call now serves both from cache.
        long beforeRepeat = stats.getPrepareStatementCount();
        settingsService.loadGroupAsMap(SystemSettingGroups.SMTP);
        settingsService.loadGroupAsMap(SystemSettingGroups.OAUTH);
        long afterRepeat = stats.getPrepareStatementCount();

        assertThat(afterRepeat - beforeRepeat)
                .as("warm reads against either group must not issue SQL")
                .isZero();
    }

    @Test
    void smtp_save_evicts_smtp_entry_only() {
        // Warm both caches.
        settingsService.loadGroupAsMap(SystemSettingGroups.SMTP);
        settingsService.loadGroupAsMap(SystemSettingGroups.OAUTH);

        Cache cache = cacheManager.getCache(CacheConfig.CACHE_SETTINGS_GROUP);
        assertThat(cache.get(SystemSettingGroups.SMTP)).isNotNull();
        assertThat(cache.get(SystemSettingGroups.OAUTH)).isNotNull();

        // Save SMTP — eviction should drop only the SMTP entry.
        EmailSettingsForm form = new EmailSettingsForm(
                "smtp.example.com", 587, "tls", "u@example.com",
                "", "ULP", "u@example.com", "");
        emailSettingsService.save(form, adminId);

        assertThat(cache.get(SystemSettingGroups.SMTP))
                .as("SMTP entry must be evicted after EmailSettingsService.save")
                .isNull();
        assertThat(cache.get(SystemSettingGroups.OAUTH))
                .as("OAUTH entry must remain intact after SMTP save")
                .isNotNull();

        // Next SMTP read fires a cold query against the database again.
        long before = stats.getPrepareStatementCount();
        settingsService.loadGroupAsMap(SystemSettingGroups.SMTP);
        long after = stats.getPrepareStatementCount();
        assertThat(after - before)
                .as("post-eviction SMTP read must hit the DB")
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void oauth_save_evicts_oauth_entry_only() {
        // Warm both caches.
        settingsService.loadGroupAsMap(SystemSettingGroups.SMTP);
        settingsService.loadGroupAsMap(SystemSettingGroups.OAUTH);

        Cache cache = cacheManager.getCache(CacheConfig.CACHE_SETTINGS_GROUP);
        assertThat(cache.get(SystemSettingGroups.SMTP)).isNotNull();
        assertThat(cache.get(SystemSettingGroups.OAUTH)).isNotNull();

        // Save OAUTH — eviction should drop only the OAUTH entry.
        OauthSettingsForm form = new OauthSettingsForm(
                "client-id-test", "", "openid,profile,email");
        oauthSettingsService.save(form, adminId);

        assertThat(cache.get(SystemSettingGroups.OAUTH))
                .as("OAUTH entry must be evicted after OauthSettingsService.save")
                .isNull();
        assertThat(cache.get(SystemSettingGroups.SMTP))
                .as("SMTP entry must remain intact after OAUTH save")
                .isNotNull();
    }
}
