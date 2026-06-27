package com.ulp.features.admin.users.service;

import com.ulp.entities.User;
import com.ulp.features.admin.users.dto.ActivityRow;
import com.ulp.features.admin.users.dto.UserFilter;
import com.ulp.features.admin.users.dto.UserRow;
import com.ulp.features.admin.users.repository.UserActivityRepository;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.utils.StringUtils;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.PersistenceContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Read-only operations backing the {@code /admin/users} screen.
 *
 * <p>Pulled out of the original {@code AdminUsersService} during the C.2
 * structural split. Covers the list/detail/history/created-at queries
 * consumed by both {@code AdminUsersController} (CRUD endpoints) and
 * {@code AdminUsersLifecycleController} (lifecycle endpoints).
 *
 * <p>Every method here is {@code @Transactional(readOnly = true)}; no
 * mutation responsibilities live on this service.
 */
@Service
public class AdminUsersReadService {

    /** Minimum allowed page size when listing users (clamped server-side). */
    public static final int MIN_PAGE_SIZE = 10;
    /** Maximum allowed page size when listing users (clamped server-side). */
    public static final int MAX_PAGE_SIZE = 100;
    /** Default page size when the request omits {@code size}. */
    public static final int DEFAULT_PAGE_SIZE = 20;

    private final UserRepository userRepository;
    private final UserActivityRepository activityRepository;

    @PersistenceContext
    private EntityManager entityManager;

    public AdminUsersReadService(UserRepository userRepository,
                                 UserActivityRepository activityRepository) {
        this.userRepository = userRepository;
        this.activityRepository = activityRepository;
    }

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

    // ── Internals ─────────────────────────────────────────────────

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
}
