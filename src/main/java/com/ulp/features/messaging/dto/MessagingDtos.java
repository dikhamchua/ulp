package com.ulp.features.messaging.dto;

import org.springframework.data.domain.Page;

import java.time.LocalDateTime;

/**
 * Read/write DTOs for the messaging feature (Epic #13). Entities never leak to
 * the controller/template layer — services map them into these records.
 */
public final class MessagingDtos {

    private MessagingDtos() {
        // Holder for record types — no instances.
    }

    /**
     * One row in the conversation sidebar list.
     *
     * @param convId        conversation id
     * @param peerId        the peer's user id
     * @param peerName      the peer's display name
     * @param peerAvatarUrl the peer's avatar URL, or {@code null}
     * @param lastSnippet   short preview of the last message, or {@code null} when none
     * @param unread        unread count in this conversation for the caller
     * @param lastMessageAt time of the last message, or {@code null} when none
     */
    public record ConversationRow(Long convId, Long peerId, String peerName,
                                  String peerAvatarUrl, String lastSnippet,
                                  long unread, LocalDateTime lastMessageAt) {
    }

    /**
     * One message bubble in a thread.
     *
     * @param id        message id
     * @param mine      whether the caller is the sender (right-aligned bubble)
     * @param body      full message text
     * @param createdAt when the message was sent
     */
    public record MessageRow(Long id, boolean mine, String body, LocalDateTime createdAt) {
    }

    /**
     * The full view of an opened conversation: peer identity plus a page of
     * messages (chronological order).
     *
     * @param convId        conversation id
     * @param peerId        the peer's user id
     * @param peerName      the peer's display name
     * @param peerAvatarUrl the peer's avatar URL, or {@code null}
     * @param messages      a page of message rows (oldest first)
     */
    public record ConversationView(Long convId, Long peerId, String peerName,
                                   String peerAvatarUrl, Page<MessageRow> messages) {
    }

    /**
     * Result of a successful send, returned to the fetch caller and used to
     * append the sent bubble optimistically.
     *
     * @param messageId   the persisted message id
     * @param convId      conversation id
     * @param body        the stored body
     * @param createdAt   when it was stored
     * @param peerUnread  the peer's new total unread count (pushed to them)
     */
    public record SendResult(Long messageId, Long convId, String body,
                             LocalDateTime createdAt, long peerUnread) {
    }

    /**
     * STOMP payload pushed to the recipient's {@code /user/queue/messages}.
     *
     * @param convId      conversation the message belongs to
     * @param senderName  the sender's display name
     * @param snippet     short preview of the message body
     * @param unreadTotal the recipient's new total unread count
     */
    public record PushPayload(Long convId, String senderName, String snippet, long unreadTotal) {
    }

    /**
     * A single eligible recipient in the "new conversation" search results.
     *
     * @param id        the recipient's user id
     * @param name      display name
     * @param email     email address
     * @param avatarUrl avatar URL, or {@code null}
     */
    public record RecipientRow(Long id, String name, String email, String avatarUrl) {
    }

    /**
     * The class-scoped messaging view: class metadata for the shared sidebar plus
     * the opened conversation with that class's lecturer. Backs
     * {@code GET /my/classes/{classId}/messages}, keeping the class shell/sidebar
     * on the left and the lecturer thread in the main pane.
     *
     * @param classId      the class id (for sidebar hrefs)
     * @param className    the class name shown in the sidebar info card
     * @param classCode    the class code shown in the sidebar info card
     * @param lecturerName the lecturer's full name, or {@code null}
     * @param conversation the opened thread with the class's lecturer
     */
    public record ClassMessagesView(Long classId, String className, String classCode,
                                    String lecturerName, ConversationView conversation) {
    }
}
