package com.ulp.features.notifications.service;

import com.ulp.entities.User;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.mail.job.MailJob;
import com.ulp.features.mail.job.MailJobEnqueueHelper;
import com.ulp.features.notifications.dto.NotificationDtos.NotificationRow;
import com.ulp.features.notifications.entity.Notification;
import com.ulp.features.notifications.entity.NotificationType;
import com.ulp.features.notifications.repository.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link NotificationService}.
 *
 * <p>Covers creation (persist-first + background mail enqueue for whitelisted
 * types), owner-scoped mark-read (foreign id is silent no-op), unread count,
 * and list paging. SMTP is never touched here — the mail worker is out of scope.
 */
class NotificationServiceTest {

    private static final Long USER_ID = 7L;
    private static final Long NOTIF_ID = 42L;

    private NotificationRepository notificationRepository;
    private UserRepository userRepository;
    private MailJobEnqueueHelper mailJobEnqueueHelper;
    private NotificationService service;

    @BeforeEach
    void setUp() {
        notificationRepository = mock(NotificationRepository.class);
        userRepository = mock(UserRepository.class);
        mailJobEnqueueHelper = mock(MailJobEnqueueHelper.class);
        service = new NotificationService(notificationRepository, userRepository, mailJobEnqueueHelper);
    }

    // ── create ─────────────────────────────────────────────────────────

    @Test
    void create_persists_notification_and_returns_saved_entity() {
        Notification saved = stubSave(NotificationType.SYSTEM, null, null);

        Notification result = service.create(USER_ID, "Title", "Body",
                NotificationType.SYSTEM, null, null);

        assertThat(result).isEqualTo(saved);
        verify(notificationRepository).save(any(Notification.class));
    }

    @Test
    void create_does_not_enqueue_email_for_non_whitelisted_type() {
        stubSave(NotificationType.CLASS_ENROLLED, NotificationType.REF_CLASS, 1L);

        service.create(USER_ID, "T", "B", NotificationType.CLASS_ENROLLED,
                NotificationType.REF_CLASS, 1L);

        verify(mailJobEnqueueHelper, never()).enqueueAfterCommit(any());
    }

    @Test
    void create_does_not_enqueue_email_for_lesson_published() {
        // LESSON_PUBLISHED is intentionally in-app only so publish stays fast.
        stubSave(NotificationType.LESSON_PUBLISHED, NotificationType.REF_LESSON, 5L);

        service.create(USER_ID, "Bài mới", "Nội dung",
                NotificationType.LESSON_PUBLISHED, NotificationType.REF_LESSON, 5L);

        verify(mailJobEnqueueHelper, never()).enqueueAfterCommit(any());
    }

    @Test
    void create_enqueues_email_for_assignment_published_type() {
        stubSave(NotificationType.ASSIGNMENT_PUBLISHED,
                NotificationType.REF_ASSIGNMENT, 5L);
        User user = user("student@ulp.edu.vn");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        service.create(USER_ID, "Bài tập mới", "Nội dung",
                NotificationType.ASSIGNMENT_PUBLISHED, NotificationType.REF_ASSIGNMENT, 5L);

        ArgumentCaptor<MailJob> captor = ArgumentCaptor.forClass(MailJob.class);
        verify(mailJobEnqueueHelper).enqueueAfterCommit(captor.capture());
        MailJob job = captor.getValue();
        assertThat(job.to()).isEqualTo("student@ulp.edu.vn");
        assertThat(job.subject()).isEqualTo("[ULP] Bài tập mới");
        assertThat(job.body()).isEqualTo("Nội dung");
        assertThat(job.notificationId()).isEqualTo(NOTIF_ID);
        assertThat(job.source()).isEqualTo(NotificationType.ASSIGNMENT_PUBLISHED);
    }

    @Test
    void create_does_not_enqueue_email_when_user_not_found() {
        stubSave(NotificationType.ASSIGNMENT_PUBLISHED, NotificationType.REF_ASSIGNMENT, 5L);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        service.create(USER_ID, "T", "B",
                NotificationType.ASSIGNMENT_PUBLISHED, NotificationType.REF_ASSIGNMENT, 5L);

        verify(mailJobEnqueueHelper, never()).enqueueAfterCommit(any());
    }

    // ── markRead ──────────────────────────────────────────────────────

    @Test
    void markRead_sets_read_true_and_read_at_for_unread_notification() {
        Notification n = unreadNotification();
        when(notificationRepository.findByIdAndUserId(NOTIF_ID, USER_ID))
                .thenReturn(Optional.of(n));

        service.markRead(USER_ID, NOTIF_ID);

        assertThat(n.isRead()).isTrue();
        assertThat(n.getReadAt()).isNotNull();
        verify(notificationRepository).save(n);
    }

    @Test
    void markRead_is_no_op_for_already_read_notification() {
        Notification n = unreadNotification();
        n.setRead(true);
        n.setReadAt(LocalDateTime.now().minusHours(1));
        when(notificationRepository.findByIdAndUserId(NOTIF_ID, USER_ID))
                .thenReturn(Optional.of(n));

        service.markRead(USER_ID, NOTIF_ID);

        verify(notificationRepository, never()).save(any());
    }

    @Test
    void markRead_is_silent_no_op_for_foreign_or_absent_notification() {
        when(notificationRepository.findByIdAndUserId(NOTIF_ID, USER_ID))
                .thenReturn(Optional.empty());

        service.markRead(USER_ID, NOTIF_ID);

        verify(notificationRepository, never()).save(any());
    }

    // ── findOwned ────────────────────────────────────────────────────

    @Test
    void findOwned_returns_row_for_owned_notification() {
        Notification n = unreadNotification();
        when(notificationRepository.findByIdAndUserId(NOTIF_ID, USER_ID))
                .thenReturn(Optional.of(n));

        Optional<NotificationRow> result = service.findOwned(USER_ID, NOTIF_ID);

        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo(NOTIF_ID);
    }

    @Test
    void findOwned_returns_empty_for_foreign_notification() {
        when(notificationRepository.findByIdAndUserId(NOTIF_ID, USER_ID))
                .thenReturn(Optional.empty());

        Optional<NotificationRow> result = service.findOwned(USER_ID, NOTIF_ID);

        assertThat(result).isEmpty();
    }

    // ── unreadCount ───────────────────────────────────────────────────

    @Test
    void unreadCount_delegates_to_repository() {
        when(notificationRepository.countByUserIdAndIsReadFalse(USER_ID)).thenReturn(5L);

        assertThat(service.unreadCount(USER_ID)).isEqualTo(5L);
    }

    // ── listForUser ───────────────────────────────────────────────────

    @Test
    void listForUser_maps_entities_to_rows() {
        Notification n = unreadNotification();
        Page<Notification> page = new PageImpl<>(List.of(n));
        when(notificationRepository.findByUserIdOrderByCreatedAtDesc(eq(USER_ID), any(Pageable.class)))
                .thenReturn(page);

        Page<NotificationRow> result = service.listForUser(USER_ID, 0);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).id()).isEqualTo(NOTIF_ID);
        assertThat(result.getContent().get(0).type()).isEqualTo(NotificationType.SYSTEM);
    }

    @Test
    void listForUser_clamps_negative_page_to_zero() {
        Page<Notification> page = new PageImpl<>(List.of());
        when(notificationRepository.findByUserIdOrderByCreatedAtDesc(eq(USER_ID), any(Pageable.class)))
                .thenReturn(page);

        assertThat(service.listForUser(USER_ID, -5)).isNotNull();
    }

    // ── helpers ───────────────────────────────────────────────────────

    private Notification stubSave(String type, String refType, Long refId) {
        Notification n = unreadNotificationOf(type, refType, refId);
        when(notificationRepository.save(any(Notification.class))).thenReturn(n);
        return n;
    }

    private Notification unreadNotification() {
        return unreadNotificationOf(NotificationType.SYSTEM, null, null);
    }

    private Notification unreadNotificationOf(String type, String refType, Long refId) {
        Notification n = new Notification(USER_ID, "Title", "Body", type, refType, refId);
        try {
            var f = Notification.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(n, NOTIF_ID);
        } catch (Exception ignored) {
            // Reflection best-effort.
        }
        return n;
    }

    private static User user(String email) {
        User u = mock(User.class);
        when(u.getEmail()).thenReturn(email);
        return u;
    }
}
