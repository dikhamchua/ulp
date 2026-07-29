package com.ulp.features.flashcards.service;

import com.ulp.entities.ClassEntity;
import com.ulp.entities.Enrollment;
import com.ulp.entities.User;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.features.classes.repository.EnrollmentRepository;
import com.ulp.features.flashcards.dto.FlashcardDtos.ClassOption;
import com.ulp.features.flashcards.dto.FlashcardDtos.DeckForm;
import jakarta.persistence.EntityNotFoundException;
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
 * Integration tests for {@link DeckShareService}: multi-class set replacement,
 * all-or-nothing validation, and unshare-all.
 */
@SpringBootTest
@Transactional
class DeckShareServiceTest {

    @Autowired private DeckService deckService;
    @Autowired private DeckShareService deckShareService;
    @Autowired private ClassRepository classRepository;
    @Autowired private EnrollmentRepository enrollmentRepository;
    @Autowired private UserRepository userRepository;

    private User owner;
    private User memberA;   // enrolled in classA only
    private User memberB;   // enrolled in classB only
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

        classA = saveClass("Share class A", "SHRA");
        classB = saveClass("Share class B", "SHRB");
        enroll(owner, classA);
        enroll(owner, classB);
        enroll(memberA, classA);
        enroll(memberB, classB);

        deckId = deckService.createDeck(owner.getId(), new DeckForm("Đa lớp", null));
    }

    @Test
    void share_to_several_classes_lets_members_of_all_of_them_view() {
        deckShareService.share(deckId, owner.getId(), List.of(classA.getId(), classB.getId()));

        assertThat(deckService.getDetail(deckId, memberA.getId()).shared()).isTrue();
        assertThat(deckService.getDetail(deckId, memberB.getId()).shared()).isTrue();
        assertThat(deckShareService.currentTargets(deckId))
                .containsExactlyInAnyOrder(classA.getId(), classB.getId());
    }

    @Test
    void removing_one_class_from_the_set_revokes_only_that_class() {
        deckShareService.share(deckId, owner.getId(), List.of(classA.getId(), classB.getId()));
        // Re-submit with classB dropped.
        deckShareService.share(deckId, owner.getId(), List.of(classA.getId()));

        assertThat(deckShareService.currentTargets(deckId)).containsExactly(classA.getId());
        assertThat(deckService.getDetail(deckId, memberA.getId()).shared()).isTrue();
        assertThatThrownBy(() -> deckService.getDetail(deckId, memberB.getId()))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void resubmitting_the_same_set_leaves_targets_untouched() {
        deckShareService.share(deckId, owner.getId(), List.of(classA.getId(), classB.getId()));
        deckShareService.share(deckId, owner.getId(), List.of(classB.getId(), classA.getId()));

        assertThat(deckShareService.currentTargets(deckId))
                .containsExactlyInAnyOrder(classA.getId(), classB.getId());
    }

    @Test
    void empty_submission_clears_every_target() {
        deckShareService.share(deckId, owner.getId(), List.of(classA.getId(), classB.getId()));
        deckShareService.share(deckId, owner.getId(), List.of());

        assertThat(deckShareService.currentTargets(deckId)).isEmpty();
        assertThat(deckService.getDetail(deckId, owner.getId()).shared()).isFalse();
    }

    @Test
    void unshare_all_clears_every_target() {
        deckShareService.share(deckId, owner.getId(), List.of(classA.getId(), classB.getId()));
        deckShareService.unshareAll(deckId, owner.getId());

        assertThat(deckShareService.currentTargets(deckId)).isEmpty();
        assertThatThrownBy(() -> deckService.getDetail(deckId, memberA.getId()))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void unshare_all_on_a_deck_with_no_targets_succeeds_silently() {
        deckShareService.unshareAll(deckId, owner.getId());
        assertThat(deckShareService.currentTargets(deckId)).isEmpty();
    }

    @Test
    void ineligible_class_is_rejected_and_previous_targets_are_unchanged() {
        deckShareService.share(deckId, owner.getId(), List.of(classA.getId()));
        ClassEntity foreign = saveClass("Not mine", "NOTM");

        assertThatThrownBy(() ->
                deckShareService.share(deckId, owner.getId(), List.of(foreign.getId())))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(deckShareService.currentTargets(deckId)).containsExactly(classA.getId());
    }

    @Test
    void mixed_valid_and_invalid_submission_writes_nothing() {
        deckShareService.share(deckId, owner.getId(), List.of(classA.getId()));
        ClassEntity foreign = saveClass("Not mine 2", "NOTM2");

        // classB is valid and would otherwise be added; the foreign id must veto it.
        assertThatThrownBy(() -> deckShareService.share(deckId, owner.getId(),
                List.of(classB.getId(), foreign.getId())))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(deckShareService.currentTargets(deckId)).containsExactly(classA.getId());
    }

    @Test
    void shareable_classes_include_the_owners_active_enrollments() {
        List<Long> ids = deckShareService.shareableClasses(owner.getId())
                .stream().map(ClassOption::id).toList();
        assertThat(ids).contains(classA.getId(), classB.getId());
    }

    // ── Helpers ─────────────────────────────────────────────────────────

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
