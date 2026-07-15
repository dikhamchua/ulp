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
import com.ulp.features.notifications.entity.NotificationType;
import com.ulp.features.notifications.service.NotificationService;
import com.ulp.security.Role;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Service for the student-side join/leave flow and lecturer approve/reject.
 *
 * <p>CODE/LINK self-join creates or re-opens a {@code PENDING} enrollment;
 * the class owner must approve (→ ACTIVE, invite {@code use_count++}) or
 * reject (→ REJECTED). Lecturer-initiated IMPORT/MANUAL paths remain ACTIVE
 * via other services.
 *
 * <p>Validation (token, capacity) is delegated to {@link JoinTokenValidator};
 * audit rows to {@link JoinAuditWriter}.
 */
@Service
public class JoinClassService {

    private final ClassInviteCodeRepository inviteRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final ClassRepository classRepository;
    private final UserRepository userRepository;
    private final JoinTokenValidator validator;
    private final JoinAuditWriter auditWriter;
    private final NotificationService notificationService;
    private final ClassesService classesService;

    public JoinClassService(ClassInviteCodeRepository inviteRepository,
                            EnrollmentRepository enrollmentRepository,
                            ClassRepository classRepository,
                            ClassActivityWriter activityWriter,
                            UserRepository userRepository,
                            NotificationService notificationService,
                            ClassesService classesService) {
        this.inviteRepository = inviteRepository;
        this.enrollmentRepository = enrollmentRepository;
        this.classRepository = classRepository;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
        this.classesService = classesService;
        this.validator = new JoinTokenValidator(inviteRepository, classRepository,
                enrollmentRepository);
        this.auditWriter = new JoinAuditWriter(activityWriter);
    }

    /** Outcome of a join attempt returned to the controller. */
    public sealed interface JoinResult permits Success, AlreadyJoined, PendingRequested {}

    /** Immediate ACTIVE admission (not used by CODE/LINK after approval flow). */
    public record Success(ClassEntity clazz) implements JoinResult {}

    /** Caller was already ACTIVE — no-op with a friendly toast. */
    public record AlreadyJoined(ClassEntity clazz) implements JoinResult {}

    /** PENDING request created or already awaiting approval. */
    public record PendingRequested(ClassEntity clazz, boolean alreadyPending)
            implements JoinResult {}

    /**
     * Validates an invite token and creates/re-opens a PENDING enrollment.
     * Does not increment invite {@code use_count} (that happens on approve).
     */
    @Transactional
    public JoinResult join(String rawToken, Long userId) {
        String normalized = JoinTokenValidator.normalize(rawToken);
        ClassInviteCode token = validator.resolveAndValidate(normalized);
        ClassEntity clazz = validator.loadJoinableClass(token);

        // Class owner must not redeem their own invite CODE/LINK.
        if (clazz.getLecturerId().equals(userId)) {
            throw new InviteCodeValidationException(InviteRejectionReason.OWN_CLASS);
        }

        Optional<Enrollment> existing =
                enrollmentRepository.findByUserIdAndClassId(userId, token.getClassId());
        if (existing.isPresent()) {
            return handleExisting(existing.get(), clazz, token, userId);
        }

        validator.enforceCapacity(clazz);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("Người dùng không tồn tại"));
        Enrollment fresh = Enrollment.createPending(user, clazz.getId(),
                JoinAuditWriter.joinedVia(token.getType()), token.getId());
        enrollmentRepository.save(fresh);

        auditWriter.writeJoin(clazz, userId, token);
        emitJoinRequestToOwner(clazz, user);
        return new PendingRequested(clazz, false);
    }

    /**
     * Soft-removes the caller's enrollment. PENDING/REJECTED rows are also
     * cleared to REMOVED so the student can re-request cleanly if needed.
     */
    @Transactional
    public ClassEntity leave(Long classId, Long userId) {
        Enrollment row = enrollmentRepository.findByUserIdAndClassId(userId, classId)
                .orElseThrow(() -> new EntityNotFoundException("Không tìm thấy enrollment"));
        if (Enrollment.STATUS_REMOVED.equals(row.getStatus())) {
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

    /**
     * Class owner approves a PENDING enrollment → ACTIVE, increments invite
     * use_count when present, notifies the student.
     *
     * @throws AccessDeniedException when caller is not the class owner (and not HEAD/ADMIN)
     * @throws IllegalStateException when not PENDING, class full, or invite max_uses exhausted
     */
    @Transactional
    public ClassEntity approve(Long classId, Long studentUserId, Long actorId, Role actorRole) {
        ClassEntity clazz = requireOwner(classId, actorId, actorRole);
        Enrollment row = enrollmentRepository.findByUserIdAndClassId(studentUserId, classId)
                .orElseThrow(() -> new EntityNotFoundException("Không tìm thấy yêu cầu tham gia"));
        if (!Enrollment.STATUS_PENDING.equals(row.getStatus())) {
            throw new IllegalStateException("Yêu cầu không ở trạng thái chờ duyệt");
        }

        validator.enforceCapacity(clazz);

        // Increment use_count under lock when the original invite is still on the row.
        if (row.getInviteCodeId() != null) {
            ClassInviteCode invite = inviteRepository.findByIdForUpdate(row.getInviteCodeId())
                    .orElse(null);
            if (invite != null) {
                if (invite.getMaxUses() != null && invite.getUseCount() >= invite.getMaxUses()) {
                    throw new IllegalStateException("Mã mời đã đạt giới hạn lượt dùng");
                }
                invite.incrementUseCount();
                inviteRepository.save(invite);
            }
        }

        row.activateFromPending();
        enrollmentRepository.save(row);

        emitApprovedNotifications(clazz, studentUserId);
        return clazz;
    }

    /**
     * Class owner rejects a PENDING enrollment → REJECTED. No use_count change.
     */
    @Transactional
    public ClassEntity reject(Long classId, Long studentUserId, Long actorId, Role actorRole) {
        ClassEntity clazz = requireOwner(classId, actorId, actorRole);
        Enrollment row = enrollmentRepository.findByUserIdAndClassId(studentUserId, classId)
                .orElseThrow(() -> new EntityNotFoundException("Không tìm thấy yêu cầu tham gia"));
        if (!Enrollment.STATUS_PENDING.equals(row.getStatus())) {
            throw new IllegalStateException("Yêu cầu không ở trạng thái chờ duyệt");
        }

        row.markRejected();
        enrollmentRepository.save(row);

        emitRejectedNotification(clazz, studentUserId);
        return clazz;
    }

    // ──────────────────── internal ────────────────────

    /** Owner-only gate: LECTURER must own the class; HEAD/ADMIN pass via getEditable. */
    private ClassEntity requireOwner(Long classId, Long actorId, Role actorRole) {
        // getEditable enforces LECTURER ownership and HEAD/ADMIN privilege.
        ClassEntity clazz = classesService.getEditable(classId, actorId, actorRole);
        // Spec: approve/reject is class owner only — deny HEAD/ADMIN non-owners.
        if (!clazz.getLecturerId().equals(actorId)) {
            throw new AccessDeniedException("Chỉ giảng viên chủ lớp mới được duyệt yêu cầu");
        }
        return clazz;
    }

    private JoinResult handleExisting(Enrollment row, ClassEntity clazz,
                                      ClassInviteCode token, Long userId) {
        return switch (row.getStatus()) {
            case Enrollment.STATUS_ACTIVE -> new AlreadyJoined(clazz);
            case Enrollment.STATUS_COMPLETED -> throw new InviteCodeValidationException(
                    InviteRejectionReason.ALREADY_COMPLETED);
            case Enrollment.STATUS_PENDING -> new PendingRequested(clazz, true);
            case Enrollment.STATUS_REJECTED, Enrollment.STATUS_REMOVED -> {
                validator.enforceCapacity(clazz);
                row.markPending(JoinAuditWriter.joinedVia(token.getType()), token.getId());
                enrollmentRepository.save(row);
                auditWriter.writeJoin(clazz, userId, token);
                User student = row.getUser();
                if (student == null) {
                    student = userRepository.findById(userId).orElse(null);
                }
                if (student != null) {
                    emitJoinRequestToOwner(clazz, student);
                }
                yield new PendingRequested(clazz, false);
            }
            default -> throw new IllegalStateException(
                    "Trạng thái enrollment không hợp lệ: " + row.getStatus());
        };
    }

    private void emitJoinRequestToOwner(ClassEntity clazz, User student) {
        try {
            String name = student.getFullName() != null ? student.getFullName() : student.getEmail();
            notificationService.create(
                    clazz.getLecturerId(),
                    "Yêu cầu tham gia lớp",
                    name + " đã gửi yêu cầu tham gia lớp \"" + clazz.getName() + "\".",
                    NotificationType.JOIN_REQUEST,
                    NotificationType.REF_CLASS,
                    clazz.getId()
            );
        } catch (Exception ignored) {
            // Notification failure must not roll back the enrollment.
        }
    }

    private void emitApprovedNotifications(ClassEntity clazz, Long studentUserId) {
        try {
            notificationService.create(
                    studentUserId,
                    "Yêu cầu tham gia đã được duyệt",
                    "Bạn đã được duyệt vào lớp \"" + clazz.getName() + "\".",
                    NotificationType.JOIN_APPROVED,
                    NotificationType.REF_CLASS,
                    clazz.getId()
            );
        } catch (Exception ignored) {
            // best-effort
        }
        emitEnrolledNotification(clazz, studentUserId);
    }

    private void emitRejectedNotification(ClassEntity clazz, Long studentUserId) {
        try {
            notificationService.create(
                    studentUserId,
                    "Yêu cầu tham gia bị từ chối",
                    "Yêu cầu tham gia lớp \"" + clazz.getName() + "\" đã bị từ chối.",
                    NotificationType.JOIN_REJECTED,
                    NotificationType.REF_CLASS,
                    clazz.getId()
            );
        } catch (Exception ignored) {
            // best-effort
        }
    }

    private void emitEnrolledNotification(ClassEntity clazz, Long userId) {
        try {
            notificationService.create(
                    userId,
                    "Tham gia lớp thành công",
                    "Bạn đã tham gia lớp \"" + clazz.getName() + "\" thành công.",
                    NotificationType.CLASS_ENROLLED,
                    NotificationType.REF_CLASS,
                    clazz.getId()
            );
        } catch (Exception ignored) {
            // Notification failure must not roll back the enrollment.
        }
    }
}
