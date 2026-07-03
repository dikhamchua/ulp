package com.ulp.features.flashcards.repository;

import com.ulp.features.flashcards.entity.FlashcardReview;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** Repository for {@link FlashcardReview} SM-2 state rows (one per user-card). */
public interface FlashcardReviewRepository extends JpaRepository<FlashcardReview, Long> {

    /** The single SM-2 state row for a (user, card) pair, if any. */
    Optional<FlashcardReview> findByUserIdAndFlashcardId(Long userId, Long flashcardId);
}
