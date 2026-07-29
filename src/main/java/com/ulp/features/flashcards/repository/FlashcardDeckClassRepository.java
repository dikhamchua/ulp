package com.ulp.features.flashcards.repository;

import com.ulp.features.flashcards.entity.FlashcardDeckClass;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

import java.util.Collection;
import java.util.List;

/**
 * Repository for {@link FlashcardDeckClass}, the deck → class sharing targets.
 *
 * <p>{@link #findByDeckIdIn} exists so a list of decks can have its targets
 * resolved in one query — callers must never issue one lookup per deck.
 */
public interface FlashcardDeckClassRepository extends JpaRepository<FlashcardDeckClass, Long> {

    /** Every class the given deck currently targets. */
    List<FlashcardDeckClass> findByDeckId(Long deckId);

    /** Batch variant for assembling a list of decks without N+1. */
    List<FlashcardDeckClass> findByDeckIdIn(Collection<Long> deckIds);

    /** Every deck target row pointing at the given class. */
    List<FlashcardDeckClass> findByClassId(Long classId);

    /** Removes the given classes from a deck's target set. */
    @Modifying
    void deleteByDeckIdAndClassIdIn(Long deckId, Collection<Long> classIds);

    /** Removes every target of a deck ("unshare from all classes"). */
    @Modifying
    void deleteByDeckId(Long deckId);
}
