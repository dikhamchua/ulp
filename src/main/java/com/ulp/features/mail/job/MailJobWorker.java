package com.ulp.features.mail.job;

import com.ulp.features.mail.MailService;
import com.ulp.features.notifications.repository.NotificationRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Single dedicated daemon thread that drains {@link MailJobQueue} and sends
 * mail through {@link MailService}.
 *
 * <p>One thread is enough for ULP's volume and keeps SMTP connection behaviour
 * simple (no parallel storms against a flaky host). Scale later by raising the
 * worker count behind the same {@link MailJobQueue} interface — callers do not
 * change.
 *
 * <p>Failures are logged and swallowed so one bad address cannot kill the
 * worker loop. When {@link MailJob#notificationId()} is set and send returns
 * {@code true}, {@code notifications.is_email_sent} is flipped in a separate
 * short DB update.
 */
@Component
public class MailJobWorker {

    private static final Logger log = LoggerFactory.getLogger(MailJobWorker.class);

    private final MailJobQueue queue;
    private final MailService mailService;
    private final NotificationRepository notificationRepository;
    private final InMemoryMailJobQueue inMemoryQueue;

    private Thread worker;
    private volatile boolean running;

    public MailJobWorker(MailJobQueue queue,
                         MailService mailService,
                         NotificationRepository notificationRepository,
                         InMemoryMailJobQueue inMemoryQueue) {
        this.queue = queue;
        this.mailService = mailService;
        this.notificationRepository = notificationRepository;
        this.inMemoryQueue = inMemoryQueue;
    }

    @PostConstruct
    void start() {
        running = true;
        worker = new Thread(this::loop, "ulp-mail-job-worker");
        worker.setDaemon(true);
        worker.start();
        log.info("Mail job worker started");
    }

    @PreDestroy
    void stop() {
        running = false;
        inMemoryQueue.stopAccepting();
        if (worker != null) {
            worker.interrupt();
            try {
                worker.join(3_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        log.info("Mail job worker stopped (queue leftover={})", queue.size());
    }

    private void loop() {
        while (running) {
            try {
                MailJob job = queue.take();
                process(job);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // Never die on a single bad job.
                log.error("Unexpected error in mail worker loop: {}", e.getMessage(), e);
            }
        }
    }

    private void process(MailJob job) {
        try {
            boolean sent = mailService.send(job.to(), job.subject(), job.body());
            if (sent && job.notificationId() != null) {
                markEmailSent(job.notificationId());
            } else if (!sent) {
                log.warn("Mail job not delivered source={} to={} notifId={}",
                        job.source(), job.to(), job.notificationId());
            }
        } catch (Exception e) {
            log.warn("Mail job failed source={} to={}: {}",
                    job.source(), job.to(), e.getMessage());
        }
    }

    private void markEmailSent(Long notificationId) {
        try {
            notificationRepository.findById(notificationId).ifPresent(n -> {
                if (!n.isEmailSent()) {
                    n.setEmailSent(true);
                    notificationRepository.save(n);
                }
            });
        } catch (Exception e) {
            log.warn("Could not mark notification {} email-sent: {}",
                    notificationId, e.getMessage());
        }
    }
}
