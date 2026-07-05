package com.ulp.features.notifications.dto;

import java.time.LocalDateTime;

/**
 * Read DTOs for the notifications feature (Sprint 5, #63/#64).
 * Entities never leak to the controller/template layer — the service maps
 * them into these records before returning.
 */
public final class NotificationDtos {

    private NotificationDtos() {
        // Constants-only holder — no instances.
    }

    /**
     * One row in the notifications list page.
     *
     * @param id            the notification id
     * @param title         short notification title
     * @param content       longer notification body
     * @param type          the notification type constant
     * @param referenceType optional reference domain type (e.g. "CLASS", "LESSON")
     * @param referenceId   optional reference domain id
     * @param isRead        whether the notification has been read
     * @param readAt        when it was read, or {@code null} when still unread
     * @param createdAt     when the notification was created
     */
    public record NotificationRow(
            Long id,
            String title,
            String content,
            String type,
            String referenceType,
            Long referenceId,
            boolean isRead,
            LocalDateTime readAt,
            LocalDateTime createdAt
    ) {
    }
}
