-- Audit log for subject catalog mutations (mirrors department_activities).

CREATE TABLE subject_activities (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    subject_id BIGINT NOT NULL,
    type VARCHAR(50) NOT NULL,
    message TEXT NULL,
    metadata TEXT NULL,
    performed_by BIGINT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_sact_subject (subject_id),
    INDEX idx_sact_type (type),
    INDEX idx_sact_created (created_at),
    CONSTRAINT fk_sact_subject FOREIGN KEY (subject_id)
        REFERENCES subjects(id) ON DELETE CASCADE,
    CONSTRAINT fk_sact_actor FOREIGN KEY (performed_by)
        REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
