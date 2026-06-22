-- =============================================================================
-- ULP — V4__rbac_enhancement.sql
-- Hệ thống RBAC đầy đủ: Roles, Permissions, Role Hierarchy, User Overrides
-- Thay thế bảng feature_permissions đơn giản
-- Sprint 0 — Foundation
-- =============================================================================


-- =============================================================================
-- 1. XOÁ BẢNG CŨ
-- =============================================================================
DROP TABLE IF EXISTS feature_permissions;

-- =============================================================================
-- 2. ROLES — Định nghĩa vai trò hệ thống
-- =============================================================================
CREATE TABLE roles (
    code VARCHAR(20) PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    description TEXT NULL,
    is_system TINYINT(1) DEFAULT 1 COMMENT '1=role hệ thống không thể xoá',
    priority INT DEFAULT 0 COMMENT 'Độ ưu tiên (càng cao càng nhiều quyền)',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Seed 4 role hệ thống
INSERT INTO roles (code, name, description, is_system, priority) VALUES
('STUDENT',  'Sinh viên',         'Người học — xem nội dung, làm bài, thảo luận', 1, 10),
('LECTURER', 'Giảng viên',        'Người dạy — soạn nội dung, quản lý lớp, chấm bài', 1, 20),
('HEAD',     'Trưởng bộ môn',     'Quản lý bộ môn — duyệt nội dung, phân công GV, báo cáo', 1, 30),
('ADMIN',    'Quản trị hệ thống', 'Toàn quyền — quản lý user, cấu hình hệ thống, bảo mật', 1, 40);

-- Thêm FK từ users.role → roles.code (xoá constraint CHECK cũ nếu có)
-- Note: MySQL không hỗ trợ DROP CHECK constraint trực tiếp.
-- Nếu MySQL < 8.0.16, cần ALTER TABLE MODIFY COLUMN để thay đổi.
-- Strategy: thêm FK, giữ nguyên cột VARCHAR(20).
ALTER TABLE users
    ADD CONSTRAINT fk_user_role FOREIGN KEY (role) REFERENCES roles(code);

-- =============================================================================
-- 3. PERMISSIONS — Định nghĩa tất cả quyền (feature key) trong hệ thống
-- =============================================================================
CREATE TABLE permissions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    feature_key VARCHAR(100) NOT NULL,
    name VARCHAR(200) NOT NULL,
    description TEXT NULL,
    permission_group VARCHAR(50) NOT NULL COMMENT 'Nhóm quyền: AUTH, COURSE, LESSON, ...',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE INDEX idx_perm_key (feature_key),
    INDEX idx_perm_group (permission_group)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Seed tất cả permissions (theo group)
INSERT INTO permissions (feature_key, name, description, permission_group) VALUES
-- AUTH
('auth.register',             'Đăng ký tài khoản', 'Tạo tài khoản mới', 'AUTH'),
('auth.login',                'Đăng nhập', 'Đăng nhập hệ thống', 'AUTH'),
('auth.logout',               'Đăng xuất', 'Đăng xuất khỏi hệ thống', 'AUTH'),
('auth.verify_email',         'Xác thực email', 'Xác minh địa chỉ email', 'AUTH'),
('auth.reset_password',       'Đặt lại mật khẩu', 'Quên mật khẩu', 'AUTH'),
-- PROFILE
('profile.view_own',          'Xem hồ sơ cá nhân', 'Xem thông tin tài khoản của chính mình', 'PROFILE'),
('profile.edit_own',          'Sửa hồ sơ cá nhân', 'Cập nhật avatar, bio, SĐT', 'PROFILE'),
('profile.change_password',   'Đổi mật khẩu', 'Đổi mật khẩu cá nhân', 'PROFILE'),
-- COURSE
('course.view',               'Xem khoá học', 'Duyệt và xem chi tiết khoá học', 'COURSE'),
('course.search',             'Tìm kiếm khoá học', 'Tìm khoá học theo từ khoá', 'COURSE'),
('course.filter',             'Lọc khoá học', 'Lọc theo danh mục, bộ môn', 'COURSE'),
('course.create',             'Tạo khoá học', 'Tạo khoá học mới trong bộ môn', 'COURSE'),
('course.edit',               'Sửa khoá học', 'Cập nhật thông tin khoá học', 'COURSE'),
('course.delete',             'Xoá khoá học', 'Xoá khoá học (soft delete)', 'COURSE'),
('course.publish',            'Publish khoá học', 'Công khai khoá học cho SV', 'COURSE'),
('course.archive',            'Lưu trữ khoá học', 'Đưa khoá học vào archive', 'COURSE'),
('course.manage_all',         'Quản lý mọi khoá học', 'Admin quản lý khoá toàn hệ thống', 'COURSE'),
-- CLASS
('class.view',                'Xem lớp học', 'Xem danh sách và chi tiết lớp học', 'CLASS'),
('class.create',              'Tạo lớp học', 'Tạo lớp mới từ khoá được phân công', 'CLASS'),
('class.edit',                'Sửa lớp học', 'Cập nhật thông tin lớp', 'CLASS'),
('class.delete',              'Xoá lớp học', 'Xoá lớp (soft delete)', 'CLASS'),
('class.change_layout',       'Đổi bố cục', 'Chuyển lưới/danh sách', 'CLASS'),
('class.invite_code',         'Sinh mã mời', 'Tạo/tái tạo mã & link mời vào lớp', 'CLASS'),
-- ENROLLMENT
('enrollment.join',           'Tham gia lớp', 'Join lớp qua mã hoặc link', 'ENROLLMENT'),
('enrollment.leave',          'Rời lớp', 'Rời khỏi lớp đã tham gia', 'ENROLLMENT'),
('enrollment.view_own',       'Xem lớp đã tham gia', 'Danh sách lớp của bản thân', 'ENROLLMENT'),
('enrollment.manage',         'Quản lý SV trong lớp', 'Thêm/xoá SV thủ công', 'ENROLLMENT'),
('enrollment.import_excel',   'Import Excel', 'Import danh sách SV từ file Excel', 'ENROLLMENT'),
('enrollment.status_manage',  'Quản lý trạng thái', 'Active/Removed/Completed', 'ENROLLMENT'),
-- SECTION
('section.view',              'Xem chương', 'Xem danh sách chương của khoá học', 'SECTION'),
('section.manage',            'Quản lý chương', 'Tạo/sửa/xoá/sắp xếp chương', 'SECTION'),
-- LESSON
('lesson.view',               'Xem bài học', 'Đọc nội dung bài học (rich-text, PDF)', 'LESSON'),
('lesson.attachment.download','Tải tài liệu', 'Download file đính kèm bài học', 'LESSON'),
('lesson.create',             'Tạo bài học', 'Soạn bài học mới', 'LESSON'),
('lesson.edit',               'Sửa bài học', 'Cập nhật nội dung bài học', 'LESSON'),
('lesson.delete',             'Xoá bài học', 'Xoá bài học (soft delete)', 'LESSON'),
('lesson.publish',            'Publish bài học', 'Công khai bài học cho SV', 'LESSON'),
('lesson.preview',            'Preview bài học', 'Xem trước từ góc nhìn sinh viên', 'LESSON'),
-- COMMENT (Q&A / Thảo luận)
('comment.create',            'Viết bình luận', 'Đặt câu hỏi / bình luận trong bài học', 'COMMENT'),
('comment.edit_own',          'Sửa bình luận của mình', 'Chỉnh sửa bình luận đã đăng', 'COMMENT'),
('comment.delete_own',        'Xoá bình luận của mình', 'Xoá bình luận của chính mình', 'COMMENT'),
('comment.delete_any',        'Xoá bình luận bất kỳ', 'GV xoá bình luận của SV', 'COMMENT'),
('comment.pin',               'Ghim bình luận', 'Ghim/bỏ ghim bình luận quan trọng', 'COMMENT'),
('comment.reply',             'Trả lời Q&A', 'Giảng viên trả lời câu hỏi của SV', 'COMMENT'),
('comment.moderate',          'Duyệt bình luận', 'Approve/Reject bình luận (moderation)', 'COMMENT'),
-- LEARNING PROGRESS
('progress.track_own',        'Theo dõi tiến độ cá nhân', 'Xem % hoàn thành của bản thân', 'PROGRESS'),
('progress.view_student',     'Xem tiến độ SV', 'GV xem tiến độ SV trong lớp', 'PROGRESS'),
('progress.view_all',         'Xem tiến độ toàn hệ thống', 'Admin xem tiến độ tổng', 'PROGRESS'),
-- FLASHCARD
('flashcard.view_own',        'Xem flashcard cá nhân', 'Xem bộ flashcard của mình', 'FLASHCARD'),
('flashcard.create_own',      'Tạo flashcard cá nhân', 'Tạo bộ + thẻ flashcard (tay/excel)', 'FLASHCARD'),
('flashcard.review',          'Học flashcard', 'Smart Review (SM-2 spaced repetition)', 'FLASHCARD'),
('flashcard.share',           'Chia sẻ flashcard', 'Chia sẻ bộ flashcard với SV khác', 'FLASHCARD'),
('flashcard.create_official', 'Tạo flashcard chính thức', 'GV tạo bộ flashcard cho khoá', 'FLASHCARD'),
('flashcard.manage_any',      'Quản lý mọi flashcard', 'Admin quản lý toàn bộ flashcard', 'FLASHCARD'),
-- TEST
('test.view',                 'Xem danh sách bài test', 'Xem Mock/Module/Practice test', 'TEST'),
('test.take',                 'Làm bài test', 'Làm bài và nộp', 'TEST'),
('test.view_own_result',      'Xem kết quả bài test', 'Xem điểm và chi tiết bài làm', 'TEST'),
('test.create_practice',      'Tạo Practice Test', 'Tự tạo bài test từ ngân hàng câu hỏi', 'TEST'),
('test.create',               'Tạo bài test', 'GV tạo Mock/Module/Practice test', 'TEST'),
('test.edit',                 'Sửa bài test', 'Cập nhật câu hỏi và cài đặt test', 'TEST'),
('test.delete',               'Xoá bài test', 'Xoá bài test (soft delete)', 'TEST'),
('test.publish',              'Publish bài test', 'Công khai test cho SV làm', 'TEST'),
('test.view_student_results', 'Xem kết quả SV', 'GV xem kết quả test của từng SV', 'TEST'),
-- QUESTION BANK
('question_bank.view',        'Xem ngân hàng câu hỏi', 'Duyệt câu hỏi trong ngân hàng', 'QUESTION_BANK'),
('question_bank.manage',      'Quản lý ngân hàng câu hỏi', 'Thêm/sửa/import câu hỏi', 'QUESTION_BANK'),
-- ASSIGNMENT
('assignment.view',           'Xem bài tập', 'Xem danh sách bài tập được giao', 'ASSIGNMENT'),
('assignment.submit',         'Nộp bài tập', 'Nộp bài luận / bài tập', 'ASSIGNMENT'),
('assignment.view_feedback',  'Xem phản hồi', 'Xem điểm và nhận xét của GV', 'ASSIGNMENT'),
('assignment.create',         'Tạo bài tập', 'GV tạo bài tập mới', 'ASSIGNMENT'),
('assignment.edit',           'Sửa bài tập', 'Cập nhật đề bài / rubric', 'ASSIGNMENT'),
('assignment.delete',         'Xoá bài tập', 'Xoá bài tập (soft delete)', 'ASSIGNMENT'),
('assignment.publish',        'Publish bài tập', 'Công khai bài tập cho SV', 'ASSIGNMENT'),
('assignment.grade',          'Chấm bài tập', 'GV chấm điểm và phản hồi Assignment', 'ASSIGNMENT'),
-- NOTIFICATION
('notification.view',         'Xem thông báo', 'Xem thông báo hệ thống', 'NOTIFICATION'),
('notification.receive_email','Nhận thông báo qua email', 'Email khi có cập nhật quan trọng', 'NOTIFICATION'),
-- MESSAGE
('message.send',              'Gửi tin nhắn', 'Gửi/nhận tin nhắn giữa các vai trò', 'MESSAGE'),
('message.view_badge',        'Xem badge chưa đọc', 'Hiển thị số tin nhắn/thông báo chưa đọc', 'MESSAGE'),
-- USER MANAGEMENT
('user.view',                 'Xem danh sách user', 'Xem danh sách người dùng', 'USER_MANAGE'),
('user.create',               'Tạo tài khoản', 'Admin tạo tài khoản mới', 'USER_MANAGE'),
('user.edit',                 'Sửa tài khoản', 'Cập nhật thông tin user', 'USER_MANAGE'),
('user.activate_deactivate',  'Kích hoạt / Vô hiệu hoá', 'Bật/tắt tài khoản', 'USER_MANAGE'),
('user.role_assign',          'Phân công vai trò', 'Đổi role của user', 'USER_MANAGE'),
('user.lock_unlock',          'Khoá / Mở khoá', 'Lock/unlock tài khoản vì lý do bảo mật', 'USER_MANAGE'),
-- DEPARTMENT
('department.view',           'Xem bộ môn', 'Xem danh sách bộ môn', 'DEPARTMENT'),
('department.manage',         'Quản lý bộ môn', 'Tạo/sửa/xoá bộ môn, gán trưởng bộ môn', 'DEPARTMENT'),
('department.report',         'Báo cáo bộ môn', 'Head xem báo cáo toàn bộ môn', 'DEPARTMENT'),
-- CATEGORY
('category.view',             'Xem danh mục', 'Xem danh mục khoá học', 'CATEGORY'),
('category.manage',           'Quản lý danh mục', 'Tạo/sửa/xoá danh mục khoá học', 'CATEGORY'),
-- CONTENT APPROVAL
('content.submit',            'Gửi duyệt nội dung', 'GV gửi bài học/test để Head duyệt', 'CONTENT'),
('content.approve',           'Duyệt nội dung', 'Head duyệt/publish nội dung', 'CONTENT'),
('content.reject',            'Từ chối nội dung', 'Head từ chối nội dung', 'CONTENT'),
('content.request_changes',   'Yêu cầu chỉnh sửa', 'Head yêu cầu GV sửa nội dung', 'CONTENT'),
('content.version_manage',    'Quản lý phiên bản', 'Content versioning draft→published', 'CONTENT'),
-- LECTURER ASSIGNMENT
('lecturer.assign',           'Phân công giảng viên', 'Head gán GV phụ trách lớp', 'LECTURER'),
-- DASHBOARD
('dashboard.student',         'Dashboard sinh viên', 'Tổng quan tiến độ học tập cá nhân', 'DASHBOARD'),
('dashboard.teaching',        'Dashboard giảng dạy', 'GV xem thống kê lớp được phân công', 'DASHBOARD'),
('dashboard.department',      'Dashboard bộ môn', 'Head xem thống kê toàn bộ môn', 'DASHBOARD'),
('dashboard.system',          'Dashboard hệ thống', 'Admin xem thống kê toàn hệ thống', 'DASHBOARD'),
-- SYSTEM SETTINGS
('system.settings',           'Cài đặt hệ thống', 'Sửa tên, logo, thông tin liên hệ', 'SYSTEM'),
('system.oauth',              'Cấu hình Google Login', 'Cấu hình Google OAuth', 'SYSTEM'),
('system.smtp',               'Cấu hình SMTP', 'Cấu hình email server', 'SYSTEM'),
('system.ai',                 'Cấu hình AI', 'Cấu hình tích hợp AI [OPT]', 'SYSTEM'),
('system.login_history',      'Xem lịch sử đăng nhập', 'Theo dõi login toàn hệ thống', 'SYSTEM'),
-- VIDEO (OPT)
('video.view',                'Xem video bài học', 'Xem video YouTube bảo mật [OPT]', 'VIDEO'),
('video.history',             'Lịch sử xem video', 'Ghi nhớ vị trí đã xem [OPT]', 'VIDEO'),
-- AI (OPT)
('ai.grading',                'AI chấm bài', 'Chấm assignment tự động theo rubric [OPT]', 'AI'),
('ai.question_gen',           'AI tạo câu hỏi', 'Sinh câu hỏi từ tài liệu [OPT]', 'AI'),
('ai.chatbot',                'AI Study Chatbot', 'Chatbot RAG theo nội dung khoá [OPT]', 'AI'),
('ai.flashcard_gen',          'AI tạo flashcard', 'Sinh flashcard từ bài giảng [OPT]', 'AI'),
('ai.weakness_analysis',      'AI phân tích điểm yếu', 'Phân tích sau test, gợi ý ôn tập [OPT]', 'AI');

-- =============================================================================
-- 4. ROLE PERMISSIONS — M:N: Role được cấp những quyền gì
-- =============================================================================
CREATE TABLE role_permissions (
    role_code VARCHAR(20) NOT NULL,
    permission_id BIGINT NOT NULL,
    granted_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (role_code, permission_id),
    CONSTRAINT fk_rp_role FOREIGN KEY (role_code) REFERENCES roles(code) ON DELETE CASCADE,
    CONSTRAINT fk_rp_permission FOREIGN KEY (permission_id) REFERENCES permissions(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Seed: STUDENT permissions (R1)
INSERT INTO role_permissions (role_code, permission_id)
SELECT 'STUDENT', id FROM permissions WHERE feature_key IN (
    'auth.register', 'auth.login', 'auth.logout', 'auth.verify_email', 'auth.reset_password',
    'profile.view_own', 'profile.edit_own', 'profile.change_password',
    'course.view', 'course.search', 'course.filter',
    'class.view', 'class.change_layout',
    'enrollment.join', 'enrollment.leave', 'enrollment.view_own',
    'section.view',
    'lesson.view', 'lesson.attachment.download',
    'comment.create', 'comment.edit_own', 'comment.delete_own',
    'progress.track_own',
    'flashcard.view_own', 'flashcard.create_own', 'flashcard.review', 'flashcard.share',
    'test.view', 'test.take', 'test.view_own_result', 'test.create_practice',
    'assignment.view', 'assignment.submit', 'assignment.view_feedback',
    'notification.view', 'notification.receive_email',
    'message.send', 'message.view_badge',
    'dashboard.student'
);

-- Seed: LECTURER permissions (R2) — CHỈ các quyền RIÊNG của Lecturer
-- (Quyền của STUDENT được kế thừa qua role_hierarchy)
INSERT INTO role_permissions (role_code, permission_id)
SELECT 'LECTURER', id FROM permissions WHERE feature_key IN (
    'class.create', 'class.edit', 'class.delete', 'class.invite_code',
    'enrollment.manage', 'enrollment.import_excel', 'enrollment.status_manage',
    'section.manage',
    'lesson.create', 'lesson.edit', 'lesson.delete', 'lesson.publish', 'lesson.preview',
    'comment.reply', 'comment.pin', 'comment.delete_any',
    'progress.view_student',
    'flashcard.create_official',
    'test.create', 'test.edit', 'test.delete', 'test.publish', 'test.view_student_results',
    'question_bank.view', 'question_bank.manage',
    'assignment.create', 'assignment.edit', 'assignment.delete', 'assignment.publish', 'assignment.grade',
    'content.submit',
    'dashboard.teaching'
);

-- Seed: HEAD permissions (R3) — CHỈ các quyền RIÊNG của Head
INSERT INTO role_permissions (role_code, permission_id)
SELECT 'HEAD', id FROM permissions WHERE feature_key IN (
    'course.create', 'course.edit', 'course.delete', 'course.publish', 'course.archive',
    'department.report',
    'content.approve', 'content.reject', 'content.request_changes', 'content.version_manage',
    'lecturer.assign',
    'dashboard.department'
);

-- Seed: ADMIN permissions (R4) — CHỈ các quyền RIÊNG của Admin
INSERT INTO role_permissions (role_code, permission_id)
SELECT 'ADMIN', id FROM permissions WHERE feature_key IN (
    'course.manage_all',
    'flashcard.manage_any',
    'user.view', 'user.create', 'user.edit', 'user.activate_deactivate',
    'user.role_assign', 'user.lock_unlock',
    'department.view', 'department.manage',
    'category.view', 'category.manage',
    'comment.moderate',
    'progress.view_all',
    'system.settings', 'system.oauth', 'system.smtp', 'system.ai', 'system.login_history',
    'dashboard.system',
    -- OPT permissions (Admin có thể bật cho user cụ thể qua override)
    'video.view', 'video.history',
    'ai.grading', 'ai.question_gen', 'ai.chatbot', 'ai.flashcard_gen', 'ai.weakness_analysis'
);

-- =============================================================================
-- 5. ROLE HIERARCHY — Kế thừa quyền giữa các role
--    LECTURER extends STUDENT
--    HEAD extends LECTURER (→ HEAD tự động có STUDENT + LECTURER)
--    ADMIN extends HEAD    (→ ADMIN tự động có tất cả)
-- =============================================================================
CREATE TABLE role_hierarchy (
    parent_role_code VARCHAR(20) NOT NULL,
    child_role_code VARCHAR(20) NOT NULL,
    PRIMARY KEY (parent_role_code, child_role_code),
    CONSTRAINT fk_rh_parent FOREIGN KEY (parent_role_code) REFERENCES roles(code) ON DELETE CASCADE,
    CONSTRAINT fk_rh_child FOREIGN KEY (child_role_code) REFERENCES roles(code) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Seed hierarchy chain:
-- STUDENT ← LECTURER ← HEAD ← ADMIN
-- (Đọc: LECTURER kế thừa STUDENT; HEAD kế thừa LECTURER; ADMIN kế thừa HEAD)
INSERT INTO role_hierarchy (parent_role_code, child_role_code) VALUES
('STUDENT',  'LECTURER'),
('LECTURER', 'HEAD'),
('HEAD',     'ADMIN');

-- =============================================================================
-- 6. USER PERMISSION OVERRIDES — Gán/Thu hồi quyền cho user cụ thể
--    Cho phép cấp thêm quyền (GRANT) hoặc thu hồi quyền (REVOKE)
--    so với quyền mặc định từ role của user đó.
--    Ví dụ:
--      - Cho 1 STUDENT quyền lesson.create (GRANT) → SV đó soạn được bài học
--      - Thu hồi quyền comment.create của 1 LECTURER (REVOKE) → GV đó không viết được bình luận
--      - Cho 1 HEAD quyền system.settings (GRANT) → Head đó truy cập được cài đặt hệ thống
-- =============================================================================
CREATE TABLE user_permission_overrides (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    override_type VARCHAR(10) NOT NULL CHECK (override_type IN ('GRANT', 'REVOKE')),
    reason VARCHAR(500) NULL COMMENT 'Lý do override',
    granted_by BIGINT NOT NULL COMMENT 'Ai thực hiện override (thường là Admin)',
    expires_at DATETIME NULL COMMENT 'Hết hạn override (NULL = vĩnh viễn)',
    is_active TINYINT(1) DEFAULT 1,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE INDEX idx_upo_user_perm (user_id, permission_id),
    INDEX idx_upo_user (user_id),
    INDEX idx_upo_active (is_active, expires_at),
    CONSTRAINT fk_upo_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_upo_permission FOREIGN KEY (permission_id) REFERENCES permissions(id) ON DELETE CASCADE,
    CONSTRAINT fk_upo_grantor FOREIGN KEY (granted_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =============================================================================
-- 7. VIEW: Tính toán quyền hiệu lực của 1 user
--    = (quyền từ role + kế thừa) + GRANT override - REVOKE override
--    Dùng trong code để check permission nhanh.
-- =============================================================================
CREATE OR REPLACE VIEW v_user_effective_permissions AS
WITH RECURSIVE role_tree AS (
    -- Base: role của user
    SELECT r.code AS role_code, r.code AS inherited_from
    FROM roles r
    UNION ALL
    -- Đệ quy lên cha: LECTURER → STUDENT, HEAD → LECTURER, ADMIN → HEAD
    SELECT rt.role_code, rh.parent_role_code
    FROM role_tree rt
    JOIN role_hierarchy rh ON rt.inherited_from = rh.child_role_code
),
user_base_permissions AS (
    -- Tất cả quyền user có từ role + kế thừa
    SELECT DISTINCT u.id AS user_id, rp.permission_id
    FROM users u
    JOIN role_tree rt ON u.role = rt.role_code
    JOIN role_permissions rp ON rp.role_code = rt.inherited_from
)
SELECT
    ubp.user_id,
    p.feature_key,
    p.permission_group,
    CASE
        WHEN revoke_override.id IS NOT NULL THEN 0
        WHEN grant_override.id IS NOT NULL THEN 1
        ELSE 1
    END AS is_granted,
    CASE
        WHEN revoke_override.id IS NOT NULL THEN 'REVOKED'
        WHEN grant_override.id IS NOT NULL THEN 'GRANTED_OVERRIDE'
        ELSE 'FROM_ROLE'
    END AS source
FROM user_base_permissions ubp
JOIN permissions p ON ubp.permission_id = p.id
LEFT JOIN user_permission_overrides revoke_override
    ON revoke_override.user_id = ubp.user_id
    AND revoke_override.permission_id = ubp.permission_id
    AND revoke_override.override_type = 'REVOKE'
    AND revoke_override.is_active = 1
    AND (revoke_override.expires_at IS NULL OR revoke_override.expires_at > NOW())
LEFT JOIN user_permission_overrides grant_override
    ON grant_override.user_id = ubp.user_id
    AND grant_override.permission_id = ubp.permission_id
    AND grant_override.override_type = 'GRANT'
    AND grant_override.is_active = 1
    AND (grant_override.expires_at IS NULL OR grant_override.expires_at > NOW())
UNION
-- Thêm GRANT overrides cho quyền user không có từ role
SELECT
    upo.user_id,
    p.feature_key,
    p.permission_group,
    1 AS is_granted,
    'GRANTED_OVERRIDE' AS source
FROM user_permission_overrides upo
JOIN permissions p ON upo.permission_id = p.id
WHERE upo.override_type = 'GRANT'
  AND upo.is_active = 1
  AND (upo.expires_at IS NULL OR upo.expires_at > NOW())
  AND NOT EXISTS (
      SELECT 1 FROM user_base_permissions ubp
      WHERE ubp.user_id = upo.user_id AND ubp.permission_id = upo.permission_id
  );

-- =============================================================================
-- DONE — RBAC Enhancement
-- =============================================================================
