package com.ulp.features.tests.dto;

import java.time.LocalDateTime;

/**
 * One row of audit history for an exam, used by the lecturer test-detail
 * page's "Lịch sử" tab. Mirrors {@code ActivityRow} from the admin
 * user-management feature.
 *
 * <p>Declared as a top-level record (not nested) because Hibernate 6's JPQL
 * parser cannot resolve nested-class names in {@code SELECT new ...}
 * projections. The {@code actorEmail} is materialised via a LEFT JOIN against
 * the {@code users} table so the template avoids a per-row N+1 lookup; it may
 * be {@code null} if the acting user has been hard-deleted.
 */
public record TestActivityRow(
        Long id,
        String type,
        String description,
        String actorEmail,
        LocalDateTime createdAt
) {}
