package com.ulp.features.flashcards.service;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Pure SM-2 spaced-repetition scheduler (SuperMemo-2). No DB, no state — kept
 * separate so it is fully unit-testable.
 *
 * <p>Recall rating maps to quality upstream: Không nhớ=1, Khó=3, Tốt=4, Dễ=5.
 * Given a quality and the prior (EF, repetitions, interval), it returns the new
 * SM-2 state and the next-due timestamp:
 * <ul>
 *   <li>EF += 0.1 − (5−q)·(0.08 + (5−q)·0.02), floored at {@value #MIN_EF}.</li>
 *   <li>q &lt; 3 → repetitions reset to 0, interval reset to 1 day.</li>
 *   <li>otherwise interval grows: reps 0→1, reps 1→6, else round(interval·EF);
 *       repetitions increment.</li>
 *   <li>next review = now + interval days.</li>
 * </ul>
 */
@Component
public class Sm2Scheduler {

    /** Easiness factor never drops below this floor. */
    public static final double MIN_EF = 1.30;

    /** Default easiness factor for a card with no prior review. */
    public static final double DEFAULT_EF = 2.50;

    /** Immutable result of a scheduling step. */
    public record Sm2State(double easinessFactor, int repetitions,
                           int intervalDays, LocalDateTime nextReviewAt) {
    }

    /**
     * Computes the next SM-2 state for a review.
     *
     * @param quality       recall quality 0..5 (1/3/4/5 in this feature)
     * @param priorEf       easiness factor before this review
     * @param priorReps     repetition count before this review
     * @param priorInterval interval (days) before this review
     * @param now           reference time for the next-due calculation
     * @return the new EF / repetitions / interval / next-due timestamp
     */
    public Sm2State schedule(int quality, double priorEf, int priorReps,
                             int priorInterval, LocalDateTime now) {
        double ef = priorEf + (0.1 - (5 - quality) * (0.08 + (5 - quality) * 0.02));
        if (ef < MIN_EF) {
            ef = MIN_EF;
        }

        int repetitions;
        int intervalDays;
        if (quality < 3) {
            // Poor recall: relearn from scratch tomorrow.
            repetitions = 0;
            intervalDays = 1;
        } else {
            if (priorReps == 0) {
                intervalDays = 1;
            } else if (priorReps == 1) {
                intervalDays = 6;
            } else {
                intervalDays = (int) Math.round(priorInterval * ef);
            }
            repetitions = priorReps + 1;
        }

        LocalDateTime nextReviewAt = now.plusDays(intervalDays);
        return new Sm2State(ef, repetitions, intervalDays, nextReviewAt);
    }
}
