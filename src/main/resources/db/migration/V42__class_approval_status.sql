-- add-head-class-approval — expand classes.status with DRAFT and REJECTED,
-- and add the HEAD review audit columns.
--
-- New classes are created in DRAFT and stay non-operational until the
-- department HEAD approves them (→ UPCOMING) or rejects them (→ REJECTED,
-- terminal). MySQL names anonymous CHECKs as <table>_chk_<n>; look up the
-- status constraint by clause text so this migration stays robust across
-- environments (same approach as V24__enrollment_pending_rejected.sql).
--
-- NOT RETROACTIVE: this migration issues no UPDATE against existing rows.
-- Classes already UPCOMING / ACTIVE / COMPLETED / CANCELLED keep their status,
-- so live enrolment for every running class is untouched.

SET @status_chk := (
    SELECT cc.CONSTRAINT_NAME
    FROM information_schema.CHECK_CONSTRAINTS cc
    JOIN information_schema.TABLE_CONSTRAINTS tc
      ON tc.CONSTRAINT_SCHEMA = cc.CONSTRAINT_SCHEMA
     AND tc.CONSTRAINT_NAME = cc.CONSTRAINT_NAME
    WHERE cc.CONSTRAINT_SCHEMA = DATABASE()
      AND tc.TABLE_NAME = 'classes'
      AND cc.CHECK_CLAUSE LIKE '%UPCOMING%ACTIVE%COMPLETED%CANCELLED%'
      AND cc.CHECK_CLAUSE NOT LIKE '%DRAFT%'
    LIMIT 1
);

SET @drop_sql := IF(
    @status_chk IS NULL,
    'SELECT 1',
    CONCAT('ALTER TABLE classes DROP CHECK `', @status_chk, '`')
);

PREPARE stmt FROM @drop_sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

ALTER TABLE classes
    ADD CONSTRAINT chk_classes_status
        CHECK (status IN ('DRAFT', 'UPCOMING', 'ACTIVE',
                          'COMPLETED', 'CANCELLED', 'REJECTED'));

-- Review audit columns. All nullable: unreviewed rows (and every pre-existing
-- row) simply carry NULL. One reviewer/timestamp pair covers both outcomes —
-- the status column already records which outcome it was.
ALTER TABLE classes
    ADD COLUMN approved_by BIGINT NULL AFTER is_deleted,
    ADD COLUMN approved_at DATETIME NULL AFTER approved_by,
    ADD COLUMN rejection_note VARCHAR(500) NULL AFTER approved_at;

ALTER TABLE classes
    ADD CONSTRAINT fk_class_approver
        FOREIGN KEY (approved_by) REFERENCES users(id);
