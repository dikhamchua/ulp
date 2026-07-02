package com.ulp.features.classes.service.support;

/**
 * Completion-percent + bucket helper for the lecturer progress dashboard.
 *
 * <p>Single source of truth for the percent formula shared with the student
 * view: the {@link #percent(int, int)} rule mirrors the canonical definition in
 * {@code StudentLessonsService} ({@code percent = total==0 ? 0 :
 * round(completed*100/total)}, half-up via {@link Math#round}). Keep the two in
 * sync so the lecturer and student surfaces never disagree.
 */
public final class ProgressMath {

    public static final String BUCKET_ALL         = "all";
    public static final String BUCKET_NOT_STARTED = "not-started";
    public static final String BUCKET_IN_PROGRESS = "in-progress";
    public static final String BUCKET_COMPLETED   = "completed";

    private ProgressMath() {
        // utility holder
    }

    /** Integer completion percent, rounded half-up; 0 when the class has no lessons. */
    public static int percent(int completed, int total) {
        return total == 0 ? 0 : (int) Math.round((double) completed * 100 / total);
    }

    /**
     * Classifies a student into a progress bucket.
     * completed: total &gt; 0 and completed == total;
     * not-started: no activity at all (never opened a lesson) and 0 completed;
     * in-progress: any activity (opened a lesson) but not all completed.
     *
     * @param hasActivity true when the student has opened at least one lesson,
     *                    so "opened but 0 completed" counts as in-progress
     */
    public static String bucket(int completed, int total, boolean hasActivity) {
        if (total > 0 && completed == total) return BUCKET_COMPLETED;
        if (!hasActivity && completed == 0) return BUCKET_NOT_STARTED;
        return BUCKET_IN_PROGRESS;
    }
}
