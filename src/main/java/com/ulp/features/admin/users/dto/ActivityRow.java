package com.ulp.features.admin.users.dto;

import java.time.LocalDateTime;

/**
 * One row of administrative audit history for a target user, used by the
 * user-detail page's "Lịch sử cập nhật" tab.
 *
 * <p>Declared as a top-level record (rather than nested inside a parent
 * {@code AdminUsersDtos} holder) because Hibernate 6's JPQL parser cannot resolve
 * nested-class names in {@code SELECT new ...} projections — it interprets
 * the dot as a property path rather than an enclosing-class separator. A
 * top-level type sidesteps the issue without needing a Hibernate-specific
 * {@code @Imported} hint and matches the project's convention of one type
 * per file for non-form DTOs.
 *
 * <p>The {@code actorEmail} field is materialised via a LEFT JOIN against
 * the {@code users} table in the repository query so the template can show
 * the acting admin's email without a per-row N+1 lookup. It may be
 * {@code null} when the actor has been hard-deleted (FK
 * {@code performed_by} is ON DELETE SET NULL — see V10 migration) or when
 * the activity was system-generated.
 */
public record ActivityRow(
        Long id,
        String type,
        String message,
        String actorEmail,
        LocalDateTime createdAt
) {}
