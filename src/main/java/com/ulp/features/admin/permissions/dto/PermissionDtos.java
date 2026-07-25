package com.ulp.features.admin.permissions.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DTOs for the admin permission screens.
 *
 * <p>Inner records only — entities never leave the service layer, per the project's
 * controller/DTO convention.
 */
public final class PermissionDtos {

    private PermissionDtos() {
    }

    /**
     * One cell of the role x permission matrix.
     *
     * @param roleCode the role owning the cell
     * @param granted  whether the role directly holds the permission
     * @param core     whether the cell is frozen (core ADMIN permission)
     */
    public record MatrixCell(String roleCode, boolean granted, boolean core) {
    }

    /**
     * One permission row of the matrix, with a cell per role.
     *
     * @param featureKey  the permission's feature key
     * @param name        the human-readable permission name
     * @param description the catalogue description
     * @param cells       one cell per role, in role order
     */
    public record MatrixRow(String featureKey, String name, String description, List<MatrixCell> cells) {
    }

    /**
     * A permission group and the rows it contains.
     *
     * @param group the permission group (e.g. {@code SYSTEM})
     * @param rows  the permissions in the group
     */
    public record MatrixGroup(String group, List<MatrixRow> rows) {
    }

    /**
     * The full matrix view model.
     *
     * @param roleCodes the roles forming the matrix columns
     * @param groups    the permission groups forming the matrix sections
     */
    public record MatrixView(List<String> roleCodes, List<MatrixGroup> groups) {
    }

    /**
     * One row of the override list.
     *
     * @param id           the override row id
     * @param userId       the user the override applies to
     * @param userEmail    the user's email, for display
     * @param featureKey   the permission's feature key
     * @param overrideType {@code GRANT} or {@code REVOKE}
     * @param reason       the recorded justification
     * @param expiresAt    expiry instant, or {@code null} when permanent
     * @param active       whether the row is still switched on
     * @param expired      whether the row's expiry has passed
     */
    public record OverrideRow(Long id, Long userId, String userEmail, String featureKey,
                              String overrideType, String reason, LocalDateTime expiresAt,
                              boolean active, boolean expired) {

        /** Reports whether the override currently affects the user's permissions. */
        public boolean inEffect() {
            return active && !expired;
        }
    }

    /**
     * One selectable permission in the override form's catalogue dropdown.
     *
     * @param featureKey the permission's feature key
     * @param name       the human-readable permission name
     * @param group      the permission group, shown as the option's prefix
     */
    public record CatalogEntry(String featureKey, String name, String group) {
    }

    /**
     * A user selectable as an override target.
     *
     * @param id       the user id
     * @param fullName the user's display name
     * @param email    the user's email
     * @param role     the user's role code
     */
    public record OverrideCandidate(Long id, String fullName, String email, String role) {
    }

    /**
     * Submitted form values for creating or replacing an override.
     *
     * @param userId       the target user
     * @param featureKey   the permission to override
     * @param overrideType {@code GRANT} or {@code REVOKE}
     * @param reason       mandatory justification
     * @param expiresAt    optional expiry instant
     */
    public record OverrideForm(Long userId, String featureKey, String overrideType,
                               String reason, LocalDateTime expiresAt) {
    }
}
