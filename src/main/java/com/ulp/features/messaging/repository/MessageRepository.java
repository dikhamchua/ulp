package com.ulp.features.messaging.repository;

import com.ulp.entities.Message;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

/**
 * Spring Data JPA repository for {@link Message}.
 *
 * <p>"Unread" is derived, never stored: a message is unread for a user while
 * {@code read_at IS NULL} AND the user is not its sender. The badge total counts
 * such messages across all the caller's conversations; opening a conversation
 * bulk-marks the peer's unread messages read.
 */
public interface MessageRepository extends JpaRepository<Message, Long> {

    /**
     * Returns a conversation's messages in chronological order (oldest first).
     *
     * @param conversationId owning conversation id
     * @param pageable       page request
     * @return a page of messages, oldest first
     */
    Page<Message> findByConversationIdOrderByCreatedAtAsc(Long conversationId, Pageable pageable);

    /**
     * Returns the most recent message in a conversation, or empty when the
     * conversation has none. Used to build the sidebar snippet.
     *
     * @param conversationId owning conversation id
     * @return the newest message, or empty
     */
    java.util.Optional<Message> findFirstByConversationIdOrderByCreatedAtDesc(Long conversationId);

    /**
     * Counts unread messages in one conversation for the caller: messages the
     * caller did NOT send that are still unread.
     *
     * @param conversationId conversation to count within
     * @param meId           the caller's user id (their own messages excluded)
     * @return unread count in that conversation for the caller
     */
    long countByConversationIdAndReadAtIsNullAndSenderIdNot(Long conversationId, Long meId);

    /**
     * Total message count in one conversation. Used to resolve the "newest page"
     * when a conversation is opened without an explicit page index, so the
     * default view lands on the most recent messages.
     *
     * @param conversationId owning conversation id
     * @return the number of messages in that conversation
     */
    long countByConversationId(Long conversationId);

    /**
     * Total unread count for the caller across every conversation they belong
     * to: peer-sent, still-unread messages. Powers the header badge.
     *
     * @param meId the caller's user id
     * @return the caller's total unread message count
     */
    @Query("SELECT COUNT(m) FROM Message m " +
            "JOIN Conversation c ON c.id = m.conversationId " +
            "WHERE m.readAt IS NULL AND m.senderId <> :meId " +
            "AND (c.userLoId = :meId OR c.userHiId = :meId)")
    long countUnreadForUser(@Param("meId") Long meId);

    /**
     * Bulk-marks a conversation's peer-sent unread messages as read. Only rows
     * sent by the OTHER participant and still unread are touched.
     *
     * @param conversationId conversation to mark within
     * @param meId           the caller's user id (their own messages untouched)
     * @param now            the read timestamp to set
     * @return the number of rows updated
     */
    @Modifying
    @Query("UPDATE Message m SET m.readAt = :now " +
            "WHERE m.conversationId = :conversationId " +
            "AND m.senderId <> :meId AND m.readAt IS NULL")
    int markReadBulk(@Param("conversationId") Long conversationId,
                     @Param("meId") Long meId,
                     @Param("now") LocalDateTime now);
}
