package com.ulp.utils;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * Turns a display name into a URL-safe slug. Vietnamese-aware: strips
 * diacritics, maps {@code đ/Đ} to {@code d}, lowercases, and collapses any run
 * of non-alphanumeric characters into a single hyphen.
 *
 * <p>Examples: {@code "Lập trình" -> "lap-trinh"}, {@code "C#/.NET" -> "c-net"},
 * a blank input yields the {@link #FALLBACK} slug so a caller never persists an
 * empty slug (the DB column is {@code NOT NULL UNIQUE}).
 *
 * <p>Uniqueness suffixing ({@code -2}, {@code -3}…) is NOT done here — it lives
 * in the service, which knows the repository. This class is a pure function.
 */
public final class Slugify {

    /** Slug returned when the input normalises to nothing (e.g. only symbols). */
    public static final String FALLBACK = "danh-muc";

    // Combining diacritical marks left over after NFD normalisation.
    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");
    // Any run of characters that are not ASCII letters or digits.
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");
    // Leading/trailing hyphens to trim after collapsing.
    private static final Pattern EDGE_HYPHENS = Pattern.compile("(^-+)|(-+$)");

    private Slugify() {
        // utility holder
    }

    /**
     * Builds a slug from the supplied name. Returns {@link #FALLBACK} when the
     * input is {@code null}, blank, or contains no alphanumeric content.
     */
    public static String slugify(String input) {
        if (input == null || input.isBlank()) {
            return FALLBACK;
        }
        // đ/Đ are not decomposed by NFD, so replace them explicitly first.
        String pre = input.replace('đ', 'd').replace('Đ', 'D');
        String normalized = Normalizer.normalize(pre, Normalizer.Form.NFD);
        String noMarks = COMBINING_MARKS.matcher(normalized).replaceAll("");
        String lowered = noMarks.toLowerCase();
        String hyphenated = NON_ALNUM.matcher(lowered).replaceAll("-");
        String trimmed = EDGE_HYPHENS.matcher(hyphenated).replaceAll("");
        return trimmed.isEmpty() ? FALLBACK : trimmed;
    }
}
