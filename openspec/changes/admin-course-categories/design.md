## Context

The `categories` and `course_categories` tables already exist (V1 schema), are seeded with a two-level tree (V2), and the RBAC permissions `category.view` / `category.manage` are seeded for ADMIN (V4). No Java code or template consumes them yet. This change wires the admin UI + backend for managing that tree. No new migration is required.

Relevant schema facts:
- `categories(id, name, slug UNIQUE, parent_id NULL, description, is_active, created_at, updated_at)`.
- `parent_id` FK self-references `categories(id)` with `ON DELETE SET NULL`.
- There is **no** `is_deleted` column → deletion is a hard `DELETE`.
- `course_categories(course_id, category_id)` is M:N with FK `ON DELETE CASCADE`.

The project is SSR (Spring MVC + Thymeleaf), Hibernate in `validate` mode (Flyway owns schema), notifications via `UlpToast`, controller magic strings via `com.ulp.common.IConstant` (static import), and DTO boundaries (entities never leak to controllers). The closest existing pattern is the `/admin/users` feature (list + create + lifecycle controllers, `AdminUsersFormSupport`, DTO records with bean-validation).

## Goals / Non-Goals

**Goals:**
- ADMIN-only CRUD + active toggle for course categories over the existing tables.
- Enforce a hard two-level tree (parent → child, no deeper).
- Auto-generate unique slugs from Vietnamese names.
- Block unsafe deletes (has children, or still linked to courses).
- Follow existing conventions: IConstant, DTO boundary, UlpToast, English comments.

**Non-Goals:**
- Assigning categories to courses (course_categories is read-only here, used only to count links when blocking a delete).
- Nesting deeper than two levels.
- Any new Flyway migration or schema change.
- Localised MessageSource (flash strings stay in IConstant as Vietnamese, per existing pattern).

## Decisions

### Entity maps parent_id as a scalar `Long`, not an object relation
Mirror the existing `Section` entity: map `parent_id` as a plain `Long parentId` column, not a `@ManyToOne Category parent`. Rationale: avoids Hibernate lazy-loading loops and keeps the entity simple; the service composes the tree with explicit repository queries. Alternative (self `@ManyToOne`/`@OneToMany`) rejected — heavier, and inconsistent with how Section models its ordering/parenting.

### Hard delete, guarded in the service layer
No `is_deleted` column exists, so delete is a real `DELETE`. The service performs two guard queries before deleting: `countByParentId(id) == 0` and `countCourseLinks(id) == 0`. Both must pass or the service throws a domain exception the controller translates into an error toast. Relying on the DB FK alone is insufficient — `parent_id` is `ON DELETE SET NULL` (would silently orphan children) and `course_categories` is `ON DELETE CASCADE` (would silently unlink courses). The guards make the "block" behavior explicit and correct.

### Two-level invariant enforced in the service
Three rules, all validated server-side (not just UI):
1. A chosen parent MUST have `parent_id = NULL` (can't nest under a child).
2. A category that already has children can't be given a parent (would create depth 3).
3. A category can't be its own parent (self-loop).
The parent dropdown only lists top-level categories, but the service re-validates because form data is untrusted.

### Slugify utility
No shared slugify helper exists (only a local one in `ExcelParser`). Add a small `slugify(String)` — Unicode NFD normalize, strip combining marks, map `đ/Đ`→`d`, lowercase, non-alphanumeric → `-`, collapse/trim dashes. Placement: a dedicated `com.ulp.utils.Slugify` (single responsibility, testable) rather than swelling `StringUtils`. Uniqueness suffixing (`-2`, `-3`) lives in the service, which knows the repository.

### Controller split
Single `AdminCategoriesController` under `/admin/categories` with GET list, GET/POST create, GET/POST edit, POST delete, POST toggle. The users feature splits across three controllers because it has many lifecycle actions; categories has a smaller surface, so one controller stays under the file-size guideline. If it grows past ~200 lines, extract a form-support helper like `AdminUsersFormSupport`.

### DTOs
- `CategoryRow` — flat view row (id, name, slug, active, isParent, childCount, courseLinkCount) used to render the tree; service builds parent rows each carrying their children.
- `CategoryForm` — a record with bean-validation (`@NotBlank` name, `@Size` bounds, optional `parentId`, `description`, `active`). `empty()` factory like `CreateUserForm`.

## Risks / Trade-offs

- [Hard delete is irreversible] → Guard queries block the only two unsafe cases (children, course links); a leaf with no links is safe to remove and matches admin intent. No soft-delete column exists to fall back on.
- [Concurrent create → slug race] → Two admins creating the same name simultaneously could both compute the same suffix. Mitigation: the DB `UNIQUE(slug)` constraint is the final arbiter; the service catches the constraint violation and retries suffixing. Low likelihood for an admin-only screen.
- [Toggle via POST without a full form] → Use a small POST form + CSRF token per row (Spring Security requires the token); no GET state change. Consistent with the users lifecycle actions.
- [parent_id ON DELETE SET NULL mismatch with our block rule] → We never rely on the cascade; the service refuses the delete before it reaches the DB, so the SET NULL path is effectively unreachable for parents with children.

## Migration Plan

No schema migration. Deployment is code-only. Rollback = revert the code; tables and seed data are untouched. Hibernate stays in `validate` mode; the new `Category` entity must match the existing column names exactly or startup validation fails (this is the intended safety net).
