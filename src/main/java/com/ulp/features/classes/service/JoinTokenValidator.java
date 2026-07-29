package com.ulp.features.classes.service;

import com.ulp.entities.ClassEntity;
import com.ulp.entities.ClassInviteCode;
import com.ulp.features.classes.repository.ClassInviteCodeRepository;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.features.classes.repository.EnrollmentRepository;
import com.ulp.features.classes.service.invites.InviteCodeValidationException;
import com.ulp.features.classes.service.invites.InviteRejectionReason;
import com.ulp.features.classes.service.invites.InviteTokenGenerator;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

/**
 * Validation gateway for the student-side join flow. Resolves an invite
 * token under the pessimistic write lock, enforces expiry/max-uses/class
 * status, and verifies the class still has capacity for a fresh enrollee.
 *
 * <p>Extracted from {@link JoinClassService} during the file-size refactor
 * so the orchestrating service stays focused on the enrollment state machine.
 * Plain helper instantiated by {@code JoinClassService} during construction
 * rather than a Spring bean — preserves the existing constructor surface
 * for unit tests that mock the four repositories + the activity writer.
 *
 * <p>Every rejection branch throws {@link InviteCodeValidationException}
 * carrying the precise {@link InviteRejectionReason}.
 */
final class JoinTokenValidator {

    /**
     * Class statuses a student may join. Anything outside this set — including
     * the review states {@code DRAFT} and {@code REJECTED} — falls through to
     * {@link InviteRejectionReason#CLASS_NOT_JOINABLE}. This whitelist is the
     * only place the join flow consults class status, which is what keeps an
     * unapproved class non-joinable without any edit to the join path.
     */
    private static final Set<String> JOINABLE_STATUSES =
            Set.of(ClassEntity.STATUS_UPCOMING, ClassEntity.STATUS_ACTIVE);

    private final ClassInviteCodeRepository inviteRepository;
    private final ClassRepository classRepository;
    private final EnrollmentRepository enrollmentRepository;

    JoinTokenValidator(ClassInviteCodeRepository inviteRepository,
                       ClassRepository classRepository,
                       EnrollmentRepository enrollmentRepository) {
        this.inviteRepository = inviteRepository;
        this.classRepository = classRepository;
        this.enrollmentRepository = enrollmentRepository;
    }

    /** Normalises the supplied user input; 6-char tokens are upper-cased (CODE namespace). */
    static String normalize(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new InviteCodeValidationException(InviteRejectionReason.INVALID);
        }
        return rawToken.length() == InviteTokenGenerator.CODE_LENGTH
                ? rawToken.toUpperCase()
                : rawToken;
    }

    /**
     * Resolves the token under a pessimistic write lock and validates its
     * expiry + max-uses fields. A null result from {@code findByCodeForUpdate}
     * is disambiguated against {@code findByCode} so the caller can surface
     * {@code DISABLED} vs {@code INVALID} for friendlier messaging.
     */
    ClassInviteCode resolveAndValidate(String normalizedToken) {
        Optional<ClassInviteCode> locked = inviteRepository.findByCodeForUpdate(normalizedToken);
        if (locked.isEmpty()) {
            // Distinguish unknown vs disabled for friendlier messaging.
            Optional<ClassInviteCode> any = inviteRepository.findByCode(normalizedToken);
            if (any.isPresent() && !any.get().isActive()) {
                throw new InviteCodeValidationException(InviteRejectionReason.DISABLED);
            }
            throw new InviteCodeValidationException(InviteRejectionReason.INVALID);
        }
        ClassInviteCode token = locked.get();

        if (token.getExpiresAt() != null
                && token.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new InviteCodeValidationException(InviteRejectionReason.EXPIRED);
        }
        if (token.getMaxUses() != null && token.getUseCount() >= token.getMaxUses()) {
            throw new InviteCodeValidationException(InviteRejectionReason.EXHAUSTED);
        }
        return token;
    }

    /**
     * Loads the class referenced by the token and verifies it is still joinable.
     * We use {@code findById} directly because {@code @SQLRestriction} filters
     * out soft-deleted rows, which is exactly what we want — but we need a
     * clear error code in that case.
     */
    ClassEntity loadJoinableClass(ClassInviteCode token) {
        ClassEntity clazz = classRepository.findById(token.getClassId()).orElse(null);
        if (clazz == null) {
            throw new InviteCodeValidationException(InviteRejectionReason.CLASS_NOT_JOINABLE);
        }
        if (!JOINABLE_STATUSES.contains(clazz.getStatus())) {
            throw new InviteCodeValidationException(InviteRejectionReason.CLASS_NOT_JOINABLE);
        }
        return clazz;
    }

    /**
     * Enforces {@code max_students} for the given class before inserting / reviving.
     *
     * <p>Correctness needs two things, because concurrent admissions write
     * *different* enrollment rows and so nothing orders them by default:
     * <ol>
     *   <li>a pessimistic write lock on the shared class row, so the two
     *       admissions run their check-then-admit sequences one after the
     *       other rather than interleaved; and</li>
     *   <li>a <em>locking</em> count. Under MySQL's default REPEATABLE READ a
     *       plain count is served from the snapshot taken at the transaction's
     *       first read — which predates the competing admission's commit — so
     *       the waiter would still see the pre-admission total and overshoot
     *       {@code max_students} despite having waited.</li>
     * </ol>
     *
     * <p>Must therefore be called inside an active transaction; every caller in
     * {@link JoinClassService} is {@code @Transactional}.
     */
    void enforceCapacity(ClassEntity clazz) {
        Integer cap = clazz.getMaxStudents();
        if (cap == null) return;
        // Lock before counting so a competing admission waits for our commit.
        classRepository.findByIdForUpdate(clazz.getId());
        long active = enrollmentRepository.countActiveByClassIdForUpdate(clazz.getId());
        if (active >= cap) {
            throw new InviteCodeValidationException(InviteRejectionReason.CLASS_FULL);
        }
    }
}
