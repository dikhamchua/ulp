package com.ulp.features.mail;

import org.springframework.stereotype.Service;

/**
 * Email facade for internal callers (e.g. {@code PasswordRecoveryService}).
 *
 * <p>Since Sprint 2, this bean is always present — it is no longer conditional
 * on {@code spring.mail.host}. SMTP configuration is loaded from the
 * {@code system_settings} table at runtime via {@link DbConfiguredMailSender}.
 *
 * <p>Contract: {@link #send} returns {@code true} on success and {@code false}
 * when SMTP is not yet configured or delivery fails. Callers use the boolean
 * return value to decide on a fallback strategy (e.g. logging the reset link
 * to the console instead of sending it by email).
 */
@Service
public class MailService {

    private final DbConfiguredMailSender sender;

    public MailService(DbConfiguredMailSender sender) {
        this.sender = sender;
    }

    /**
     * Sends an email on a best-effort basis.
     *
     * @param to      recipient email address
     * @param subject email subject line
     * @param body    email body (plain text or HTML)
     * @return {@code true} if the message was delivered successfully,
     *         {@code false} if SMTP is unconfigured or delivery failed
     */
    public boolean send(String to, String subject, String body) {
        return sender.send(to, subject, body);
    }

    /**
     * Sends an email and surfaces error details on failure — intended for the
     * test-send action on the admin settings page. Callers use the error
     * message to display a toast notification.
     *
     * @param to      recipient email address
     * @param subject email subject line
     * @param body    email body (plain text or HTML)
     * @return a {@link MailSendResult} indicating success or containing an
     *         error message if delivery failed
     */
    public MailSendResult sendWithDetail(String to, String subject, String body) {
        return sender.sendWithDetail(to, subject, body);
    }
}
