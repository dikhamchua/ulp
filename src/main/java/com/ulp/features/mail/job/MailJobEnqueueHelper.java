package com.ulp.features.mail.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Helper that enqueues {@link MailJob}s only after the surrounding transaction
 * has committed.
 *
 * <p>Why after-commit: the mail worker runs on another thread. If it picked up
 * a job that references a notification row (or any row) written in the same
 * request transaction before that transaction commits, the worker would see
 * stale data or a missing row under READ COMMITTED / REPEATABLE READ.
 *
 * <p>When there is no active transaction, the job is enqueued immediately.
 *
 * <p><b>Usage (for future AI / human implementers)</b>
 * <pre>{@code
 * // Inside a @Transactional service method, AFTER saving domain state:
 * mailJobEnqueueHelper.enqueueAfterCommit(
 *     MailJob.forNotification(email, subject, body, notifId, "MY_FEATURE"));
 * }</pre>
 *
 * <p>Never call {@code MailService.send} in a loop on the request thread for
 * fan-out (class publish, bulk invites, …). That blocks the HTTP response
 * behind SMTP timeouts. Use this helper instead.
 *
 * @see .claude/rules/mail-job-queue.md
 */
@Component
public class MailJobEnqueueHelper {

    private static final Logger log = LoggerFactory.getLogger(MailJobEnqueueHelper.class);

    private final MailJobQueue queue;

    public MailJobEnqueueHelper(MailJobQueue queue) {
        this.queue = queue;
    }

    /**
     * Schedules {@code job} to enter the queue after a successful commit,
     * or immediately when no transaction is active.
     *
     * @param job the mail work unit
     */
    public void enqueueAfterCommit(MailJob job) {
        if (job == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            offer(job);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                offer(job);
            }
        });
    }

    private void offer(MailJob job) {
        boolean accepted = queue.enqueue(job);
        if (!accepted) {
            log.warn("Failed to enqueue mail job source={} to={}", job.source(), job.to());
        }
    }
}
