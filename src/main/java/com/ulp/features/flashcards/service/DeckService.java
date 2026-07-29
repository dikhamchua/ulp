package com.ulp.features.flashcards.service;

import com.ulp.entities.ClassEntity;
import com.ulp.entities.Enrollment;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.features.classes.repository.EnrollmentRepository;
import com.ulp.features.flashcards.dto.FlashcardDtos.ClassOption;
import com.ulp.features.flashcards.dto.FlashcardDtos.DeckDetailView;
import com.ulp.features.flashcards.dto.FlashcardDtos.DeckForm;
import com.ulp.features.flashcards.dto.FlashcardDtos.DeckSummary;
import com.ulp.features.flashcards.dto.FlashcardDtos.StudentDeckList;
import com.ulp.features.flashcards.entity.FlashcardDeck;
import com.ulp.features.flashcards.repository.FlashcardDeckRepository;
import com.ulp.features.flashcards.repository.FlashcardRepository;
import com.ulp.features.flashcards.support.DeckAccessResolver;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.ulp.common.IConstant.DEFAULT_DECK_PAGE_SIZE;

/**
 * Deck lifecycle (create / update / soft-delete) plus list and detail view
 * assembly. Sharing lives in {@link DeckShareService}.
 */
@Service
public class DeckService {

    private final FlashcardDeckRepository deckRepository;
    private final FlashcardRepository cardRepository;
    private final DeckAccessResolver accessResolver;
    private final DeckSummaryAssembler assembler;
    private final EnrollmentRepository enrollmentRepository;
    private final ClassRepository classRepository;
    private final DeckShareService shareService;

    public DeckService(FlashcardDeckRepository deckRepository,
                       FlashcardRepository cardRepository,
                       DeckAccessResolver accessResolver,
                       DeckSummaryAssembler assembler,
                       EnrollmentRepository enrollmentRepository,
                       ClassRepository classRepository,
                       DeckShareService shareService) {
        this.deckRepository = deckRepository;
        this.cardRepository = cardRepository;
        this.accessResolver = accessResolver;
        this.assembler = assembler;
        this.enrollmentRepository = enrollmentRepository;
        this.classRepository = classRepository;
        this.shareService = shareService;
    }

    /** Creates a new deck owned by the caller, targeting no classes; returns its id. */
    @Transactional
    public Long createDeck(Long ownerId, DeckForm form) {
        FlashcardDeck deck = new FlashcardDeck(ownerId, form.title().trim(),
                trimToNull(form.description()));
        return deckRepository.save(deck).getId();
    }

    /** Updates a deck's metadata; owner-only. */
    @Transactional
    public void updateMetadata(Long deckId, Long ownerId, DeckForm form) {
        FlashcardDeck deck = accessResolver.requireOwner(deckId, ownerId);
        deck.updateMetadata(form.title().trim(), trimToNull(form.description()));
        deckRepository.save(deck);
    }

    /** Soft-deletes a deck; owner-only. */
    @Transactional
    public void softDelete(Long deckId, Long ownerId) {
        FlashcardDeck deck = accessResolver.requireOwner(deckId, ownerId);
        deck.markDeleted();
        deckRepository.save(deck);
    }

    /** Detail view-model for the launcher page (owner or shared member). */
    @Transactional(readOnly = true)
    public DeckDetailView getDetail(Long deckId, Long userId) {
        DeckAccessResolver.ResolvedDeck resolved = accessResolver.resolve(deckId, userId);
        if (resolved.access() == DeckAccessResolver.DeckAccess.NONE) {
            throw new EntityNotFoundException(DeckAccessResolver.NF_MSG);
        }
        FlashcardDeck deck = resolved.deck();
        long count = cardRepository.countByDeckId(deckId);
        List<ClassOption> targets = targetClasses(deckId);
        List<ClassOption> shareClasses = resolved.isOwner()
                ? shareService.shareableClasses(userId) : List.of();
        return new DeckDetailView(deck.getId(), deck.getTitle(), deck.getDescription(),
                count, resolved.isOwner(), !targets.isEmpty(), targets, shareClasses);
    }

    /** The classes a deck currently targets, as id + name options. */
    @Transactional(readOnly = true)
    public List<ClassOption> targetClasses(Long deckId) {
        Set<Long> ids = shareService.currentTargets(deckId);
        if (ids.isEmpty()) return List.of();
        List<ClassOption> options = new ArrayList<>();
        for (ClassEntity c : classRepository.findAllById(ids)) {
            options.add(new ClassOption(c.getId(), c.getName()));
        }
        return options;
    }

    /**
     * One SSR page of the caller's own decks (newest-first) plus all decks shared
     * to their ACTIVE-enrolled classes. Only own decks paginate (the numbered
     * pager navigates by {@code ?page=N}); shared decks are returned in full.
     * Card counts for the page are resolved in one batch query (no N+1).
     *
     * @param page zero-based page index (negative clamps to 0)
     */
    @Transactional(readOnly = true)
    public StudentDeckList listForStudent(Long userId, int page) {
        Page<DeckSummary> ownPage = ownDecksPage(userId, page);
        List<Long> classIds = activeClassIds(userId);
        List<FlashcardDeck> shared = classIds.isEmpty() ? List.of()
                : deckRepository.findSharedToClassesExcludingOwner(classIds, userId);
        return new StudentDeckList(ownPage, assembler.toSummaries(shared, userId));
    }

    /**
     * One page of the caller's own decks as a {@code Page<DeckSummary>},
     * newest-first. The deck page is fetched with the paging query, then its
     * content is batch-assembled into summaries and re-wrapped preserving the
     * original {@code Pageable} and total count (so {@code totalPages} etc. stay
     * correct for the pager). id is a stable tiebreaker so same-second decks keep
     * a fixed order and never drift between pages.
     */
    private Page<DeckSummary> ownDecksPage(Long userId, int page) {
        int safePage = Math.max(page, 0);
        PageRequest pageable = PageRequest.of(safePage, DEFAULT_DECK_PAGE_SIZE,
                Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        Page<FlashcardDeck> deckPage = deckRepository.findByOwnerId(userId, pageable);
        List<DeckSummary> summaries = assembler.toSummaries(deckPage.getContent(), userId);
        return new PageImpl<>(summaries, pageable, deckPage.getTotalElements());
    }

    /** Decks targeting a class (surfaced on the class page). */
    @Transactional(readOnly = true)
    public List<DeckSummary> listSharedForClass(Long classId, Long userId) {
        return assembler.toSummaries(deckRepository.findSharedToClass(classId), userId);
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private List<Long> activeClassIds(Long userId) {
        List<Long> ids = new ArrayList<>();
        for (Enrollment e : enrollmentRepository
                .findAllByUserIdAndStatusOrderByJoinedAtDesc(userId, Enrollment.STATUS_ACTIVE)) {
            ids.add(e.getClassId());
        }
        return ids;
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
