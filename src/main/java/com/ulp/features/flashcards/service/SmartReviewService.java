package com.ulp.features.flashcards.service;

import com.ulp.features.flashcards.dto.FlashcardDtos.CardView;
import com.ulp.features.flashcards.dto.FlashcardDtos.ReviewResult;
import com.ulp.features.flashcards.entity.Flashcard;
import com.ulp.features.flashcards.entity.FlashcardReview;
import com.ulp.features.flashcards.repository.FlashcardRepository;
import com.ulp.features.flashcards.repository.FlashcardReviewRepository;
import com.ulp.features.flashcards.service.Sm2Scheduler.Sm2State;
import com.ulp.features.flashcards.support.DeckAccessResolver;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static com.ulp.common.IConstant.MSG_CARD_NOT_FOUND;

/**
 * Smart Review (SM-2) service (ULP-5.x): selects due cards for a user and
 * records recall ratings, upserting exactly one review row per (user, card).
 * Scheduling per user is fully isolated — one student's ratings never touch
 * another's due set on a shared deck.
 */
@Service
public class SmartReviewService {

    private final FlashcardRepository cardRepository;
    private final FlashcardReviewRepository reviewRepository;
    private final DeckAccessResolver accessResolver;
    private final Sm2Scheduler scheduler;

    public SmartReviewService(FlashcardRepository cardRepository,
                              FlashcardReviewRepository reviewRepository,
                              DeckAccessResolver accessResolver,
                              Sm2Scheduler scheduler) {
        this.cardRepository = cardRepository;
        this.reviewRepository = reviewRepository;
        this.accessResolver = accessResolver;
        this.scheduler = scheduler;
    }

    /** Cards of a deck due now for the user (no review row, or next_review ≤ now). */
    @Transactional(readOnly = true)
    public List<CardView> getDueCards(Long deckId, Long userId) {
        accessResolver.requireViewable(deckId, userId);
        List<CardView> cards = new ArrayList<>();
        for (Flashcard c : cardRepository.findDueCards(deckId, userId, LocalDateTime.now())) {
            cards.add(new CardView(c.getId(), c.getFrontText(), c.getBackText()));
        }
        return cards;
    }

    /** Count of due cards for the user (progress badge). */
    @Transactional(readOnly = true)
    public int countDue(Long deckId, Long userId) {
        return cardRepository.findDueCards(deckId, userId, LocalDateTime.now()).size();
    }

    /**
     * Records a recall rating for a card and upserts the user's SM-2 state row.
     * Access is gated on the card's deck (viewable). Returns the remaining due
     * count and the new interval.
     *
     * @throws EntityNotFoundException if the card does not exist
     */
    @Transactional
    public ReviewResult recordRating(Long cardId, Long userId, int quality) {
        // SM-2 quality is 0..5; reject out-of-range so the DB CHECK never trips.
        if (quality < 0 || quality > 5) {
            throw new IllegalArgumentException("Điểm ghi nhớ không hợp lệ");
        }
        Flashcard card = cardRepository.findById(cardId)
                .orElseThrow(() -> new EntityNotFoundException(MSG_CARD_NOT_FOUND));
        accessResolver.requireViewable(card.getDeckId(), userId);

        FlashcardReview review = reviewRepository
                .findByUserIdAndFlashcardId(userId, cardId).orElse(null);
        double priorEf = review != null ? review.getEasinessFactor() : Sm2Scheduler.DEFAULT_EF;
        int priorReps = review != null ? review.getRepetitions() : 0;
        int priorInterval = review != null ? review.getIntervalDays() : 1;

        Sm2State state = scheduler.schedule(quality, priorEf, priorReps,
                priorInterval, LocalDateTime.now());

        if (review == null) {
            review = new FlashcardReview(userId, cardId, quality, state.easinessFactor(),
                    state.intervalDays(), state.repetitions(), state.nextReviewAt());
        } else {
            review.apply(quality, state.easinessFactor(), state.intervalDays(),
                    state.repetitions(), state.nextReviewAt());
        }
        reviewRepository.save(review);

        int remaining = cardRepository
                .findDueCards(card.getDeckId(), userId, LocalDateTime.now()).size();
        return new ReviewResult(remaining, state.intervalDays());
    }
}
