## Why

Section CRUD (ULP-4.0a) shipped without any content to organise — the
"Bài giảng" column on the lessons tab still shows a placeholder. Lesson
CRUD is the immediate next building block: every other Sprint 3 story
(ULP-4.0c attachments, ULP-4.1 student list view, ULP-4.2 student rich-text
viewer, ULP-4.3 PDF viewer, ULP-4.4 download, ULP-4.5 progress tracking,
ULP-4.6 Q&A) needs a Lesson entity to attach to. Unblocking those six
stories with one focused change is the highest-leverage move for the
sprint.

## What Changes

- Add Lesson entity, repository, service, and form-based controller mirroring
  the Section CRUD pattern that landed in ULP-4.0a.
- Embed a Quill rich-text editor in the create/edit form; persist sanitised
  HTML in `lessons.content_richtext`.
- Add Jsoup-based HTML sanitiser to strip script/event handlers before save.
- Add `V14__lessons_table.sql` to align the existing `lessons` schema with
  the new scope (nullable `display_order` for soft-delete slot release,
  `status` discriminator, soft-delete flag).
- Wire activity logging for Lesson mutations (CREATED, UPDATED, PUBLISHED,
  UNPUBLISHED, DELETED, REORDERED) through a new `LessonActivityWriter`.
- Replace the "Sắp ra mắt (ULP-4.0b)" placeholder in `detail-lessons.html`
  with a real lesson list inside the content column, filtered by the
  currently selected section.
- Add Lesson-related entries to `com.ulp.common.IConstant` (view name,
  attribute keys, flash messages, status discriminators).
- Add Jsoup dependency to `pom.xml`.

Out of scope (deferred to later stories):
- File attachments (ULP-4.0c).
- Student-facing viewers (ULP-4.1–4.4).
- Progress tracking (ULP-4.5).
- Comments / Q&A (ULP-4.6).

## Capabilities

### New Capabilities
- `lessons-crud`: Producer-side lifecycle of a Lesson inside a Section — create with title/status/HTML body, rename, edit content, publish/unpublish, soft-delete, drag-reorder. Authorisation tied to the owning class; rich-text persisted as sanitised HTML; every mutation written to an append-only audit log.

### Modified Capabilities
<!-- No existing capability has its requirements changed. The lessons-tab
     redesign delivered in ULP-4.0a is unaffected at the spec level; only
     its placeholder content column is filled in. -->

## Impact

- **New code**: `entities/Lesson.java`, `entities/LessonActivity.java`,
  `features/lessons/{repository,service,controller,dto}/Lesson*.java`,
  `common/HtmlSanitizer.java`.
- **Modified code**: `SectionsController.renderLessonsPage` (pass lessons
  for selected section into the model), `templates/classes/detail-lessons.html`
  (replace placeholder with real list), `static/css/sections.css` (lesson
  card/list styles), `static/js/sections.js` (lesson action menu + AJAX
  delete), `com.ulp.common.IConstant` (new keys).
- **New template**: `templates/classes/lesson-form.html`.
- **Schema**: `V14__lessons_table.sql` migration.
- **Dependencies**: add `org.jsoup:jsoup` to `pom.xml`; load Quill 2.x from
  CDN in lesson-form template.
- **Tests**: `LessonsServiceTest`, `LessonsControllerIntegrationTest` —
  expected +20–25 tests on top of the current 326.
- **Authorisation surface**: nested resource path
  `/lecturer/classes/{cid}/sections/{sid}/lessons/...` — the controller
  must validate that the section belongs to the class before mutating.
