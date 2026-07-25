-- =============================================================================
-- ULP — V36__rbac_permissions_backfill.sql
-- Backfill RBAC permission groups that did not exist when V4 was written.
-- Sprint 7 — ULP-11.8 (Admin RBAC Permission Management)
--
-- V4 (Sprint 0) seeded ~110 permissions across 24 groups, but LIBRARY, UPLOAD
-- and MODERATION shipped afterwards and were never added to the catalogue.
--
-- Role assignments below are DERIVED FROM THE CURRENT @PreAuthorize IN CODE —
-- nothing speculative is invented:
--   * LIBRARY    -> LibraryController, LessonTemplateController and
--                   LibraryAttachTargetsController all carry class-level
--                   @PreAuthorize(Roles.PREAUTH_LECTURER_OR_ABOVE), i.e.
--                   hasAnyRole('LECTURER','HEAD','ADMIN'). LECTURER is the
--                   lowest role admitted, so the permissions attach to
--                   LECTURER and HEAD/ADMIN inherit them via role_hierarchy.
--   * UPLOAD     -> the upload endpoints live on LibraryController
--                   (POST /lecturer/library/upload) and the lesson media
--                   controllers, all lecturer-or-above. Same reasoning.
--   * MODERATION -> LessonCommentsApiController is @PreAuthorize("isAuthenticated()")
--                   at class level, but hide/unhide/bulk delegate the real check
--                   to ClassAccessPolicy.isModerator = owning lecturer OR
--                   ADMIN/HEAD. LECTURER is again the lowest role that can
--                   moderate, so MODERATION attaches to LECTURER.
--
-- This migration is ADDITIVE ONLY. It never deletes or rewrites V4 seed rows.
-- Every statement is guarded so replaying it against an already-seeded
-- database leaves existing permissions, role assignments and overrides intact.
-- =============================================================================


-- =============================================================================
-- 1. PERMISSIONS — new catalogue entries for LIBRARY, UPLOAD, MODERATION
--    INSERT ... SELECT ... WHERE NOT EXISTS keeps the insert idempotent
--    without relying on INSERT IGNORE (which would also swallow real errors).
-- =============================================================================

-- ── LIBRARY ──────────────────────────────────────────────────────────────────
INSERT INTO permissions (feature_key, name, description, permission_group)
SELECT * FROM (
    SELECT 'library.view' AS fk,
           'Xem thư viện' AS nm,
           'Duyệt tài liệu và video trong thư viện cá nhân' AS ds,
           'LIBRARY' AS pg
    UNION ALL SELECT 'library.manage',
           'Quản lý thư viện',
           'Đổi tên và xoá tài nguyên trong thư viện',
           'LIBRARY'
    UNION ALL SELECT 'library.attach',
           'Đính kèm tài nguyên',
           'Gắn tài nguyên thư viện vào lớp / chương / bài học',
           'LIBRARY'
    UNION ALL SELECT 'library.template_manage',
           'Quản lý mẫu bài học',
           'Tạo, đổi tên, xoá và nhân bản mẫu bài học',
           'LIBRARY'
) AS src
WHERE NOT EXISTS (
    SELECT 1 FROM permissions p WHERE p.feature_key = src.fk
);

-- ── UPLOAD ───────────────────────────────────────────────────────────────────
INSERT INTO permissions (feature_key, name, description, permission_group)
SELECT * FROM (
    SELECT 'upload.library_asset' AS fk,
           'Tải tài nguyên thư viện' AS nm,
           'Upload tài liệu hoặc video vào thư viện' AS ds,
           'UPLOAD' AS pg
    UNION ALL SELECT 'upload.lesson_attachment',
           'Tải tệp đính kèm bài học',
           'Upload tệp đính kèm cho bài học',
           'UPLOAD'
    UNION ALL SELECT 'upload.lesson_video',
           'Tải video bài học',
           'Upload video MP4 cho bài học',
           'UPLOAD'
    UNION ALL SELECT 'upload.exam_image',
           'Tải ảnh đề thi',
           'Upload ảnh minh hoạ cho câu hỏi trong ngân hàng đề',
           'UPLOAD'
) AS src
WHERE NOT EXISTS (
    SELECT 1 FROM permissions p WHERE p.feature_key = src.fk
);

-- ── MODERATION ───────────────────────────────────────────────────────────────
INSERT INTO permissions (feature_key, name, description, permission_group)
SELECT * FROM (
    SELECT 'moderation.comment_hide' AS fk,
           'Ẩn bình luận' AS nm,
           'Ẩn bình luận khỏi tầm nhìn của sinh viên' AS ds,
           'MODERATION' AS pg
    UNION ALL SELECT 'moderation.comment_unhide',
           'Bỏ ẩn bình luận',
           'Khôi phục bình luận đã bị ẩn',
           'MODERATION'
    UNION ALL SELECT 'moderation.comment_bulk',
           'Kiểm duyệt hàng loạt',
           'Ẩn hoặc bỏ ẩn nhiều bình luận cùng lúc',
           'MODERATION'
    UNION ALL SELECT 'moderation.view_hidden',
           'Xem bình luận đã ẩn',
           'Thấy các bình luận bị ẩn trong luồng thảo luận',
           'MODERATION'
) AS src
WHERE NOT EXISTS (
    SELECT 1 FROM permissions p WHERE p.feature_key = src.fk
);


-- =============================================================================
-- 2. ROLE_PERMISSIONS — attach each new permission to exactly the role its
--    current @PreAuthorize requires, and to NO lower role.
--
--    All three groups resolve to LECTURER as the lowest admitted role.
--    HEAD and ADMIN inherit automatically through role_hierarchy
--    (STUDENT <- LECTURER <- HEAD <- ADMIN), so we deliberately do NOT
--    duplicate rows for HEAD/ADMIN — that matches the V4 seeding style
--    where each role carries only its OWN permissions.
-- =============================================================================

INSERT INTO role_permissions (role_code, permission_id)
SELECT 'LECTURER', p.id
FROM permissions p
WHERE p.permission_group IN ('LIBRARY', 'UPLOAD', 'MODERATION')
  AND NOT EXISTS (
      SELECT 1 FROM role_permissions rp
      WHERE rp.role_code = 'LECTURER' AND rp.permission_id = p.id
  );


-- =============================================================================
-- 3. PERMISSIONS — the permission guarding the RBAC management screens.
--    Placed in the SYSTEM group so it is automatically treated as a core
--    permission by AdminPermissionsGuard (SYSTEM + USER_MANAGE are frozen
--    for the ADMIN role to make self-lockout impossible).
-- =============================================================================

INSERT INTO permissions (feature_key, name, description, permission_group)
SELECT * FROM (
    SELECT 'system.permissions' AS fk,
           'Phân quyền' AS nm,
           'Quản lý ma trận vai trò × quyền và ngoại lệ quyền theo người dùng' AS ds,
           'SYSTEM' AS pg
) AS src
WHERE NOT EXISTS (
    SELECT 1 FROM permissions p WHERE p.feature_key = src.fk
);

-- Attach it to ADMIN only — no lower role manages permissions today.
INSERT INTO role_permissions (role_code, permission_id)
SELECT 'ADMIN', p.id
FROM permissions p
WHERE p.feature_key = 'system.permissions'
  AND NOT EXISTS (
      SELECT 1 FROM role_permissions rp
      WHERE rp.role_code = 'ADMIN' AND rp.permission_id = p.id
  );

-- =============================================================================
-- 4. AUDIT — permission_activities.
--    Mirrors the department_activities convention from V29. No existing
--    activity_* table can carry these events: activity_users.user_id is
--    NOT NULL, but a role x permission matrix change targets a ROLE and has
--    no target user at all. Hence target_user_id is nullable here — it is
--    filled for override writes and left NULL for matrix changes.
-- =============================================================================

CREATE TABLE permission_activities (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    type VARCHAR(50) NOT NULL,
    role_code VARCHAR(20) NULL,
    feature_key VARCHAR(100) NULL,
    target_user_id BIGINT NULL,
    message TEXT NULL,
    metadata TEXT NULL,
    performed_by BIGINT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_pact_type (type),
    INDEX idx_pact_role (role_code),
    INDEX idx_pact_target (target_user_id),
    INDEX idx_pact_created (created_at),
    CONSTRAINT fk_pact_target FOREIGN KEY (target_user_id)
        REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_pact_actor FOREIGN KEY (performed_by)
        REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =============================================================================
-- DONE — RBAC permission backfill
-- =============================================================================
