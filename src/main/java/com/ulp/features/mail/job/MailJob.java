package com.ulp.features.mail.job;

/**
 * One unit of outbound email work for the background mail worker.
 *
 * <p>Callers never send SMTP on the request thread for bulk or non-critical
 * mail. They build a {@code MailJob} and hand it to {@link MailJobQueue}
 * (typically via {@link MailJobEnqueueHelper#enqueueAfterCommit}).
 *
 * <p>{@link #notificationId()} is optional. When present and delivery succeeds,
 * the worker flips {@code notifications.is_email_sent = true} for that row.
 * Password-reset and admin test-send do not set it.
 *
 * @param to             recipient address
 * @param subject        subject line
 * @param body           plain-text or HTML body
 * @param notificationId optional notification row to mark sent; may be {@code null}
 * @param source         short label for logs (e.g. {@code LESSON_PUBLISHED}, {@code PASSWORD_RESET})
 */
public record MailJob(
        String to,
        String subject,
        String body,
        Long notificationId,
        String source
) {
    /**
     * Builds a job with no notification correlation (standalone mail).
     *
     * @param to      recipient
     * @param subject subject
     * @param body    body
     * @param source  log label
     * @return a job with {@code notificationId == null}
     */
    public static MailJob of(String to, String subject, String body, String source) {
        return new MailJob(to, subject, body, null, source);
    }

    /**
     * Builds a job that will mark a notification row sent on success.
     *
     * @param to             recipient
     * @param subject        subject
     * @param body           body
     * @param notificationId persisted notification id
     * @param source         log label
     * @return a correlated job
     */
    public static MailJob forNotification(String to, String subject, String body,
                                          Long notificationId, String source) {
        return new MailJob(to, subject, body, notificationId, source);
    }
}
