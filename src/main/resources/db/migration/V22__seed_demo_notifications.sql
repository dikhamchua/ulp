-- V22__seed_demo_notifications.sql
-- Seed a small set of demo notifications so the inbox is non-empty
-- in the development environment.
--
-- Guarded with existence-safe INSERT IGNORE so the migration can be
-- replayed on a fresh schema without duplicate-key errors. IDs are
-- explicit to keep tests stable.
--
-- Notification types used here must match NotificationType constants:
--   CLASS_ENROLLED, LESSON_PUBLISHED, SYSTEM

-- student@ulp.edu.vn (id = user seeded in V5)
INSERT IGNORE INTO notifications
    (id, user_id, title, content, type,
     reference_type, reference_id,
     is_read, read_at, is_email_sent, created_at)
SELECT
    1,
    u.id,
    'Chào mừng đến với ULP',
    'Bạn đã đăng nhập thành công vào hệ thống ULP lần đầu tiên.',
    'SYSTEM',
    NULL, NULL,
    0, NULL, 0,
    NOW() - INTERVAL 3 DAY
FROM users u WHERE u.email = 'student@ulp.edu.vn'
LIMIT 1;

INSERT IGNORE INTO notifications
    (id, user_id, title, content, type,
     reference_type, reference_id,
     is_read, read_at, is_email_sent, created_at)
SELECT
    2,
    u.id,
    'Tham gia lớp thành công',
    'Bạn đã tham gia lớp học thành công. Hãy khám phá nội dung bài giảng.',
    'CLASS_ENROLLED',
    'CLASS', NULL,
    0, NULL, 0,
    NOW() - INTERVAL 2 DAY
FROM users u WHERE u.email = 'student@ulp.edu.vn'
LIMIT 1;

INSERT IGNORE INTO notifications
    (id, user_id, title, content, type,
     reference_type, reference_id,
     is_read, read_at, is_email_sent, created_at)
SELECT
    3,
    u.id,
    'Bài giảng mới được xuất bản',
    'Một bài giảng mới vừa được giảng viên xuất bản trong lớp của bạn.',
    'LESSON_PUBLISHED',
    'LESSON', NULL,
    1, NOW() - INTERVAL 1 DAY, 0,
    NOW() - INTERVAL 1 DAY
FROM users u WHERE u.email = 'student@ulp.edu.vn'
LIMIT 1;

-- sv01@ulp.edu.vn (seeded in V8) — unread LESSON_PUBLISHED
INSERT IGNORE INTO notifications
    (id, user_id, title, content, type,
     reference_type, reference_id,
     is_read, read_at, is_email_sent, created_at)
SELECT
    4,
    u.id,
    'Bài giảng mới được xuất bản',
    'Bài giảng "Giới thiệu môn học" vừa được xuất bản.',
    'LESSON_PUBLISHED',
    'LESSON', NULL,
    0, NULL, 0,
    NOW() - INTERVAL 12 HOUR
FROM users u WHERE u.email = 'sv01@ulp.edu.vn'
LIMIT 1;
