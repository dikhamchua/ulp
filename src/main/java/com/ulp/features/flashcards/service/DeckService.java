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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

import static com.ulp.common.IConstant.DEFAULT_DECK_PAGE_SIZE;
import static com.ulp.common.IConstant.MSG_SHARE_CLASS_INVALID;

/** Deck CRUD, listing and sharing (ULP-5.x). */
@Service
public class DeckService {

    private final FlashcardDeckRepository deckRepository;
    private final FlashcardRepository cardRepository;
    private final DeckAccessResolver accessResolver;
    private final DeckSummaryAssembler assembler;
    private final EnrollmentRepository enrollmentRepository;
    private final ClassRepository classRepository;

    public DeckService(FlashcardDeckRepository deckRepository,
                       FlashcardRepository cardRepository,
                       DeckAccessResolver accessResolver,
                       DeckSummaryAssembler assembler,
                       EnrollmentRepository enrollmentRepository,
                       ClassRepository classRepository) {
        this.deckRepository = deckRepository;
        this.cardRepository = cardRepository;
        this.accessResolver = accessResolver;
        this.assembler = assembler;
        this.enrollmentRepository = enrollmentRepository;
        this.classRepository = classRepository;
    }

    /** Creates a new PRIVATE deck owned by the caller; returns its id. */
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
        String className = deck.getClassId() == null ? null
                : classRepository.findById(deck.getClassId())
                        .map(ClassEntity::getName).orElse(null);
        List<ClassOption> shareClasses = resolved.isOwner() ? shareableClasses(userId) : List.of();
        return new DeckDetailView(deck.getId(), deck.getTitle(), deck.getDescription(),
                count, resolved.isOwner(), deck.isShared(), deck.getClassId(),
                className, shareClasses);
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
                : deckRepository.findByVisibilityAndClassIdInAndOwnerIdNotOrderByUpdatedAtDesc(
                        FlashcardDeck.VISIBILITY_SHARED, classIds, userId);
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

    /** SHARED decks targeting a class (surfaced on the class page). */
    @Transactional(readOnly = true)
    public List<DeckSummary> listSharedForClass(Long classId, Long userId) {
        List<FlashcardDeck> shared = deckRepository
                .findByVisibilityAndClassIdOrderByUpdatedAtDesc(
                        FlashcardDeck.VISIBILITY_SHARED, classId);
        return assembler.toSummaries(shared, userId);
    }

    /** Shares a deck to one of the owner's classes; owner-only. */
    @Transactional
    public void share(Long deckId, Long ownerId, Long classId) {
        FlashcardDeck deck = accessResolver.requireOwner(deckId, ownerId);
        if (classId == null || !isOwnersClass(ownerId, classId)) {
            throw new AccessDeniedException(MSG_SHARE_CLASS_INVALID);
        }
        deck.shareTo(classId);
        deckRepository.save(deck);
    }

    /** Reverts a deck to PRIVATE; owner-only. */
    @Transactional
    public void unshare(Long deckId, Long ownerId) {
        FlashcardDeck deck = accessResolver.requireOwner(deckId, ownerId);
        deck.unshare();
        deckRepository.save(deck);
    }

    /** Classes the owner may share to (ACTIVE-enrolled or owns as lecturer). */
    @Transactional(readOnly = true)
    public List<ClassOption> shareableClasses(Long userId) {
        List<Long> classIds = activeClassIds(userId);
        classRepository.findAllByLecturerId(userId).forEach(c -> {
            if (!classIds.contains(c.getId())) classIds.add(c.getId());
        });
        List<ClassOption> options = new ArrayList<>();
        for (ClassEntity c : classRepository.findAllById(classIds)) {
            options.add(new ClassOption(c.getId(), c.getName()));
        }
        return options;
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private boolean isOwnersClass(Long ownerId, Long classId) {
        boolean enrolled = enrollmentRepository.findByUserIdAndClassId(ownerId, classId)
                .map(e -> Enrollment.STATUS_ACTIVE.equals(e.getStatus())).orElse(false);
        if (enrolled) return true;
        return classRepository.findById(classId)
                .map(c -> ownerId.equals(c.getLecturerId())).orElse(false);
    }

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
