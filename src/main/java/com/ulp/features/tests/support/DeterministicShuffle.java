package com.ulp.features.tests.support;

import java.util.List;
import java.util.Random;

/**
 * Seeded Fisher–Yates shuffle so a resumed/reloaded attempt renders questions
 * and options in the same order every time. The seed is derived from the
 * attempt id (and question id for options), keeping answers aligned across
 * reloads without persisting any ordering.
 */
public final class DeterministicShuffle {

    private DeterministicShuffle() {
        // utility holder
    }

    /** Shuffles {@code items} in place using {@code seed}. */
    public static <T> void shuffle(List<T> items, long seed) {
        Random rnd = new Random(seed);
        for (int i = items.size() - 1; i > 0; i--) {
            int j = rnd.nextInt(i + 1);
            T tmp = items.get(i);
            items.set(i, items.get(j));
            items.set(j, tmp);
        }
    }

    /** Seed for question order within an attempt. */
    public static long questionSeed(long attemptId) {
        return attemptId * 1_000_003L;
    }

    /** Seed for option order within an attempt + question (stable per pair). */
    public static long optionSeed(long attemptId, long questionId) {
        return attemptId * 1_000_003L + questionId * 31L;
    }
}
