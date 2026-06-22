package com.ulp.auth;

/**
 * SpEL-compatible role constants for {@code @PreAuthorize} expressions
 * and {@code hasRole(...)} / {@code hasAnyRole(...)} filter chain calls.
 *
 * <p>Spring Security expression engine works on string literals, so we keep
 * these as compile-time constants of {@link Role#name()}. This eliminates
 * the "LECTURER" / "HEAD" / "ADMIN" string duplication across
 * {@code SecurityConfig}, controllers, and Thymeleaf {@code sec:authorize}.
 *
 * <p>Common group:
 * {@link #LECTURER_OR_ABOVE} = LECTURER, HEAD, ADMIN — i.e. anyone able to
 * teach or manage classes.
 */
public final class Roles {

    public static final String STUDENT  = "STUDENT";
    public static final String LECTURER = "LECTURER";
    public static final String HEAD     = "HEAD";
    public static final String ADMIN    = "ADMIN";

    /** Comma-separated list usable in hasAnyRole('LECTURER','HEAD','ADMIN'). */
    public static final String LECTURER_OR_ABOVE = "'LECTURER','HEAD','ADMIN'";

    /** Full SpEL for @PreAuthorize on lecturer-side endpoints. */
    public static final String PREAUTH_LECTURER_OR_ABOVE =
            "hasAnyRole(" + LECTURER_OR_ABOVE + ")";

    private Roles() {
        // utility class
    }
}
