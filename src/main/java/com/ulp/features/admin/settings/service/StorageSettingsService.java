package com.ulp.features.admin.settings.service;

import com.ulp.config.CacheConfig;
import com.ulp.entities.SystemSetting;
import com.ulp.features.admin.settings.SystemSettingGroups;
import com.ulp.features.admin.settings.dto.StorageSettingsDtos;
import com.ulp.features.admin.settings.dto.StorageSettingsDtos.StorageSettingsForm;
import com.ulp.features.admin.settings.dto.StorageSettingsDtos.TestResult;
import com.ulp.features.admin.settings.repository.SystemSettingsRepository;
import com.ulp.features.storage.R2ClientHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static com.ulp.common.IConstant.MSG_STORAGE_PROVIDER_INVALID;
import static com.ulp.common.IConstant.MSG_STORAGE_R2_CONNECT_FAILED;
import static com.ulp.common.IConstant.MSG_STORAGE_R2_FIELDS_REQUIRED;
import static com.ulp.common.IConstant.STORAGE_PROVIDER_LOCAL;
import static com.ulp.common.IConstant.STORAGE_PROVIDER_R2;

/**
 * Load / save / test Cloudflare R2 storage settings for the admin panel.
 * Secrets are masked on load; blank/{@link StorageSettingsDtos#MASKED} on save
 * keeps the existing secret. Save evicts the STORAGE cache entry and
 * invalidates the cached S3 client.
 */
@Service
public class StorageSettingsService {

    private static final Logger log = LoggerFactory.getLogger(StorageSettingsService.class);

    private static final String GROUP = SystemSettingGroups.STORAGE;
    private static final String MASKED = StorageSettingsDtos.MASKED;
    private static final Set<String> SECRET_KEYS = Set.of("storage.r2.secret_access_key");

    private final SystemSettingsRepository repository;
    private final R2ClientHolder r2ClientHolder;

    public StorageSettingsService(SystemSettingsRepository repository,
                                  R2ClientHolder r2ClientHolder) {
        this.repository = repository;
        this.r2ClientHolder = r2ClientHolder;
    }

    /** Loads current STORAGE settings with the secret always masked. */
    @Transactional(readOnly = true)
    public StorageSettingsForm load() {
        Map<String, String> cfg = repository.loadGroupAsMap(GROUP);
        return new StorageSettingsForm(
                cfg.getOrDefault("storage.provider", STORAGE_PROVIDER_LOCAL),
                cfg.getOrDefault("storage.r2.account_id", ""),
                cfg.getOrDefault("storage.r2.access_key_id", ""),
                MASKED,
                cfg.getOrDefault("storage.r2.bucket", ""),
                cfg.getOrDefault("storage.r2.endpoint", ""),
                cfg.getOrDefault("storage.r2.region", "auto")
        );
    }

    /**
     * Persists storage settings. When provider is {@code r2}, requires access
     * key, secret (new or already stored), bucket and endpoint.
     */
    @Transactional
    @CacheEvict(value = CacheConfig.CACHE_SETTINGS_GROUP, key = "'STORAGE'")
    public void save(StorageSettingsForm form, Long currentUserId) {
        String provider = form.provider() == null ? "" : form.provider().trim().toLowerCase();
        if (!STORAGE_PROVIDER_LOCAL.equals(provider) && !STORAGE_PROVIDER_R2.equals(provider)) {
            throw new IllegalArgumentException(MSG_STORAGE_PROVIDER_INVALID);
        }

        Map<String, String> existing = repository.loadGroupAsMap(GROUP);
        String incomingSecret = form.secretAccessKey();
        boolean keepSecret = incomingSecret == null || incomingSecret.isBlank()
                || MASKED.equals(incomingSecret);
        String effectiveSecret = keepSecret
                ? existing.getOrDefault("storage.r2.secret_access_key", "")
                : incomingSecret;

        String accessKey = trim(form.accessKeyId());
        String bucket = trim(form.bucket());
        String endpoint = trim(form.endpoint());

        if (STORAGE_PROVIDER_R2.equals(provider)) {
            if (accessKey.isBlank() || effectiveSecret.isBlank()
                    || bucket.isBlank() || endpoint.isBlank()) {
                throw new IllegalArgumentException(MSG_STORAGE_R2_FIELDS_REQUIRED);
            }
        }

        Map<String, String> incoming = new LinkedHashMap<>();
        incoming.put("storage.provider", provider);
        incoming.put("storage.r2.account_id", trim(form.accountId()));
        incoming.put("storage.r2.access_key_id", accessKey);
        incoming.put("storage.r2.bucket", bucket);
        incoming.put("storage.r2.endpoint", endpoint);
        incoming.put("storage.r2.region",
                form.region() == null || form.region().isBlank() ? "auto" : form.region().trim());
        if (!keepSecret) {
            incoming.put("storage.r2.secret_access_key", incomingSecret);
        }

        upsertAll(incoming, currentUserId);
        r2ClientHolder.invalidate();
    }

    /**
     * HeadBucket against the currently saved R2 settings (not the in-form draft).
     */
    public TestResult testConnection() {
        Map<String, String> cfg = repository.loadGroupAsMap(GROUP);
        R2ClientHolder.R2Config r2 = new R2ClientHolder.R2Config(
                cfg.getOrDefault("storage.r2.access_key_id", ""),
                cfg.getOrDefault("storage.r2.secret_access_key", ""),
                cfg.getOrDefault("storage.r2.bucket", ""),
                cfg.getOrDefault("storage.r2.endpoint", ""),
                cfg.getOrDefault("storage.r2.region", "auto")
        );
        if (!r2.isComplete()) {
            return new TestResult(false, MSG_STORAGE_R2_FIELDS_REQUIRED);
        }
        try {
            S3Client client = r2ClientHolder.getOrCreate(r2);
            if (client == null) {
                return new TestResult(false, MSG_STORAGE_R2_FIELDS_REQUIRED);
            }
            client.headBucket(HeadBucketRequest.builder().bucket(r2.bucket().trim()).build());
            return new TestResult(true, null);
        } catch (RuntimeException ex) {
            log.warn("R2 HeadBucket failed: {}", ex.getMessage());
            String msg = ex.getMessage() == null || ex.getMessage().isBlank()
                    ? MSG_STORAGE_R2_CONNECT_FAILED
                    : ex.getMessage();
            return new TestResult(false, msg);
        }
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
        log.info("Storage settings saved by user {} (updated {} keys{})",
                currentUserId, incoming.size(),
                incoming.keySet().stream().anyMatch(SECRET_KEYS::contains)
                        ? ", incl. secret" : ", secret unchanged");
    }

    private static String trim(String v) {
        return v == null ? "" : v.trim();
    }
}
