package com.ulp.features.notifications.service;

import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.mail.job.MailJob;
import com.ulp.features.mail.job.MailJobEnqueueHelper;
import com.ulp.features.notifications.dto.NotificationDtos.NotificationRow;
import com.ulp.features.notifications.entity.Notification;
import com.ulp.features.notifications.entity.NotificationType;
import com.ulp.features.notifications.repository.NotificationRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Application service for in-app notifications (Sprint 5, #63/#64).
 *
 * <p>Owns creation (with optional background email for whitelisted types),
 * listing, unread-count, and owner-scoped mark-read. Entities never leak
 * past this layer.
 *
 * <p><b>Email policy.</b> Types in {@link NotificationType#EMAIL_TYPES} do
 * <em>not</em> call SMTP on the request thread. After the notification row is
 * saved, a {@link MailJob} is enqueued via {@link MailJobEnqueueHelper}
 * (after-commit). The dedicated mail worker delivers it and flips
 * {@code is_email_sent} on success. See {@code .claude/rules/mail-job-queue.md}.
 *
 * <p>Critical transactional mail that is not a notification (password reset,
 * admin test-send) still uses {@code MailService} directly and stays sync.
 */
@Service
public class NotificationService {

    /** Page size for the notifications list. */
    static final int NOTIFICATION_PAGE_SIZE = 20;

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final MailJobEnqueueHelper mailJobEnqueueHelper;

    public NotificationService(NotificationRepository notificationRepository,
                               UserRepository userRepository,
                               MailJobEnqueueHelper mailJobEnqueueHelper) {
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
        this.mailJobEnqueueHelper = mailJobEnqueueHelper;
    }

    // ── Creation ────────────────────────────────────────────────────────

    /**
     * Persists a new notification and, when the type is email-whitelisted
     * ({@link NotificationType#EMAIL_TYPES}), enqueues a background email.
     *
     * <p>Queue full / missing recipient email does NOT prevent persistence.
     *
     * @param userId        the recipient's user id
     * @param title         short notification title (Vietnamese UI text)
     * @param content       longer notification body (Vietnamese UI text)
     * @param type          notification type constant from {@link NotificationType}
     * @param referenceType optional reference domain type (e.g. "CLASS", "LESSON")
     * @param referenceId   optional reference domain id
     * @return the persisted notification row
     */
    @Transactional
    public Notification create(Long userId, String title, String content,
                               String type, String referenceType, Long referenceId) {
        Notification notification = new Notification(userId, title, content,
                type, referenceType, referenceId);
        Notification saved = notificationRepository.save(notification);

        if (NotificationType.EMAIL_TYPES.contains(type)) {
            enqueueEmail(userId, title, content, saved.getId(), type);
        }

        return saved;
    }

    /**
     * Looks up the recipient email and schedules a {@link MailJob} after commit.
     * Missing user / blank email is a silent no-op (in-app row already exists).
     */
    private void enqueueEmail(Long userId, String title, String content,
                              Long notificationId, String type) {
        userRepository.findById(userId).ifPresent(user -> {
            String email = user.getEmail();
            if (email == null || email.isBlank()) {
                return;
            }
            mailJobEnqueueHelper.enqueueAfterCommit(
                    MailJob.forNotification(
                            email,
                            "[ULP] " + title,
                            content,
                            notificationId,
                            type));
        });
    }

    // ── Listing ──────────────────────────────────────────────────────────

    /**
     * Returns a page of the caller's notifications, newest first.
     *
     * @param userId the caller's user id
     * @param page   zero-based page index
     * @return a page of {@link NotificationRow}s
     */
    @Transactional(readOnly = true)
    public Page<NotificationRow> listForUser(Long userId, int page) {
        Pageable pageable = PageRequest.of(Math.max(0, page), NOTIFICATION_PAGE_SIZE);
        return notificationRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable)
                .map(this::toRow);
    }

    // ── Unread count ─────────────────────────────────────────────────────

    /**
     * The caller's total unread notification count for the header badge.
     *
     * @param userId the caller's user id
     * @return total unread notifications
     */
    @Transactional(readOnly = true)
    public long unreadCount(Long userId) {
        return notificationRepository.countByUserIdAndIsReadFalse(userId);
    }

    // ── Mark read ────────────────────────────────────────────────────────

    /**
     * Marks a notification as read (owner-scoped, no-leak). If the id does not
     * exist or belongs to a different user, this is a silent no-op — no
     * modification, no error, no existence leak.
     *
     * @param userId   the caller's user id
     * @param notifId  the notification id to mark read
     */
    @Transactional
    public void markRead(Long userId, Long notifId) {
        notificationRepository.findByIdAndUserId(notifId, userId).ifPresent(n -> {
            if (!n.isRead()) {
                n.setRead(true);
                n.setReadAt(LocalDateTime.now());
                notificationRepository.save(n);
            }
        });
    }

    /**
     * Returns the notification if it belongs to the caller, or empty otherwise
     * (used by the controller to build the redirect target after marking read).
     *
     * @param userId  the caller's user id
     * @param notifId the notification id
     * @return the row DTO, or empty if absent or foreign
     */
    @Transactional(readOnly = true)
    public java.util.Optional<NotificationRow> findOwned(Long userId, Long notifId) {
        return notificationRepository.findByIdAndUserId(notifId, userId).map(this::toRow);
    }

    // ── Helper ───────────────────────────────────────────────────────────

    /** Maps a {@link Notification} entity to a list-row DTO. */
    private NotificationRow toRow(Notification n) {
        return new NotificationRow(
                n.getId(), n.getTitle(), n.getContent(), n.getType(),
                n.getReferenceType(), n.getReferenceId(),
                n.isRead(), n.getReadAt(), n.getCreatedAt());
    }
}
