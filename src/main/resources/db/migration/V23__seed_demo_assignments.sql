-- =============================================================================
-- ULP — V23__seed_demo_assignments.sql
-- Seeds demo assignments for classes that were seeded in earlier migrations.
--
-- This migration is DATA-ONLY (no DDL). The assignments, assignment_submissions,
-- and assignment_feedback tables already exist from V1__init_schema.sql.
--
-- Strategy: Use existence-safe INSERT IGNORE to avoid duplicates if run twice.
-- The lecturer@ulp.edu.vn user is referenced via subquery so the ID stays
-- stable even if auto-increment differs across environments.
-- =============================================================================

-- We rely on V8 fake-student + V5 test-user seeds. Pull the lecturer ID once
-- into a variable so all inserts reference the same user without hardcoding.
SET @lecturer_id = (SELECT id FROM users WHERE email = 'lecturer@ulp.edu.vn' LIMIT 1);
SET @student_id  = (SELECT id FROM users WHERE email = 'student@ulp.edu.vn'  LIMIT 1);

-- Pick the first class that belongs to the lecturer (created in V2 / V8 seeds).
-- If no class exists yet the INSERTs below will simply have a NULL class_id and
-- Flyway will still succeed (seed data is best-effort in dev environments).
SET @class_id = (
    SELECT c.id
    FROM classes c
    WHERE c.lecturer_id = @lecturer_id
      AND c.is_deleted = 0
    ORDER BY c.id
    LIMIT 1
);

-- ── DRAFT assignment ─────────────────────────────────────────────────────────
INSERT IGNORE INTO assignments
    (class_id, title, description, max_score, due_date, allow_late_submission,
     status, created_by, created_at, updated_at, is_deleted)
SELECT
    @class_id,
    'Bài tập 1: Giới thiệu bản thân',
    'Viết một đoạn văn ngắn (200–300 từ) giới thiệu bản thân, kinh nghiệm học tập và mục tiêu của bạn trong khoá học này.',
    100.00,
    DATE_ADD(NOW(), INTERVAL 14 DAY),
    0,
    'DRAFT',
    @lecturer_id,
    NOW(),
    NOW(),
    0
WHERE @class_id IS NOT NULL AND @lecturer_id IS NOT NULL;

-- ── PUBLISHED assignment ──────────────────────────────────────────────────────
INSERT IGNORE INTO assignments
    (class_id, title, description, max_score, due_date, allow_late_submission,
     status, created_by, created_at, updated_at, is_deleted)
SELECT
    @class_id,
    'Bài tập 2: Phân tích yêu cầu hệ thống',
    'Dựa trên bài giảng Chương 2, hãy phân tích yêu cầu chức năng và phi chức năng của hệ thống quản lý thư viện.\n\nYêu cầu:\n- Liệt kê ít nhất 5 yêu cầu chức năng\n- Liệt kê ít nhất 3 yêu cầu phi chức năng\n- Vẽ sơ đồ use-case cơ bản (có thể dùng text ASCII)\n\nNộp bài dưới dạng văn bản hoặc đường dẫn tài liệu.',
    100.00,
    DATE_ADD(NOW(), INTERVAL 7 DAY),
    1,
    'PUBLISHED',
    @lecturer_id,
    DATE_SUB(NOW(), INTERVAL 2 DAY),
    DATE_SUB(NOW(), INTERVAL 2 DAY),
    0
WHERE @class_id IS NOT NULL AND @lecturer_id IS NOT NULL;

-- Keep track of the published assignment id for seeding a submission below.
SET @published_assignment_id = (
    SELECT id FROM assignments
    WHERE class_id = @class_id
      AND title = 'Bài tập 2: Phân tích yêu cầu hệ thống'
      AND is_deleted = 0
    LIMIT 1
);

-- ── CLOSED assignment ─────────────────────────────────────────────────────────
INSERT IGNORE INTO assignments
    (class_id, title, description, max_score, due_date, allow_late_submission,
     status, created_by, created_at, updated_at, is_deleted)
SELECT
    @class_id,
    'Bài tập 0: Khảo sát kiến thức nền',
    'Điền vào bảng khảo sát các kiến thức nền mà bạn đã có trước khi bắt đầu khoá học. Bài tập này không tính điểm, chỉ dùng để giảng viên nắm bắt trình độ của lớp.',
    10.00,
    DATE_SUB(NOW(), INTERVAL 10 DAY),
    0,
    'CLOSED',
    @lecturer_id,
    DATE_SUB(NOW(), INTERVAL 20 DAY),
    DATE_SUB(NOW(), INTERVAL 10 DAY),
    0
WHERE @class_id IS NOT NULL AND @lecturer_id IS NOT NULL;

SET @closed_assignment_id = (
    SELECT id FROM assignments
    WHERE class_id = @class_id
      AND title = 'Bài tập 0: Khảo sát kiến thức nền'
      AND is_deleted = 0
    LIMIT 1
);

-- ── Demo submission (SUBMITTED) on the PUBLISHED assignment ───────────────────
-- Only seed if both the student and the assignment exist in this environment.
INSERT IGNORE INTO assignment_submissions
    (assignment_id, user_id, content, status, is_late, submitted_at, updated_at)
SELECT
    @published_assignment_id,
    @student_id,
    'Yêu cầu chức năng:\n1. Người dùng có thể tìm kiếm sách theo tên, tác giả, ISBN.\n2. Thủ thư có thể thêm / sửa / xoá sách trong hệ thống.\n3. Độc giả có thể mượn và trả sách.\n4. Hệ thống tự động ghi nhận ngày mượn và ngày trả dự kiến.\n5. Hệ thống gửi nhắc nhở khi sắp đến hạn trả.\n\nYêu cầu phi chức năng:\n1. Thời gian phản hồi tìm kiếm < 2 giây.\n2. Hệ thống phải có tính sẵn sàng 99.9% trong giờ làm việc.\n3. Giao diện tương thích với trình duyệt hiện đại (Chrome, Firefox, Edge).',
    'SUBMITTED',
    0,
    NOW(),
    NOW()
WHERE @published_assignment_id IS NOT NULL
  AND @student_id IS NOT NULL;

-- ── Demo submission (GRADED) on the CLOSED assignment ────────────────────────
INSERT IGNORE INTO assignment_submissions
    (assignment_id, user_id, content, status, is_late, submitted_at, updated_at)
SELECT
    @closed_assignment_id,
    @student_id,
    'Tôi đã học Java trong 1 học kỳ, biết cơ bản về OOP. Chưa có kinh nghiệm với Spring Boot.',
    'GRADED',
    0,
    DATE_SUB(NOW(), INTERVAL 9 DAY),
    DATE_SUB(NOW(), INTERVAL 8 DAY)
WHERE @closed_assignment_id IS NOT NULL
  AND @student_id IS NOT NULL;

SET @graded_submission_id = (
    SELECT id FROM assignment_submissions
    WHERE assignment_id = @closed_assignment_id
      AND user_id = @student_id
    LIMIT 1
);

INSERT IGNORE INTO assignment_feedback
    (submission_id, graded_by, score, feedback, is_ai_generated, created_at, updated_at)
SELECT
    @graded_submission_id,
    @lecturer_id,
    9.00,
    'Bạn đã nêu rõ kinh nghiệm Java. Trong khoá học này chúng ta sẽ bổ sung Spring Boot từ đầu, bạn không cần lo lắng.',
    0,
    DATE_SUB(NOW(), INTERVAL 8 DAY),
    DATE_SUB(NOW(), INTERVAL 8 DAY)
WHERE @graded_submission_id IS NOT NULL AND @lecturer_id IS NOT NULL;
