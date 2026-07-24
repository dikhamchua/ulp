package com.ulp.features.admin.settings.service;

import com.ulp.entities.SystemSetting;
import com.ulp.features.admin.settings.SystemSettingGroups;
import com.ulp.features.admin.settings.dto.GeneralSettingsDtos.GeneralSettingsForm;
import com.ulp.features.admin.settings.repository.SystemSettingsRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link GeneralSettingsService}.
 *
 * <p>Covers the main scenarios:
 * <ul>
 *   <li>load() returns stored values, missing keys fall back to empty string</li>
 *   <li>save() trims values and stamps updatedBy on every written row</li>
 *   <li>save() reuses existing rows and creates new rows for missing keys</li>
 *   <li>save() writes blank values verbatim (admin can clear logo/contact)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class GeneralSettingsServiceTest {

    @Mock
    private SystemSettingsRepository repository;

    @InjectMocks
    private GeneralSettingsService service;

    // ───────────────────── load() ─────────────────────

    @Test
    void load_returns_stored_values() {
        when(repository.loadGroupAsMap(SystemSettingGroups.GENERAL)).thenReturn(Map.of(
                "site.name", "ULP",
                "site.description", "Learning platform",
                "site.logo_url", "/images/logo.png",
                "site.contact_email", "contact@ulp.edu.vn"
        ));

        GeneralSettingsForm form = service.load();
        assertThat(form.siteName()).isEqualTo("ULP");
        assertThat(form.siteDescription()).isEqualTo("Learning platform");
        assertThat(form.siteLogoUrl()).isEqualTo("/images/logo.png");
        assertThat(form.siteContactEmail()).isEqualTo("contact@ulp.edu.vn");
    }

    @Test
    void load_falls_back_to_empty_string_for_missing_keys() {
        when(repository.loadGroupAsMap(SystemSettingGroups.GENERAL)).thenReturn(Map.of(
                "site.name", "ULP"
        ));

        GeneralSettingsForm form = service.load();
        assertThat(form.siteName()).isEqualTo("ULP");
        assertThat(form.siteDescription()).isEmpty();
        assertThat(form.siteLogoUrl()).isEmpty();
        assertThat(form.siteContactEmail()).isEmpty();
    }

    // ───────────────────── save() ─────────────────────

    @Test
    void save_writes_all_four_keys_with_trimmed_values() {
        when(repository.findBySettingGroup(SystemSettingGroups.GENERAL)).thenReturn(List.of());

        GeneralSettingsForm form = new GeneralSettingsForm(
                "  ULP  ", "  desc  ", "  /logo.png  ", "  a@b.com  ");
        service.save(form, 7L);

        ArgumentCaptor<SystemSetting> captor = ArgumentCaptor.forClass(SystemSetting.class);
        verify(repository, atLeastOnce()).save(captor.capture());

        Map<String, String> written = captor.getAllValues().stream()
                .collect(java.util.stream.Collectors.toMap(
                        SystemSetting::getSettingKey, SystemSetting::getSettingValue));
        assertThat(written).containsOnlyKeys(
                "site.name", "site.description", "site.logo_url", "site.contact_email");
        assertThat(written.get("site.name")).isEqualTo("ULP");
        assertThat(written.get("site.description")).isEqualTo("desc");
        assertThat(written.get("site.logo_url")).isEqualTo("/logo.png");
        assertThat(written.get("site.contact_email")).isEqualTo("a@b.com");
    }

    @Test
    void save_stamps_updated_by_on_all_written_rows() {
        when(repository.findBySettingGroup(SystemSettingGroups.GENERAL)).thenReturn(List.of());

        GeneralSettingsForm form = new GeneralSettingsForm("ULP", "d", "/l.png", "a@b.com");
        service.save(form, 99L);

        ArgumentCaptor<SystemSetting> captor = ArgumentCaptor.forClass(SystemSetting.class);
        verify(repository, atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues())
                .allMatch(s -> s.getUpdatedBy().equals(99L));
    }

    @Test
    void save_reuses_existing_row_instead_of_creating_duplicate() {
        SystemSetting existing = new SystemSetting("site.name", "old-name", SystemSettingGroups.GENERAL);
        when(repository.findBySettingGroup(SystemSettingGroups.GENERAL)).thenReturn(List.of(existing));

        GeneralSettingsForm form = new GeneralSettingsForm("new-name", "", "", "");
        service.save(form, 1L);

        ArgumentCaptor<SystemSetting> captor = ArgumentCaptor.forClass(SystemSetting.class);
        verify(repository, atLeastOnce()).save(captor.capture());
        SystemSetting nameRow = captor.getAllValues().stream()
                .filter(s -> "site.name".equals(s.getSettingKey()))
                .findFirst().orElseThrow();
        // Same instance mutated in place, not a fresh row.
        assertThat(nameRow).isSameAs(existing);
        assertThat(nameRow.getSettingValue()).isEqualTo("new-name");
    }

    @Test
    void save_writes_blank_values_verbatim() {
        when(repository.findBySettingGroup(SystemSettingGroups.GENERAL)).thenReturn(List.of());

        // Blank logo/contact must be stored as-is so admin can clear them.
        GeneralSettingsForm form = new GeneralSettingsForm("ULP", "", "", "");
        service.save(form, 1L);

        ArgumentCaptor<SystemSetting> captor = ArgumentCaptor.forClass(SystemSetting.class);
        verify(repository, atLeastOnce()).save(captor.capture());
        SystemSetting logoRow = captor.getAllValues().stream()
                .filter(s -> "site.logo_url".equals(s.getSettingKey()))
                .findFirst().orElseThrow();
        assertThat(logoRow.getSettingValue()).isEmpty();
    }

    @Test
    void save_method_is_transactional() throws NoSuchMethodException {
        // save() must be @Transactional so all-or-nothing applies.
        var method = GeneralSettingsService.class.getMethod(
                "save", GeneralSettingsForm.class, Long.class);
        var annotation = method.getAnnotation(
                org.springframework.transaction.annotation.Transactional.class);
        assertThat(annotation)
                .as("save() must be @Transactional for atomic rollback")
                .isNotNull();
        assertThat(annotation.readOnly()).isFalse();
    }
}
