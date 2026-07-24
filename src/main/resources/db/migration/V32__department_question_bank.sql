SET NAMES utf8mb4;

CREATE TABLE question_bank_categories (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    department_id BIGINT NOT NULL,
    name VARCHAR(150) NOT NULL,
    description TEXT NULL,
    is_active TINYINT(1) DEFAULT 1,
    created_by BIGINT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE INDEX idx_qbc_dept_name (department_id, name),
    INDEX idx_qbc_department_active (department_id, is_active, name),
    CONSTRAINT fk_qbc_department FOREIGN KEY (department_id) REFERENCES departments(id) ON DELETE CASCADE,
    CONSTRAINT fk_qbc_creator FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE question_bank_items (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    department_id BIGINT NOT NULL,
    category_id BIGINT NOT NULL,
    contributor_id BIGINT NOT NULL,
    reviewed_by BIGINT NULL,
    question_type VARCHAR(20) NOT NULL CHECK (question_type IN ('MCQ','MR')),
    workflow_status VARCHAR(20) NOT NULL DEFAULT 'DRAFT'
        CHECK (workflow_status IN ('DRAFT','REVIEW','APPROVED','REJECTED','ARCHIVED')),
    content MEDIUMTEXT NOT NULL,
    explanation TEXT NULL,
    review_note TEXT NULL,
    approved_at DATETIME NULL,
    reviewed_at DATETIME NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_qbi_department_status (department_id, workflow_status, updated_at),
    INDEX idx_qbi_department_category (department_id, category_id),
    INDEX idx_qbi_contributor_status (contributor_id, workflow_status),
    INDEX idx_qbi_reviewer (reviewed_by),
    CONSTRAINT fk_qbi_department FOREIGN KEY (department_id) REFERENCES departments(id) ON DELETE CASCADE,
    CONSTRAINT fk_qbi_category FOREIGN KEY (category_id) REFERENCES question_bank_categories(id),
    CONSTRAINT fk_qbi_contributor FOREIGN KEY (contributor_id) REFERENCES users(id),
    CONSTRAINT fk_qbi_reviewer FOREIGN KEY (reviewed_by) REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE question_bank_options (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    item_id BIGINT NOT NULL,
    content MEDIUMTEXT NOT NULL,
    is_correct TINYINT(1) DEFAULT 0,
    sort_order INT DEFAULT 0,
    INDEX idx_qbo_item_order (item_id, sort_order),
    CONSTRAINT fk_qbo_item FOREIGN KEY (item_id) REFERENCES question_bank_items(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO permissions (feature_key, name, description, permission_group)
SELECT 'question_bank.category_manage', 'Quản lý danh mục ngân hàng câu hỏi',
       'Tạo/sửa/ẩn danh mục cho ngân hàng câu hỏi theo bộ môn', 'QUESTION_BANK'
WHERE NOT EXISTS (
    SELECT 1 FROM permissions WHERE feature_key = 'question_bank.category_manage'
);

INSERT INTO permissions (feature_key, name, description, permission_group)
SELECT 'question_bank.review', 'Duyệt ngân hàng câu hỏi',
       'Duyệt, trả lại, lưu trữ câu hỏi ngân hàng theo bộ môn', 'QUESTION_BANK'
WHERE NOT EXISTS (
    SELECT 1 FROM permissions WHERE feature_key = 'question_bank.review'
);

INSERT INTO role_permissions (role_code, permission_id)
SELECT 'HEAD', p.id
FROM permissions p
WHERE p.feature_key IN ('question_bank.category_manage', 'question_bank.review')
  AND NOT EXISTS (
      SELECT 1 FROM role_permissions rp
      WHERE rp.role_code = 'HEAD' AND rp.permission_id = p.id
  );

INSERT INTO role_permissions (role_code, permission_id)
SELECT 'ADMIN', p.id
FROM permissions p
WHERE p.feature_key IN ('question_bank.category_manage', 'question_bank.review')
  AND NOT EXISTS (
      SELECT 1 FROM role_permissions rp
      WHERE rp.role_code = 'ADMIN' AND rp.permission_id = p.id
  );
