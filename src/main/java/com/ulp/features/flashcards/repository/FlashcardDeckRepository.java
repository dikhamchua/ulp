package com.ulp.features.flashcards.repository;

import com.ulp.features.flashcards.entity.FlashcardDeck;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
     * Decks targeting any of the given classes, excluding the caller's own decks
     * (those already appear in the "own" list). Newest-updated first.
     *
     * <p>{@code DISTINCT} matters: a deck targeting several of the caller's
     * classes joins once per target and would otherwise appear repeatedly.
     * Callers MUST pass a non-empty collection (empty {@code IN ()} is invalid).
     */
    @Query("SELECT DISTINCT d FROM FlashcardDeck d "
            + "JOIN FlashcardDeckClass dc ON dc.deckId = d.id "
            + "WHERE dc.classId IN :classIds AND d.ownerId <> :ownerId "
            + "ORDER BY d.updatedAt DESC")
    List<FlashcardDeck> findSharedToClassesExcludingOwner(
            @Param("classIds") Collection<Long> classIds, @Param("ownerId") Long ownerId);

    /** Decks targeting a single class (class-page surface), newest-updated first. */
    @Query("SELECT DISTINCT d FROM FlashcardDeck d "
            + "JOIN FlashcardDeckClass dc ON dc.deckId = d.id "
            + "WHERE dc.classId = :classId "
            + "ORDER BY d.updatedAt DESC")
    List<FlashcardDeck> findSharedToClass(@Param("classId") Long classId);
}
