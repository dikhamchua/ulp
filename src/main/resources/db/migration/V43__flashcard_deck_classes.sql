-- flashcard-multi-class-share — a deck may target any number of classes.
--
-- The single-target columns on flashcard_decks (class_id / visibility /
-- is_official) are replaced by a join table. Sharedness becomes a derived fact
-- (>= 1 join row) so the two representations can no longer disagree.
--
-- Order matters: the backfill MUST run before any DROP, otherwise existing
-- sharing is lost. There is no down-migration — rollback is a database restore.

-- 1. Join table: one row per (deck, class) target.
CREATE TABLE flashcard_deck_classes (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    deck_id BIGINT NOT NULL,
    class_id BIGINT NOT NULL,
    shared_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uq_fdc_deck_class (deck_id, class_id),
    INDEX idx_fdc_class (class_id),
    CONSTRAINT fk_fdc_deck FOREIGN KEY (deck_id) REFERENCES flashcard_decks(id) ON DELETE CASCADE,
    CONSTRAINT fk_fdc_class FOREIGN KEY (class_id) REFERENCES classes(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 2. Backfill every currently SHARED deck. Runs before any DROP.
INSERT INTO flashcard_deck_classes (deck_id, class_id)
SELECT id, class_id
FROM flashcard_decks
WHERE class_id IS NOT NULL AND visibility = 'SHARED';

-- 3. MySQL names the inline CHECK on visibility anonymously
-- (<table>_chk_<n>), so look it up by clause text before dropping the column.
SET @visibility_chk := (
    SELECT cc.CONSTRAINT_NAME
    FROM information_schema.CHECK_CONSTRAINTS cc
    JOIN information_schema.TABLE_CONSTRAINTS tc
      ON tc.CONSTRAINT_SCHEMA = cc.CONSTRAINT_SCHEMA
     AND tc.CONSTRAINT_NAME = cc.CONSTRAINT_NAME
    WHERE cc.CONSTRAINT_SCHEMA = DATABASE()
      AND tc.TABLE_NAME = 'flashcard_decks'
      AND cc.CHECK_CLAUSE LIKE '%PRIVATE%SHARED%OFFICIAL%'
    LIMIT 1
);

SET @drop_chk_sql := IF(
    @visibility_chk IS NULL,
    'SELECT 1',
    CONCAT('ALTER TABLE flashcard_decks DROP CHECK `', @visibility_chk, '`')
);

PREPARE stmt FROM @drop_chk_sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 4. Drop the class FK + index that hang off the column being removed.
ALTER TABLE flashcard_decks DROP FOREIGN KEY fk_fd_class;
ALTER TABLE flashcard_decks DROP INDEX idx_fd_class;

-- 5. The join table is now the single source of truth.
ALTER TABLE flashcard_decks
    DROP COLUMN class_id,
    DROP COLUMN visibility,
    DROP COLUMN is_official;
