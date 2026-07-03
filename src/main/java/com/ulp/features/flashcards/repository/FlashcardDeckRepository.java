package com.ulp.features.flashcards.repository;

import com.ulp.features.flashcards.entity.FlashcardDeck;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

/**
 * Repository for {@link FlashcardDeck}. The entity's
 * {@code @SQLRestriction("is_deleted = 0")} already filters soft-deleted rows
 * from every query below, so callers never see deleted decks.
 */
public interface FlashcardDeckRepository extends JpaRepository<FlashcardDeck, Long> {

    /** Non-deleted decks owned by the caller, newest-updated first. */
    List<FlashcardDeck> findByOwnerIdOrderByUpdatedAtDesc(Long ownerId);

    /**
     * One page of the caller's non-deleted own decks. Ordering is governed by the
     * {@link Pageable}'s sort (newest-first by created_at + id in the service). The
     * entity's {@code @SQLRestriction} already excludes soft-deleted decks, so this
     * needs no explicit deleted filter.
     */
    Page<FlashcardDeck> findByOwnerId(Long ownerId, Pageable pageable);

    /**
     * SHARED decks targeting any of the given classes, excluding the caller's
     * own decks (those already appear in the "own" list). Newest-updated first.
     */
    List<FlashcardDeck> findByVisibilityAndClassIdInAndOwnerIdNotOrderByUpdatedAtDesc(
            String visibility, Collection<Long> classIds, Long ownerId);

    /** SHARED decks targeting a single class (class-page surface). */
    List<FlashcardDeck> findByVisibilityAndClassIdOrderByUpdatedAtDesc(
            String visibility, Long classId);
}
