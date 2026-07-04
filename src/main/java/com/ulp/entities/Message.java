package com.ulp.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * JPA entity mapping the {@code messages} table (Epic #13, ULP-8.3).
 *
 * <p>One row per message in a {@link Conversation}. "Unread" is derived, not
 * stored: a message counts as unread for the recipient while
 * {@code readAt IS NULL} and the caller is not the sender. Opening a
 * conversation bulk-sets {@code readAt} on the peer's unread messages.
 *
 * <p>Uses a plain {@code Long} FK ({@code conversationId}) rather than a
 * {@code @ManyToOne} to mirror the project's lightweight {@code Enrollment.classId}
 * style and keep the write path simple.
 */
@Entity
@Table(name = "messages")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Message {

    /** Hard cap on message body length, enforced server-side. */
    public static final int MAX_BODY_LENGTH = 2000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id", nullable = false, updatable = false)
    private Long conversationId;

    @Column(name = "sender_id", nullable = false, updatable = false)
    private Long senderId;

    @Column(nullable = false, columnDefinition = "TEXT", updatable = false)
    private String body;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    /**
     * Creates a new unread message ready to persist. The caller passes an
     * already-trimmed, length-validated body.
     *
     * @param conversationId owning conversation id
     * @param senderId       author id
     * @param body           trimmed message text (1..{@value #MAX_BODY_LENGTH} chars)
     */
    public Message(Long conversationId, Long senderId, String body) {
        this.conversationId = conversationId;
        this.senderId = senderId;
        this.body = body;
    }

    @PrePersist
    void onPersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    /**
     * Marks this message as read at the given time; no-op if already read.
     *
     * @param when the timestamp to record as the read time
     */
    public void markRead(LocalDateTime when) {
        if (this.readAt == null) this.readAt = when;
    }
}
