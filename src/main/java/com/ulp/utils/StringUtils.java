package com.ulp.utils;

/**
 * String utility helpers used across feature packages. Pure-static; no
 * instances. Keep this tiny — only add a method here when at least two
 * features genuinely need it (per the project's DRY-after-three-uses rule).
 */
public final class StringUtils {

    private StringUtils() {
        // utility holder
    }

    /**
     * Returns {@code null} if the supplied string is {@code null}, empty, or
     * consists entirely of whitespace; otherwise returns the original string.
     * Used by controller and service layers when binding optional form fields
     * so that absent values land in the database as {@code NULL} rather than
     * an empty string.
     */
    public static String blankToNull(String s) {
        return (s != null && !s.isBlank()) ? s : null;
    }
}
