## Why

The `categories` table (course categories, two-level parent → child) exists in the schema (V1), is seeded (V2), and has RBAC permissions `category.view` / `category.manage` seeded for ADMIN (V4) — but there is no Java code or UI to manage it. ADMINs cannot currently create, edit, delete, or toggle course categories. This change delivers the missing management screen (issue #87, ULP-11.4).

## What Changes

- Add a new admin screen at `/admin/categories` (ADMIN only) that renders course categories as a two-level tree (parent → child).
- Add create / edit / delete / toggle-active operations for categories.
- Auto-generate a URL `slug` from the category name (Vietnamese diacritics stripped → kebab-case); on collision, append a numeric suffix (`-2`, `-3`, …).
- Enforce a hard two-level hierarchy: a child category cannot have children; a category may not be its own parent (no cycles); a category that already has children must remain a parent.
- Guard deletion: block deleting a parent that still has children, and block deleting any category still linked to a course via `course_categories`. Both surface an error toast.
- Deletion is a HARD delete (the `categories` table has no `is_deleted` column).
- Add a "Danh mục" (Categories) item to the admin sidebar with active-state highlighting.

No database migration is required — schema, seed data, and RBAC permissions already exist.

## Capabilities

### New Capabilities
- `admin-course-categories`: ADMIN management of two-level course categories — list (tree), create, edit, delete, toggle active, with slug generation and hierarchy/deletion guards.

### Modified Capabilities
<!-- None — no existing spec's requirements change. -->

## Impact

- **New code**:
  - `entities/Category.java` — JPA entity mapping the `categories` table (self-reference via `Long parentId`, no object relation, mirroring the `Section` entity pattern).
  - `features/admin/categories/repository/CategoryRepository.java` — queries by parent, child count, slug-existence check, course-link count.
  - `features/admin/categories/service/CategoryService.java` — CRUD + slugify + deletion guards + two-level enforcement.
  - `features/admin/categories/dto/CategoryDtos.java` — `CategoryRow` (tree view) and `CategoryForm` (record + bean-validation).
  - `features/admin/categories/controller/AdminCategoriesController.java` — list / new / create / edit / update / delete / toggle, `@PreAuthorize` ADMIN.
  - Slugify helper (Vietnamese diacritic removal + kebab-case) — no shared util exists (only a local copy in `ExcelParser`).
  - `templates/admin/categories.html`, `static/css/admin-categories.css`, `static/js/admin-categories.js`.
  - `features/admin/categories/AdminCategoriesIntegrationTest.java` (`@SpringBootTest` + `MockMvc`).
- **Modified code**:
  - `common/IConstant.java` — add `VIEW_ADMIN_CATEGORIES`, `TAB_CATEGORIES`, related `ATTR_*` and `MSG_*` keys.
  - `fragments/admin-sidebar.html` — add "Danh mục" nav item + active state.
  - `features/admin/controller/AdminController.java` — remove `categories` from the placeholder handler if present; set active tab.
- **Data**: reads `categories` (CRUD) and `course_categories` (read-only, to count links before allowing delete). No schema change.
- **Dependencies / systems**: none new. Spring Security CSRF protects all POSTs; Hibernate stays in `validate` mode.

### Out of scope
- Assigning categories to courses (`course_categories` is read-only here — used only to count links when blocking deletion).
- Nesting deeper than two levels.
- New database migrations.
