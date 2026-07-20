-- V28: Add classes.department_id for department-scoped HEAD features.
-- Backfill from the assigned lecturer's users.department_id when present.

ALTER TABLE classes
    ADD COLUMN department_id BIGINT NULL AFTER lecturer_id,
    ADD INDEX idx_class_department (department_id),
    ADD CONSTRAINT fk_class_department
        FOREIGN KEY (department_id) REFERENCES departments(id) ON DELETE SET NULL;

UPDATE classes c
    INNER JOIN users u ON u.id = c.lecturer_id
SET c.department_id = u.department_id
WHERE u.department_id IS NOT NULL
  AND c.department_id IS NULL;
