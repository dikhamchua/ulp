package com.ulp.features.flashcards.service;

import com.ulp.entities.ClassEntity;
import com.ulp.entities.Enrollment;
import com.ulp.entities.User;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.features.classes.repository.EnrollmentRepository;
import com.ulp.features.flashcards.dto.FlashcardDtos.DeckForm;
import com.ulp.features.notifications.entity.Notification;
import com.ulp.features.notifications.entity.NotificationType;
import com.ulp.features.notifications.repository.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for the {@code DECK_SHARED} fan-out driven by
 * {@link DeckShareService}: only newly added classes notify, and the deck
 * owner is never a recipient.
 */
@SpringBootTest
@Transactional
class DeckShareNotificationTest {

    @Autowired private DeckService deckService;
    @Autowired private DeckShareService deckShareService;
    @Autowired private ClassRepository classRepository;
    @Autowired private EnrollmentRepository enrollmentRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private NotificationRepository notificationRepository;

    private User owner;
    private User memberA;
    private User memberB;
    private User lecturer;
    private ClassEntity classA;
    private ClassEntity classB;
    private Long deckId;

    @BeforeEach
    void setUp() {
        owner = userRepository.findByEmailIgnoreCase("student@ulp.edu.vn").orElseThrow();
        memberA = userRepository.findByEmailIgnoreCase("sv02@ulp.edu.vn").orElseThrow();
        memberB = userRepository.findByEmailIgnoreCase("sv01@ulp.edu.vn").orElseThrow();
        lecturer = userRepository.findByEmailIgnoreCase("lecturer@ulp.edu.vn").orElseThrow();

        classA = saveClass("Notify class A", "NTFA");
        classB = saveClass("Notify class B", "NTFB");
        enroll(owner, classA);
        enroll(owner, classB);
        enroll(memberA, classA);
        enroll(memberB, classB);

        deckId = deckService.createDeck(owner.getId(), new DeckForm("Thông báo", null));
    }

    @Test
    void newly_shared_class_notifies_each_enrolled_student_once() {
        deckShareService.share(deckId, owner.getId(), List.of(classA.getId()));

        assertThat(deckNotificationsFor(memberA.getId())).hasSize(1);
        Notification n = deckNotificationsFor(memberA.getId()).get(0);
        assertThat(n.getType()).isEqualTo(NotificationType.DECK_SHARED);
        assertThat(n.getReferenceType()).isEqualTo(NotificationType.REF_DECK);
        assertThat(n.getReferenceId()).isEqualTo(deckId);
        assertThat(n.isEmailSent()).isFalse();
    }

    @Test
    void owner_never_receives_a_deck_shared_notification() {
        deckShareService.share(deckId, owner.getId(), List.of(classA.getId(), classB.getId()));
        assertThat(deckNotificationsFor(owner.getId())).isEmpty();
    }

    @Test
    void resubmitting_the_same_set_creates_no_further_notifications() {
        deckShareService.share(deckId, owner.getId(), List.of(classA.getId()));
        deckShareService.share(deckId, owner.getId(), List.of(classA.getId()));

        assertThat(deckNotificationsFor(memberA.getId())).hasSize(1);
    }

    @Test
    void removing_a_class_creates_no_notification() {
        deckShareService.share(deckId, owner.getId(), List.of(classA.getId(), classB.getId()));
        int beforeA = deckNotificationsFor(memberA.getId()).size();
        int beforeB = deckNotificationsFor(memberB.getId()).size();

        deckShareService.share(deckId, owner.getId(), List.of(classA.getId()));

        assertThat(deckNotificationsFor(memberA.getId())).hasSize(beforeA);
        assertThat(deckNotificationsFor(memberB.getId())).hasSize(beforeB);
    }

    @Test
    void unshare_all_creates_no_notification() {
        deckShareService.share(deckId, owner.getId(), List.of(classA.getId()));
        int before = deckNotificationsFor(memberA.getId()).size();

        deckShareService.unshareAll(deckId, owner.getId());

        assertThat(deckNotificationsFor(memberA.getId())).hasSize(before);
    }

    @Test
    void rejected_share_creates_no_notification() {
        ClassEntity foreign = saveClass("Foreign", "FRGN");
        assertThatThrownBy(() -> deckShareService.share(deckId, owner.getId(),
                List.of(classA.getId(), foreign.getId())))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(deckNotificationsFor(memberA.getId())).isEmpty();
    }

    @Test
    void deck_shared_is_not_an_emailed_type() {
        // Mail policy: DECK_SHARED stays in-app only (see mail-job-queue rule).
        assertThat(NotificationType.EMAIL_TYPES).doesNotContain(NotificationType.DECK_SHARED);
        assertThat(NotificationType.EMAIL_TYPES)
                .containsExactly(NotificationType.ASSIGNMENT_PUBLISHED);
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /** DECK_SHARED notifications for this deck addressed to one user. */
    private List<Notification> deckNotificationsFor(Long userId) {
        return notificationRepository.findAll().stream()
                .filter(n -> userId.equals(n.getUserId()))
                .filter(n -> NotificationType.DECK_SHARED.equals(n.getType()))
                .filter(n -> deckId.equals(n.getReferenceId()))
                .toList();
    }

    private void enroll(User u, ClassEntity c) {
        enrollmentRepository.saveAndFlush(Enrollment.createFor(
                u, c.getId(), Enrollment.JoinedVia.CODE, null));
    }

    private ClassEntity saveClass(String name, String code) {
        ClassEntity entity = new ClassEntity(name, lecturer.getId(), lecturer.getId(),
                null, null, null, 100);
        entity.setCode(code);
        try {
            return classRepository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException ex) {
            entity.setCode(code + "x");
            return classRepository.saveAndFlush(entity);
        }
    }
}
