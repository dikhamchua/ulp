package com.ulp.features.admin.users.dto;

import java.time.LocalDateTime;

/**
 * Spring Data JPA native-query projection — one row per user surfaced on
 * the admin list page. The native SQL in
 * {@code UserRepository.searchUsersForAdmin} aliases columns to match
 * these accessor names.
 *
 * <p>Status fields are exposed as raw booleans rather than a pre-computed
 * label so the template can both render the pill colour AND decide which
 * kebab-menu actions to display per row (e.g. show "Unlock" only when
 * {@code locked = true}).
 */
public interface UserRow {
    Long getId();
    String getFullName();
    String getEmail();
    String getRole();
    boolean isActive();
    boolean isLocked();
    boolean isDeleted();
    Long getDepartmentId();
    LocalDateTime getLastLoginAt();
    LocalDateTime getCreatedAt();
    String getAvatarUrl();

    /**
     * Computed status label used by the template for the status pill.
     * Ordering matters: a deleted row is reported as DELETED regardless
     * of its active/locked flags, then locked, then inactive, then active.
     *
     * @return one of {@code ACTIVE | INACTIVE | LOCKED | DELETED}
     */
    default String statusLabel() {
        if (isDeleted()) return "DELETED";
        if (isLocked())  return "LOCKED";
        if (!isActive()) return "INACTIVE";
        return "ACTIVE";
    }
}
