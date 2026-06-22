-- =============================================================================
-- ULP — V2__seed_data.sql
-- Seed data cho University Learning Platform
-- Chạy sau V1__init_schema.sql
-- Sprint 0 — Foundation
-- =============================================================================


-- =============================================================================
-- 1. DEPARTMENTS (Bộ môn)
-- =============================================================================

INSERT INTO departments (name, code, description) VALUES
('Công nghệ thông tin', 'CNTT', 'Khoa Công nghệ thông tin — đào tạo các ngành về phần mềm, mạng, hệ thống thông tin'),
('Kinh tế', 'KT', 'Khoa Kinh tế — đào tạo các ngành về kinh tế, quản trị kinh doanh, tài chính'),
('Ngoại ngữ', 'NN', 'Khoa Ngoại ngữ — đào tạo các ngành về ngôn ngữ Anh, Nhật, Hàn, Trung'),
('Điện - Điện tử', 'DDT', 'Khoa Điện - Điện tử — đào tạo các ngành về điện, điện tử, tự động hoá'),
('Cơ khí', 'CK', 'Khoa Cơ khí — đào tạo các ngành về cơ khí chế tạo, cơ điện tử');

-- =============================================================================
-- 2. DEFAULT ADMIN USER
--    Password: Admin@123 (BCrypt hash)
--    ⚠️ ĐỔI MẬT KHẨU NGAY SAU DEPLOY!
-- =============================================================================

INSERT INTO users (email, password_hash, full_name, role, is_email_verified, is_active) VALUES
('admin@ulp.edu.vn',
 '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
 'System Admin',
 'ADMIN',
 1,
 1);

-- Cập nhật departments.head_user_id (tạm NULL vì chưa có Head)

-- =============================================================================
-- 3. CATEGORIES (Danh mục khoá học — 2 cấp)
-- =============================================================================

-- Cấp 1 — Danh mục cha
INSERT INTO categories (name, slug, parent_id, description) VALUES
('Lập trình', 'lap-trinh', NULL, 'Các khoá học về lập trình phần mềm'),
('Cơ sở dữ liệu', 'co-so-du-lieu', NULL, 'Các khoá học về thiết kế và quản trị CSDL'),
('Mạng máy tính', 'mang-may-tinh', NULL, 'Các khoá học về mạng, bảo mật, hệ thống'),
('Kinh tế học', 'kinh-te-hoc', NULL, 'Các khoá học về kinh tế vi mô, vĩ mô'),
('Quản trị kinh doanh', 'quan-tri-kinh-doanh', NULL, 'Các khoá học về quản trị, marketing, nhân sự'),
('Tiếng Anh', 'tieng-anh', NULL, 'Các khoá học tiếng Anh từ cơ bản đến nâng cao'),
('Tiếng Nhật', 'tieng-nhat', NULL, 'Các khoá học tiếng Nhật'),
('Kỹ năng mềm', 'ky-nang-mem', NULL, 'Các khoá học về kỹ năng giao tiếp, làm việc nhóm, thuyết trình');

-- Cấp 2 — Danh mục con (parent_id trỏ lên cấp 1)
-- Các ID này sẽ được DB tự sinh; ta dùng subquery để lấy
INSERT INTO categories (name, slug, parent_id, description)
SELECT 'Java', 'java', id, 'Lập trình Java từ cơ bản đến nâng cao'
FROM categories WHERE slug = 'lap-trinh';

INSERT INTO categories (name, slug, parent_id, description)
SELECT 'Python', 'python', id, 'Lập trình Python cho người mới bắt đầu và phân tích dữ liệu'
FROM categories WHERE slug = 'lap-trinh';

INSERT INTO categories (name, slug, parent_id, description)
SELECT 'Web Development', 'web-development', id, 'Phát triển web với HTML, CSS, JavaScript, React'
FROM categories WHERE slug = 'lap-trinh';

INSERT INTO categories (name, slug, parent_id, description)
SELECT 'SQL & Database', 'sql-database', id, 'Thiết kế CSDL và truy vấn SQL'
FROM categories WHERE slug = 'co-so-du-lieu';

INSERT INTO categories (name, slug, parent_id, description)
SELECT 'An ninh mạng', 'an-ninh-mang', id, 'Bảo mật hệ thống và an ninh mạng'
FROM categories WHERE slug = 'mang-may-tinh';

INSERT INTO categories (name, slug, parent_id, description)
SELECT 'IELTS', 'ielts', id, 'Luyện thi IELTS 4 kỹ năng'
FROM categories WHERE slug = 'tieng-anh';

INSERT INTO categories (name, slug, parent_id, description)
SELECT 'TOEIC', 'toeic', id, 'Luyện thi TOEIC'
FROM categories WHERE slug = 'tieng-anh';

INSERT INTO categories (name, slug, parent_id, description)
SELECT 'Tiếng Nhật N5', 'tieng-nhat-n5', id, 'Tiếng Nhật trình độ N5'
FROM categories WHERE slug = 'tieng-nhat';

-- =============================================================================
-- 4. FEATURE PERMISSIONS MẶC ĐỊNH
--    Granular RBAC (11.8)
-- =============================================================================

-- STUDENT (R1) — Quyền cơ bản
INSERT INTO feature_permissions (role, feature_key, is_granted) VALUES
('STUDENT', 'course.view', 1),
('STUDENT', 'class.view', 1),
('STUDENT', 'enrollment.join', 1),
('STUDENT', 'enrollment.leave', 1),
('STUDENT', 'lesson.view', 1),
('STUDENT', 'lesson.attachment.download', 1),
('STUDENT', 'progress.track', 1),
('STUDENT', 'comment.create', 1),
('STUDENT', 'comment.edit_own', 1),
('STUDENT', 'comment.delete_own', 1),
('STUDENT', 'flashcard.create_own', 1),
('STUDENT', 'flashcard.review_own', 1),
('STUDENT', 'flashcard.share', 1),
('STUDENT', 'test.take', 1),
('STUDENT', 'test.view_result', 1),
('STUDENT', 'test.create_practice', 1),
('STUDENT', 'assignment.submit', 1),
('STUDENT', 'assignment.view_feedback', 1),
('STUDENT', 'notification.view', 1),
('STUDENT', 'message.send', 1),
('STUDENT', 'profile.edit_own', 1),
('STUDENT', 'password.change_own', 1);

-- LECTURER (R2) — Kế thừa STUDENT + quyền quản lý lớp/nội dung
INSERT INTO feature_permissions (role, feature_key, is_granted) VALUES
('LECTURER', 'class.create', 1),
('LECTURER', 'class.manage', 1),
('LECTURER', 'class.invite_code', 1),
('LECTURER', 'enrollment.manage', 1),
('LECTURER', 'enrollment.import_excel', 1),
('LECTURER', 'lesson.create', 1),
('LECTURER', 'lesson.edit', 1),
('LECTURER', 'lesson.delete', 1),
('LECTURER', 'lesson.publish', 1),
('LECTURER', 'lesson.preview', 1),
('LECTURER', 'comment.reply', 1),
('LECTURER', 'comment.pin', 1),
('LECTURER', 'comment.delete_any', 1),
('LECTURER', 'flashcard.create_official', 1),
('LECTURER', 'test.create', 1),
('LECTURER', 'test.edit', 1),
('LECTURER', 'test.delete', 1),
('LECTURER', 'test.view_student_results', 1),
('LECTURER', 'question_bank.manage', 1),
('LECTURER', 'assignment.create', 1),
('LECTURER', 'assignment.edit', 1),
('LECTURER', 'assignment.grade', 1),
('LECTURER', 'progress.view_student', 1),
('LECTURER', 'dashboard.teaching', 1);

-- HEAD (R3) — Kế thừa LECTURER + quyền quản lý bộ môn
INSERT INTO feature_permissions (role, feature_key, is_granted) VALUES
('HEAD', 'course.create', 1),
('HEAD', 'course.edit', 1),
('HEAD', 'course.delete', 1),
('HEAD', 'course.publish', 1),
('HEAD', 'section.manage', 1),
('HEAD', 'department.report', 1),
('HEAD', 'content.approve', 1),
('HEAD', 'content.version_manage', 1),
('HEAD', 'lecturer.assign', 1);

-- ADMIN (R4) — Toàn quyền hệ thống
INSERT INTO feature_permissions (role, feature_key, is_granted) VALUES
('ADMIN', 'user.manage', 1),
('ADMIN', 'user.role_assign', 1),
('ADMIN', 'user.lock_unlock', 1),
('ADMIN', 'department.manage', 1),
('ADMIN', 'category.manage', 1),
('ADMIN', 'course.manage_all', 1),
('ADMIN', 'course.activate_deactivate', 1),
('ADMIN', 'comment.moderate', 1),
('ADMIN', 'permission.manage', 1),
('ADMIN', 'system.settings', 1),
('ADMIN', 'system.google_oauth', 1),
('ADMIN', 'system.smtp', 1),
('ADMIN', 'system.ai_integration', 1),
('ADMIN', 'system.login_history', 1),
('ADMIN', 'system.progress_all', 1),
('ADMIN', 'dashboard.system', 1);

-- =============================================================================
-- 5. SAMPLE DATA (cho development)
--    Bỏ comment để dùng khi dev
-- =============================================================================

/*
-- Sample Lecturer account (password: Lecturer@123)
INSERT INTO users (email, password_hash, full_name, role, department_id, is_email_verified)
VALUES ('giangvien1@ulp.edu.vn',
        '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
        'Nguyễn Văn A',
        'LECTURER',
        1,
        1);

-- Sample Head account (password: Head@123)
INSERT INTO users (email, password_hash, full_name, role, department_id, is_email_verified)
VALUES ('truongbomon@ulp.edu.vn',
        '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
        'Trần Thị B',
        'HEAD',
        1,
        1);

-- Cập nhật trưởng bộ môn
UPDATE departments SET head_user_id = (SELECT id FROM users WHERE email = 'truongbomon@ulp.edu.vn')
WHERE code = 'CNTT';

-- Sample Student accounts (password: Student@123)
INSERT INTO users (email, password_hash, full_name, role, is_email_verified) VALUES
('sv01@student.ulp.edu.vn', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'Phạm Văn C', 'STUDENT', 1),
('sv02@student.ulp.edu.vn', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'Lê Thị D', 'STUDENT', 1),
('sv03@student.ulp.edu.vn', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'Hoàng Văn E', 'STUDENT', 1);

-- Sample Course
INSERT INTO courses (title, slug, description, department_id, created_by, status) VALUES
('Lập trình Java cơ bản', 'lap-trinh-java-co-ban',
 'Khoá học lập trình Java từ cơ bản đến hướng đối tượng. Dành cho sinh viên năm 1-2 ngành CNTT.',
 1, 1, 'DRAFT');
*/

-- =============================================================================
-- DONE — Seed data version 1
-- =============================================================================
