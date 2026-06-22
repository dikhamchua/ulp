package com.ulp.admin.settings.service;

import com.ulp.admin.settings.dto.EmailSettingsDtos;
import com.ulp.admin.settings.dto.EmailSettingsDtos.EmailSettingsForm;
import com.ulp.admin.settings.dto.EmailSettingsDtos.TestResult;
import com.ulp.shared.mail.MailSendResult;
import com.ulp.shared.mail.MailService;
import com.ulp.shared.settings.SystemSettingGroups;
import com.ulp.shared.settings.entity.SystemSetting;
import com.ulp.shared.settings.repository.SystemSettingsRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test cho {@link EmailSettingsService}.
 *
 * <p>Cover cac scenario chinh trong spec:
 * <ul>
 *   <li>load() mask password</li>
 *   <li>save() empty password skip row</li>
 *   <li>save() MASKED placeholder cung skip row</li>
 *   <li>save() password thuc su thi overwrite</li>
 *   <li>sendTest() invalid recipient khong goi MailService</li>
 *   <li>sendTest() empty host tra ve loi cu the</li>
 *   <li>sendTest() mail exception tra ve loi voi message</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class EmailSettingsServiceTest {

    private static final String MASKED = EmailSettingsDtos.MASKED;

    @Mock
    private SystemSettingsRepository repository;

    @Mock
    private MailService mailService;

    @InjectMocks
    private EmailSettingsService service;

    private Map<String, String> defaultCfg() {
        return Map.of(
                "smtp.host", "smtp.example.com",
                "smtp.port", "587",
                "smtp.encryption", "tls",
                "smtp.username", "noreply@example.com",
                "smtp.password", "real-secret",
                "smtp.from_name", "ULP",
                "smtp.from_email", "noreply@example.com",
                "smtp.reply_to", ""
        );
    }

    // ───────────────────── load() ─────────────────────

    @Test
    void load_returns_masked_password() {
        when(repository.loadGroupAsMap(SystemSettingGroups.SMTP)).thenReturn(defaultCfg());

        EmailSettingsForm form = service.load();
        assertThat(form.password()).isEqualTo(MASKED);
        assertThat(form.host()).isEqualTo("smtp.example.com");
        assertThat(form.port()).isEqualTo(587);
        assertThat(form.encryption()).isEqualTo("tls");
    }

    @Test
    void load_returns_null_port_when_stored_value_is_non_numeric() {
        when(repository.loadGroupAsMap(SystemSettingGroups.SMTP)).thenReturn(Map.of(
                "smtp.host", "smtp.example.com",
                "smtp.port", "not-a-number",
                "smtp.encryption", "tls",
                "smtp.username", "u",
                "smtp.password", "p",
                "smtp.from_name", "n",
                "smtp.from_email", "from@example.com",
                "smtp.reply_to", ""
        ));
        EmailSettingsForm form = service.load();
        assertThat(form.port()).isNull();
    }

    // ───────────────────── save() ─────────────────────

    @Test
    void save_with_empty_password_skips_password_row() {
        when(repository.findBySettingGroup(SystemSettingGroups.SMTP)).thenReturn(List.of(
                row("smtp.host", "old-host"),
                row("smtp.password", "old-secret")
        ));

        EmailSettingsForm form = new EmailSettingsForm(
                "new-host", 587, "tls", "u", "", "ULP", "from@example.com", "");
        service.save(form, 42L);

        // Assert by content, not count — robust to adding/removing SMTP keys later.
        ArgumentCaptor<SystemSetting> captor = ArgumentCaptor.forClass(SystemSetting.class);
        verify(repository, atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(SystemSetting::getSettingKey)
                .doesNotContain("smtp.password");
    }

    @Test
    void save_with_masked_placeholder_skips_password_row() {
        when(repository.findBySettingGroup(SystemSettingGroups.SMTP)).thenReturn(List.of(
                row("smtp.password", "old-secret")
        ));

        EmailSettingsForm form = new EmailSettingsForm(
                "h", 587, "tls", "u", MASKED, "ULP", "from@example.com", "");
        service.save(form, 42L);

        ArgumentCaptor<SystemSetting> captor = ArgumentCaptor.forClass(SystemSetting.class);
        verify(repository, atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(SystemSetting::getSettingKey)
                .doesNotContain("smtp.password");
    }

    @Test
    void save_with_explicit_new_password_overwrites_password_row() {
        when(repository.findBySettingGroup(SystemSettingGroups.SMTP)).thenReturn(List.of(
                row("smtp.password", "old-secret")
        ));

        EmailSettingsForm form = new EmailSettingsForm(
                "h", 587, "tls", "u", "brand-new-pw", "ULP", "from@example.com", "");
        service.save(form, 42L);

        ArgumentCaptor<SystemSetting> captor = ArgumentCaptor.forClass(SystemSetting.class);
        verify(repository, atLeastOnce()).save(captor.capture());
        SystemSetting passwordRow = captor.getAllValues().stream()
                .filter(s -> "smtp.password".equals(s.getSettingKey()))
                .findFirst().orElseThrow();
        assertThat(passwordRow.getSettingValue()).isEqualTo("brand-new-pw");
        assertThat(passwordRow.getUpdatedBy()).isEqualTo(42L);
    }

    @Test
    void save_stamps_updated_by_on_all_written_rows() {
        when(repository.findBySettingGroup(SystemSettingGroups.SMTP)).thenReturn(List.of());

        EmailSettingsForm form = new EmailSettingsForm(
                "h", 587, "tls", "u", "p", "n", "f@e.com", "");
        service.save(form, 99L);

        ArgumentCaptor<SystemSetting> captor = ArgumentCaptor.forClass(SystemSetting.class);
        verify(repository, atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues())
                .allMatch(s -> s.getUpdatedBy().equals(99L));
    }

    @Test
    void save_method_is_transactional() throws NoSuchMethodException {
        // Spec: save() must be @Transactional so all-or-nothing applies.
        // Verify the annotation is present on the public method.
        var method = EmailSettingsService.class.getMethod(
                "save", EmailSettingsForm.class, Long.class);
        var annotation = method.getAnnotation(
                org.springframework.transaction.annotation.Transactional.class);
        assertThat(annotation)
                .as("save() must be @Transactional for atomic rollback")
                .isNotNull();
        assertThat(annotation.readOnly()).isFalse();
    }

    // ───────────────────── sendTest() ─────────────────────

    @Test
    void sendTest_with_blank_recipient_does_not_call_mail_service() {
        TestResult result = service.sendTest("");
        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("nhập email");
        verify(mailService, never()).sendWithDetail(anyString(), anyString(), anyString());
    }

    @Test
    void sendTest_with_invalid_email_does_not_call_mail_service() {
        TestResult result = service.sendTest("not-an-email");
        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("không hợp lệ");
        verify(mailService, never()).sendWithDetail(anyString(), anyString(), anyString());
    }

    @Test
    void sendTest_when_host_empty_returns_specific_message() {
        when(mailService.sendWithDetail(eq("valid@example.com"), anyString(), anyString()))
                .thenReturn(MailSendResult.failure("SMTP host is not configured"));

        TestResult result = service.sendTest("valid@example.com");
        assertThat(result.ok()).isFalse();
        assertThat(result.error()).isEqualTo("SMTP host is not configured");
    }

    @Test
    void sendTest_when_smtp_fails_returns_error_message() {
        when(mailService.sendWithDetail(eq("valid@example.com"), anyString(), anyString()))
                .thenReturn(MailSendResult.failure("Authentication failed: 535"));

        TestResult result = service.sendTest("valid@example.com");
        assertThat(result.ok()).isFalse();
        assertThat(result.error()).isEqualTo("Authentication failed: 535");
    }

    @Test
    void sendTest_when_smtp_succeeds_returns_ok_true() {
        when(mailService.sendWithDetail(eq("valid@example.com"), anyString(), anyString()))
                .thenReturn(MailSendResult.success());

        TestResult result = service.sendTest("valid@example.com");
        assertThat(result.ok()).isTrue();
        assertThat(result.error()).isNull();
    }

    // ───────────────────── helper ─────────────────────

    private SystemSetting row(String key, String value) {
        return new SystemSetting(key, value, SystemSettingGroups.SMTP);
    }
}
