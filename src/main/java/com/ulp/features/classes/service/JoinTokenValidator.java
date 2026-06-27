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
        if (!"UPCOMING".equals(clazz.getStatus()) && !"ACTIVE".equals(clazz.getStatus())) {
            throw new InviteCodeValidationException(InviteRejectionReason.CLASS_NOT_JOINABLE);
        }
        return clazz;
    }

    /** Enforces {@code max_students} for the given class before inserting / reviving. */
    void enforceCapacity(ClassEntity clazz) {
        Integer cap = clazz.getMaxStudents();
        if (cap == null) return;
        long active = enrollmentRepository.countActiveByClassId(clazz.getId());
        if (active >= cap) {
            throw new InviteCodeValidationException(InviteRejectionReason.CLASS_FULL);
        }
    }
}
