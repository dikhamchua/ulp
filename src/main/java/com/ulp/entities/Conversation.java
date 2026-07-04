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
 * JPA entity mapping the {@code conversations} table (Epic #13, ULP-8.3).
 *
 * <p>Each row represents a single 1-on-1 thread between two users, stored as a
 * normalized pair: {@code userLoId} always holds the smaller user id and
 * {@code userHiId} the larger. A {@code UNIQUE(user_lo_id, user_hi_id)}
 * constraint makes {@code getOrCreate} idempotent regardless of who initiates,
 * so there is never more than one thread per unordered pair.
 *
 * <p>No {@code @SQLRestriction}: conversations are never soft-deleted.
 */
@Entity
@Table(name = "conversations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Conversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_lo_id", nullable = false, updatable = false)
    private Long userLoId;

    @Column(name = "user_hi_id", nullable = false, updatable = false)
    private Long userHiId;

    @Column(name = "last_message_at")
    private LocalDateTime lastMessageAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Factory for a brand-new conversation between two users. The caller need
     * not pre-order the ids: this normalizes them into {@code lo < hi} so the
     * unique-pair constraint is honoured no matter who initiates.
     *
     * @param userA one participant id
     * @param userB the other participant id (must differ from {@code userA})
     * @return an unsaved {@link Conversation} with the normalized pair set
     */
    public static Conversation between(Long userA, Long userB) {
        Conversation c = new Conversation();
        c.userLoId = Math.min(userA, userB);
        c.userHiId = Math.max(userA, userB);
        return c;
    }

    @PrePersist
    void onPersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    /**
     * Returns the id of the participant that is NOT the supplied caller.
     *
     * @param meId the caller's user id (must be one of the two participants)
     * @return the peer's user id
     */
    public Long peerOf(Long meId) {
        return meId.equals(userLoId) ? userHiId : userLoId;
    }

    /**
     * Whether the supplied user id is one of the two participants.
     *
     * @param userId candidate user id
     * @return {@code true} when {@code userId} is a participant of this conversation
     */
    public boolean hasParticipant(Long userId) {
        return userId != null && (userId.equals(userLoId) || userId.equals(userHiId));
    }

    /**
     * Bumps the last-activity timestamp; called when a new message is stored so
     * the conversation floats to the top of the most-recent-first list.
     *
     * @param when the timestamp of the newly sent message
     */
    public void touch(LocalDateTime when) {
        this.lastMessageAt = when;
    }
}
