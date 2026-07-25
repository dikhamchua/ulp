package com.ulp.features.admin.permissions.repository;

import com.ulp.entities.PermissionActivity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data repository over the {@code permission_activities} audit table.
 *
 * <p>Write-mostly: rows are appended by {@code PermissionAuditWriter} and never updated
 * or deleted, so the audit trail of RBAC changes stays complete.
 */
@Repository
public interface PermissionActivityRepository extends JpaRepository<PermissionActivity, Long> {
}
