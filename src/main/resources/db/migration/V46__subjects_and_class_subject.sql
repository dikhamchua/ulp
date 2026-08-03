-- department-subject-class-taxonomy
-- Canonical academic catalog is `subjects` (môn học). Legacy `courses` stays
-- untouched (still referenced by unused FKs); do not rename it.

-- 1. subjects table (department-owned catalog)
CREATE TABLE subjects (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    department_id BIGINT       NOT NULL,
    code          VARCHAR(30)  NOT NULL,
    title         VARCHAR(300) NOT NULL,
    description   TEXT         NULL,
    is_active     TINYINT(1)   NOT NULL DEFAULT 1,
    created_by    BIGINT       NULL,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted    TINYINT(1)   NOT NULL DEFAULT 0,
    -- Generated column so (department_id, code) is unique only among live rows.
    live_code     VARCHAR(30)  AS (IF(is_deleted = 0, code, NULL)) STORED,
    CONSTRAINT fk_subjects_department
        FOREIGN KEY (department_id) REFERENCES departments(id),
    CONSTRAINT fk_subjects_created_by
        FOREIGN KEY (created_by) REFERENCES users(id),
    UNIQUE KEY uk_subjects_dept_live_code (department_id, live_code),
    KEY idx_subjects_department (department_id),
    KEY idx_subjects_active (is_active, is_deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 2. Inactive placeholder per department for legacy class backfill (hidden from picker).
INSERT INTO subjects (department_id, code, title, description, is_active, is_deleted, created_at, updated_at)
SELECT d.id,
       'UNASSIGNED',
       'Chưa phân môn',
       'Placeholder for legacy classes without a real subject binding',
       0,
       0,
       CURRENT_TIMESTAMP,
       CURRENT_TIMESTAMP
FROM departments d;

-- One demo active subject per department so class forms are usable after migrate.
INSERT INTO subjects (department_id, code, title, description, is_active, is_deleted, created_at, updated_at)
SELECT d.id,
       CONCAT(d.code, '101'),
       CONCAT('Môn mẫu ', d.code),
       'Seeded active subject for class binding demos and tests',
       1,
       0,
       CURRENT_TIMESTAMP,
       CURRENT_TIMESTAMP
FROM departments d;

-- 3. Bind classes to subjects
ALTER TABLE classes
    ADD COLUMN subject_id BIGINT NULL AFTER department_id;

-- Prefer class.department_id when known.
UPDATE classes c
    INNER JOIN subjects s
        ON s.department_id = c.department_id
       AND s.code = 'UNASSIGNED'
       AND s.is_deleted = 0
SET c.subject_id = s.id
WHERE c.subject_id IS NULL
  AND c.department_id IS NOT NULL;

-- Fallback: lecturer department, and stamp class.department_id from that placeholder.
UPDATE classes c
    INNER JOIN users u ON u.id = c.lecturer_id
    INNER JOIN subjects s
        ON s.department_id = u.department_id
       AND s.code = 'UNASSIGNED'
       AND s.is_deleted = 0
SET c.subject_id = s.id,
    c.department_id = u.department_id
WHERE c.subject_id IS NULL
  AND u.department_id IS NOT NULL;

ALTER TABLE classes
    ADD CONSTRAINT fk_class_subject
        FOREIGN KEY (subject_id) REFERENCES subjects(id);

CREATE INDEX idx_classes_subject_id ON classes (subject_id);
