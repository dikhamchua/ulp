package com.ulp.features.admin.users.dto;

/**
 * Filter bound from the admin user-list page's query parameters. All fields
 * are optional; empty strings are treated as "no filter" by the repository SQL.
 */
public record UserFilter(String q, String role, String status, String sort) {

    public static UserFilter empty() {
        return new UserFilter(null, null, null, null);
    }

    /** Convenience for templates: returns the supplied default when blank. */
    public String qOr(String fallback) {
        return (q == null || q.isBlank()) ? fallback : q;
    }
}
