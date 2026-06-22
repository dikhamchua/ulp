package com.ulp.shared.mail;

import com.ulp.shared.settings.SystemSettingGroups;
import com.ulp.shared.settings.repository.SystemSettingsRepository;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

import java.io.UnsupportedEncodingException;
import java.util.Map;
import java.util.Properties;

/**
 * Mail transport doc cau hinh SMTP tu bang {@code system_settings} tai send-time.
 *
 * <p>Mo hinh: moi lan goi {@link #send} (hoac {@link #sendWithDetail}) se:
 * <ol>
 *   <li>Load cac row group {@code SMTP} ra map.</li>
 *   <li>Short-circuit voi {@code false} (warn-log) khi {@code smtp.host} rong.</li>
 *   <li>Build mot {@link JavaMailSenderImpl} moi, set timeout, encryption.</li>
 *   <li>Build {@link MimeMessage}, set From/Reply-To, gui qua mail server.</li>
 * </ol>
 *
 * <p>Per-call instantiation: chi phi nho hon mot SMTP RTT — chap nhan duoc
 * voi MVP volume. Khong cache nen khong co stale-config bug khi admin
 * sua setting o {@code /admin/settings/email}.
 *
 * <p>Property keys cua JavaMail bat buoc lowercase: {@code mail.smtp.connectiontimeout}
 * va {@code mail.smtp.timeout}. CamelCase bi silently ignored => UI hang khi
 * bad host. Han hu timeout 10 giay.
 */
@Component
public class DbConfiguredMailSender {

    private static final Logger log = LoggerFactory.getLogger(DbConfiguredMailSender.class);

    private static final String GROUP = SystemSettingGroups.SMTP;
    private static final int DEFAULT_PORT = 587;
    private static final int TIMEOUT_MS = 10_000;

    private final SystemSettingsRepository repository;

    public DbConfiguredMailSender(SystemSettingsRepository repository) {
        this.repository = repository;
    }

    /**
     * Gui email best-effort. Tra ve {@code true} neu thanh cong,
     * {@code false} neu SMTP chua cau hinh hoac co loi (chi log warn).
     */
    public boolean send(String to, String subject, String body) {
        return sendWithDetail(to, subject, body).ok();
    }

    /**
     * Gui email va tra ve ket qua kem error message khi that bai —
     * dung cho test-send endpoint can surface SMTP error ra UI toast.
     */
    public MailSendResult sendWithDetail(String to, String subject, String body) {
        Map<String, String> cfg = repository.loadGroupAsMap(GROUP);

        String host = cfg.getOrDefault("smtp.host", "").trim();
        if (host.isEmpty()) {
            log.warn("SMTP host is not configured — skipping send to {}", to);
            return MailSendResult.failure("SMTP host is not configured");
        }

        JavaMailSenderImpl sender = buildSender(cfg, host);

        try {
            MimeMessage message = buildMessage(sender, cfg, to, subject, body);
            sender.send(message);
            log.info("Email sent to {} via {}", to, host);
            return MailSendResult.success();
        } catch (MailException | MessagingException | UnsupportedEncodingException e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("Failed to send email to {}: {}", to, reason);
            return MailSendResult.failure(reason);
        }
    }

    // ─────────────────────────────────────────────────────────────────

    private JavaMailSenderImpl buildSender(Map<String, String> cfg, String host) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(host);
        sender.setPort(parsePort(cfg.get("smtp.port")));
        sender.setUsername(cfg.getOrDefault("smtp.username", ""));
        sender.setPassword(cfg.getOrDefault("smtp.password", ""));

        Properties props = sender.getJavaMailProperties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.connectiontimeout", String.valueOf(TIMEOUT_MS));
        props.put("mail.smtp.timeout", String.valueOf(TIMEOUT_MS));
        props.put("mail.smtp.writetimeout", String.valueOf(TIMEOUT_MS));

        String encryption = cfg.getOrDefault("smtp.encryption", "none").trim().toLowerCase();
        switch (encryption) {
            case "tls" -> props.put("mail.smtp.starttls.enable", "true");
            case "ssl" -> props.put("mail.smtp.ssl.enable", "true");
            default -> {
                /* none — no extra props */
            }
        }
        return sender;
    }

    private MimeMessage buildMessage(JavaMailSenderImpl sender, Map<String, String> cfg,
                                     String to, String subject, String body)
            throws MessagingException, UnsupportedEncodingException {
        MimeMessage message = sender.createMimeMessage();
        message.setSubject(subject, "UTF-8");
        message.setText(body, "UTF-8");
        message.setRecipients(jakarta.mail.Message.RecipientType.TO, to);

        String fromEmail = cfg.getOrDefault("smtp.from_email", "").trim();
        if (fromEmail.isEmpty()) {
            // Khong co from_email — JavaMail se throw, dat tam mot gia tri an toan
            fromEmail = cfg.getOrDefault("smtp.username", "noreply@localhost");
        }
        String fromName = cfg.getOrDefault("smtp.from_name", "").trim();
        if (fromName.isEmpty()) {
            message.setFrom(new InternetAddress(fromEmail));
        } else {
            message.setFrom(new InternetAddress(fromEmail, fromName, "UTF-8"));
        }

        String replyTo = cfg.getOrDefault("smtp.reply_to", "").trim();
        if (!replyTo.isEmpty()) {
            message.setReplyTo(new InternetAddress[]{new InternetAddress(replyTo)});
        }
        return message;
    }

    private Integer parsePort(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_PORT;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("smtp.port '{}' is not a valid integer — falling back to {}", raw, DEFAULT_PORT);
            return DEFAULT_PORT;
        }
    }
}
