package com.ulp.features.mail.job;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke tests for the bounded in-process mail queue.
 */
class InMemoryMailJobQueueTest {

    @Test
    void enqueue_and_take_round_trip() throws InterruptedException {
        InMemoryMailJobQueue queue = new InMemoryMailJobQueue();
        MailJob job = MailJob.of("a@b.c", "s", "b", "TEST");

        assertThat(queue.enqueue(job)).isTrue();
        assertThat(queue.size()).isEqualTo(1);
        assertThat(queue.take()).isEqualTo(job);
        assertThat(queue.size()).isZero();
    }

    @Test
    void enqueue_returns_false_after_stop_accepting() {
        InMemoryMailJobQueue queue = new InMemoryMailJobQueue();
        queue.stopAccepting();

        assertThat(queue.enqueue(MailJob.of("a@b.c", "s", "b", "TEST"))).isFalse();
    }
}
