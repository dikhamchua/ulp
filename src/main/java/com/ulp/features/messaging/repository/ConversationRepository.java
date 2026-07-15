package com.ulp.features.messaging.repository;

import com.ulp.entities.Conversation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link Conversation}.
 *
 * <p>Conversations store a normalized pair {@code (user_lo_id, user_hi_id)} with
 * {@code lo < hi}, so a pair lookup uses the pre-ordered ids and the caller-side
 * "conversations of a user" query matches either column.
 */
public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    /**
     * Finds the (at-most-one) conversation for a normalized user pair.
     *
     * @param lo the smaller participant id
     * @param hi the larger participant id
     * @return the conversation for that pair, or empty
     */
    Optional<Conversation> findByUserLoIdAndUserHiId(Long lo, Long hi);

    /**
     * Returns the caller's conversations ordered by most-recent activity.
     * Rows never touched yet ({@code last_message_at IS NULL}) sort last, then
     * by creation time descending as a stable tie-breaker.
     *
     * @param userId   the caller's user id (matched against either participant column)
     * @param pageable page request
     * @return a page of the caller's conversations, most-recent first
     */
    @Query("SELECT c FROM Conversation c " +
            "WHERE c.userLoId = :userId OR c.userHiId = :userId " +
            "ORDER BY CASE WHEN c.lastMessageAt IS NULL THEN 1 ELSE 0 END ASC, " +
            "c.lastMessageAt DESC, c.createdAt DESC")
    Page<Conversation> findConversationsForUser(@Param("userId") Long userId, Pageable pageable);

    /**
     * Atomic bump of last-activity time after a message is stored. Prefer this
     * over loading + saving the entity so concurrent sends do not deadlock on
     * the same conversation row under Hibernate batching.
     *
     * @param convId conversation id
     * @param when   timestamp of the newly sent message
     * @return rows updated (0 or 1)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Conversation c SET c.lastMessageAt = :when WHERE c.id = :convId")
    int touchLastMessageAt(@Param("convId") Long convId, @Param("when") LocalDateTime when);
}
