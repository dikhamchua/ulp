package com.ulp.features.mail;

import com.ulp.features.admin.settings.SystemSettingGroups;
import com.ulp.features.admin.settings.service.SystemSettingsService;
import jakarta.mail.Address;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit test cho {@link DbConfiguredMailSender}.
 *
 * <p>Cover cac scenario "Send uses from_name when present" / "Send uses bare
 * from_email when from_name is empty" / "Send includes reply-to" trong spec.md.
 * Test khong gui email that — chi build MimeMessage va inspect headers.
 */
@ExtendWith(MockitoExtension.class)
class DbConfiguredMailSenderTest {

    @Mock
    private SystemSettingsService settingsService;

    @InjectMocks
    private DbConfiguredMailSender sender;

    @Test
    void send_with_empty_host_returns_false_without_throwing() {
        when(settingsService.loadGroupAsMap(SystemSettingGroups.SMTP)).thenReturn(Map.of(
                "smtp.host", "",
                "smtp.from_email", "from@example.com"
        ));
        boolean ok = sender.send("to@example.com", "subj", "body");
        assertThat(ok).isFalse();
    }

    @Test
    void sendWithDetail_with_empty_host_returns_specific_error() {
        when(settingsService.loadGroupAsMap(SystemSettingGroups.SMTP)).thenReturn(Map.of(
                "smtp.host", ""
        ));
        MailSendResult result = sender.sendWithDetail("to@example.com", "s", "b");
        assertThat(result.ok()).isFalse();
        assertThat(result.errorMessage()).isEqualTo("SMTP host is not configured");
    }

    @Test
    void from_header_uses_display_name_when_from_name_is_set() throws Exception {
        MimeMessage message = buildTestMessage(Map.of(
                "smtp.host", "smtp.example.com",
                "smtp.port", "587",
                "smtp.encryption", "tls",
                "smtp.username", "u",
                "smtp.password", "p",
                "smtp.from_name", "ULP Team",
                "smtp.from_email", "noreply@ulp.edu.vn",
                "smtp.reply_to", ""
        ));

        Address[] from = message.getFrom();
        assertThat(from).hasSize(1);
        InternetAddress addr = (InternetAddress) from[0];
        assertThat(addr.getAddress()).isEqualTo("noreply@ulp.edu.vn");
        assertThat(addr.getPersonal()).isEqualTo("ULP Team");
    }

    @Test
    void from_header_uses_bare_email_when_from_name_is_blank() throws Exception {
        MimeMessage message = buildTestMessage(Map.of(
                "smtp.host", "smtp.example.com",
                "smtp.port", "587",
                "smtp.encryption", "tls",
                "smtp.username", "u",
                "smtp.password", "p",
                "smtp.from_name", "",   // empty
                "smtp.from_email", "noreply@ulp.edu.vn",
                "smtp.reply_to", ""
        ));

        Address[] from = message.getFrom();
        assertThat(from).hasSize(1);
        InternetAddress addr = (InternetAddress) from[0];
        assertThat(addr.getAddress()).isEqualTo("noreply@ulp.edu.vn");
        assertThat(addr.getPersonal()).isNull();
    }

    @Test
    void reply_to_is_set_when_configured() throws Exception {
        MimeMessage message = buildTestMessage(Map.of(
                "smtp.host", "smtp.example.com",
                "smtp.port", "587",
                "smtp.encryption", "tls",
                "smtp.username", "u",
                "smtp.password", "p",
                "smtp.from_name", "ULP",
                "smtp.from_email", "noreply@ulp.edu.vn",
                "smtp.reply_to", "support@ulp.edu.vn"
        ));

        Address[] replyTo = message.getReplyTo();
        assertThat(replyTo).isNotEmpty();
        assertThat(((InternetAddress) replyTo[0]).getAddress())
                .isEqualTo("support@ulp.edu.vn");
    }

    // ─────────────────────────────────────────────────────────────────

    /**
     * Goi private method {@code buildMessage} de inspect headers ma khong
     * thuc su gui SMTP. Reflection vi method chua expose ra ngoai —
     * acceptable cho test boundary.
     */
    private MimeMessage buildTestMessage(Map<String, String> cfg) throws Exception {
        // Bypass send() de tranh ket noi mang. Goi build private qua reflection.
        Class<?> clazz = DbConfiguredMailSender.class;

        Field svcField = clazz.getDeclaredField("settingsService");
        svcField.setAccessible(true);

        // buildSender (private) — re-implement minimal version that uses cfg directly
        JavaMailSenderImpl impl = new JavaMailSenderImpl();
        impl.setHost(cfg.get("smtp.host"));
        impl.setPort(Integer.parseInt(cfg.get("smtp.port")));

        java.lang.reflect.Method buildMessage = clazz.getDeclaredMethod(
                "buildMessage", JavaMailSenderImpl.class, Map.class,
                String.class, String.class, String.class);
        buildMessage.setAccessible(true);

        return (MimeMessage) buildMessage.invoke(
                sender, impl, new HashMap<>(cfg),
                "to@example.com", "Subject", "Body");
    }
}
