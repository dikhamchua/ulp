package com.ulp.classes.service;

import com.ulp.auth.entity.User;
import com.ulp.auth.repository.UserRepository;
import com.ulp.classes.entity.ClassActivity;
import com.ulp.classes.entity.ClassEntity;
import com.ulp.classes.entity.ClassInviteCode;
import com.ulp.classes.entity.Enrollment;
import com.ulp.classes.repository.ClassInviteCodeRepository;
import com.ulp.classes.repository.ClassRepository;
import com.ulp.classes.repository.EnrollmentRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
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
 */
@Service
public class JoinClassService {

    private final ClassInviteCodeRepository inviteRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final ClassRepository classRepository;
    private final ClassActivityWriter activityWriter;
    private final UserRepository userRepository;

    public JoinClassService(ClassInviteCodeRepository inviteRepository,
                            EnrollmentRepository enrollmentRepository,
                            ClassRepository classRepository,
                            ClassActivityWriter activityWriter,
                            UserRepository userRepository) {
        this.inviteRepository = inviteRepository;
        this.enrollmentRepository = enrollmentRepository;
        this.classRepository = classRepository;
        this.activityWriter = activityWriter;
        this.userRepository = userRepository;
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
        if (rawToken == null || rawToken.isBlank()) {
            throw new InviteCodeValidationException(InviteRejectionReason.INVALID);
        }
        String normalized = rawToken.length() == InviteTokenGenerator.CODE_LENGTH
                ? rawToken.toUpperCase()
                : rawToken;

        // 1. Resolve token under a pessimistic lock. A NULL result
        //    means either there is no row at all OR the row is
        //    disabled. The unlocked findByCode lookup disambiguates.
        Optional<ClassInviteCode> locked = inviteRepository.findByCodeForUpdate(normalized);
        if (locked.isEmpty()) {
            // Distinguish unknown vs disabled for friendlier messaging.
            Optional<ClassInviteCode> any = inviteRepository.findByCode(normalized);
            if (any.isPresent() && !any.get().isActive()) {
                throw new InviteCodeValidationException(InviteRejectionReason.DISABLED);
            }
            throw new InviteCodeValidationException(InviteRejectionReason.INVALID);
        }
        ClassInviteCode token = locked.get();

        // 2. Expiry + max-uses validation.
        if (token.getExpiresAt() != null
                && token.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new InviteCodeValidationException(InviteRejectionReason.EXPIRED);
        }
        if (token.getMaxUses() != null && token.getUseCount() >= token.getMaxUses()) {
            throw new InviteCodeValidationException(InviteRejectionReason.EXHAUSTED);
        }

        // 3. Class status / soft-delete gating. We use findById
        //    directly because @SQLRestriction filters out soft-
        //    deleted rows, which is exactly what we want — but we
        //    need a clear error code in that case.
        ClassEntity clazz = classRepository.findById(token.getClassId()).orElse(null);
        if (clazz == null) {
            // Class soft-deleted: the token still exists but the
            // class is gone from our view.
            throw new InviteCodeValidationException(InviteRejectionReason.CLASS_NOT_JOINABLE);
        }
        if (!"UPCOMING".equals(clazz.getStatus()) && !"ACTIVE".equals(clazz.getStatus())) {
            throw new InviteCodeValidationException(InviteRejectionReason.CLASS_NOT_JOINABLE);
        }

        // 4. Existing enrollment handling — duplicates / revival.
        Optional<Enrollment> existing =
                enrollmentRepository.findByUserIdAndClassId(userId, token.getClassId());
        if (existing.isPresent()) {
            Enrollment row = existing.get();
            switch (row.getStatus()) {
                case Enrollment.STATUS_ACTIVE -> {
                    return new AlreadyJoined(clazz);
                }
                case Enrollment.STATUS_COMPLETED -> throw new InviteCodeValidationException(
                        InviteRejectionReason.ALREADY_COMPLETED);
                case Enrollment.STATUS_REMOVED -> {
                    // 5a. Capacity check applies for revival too.
                    enforceCapacity(clazz);
                    row.reactivateVia(joinedVia(token.getType()), token.getId());
                    enrollmentRepository.save(row);
                    token.incrementUseCount();
                    inviteRepository.save(token);
                    writeJoinAudit(clazz, userId, token);
                    return new Success(clazz);
                }
                default -> throw new IllegalStateException(
                        "Trạng thái enrollment không hợp lệ: " + row.getStatus());
            }
        }

        // 5. Fresh enrollment path.
        enforceCapacity(clazz);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("Người dùng không tồn tại"));
        Enrollment fresh = Enrollment.createFor(user, clazz.getId(),
                joinedVia(token.getType()), token.getId());
        enrollmentRepository.save(fresh);

        token.incrementUseCount();
        inviteRepository.save(token);

        writeJoinAudit(clazz, userId, token);
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
            // From the caller's perspective this is the same as "not
            // a member" — surface as 404 so we don't leak the
            // existence of the previous enrollment.
            throw new EntityNotFoundException("Không tìm thấy enrollment");
        }
        if (Enrollment.STATUS_COMPLETED.equals(row.getStatus())) {
            throw new IllegalStateException("Không thể rời lớp đã hoàn thành");
        }

        ClassEntity clazz = classRepository.findById(classId)
                .orElseThrow(() -> new EntityNotFoundException("Lớp không tồn tại"));

        row.markRemoved();
        enrollmentRepository.save(row);

        activityWriter.write(
                clazz.getId(),
                ClassActivity.TYPE_MEMBER_LEFT,
                "Học viên rời lớp " + clazz.getName(),
                Map.of("user_id", userId),
                userId
        );
        return clazz;
    }

    // ──────────────────── internal ────────────────────

    private void enforceCapacity(ClassEntity clazz) {
        Integer cap = clazz.getMaxStudents();
        if (cap == null) return;
        long active = enrollmentRepository.countActiveByClassId(clazz.getId());
        if (active >= cap) {
            throw new InviteCodeValidationException(InviteRejectionReason.CLASS_FULL);
        }
    }

    private void writeJoinAudit(ClassEntity clazz, Long userId, ClassInviteCode token) {
        activityWriter.write(
                clazz.getId(),
                ClassActivity.TYPE_MEMBER_JOINED,
                "Học viên tham gia lớp " + clazz.getName(),
                Map.of("user_id", userId, "joined_via", joinedVia(token.getType()).name()),
                userId
        );
    }

    /**
     * Maps the invite-token type to the {@link Enrollment.JoinedVia} channel.
     * A 6-character code maps to {@link Enrollment.JoinedVia#CODE}; anything else
     * (currently only the 32-character link type) maps to
     * {@link Enrollment.JoinedVia#LINK}.
     */
    private Enrollment.JoinedVia joinedVia(String tokenType) {
        return ClassInviteCode.TYPE_CODE.equals(tokenType)
                ? Enrollment.JoinedVia.CODE
                : Enrollment.JoinedVia.LINK;
    }
}
