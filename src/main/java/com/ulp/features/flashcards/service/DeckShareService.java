package com.ulp.features.flashcards.service;

import com.ulp.entities.ClassEntity;
import com.ulp.entities.Enrollment;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.features.classes.repository.EnrollmentRepository;
import com.ulp.features.flashcards.dto.FlashcardDtos.ClassOption;
import com.ulp.features.flashcards.entity.FlashcardDeck;
import com.ulp.features.flashcards.entity.FlashcardDeckClass;
import com.ulp.features.flashcards.repository.FlashcardDeckClassRepository;
import com.ulp.features.flashcards.support.DeckAccessResolver;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static com.ulp.common.IConstant.MSG_SHARE_CLASS_INVALID;

/**
 * Owns which classes a flashcard deck targets: the eligible target lookup,
 * set-replacement sharing and unshare-all. Notifying the students of newly
 * added classes is delegated to {@link DeckShareNotifier}.
 *
 * <p>Split out of {@code DeckService} (which keeps deck lifecycle and view
 * assembly) because sharing is a distinct operation with its own collaborators.
 * Authorization still funnels through the shared {@link DeckAccessResolver}.
 */
@Service
public class DeckShareService {

    private final FlashcardDeckClassRepository deckClassRepository;
    private final DeckAccessResolver accessResolver;
    private final EnrollmentRepository enrollmentRepository;
    private final ClassRepository classRepository;
    private final DeckShareNotifier notifier;

    public DeckShareService(FlashcardDeckClassRepository deckClassRepository,
                            DeckAccessResolver accessResolver,
                            EnrollmentRepository enrollmentRepository,
                            ClassRepository classRepository,
                            DeckShareNotifier notifier) {
        this.deckClassRepository = deckClassRepository;
        this.accessResolver = accessResolver;
        this.enrollmentRepository = enrollmentRepository;
        this.classRepository = classRepository;
        this.notifier = notifier;
    }

    /**
     * Replaces the deck's target classes with the submitted set; owner-only.
     * Classes newly added start being shared, classes dropped from the set stop
     * being shared, and classes in both are left untouched. A null or empty
     * submission therefore clears every target.
     *
     * <p>Validation is all-or-nothing: if any submitted class is not one the
     * owner may share to, nothing is written at all.
     *
     * @throws AccessDeniedException if the caller is not the owner, or any
     *                               submitted class is not an eligible target
     */
    @Transactional
    public void share(Long deckId, Long ownerId, List<Long> classIds) {
        FlashcardDeck deck = accessResolver.requireOwner(deckId, ownerId);
        Set<Long> submitted = distinctNonNull(classIds);

        // Validate every id before writing anything — a partially applied set
        // would leave the owner's form disagreeing with reality.
        for (Long classId : submitted) {
            if (!isOwnersClass(ownerId, classId)) {
                throw new AccessDeniedException(MSG_SHARE_CLASS_INVALID);
            }
        }

        Set<Long> current = currentTargets(deckId);
        List<Long> toAdd = difference(submitted, current);
        List<Long> toRemove = difference(current, submitted);

        if (!toRemove.isEmpty()) {
            deckClassRepository.deleteByDeckIdAndClassIdIn(deckId, toRemove);
        }
        for (Long classId : toAdd) {
            deckClassRepository.save(new FlashcardDeckClass(deckId, classId));
        }

        // Only newly added classes are notified, so re-saving cannot spam.
        notifier.notifyNewlyShared(deck, ownerId, toAdd);
    }

    /**
     * Adds one class target; owner-only, idempotent. Notifies the class only
     * when the row is newly created, so repeated calls cannot spam students.
     *
     * @throws AccessDeniedException if the caller is not the owner, or the class
     *                               is not an eligible target
     */
    @Transactional
    public void shareToClass(Long deckId, Long ownerId, Long classId) {
        FlashcardDeck deck = accessResolver.requireOwner(deckId, ownerId);
        if (!isOwnersClass(ownerId, classId)) {
            throw new AccessDeniedException(MSG_SHARE_CLASS_INVALID);
        }
        // Already a target: stay silent rather than writing a duplicate row and
        // re-notifying the class.
        if (currentTargets(deckId).contains(classId)) {
            return;
        }
        deckClassRepository.save(new FlashcardDeckClass(deckId, classId));
        notifier.notifyNewlyShared(deck, ownerId, List.of(classId));
    }

    /**
     * Removes one class target; owner-only, idempotent. Emits no notification,
     * matching {@link #unshareAll}.
     *
     * @throws AccessDeniedException if the caller is not the deck owner
     */
    @Transactional
    public void unshareFromClass(Long deckId, Long ownerId, Long classId) {
        accessResolver.requireOwner(deckId, ownerId);
        deckClassRepository.deleteByDeckIdAndClassIdIn(deckId, List.of(classId));
    }

    /**
     * Removes every target class from the deck; owner-only. Succeeds silently
     * when the deck already targets nothing. Emits no notification.
     *
     * @throws AccessDeniedException if the caller is not the deck owner
     */
    @Transactional
    public void unshareAll(Long deckId, Long ownerId) {
        accessResolver.requireOwner(deckId, ownerId);
        deckClassRepository.deleteByDeckId(deckId);
    }

    /** The class ids a deck currently targets. */
    @Transactional(readOnly = true)
    public Set<Long> currentTargets(Long deckId) {
        Set<Long> ids = new LinkedHashSet<>();
        for (FlashcardDeckClass row : deckClassRepository.findByDeckId(deckId)) {
            ids.add(row.getClassId());
        }
        return ids;
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

    /** ACTIVE-enrolled in the class, or its assigned lecturer. */
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

    /** Null-tolerant, order-preserving de-duplication of a submitted id list. */
    private static Set<Long> distinctNonNull(List<Long> ids) {
        Set<Long> out = new LinkedHashSet<>();
        if (ids == null) return out;
        for (Long id : ids) {
            if (id != null) out.add(id);
        }
        return out;
    }

    private static List<Long> difference(Set<Long> from, Set<Long> remove) {
        List<Long> out = new ArrayList<>();
        for (Long id : from) {
            if (!remove.contains(id)) out.add(id);
        }
        return out;
    }
}
