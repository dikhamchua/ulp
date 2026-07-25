package com.ulp.features.admin.users.dto;

import java.util.List;

/**
 * View models for the per-user permission tab on the user edit screen.
 *
 * <p>Unlike the role matrix, this view is resolved for one user: every catalogue
 * permission is shown together with where its current state comes from — the
 * user's role chain, or an override that adds to / suppresses it.
 */
public final class UserPermissionDtos {

    private UserPermissionDtos() {
    }

    /** Permission the user holds purely because their role chain grants it. */
    public static final String SOURCE_FROM_ROLE = "FROM_ROLE";

    /** Permission the user holds only because an override adds it. */
    public static final String SOURCE_GRANT = "GRANT";

    /** Permission the user's role grants but an override suppresses. */
    public static final String SOURCE_REVOKE = "REVOKE";

    /** Permission neither the role nor any override provides. */
    public static final String SOURCE_NONE = "NONE";

    /**
     * One permission row rendered inside a group.
     *
     * @param featureKey  the permission's feature key (e.g. {@code library.manage})
     * @param name        the human-readable permission name
     * @param description optional catalogue description; may be {@code null}
     * @param source      one of the {@code SOURCE_*} constants
     * @param effective   whether the user currently holds the permission
     * @param fromRole    whether the user's role chain grants it, ignoring overrides
     * @param locked      whether the checkbox is frozen (core ADMIN permission)
     */
    public record PermissionRow(String featureKey,
                                String name,
                                String description,
                                String source,
                                boolean effective,
                                boolean fromRole,
                                boolean locked) {

        /** @return {@code true} when an override — not the role — decides this row */
        public boolean overridden() {
            return SOURCE_GRANT.equals(source) || SOURCE_REVOKE.equals(source);
        }
    }

    /**
     * One permission group with its rows.
     *
     * @param group       the group code (e.g. {@code USER_MANAGE})
     * @param rows        the group's permission rows, ordered by feature key
     * @param grantedCount how many rows in this group the user effectively holds
     */
    public record PermissionGroup(String group, List<PermissionRow> rows, int grantedCount) {
    }

    /**
     * The whole tab payload for one user.
     *
     * @param userId        the user being inspected
     * @param roleCode      the user's role code, used to explain inherited rows
     * @param groups        every permission group, ordered by group code
     * @param totalGranted  how many permissions the user effectively holds
     * @param overrideCount how many active overrides currently apply
     */
    public record UserPermissionView(Long userId,
                                     String roleCode,
                                     List<PermissionGroup> groups,
                                     int totalGranted,
                                     int overrideCount) {
    }
}
