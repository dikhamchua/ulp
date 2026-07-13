-- join-class-approval — expand enrollments.status with PENDING and REJECTED.
--
-- Invite CODE/LINK self-join now creates PENDING rows that require the class
-- owner to approve (→ ACTIVE) or reject (→ REJECTED). Import/manual paths stay
-- ACTIVE. MySQL names anonymous CHECKs as <table>_chk_<n>; look up the status
-- constraint by clause text so this migration stays robust across environments.

SET @status_chk := (
    SELECT cc.CONSTRAINT_NAME
    FROM information_schema.CHECK_CONSTRAINTS cc
    JOIN information_schema.TABLE_CONSTRAINTS tc
      ON tc.CONSTRAINT_SCHEMA = cc.CONSTRAINT_SCHEMA
     AND tc.CONSTRAINT_NAME = cc.CONSTRAINT_NAME
    WHERE cc.CONSTRAINT_SCHEMA = DATABASE()
      AND tc.TABLE_NAME = 'enrollments'
      AND cc.CHECK_CLAUSE LIKE '%ACTIVE%REMOVED%COMPLETED%'
      AND cc.CHECK_CLAUSE NOT LIKE '%PENDING%'
    LIMIT 1
);

SET @drop_sql := IF(
    @status_chk IS NULL,
    'SELECT 1',
    CONCAT('ALTER TABLE enrollments DROP CHECK `', @status_chk, '`')
);

PREPARE stmt FROM @drop_sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

ALTER TABLE enrollments
    ADD CONSTRAINT chk_enrollments_status
        CHECK (status IN ('ACTIVE', 'REMOVED', 'COMPLETED', 'PENDING', 'REJECTED'));
