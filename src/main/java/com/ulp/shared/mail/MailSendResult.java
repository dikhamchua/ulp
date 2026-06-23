package com.ulp.shared.mail;

/**
 * Represents the result of a single email send attempt. Defined as a top-level
 * record so that upper layers (admin/service) can import it without depending
 * on the concrete transport implementation ({@link DbConfiguredMailSender}).
 *
 * @param ok           {@code true} when the email was sent successfully
 * @param errorMessage description of the error when {@code ok} is {@code false};
 *                     {@code null} on success
 */
public record MailSendResult(boolean ok, String errorMessage) {

    public static MailSendResult success() {
        return new MailSendResult(true, null);
    }

    public static MailSendResult failure(String errorMessage) {
        return new MailSendResult(false, errorMessage);
    }
}
