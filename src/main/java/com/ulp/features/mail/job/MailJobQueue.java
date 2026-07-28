package com.ulp.features.mail.job;

/**
 * Queue of outbound {@link MailJob}s drained by a dedicated worker thread.
 *
 * <p>Implementations MUST be thread-safe. The default bean is an in-process
 * bounded queue ({@link InMemoryMailJobQueue}); a future Redis/DB-backed
 * implementation can replace it without touching callers.
 *
 * <p><b>Contract for producers</b>
 * <ul>
 *   <li>Do not call SMTP on the request thread for bulk or non-critical mail.</li>
 *   <li>Prefer {@link MailJobEnqueueHelper#enqueueAfterCommit} so the worker
 *       never races a transaction that has not committed yet.</li>
 *   <li>Treat {@link #enqueue(MailJob)} as best-effort: a full queue returns
 *       {@code false} and the caller logs; it must not fail the business action.</li>
 * </ul>
 *
 * @see .claude/rules/mail-job-queue.md
 */
public interface MailJobQueue {

    /**
     * Offers one job to the queue without blocking the caller.
     *
     * @param job the work unit; must not be {@code null}
     * @return {@code true} when accepted, {@code false} when the queue is full
     *         or shut down (caller should log and continue)
     */
    boolean enqueue(MailJob job);

    /**
     * Blocks until a job is available or the worker is interrupted.
     * Used only by the mail worker thread.
     *
     * @return the next job
     * @throws InterruptedException when the worker is shutting down
     */
    MailJob take() throws InterruptedException;

    /** Approximate number of jobs waiting (for metrics / admin diagnostics). */
    int size();
}
