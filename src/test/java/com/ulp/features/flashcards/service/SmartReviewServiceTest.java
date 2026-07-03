package com.ulp.features.flashcards.service;

import com.ulp.entities.ClassEntity;
import com.ulp.entities.Enrollment;
import com.ulp.entities.User;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.features.classes.repository.EnrollmentRepository;
import com.ulp.features.flashcards.dto.FlashcardDtos.CardItem;
import com.ulp.features.flashcards.dto.FlashcardDtos.CardView;
import com.ulp.features.flashcards.dto.FlashcardDtos.DeckForm;
import com.ulp.features.flashcards.entity.FlashcardReview;
import com.ulp.features.flashcards.repository.FlashcardReviewRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Integration tests for {@link SmartReviewService}: due selection, upsert,
 *  scheduling, and per-user isolation. */
@SpringBootTest
@Transactional
class SmartReviewServiceTest {

    @Autowired private DeckService deckService;
    @Autowired private CardService cardService;
    @Autowired private SmartReviewService smartReviewService;
    @Autowired private FlashcardReviewRepository reviewRepository;
    @Autowired private ClassRepository classRepository;
    @Autowired private EnrollmentRepository enrollmentRepository;
    @Autowired private UserRepository userRepository;

    private User owner;
    private User member;
    private ClassEntity clazz;
    private Long deckId;
    private Long card1;
    private Long card2;

    @BeforeEach
    void setUp() {
        owner = userRepository.findByEmailIgnoreCase("student@ulp.edu.vn").orElseThrow();
        member = userRepository.findByEmailIgnoreCase("sv02@ulp.edu.vn").orElseThrow();
        User lecturer = userRepository.findByEmailIgnoreCase("lecturer@ulp.edu.vn").orElseThrow();
        clazz = saveClass(lecturer, "Review class", "RVCLS");
        enroll(owner);
        enroll(member);

        deckId = deckService.createDeck(owner.getId(), new DeckForm("Ôn tập", null));
        cardService.replaceCards(deckId, owner.getId(), List.of(
                new CardItem(null, "c1", "b1"),
                new CardItem(null, "c2", "b2")));
        deckService.share(deckId, owner.getId(), clazz.getId());
        List<CardView> cards = cardService.getEditorView(deckId, owner.getId()).cards();
        card1 = cards.get(0).id();
        card2 = cards.get(1).id();
    }

    @Test
    void new_cards_are_all_due() {
        assertThat(smartReviewService.getDueCards(deckId, owner.getId())).hasSize(2);
    }

    @Test
    void rating_upserts_single_row_and_removes_from_due() {
        smartReviewService.recordRating(card1, owner.getId(), 4);
        smartReviewService.recordRating(card1, owner.getId(), 5);

        // Exactly one review row for (owner, card1) despite two ratings.
        assertThat(reviewRepository.findByUserIdAndFlashcardId(owner.getId(), card1)).isPresent();
        long rows = reviewRepository.findAll().stream()
                .filter(r -> r.getUserId().equals(owner.getId()) && r.getFlashcardId().equals(card1))
                .count();
        assertThat(rows).isEqualTo(1);

        // card1 no longer due (scheduled for the future); card2 still due.
        assertThat(smartReviewService.getDueCards(deckId, owner.getId()))
                .extracting(CardView::id).containsExactly(card2);
    }

    @Test
    void poor_recall_keeps_interval_at_one() {
        smartReviewService.recordRating(card1, owner.getId(), 1);
        FlashcardReview r = reviewRepository
                .findByUserIdAndFlashcardId(owner.getId(), card1).orElseThrow();
        assertThat(r.getIntervalDays()).isEqualTo(1);
        assertThat(r.getRepetitions()).isZero();
    }

    @Test
    void successive_good_ratings_grow_interval() {
        smartReviewService.recordRating(card1, owner.getId(), 4); // interval 1
        smartReviewService.recordRating(card1, owner.getId(), 4); // interval 6
        smartReviewService.recordRating(card1, owner.getId(), 4); // interval 15
        FlashcardReview r = reviewRepository
                .findByUserIdAndFlashcardId(owner.getId(), card1).orElseThrow();
        assertThat(r.getIntervalDays()).isEqualTo(15);
        assertThat(r.getEasinessFactor()).isGreaterThanOrEqualTo(1.30);
    }

    @Test
    void review_state_is_isolated_per_user() {
        smartReviewService.recordRating(card1, owner.getId(), 4);

        // Owner's card1 is scheduled away; the member still sees it as due.
        assertThat(smartReviewService.getDueCards(deckId, owner.getId()))
                .extracting(CardView::id).doesNotContain(card1);
        assertThat(smartReviewService.getDueCards(deckId, member.getId()))
                .extracting(CardView::id).contains(card1, card2);
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private void enroll(User u) {
        enrollmentRepository.saveAndFlush(Enrollment.createFor(
                u, clazz.getId(), Enrollment.JoinedVia.CODE, null));
    }

    private ClassEntity saveClass(User lecturer, String name, String code) {
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
