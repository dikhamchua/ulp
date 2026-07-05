package com.ulp.features.notifications.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * JPA entity mapping the existing {@code notifications} table (created in V1).
 *
 * <p>No {@code @Data}: project rule prohibits it on entities (equals/hashCode loops).
 * Getters and setters are provided via Lombok {@code @Getter}/{@code @Setter}.
 * Column names are mapped exactly to the V1 schema so Hibernate {@code validate}
 * passes on startup.
 */
@Entity
@Table(name = "notifications")
@Getter
@Setter
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The recipient's user id (FK to users.id). */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "title", nullable = false, length = 300)
    private String title;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    /** Notification type string — see {@link NotificationType} for valid values. */
    @Column(name = "type", nullable = false, length = 30)
    private String type;

    /** Optional: the type of the referenced domain object (e.g. "CLASS", "LESSON"). */
    @Column(name = "reference_type", length = 30)
    private String referenceType;

    /** Optional: the id of the referenced domain object. */
    @Column(name = "reference_id")
    private Long referenceId;

    /** Whether the notification has been read by the recipient. */
    @Column(name = "is_read", nullable = false)
    private boolean isRead = false;

    /** Timestamp when the notification was marked read; null when unread. */
    @Column(name = "read_at")
    private LocalDateTime readAt;

    /** Whether an email was successfully delivered for this notification. */
    @Column(name = "is_email_sent", nullable = false)
    private boolean isEmailSent = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** Default constructor for JPA. */
    public Notification() {
    }

    /**
     * Factory constructor used by {@code NotificationService.create()}.
     *
     * @param userId        the recipient's user id
     * @param title         short notification title
     * @param content       longer notification body
     * @param type          notification type constant
     * @param referenceType optional reference domain type
     * @param referenceId   optional reference domain id
     */
    public Notification(Long userId, String title, String content,
                        String type, String referenceType, Long referenceId) {
        this.userId = userId;
        this.title = title;
        this.content = content;
        this.type = type;
        this.referenceType = referenceType;
        this.referenceId = referenceId;
        this.isRead = false;
        this.isEmailSent = false;
        this.createdAt = LocalDateTime.now();
    }
}
