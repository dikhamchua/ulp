package com.ulp.features.mail.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Process-local bounded {@link MailJobQueue}.
 *
 * <p>Capacity is fixed at {@link #CAPACITY}. When full, {@link #enqueue} refuses
 * the job (returns {@code false}) instead of blocking the request thread —
 * dropping mail is preferable to hanging "Xuất bản" or similar UI actions.
 *
 * <p>Not durable across restarts. That is intentional for the current SSR stack
 * (no Redis/Kafka). Critical transactional mail that must not be lost (e.g.
 * password reset) should still call {@code MailService.send} synchronously
 * on the request path, or a future durable queue implementation.
 */
@Component
public class InMemoryMailJobQueue implements MailJobQueue {

    private static final Logger log = LoggerFactory.getLogger(InMemoryMailJobQueue.class);

    /** Hard cap so a mail storm cannot exhaust heap. */
    static final int CAPACITY = 2_000;

    private final BlockingQueue<MailJob> queue = new ArrayBlockingQueue<>(CAPACITY);
    private final AtomicBoolean accepting = new AtomicBoolean(true);

    @Override
    public boolean enqueue(MailJob job) {
        Objects.requireNonNull(job, "job");
        if (!accepting.get()) {
            log.warn("Mail queue shut down; dropped job source={}", job.source());
            return false;
        }
        boolean ok = queue.offer(job);
        if (!ok) {
            log.warn("Mail queue full (cap={}); dropped job source={} to={}",
                    CAPACITY, job.source(), job.to());
        }
        return ok;
    }

    @Override
    public MailJob take() throws InterruptedException {
        return queue.take();
    }

    @Override
    public int size() {
        return queue.size();
    }

    /** Stops accepting new jobs (called on application shutdown). */
    void stopAccepting() {
        accepting.set(false);
    }
}
