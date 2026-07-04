-- =============================================================================
-- V21 — Student ↔ Lecturer direct messaging (Epic #13, ULP-8.3 + ULP-8.4).
-- Two new tables:
--   conversations — one row per unordered user pair (normalized lo/hi ids).
--   messages      — one row per message; unread is derived (read_at IS NULL).
-- Ends with an idempotent demo seed (stored procedure, mirrors V20) that no-ops
-- cleanly when the seed users / class are absent on a fresh database.
-- =============================================================================
SET NAMES utf8mb4;

-- V1 created a placeholder `messages` table (sender/receiver model) that this
-- migration supersedes with the conversation-normalized model below. It is
-- empty and unreferenced by any code. Drop it (and any half-created leftovers
-- from a prior failed run of this migration) so the CREATE statements below
-- run cleanly on every database. Order: drop `messages` before `conversations`
-- (the new `messages` FK-references `conversations`).
DROP TABLE IF EXISTS messages;
DROP TABLE IF EXISTS conversations;

-- 1. conversations — normalized pair (user_lo_id < user_hi_id), unique per pair.
CREATE TABLE conversations (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    user_lo_id      BIGINT       NOT NULL,
    user_hi_id      BIGINT       NOT NULL,
    last_message_at DATETIME     NULL,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uq_conversation_pair UNIQUE (user_lo_id, user_hi_id),
    CONSTRAINT chk_conversation_pair_order CHECK (user_lo_id < user_hi_id),
    CONSTRAINT fk_conversation_lo FOREIGN KEY (user_lo_id) REFERENCES users (id),
    CONSTRAINT fk_conversation_hi FOREIGN KEY (user_hi_id) REFERENCES users (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 2. messages — one row per message; read_at NULL means unread by the recipient.
CREATE TABLE messages (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    conversation_id BIGINT       NOT NULL,
    sender_id       BIGINT       NOT NULL,
    body            TEXT         NOT NULL,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    read_at         DATETIME     NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_message_conversation FOREIGN KEY (conversation_id) REFERENCES conversations (id),
    CONSTRAINT fk_message_sender FOREIGN KEY (sender_id) REFERENCES users (id),
    INDEX idx_messages_conversation_created (conversation_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- =============================================================================
-- 3. Demo seed — one conversation between student@ulp.edu.vn and class 334's
-- lecturer, with two-way messages and one lecturer→student message left unread
-- (read_at NULL) so the student's header badge is > 0. Guarded: no-op when the
-- student user, class 334, or its lecturer is absent (fresh DB).
-- =============================================================================
DROP PROCEDURE IF EXISTS seed_messaging_demo;
DELIMITER $$
CREATE PROCEDURE seed_messaging_demo()
BEGIN
    DECLARE v_student  BIGINT DEFAULT NULL;
    DECLARE v_lecturer BIGINT DEFAULT NULL;
    DECLARE v_lo       BIGINT DEFAULT NULL;
    DECLARE v_hi       BIGINT DEFAULT NULL;
    DECLARE v_conv     BIGINT DEFAULT NULL;

    -- Resolve the demo student; INTO leaves the var NULL when no row matches.
    SELECT id INTO v_student
    FROM users WHERE email = 'student@ulp.edu.vn' AND is_deleted = 0 LIMIT 1;

    -- Resolve the owning lecturer of class 334.
    SELECT lecturer_id INTO v_lecturer
    FROM classes WHERE id = 334 AND is_deleted = 0 LIMIT 1;

    -- Only seed when both parties exist and are distinct.
    IF v_student IS NOT NULL AND v_lecturer IS NOT NULL AND v_student <> v_lecturer THEN

        -- Normalize the pair: lo = smaller id, hi = larger id.
        SET v_lo = LEAST(v_student, v_lecturer);
        SET v_hi = GREATEST(v_student, v_lecturer);

        -- Skip if a conversation for this pair already exists (idempotent re-run).
        SELECT id INTO v_conv
        FROM conversations WHERE user_lo_id = v_lo AND user_hi_id = v_hi LIMIT 1;

        IF v_conv IS NULL THEN
            INSERT INTO conversations (user_lo_id, user_hi_id, last_message_at, created_at)
            VALUES (v_lo, v_hi, NULL, DATE_SUB(NOW(), INTERVAL 2 DAY));
            SET v_conv = LAST_INSERT_ID();

            -- Thread: student opens, lecturer replies, then one unread lecturer message.
            INSERT INTO messages (conversation_id, sender_id, body, created_at, read_at) VALUES
                (v_conv, v_student,  'Em chào thầy/cô, em có một câu hỏi về bài giảng tuần này ạ.',
                    DATE_SUB(NOW(), INTERVAL 2 DAY), DATE_SUB(NOW(), INTERVAL 2 DAY)),
                (v_conv, v_lecturer, 'Chào em, em cứ hỏi nhé.',
                    DATE_SUB(NOW(), INTERVAL 47 HOUR), DATE_SUB(NOW(), INTERVAL 46 HOUR)),
                (v_conv, v_student,  'Dạ em chưa hiểu rõ phần mô hình Agile ạ.',
                    DATE_SUB(NOW(), INTERVAL 46 HOUR), DATE_SUB(NOW(), INTERVAL 45 HOUR)),
                (v_conv, v_lecturer, 'Em xem lại slide 12, phần vòng lặp iteration nhé. Nếu còn thắc mắc cứ nhắn thầy.',
                    DATE_SUB(NOW(), INTERVAL 3 HOUR), NULL);

            -- last_message_at tracks the latest message time.
            UPDATE conversations
            SET last_message_at = DATE_SUB(NOW(), INTERVAL 3 HOUR)
            WHERE id = v_conv;
        END IF;

    END IF;
END$$
DELIMITER ;

CALL seed_messaging_demo();
DROP PROCEDURE seed_messaging_demo;
