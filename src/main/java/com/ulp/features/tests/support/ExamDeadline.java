package com.ulp.features.tests.support;

import com.ulp.features.tests.entity.Test;
import com.ulp.features.tests.entity.TestAttempt;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Authoritative deadline computation shared by the taking view and the submit
 * path. The client-side countdown is advisory; the server always recomputes the
 * deadline here so a closed laptop or clock-skewed browser cannot gain time.
 *
 * <ul>
 *   <li>{@code FIXED_WINDOW} → deadline is the exam's {@code end_at}.</li>
 *   <li>{@code INDIVIDUAL} → deadline is {@code started_at + duration_minutes}.</li>
 * </ul>
 * A test with no resolvable deadline (e.g. a practice test) has no timer.
 */
public final class ExamDeadline {

    /** Signals "no timer" to the client (hide countdown, no auto-submit). */
    public static final long NO_TIMER = -1L;

    /** Small grace window so a submit fired exactly at zero isn't penalised. */
    public static final long GRACE_SECONDS = 5L;

    private ExamDeadline() {
        // utility holder
    }

    /** The attempt's absolute deadline, or {@code null} when the exam has no timer. */
    public static LocalDateTime deadline(Test test, TestAttempt attempt) {
        if (test.isIndividualTimer()) {
            Integer minutes = test.getDurationMinutes();
            if (minutes == null || minutes <= 0) return null;
            return attempt.getStartedAt().plusMinutes(minutes);
        }
        // FIXED_WINDOW: the shared exam end time.
        return test.getEndAt();
    }

    /**
     * Remaining seconds from {@code now} to the deadline, clamped at 0, or
     * {@link #NO_TIMER} when the exam has no timer.
     */
    public static long remainingSeconds(Test test, TestAttempt attempt, LocalDateTime now) {
        LocalDateTime deadline = deadline(test, attempt);
        if (deadline == null) return NO_TIMER;
        long secs = Duration.between(now, deadline).getSeconds();
        return Math.max(secs, 0);
    }

    /** True when {@code now} is past the deadline beyond the grace window. */
    public static boolean isPastDeadline(Test test, TestAttempt attempt, LocalDateTime now) {
        LocalDateTime deadline = deadline(test, attempt);
        if (deadline == null) return false;
        return now.isAfter(deadline.plusSeconds(GRACE_SECONDS));
    }
}
