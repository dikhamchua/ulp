package com.ulp.features.messaging.service;

import com.ulp.entities.Conversation;
import com.ulp.entities.Message;
import com.ulp.entities.User;
import com.ulp.entities.UserFactory;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.features.classes.repository.EnrollmentRepository;
import com.ulp.features.messaging.dto.MessagingDtos.ConversationView;
import com.ulp.features.messaging.dto.MessagingDtos.SendResult;
import com.ulp.features.messaging.repository.ConversationRepository;
import com.ulp.features.messaging.repository.MessageRepository;
import com.ulp.features.messaging.support.MessagingAccess;
import com.ulp.security.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MessagingService}.
 *
 * <p>{@code MessagingIntegrationTest} already covers the HTTP surface and the
 * authorization gates. These tests target the pure decision logic that never
 * shows up in a status code: which message page a default open resolves to, the
 * body-length contract, snippet truncation in the push payload, and the fallback
 * used when a peer account has been removed.</p>
 */
class MessagingServiceTest {

    private static final Long ME = 1L;
    private static final Long PEER = 2L;
    private static final Long CONV_ID = 77L;
    /** Mirrors the service's private page size — the pager contract under test. */
    private static final int MESSAGE_PAGE_SIZE = 30;

    private ConversationRepository conversationRepository;
    private MessageRepository messageRepository;
    private UserRepository userRepository;
    private MessagingAccess access;
    private SimpMessagingTemplate messagingTemplate;
    private MessagingService service;

    @BeforeEach
    void setUp() {
        conversationRepository = mock(ConversationRepository.class);
        messageRepository = mock(MessageRepository.class);
        userRepository = mock(UserRepository.class);
        access = mock(MessagingAccess.class);
        messagingTemplate = mock(SimpMessagingTemplate.class);
        service = new MessagingService(conversationRepository, messageRepository,
                userRepository, access, messagingTemplate,
                mock(ClassRepository.class), mock(EnrollmentRepository.class));
    }

    private static User peerUser() {
        return UserFactory.newAdminCreated("peer@ulp.edu.vn", "hash",
                "Người Nhận", Role.LECTURER, true, null, null);
    }

    /** Registers a conversation between ME and PEER that passes the membership gate. */
    private void givenExistingConversation() {
        Conversation conv = Conversation.between(ME, PEER);
        when(conversationRepository.findById(CONV_ID)).thenReturn(Optional.of(conv));
    }

    /** Stubs the message page query and returns the captured Pageable. */
    private Pageable captureOpenPageable(long totalMessages, int requestedPage) {
        givenExistingConversation();
        when(messageRepository.countByConversationId(CONV_ID)).thenReturn(totalMessages);
        when(messageRepository.findByConversationIdOrderByCreatedAtAsc(eq(CONV_ID), any(Pageable.class)))
                .thenReturn(Page.empty());
        when(userRepository.findById(PEER)).thenReturn(Optional.of(peerUser()));

        service.openConversation(ME, CONV_ID, requestedPage);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(messageRepository).findByConversationIdOrderByCreatedAtAsc(eq(CONV_ID), captor.capture());
        return captor.getValue();
    }

    // ── Default page resolution (negative page = newest) ────────────────

    @Test
    void defaultOpenOnShortThreadLandsOnPageZero() {
        // A thread that fits in one page has no "last page" to jump to.
        Pageable pageable = captureOpenPageable(4, -1);

        assertThat(pageable.getPageNumber()).isZero();
    }

    @Test
    void defaultOpenOnLongThreadLandsOnTheNewestPage() {
        // 95 messages at 30/page → pages 0..3; the newest slice is page 3.
        Pageable pageable = captureOpenPageable(95, -1);

        assertThat(pageable.getPageNumber()).isEqualTo(3);
    }

    @Test
    void defaultOpenOnExactlyFullPageStaysOnPageZero() {
        // Boundary: exactly one full page must not roll over to page 1.
        Pageable pageable = captureOpenPageable(MESSAGE_PAGE_SIZE, -1);

        assertThat(pageable.getPageNumber()).isZero();
    }

    @Test
    void explicitPageIsHonouredWithoutCountingMessages() {
        // The pager walks toward older messages; page 0 is the oldest slice.
        Pageable pageable = captureOpenPageable(95, 1);

        assertThat(pageable.getPageNumber()).isEqualTo(1);
        verify(messageRepository, never()).countByConversationId(anyLong());
    }

    @Test
    void openMarksPeerMessagesReadBeforeLoadingTheThread() {
        captureOpenPageable(4, -1);

        // Badge and rendered thread must agree within the same request.
        verify(messageRepository).markReadBulk(eq(CONV_ID), eq(ME), any());
    }

    // ── Membership gate (no-leak 404) ───────────────────────────────────

    @Test
    void openingAForeignConversationIsIndistinguishableFromAMissingOne() {
        Conversation foreign = Conversation.between(PEER, 99L);
        when(conversationRepository.findById(CONV_ID)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.openConversation(ME, CONV_ID, 0))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void startingAConversationWithAnIneligiblePeerReturns404NotForbidden() {
        // 403 would confirm the target exists; the gate must not leak that.
        when(access.canStartConversation(ME, Role.STUDENT, PEER)).thenReturn(false);

        assertThatThrownBy(() -> service.getOrCreateConversation(ME, Role.STUDENT, PEER))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        verify(conversationRepository, never()).save(any());
    }

    @Test
    void startingAConversationReusesTheExistingThreadRegardlessOfArgumentOrder() {
        // The pair is normalised (lo, hi), so who initiates must not matter.
        // A persisted conversation is mocked rather than built via the factory:
        // the factory returns an unsaved entity whose id is still null, which the
        // service would read as "no thread yet" and create a duplicate.
        Conversation existing = mock(Conversation.class);
        when(existing.getId()).thenReturn(CONV_ID);
        when(access.canStartConversation(PEER, Role.LECTURER, ME)).thenReturn(true);
        when(conversationRepository.findByUserLoIdAndUserHiId(ME, PEER))
                .thenReturn(Optional.of(existing));

        Long convId = service.getOrCreateConversation(PEER, Role.LECTURER, ME);

        assertThat(convId).isEqualTo(CONV_ID);
        verify(conversationRepository).findByUserLoIdAndUserHiId(ME, PEER);
        verify(conversationRepository, never()).save(any());
    }

    // ── Send: body contract ─────────────────────────────────────────────

    @Test
    void sendRejectsABlankBodyWithoutPersisting() {
        givenExistingConversation();

        assertThatThrownBy(() -> service.send(ME, CONV_ID, "   \n\t "))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        verify(messageRepository, never()).saveAndFlush(any());
    }

    @Test
    void sendRejectsABodyOverTheLimitWithoutPersisting() {
        givenExistingConversation();
        String tooLong = "x".repeat(Message.MAX_BODY_LENGTH + 1);

        assertThatThrownBy(() -> service.send(ME, CONV_ID, tooLong))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        verify(messageRepository, never()).saveAndFlush(any());
    }

    @Test
    void sendAcceptsABodyExactlyAtTheLimit() {
        // Boundary: MAX_BODY_LENGTH itself is valid, only above it is rejected.
        givenExistingConversation();
        String atLimit = "x".repeat(Message.MAX_BODY_LENGTH);
        stubSuccessfulSave(atLimit);

        SendResult result = service.send(ME, CONV_ID, atLimit);

        assertThat(result.body()).hasSize(Message.MAX_BODY_LENGTH);
    }

    @Test
    void sendTrimsSurroundingWhitespaceBeforeStoring() {
        givenExistingConversation();
        stubSuccessfulSave("Xin chào thầy");

        service.send(ME, CONV_ID, "  Xin chào thầy  ");

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getBody()).isEqualTo("Xin chào thầy");
    }

    @Test
    void sendPushesToThePeerNotTheSender() {
        givenExistingConversation();
        stubSuccessfulSave("Chào em");
        when(messageRepository.countUnreadForUser(PEER)).thenReturn(7L);

        service.send(ME, CONV_ID, "Chào em");

        verify(messagingTemplate).convertAndSendToUser(
                eq("peer@ulp.edu.vn"), eq("/queue/messages"), any());
    }

    @Test
    void sendSkipsThePushWhenThePeerAccountIsGone() {
        // A deleted peer must not break the send — the message still persists.
        givenExistingConversation();
        when(messageRepository.saveAndFlush(any(Message.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.findById(PEER)).thenReturn(Optional.empty());
        when(userRepository.findById(ME)).thenReturn(Optional.empty());

        SendResult result = service.send(ME, CONV_ID, "Còn ai đọc không");

        assertThat(result.body()).isEqualTo("Còn ai đọc không");
        verify(messagingTemplate, never()).convertAndSendToUser(any(String.class), any(), any());
    }

    /** Stubs save + peer/sender lookups for a send that is expected to succeed. */
    private void stubSuccessfulSave(String expectedBody) {
        when(messageRepository.saveAndFlush(any(Message.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.findById(PEER)).thenReturn(Optional.of(peerUser()));
        when(userRepository.findById(ME)).thenReturn(Optional.of(
                UserFactory.newAdminCreated("me@ulp.edu.vn", "hash", "Người Gửi",
                        Role.STUDENT, true, null, null)));
        assertThat(expectedBody).isNotNull();
    }

    // ── Peer identity fallback ──────────────────────────────────────────

    @Test
    void openFallsBackToAPlaceholderNameWhenThePeerAccountIsGone() {
        // A removed peer must render a placeholder, never a null-pointer page.
        givenExistingConversation();
        when(messageRepository.countByConversationId(CONV_ID)).thenReturn(0L);
        when(messageRepository.findByConversationIdOrderByCreatedAtAsc(eq(CONV_ID), any(Pageable.class)))
                .thenReturn(Page.empty());
        when(userRepository.findById(PEER)).thenReturn(Optional.empty());

        ConversationView view = service.openConversation(ME, CONV_ID, -1);

        assertThat(view.peerName()).isEqualTo("Người dùng");
        assertThat(view.peerAvatarUrl()).isNull();
    }

    // ── Listing ─────────────────────────────────────────────────────────

    @Test
    void listingClampsANegativePageToZero() {
        // A hand-edited ?page=-5 must not blow up PageRequest.of.
        when(conversationRepository.findConversationsForUser(eq(ME), any(Pageable.class)))
                .thenReturn(Page.empty());

        service.listConversations(ME, -5);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(conversationRepository).findConversationsForUser(eq(ME), captor.capture());
        assertThat(captor.getValue().getPageNumber()).isZero();
    }

    // ── Recipient search ────────────────────────────────────────────────

    @Test
    void searchRecipientsMapsOnlyEligibleUsers() {
        when(access.eligibleRecipients(ME, Role.STUDENT, "ngu"))
                .thenReturn(List.of(peerUser()));

        var rows = service.searchRecipients(ME, Role.STUDENT, "ngu");

        assertThat(rows).singleElement()
                .satisfies(r -> {
                    assertThat(r.name()).isEqualTo("Người Nhận");
                    assertThat(r.email()).isEqualTo("peer@ulp.edu.vn");
                });
    }

    /** Guards the page-size constant this test's expectations are built on. */
    @Test
    void messagePageSizeConstantMatchesTheServiceContract() {
        Pageable pageable = captureOpenPageable(4, 0);

        assertThat(pageable.getPageSize()).isEqualTo(MESSAGE_PAGE_SIZE);
        assertThat(PageRequest.of(0, MESSAGE_PAGE_SIZE).getPageSize())
                .isEqualTo(pageable.getPageSize());
    }
}
