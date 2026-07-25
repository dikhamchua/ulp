package com.ulp.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * JPA entity mapping the {@code role_permissions} join table
 * (see {@code V4__rbac_enhancement.sql}).
 *
 * <p>A row means "role {@code role_code} is directly granted permission
 * {@code permission_id}". Inheritance between roles is expressed by
 * {@code role_hierarchy}, never by duplicating rows here — so an ADMIN row is
 * absent for permissions ADMIN merely inherits from HEAD.
 *
 * <p>Uses {@link Key} as an {@code @EmbeddedId} because the table's primary key
 * is the composite {@code (role_code, permission_id)}. Deliberately not
 * annotated with {@code @Data}.
 */
@Entity
@Table(name = "role_permissions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RolePermission {

    @EmbeddedId
    private Key id;

    @Column(name = "granted_at", insertable = false, updatable = false)
    private LocalDateTime grantedAt;

    /**
     * Creates a direct role-to-permission grant.
     *
     * @param roleCode     the role receiving the permission (e.g. {@code LECTURER})
     * @param permissionId the {@code permissions.id} being granted
     */
    public RolePermission(String roleCode, Long permissionId) {
        this.id = new Key(roleCode, permissionId);
    }

    /** Convenience accessor for the role side of the composite key. */
    public String getRoleCode() {
        return id == null ? null : id.getRoleCode();
    }

    /** Convenience accessor for the permission side of the composite key. */
    public Long getPermissionId() {
        return id == null ? null : id.getPermissionId();
    }

    /**
     * Composite primary key of {@code role_permissions}.
     *
     * <p>Equals/hashCode are hand-written (not Lombok-generated) because JPA
     * requires a stable, value-based identity contract on an embedded id.
     */
    @Embeddable
    @Getter
    @NoArgsConstructor(access = AccessLevel.PROTECTED)
    public static class Key implements Serializable {

        @Column(name = "role_code", nullable = false, length = 20)
        private String roleCode;

        @Column(name = "permission_id", nullable = false)
        private Long permissionId;

        public Key(String roleCode, Long permissionId) {
            this.roleCode = roleCode;
            this.permissionId = permissionId;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Key key)) return false;
            return Objects.equals(roleCode, key.roleCode)
                    && Objects.equals(permissionId, key.permissionId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(roleCode, permissionId);
        }
    }
}
