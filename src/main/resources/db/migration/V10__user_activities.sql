-- =============================================================================
-- ULP — V10__user_activities.sql
-- Audit log table for administrative mutations on user accounts.
-- Sprint 3 — Admin User Management
--
-- Mirrors the activity_classes pattern from V3:
--   - target_user_id stored as plain BIGINT (no @ManyToOne in the entity) so
--     soft-deleted users remain auditable
--   - performed_by ON DELETE SET NULL so a hard-deleted admin does not erase
--     their action history
--   - target_user_id ON DELETE CASCADE — if a row in `users` is ever truly
--     hard-deleted (administrator cleanup), its audit rows go with it
-- =============================================================================

CREATE TABLE user_activities (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    target_user_id BIGINT NOT NULL,
    type VARCHAR(50) NOT NULL,
    message TEXT NULL,
    metadata TEXT NULL,
    performed_by BIGINT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_uact_target (target_user_id),
    INDEX idx_uact_type (type),
    INDEX idx_uact_created (created_at),
    CONSTRAINT fk_uact_target FOREIGN KEY (target_user_id)
        REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_uact_actor FOREIGN KEY (performed_by)
        REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
