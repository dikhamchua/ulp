package com.ulp.admin.users.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ulp.admin.users.dto.ActivityRow;
import com.ulp.admin.users.dto.AdminUsersDtos.CreateUserForm;
import com.ulp.admin.users.dto.AdminUsersDtos.EditUserForm;
import com.ulp.admin.users.dto.AdminUsersDtos.UserFilter;
import com.ulp.admin.users.dto.AdminUsersDtos.UserRow;
import com.ulp.admin.users.entity.UserActivity;
import com.ulp.admin.users.repository.UserActivityRepository;
import com.ulp.auth.Role;
import com.ulp.auth.entity.User;
import com.ulp.auth.entity.UserFactory;
import com.ulp.auth.repository.UserRepository;
import com.ulp.classes.entity.ClassEntity;
import com.ulp.classes.repository.ClassRepository;
import com.ulp.shared.util.StringUtils;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Business service backing the {@code /admin/users} screen.
 *
 * <p>Authorization rules (enforced here, NOT in the controller):
 * <ul>
 *   <li>The acting admin cannot deactivate / lock / soft-delete / demote /
 *       reset-password their own account (handled via
 *       {@link AdminUsersGuard#requireNotSelf}).</li>
 *   <li>The last active admin (the only user with
 *       {@code role = ADMIN AND is_active = 1 AND is_deleted = 0}) cannot
 *       be deactivated, locked, deleted, or demoted (handled via
 *       {@link AdminUsersGuard#requireNotLastActiveAdmin} and
 *       {@link AdminUsersGuard#requireRoleNotDemotingLastAdmin}).</li>
 * </ul>
 *
 * <p>Every mutation method is {@code @Transactional} and writes exactly one
 * row in {@code user_activities} per business mutation. If the audit insert
 * fails, the transaction rolls back. Mutations on {@link User} go through
 * the entity's business methods ({@code setActive}, {@code lock},
 * {@code unlock}, {@code softDelete}, {@code restore},
 * {@code updateAdminFields}) — direct setter calls are avoided to keep the
 * entity's surface narrow.
 */
@Service
public class AdminUsersService {

    private static final Logger log = LoggerFactory.getLogger(AdminUsersService.class);

    /** Minimum allowed page size when listing users (clamped server-side). */
    public static final int MIN_PAGE_SIZE = 10;
    /** Maximum allowed page size when listing users (clamped server-side). */
    public static final int MAX_PAGE_SIZE = 100;
    /** Default page size when the request omits {@code size}. */
    public static final int DEFAULT_PAGE_SIZE = 20;

    private final UserRepository userRepository;
    private final UserActivityRepository activityRepository;
    private final PasswordEncoder passwordEncoder;
    private final AdminUsersGuard guard;
    private final ObjectMapper objectMapper;
    private final ClassRepository classRepository;

    @PersistenceContext
    private EntityManager entityManager;

    public AdminUsersService(UserRepository userRepository,
                             UserActivityRepository activityRepository,
                             PasswordEncoder passwordEncoder,
                             AdminUsersGuard guard,
                             ObjectMapper objectMapper,
                             ClassRepository classRepository) {
        this.userRepository = userRepository;
        this.activityRepository = activityRepository;
        this.passwordEncoder = passwordEncoder;
        this.guard = guard;
        this.objectMapper = objectMapper;
        this.classRepository = classRepository;
    }

    // ── Read API ──────────────────────────────────────────────────

    /**
     * Returns the paged list of users matching the supplied filter. Page size
     * is clamped to {@code [MIN_PAGE_SIZE, MAX_PAGE_SIZE]} and defaults to
     * {@code DEFAULT_PAGE_SIZE}. Sort is resolved per the request's
     * {@code sort} parameter; the special key {@code rolePriority,*} routes
     * to a dedicated native query that sorts ADMIN → HEAD → LECTURER →
     * STUDENT via a CASE expression. Unrecognised keys fall back to
     * {@code createdAt,desc}.
     */
    @Transactional(readOnly = true)
    public Page<UserRow> list(UserFilter filter, Pageable requested) {
        int size = clampPageSize(requested.getPageSize());
        int page = Math.max(0, requested.getPageNumber());

        String q       = StringUtils.blankToNull(filter.q());
        String role    = StringUtils.blankToNull(filter.role());
        String status  = StringUtils.blankToNull(filter.status());
        String sortKey = StringUtils.blankToNull(filter.sort());

        if (sortKey != null && sortKey.startsWith("rolePriority")) {
            // Pageable carries no meaningful Sort here — the native SQL hard-codes
            // ORDER BY CASE u.role. Use an unsorted Pageable to avoid Spring Data
            // appending a conflicting ORDER BY clause.
            Pageable plain = PageRequest.of(page, size);
            return sortKey.endsWith(",desc")
                    ? userRepository.searchUsersForAdminByRolePriorityDesc(q, role, status, plain)
                    : userRepository.searchUsersForAdminByRolePriorityAsc(q, role, status, plain);
        }

        Pageable pageable = PageRequest.of(page, size, resolveSort(sortKey));
        return userRepository.searchUsersForAdmin(q, role, status, pageable);
    }

    /**
     * Loads a user for the Edit / Restore flow, including soft-deleted rows.
     * Throws {@link EntityNotFoundException} if the id does not exist.
     */
    @Transactional(readOnly = true)
    public User getEditable(Long id) {
        return userRepository.findByIdIncludingDeleted(id)
                .orElseThrow(() -> new EntityNotFoundException("Người dùng không tồn tại"));
    }

    /**
     * Paginated audit history for a single user, consumed by the user-detail
     * page's "Lịch sử cập nhật" tab. Delegates to
     * {@link UserActivityRepository#findActivitiesForTargetUser} which already
     * joins against the actor's email to avoid N+1 lookups across the page.
     *
     * @param targetUserId the user the audit rows are about
     * @param pageable     paging directives (sort is ignored — the repository
     *                     query hard-codes {@code ORDER BY createdAt DESC})
     * @return one page of audit rows, newest first
     */
    @Transactional(readOnly = true)
    public Page<ActivityRow> listActivities(Long targetUserId, Pageable pageable) {
        return activityRepository.findActivitiesForTargetUser(targetUserId, pageable);
    }

    /**
     * Returns the {@code users.created_at} timestamp for the given user.
     *
     * <p>The {@link User} entity does not map {@code created_at}; this helper
     * reads it directly via native SQL so the detail-page header can display
     * "Tạo lúc" without modifying out-of-scope entity code. Returns
     * {@code null} when the row does not exist or the column is null.
     *
     * @param userId target user id
     * @return creation timestamp or {@code null}
     */
    @Transactional(readOnly = true)
    public LocalDateTime getCreatedAt(Long userId) {
        Object raw;
        try {
            raw = entityManager
                    .createNativeQuery("SELECT created_at FROM users WHERE id = ?1")
                    .setParameter(1, userId)
                    .getSingleResult();
        } catch (jakarta.persistence.NoResultException ex) {
            return null;
        }
        if (raw == null) return null;
        if (raw instanceof LocalDateTime ldt) return ldt;
        if (raw instanceof java.sql.Timestamp ts) return ts.toLocalDateTime();
        return null;
    }

    /**
     * Persists a new user, BCrypt-encoding the chosen temporary password.
     * Email is normalised before the uniqueness check. Throws
     * {@link EmailAlreadyUsedException} if the email already exists.
     */
    @Transactional
    public User create(CreateUserForm form, Long actingUserId) {
        String email = normalizeEmail(form.email());
        userRepository.findFirstByEmailIgnoreCase(email).ifPresent(u -> {
            throw new EmailAlreadyUsedException(email);
        });

        User u = UserFactory.newAdminCreated(
                email,
                passwordEncoder.encode(form.password()),
                form.fullName(),
                form.role(),
                form.emailVerified(),
                StringUtils.blankToNull(form.phone()),
                StringUtils.blankToNull(form.bio())
        );
        User saved = userRepository.save(u);

        writeActivity(saved.getId(), UserActivity.TYPE_CREATED,
                "Tạo tài khoản " + saved.getEmail(), null, actingUserId);
        return saved;
    }

    /**
     * Updates the admin-editable fields of a user. Returns a list of warning
     * messages: empty when no warning applies, otherwise a single message
     * describing the demote-impact on owned classes.
     */
    @Transactional
    public List<String> update(Long id, EditUserForm form, Long actingUserId) {
        User target = userRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new EntityNotFoundException("Người dùng không tồn tại"));

        // Self-role-change is forbidden.
        if (actingUserId != null && actingUserId.equals(target.getId())
                && form.role() != target.getRole()) {
            guard.requireNotSelf(actingUserId, target.getId(), "thay đổi vai trò");
        }

        // Last-admin demote guard runs only when the role is actually changing
        // away from ADMIN. It is safe to call even when role is unchanged; the
        // guard's own short-circuit handles it.
        guard.requireRoleNotDemotingLastAdmin(target, form.role());

        String newEmail = normalizeEmail(form.email());
        userRepository.findFirstByEmailIgnoreCaseAndIdNot(newEmail, target.getId())
                .ifPresent(other -> {
                    throw new EmailAlreadyUsedException(newEmail);
                });

        Map<String, Object> oldState = snapshot(target);

        Role oldRole = target.getRole();
        target.updateAdminFields(
                newEmail,
                form.fullName(),
                form.role(),
                form.emailVerified(),
                form.phone(),
                form.bio()
        );
        User saved = userRepository.save(target);

        Map<String, Object> newState = snapshot(saved);
        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("old", oldState);
        diff.put("new", newState);
        writeActivity(saved.getId(), UserActivity.TYPE_UPDATED,
                "Cập nhật tài khoản " + saved.getEmail(), serialize(diff), actingUserId);

        // Role-change activity is recorded as a separate row so audit consumers
        // can filter on `type = ROLE_CHANGED` without parsing UPDATED metadata.
        if (oldRole != saved.getRole()) {
            Map<String, Object> rolePayload = new LinkedHashMap<>();
            rolePayload.put("oldRole", oldRole.name());
            rolePayload.put("newRole", saved.getRole().name());
            writeActivity(saved.getId(), UserActivity.TYPE_ROLE_CHANGED,
                    "Đổi vai trò: " + oldRole + " → " + saved.getRole(),
                    serialize(rolePayload), actingUserId);
        }

        // Demote warning: if the user was LECTURER or HEAD and is now STUDENT,
        // surface affected classes so the admin can reassign them later.
        if ((oldRole == Role.LECTURER || oldRole == Role.HEAD)
                && saved.getRole() == Role.STUDENT) {
            List<ClassEntity> owned = classRepository.findAllByLecturerId(saved.getId());
            if (!owned.isEmpty()) {
                String classList = owned.stream()
                        .map(c -> c.getName() + " (" + c.getCode() + ")")
                        .reduce((a, b) -> a + ", " + b)
                        .orElse("");
                return List.of(
                        "Người dùng đang là giảng viên của " + owned.size()
                                + " lớp: " + classList
                                + ". Vui lòng phân công lại giảng viên.");
            }
        }

        return List.of();
    }

    // ── Lifecycle ─────────────────────────────────────────────────

    @Transactional
    public void deactivate(Long id, Long actingUserId) {
        User target = lockForLifecycle(id);
        guard.requireNotSelf(actingUserId, target.getId(), "vô hiệu hoá");
        guard.requireNotLastActiveAdmin(target, "vô hiệu hoá");

        target.setActive(false);
        User saved = userRepository.save(target);
        writeActivity(saved.getId(), UserActivity.TYPE_DEACTIVATED,
                "Vô hiệu hoá " + saved.getEmail(), null, actingUserId);
    }

    @Transactional
    public void activate(Long id, Long actingUserId) {
        User target = lockForLifecycle(id);

        target.setActive(true);
        User saved = userRepository.save(target);
        writeActivity(saved.getId(), UserActivity.TYPE_ACTIVATED,
                "Kích hoạt " + saved.getEmail(), null, actingUserId);
    }

    @Transactional
    public void lock(Long id, String reason, Long actingUserId) {
        User target = lockForLifecycle(id);
        guard.requireNotSelf(actingUserId, target.getId(), "khoá");
        guard.requireNotLastActiveAdmin(target, "khoá");

        if (reason == null || reason.isBlank()) {
            // Defensive — the controller's @Valid LockForm already enforces this.
            throw new IllegalArgumentException("Lý do khoá tài khoản không được để trống");
        }
        // Defensive sanitisation: cap length and strip control characters that
        // could break audit-log readability if the client somehow bypasses
        // the @Size(max=255) on the form.
        String safeReason = reason.trim();
        if (safeReason.length() > 255) safeReason = safeReason.substring(0, 255);
        safeReason = safeReason.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "");

        target.lock(safeReason);
        User saved = userRepository.save(target);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reason", safeReason);
        writeActivity(saved.getId(), UserActivity.TYPE_LOCKED,
                "Khoá " + saved.getEmail(), serialize(payload), actingUserId);
    }

    @Transactional
    public void unlock(Long id, Long actingUserId) {
        User target = lockForLifecycle(id);
        target.unlock();
        User saved = userRepository.save(target);
        writeActivity(saved.getId(), UserActivity.TYPE_UNLOCKED,
                "Mở khoá " + saved.getEmail(), null, actingUserId);
    }

    @Transactional
    public void resetPassword(Long id, String newPassword, Long actingUserId) {
        User target = lockForLifecycle(id);
        guard.requireNotSelf(actingUserId, target.getId(), "đặt lại mật khẩu");

        if (newPassword == null || newPassword.isBlank()) {
            throw new IllegalArgumentException("Mật khẩu mới không được để trống");
        }

        target.setPasswordHash(passwordEncoder.encode(newPassword));
        User saved = userRepository.save(target);
        // Intentionally null metadata — the plaintext password must not appear
        // in the audit log.
        writeActivity(saved.getId(), UserActivity.TYPE_PASSWORD_RESET,
                "Đặt lại mật khẩu " + saved.getEmail(), null, actingUserId);
    }

    @Transactional
    public void softDelete(Long id, Long actingUserId) {
        User target = lockForLifecycle(id);
        guard.requireNotSelf(actingUserId, target.getId(), "xoá");
        guard.requireNotLastActiveAdmin(target, "xoá");

        target.softDelete();
        User saved = userRepository.save(target);
        writeActivity(saved.getId(), UserActivity.TYPE_DELETED,
                "Xoá " + saved.getEmail(), null, actingUserId);
    }

    @Transactional
    public void restore(Long id, Long actingUserId) {
        // Soft-deleted rows are not visible via findByIdForUpdate (the entity's
        // @SQLRestriction filters them out). Restore must therefore go through
        // the native lookup that bypasses the restriction.
        User target = userRepository.findByIdIncludingDeleted(id)
                .orElseThrow(() -> new EntityNotFoundException("Người dùng không tồn tại"));

        target.restore();
        User saved = userRepository.save(target);
        writeActivity(saved.getId(), UserActivity.TYPE_RESTORED,
                "Khôi phục " + saved.getEmail(), null, actingUserId);
    }

    // ── Internals ─────────────────────────────────────────────────

    private User lockForLifecycle(Long id) {
        return userRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new EntityNotFoundException("Người dùng không tồn tại"));
    }

    private void writeActivity(Long targetUserId, String type, String message,
                               String metadata, Long actorId) {
        activityRepository.save(new UserActivity(targetUserId, type, message, metadata, actorId));
    }

    private static int clampPageSize(int requested) {
        if (requested <= 0) return DEFAULT_PAGE_SIZE;
        return Math.min(MAX_PAGE_SIZE, Math.max(MIN_PAGE_SIZE, requested));
    }

    /**
     * Resolves the request's {@code sort} parameter to a Spring Data
     * {@link Sort}. Unknown keys fall back to {@code createdAt,desc}.
     * Note: {@code rolePriority,*} is handled by a dedicated repository
     * method in {@link #list} and never reaches this resolver.
     */
    private static Sort resolveSort(String key) {
        if (key == null || key.isBlank()) return Sort.by(Sort.Direction.DESC, "createdAt");
        return switch (key) {
            case "fullName,asc"  -> Sort.by(Sort.Direction.ASC,  "fullName");
            case "fullName,desc" -> Sort.by(Sort.Direction.DESC, "fullName");
            case "createdAt,asc" -> Sort.by(Sort.Direction.ASC,  "createdAt");
            case "createdAt,desc"-> Sort.by(Sort.Direction.DESC, "createdAt");
            default              -> Sort.by(Sort.Direction.DESC, "createdAt");
        };
    }

    private static String normalizeEmail(String raw) {
        if (raw == null) return null;
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    private Map<String, Object> snapshot(User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("email", u.getEmail());
        m.put("fullName", u.getFullName());
        m.put("role", u.getRole() != null ? u.getRole().name() : null);
        m.put("emailVerified", u.isEmailVerified());
        m.put("phone", u.getPhone());
        m.put("bio", u.getBio());
        return m;
    }

    private String serialize(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            log.warn("Failed to serialize user activity metadata", ex);
            return null;
        }
    }
}
