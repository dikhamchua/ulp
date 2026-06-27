package com.ulp.features.classes.service;

import com.ulp.entities.ClassEntity;
import com.ulp.entities.ClassInviteCode;
import com.ulp.entities.Enrollment;
import com.ulp.entities.User;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.classes.repository.ClassInviteCodeRepository;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.features.classes.repository.EnrollmentRepository;
import com.ulp.features.classes.service.invites.InviteCodeValidationException;
import com.ulp.features.classes.service.invites.InviteRejectionReason;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Service for the student-side join/leave flow.
 *
 * <p>The {@link #join} method resolves an invite token under a
 * pessimistic write lock so two concurrent attempts on a token
 * with one remaining use produce exactly one success + one
 * rejection (see {@code class-invite-codes} spec, "Concurrent join
 * attempts respect max_uses").
 *
 * <p>Re-join semantics: an existing enrollment row is reused —
 * {@code REMOVED} → flipped back to {@code ACTIVE}, {@code ACTIVE}
 * → short-circuit info, {@code COMPLETED} → reject. INSERTing a
 * second row is impossible due to the {@code idx_enroll_user_class}
 * unique index.
 *
 * <p>Validation logic (token resolution, expiry, class status,
 * capacity) and audit-row construction are delegated to the
 * package-private {@link JoinTokenValidator} and {@link JoinAuditWriter}
 * helpers so this class stays focused on the enrollment state machine.
 */
@Service
public class JoinClassService {

    private final ClassInviteCodeRepository inviteRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final ClassRepository classRepository;
    private final UserRepository userRepository;
    private final JoinTokenValidator validator;
    private final JoinAuditWriter auditWriter;

    public JoinClassService(ClassInviteCodeRepository inviteRepository,
                            EnrollmentRepository enrollmentRepository,
                            ClassRepository classRepository,
                            ClassActivityWriter activityWriter,
                            UserRepository userRepository) {
        this.inviteRepository = inviteRepository;
        this.enrollmentRepository = enrollmentRepository;
        this.classRepository = classRepository;
        this.userRepository = userRepository;
        this.validator = new JoinTokenValidator(inviteRepository, classRepository,
                enrollmentRepository);
        this.auditWriter = new JoinAuditWriter(activityWriter);
    }

    /** Outcome of a successful join attempt, returned to the controller. */
    public sealed interface JoinResult permits Success, AlreadyJoined {}

    /** First-time join or revive-after-REMOVED succeeded. */
    public record Success(ClassEntity clazz) implements JoinResult {}

    /** Caller was already ACTIVE — no-op result with a friendly toast. */
    public record AlreadyJoined(ClassEntity clazz) implements JoinResult {}

    /**
     * Validates an invite token and either inserts a new enrollment
     * row, revives a REMOVED one, or short-circuits if the caller is
     * already ACTIVE.
     *
     * <p>The token is normalized inside this method: a 6-char input
     * is upper-cased (CODE namespace), a 32-char input is queried
     * as-is (LINK namespace).
     *
     * <p>The pessimistic write lock acquired by
     * {@link ClassInviteCodeRepository#findByCodeForUpdate} is held
     * through the {@code use_count++} update AND the
     * {@code enrollments} write to serialize concurrent attempts.
     *
     * @param rawToken token text the user supplied
     * @param userId   caller's user id
     * @return {@link Success} or {@link AlreadyJoined}
     * @throws InviteCodeValidationException when validation fails;
     *         {@link InviteCodeValidationException#getReason()} carries
     *         the precise {@link InviteRejectionReason}
     */
    @Transactional
    public JoinResult join(String rawToken, Long userId) {
        String normalized = JoinTokenValidator.normalize(rawToken);
        ClassInviteCode token = validator.resolveAndValidate(normalized);
        ClassEntity clazz = validator.loadJoinableClass(token);

        // Existing enrollment handling — duplicates / revival.
        Optional<Enrollment> existing =
                enrollmentRepository.findByUserIdAndClassId(userId, token.getClassId());
        if (existing.isPresent()) {
            return handleExisting(existing.get(), clazz, token, userId);
        }

        // Fresh enrollment path.
        validator.enforceCapacity(clazz);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("Người dùng không tồn tại"));
        Enrollment fresh = Enrollment.createFor(user, clazz.getId(),
                JoinAuditWriter.joinedVia(token.getType()), token.getId());
        enrollmentRepository.save(fresh);

        token.incrementUseCount();
        inviteRepository.save(token);

        auditWriter.writeJoin(clazz, userId, token);
        return new Success(clazz);
    }

    /**
     * Soft-removes the caller's enrollment in the given class.
     * Status transitions to {@code REMOVED}; the row is retained.
     *
     * @param classId target class
     * @param userId  caller's user id
     * @return the class entity (for the success toast)
     * @throws EntityNotFoundException when no enrollment exists or
     *         the existing row is already {@code REMOVED}
     * @throws IllegalStateException   when the row is {@code COMPLETED}
     *         (cannot leave a finished class)
     */
    @Transactional
    public ClassEntity leave(Long classId, Long userId) {
        Enrollment row = enrollmentRepository.findByUserIdAndClassId(userId, classId)
                .orElseThrow(() -> new EntityNotFoundException("Không tìm thấy enrollment"));
        if (Enrollment.STATUS_REMOVED.equals(row.getStatus())) {
            // From the caller's perspective this is the same as "not a
            // member" — surface as 404 so we don't leak the existence of
            // the previous enrollment.
            throw new EntityNotFoundException("Không tìm thấy enrollment");
        }
        if (Enrollment.STATUS_COMPLETED.equals(row.getStatus())) {
            throw new IllegalStateException("Không thể rời lớp đã hoàn thành");
        }

        ClassEntity clazz = classRepository.findById(classId)
                .orElseThrow(() -> new EntityNotFoundException("Lớp không tồn tại"));

        row.markRemoved();
        enrollmentRepository.save(row);

        auditWriter.writeLeave(clazz, userId);
        return clazz;
    }

    // ──────────────────── internal ────────────────────

    /** Dispatches existing-enrollment branches: short-circuit ACTIVE, revive REMOVED, reject COMPLETED. */
    private JoinResult handleExisting(Enrollment row, ClassEntity clazz,
                                      ClassInviteCode token, Long userId) {
        switch (row.getStatus()) {
            case Enrollment.STATUS_ACTIVE -> {
                return new AlreadyJoined(clazz);
            }
            case Enrollment.STATUS_COMPLETED -> throw new InviteCodeValidationException(
                    InviteRejectionReason.ALREADY_COMPLETED);
            case Enrollment.STATUS_REMOVED -> {
                // Capacity check applies for revival too.
                validator.enforceCapacity(clazz);
                row.reactivateVia(JoinAuditWriter.joinedVia(token.getType()), token.getId());
                enrollmentRepository.save(row);
                token.incrementUseCount();
                inviteRepository.save(token);
                auditWriter.writeJoin(clazz, userId, token);
                return new Success(clazz);
            }
            default -> throw new IllegalStateException(
                    "Trạng thái enrollment không hợp lệ: " + row.getStatus());
        }
    }
}
