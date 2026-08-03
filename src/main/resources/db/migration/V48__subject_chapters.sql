-- subject-chapter-outline-templates
-- Sample chapter outline owned by a subject (not class sections).

CREATE TABLE subject_chapters (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    subject_id    BIGINT       NOT NULL,
    title         VARCHAR(200) NOT NULL,
    -- Nullable so soft-delete can release the unique (subject_id, display_order) slot.
    display_order SMALLINT     NULL,
    is_deleted    TINYINT(1)   NOT NULL DEFAULT 0,
    created_by    BIGINT       NULL,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_subj_ch_subject
        FOREIGN KEY (subject_id) REFERENCES subjects(id) ON DELETE CASCADE,
    CONSTRAINT fk_subj_ch_creator
        FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE SET NULL,
    UNIQUE KEY uk_subj_ch_subject_order (subject_id, display_order),
    KEY idx_subj_ch_subject (subject_id, is_deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
