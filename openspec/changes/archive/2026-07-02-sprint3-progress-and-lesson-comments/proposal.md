# Proposal: sprint3-progress-and-lesson-comments

## Why

Sprint 3's lesson epic (GitHub epic #9) has two remaining stories: students cannot see how far they have progressed through a class's published lessons (#46, ULP-4.5), and there is no way to ask questions or discuss a lesson (#47, ULP-4.6). Both database tables (`learning_progress`, `comments`) already exist from `V1__init_schema.sql` and were verified intact on the live DB (FKs still reference the V14-recreated `lessons` table), but no Java code, UI, or tests exist for either feature.

## What Changes

- **Learning progress tracking (ULP-4.5)**
  - Opening a published lesson as an ACTIVE-enrolled student auto-records `IN_PROGRESS` (idempotent upsert into `learning_progress`).
  - A "Đánh dấu hoàn thành" toggle button on the inlined lesson detail marks the lesson `COMPLETED` (form POST → redirect → flash toast); pressing again reverts to `IN_PROGRESS`.
  - The student lessons page shows: an overall class progress bar (completed / published lessons) in the sidebar class card, per-section `n/m` counts in the chapter switcher, and a ✓ badge on completed lesson cards in the right rail.
  - Percentages count only PUBLISHED, non-soft-deleted lessons.
- **Lesson Q&A comments (ULP-4.6)**
  - New JSON API under `/api/lessons/{lessonId}/comments`: list, create (question or reply via optional `parentId`), edit own, soft-delete own; the class's owning lecturer may delete any comment.
  - Plain-text content (1–2000 chars), rendered client-side via `textContent` (XSS-safe), 1-level threading, `is_edited` flag surfaced as "(đã chỉnh sửa)", deleted root comments with live replies render a placeholder.
  - Comments panel appears below the lesson detail on the student lessons page, loaded and mutated via AJAX (CSRF meta-tag pattern) with `UlpToast` feedback.
  - Pin, moderation workflow, and a lecturer-facing UI are explicitly OUT of scope (schema already supports them for a later sprint); the API authz already admits the owning lecturer so a lecturer surface can be added without API changes.
- **No schema migration** — both tables verified present and correctly FK-linked; only JPA entities/repositories are added.

## Capabilities

### New Capabilities

- `learning-progress`: recording a student's per-lesson progress (auto IN_PROGRESS on open, manual COMPLETED toggle) and aggregating completion percentages per section and per class for the student lessons page.
- `lesson-comments`: threaded plain-text Q&A on a published lesson (create/reply/edit-own/delete-own, lecturer delete-any) exposed as a JSON API plus the student-facing comments panel.

### Modified Capabilities

<!-- none — existing specs are not affected; the student lessons page gains additive UI only -->

## Impact

- **New code**: `com.ulp.entities.LearningProgress`, `com.ulp.entities.Comment`; new feature packages `com.ulp.features.progress` and `com.ulp.features.comments` (controller/service/repository/dto); new `static/js/lesson-comments.js`, new CSS; additions to `IConstant`.
- **Modified code**: `StudentLessonsController` (+POST toggle handler wiring or a small new controller), `StudentLessonsService`/`StudentLessonDetailService` DTO view models gain progress fields, `templates/student/class-lessons.html` (progress UI + comments panel).
- **DB**: read/write to existing `learning_progress` and `comments` tables; no Flyway migration.
- **Security**: all endpoints reuse the established authz gates (ACTIVE enrollment, live class, cross-class guard, PUBLISHED lesson; failures collapse to 404). Comment mutations additionally check ownership.
- **Tests**: new `@SpringBootTest @Transactional` service integration tests and MockMvc controller tests; existing suites unaffected.
- **GitHub issues closed on completion**: #46, #176–179, #47, #180–183.
