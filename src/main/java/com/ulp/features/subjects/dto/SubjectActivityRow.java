package com.ulp.features.subjects.dto;

import java.time.LocalDateTime;

/** History-tab row for subject detail (type, message, actor email, time). */
public record SubjectActivityRow(
        String type,
        String message,
        String actorEmail,
        LocalDateTime createdAt
) {
}
