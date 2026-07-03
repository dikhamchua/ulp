package com.ulp.features.flashcards.service;

import com.ulp.features.flashcards.service.Sm2Scheduler.Sm2State;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the pure {@link Sm2Scheduler}: q&lt;3 reset, the 1→6→×EF
 * interval progression, and the EF floor of 1.30.
 */
class Sm2SchedulerTest {

    private final Sm2Scheduler scheduler = new Sm2Scheduler();
    private final LocalDateTime now = LocalDateTime.of(2026, 1, 1, 8, 0);

    @Test
    void poor_recall_resets_repetitions_and_interval() {
        // A card with an established schedule, rated "Không nhớ" (q=1).
        Sm2State s = scheduler.schedule(1, 2.5, 4, 30, now);

        assertThat(s.repetitions()).isZero();
        assertThat(s.intervalDays()).isEqualTo(1);
        assertThat(s.nextReviewAt()).isEqualTo(now.plusDays(1));
    }

    @Test
    void successful_recall_grows_interval_1_then_6_then_times_ef() {
        // First success on a new card (q=4 keeps EF at 2.5).
        Sm2State first = scheduler.schedule(4, 2.5, 0, 1, now);
        assertThat(first.intervalDays()).isEqualTo(1);
        assertThat(first.repetitions()).isEqualTo(1);
        assertThat(first.easinessFactor()).isEqualTo(2.5);

        // Second success → interval 6.
        Sm2State second = scheduler.schedule(4, first.easinessFactor(),
                first.repetitions(), first.intervalDays(), now);
        assertThat(second.intervalDays()).isEqualTo(6);
        assertThat(second.repetitions()).isEqualTo(2);

        // Third success → round(interval × EF) = round(6 × 2.5) = 15.
        Sm2State third = scheduler.schedule(4, second.easinessFactor(),
                second.repetitions(), second.intervalDays(), now);
        assertThat(third.intervalDays()).isEqualTo(15);
        assertThat(third.repetitions()).isEqualTo(3);
        assertThat(third.nextReviewAt()).isEqualTo(now.plusDays(15));
    }

    @Test
    void easiness_factor_never_drops_below_floor() {
        // Repeated "Không nhớ" (q=1) drives EF down by 0.54 each step; it must
        // clamp at the 1.30 floor.
        double ef = 2.5;
        for (int i = 0; i < 10; i++) {
            ef = scheduler.schedule(1, ef, 0, 1, now).easinessFactor();
        }
        assertThat(ef).isEqualTo(Sm2Scheduler.MIN_EF);
    }

    @Test
    void easy_rating_raises_easiness_factor() {
        // q=5 → EF += 0.1, so 2.5 → 2.6.
        Sm2State s = scheduler.schedule(5, 2.5, 0, 1, now);
        assertThat(s.easinessFactor()).isEqualTo(2.6);
    }
}
