package com.ulp.features.admin.settings.service;

import com.ulp.entities.SystemSetting;
import com.ulp.features.admin.settings.SystemSettingGroups;
import com.ulp.features.admin.settings.dto.StorageSettingsDtos;
import com.ulp.features.admin.settings.dto.StorageSettingsDtos.StorageSettingsForm;
import com.ulp.features.admin.settings.repository.SystemSettingsRepository;
import com.ulp.features.storage.R2ClientHolder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static com.ulp.common.IConstant.MSG_STORAGE_R2_FIELDS_REQUIRED;
import static com.ulp.common.IConstant.STORAGE_PROVIDER_LOCAL;
import static com.ulp.common.IConstant.STORAGE_PROVIDER_R2;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link StorageSettingsService}: mask, skip MASKED secret,
 * reject incomplete R2, invalidate holder.
 */
@ExtendWith(MockitoExtension.class)
class StorageSettingsServiceTest {

    private static final String MASKED = StorageSettingsDtos.MASKED;

    @Mock
    private SystemSettingsRepository repository;

    @Mock
    private R2ClientHolder r2ClientHolder;

    @InjectMocks
    private StorageSettingsService service;

    private Map<String, String> defaultCfg() {
        return Map.of(
                "storage.provider", STORAGE_PROVIDER_LOCAL,
                "storage.r2.account_id", "acc",
                "storage.r2.access_key_id", "AKIA",
                "storage.r2.secret_access_key", "real-secret",
                "storage.r2.bucket", "ulp",
                "storage.r2.endpoint", "https://example.r2.cloudflarestorage.com",
                "storage.r2.region", "auto"
        );
    }

    @Test
    void load_masks_secret() {
        when(repository.loadGroupAsMap(SystemSettingGroups.STORAGE)).thenReturn(defaultCfg());

        StorageSettingsForm form = service.load();
        assertThat(form.secretAccessKey()).isEqualTo(MASKED);
        assertThat(form.provider()).isEqualTo(STORAGE_PROVIDER_LOCAL);
        assertThat(form.accessKeyId()).isEqualTo("AKIA");
    }

    @Test
    void save_skips_secret_when_masked() {
        when(repository.loadGroupAsMap(SystemSettingGroups.STORAGE)).thenReturn(defaultCfg());
        when(repository.findBySettingGroup(SystemSettingGroups.STORAGE)).thenReturn(List.of(
                setting("storage.provider", "local"),
                setting("storage.r2.access_key_id", "AKIA"),
                setting("storage.r2.secret_access_key", "real-secret"),
                setting("storage.r2.bucket", "ulp"),
                setting("storage.r2.endpoint", "https://example.r2.cloudflarestorage.com"),
                setting("storage.r2.region", "auto"),
                setting("storage.r2.account_id", "acc")
        ));

        StorageSettingsForm form = new StorageSettingsForm(
                STORAGE_PROVIDER_LOCAL, "acc", "AKIA", MASKED,
                "ulp", "https://example.r2.cloudflarestorage.com", "auto");
        service.save(form, 1L);

        ArgumentCaptor<SystemSetting> captor = ArgumentCaptor.forClass(SystemSetting.class);
        verify(repository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues())
                .noneMatch(s -> "storage.r2.secret_access_key".equals(s.getSettingKey())
                        && !"real-secret".equals(s.getSettingValue())
                        && s.getSettingValue() != null
                        && !s.getSettingValue().isBlank()
                        && MASKED.equals(s.getSettingValue()) == false
                        && !"real-secret".equals(s.getSettingValue())
                        && s.getSettingValue().equals(MASKED));
        // Secret row must not be overwritten with MASKED.
        boolean secretSavedAsMasked = captor.getAllValues().stream()
                .anyMatch(s -> "storage.r2.secret_access_key".equals(s.getSettingKey())
                        && MASKED.equals(s.getSettingValue()));
        assertThat(secretSavedAsMasked).isFalse();
        verify(r2ClientHolder).invalidate();
    }

    @Test
    void save_r2_incomplete_rejects() {
        when(repository.loadGroupAsMap(SystemSettingGroups.STORAGE)).thenReturn(Map.of(
                "storage.provider", "local",
                "storage.r2.secret_access_key", ""
        ));

        StorageSettingsForm form = new StorageSettingsForm(
                STORAGE_PROVIDER_R2, "", "", MASKED, "", "", "auto");

        assertThatThrownBy(() -> service.save(form, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(MSG_STORAGE_R2_FIELDS_REQUIRED);
        verify(repository, never()).save(any());
        verify(r2ClientHolder, never()).invalidate();
    }

    private static SystemSetting setting(String key, String value) {
        SystemSetting s = new SystemSetting(key, value, SystemSettingGroups.STORAGE);
        return s;
    }
}
