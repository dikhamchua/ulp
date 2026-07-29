package com.ulp.features.flashcards.service;

import com.ulp.entities.Enrollment;
import com.ulp.features.classes.repository.EnrollmentRepository;
import com.ulp.features.flashcards.entity.FlashcardDeck;
import com.ulp.features.notifications.entity.NotificationType;
import com.ulp.features.notifications.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.ulp.common.IConstant.MSG_NOTIF_DECK_SHARED_BODY_PREFIX;
import static com.ulp.common.IConstant.MSG_NOTIF_DECK_SHARED_BODY_SUFFIX;
import static com.ulp.common.IConstant.MSG_NOTIF_DECK_SHARED_TITLE;

/**
 * Fans out in-app DECK_SHARED notifications to the students of classes a deck
 * has just started targeting.
 *
 * <p>Extracted from {@code DeckShareService} to keep both classes within the
 * file-size budget and to keep notification concerns out of the write path.
 */
@Component
public class DeckShareNotifier {

    private static final Logger log = LoggerFactory.getLogger(DeckShareNotifier.class);

    private final EnrollmentRepository enrollmentRepository;
    private final NotificationService notificationService;

    public DeckShareNotifier(EnrollmentRepository enrollmentRepository,
                             NotificationService notificationService) {
        this.enrollmentRepository = enrollmentRepository;
        this.notificationService = notificationService;
    }

    /**
     * Sends one notification per ACTIVE enrollment of each newly targeted class,
     * excluding the deck owner. Best-effort: a failing recipient does not stop
     * the others and a fan-out failure never rolls back the share. Sends no
     * email — DECK_SHARED is deliberately absent from {@code EMAIL_TYPES}.
     *
     * @param addedClassIds only the classes newly added, so re-saving an
     *                      unchanged set notifies nobody
     */
    public void notifyNewlyShared(FlashcardDeck deck, Long ownerId, List<Long> addedClassIds) {
        try {
            for (Long classId : addedClassIds) {
                notifyClass(deck, ownerId, classId);
            }
        } catch (Exception ex) {
            // Fan-out failure must not roll back the persisted share.
            log.warn("DECK_SHARED fan-out failed for deck {}", deck.getId(), ex);
        }
    }

    private void notifyClass(FlashcardDeck deck, Long ownerId, Long classId) {
        String body = MSG_NOTIF_DECK_SHARED_BODY_PREFIX + deck.getTitle()
                + MSG_NOTIF_DECK_SHARED_BODY_SUFFIX;
        for (Enrollment e : enrollmentRepository
                .findAllByClassIdAndStatusOrderByJoinedAtDesc(classId, Enrollment.STATUS_ACTIVE)) {
            Long recipientId = e.getUser().getId();
            if (ownerId.equals(recipientId)) continue;
            try {
                notificationService.create(recipientId, MSG_NOTIF_DECK_SHARED_TITLE, body,
                        NotificationType.DECK_SHARED, NotificationType.REF_DECK, deck.getId());
            } catch (Exception ex) {
                // One failing recipient must not stop the others.
                log.warn("DECK_SHARED notification failed for user {} on deck {}",
                        recipientId, deck.getId(), ex);
            }
        }
    }
}
