-- =============================================================================
-- V20 — MCQ online exams: additive scheduling columns + demo seed.
-- Reuses the V1 tables (tests / questions / question_options / test_attempts /
-- test_responses); NOTHING is recreated. Only new columns are added and demo
-- exams are seeded so the feature is demoable before the lecturer authoring UI.
-- =============================================================================
SET NAMES utf8mb4;

-- 1. Additive columns on `tests`: schedule window + time model.
ALTER TABLE tests
    ADD COLUMN start_at DATETIME NULL AFTER passing_score,
    ADD COLUMN end_at   DATETIME NULL AFTER start_at,
    ADD COLUMN time_mode VARCHAR(20) NOT NULL DEFAULT 'FIXED_WINDOW' AFTER end_at,
    ADD CONSTRAINT chk_test_time_mode CHECK (time_mode IN ('FIXED_WINDOW','INDIVIDUAL'));

-- 2. Additive column on `test_attempts`: last heartbeat timestamp for monitoring.
ALTER TABLE test_attempts
    ADD COLUMN last_activity_at DATETIME NULL AFTER time_spent_seconds;

-- =============================================================================
-- 3. Demo seed — two published exams in class 334.
-- The class + its lecturer must exist; the procedure resolves created_by from
-- classes.lecturer_id and skips cleanly (no-op) when the class is absent (e.g.
-- on a fresh database where auto-increment has not reached id 334).
-- =============================================================================
DROP PROCEDURE IF EXISTS seed_mcq_demo;
DELIMITER $$
CREATE PROCEDURE seed_mcq_demo()
BEGIN
    DECLARE v_class BIGINT DEFAULT 334;
    DECLARE v_lecturer BIGINT DEFAULT NULL;
    DECLARE v_test BIGINT DEFAULT NULL;
    DECLARE v_q BIGINT DEFAULT NULL;

    -- Resolve the owning lecturer; INTO leaves v_lecturer NULL when no row matches.
    SELECT lecturer_id INTO v_lecturer
    FROM classes WHERE id = v_class AND is_deleted = 0 LIMIT 1;

    IF v_lecturer IS NOT NULL THEN

        -- ── Exam 1: MOCK, FIXED_WINDOW, ongoing (started 1 day ago, ends in 7) ──
        INSERT INTO tests (title, description, class_id, type, duration_minutes,
                           passing_score, total_questions, shuffle_questions,
                           shuffle_options, status, created_by, start_at, end_at, time_mode)
        VALUES ('Kiểm tra giữa kỳ - Nhập môn Công nghệ phần mềm',
                'Đề trắc nghiệm ôn tập kiến thức nền tảng về quy trình phát triển phần mềm.',
                v_class, 'MOCK', NULL, 8.00, 6, 0, 0, 'PUBLISHED', v_lecturer,
                DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 7 DAY), 'FIXED_WINDOW');
        SET v_test = LAST_INSERT_ID();

        INSERT INTO questions (test_id, question_type, content, explanation, points, sort_order)
        VALUES (v_test, 'MCQ', 'Mô hình phát triển phần mềm nào nhấn mạnh việc lặp và tăng dần theo từng vòng?',
                'Agile phát triển phần mềm theo các vòng lặp ngắn (iteration), tăng dần giá trị.', 2.00, 1);
        SET v_q = LAST_INSERT_ID();
        INSERT INTO question_options (question_id, content, is_correct, sort_order) VALUES
            (v_q, 'Waterfall', 0, 1), (v_q, 'Agile', 1, 2),
            (v_q, 'Big Bang', 0, 3), (v_q, 'V-Model', 0, 4);

        INSERT INTO questions (test_id, question_type, content, explanation, points, sort_order)
        VALUES (v_test, 'MCQ', 'Trong nguyên tắc SOLID, chữ "S" đại diện cho nguyên tắc nào?',
                'S = Single Responsibility Principle: mỗi lớp chỉ nên có một lý do để thay đổi.', 2.00, 2);
        SET v_q = LAST_INSERT_ID();
        INSERT INTO question_options (question_id, content, is_correct, sort_order) VALUES
            (v_q, 'Single Responsibility Principle', 1, 1), (v_q, 'Separation of Concerns', 0, 2),
            (v_q, 'Simple Design', 0, 3), (v_q, 'Static Typing', 0, 4);

        INSERT INTO questions (test_id, question_type, content, explanation, points, sort_order)
        VALUES (v_test, 'MR', 'Những hoạt động nào sau đây thuộc giai đoạn kiểm thử phần mềm?',
                'Thu thập yêu cầu thuộc giai đoạn phân tích, không phải kiểm thử.', 3.00, 3);
        SET v_q = LAST_INSERT_ID();
        INSERT INTO question_options (question_id, content, is_correct, sort_order) VALUES
            (v_q, 'Unit testing', 1, 1), (v_q, 'Integration testing', 1, 2),
            (v_q, 'Thu thập yêu cầu (requirement elicitation)', 0, 3), (v_q, 'System testing', 1, 4);

        INSERT INTO questions (test_id, question_type, content, explanation, points, sort_order)
        VALUES (v_test, 'MCQ', 'Trong Git, lệnh nào dùng để tạo một nhánh (branch) mới?',
                '"git branch <ten>" tạo nhánh mới; git commit lưu thay đổi, git merge hợp nhất.', 1.00, 4);
        SET v_q = LAST_INSERT_ID();
        INSERT INTO question_options (question_id, content, is_correct, sort_order) VALUES
            (v_q, 'git branch', 1, 1), (v_q, 'git commit', 0, 2),
            (v_q, 'git merge', 0, 3), (v_q, 'git clone', 0, 4);

        INSERT INTO questions (test_id, question_type, content, explanation, points, sort_order)
        VALUES (v_test, 'MR', 'Những đặc điểm nào là nguyên tắc cốt lõi của lập trình hàm (functional programming)?',
                'Trạng thái toàn cục có thể thay đổi đi ngược lại tinh thần lập trình hàm.', 3.00, 5);
        SET v_q = LAST_INSERT_ID();
        INSERT INTO question_options (question_id, content, is_correct, sort_order) VALUES
            (v_q, 'Hàm thuần khiết (pure functions)', 1, 1), (v_q, 'Tính bất biến (immutability)', 1, 2),
            (v_q, 'Trạng thái toàn cục có thể thay đổi', 0, 3), (v_q, 'Hàm là công dân hạng nhất (first-class)', 1, 4);

        INSERT INTO questions (test_id, question_type, content, explanation, points, sort_order)
        VALUES (v_test, 'MCQ', 'Độ phức tạp thời gian trung bình của thuật toán tìm kiếm nhị phân là?',
                'Tìm kiếm nhị phân chia đôi không gian tìm kiếm mỗi bước → O(log n).', 2.00, 6);
        SET v_q = LAST_INSERT_ID();
        INSERT INTO question_options (question_id, content, is_correct, sort_order) VALUES
            (v_q, 'O(n)', 0, 1), (v_q, 'O(log n)', 1, 2),
            (v_q, 'O(n^2)', 0, 3), (v_q, 'O(1)', 0, 4);

        -- ── Exam 2: MODULE, INDIVIDUAL, 30-minute duration ──
        INSERT INTO tests (title, description, class_id, type, duration_minutes,
                           passing_score, total_questions, shuffle_questions,
                           shuffle_options, status, created_by, start_at, end_at, time_mode)
        VALUES ('Bài kiểm tra chương - Cơ sở dữ liệu quan hệ',
                'Đề trắc nghiệm về mô hình quan hệ, SQL và giao dịch. Mỗi sinh viên có 30 phút.',
                v_class, 'MODULE', 30, 6.00, 5, 1, 1, 'PUBLISHED', v_lecturer,
                DATE_SUB(NOW(), INTERVAL 2 DAY), DATE_ADD(NOW(), INTERVAL 14 DAY), 'INDIVIDUAL');
        SET v_test = LAST_INSERT_ID();

        INSERT INTO questions (test_id, question_type, content, explanation, points, sort_order)
        VALUES (v_test, 'MCQ', 'Khóa chính (primary key) của một bảng có đặc điểm nào?',
                'Khóa chính phải duy nhất và không được chứa giá trị NULL.', 2.00, 1);
        SET v_q = LAST_INSERT_ID();
        INSERT INTO question_options (question_id, content, is_correct, sort_order) VALUES
            (v_q, 'Duy nhất và không NULL', 1, 1), (v_q, 'Có thể chứa NULL', 0, 2),
            (v_q, 'Luôn phải tự tăng (auto increment)', 0, 3), (v_q, 'Có thể trùng lặp', 0, 4);

        INSERT INTO questions (test_id, question_type, content, explanation, points, sort_order)
        VALUES (v_test, 'MR', 'Những câu lệnh nào sau đây thuộc nhóm DML (Data Manipulation Language)?',
                'CREATE TABLE thuộc DDL; SELECT/INSERT/UPDATE thao tác trên dữ liệu → DML.', 3.00, 2);
        SET v_q = LAST_INSERT_ID();
        INSERT INTO question_options (question_id, content, is_correct, sort_order) VALUES
            (v_q, 'SELECT', 1, 1), (v_q, 'INSERT', 1, 2),
            (v_q, 'CREATE TABLE', 0, 3), (v_q, 'UPDATE', 1, 4);

        INSERT INTO questions (test_id, question_type, content, explanation, points, sort_order)
        VALUES (v_test, 'MCQ', 'Dạng chuẩn 1NF (First Normal Form) yêu cầu điều gì?',
                '1NF yêu cầu mỗi ô chỉ chứa một giá trị nguyên tử (atomic), không lặp nhóm.', 2.00, 3);
        SET v_q = LAST_INSERT_ID();
        INSERT INTO question_options (question_id, content, is_correct, sort_order) VALUES
            (v_q, 'Mỗi ô chứa giá trị nguyên tử', 1, 1), (v_q, 'Bảng không được có khóa ngoại', 0, 2),
            (v_q, 'Bảng phải có ít nhất 2 khóa chính', 0, 3), (v_q, 'Dữ liệu phải được mã hóa', 0, 4);

        INSERT INTO questions (test_id, question_type, content, explanation, points, sort_order)
        VALUES (v_test, 'MCQ', 'Phép JOIN nào trả về tất cả bản ghi của bảng trái và các bản ghi khớp của bảng phải?',
                'LEFT JOIN giữ toàn bộ bảng trái; cột bảng phải là NULL khi không khớp.', 2.00, 4);
        SET v_q = LAST_INSERT_ID();
        INSERT INTO question_options (question_id, content, is_correct, sort_order) VALUES
            (v_q, 'LEFT JOIN', 1, 1), (v_q, 'INNER JOIN', 0, 2),
            (v_q, 'RIGHT JOIN', 0, 3), (v_q, 'CROSS JOIN', 0, 4);

        INSERT INTO questions (test_id, question_type, content, explanation, points, sort_order)
        VALUES (v_test, 'MR', 'Các thuộc tính ACID của một giao dịch (transaction) gồm những thành phần nào?',
                'ACID = Atomicity, Consistency, Isolation, Durability. Scalability không thuộc ACID.', 3.00, 5);
        SET v_q = LAST_INSERT_ID();
        INSERT INTO question_options (question_id, content, is_correct, sort_order) VALUES
            (v_q, 'Atomicity (tính nguyên tử)', 1, 1), (v_q, 'Consistency (tính nhất quán)', 1, 2),
            (v_q, 'Isolation (tính cô lập)', 1, 3), (v_q, 'Durability (tính bền vững)', 1, 4),
            (v_q, 'Scalability (khả năng mở rộng)', 0, 5);

    END IF;
END$$
DELIMITER ;

CALL seed_mcq_demo();
DROP PROCEDURE seed_mcq_demo;
