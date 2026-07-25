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
import java.util.Objects;

/**
 * JPA entity mapping the {@code role_hierarchy} table
 * (see {@code V4__rbac_enhancement.sql}).
 *
 * <p>A row {@code (parent, child)} reads "child inherits every permission of
 * parent". The seeded chain is {@code STUDENT <- LECTURER <- HEAD <- ADMIN}, so
 * ADMIN transitively holds everything.
 *
 * <p>Used by the cache-eviction path: when a permission is attached to or
 * detached from role R, every role reachable downwards from R (R itself plus all
 * its descendants) has its users' cached permission sets evicted.
 *
 * <p>Deliberately not annotated with {@code @Data}.
 */
@Entity
@Table(name = "role_hierarchy")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RoleHierarchy {

    @EmbeddedId
    private Key id;

    /**
     * Creates an inheritance edge.
     *
     * @param parentRoleCode the role whose permissions are inherited
     * @param childRoleCode  the role inheriting them
     */
    public RoleHierarchy(String parentRoleCode, String childRoleCode) {
        this.id = new Key(parentRoleCode, childRoleCode);
    }

    /** Convenience accessor for the parent side of the composite key. */
    public String getParentRoleCode() {
        return id == null ? null : id.getParentRoleCode();
    }

    /** Convenience accessor for the child side of the composite key. */
    public String getChildRoleCode() {
        return id == null ? null : id.getChildRoleCode();
    }

    /** Composite primary key of {@code role_hierarchy}. */
    @Embeddable
    @Getter
    @NoArgsConstructor(access = AccessLevel.PROTECTED)
    public static class Key implements Serializable {

        @Column(name = "parent_role_code", nullable = false, length = 20)
        private String parentRoleCode;

        @Column(name = "child_role_code", nullable = false, length = 20)
        private String childRoleCode;

        public Key(String parentRoleCode, String childRoleCode) {
            this.parentRoleCode = parentRoleCode;
            this.childRoleCode = childRoleCode;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Key key)) return false;
            return Objects.equals(parentRoleCode, key.parentRoleCode)
                    && Objects.equals(childRoleCode, key.childRoleCode);
        }

        @Override
        public int hashCode() {
            return Objects.hash(parentRoleCode, childRoleCode);
        }
    }
}
