# Design: sprint3-progress-and-lesson-comments

## Context

The student lessons page (`/my/classes/{classId}/lessons`, template `templates/student/class-lessons.html`, single-template 3-column layout) already renders sections, a lesson rail, and an inlined lesson detail resolved by `StudentLessonDetailService` behind four authz gates. The `learning_progress` and `comments` tables exist since `V1__init_schema.sql`; a live-DB check confirmed their FKs (`fk_lp_lesson`, `fk_cmt_lesson`) still reference the `lessons` table recreated in V14, so **no migration is required**. No Java code exists for either feature. Established house patterns: feature-first packages, entities in `com.ulp.entities` (plain JPA, `@SQLRestriction` for soft-delete, no Lombok `@Data`), `IConstant` static imports in controllers, `AjaxResponses` + `AjaxResult` JSON envelope for AJAX endpoints, CSRF via `_csrf` meta tags, `UlpToast` for all user feedback, integration tests with `@SpringBootTest @Transactional` against MySQL.

## Goals / Non-Goals

**Goals:**
- ULP-4.5: record and display per-student lesson completion (% per section + whole class) — GitHub #46/#176–179.
- ULP-4.6: plain-text threaded Q&A per lesson with create/reply/edit-own/delete-own and lecturer delete-any — GitHub #47/#180–183.
- Keep all authz behavior consistent with the existing 404-collapse anti-fuzzing convention.

**Non-Goals:**
- Comment pin, moderation workflow UI, lecturer-facing comments page (schema supports later work; API already admits the owning lecturer).
- Progress display anywhere other than the student lessons page (e.g., my-classes cards).
- Rich-text comments, file attachments in comments, notifications.
- Any Flyway migration or change to existing tables.

## Decisions

### D1 — No migration; entities map V1 tables as-is
Verified live: FKs intact, tables contain a handful of manual test rows. `LearningProgress` maps `learning_progress` (unique `user_id+lesson_id`); `Comment` maps `comments` with `@SQLRestriction("is_deleted = 0")` **not** applied — the list query must see deleted roots to render placeholders, so soft-delete filtering happens in repository queries/service instead. `LearningProgress` needs no SQLRestriction (no `is_deleted` column). Alternative (drop-recreate like V14) rejected: tables are correct and hold data.

### D2 — Package layout
Two new feature packages: `com.ulp.features.progress` (service, repository, controller for the toggle POST) and `com.ulp.features.comments` (api controller, service, repository, dtos). Entities go to `com.ulp.entities` like all others. Keeps `features.student` read-oriented; each file stays under ~200 lines.

### D3 — Progress write path
- Auto-record: `StudentLessonsController.view()` calls `LearningProgressService.recordOpened(...)` AFTER `getLessonDetail` succeeds (inside the existing try; EntityNotFoundException path skips it). Method uses `REQUIRES_NEW`-free plain `@Transactional` (controller call is outside the read-only tx of the detail service) and is upsert-idempotent: `findByUserIdAndLessonId` → insert IN_PROGRESS if absent; on `DataIntegrityViolationException` (concurrent first-open race on the unique key) it swallows and re-reads. A failure here is caught and logged — never breaks rendering.
- Toggle: `LearningProgressController` POST `/my/classes/{classId}/lessons/{lessonId}/progress/toggle`, re-runs the four gates via the service (duplicated gate logic factored into `LearningProgressService` using the same repositories — mirrors `StudentLessonDetailService` gates), then toggles and PRG-redirects to the canonical lesson URL with `flashSuccess`. Form POST (not AJAX) because the page reloads anyway to refresh all aggregates; matches the leave-class hidden-form pattern.

### D4 — Progress read path
`StudentLessonsService.listClassLessons` gains a per-user progress lookup: one repository query `findByUserIdAndLessonIdIn(userId, publishedLessonIds)` (or a JPQL `status=COMPLETED` id-set query) executed once per page view; DTOs extended: `StudentLessonRow` + `completed` boolean, `SectionWithLessons` + `completedCount`, `ClassLessonsView` + `completedTotal`, `publishedTotal`, `percent` (int, rounded half-up, 0 when denominator 0). `LessonDetailView` + `completed` boolean for the toggle button. Computation stays in the service (view-model building is its job); no extra service class.

### D5 — Comments API shape
`LessonCommentsApiController` (`@RestController`, `@PreAuthorize("isAuthenticated()")` per method) under `/api/lessons/{lessonId}/comments`; reuses `AjaxResponses` for failures. `LessonCommentsService` performs authz (gates + role check): resolve lesson → section → class; allow ACTIVE-enrolled caller or `clazz.lecturerId == userId`; everything else 404. Ownership checks return `AccessDeniedException` → 403 via the shared helpers. DTOs in `LessonCommentsDtos`: `CommentRow(id, parentId, authorId, authorName, lecturer, content, edited, deleted, createdAt, canEdit, canDelete, replies)`, request records with Bean Validation (`@NotBlank`, `@Size(max=2000)`) validated after trim in the service (trim-then-validate to catch whitespace-only). List assembly: single `findByLessonIdOrderByCreatedAtAsc`, group in-memory by parentId (lesson comment volume is small; avoids N+1). Reply-to-reply flattens to the root's id at create time.

### D6 — Comments FE
New `static/js/lesson-comments.js` (vanilla, IIFE like other page scripts) + `static/css/lesson-comments.css`. The template renders an empty panel shell with `data-lesson-id` when `lessonDetail != null`; JS fetches the list, builds DOM nodes with `document.createElement` + `textContent` (XSS-safe), `white-space: pre-wrap` preserves newlines. Composer at top-level, per-root "Trả lời" toggles an inline reply box, own rows get "Sửa"/"Xoá" per the API's `canEdit`/`canDelete`. Delete uses the existing confirm-modal pattern if trivially reusable, otherwise a plain `confirm()`-free inline confirm row (house rule bans native dialogs → use a small inline confirm state on the row). Success/failure via `UlpToast`. CSRF from meta tags (pattern from `admin-settings.js`).

### D7 — Timestamps and display
`createdAt` serialized ISO-8601; JS renders `dd/MM/yyyy HH:mm` (no relative-time lib — KISS). Author name resolved via join to `users` in the repository query projection (single query, avoids N+1 on `userRepository`).

## Risks / Trade-offs

- [Race on first-open upsert] → unique key `idx_lp_user_lesson` + catch `DataIntegrityViolationException` and re-read; test with the existing concurrency-test pattern if feasible, else unit-covered logic.
- [Gate logic duplicated between detail service and progress/comments services] → acceptable duplication (3 call sites, ~15 lines each) over a premature shared abstraction; comments in code point at `StudentLessonDetailService` as canonical. Refactor opportunity noted for a later sprint.
- [In-memory thread grouping] → fine at classroom scale (tens–hundreds of comments/lesson); no pagination in this sprint. If volume grows, add pagination on roots later without API break (list response already an object? — keep response an object `{comments: [...]}` to allow additive fields).
- [`comments.moderation_status` default APPROVED] → feature ignores PENDING/REJECTED rows defensively in queries so a future moderation sprint cannot leak rejected rows through this API.
- [Existing manual test rows in both tables] → harmless; integration tests create their own fixtures and run transactional-rollback.

## Migration Plan

Code-only change; deploy normally. Rollback = revert the commit — no schema or data changes to unwind.

## Open Questions

None — all decisions locked during exploration.
