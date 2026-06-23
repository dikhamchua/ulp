package com.ulp.admin.users.dto;

import com.ulp.auth.Role;
import com.ulp.auth.entity.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * DTOs and form objects for the admin user-management screen.
 *
 * <p>Records are grouped here following the project's {@code <Feature>Dtos.java}
 * convention (see {@code AdminDashboardDtos}, {@code ClassesDtos}). The
 * {@link UserRow} projection is consumed by Spring Data JPA's native-query
 * mapping; the {@link CreateUserForm} / {@link EditUserForm} / {@link LockForm}
 * / {@link ResetPasswordForm} records carry {@code @Valid}-annotated submissions
 * from the controller layer.
 */
public final class AdminUsersDtos {

    private AdminUsersDtos() {
        // utility holder; no instances
    }

    // ── List projection ────────────────────────────────────────────

    /**
     * Spring Data JPA native-query projection — one row per user surfaced on
     * the admin list page. The native SQL in
     * {@code UserRepository.searchUsersForAdmin} aliases columns to match
     * these accessor names.
     *
     * <p>Status fields are exposed as raw booleans rather than a pre-computed
     * label so the template can both render the pill colour AND decide which
     * kebab-menu actions to display per row (e.g. show "Unlock" only when
     * {@code locked = true}).
     */
    public interface UserRow {
        Long getId();
        String getFullName();
        String getEmail();
        String getRole();
        boolean isActive();
        boolean isLocked();
        boolean isDeleted();
        Long getDepartmentId();
        LocalDateTime getLastLoginAt();
        LocalDateTime getCreatedAt();
        String getAvatarUrl();

        /**
         * Computed status label used by the template for the status pill.
         * Ordering matters: a deleted row is reported as DELETED regardless
         * of its active/locked flags, then locked, then inactive, then active.
         *
         * @return one of {@code ACTIVE | INACTIVE | LOCKED | DELETED}
         */
        default String statusLabel() {
            if (isDeleted()) return "DELETED";
            if (isLocked())  return "LOCKED";
            if (!isActive()) return "INACTIVE";
            return "ACTIVE";
        }
    }

    // ── Filter / query binding ─────────────────────────────────────

    /**
     * Bound from the list page's query parameters. All fields are optional;
     * empty strings are treated as "no filter" by the repository SQL.
     */
    public record UserFilter(String q, String role, String status, String sort) {

        public static UserFilter empty() {
            return new UserFilter(null, null, null, null);
        }

        /** Convenience for templates: returns the supplied default when blank. */
        public String qOr(String fallback) {
            return (q == null || q.isBlank()) ? fallback : q;
        }
    }

    /** Closed enumeration of admissible status filter values. */
    public enum StatusFilter {
        ACTIVE,
        INACTIVE,
        LOCKED,
        DELETED;

        /** Parses a raw query-string value, returning {@code null} when blank/invalid. */
        public static String normalize(String raw) {
            if (raw == null || raw.isBlank()) return null;
            try {
                return StatusFilter.valueOf(raw.trim().toUpperCase()).name();
            } catch (IllegalArgumentException ex) {
                return null;
            }
        }
    }

    // ── Forms ──────────────────────────────────────────────────────

    /**
     * Create-account form. All required fields are validated with bean-validation
     * annotations; the service additionally normalises {@code email} (trim +
     * lowercase) before persistence.
     */
    public record CreateUserForm(
            @NotBlank(message = "Email không được để trống")
            @Email(message = "Email không hợp lệ")
            @Size(max = 255)
            String email,

            @NotBlank(message = "Họ tên không được để trống")
            @Size(max = 150)
            String fullName,

            @NotNull(message = "Vai trò không được để trống")
            Role role,

            Long departmentId,

            @Size(max = 20, message = "Số điện thoại tối đa 20 ký tự")
            String phone,

            String bio,

            boolean emailVerified,

            @NotBlank(message = "Mật khẩu tạm thời không được để trống")
            String password
    ) {
        public static CreateUserForm empty() {
            return new CreateUserForm(null, null, Role.LECTURER, null, null, null, false, null);
        }
    }

    /**
     * Edit-account form. Mirrors {@link CreateUserForm} but without the password
     * field — admins use the dedicated {@link ResetPasswordForm} flow for that.
     */
    public record EditUserForm(
            @NotBlank(message = "Email không được để trống")
            @Email(message = "Email không hợp lệ")
            @Size(max = 255)
            String email,

            @NotBlank(message = "Họ tên không được để trống")
            @Size(max = 150)
            String fullName,

            @NotNull(message = "Vai trò không được để trống")
            Role role,

            Long departmentId,

            @Size(max = 20, message = "Số điện thoại tối đa 20 ký tự")
            String phone,

            String bio,

            boolean emailVerified
    ) {
        public static EditUserForm empty() {
            return new EditUserForm(null, null, Role.LECTURER, null, null, null, false);
        }

        /** Build an edit form pre-filled with the supplied user's current values. */
        public static EditUserForm fromUser(User u) {
            return new EditUserForm(
                    u.getEmail(),
                    u.getFullName(),
                    u.getRole(),
                    null,
                    u.getPhone(),
                    u.getBio(),
                    u.isEmailVerified()
            );
        }
    }

    /** Lock-with-reason modal submission. */
    public record LockForm(
            @NotBlank(message = "Lý do khoá tài khoản không được để trống")
            @Size(max = 255)
            String lockedReason
    ) {}

    /** Admin reset-password modal submission. */
    public record ResetPasswordForm(
            @NotBlank(message = "Mật khẩu mới không được để trống")
            String newPassword
    ) {}

    // ── Audit history projection ──────────────────────────────────
    // ActivityRow lives as a top-level record in the same package (see
    // ActivityRow.java). It was originally written as a nested record here
    // per tasks.md 1.1, but Hibernate 6's JPQL `SELECT new ...` cannot
    // resolve nested-class names without a Hibernate-specific @Imported
    // hint that Spring Boot's default entity scan does not pick up. The
    // top-level record sidesteps the issue cleanly; same package + same
    // class name, no JPQL FQN gymnastics. See task report for details.
}
