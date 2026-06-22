-- =============================================================================
-- ULP — V3__activity_tables.sql
-- Bảng activity log cho từng entity chính
-- Mỗi entity cần tracking lifecycle sẽ có 1 bảng activity_<entity> riêng
-- Sprint 0 — Foundation
-- =============================================================================


-- =============================================================================
-- 1. Activity: Courses (khoá học)
--    Events: tạo mới, cập nhật, publish, archive, duyệt, từ chối
-- =============================================================================

CREATE TABLE activity_courses (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    course_id BIGINT NOT NULL,
    type VARCHAR(50) NOT NULL,
    description TEXT NULL,
    metadata JSON NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_ac_course (course_id, created_at),
    INDEX idx_ac_type (type),
    CONSTRAINT fk_ac_course FOREIGN KEY (course_id) REFERENCES courses(id) ON DELETE CASCADE,
    CONSTRAINT fk_ac_creator FOREIGN KEY (created_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =============================================================================
-- 2. Activity: Sections (chương)
--    Events: tạo mới, cập nhật, sắp xếp lại, xoá
-- =============================================================================

CREATE TABLE activity_sections (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    section_id BIGINT NOT NULL,
    type VARCHAR(50) NOT NULL,
    description TEXT NULL,
    metadata JSON NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_asec_section (section_id, created_at),
    CONSTRAINT fk_asec_section FOREIGN KEY (section_id) REFERENCES sections(id) ON DELETE CASCADE,
    CONSTRAINT fk_asec_creator FOREIGN KEY (created_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =============================================================================
-- 3. Activity: Lessons (bài học)
--    Events: tạo mới, cập nhật nội dung, publish, duyệt, từ chối, yêu cầu sửa
-- =============================================================================

CREATE TABLE activity_lessons (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    lesson_id BIGINT NOT NULL,
    type VARCHAR(50) NOT NULL,
    description TEXT NULL,
    metadata JSON NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_al_lesson (lesson_id, created_at),
    INDEX idx_al_type (type),
    CONSTRAINT fk_al_lesson FOREIGN KEY (lesson_id) REFERENCES lessons(id) ON DELETE CASCADE,
    CONSTRAINT fk_al_creator FOREIGN KEY (created_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =============================================================================
-- 4. Activity: Classes (lớp học)
--    Events: tạo mới, cập nhật, khai giảng, kết thúc, huỷ
-- =============================================================================

CREATE TABLE activity_classes (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    class_id BIGINT NOT NULL,
    type VARCHAR(50) NOT NULL,
    description TEXT NULL,
    metadata JSON NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_acl_class (class_id, created_at),
    INDEX idx_acl_type (type),
    CONSTRAINT fk_acl_class FOREIGN KEY (class_id) REFERENCES classes(id) ON DELETE CASCADE,
    CONSTRAINT fk_acl_creator FOREIGN KEY (created_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =============================================================================
-- 5. Activity: Enrollments (tham gia lớp)
--    Events: join, rời lớp, bị xoá khỏi lớp, hoàn thành
-- =============================================================================

CREATE TABLE activity_enrollments (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    enrollment_id BIGINT NOT NULL,
    type VARCHAR(50) NOT NULL,
    description TEXT NULL,
    metadata JSON NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_aenr_enrollment (enrollment_id, created_at),
    INDEX idx_aenr_type (type),
    CONSTRAINT fk_aenr_enrollment FOREIGN KEY (enrollment_id) REFERENCES enrollments(id) ON DELETE CASCADE,
    CONSTRAINT fk_aenr_creator FOREIGN KEY (created_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =============================================================================
-- 6. Activity: Tests (bài test)
--    Events: tạo mới, cập nhật, publish, archive
-- =============================================================================

CREATE TABLE activity_tests (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    test_id BIGINT NOT NULL,
    type VARCHAR(50) NOT NULL,
    description TEXT NULL,
    metadata JSON NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_atest_test (test_id, created_at),
    INDEX idx_atest_type (type),
    CONSTRAINT fk_atest_test FOREIGN KEY (test_id) REFERENCES tests(id) ON DELETE CASCADE,
    CONSTRAINT fk_atest_creator FOREIGN KEY (created_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =============================================================================
-- 7. Activity: Assignments (bài tập)
--    Events: tạo mới, cập nhật, publish, đóng
-- =============================================================================

CREATE TABLE activity_assignments (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    assignment_id BIGINT NOT NULL,
    type VARCHAR(50) NOT NULL,
    description TEXT NULL,
    metadata JSON NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_aasg_assignment (assignment_id, created_at),
    INDEX idx_aasg_type (type),
    CONSTRAINT fk_aasg_assignment FOREIGN KEY (assignment_id) REFERENCES assignments(id) ON DELETE CASCADE,
    CONSTRAINT fk_aasg_creator FOREIGN KEY (created_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =============================================================================
-- 8. Activity: Assignment Submissions (bài nộp)
--    Events: nộp bài, chấm điểm, cập nhật điểm, yêu cầu nộp lại
-- =============================================================================

CREATE TABLE activity_submissions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    submission_id BIGINT NOT NULL,
    type VARCHAR(50) NOT NULL,
    description TEXT NULL,
    metadata JSON NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_asub_submission (submission_id, created_at),
    INDEX idx_asub_type (type),
    CONSTRAINT fk_asub_submission FOREIGN KEY (submission_id) REFERENCES assignment_submissions(id) ON DELETE CASCADE,
    CONSTRAINT fk_asub_creator FOREIGN KEY (created_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =============================================================================
-- 9. Activity: Users (tài khoản)
--    Events: đổi vai trò, khoá/mở khoá, kích hoạt/vô hiệu hoá, đổi mật khẩu
-- =============================================================================

CREATE TABLE activity_users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    type VARCHAR(50) NOT NULL,
    description TEXT NULL,
    metadata JSON NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_au_user (user_id, created_at),
    INDEX idx_au_type (type),
    CONSTRAINT fk_au_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_au_creator FOREIGN KEY (created_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =============================================================================
-- 10. Activity: Comments (bình luận — moderation)
--     Events: duyệt, từ chối, ghim, bỏ ghim
-- =============================================================================

CREATE TABLE activity_comments (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    comment_id BIGINT NOT NULL,
    type VARCHAR(50) NOT NULL,
    description TEXT NULL,
    metadata JSON NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_acmt_comment (comment_id, created_at),
    INDEX idx_acmt_type (type),
    CONSTRAINT fk_acmt_comment FOREIGN KEY (comment_id) REFERENCES comments(id) ON DELETE CASCADE,
    CONSTRAINT fk_acmt_creator FOREIGN KEY (created_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =============================================================================
-- 11. Activity: Content Versions (phiên bản nội dung)
--     Events: submit để duyệt, duyệt, từ chối, yêu cầu chỉnh sửa
-- =============================================================================

CREATE TABLE activity_content_versions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    version_id BIGINT NOT NULL,
    type VARCHAR(50) NOT NULL,
    description TEXT NULL,
    metadata JSON NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_av_version (version_id, created_at),
    INDEX idx_av_type (type),
    CONSTRAINT fk_av_version FOREIGN KEY (version_id) REFERENCES content_versions(id) ON DELETE CASCADE,
    CONSTRAINT fk_av_creator FOREIGN KEY (created_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =============================================================================
-- 12. Activity: Flashcard Decks (bộ flashcard)
--     Events: tạo mới, cập nhật, chia sẻ, đánh dấu chính thức, xoá
-- =============================================================================

CREATE TABLE activity_flashcard_decks (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    deck_id BIGINT NOT NULL,
    type VARCHAR(50) NOT NULL,
    description TEXT NULL,
    metadata JSON NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_afd_deck (deck_id, created_at),
    INDEX idx_afd_type (type),
    CONSTRAINT fk_afd_deck FOREIGN KEY (deck_id) REFERENCES flashcard_decks(id) ON DELETE CASCADE,
    CONSTRAINT fk_afd_creator FOREIGN KEY (created_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =============================================================================
-- 13. Activity: Departments (bộ môn)
--     Events: tạo mới, cập nhật, đổi trưởng bộ môn
-- =============================================================================

CREATE TABLE activity_departments (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    department_id BIGINT NOT NULL,
    type VARCHAR(50) NOT NULL,
    description TEXT NULL,
    metadata JSON NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_adep_department (department_id, created_at),
    CONSTRAINT fk_adep_department FOREIGN KEY (department_id) REFERENCES departments(id) ON DELETE CASCADE,
    CONSTRAINT fk_adep_creator FOREIGN KEY (created_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =============================================================================
-- TỔNG KẾT: 13 bảng activity_*
-- =============================================================================
-- activity_courses            — Khoá học
-- activity_sections           — Chương
-- activity_lessons            — Bài học (publish/duyệt/từ chối)
-- activity_classes            — Lớp học
-- activity_enrollments        — Tham gia lớp
-- activity_tests              — Bài test
-- activity_assignments        — Bài tập
-- activity_submissions        — Bài nộp (chấm điểm)
-- activity_users              — Tài khoản (đổi role, lock/unlock)
-- activity_comments           — Bình luận (moderation)
-- activity_content_versions   — Phiên bản nội dung (duyệt/từ chối)
-- activity_flashcard_decks    — Bộ flashcard
-- activity_departments        — Bộ môn
-- =============================================================================
