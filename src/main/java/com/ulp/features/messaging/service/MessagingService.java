package com.ulp.features.messaging.service;

import com.ulp.entities.ClassEntity;
import com.ulp.entities.Conversation;
import com.ulp.entities.Enrollment;
import com.ulp.entities.Message;
import com.ulp.entities.User;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.features.classes.repository.EnrollmentRepository;
import com.ulp.features.messaging.dto.MessagingDtos.ClassMessagesView;
import com.ulp.features.messaging.dto.MessagingDtos.ConversationRow;
import com.ulp.features.messaging.dto.MessagingDtos.ConversationView;
import com.ulp.features.messaging.dto.MessagingDtos.MessageRow;
import com.ulp.features.messaging.dto.MessagingDtos.PushPayload;
import com.ulp.features.messaging.dto.MessagingDtos.RecipientRow;
import com.ulp.features.messaging.dto.MessagingDtos.SendResult;
import com.ulp.features.messaging.repository.ConversationRepository;
import com.ulp.features.messaging.repository.MessageRepository;
import com.ulp.features.messaging.support.MessagingAccess;
import com.ulp.security.Role;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Application service for direct messaging (Epic #13, ULP-8.3 + ULP-8.4).
 *
 * <p>Owns conversation creation (gated by {@link MessagingAccess}), listing,
 * opening (with read-marking), sending (with real-time STOMP push), the unread
 * badge count, and recipient search. Entities never leak past this layer.
 *
 * <p>Authorization split (design decision D2): the recipient gate applies ONLY
 * to {@link #getOrCreateConversation} and {@link #searchRecipients}. Once a
 * conversation exists, {@link #openConversation} and {@link #send} validate only
 * that the caller is a participant — enrollment is never re-checked, so a student
 * who leaves a class keeps their existing thread.
 */
@Service
public class MessagingService {

    /** Sidebar page size for the conversation list. */
    static final int CONVERSATION_PAGE_SIZE = 20;
    /** Thread page size for messages within one conversation. */
    static final int MESSAGE_PAGE_SIZE = 30;
    /** Max characters of a message body shown as a sidebar/push snippet. */
    private static final int SNIPPET_LENGTH = 80;

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final MessagingAccess access;
    private final SimpMessagingTemplate messagingTemplate;
    private final ClassRepository classRepository;
    private final EnrollmentRepository enrollmentRepository;

    public MessagingService(ConversationRepository conversationRepository,
                            MessageRepository messageRepository,
                            UserRepository userRepository,
                            MessagingAccess access,
                            SimpMessagingTemplate messagingTemplate,
                            ClassRepository classRepository,
                            EnrollmentRepository enrollmentRepository) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.userRepository = userRepository;
        this.access = access;
        this.messagingTemplate = messagingTemplate;
        this.classRepository = classRepository;
        this.enrollmentRepository = enrollmentRepository;
    }

    // ── Conversation creation ───────────────────────────────────────────

    /**
     * Finds or creates the conversation between the caller and another user.
     * Applies the recipient eligibility gate; an ineligible pair fails with a
     * 404 so the target's existence is never leaked (see the spec no-leak rule).
     *
     * @param meId    the caller's user id
     * @param meRole  the caller's role
     * @param otherId the prospective peer's id
     * @return the id of the found or newly created conversation
     */
    @Transactional
    public Long getOrCreateConversation(Long meId, Role meRole, Long otherId) {
        if (!access.canStartConversation(meId, meRole, otherId)) {
            // No-leak: same response whether the user is missing or ineligible.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        Long lo = Math.min(meId, otherId);
        Long hi = Math.max(meId, otherId);
        return conversationRepository.findByUserLoIdAndUserHiId(lo, hi)
                .map(Conversation::getId)
                .orElseGet(() -> conversationRepository.save(Conversation.between(meId, otherId)).getId());
    }

    // ── Listing ─────────────────────────────────────────────────────────

    /**
     * Lists the caller's conversations, most-recent activity first.
     *
     * @param meId the caller's user id
     * @param page zero-based page index
     * @return a page of {@link ConversationRow}s
     */
    @Transactional(readOnly = true)
    public Page<ConversationRow> listConversations(Long meId, int page) {
        Pageable pageable = PageRequest.of(Math.max(0, page), CONVERSATION_PAGE_SIZE);
        Page<Conversation> convs = conversationRepository.findConversationsForUser(meId, pageable);
        return convs.map(c -> toRow(c, meId));
    }

    // ── Opening (with read-marking) ─────────────────────────────────────

    /**
     * Opens a conversation for the caller: validates membership, marks the peer's
     * unread messages read, and returns the peer identity plus a page of messages.
     *
     * <p>Pages are ordered oldest-first so bubbles read top-to-bottom, but the
     * DEFAULT view (page {@code < 0}) resolves to the LAST page — the newest
     * messages — matching chat expectations (scroll-to-bottom = latest). Explicit
     * non-negative pages are honoured as-is so the pager can walk toward older
     * messages; page 0 is the oldest slice.
     *
     * @param meId   the caller's user id
     * @param convId the conversation id
     * @param page   zero-based message page index; negative means "newest page"
     * @return the {@link ConversationView}
     * @throws ResponseStatusException 404 when the conversation is absent or the
     *                                 caller is not a participant (no-leak)
     */
    @Transactional
    public ConversationView openConversation(Long meId, Long convId, int page) {
        Conversation conv = requireParticipant(meId, convId);
        // Mark the peer's unread messages read BEFORE loading, so the badge and
        // the rendered thread agree on read state within this request.
        messageRepository.markReadBulk(convId, meId, LocalDateTime.now());

        int resolvedPage = resolveMessagePage(convId, page);
        Pageable pageable = PageRequest.of(resolvedPage, MESSAGE_PAGE_SIZE,
                Sort.by(Sort.Direction.ASC, "createdAt"));
        Page<MessageRow> rows = messageRepository
                .findByConversationIdOrderByCreatedAtAsc(convId, pageable)
                .map(m -> new MessageRow(m.getId(), m.getSenderId().equals(meId),
                        m.getBody(), m.getCreatedAt()));

        User peer = userRepository.findById(conv.peerOf(meId)).orElse(null);
        return new ConversationView(convId, conv.peerOf(meId),
                peerName(peer), peerAvatar(peer), rows);
    }

    /**
     * Resolves the message page index to load. A negative request means "newest
     * page": we compute the last page from the total count so the default open
     * lands on the most recent messages. Short threads (≤ one page) resolve to 0,
     * preserving the original behaviour for the seed's 4-message thread.
     */
    private int resolveMessagePage(Long convId, int requestedPage) {
        if (requestedPage >= 0) return requestedPage;
        long total = messageRepository.countByConversationId(convId);
        if (total <= MESSAGE_PAGE_SIZE) return 0;
        return (int) ((total - 1) / MESSAGE_PAGE_SIZE);
    }

    // ── Class-scoped conversation (keeps the class shell/sidebar) ────────

    /**
     * Opens the calling student's conversation with a class's lecturer, keeping
     * the class shell and sidebar around it. Backs
     * {@code GET /my/classes/{classId}/messages}: gates on ACTIVE enrollment
     * (else 404, no existence leak — same policy as class lessons/tests), resolves
     * the class's lecturer, gets-or-creates the student↔lecturer thread, marks it
     * read, and returns the class metadata plus the opened conversation.
     *
     * @param meId    the calling student's user id
     * @param classId the class id
     * @param page    zero-based message page; negative means "newest page"
     * @return a {@link ClassMessagesView} for the class shell + lecturer thread
     * @throws ResponseStatusException 404 when the caller is not ACTIVE-enrolled,
     *                                 the class is absent, or it has no lecturer
     */
    @Transactional
    public ClassMessagesView openClassConversation(Long meId, Long classId, int page) {
        // Gate — the caller must be ACTIVE-enrolled (else 404, no existence leak).
        enrollmentRepository.findByUserIdAndClassId(meId, classId)
                .filter(e -> Enrollment.STATUS_ACTIVE.equals(e.getStatus()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        ClassEntity clazz = classRepository.findById(classId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        Long lecturerId = clazz.getLecturerId();
        if (lecturerId == null || lecturerId.equals(meId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }

        // Get-or-create the student↔lecturer thread. The enrollment gate above
        // already proves eligibility, so we normalise the pair directly here.
        Long convId = conversationRepository
                .findByUserLoIdAndUserHiId(Math.min(meId, lecturerId), Math.max(meId, lecturerId))
                .map(Conversation::getId)
                .orElseGet(() -> conversationRepository.save(Conversation.between(meId, lecturerId)).getId());

        ConversationView conversation = openConversation(meId, convId, page);
        String lecturerName = userRepository.findById(lecturerId)
                .map(User::getFullName).orElse(null);
        return new ClassMessagesView(clazz.getId(), clazz.getName(), clazz.getCode(),
                lecturerName, conversation);
    }

    // ── Sending (with STOMP push) ───────────────────────────────────────

    /**
     * Sends a message in an existing conversation. Validates ONLY membership
     * (never enrollment) and the 2000-char limit, persists the message, bumps the
     * conversation's last-activity time, and pushes a real-time notification to
     * the peer's {@code /user/queue/messages} after the write.
     *
     * @param meId   the caller's user id
     * @param convId the conversation id
     * @param body   the raw message body (trimmed and length-validated here)
     * @return a {@link SendResult} describing the stored message
     * @throws ResponseStatusException 404 when not a participant; 400 when the
     *                                 body is blank or exceeds the length limit
     */
    @Transactional
    public SendResult send(Long meId, Long convId, String body) {
        Conversation conv = requireParticipant(meId, convId);

        String trimmed = body == null ? "" : body.trim();
        if (trimmed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nội dung không được để trống");
        }
        if (trimmed.length() > Message.MAX_BODY_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nội dung tối đa 2000 ký tự");
        }

        // Persist the message first, then bump last_message_at with a single
        // UPDATE (not entity merge) so concurrent sends on the same thread do
        // not deadlock under InnoDB + Hibernate batching.
        Message saved = messageRepository.saveAndFlush(new Message(convId, meId, trimmed));
        conversationRepository.touchLastMessageAt(convId, saved.getCreatedAt());

        Long peerId = conv.peerOf(meId);
        long peerUnread = messageRepository.countUnreadForUser(peerId);
        pushToPeer(peerId, convId, meId, trimmed, peerUnread);

        return new SendResult(saved.getId(), convId, saved.getBody(),
                saved.getCreatedAt(), peerUnread);
    }

    // ── Badge count ─────────────────────────────────────────────────────

    /**
     * The caller's total unread message count for the header badge.
     *
     * @param meId the caller's user id
     * @return the total number of unread, peer-sent messages
     */
    @Transactional(readOnly = true)
    public long unreadCount(Long meId) {
        return messageRepository.countUnreadForUser(meId);
    }

    // ── Recipient search ────────────────────────────────────────────────

    /**
     * Searches for users the caller may start a new conversation with, filtered
     * by an optional name/email substring. Only eligible peers are returned.
     *
     * @param meId   the caller's user id
     * @param meRole the caller's role
     * @param q      optional case-insensitive name/email filter
     * @return eligible {@link RecipientRow}s
     */
    @Transactional(readOnly = true)
    public List<RecipientRow> searchRecipients(Long meId, Role meRole, String q) {
        return access.eligibleRecipients(meId, meRole, q).stream()
                .map(u -> new RecipientRow(u.getId(), u.getFullName(), u.getEmail(), u.getAvatarUrl()))
                .toList();
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /**
     * Loads a conversation and asserts the caller is one of its two participants.
     * Returns a 404 (not 403) when absent or foreign, so a conversation the caller
     * does not belong to is indistinguishable from one that does not exist.
     */
    private Conversation requireParticipant(Long meId, Long convId) {
        Conversation conv = conversationRepository.findById(convId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!conv.hasParticipant(meId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return conv;
    }

    /** Maps a conversation to a sidebar row, resolving peer identity + unread. */
    private ConversationRow toRow(Conversation c, Long meId) {
        Long peerId = c.peerOf(meId);
        User peer = userRepository.findById(peerId).orElse(null);
        long unread = messageRepository.countByConversationIdAndReadAtIsNullAndSenderIdNot(c.getId(), meId);
        String snippet = lastSnippet(c.getId());
        return new ConversationRow(c.getId(), peerId, peerName(peer), peerAvatar(peer),
                snippet, unread, c.getLastMessageAt());
    }

    /** Returns a short preview of the conversation's most recent message body. */
    private String lastSnippet(Long convId) {
        return messageRepository.findFirstByConversationIdOrderByCreatedAtDesc(convId)
                .map(m -> snippet(m.getBody()))
                .orElse(null);
    }

    /** Pushes a real-time notification to the peer after a message is stored. */
    private void pushToPeer(Long peerId, Long convId, Long senderId, String body, long peerUnread) {
        Optional<User> peer = userRepository.findById(peerId);
        Optional<User> sender = userRepository.findById(senderId);
        if (peer.isEmpty()) return;
        String senderName = sender.map(User::getFullName).orElse("");
        PushPayload payload = new PushPayload(convId, senderName, snippet(body), peerUnread);
        // Route by the peer's email — matches the Spring Security principal name.
        messagingTemplate.convertAndSendToUser(peer.get().getEmail(), "/queue/messages", payload);
    }

    private static String snippet(String body) {
        if (body == null) return "";
        String flat = body.strip().replaceAll("\\s+", " ");
        return flat.length() <= SNIPPET_LENGTH ? flat : flat.substring(0, SNIPPET_LENGTH) + "…";
    }

    private static String peerName(User peer) {
        return peer != null ? peer.getFullName() : "Người dùng";
    }

    private static String peerAvatar(User peer) {
        return peer != null ? peer.getAvatarUrl() : null;
    }
}
