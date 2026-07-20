package com.ulp.features.admin.departments.dto;

import java.time.LocalDateTime;

/**
 * One row of administrative audit history for a department.
 * Top-level record so Hibernate JPQL {@code SELECT new ...} can resolve it.
 */
public record DepartmentActivityRow(
        Long id,
        String type,
        String message,
        String actorEmail,
        LocalDateTime createdAt
) {
}
