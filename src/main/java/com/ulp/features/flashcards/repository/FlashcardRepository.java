package com.ulp.features.flashcards.repository;

import com.ulp.features.flashcards.entity.Flashcard;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/** Repository for {@link Flashcard} rows. */
public interface FlashcardRepository extends JpaRepository<Flashcard, Long> {

    /** Cards of a deck in editor/study order. */
    List<Flashcard> findByDeckIdOrderBySortOrderAsc(Long deckId);

    /** Number of cards in a deck (list card counts). */
    long countByDeckId(Long deckId);

    /** Per-deck card counts for a set of decks, as {@code [deckId, count]} rows. */
    @Query("SELECT c.deckId, COUNT(c) FROM Flashcard c " +
            "WHERE c.deckId IN :deckIds GROUP BY c.deckId")
    List<Object[]> countByDeckIds(@Param("deckIds") Collection<Long> deckIds);

    /**
     * Cards of a deck that are DUE for the given user: no review row yet, or a
     * review row whose {@code next_review_at} is at/before {@code now}. One
     * query, no N+1 — the LEFT-anti / EXISTS pair evaluates per card.
     */
    @Query("SELECT c FROM Flashcard c WHERE c.deckId = :deckId AND (" +
            "NOT EXISTS (SELECT r FROM FlashcardReview r " +
            "            WHERE r.flashcardId = c.id AND r.userId = :userId) " +
            "OR EXISTS (SELECT r FROM FlashcardReview r " +
            "           WHERE r.flashcardId = c.id AND r.userId = :userId " +
            "           AND r.nextReviewAt <= :now)) " +
            "ORDER BY c.sortOrder ASC")
    List<Flashcard> findDueCards(@Param("deckId") Long deckId,
                                 @Param("userId") Long userId,
                                 @Param("now") LocalDateTime now);
}
