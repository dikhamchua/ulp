-- =============================================================================
-- V8__seed_fake_students.sql
-- Seed 8 sinh vien fake + enroll tat ca vao moi lop hien co (active).
--
-- Muc dich: phuc vu UI testing trang chi tiet lop (tab Thanh vien).
-- KHONG dung o production — chi cho dev/demo.
--
-- Mat khau cho tat ca: "123456" (finalized by V25).
-- Historical BCrypt below matched earlier "password" seed; V25 re-hashes all users.
-- BCrypt: $2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy (giong V5).
--
-- Re-runnable: dung INSERT IGNORE de tranh duplicate email khi rerun sau khi
-- xoa tay (Flyway thuc su chi chay 1 lan — but safety net rat re).
-- =============================================================================

INSERT IGNORE INTO users (email, password_hash, full_name, role, phone, is_email_verified, is_active)
VALUES
('sv01@ulp.edu.vn', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'Đỗ Khắc Nam',     'STUDENT', '0971761607', 1, 1),
('sv02@ulp.edu.vn', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'Trần Thu Hà',     'STUDENT', '0905123456', 1, 1),
('sv03@ulp.edu.vn', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'Lê Văn Hùng',     'STUDENT', '0912765432', 1, 1),
('sv04@ulp.edu.vn', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'Phạm Minh Anh',   'STUDENT', '0987654321', 1, 1),
('sv05@ulp.edu.vn', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'Vũ Thị Mai',      'STUDENT', '0938222111', 1, 1),
('sv06@ulp.edu.vn', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'Nguyễn Bá Sơn',   'STUDENT', '0901234567', 1, 1),
('sv07@ulp.edu.vn', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'Hoàng Quỳnh Như', 'STUDENT', '0966778899', 1, 1),
('sv08@ulp.edu.vn', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'Bùi Tuấn Khang',  'STUDENT', '0989887766', 1, 1);

-- Enroll tat ca SV vao moi lop hien co (chua soft-delete).
-- Dung NOT EXISTS de re-runnable + bo qua khi da enrolled (UNIQUE constraint).
INSERT IGNORE INTO enrollments (user_id, class_id, status, joined_via, joined_at)
SELECT u.id, c.id, 'ACTIVE', 'MANUAL', NOW()
FROM users u
CROSS JOIN classes c
WHERE u.email IN ('sv01@ulp.edu.vn','sv02@ulp.edu.vn','sv03@ulp.edu.vn','sv04@ulp.edu.vn',
                  'sv05@ulp.edu.vn','sv06@ulp.edu.vn','sv07@ulp.edu.vn','sv08@ulp.edu.vn')
  AND c.is_deleted = 0;
