package com.ulp.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * JPA entity mapping the {@code permissions} catalogue table
 * (see {@code V4__rbac_enhancement.sql}, extended by {@code V36}).
 *
 * <p>Each row is one addressable feature key (e.g. {@code class.create}) belonging
 * to a {@code permission_group} (e.g. {@code CLASS}, {@code SYSTEM}). The catalogue
 * is migration-owned: this change never creates or deletes rows from the UI, it only
 * reads them to build the role x permission matrix.
 *
 * <p>Deliberately not annotated with {@code @Data} — Lombok's generated
 * equals/hashCode on a JPA entity causes identity cycles.
 */
@Entity
@Table(name = "permissions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Permission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "feature_key", nullable = false, length = 100, unique = true)
    private String featureKey;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "permission_group", nullable = false, length = 50)
    private String permissionGroup;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Builds the Spring Security authority string for this permission.
     *
     * <p>Mirrors {@link com.ulp.security.Role#authority()} but with the {@code PERM_}
     * prefix, so permission authorities can never collide with {@code ROLE_} ones.
     *
     * @return authority string of the form {@code PERM_<feature_key>}
     */
    public String authority() {
        return authorityOf(featureKey);
    }

    /**
     * Builds the authority string for a raw feature key without loading the entity.
     *
     * @param featureKey the permission's feature key (e.g. {@code class.create})
     * @return authority string of the form {@code PERM_<feature_key>}
     */
    public static String authorityOf(String featureKey) {
        return "PERM_" + featureKey;
    }
}
