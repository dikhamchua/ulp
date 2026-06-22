package com.ulp.auth;

/**
 * 4 vai tro he thong cua ULP — khop voi CHECK constraint cua cot users.role:
 * CHECK (role IN ('STUDENT','LECTURER','HEAD','ADMIN')).
 *
 * <p>Quan he ke thua nghiep vu: HEAD ke thua quyen cua LECTURER.
 * Spring Security authority dang {@code "ROLE_<name>"} duoc tao tu
 * {@link #authority()}; cac SpEL constant ({@link Roles}) dung trong
 * {@code @PreAuthorize} de tranh string literal lap lai trong codebase.
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
