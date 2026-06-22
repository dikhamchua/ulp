-- =============================================================================
-- ULP — V1__init_schema.sql
-- Khởi tạo toàn bộ schema cho University Learning Platform
-- MySQL 8.0 · utf8mb4 · InnoDB
-- Sprint 0 — Foundation
-- =============================================================================

CREATE DATABASE IF NOT EXISTS ulp_db
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;


-- =============================================================================
-- 1. IDENTITY & AUTH
-- =============================================================================

-- 1.1 Users — Tài khoản người dùng
CREATE TABLE users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    email VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    full_name VARCHAR(150) NOT NULL,
    role VARCHAR(20) NOT NULL CHECK (role IN ('STUDENT','LECTURER','HEAD','ADMIN')),
    department_id BIGINT NULL,
    avatar_url VARCHAR(500) NULL,
    bio TEXT NULL,
    phone VARCHAR(20) NULL,
    is_email_verified TINYINT(1) DEFAULT 0,
    is_active TINYINT(1) DEFAULT 1,
    is_locked TINYINT(1) DEFAULT 0,
    locked_reason VARCHAR(255) NULL,
    last_login_at DATETIME NULL,
    google_id VARCHAR(100) NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted TINYINT(1) DEFAULT 0,
    UNIQUE INDEX idx_users_email (email),
    UNIQUE INDEX idx_users_google_id (google_id),
    INDEX idx_users_role (role),
    INDEX idx_users_department_id (department_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 1.2 User Verification Tokens — Xác thực email
CREATE TABLE user_verification_tokens (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    token VARCHAR(128) NOT NULL,
    expires_at DATETIME NOT NULL,
    used_at DATETIME NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE INDEX idx_vtoken_token (token),
    INDEX idx_vtoken_user (user_id),
    CONSTRAINT fk_vtoken_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 1.3 Password Reset Tokens
CREATE TABLE password_reset_tokens (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    token VARCHAR(128) NOT NULL,
    expires_at DATETIME NOT NULL,
    used_at DATETIME NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE INDEX idx_preset_token (token),
    INDEX idx_preset_user (user_id),
    CONSTRAINT fk_preset_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 1.4 OAuth Providers
CREATE TABLE user_oauth_providers (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    provider VARCHAR(30) NOT NULL,
    provider_user_id VARCHAR(255) NOT NULL,
    access_token TEXT NULL,
    refresh_token TEXT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE INDEX idx_oauth_provider (provider, provider_user_id),
    CONSTRAINT fk_oauth_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 1.5 Login History
CREATE TABLE login_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NULL,
    ip_address VARCHAR(45) NULL,
    user_agent VARCHAR(500) NULL,
    login_status VARCHAR(20) NOT NULL CHECK (login_status IN ('SUCCESS','FAILED','LOCKED')),
    failure_reason VARCHAR(255) NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_login_user (user_id),
    INDEX idx_login_created (created_at),
    CONSTRAINT fk_login_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =============================================================================
-- 2. ORGANIZATION
-- =============================================================================

-- 2.1 Departments — Bộ môn
CREATE TABLE departments (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    code VARCHAR(20) NOT NULL,
    description TEXT NULL,
    head_user_id BIGINT NULL,
    is_active TINYINT(1) DEFAULT 1,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE INDEX idx_dept_code (code),
    INDEX idx_dept_head (head_user_id),
    CONSTRAINT fk_dept_head FOREIGN KEY (head_user_id) REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Add FK từ users.department_id → departments (cần tạo departments trước)
ALTER TABLE users
    ADD CONSTRAINT fk_user_department FOREIGN KEY (department_id) REFERENCES departments(id) ON DELETE SET NULL;

-- 2.2 Categories — Danh mục khoá học
CREATE TABLE categories (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(150) NOT NULL,
    slug VARCHAR(150) NOT NULL,
    parent_id BIGINT NULL,
    description TEXT NULL,
    is_active TINYINT(1) DEFAULT 1,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE INDEX idx_cat_slug (slug),
    INDEX idx_cat_parent (parent_id),
    CONSTRAINT fk_cat_parent FOREIGN KEY (parent_id) REFERENCES categories(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =============================================================================
-- 3. COURSES & CLASSES
-- =============================================================================

-- 3.1 Courses — Khoá học
CREATE TABLE courses (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(300) NOT NULL,
    slug VARCHAR(300) NOT NULL,
    description TEXT NULL,
    image_url VARCHAR(500) NULL,
    department_id BIGINT NOT NULL,
    created_by BIGINT NOT NULL,
    status VARCHAR(20) DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','PUBLISHED','ARCHIVED')),
    is_active TINYINT(1) DEFAULT 1,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted TINYINT(1) DEFAULT 0,
    UNIQUE INDEX idx_course_slug (slug),
    INDEX idx_course_dept (department_id),
    INDEX idx_course_status (status),
    INDEX idx_course_created_by (created_by),
    CONSTRAINT fk_course_dept FOREIGN KEY (department_id) REFERENCES departments(id),
    CONSTRAINT fk_course_creator FOREIGN KEY (created_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 3.2 Course ↔ Categories (M:N)
CREATE TABLE course_categories (
    course_id BIGINT NOT NULL,
    category_id BIGINT NOT NULL,
    PRIMARY KEY (course_id, category_id),
    CONSTRAINT fk_cc_course FOREIGN KEY (course_id) REFERENCES courses(id) ON DELETE CASCADE,
    CONSTRAINT fk_cc_category FOREIGN KEY (category_id) REFERENCES categories(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 3.3 Sections — Chương
CREATE TABLE sections (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    course_id BIGINT NOT NULL,
    title VARCHAR(300) NOT NULL,
    description TEXT NULL,
    sort_order INT DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_section_course (course_id, sort_order),
    CONSTRAINT fk_section_course FOREIGN KEY (course_id) REFERENCES courses(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 3.4 Classes — Lớp học
CREATE TABLE classes (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    course_id BIGINT NOT NULL,
    name VARCHAR(300) NOT NULL,
    lecturer_id BIGINT NOT NULL,
    start_date DATE NULL,
    end_date DATE NULL,
    max_students INT DEFAULT 100,
    status VARCHAR(20) DEFAULT 'UPCOMING' CHECK (status IN ('UPCOMING','ACTIVE','COMPLETED','CANCELLED')),
    description TEXT NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted TINYINT(1) DEFAULT 0,
    INDEX idx_class_course (course_id),
    INDEX idx_class_lecturer (lecturer_id),
    INDEX idx_class_status (status),
    CONSTRAINT fk_class_course FOREIGN KEY (course_id) REFERENCES courses(id),
    CONSTRAINT fk_class_lecturer FOREIGN KEY (lecturer_id) REFERENCES users(id),
    CONSTRAINT fk_class_creator FOREIGN KEY (created_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 3.5 Class Invite Codes
CREATE TABLE class_invite_codes (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    class_id BIGINT NOT NULL,
    code VARCHAR(20) NOT NULL,
    type VARCHAR(10) DEFAULT 'CODE' CHECK (type IN ('CODE','LINK')),
    is_active TINYINT(1) DEFAULT 1,
    max_uses INT NULL,
    use_count INT DEFAULT 0,
    expires_at DATETIME NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE INDEX idx_ic_code (code),
    INDEX idx_ic_class (class_id),
    CONSTRAINT fk_ic_class FOREIGN KEY (class_id) REFERENCES classes(id) ON DELETE CASCADE,
    CONSTRAINT fk_ic_creator FOREIGN KEY (created_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =============================================================================
-- 4. ENROLLMENT
-- =============================================================================

CREATE TABLE enrollments (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    class_id BIGINT NOT NULL,
    status VARCHAR(20) DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','REMOVED','COMPLETED')),
    joined_via VARCHAR(20) NULL CHECK (joined_via IN ('CODE','LINK','IMPORT','MANUAL')),
    invite_code_id BIGINT NULL,
    joined_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    completed_at DATETIME NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE INDEX idx_enroll_user_class (user_id, class_id),
    INDEX idx_enroll_class (class_id),
    INDEX idx_enroll_status (status),
    CONSTRAINT fk_enroll_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_enroll_class FOREIGN KEY (class_id) REFERENCES classes(id),
    CONSTRAINT fk_enroll_invite FOREIGN KEY (invite_code_id) REFERENCES class_invite_codes(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =============================================================================
-- 5. LESSONS & CONTENT
-- =============================================================================

-- 5.1 Lessons — Bài học
CREATE TABLE lessons (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    section_id BIGINT NOT NULL,
    title VARCHAR(300) NOT NULL,
    type VARCHAR(20) DEFAULT 'RICH_TEXT' CHECK (type IN ('RICH_TEXT','PDF','VIDEO')),
    sort_order INT DEFAULT 0,
    status VARCHAR(20) DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','PUBLISHED','ARCHIVED')),
    estimated_minutes INT NULL,
    created_by BIGINT NOT NULL,
    published_at DATETIME NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted TINYINT(1) DEFAULT 0,
    INDEX idx_lesson_section (section_id, sort_order),
    INDEX idx_lesson_status (status),
    CONSTRAINT fk_lesson_section FOREIGN KEY (section_id) REFERENCES sections(id) ON DELETE CASCADE,
    CONSTRAINT fk_lesson_creator FOREIGN KEY (created_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 5.2 Lesson Contents — Nội dung bài học (1-1 với lesson)
CREATE TABLE lesson_contents (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    lesson_id BIGINT NOT NULL UNIQUE,
    content_type VARCHAR(20) NOT NULL CHECK (content_type IN ('RICH_TEXT','PDF','VIDEO_URL')),
    body_html MEDIUMTEXT NULL,
    body_markdown MEDIUMTEXT NULL,
    file_url VARCHAR(500) NULL,
    file_size BIGINT NULL,
    duration_seconds INT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_lc_lesson FOREIGN KEY (lesson_id) REFERENCES lessons(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 5.3 Lesson Attachments
CREATE TABLE lesson_attachments (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    lesson_id BIGINT NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    file_url VARCHAR(500) NOT NULL,
    file_size BIGINT NOT NULL,
    mime_type VARCHAR(100) NULL,
    sort_order INT DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_attach_lesson (lesson_id),
    CONSTRAINT fk_attach_lesson FOREIGN KEY (lesson_id) REFERENCES lessons(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 5.4 Learning Progress
CREATE TABLE learning_progress (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    lesson_id BIGINT NOT NULL,
    status VARCHAR(20) DEFAULT 'NOT_STARTED' CHECK (status IN ('NOT_STARTED','IN_PROGRESS','COMPLETED')),
    progress_percent DECIMAL(5,2) DEFAULT 0,
    started_at DATETIME NULL,
    completed_at DATETIME NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE INDEX idx_lp_user_lesson (user_id, lesson_id),
    INDEX idx_lp_user (user_id),
    INDEX idx_lp_lesson (lesson_id),
    CONSTRAINT fk_lp_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_lp_lesson FOREIGN KEY (lesson_id) REFERENCES lessons(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =============================================================================
-- 6. CONTENT VERSIONS
-- =============================================================================

CREATE TABLE content_versions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    entity_type VARCHAR(30) NOT NULL CHECK (entity_type IN ('LESSON','TEST','ASSIGNMENT')),
    entity_id BIGINT NOT NULL,
    version_number INT NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('DRAFT','SUBMITTED','APPROVED','PUBLISHED','REJECTED')),
    content_snapshot MEDIUMTEXT NOT NULL,
    change_summary VARCHAR(500) NULL,
    created_by BIGINT NOT NULL,
    reviewed_by BIGINT NULL,
    reviewed_at DATETIME NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_cv_entity (entity_type, entity_id),
    INDEX idx_cv_status (status),
    CONSTRAINT fk_cv_creator FOREIGN KEY (created_by) REFERENCES users(id),
    CONSTRAINT fk_cv_reviewer FOREIGN KEY (reviewed_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =============================================================================
-- 7. DISCUSSIONS (Q&A)
-- =============================================================================

-- 7.1 Comments
CREATE TABLE comments (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    lesson_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    parent_id BIGINT NULL,
    content TEXT NOT NULL,
    is_edited TINYINT(1) DEFAULT 0,
    is_pinned TINYINT(1) DEFAULT 0,
    moderation_status VARCHAR(20) DEFAULT 'APPROVED' CHECK (moderation_status IN ('PENDING','APPROVED','REJECTED')),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted TINYINT(1) DEFAULT 0,
    INDEX idx_cmt_lesson (lesson_id, created_at),
    INDEX idx_cmt_parent (parent_id),
    INDEX idx_cmt_mod (moderation_status),
    CONSTRAINT fk_cmt_lesson FOREIGN KEY (lesson_id) REFERENCES lessons(id),
    CONSTRAINT fk_cmt_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_cmt_parent FOREIGN KEY (parent_id) REFERENCES comments(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 7.2 Comment Moderation Log
CREATE TABLE comment_moderation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    comment_id BIGINT NOT NULL,
    moderated_by BIGINT NOT NULL,
    action VARCHAR(20) NOT NULL CHECK (action IN ('APPROVED','REJECTED')),
    reason VARCHAR(500) NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_cmod_comment FOREIGN KEY (comment_id) REFERENCES comments(id) ON DELETE CASCADE,
    CONSTRAINT fk_cmod_moderator FOREIGN KEY (moderated_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =============================================================================
-- 8. FLASHCARDS
-- =============================================================================

-- 8.1 Flashcard Decks
CREATE TABLE flashcard_decks (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(300) NOT NULL,
    description TEXT NULL,
    course_id BIGINT NULL,
    class_id BIGINT NULL,
    owner_id BIGINT NOT NULL,
    visibility VARCHAR(20) DEFAULT 'PRIVATE' CHECK (visibility IN ('PRIVATE','SHARED','OFFICIAL')),
    is_official TINYINT(1) DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted TINYINT(1) DEFAULT 0,
    INDEX idx_fd_owner (owner_id),
    INDEX idx_fd_course (course_id),
    INDEX idx_fd_class (class_id),
    CONSTRAINT fk_fd_owner FOREIGN KEY (owner_id) REFERENCES users(id),
    CONSTRAINT fk_fd_course FOREIGN KEY (course_id) REFERENCES courses(id) ON DELETE SET NULL,
    CONSTRAINT fk_fd_class FOREIGN KEY (class_id) REFERENCES classes(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 8.2 Flashcards
CREATE TABLE flashcards (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    deck_id BIGINT NOT NULL,
    front_text TEXT NOT NULL,
    back_text TEXT NOT NULL,
    sort_order INT DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_fc_deck (deck_id, sort_order),
    CONSTRAINT fk_fc_deck FOREIGN KEY (deck_id) REFERENCES flashcard_decks(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 8.3 Flashcard Reviews (SM-2)
CREATE TABLE flashcard_reviews (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    flashcard_id BIGINT NOT NULL,
    quality TINYINT NOT NULL CHECK (quality BETWEEN 0 AND 5),
    easiness_factor DECIMAL(5,2) DEFAULT 2.50,
    interval_days INT DEFAULT 1,
    repetitions INT DEFAULT 0,
    next_review_at DATETIME NOT NULL,
    reviewed_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_fr_user_card (user_id, flashcard_id),
    INDEX idx_fr_next (user_id, next_review_at),
    CONSTRAINT fk_fr_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_fr_card FOREIGN KEY (flashcard_id) REFERENCES flashcards(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =============================================================================
-- 9. TESTS
-- =============================================================================

-- 9.1 Tests
CREATE TABLE tests (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(300) NOT NULL,
    description TEXT NULL,
    course_id BIGINT NULL,
    class_id BIGINT NULL,
    type VARCHAR(20) NOT NULL CHECK (type IN ('MOCK','MODULE','PRACTICE')),
    duration_minutes INT NULL,
    passing_score DECIMAL(5,2) NULL,
    total_questions INT DEFAULT 0,
    shuffle_questions TINYINT(1) DEFAULT 0,
    shuffle_options TINYINT(1) DEFAULT 0,
    status VARCHAR(20) DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','PUBLISHED','ARCHIVED')),
    created_by BIGINT NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted TINYINT(1) DEFAULT 0,
    INDEX idx_test_course (course_id),
    INDEX idx_test_class (class_id),
    INDEX idx_test_type (type),
    INDEX idx_test_status (status),
    CONSTRAINT fk_test_course FOREIGN KEY (course_id) REFERENCES courses(id) ON DELETE SET NULL,
    CONSTRAINT fk_test_class FOREIGN KEY (class_id) REFERENCES classes(id) ON DELETE SET NULL,
    CONSTRAINT fk_test_creator FOREIGN KEY (created_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 9.2 Questions
CREATE TABLE questions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    test_id BIGINT NOT NULL,
    question_type VARCHAR(20) NOT NULL CHECK (question_type IN ('MCQ','MR','FILL_IN','MATCHING')),
    content TEXT NOT NULL,
    explanation TEXT NULL,
    points DECIMAL(5,2) DEFAULT 1,
    sort_order INT DEFAULT 0,
    image_url VARCHAR(500) NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_q_test (test_id, sort_order),
    CONSTRAINT fk_q_test FOREIGN KEY (test_id) REFERENCES tests(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 9.3 Question Options
CREATE TABLE question_options (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    question_id BIGINT NOT NULL,
    content TEXT NOT NULL,
    is_correct TINYINT(1) DEFAULT 0,
    sort_order INT DEFAULT 0,
    match_key VARCHAR(50) NULL,
    INDEX idx_qo_question (question_id, sort_order),
    CONSTRAINT fk_qo_question FOREIGN KEY (question_id) REFERENCES questions(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 9.4 Test Attempts
CREATE TABLE test_attempts (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    test_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    status VARCHAR(20) DEFAULT 'IN_PROGRESS' CHECK (status IN ('IN_PROGRESS','SUBMITTED','TIMED_OUT')),
    score DECIMAL(6,2) NULL,
    total_points DECIMAL(6,2) NULL,
    correct_count INT NULL,
    total_questions INT NULL,
    started_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    submitted_at DATETIME NULL,
    time_spent_seconds INT NULL,
    INDEX idx_ta_test (test_id),
    INDEX idx_ta_user (user_id),
    INDEX idx_ta_user_test (user_id, test_id),
    CONSTRAINT fk_ta_test FOREIGN KEY (test_id) REFERENCES tests(id),
    CONSTRAINT fk_ta_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 9.5 Test Responses
CREATE TABLE test_responses (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    attempt_id BIGINT NOT NULL,
    question_id BIGINT NOT NULL,
    selected_option_ids JSON NULL,
    fill_in_text VARCHAR(500) NULL,
    matching_pairs JSON NULL,
    is_correct TINYINT(1) NULL,
    points_earned DECIMAL(5,2) NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_tr_attempt (attempt_id),
    CONSTRAINT fk_tr_attempt FOREIGN KEY (attempt_id) REFERENCES test_attempts(id) ON DELETE CASCADE,
    CONSTRAINT fk_tr_question FOREIGN KEY (question_id) REFERENCES questions(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =============================================================================
-- 10. ASSIGNMENTS
-- =============================================================================

-- 10.1 Assignments
CREATE TABLE assignments (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    class_id BIGINT NOT NULL,
    title VARCHAR(300) NOT NULL,
    description TEXT NOT NULL,
    rubric JSON NULL,
    max_score DECIMAL(5,2) DEFAULT 100,
    due_date DATETIME NULL,
    allow_late_submission TINYINT(1) DEFAULT 0,
    attachment_url VARCHAR(500) NULL,
    status VARCHAR(20) DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','PUBLISHED','CLOSED')),
    created_by BIGINT NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted TINYINT(1) DEFAULT 0,
    INDEX idx_asg_class (class_id),
    INDEX idx_asg_due (due_date),
    CONSTRAINT fk_asg_class FOREIGN KEY (class_id) REFERENCES classes(id),
    CONSTRAINT fk_asg_creator FOREIGN KEY (created_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 10.2 Assignment Submissions
CREATE TABLE assignment_submissions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    assignment_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    content TEXT NULL,
    attachment_url VARCHAR(500) NULL,
    status VARCHAR(20) DEFAULT 'SUBMITTED' CHECK (status IN ('DRAFT','SUBMITTED','GRADED')),
    is_late TINYINT(1) DEFAULT 0,
    submitted_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE INDEX idx_sub_asg_user (assignment_id, user_id),
    INDEX idx_sub_asg (assignment_id),
    CONSTRAINT fk_sub_asg FOREIGN KEY (assignment_id) REFERENCES assignments(id) ON DELETE CASCADE,
    CONSTRAINT fk_sub_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 10.3 Assignment Feedback
CREATE TABLE assignment_feedback (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    submission_id BIGINT NOT NULL UNIQUE,
    graded_by BIGINT NOT NULL,
    score DECIMAL(5,2) NOT NULL,
    feedback TEXT NULL,
    rubric_scores JSON NULL,
    is_ai_generated TINYINT(1) DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_af_submission FOREIGN KEY (submission_id) REFERENCES assignment_submissions(id) ON DELETE CASCADE,
    CONSTRAINT fk_af_grader FOREIGN KEY (graded_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =============================================================================
-- 11. COMMUNICATION
-- =============================================================================

-- 11.1 Notifications
CREATE TABLE notifications (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    title VARCHAR(300) NOT NULL,
    content TEXT NOT NULL,
    type VARCHAR(30) NOT NULL,
    reference_type VARCHAR(30) NULL,
    reference_id BIGINT NULL,
    is_read TINYINT(1) DEFAULT 0,
    read_at DATETIME NULL,
    is_email_sent TINYINT(1) DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_noti_user_read (user_id, is_read, created_at),
    CONSTRAINT fk_noti_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 11.2 Messages
CREATE TABLE messages (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sender_id BIGINT NOT NULL,
    receiver_id BIGINT NOT NULL,
    content TEXT NOT NULL,
    is_read TINYINT(1) DEFAULT 0,
    read_at DATETIME NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_msg_sender (sender_id),
    INDEX idx_msg_receiver_read (receiver_id, is_read),
    INDEX idx_msg_conversation (sender_id, receiver_id, created_at),
    CONSTRAINT fk_msg_sender FOREIGN KEY (sender_id) REFERENCES users(id),
    CONSTRAINT fk_msg_receiver FOREIGN KEY (receiver_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =============================================================================
-- 12. ADMIN & SYSTEM
-- =============================================================================

-- 12.1 System Settings
CREATE TABLE system_settings (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    setting_key VARCHAR(100) NOT NULL,
    setting_value TEXT NOT NULL,
    setting_group VARCHAR(50) NOT NULL,
    description VARCHAR(300) NULL,
    is_encrypted TINYINT(1) DEFAULT 0,
    updated_by BIGINT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE INDEX idx_ss_key (setting_key),
    INDEX idx_ss_group (setting_group),
    CONSTRAINT fk_ss_updater FOREIGN KEY (updated_by) REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 12.2 Feature Permissions
CREATE TABLE feature_permissions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    role VARCHAR(20) NOT NULL CHECK (role IN ('STUDENT','LECTURER','HEAD','ADMIN')),
    feature_key VARCHAR(100) NOT NULL,
    is_granted TINYINT(1) DEFAULT 1,
    updated_by BIGINT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE INDEX idx_fp_role_feature (role, feature_key),
    CONSTRAINT fk_fp_updater FOREIGN KEY (updated_by) REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =============================================================================
-- DEFAULT SYSTEM SETTINGS
-- =============================================================================

INSERT INTO system_settings (setting_key, setting_value, setting_group, description) VALUES
('site.name', 'University Learning Platform', 'GENERAL', 'Tên hệ thống'),
('site.description', 'Nền tảng học tập trực tuyến', 'GENERAL', 'Mô tả hệ thống'),
('site.logo_url', '/images/logo.png', 'GENERAL', 'Đường dẫn logo'),
('site.contact_email', 'contact@ulp.edu.vn', 'GENERAL', 'Email liên hệ'),
('smtp.host', 'smtp.gmail.com', 'SMTP', 'SMTP host'),
('smtp.port', '587', 'SMTP', 'SMTP port'),
('smtp.username', '', 'SMTP', 'SMTP username (để trống, cấu hình sau)'),
('smtp.password', '', 'SMTP', 'SMTP password (để trống, cấu hình sau)'),
('smtp.from_email', 'noreply@ulp.edu.vn', 'SMTP', 'Email gửi đi'),
('oauth.google.client_id', '', 'OAUTH', 'Google OAuth Client ID (cấu hình sau)'),
('oauth.google.client_secret', '', 'OAUTH', 'Google OAuth Client Secret (cấu hình sau)'),
('ai.provider', '', 'AI', 'AI provider (OPENAI, GEMINI, ...)'),
('ai.api_key', '', 'AI', 'AI API Key (cấu hình sau)');

-- =============================================================================
-- DONE — Schema version 1
-- =============================================================================
