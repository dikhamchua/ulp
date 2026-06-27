package com.ulp.features.admin.users.dto;

/** Closed enumeration of admissible status filter values on the admin user list. */
public enum StatusFilter {
    ACTIVE,
    INACTIVE,
    LOCKED,
    DELETED;

    /** Parses a raw query-string value, returning {@code null} when blank/invalid. */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return StatusFilter.valueOf(raw.trim().toUpperCase()).name();
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
