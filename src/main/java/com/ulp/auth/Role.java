package com.ulp.auth;

/**
 * The four system roles of ULP, matching the CHECK constraint on the {@code users.role} column:
 * {@code CHECK (role IN ('STUDENT','LECTURER','HEAD','ADMIN'))}.
 *
 * <p>Business hierarchy: {@code HEAD} inherits all permissions of {@code LECTURER}.
 * Spring Security authority strings of the form {@code "ROLE_<name>"} are produced by
 * {@link #authority()}; SpEL constants in {@link Roles} are used inside
 * {@code @PreAuthorize} expressions to avoid repeating string literals across the codebase.
 */
public enum Role {
    STUDENT,
    LECTURER,
    HEAD,
    ADMIN;

    /** Spring Security authority string (e.g. "ROLE_LECTURER"). */
    public String authority() {
        return "ROLE_" + name();
    }
}
