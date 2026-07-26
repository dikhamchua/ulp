package com.ulp.features.storage;

import com.ulp.features.admin.settings.SystemSettingGroups;
import com.ulp.features.admin.settings.service.SystemSettingsService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * Wires the always-on {@link DualReadObjectStorage} bean used by every
 * upload family. R2 client is obtained lazily via {@link R2ClientHolder}
 * so admin settings changes take effect without restart.
 */
@Configuration
public class ObjectStorageConfig {

    @Bean
    public LocalObjectStorage localObjectStorage(
            @Value("${app.upload.dir:uploads}") String uploadDir) {
        Path root = Paths.get(uploadDir).toAbsolutePath().normalize();
        return new LocalObjectStorage(root);
    }

    @Bean
    public R2ObjectStorage r2ObjectStorage(R2ClientHolder holder,
                                           SystemSettingsService settingsService) {
        return new R2ObjectStorage(
                () -> {
                    R2ClientHolder.R2Config cfg = loadR2Config(settingsService);
                    return holder.getOrCreate(cfg);
                },
                () -> loadR2Config(settingsService).bucket()
        );
    }

    @Bean
    public ObjectStorage objectStorage(LocalObjectStorage local,
                                       R2ObjectStorage r2,
                                       SystemSettingsService settingsService) {
        return new DualReadObjectStorage(
                local,
                r2,
                () -> loadProvider(settingsService),
                () -> loadR2Config(settingsService).isComplete()
        );
    }

    private static String loadProvider(SystemSettingsService settingsService) {
        Map<String, String> cfg = settingsService.loadGroupAsMap(SystemSettingGroups.STORAGE);
        String provider = cfg.getOrDefault("storage.provider", "local");
        return provider == null || provider.isBlank() ? "local" : provider.trim().toLowerCase();
    }

    private static R2ClientHolder.R2Config loadR2Config(SystemSettingsService settingsService) {
        Map<String, String> cfg = settingsService.loadGroupAsMap(SystemSettingGroups.STORAGE);
        return new R2ClientHolder.R2Config(
                cfg.getOrDefault("storage.r2.access_key_id", ""),
                cfg.getOrDefault("storage.r2.secret_access_key", ""),
                cfg.getOrDefault("storage.r2.bucket", ""),
                cfg.getOrDefault("storage.r2.endpoint", ""),
                cfg.getOrDefault("storage.r2.region", "auto")
        );
    }
}
