-- =============================================================================
-- ULP — V29__department_activities.sql
-- Audit log table for administrative mutations on departments.
-- Mirrors user_activities (V10):
--   - department_id ON DELETE CASCADE
--   - performed_by ON DELETE SET NULL
-- =============================================================================

CREATE TABLE department_activities (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    department_id BIGINT NOT NULL,
    type VARCHAR(50) NOT NULL,
    message TEXT NULL,
    metadata TEXT NULL,
    performed_by BIGINT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_dact_department (department_id),
    INDEX idx_dact_type (type),
    INDEX idx_dact_created (created_at),
    CONSTRAINT fk_dact_department FOREIGN KEY (department_id)
        REFERENCES departments(id) ON DELETE CASCADE,
    CONSTRAINT fk_dact_actor FOREIGN KEY (performed_by)
        REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
