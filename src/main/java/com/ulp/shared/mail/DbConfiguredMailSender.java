package com.ulp.shared.mail;

import com.ulp.shared.settings.SystemSettingGroups;
import com.ulp.shared.settings.service.SystemSettingsService;
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
 * Mail transport that reads SMTP configuration from the {@code system_settings} table at send-time.
 *
 * <p>Each call to {@link #send} (or {@link #sendWithDetail}) follows this sequence:
 * <ol>
 *   <li>Load all rows in the {@code SMTP} settings group through the cached
 *       {@link SystemSettingsService} (typical hit cost: in-memory map lookup;
 *       cold reads hit MySQL).</li>
 *   <li>Short-circuit with {@code false} (warn-log) when {@code smtp.host} is empty.</li>
 *   <li>Build a new {@link JavaMailSenderImpl} instance with timeouts and encryption settings.</li>
 *   <li>Build a {@link MimeMessage}, set From/Reply-To headers, and dispatch via the mail server.</li>
 * </ol>
 *
 * <p>The configuration map is served from the {@code settingsGroup} Caffeine
 * cache; {@code EmailSettingsService.save} evicts the {@code SMTP} entry on
 * admin save so changes propagate immediately.
 *
 * <p>JavaMail property keys must be lowercase: {@code mail.smtp.connectiontimeout}
 * and {@code mail.smtp.timeout}. CamelCase variants are silently ignored, which causes
 * the UI to hang on a bad host. All timeouts are capped at 10 seconds.
 */
@Component
public class DbConfiguredMailSender {

    private static final Logger log = LoggerFactory.getLogger(DbConfiguredMailSender.class);

    private static final String GROUP = SystemSettingGroups.SMTP;
    private static final int DEFAULT_PORT = 587;
    private static final int TIMEOUT_MS = 10_000;

    private final SystemSettingsService settingsService;

    public DbConfiguredMailSender(SystemSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    /**
     * Sends an email on a best-effort basis.
     *
     * @param to      recipient email address
     * @param subject email subject line
     * @param body    plain-text email body
     * @return {@code true} if the message was accepted by the mail server;
     *         {@code false} if SMTP is not configured or any error occurs (logged at WARN)
     */
    public boolean send(String to, String subject, String body) {
        return sendWithDetail(to, subject, body).ok();
    }

    /**
     * Sends an email and returns a result object that includes an error message on failure.
     * Prefer this over {@link #send} when the caller needs to surface SMTP errors to the UI
     * (e.g. the test-send endpoint).
     *
     * @param to      recipient email address
     * @param subject email subject line
     * @param body    plain-text email body
     * @return {@link MailSendResult#ok()} on success, or a failure result with a reason string
     */
    public MailSendResult sendWithDetail(String to, String subject, String body) {
        Map<String, String> cfg = settingsService.loadGroupAsMap(GROUP);

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
            // No from_email configured — fall back to smtp.username to avoid a JavaMail exception
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
