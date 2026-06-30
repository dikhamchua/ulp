## Context

ULP-4.0a (Section CRUD) landed the producer-side chrome on the lessons
tab: a 3-column shell with a folders column listing sections, and a
content column that today renders a "Sắp ra mắt (ULP-4.0b)" placeholder.
ULP-4.0b is the producer-side companion: the lecturer must be able to
create, edit, publish, reorder, and soft-delete lessons inside any
section of a class they own.

The V1 baseline schema already provisioned a `lessons` table
(`section_id`, `title`, `type`, `sort_order`, `status`, `created_by`,
`published_at`, `is_deleted`, ...) and a separate `lesson_contents`
table (rich-text body, PDF URL, video URL). That split anticipated
multiple lesson types. For ULP-4.0b we intentionally narrow to a single
type — rich-text — and store the HTML inline on `lessons.content_richtext`.
The `lesson_contents` table remains in place but unused; it will be
revisited when ULP-4.0c (attachments) and ULP-4.3 (PDF viewer) ship.

The Sections CRUD pattern (entity → repo → service → controller →
form template + AJAX endpoints + activity log + IConstant keys + tests)
is the canonical example. Lessons follow it verbatim, one level
deeper in the hierarchy (`Class > Section > Lesson`).

## Goals / Non-Goals

**Goals:**
- Lecturer can create a Lesson with a Vietnamese title + rich-text body
  + DRAFT/PUBLISHED status, scoped to one Section of a Class they own.
- Lecturer can edit the title, status, and body of an existing Lesson.
- Lecturer can publish/unpublish a Lesson without touching its body.
- Lecturer can soft-delete a Lesson; the slot is freed so another
  Lesson can take its `display_order` (V13 soft-delete pattern).
- Lecturer can drag-reorder Lessons inside a Section.
- All mutations are captured in `activity_lessons` via an append-only
  writer, mirroring `SectionActivityWriter`.
- Rich-text content is sanitised server-side before persistence so a
  malicious paste cannot execute scripts when a student later views it.
- The placeholder content column of `detail-lessons.html` becomes a
  real list filtered by `?section=`.

**Non-Goals:**
- File / PDF / video attachments — deferred to ULP-4.0c (issue #339).
- Any student-facing viewer — deferred to ULP-4.1–4.4 (issues #42–#45).
- Progress tracking — deferred to ULP-4.5 (issue #46).
- Comments / Q&A on a lesson — deferred to ULP-4.6 (issue #47).
- Lesson-content versioning or draft autosave.
- Cross-section move (Lesson stays in the section it was created in).
- Editing lessons inline from the list view; create/edit lives on a
  dedicated full-page form, same as Section editing.
- WYSIWYG image upload to server-side storage. Images that the lecturer
  pastes/embeds are persisted as data-URI inside the HTML body. The
  attachment path comes in 4.0c.

## Decisions

### D1 — Quill 2.x for the rich-text editor (loaded from CDN)

Quill ships at ~50 KB minified, exposes a Delta-based API that we don't
need at the server, and renders straight to HTML in the DOM. We pin to
Quill 2.x via `cdn.jsdelivr.net` (same CDN as the existing
SortableJS and iziToast loads). On submit, the page JS copies
`editor.root.innerHTML` into a hidden `<input name="contentHtml">`
just before the form posts.

Alternatives considered:
- **TinyMCE** — heavier (~300 KB), full plugin ecosystem we don't need,
  and the community licence is GPL — adds compliance friction for a
  capstone product.
- **CKEditor 5** — modern but premium features live behind a paid licence;
  the open-source build is still ~120 KB and has a steeper init API.
- **Trix** — under-featured for our headings + lists + image story.
- **Plain `contenteditable`** — too much DIY work; cross-browser
  selection/keyboard quirks dominate.

### D2 — Persist sanitised HTML, never raw input

Quill emits HTML. We accept HTML on submit and run it through Jsoup's
`Safelist` before save. Storing HTML keeps server-side rendering trivial
(`th:utext="${lesson.contentHtml}"`) and matches how a student-facing
viewer will consume it in ULP-4.2. Delta JSON (Quill's native format)
was rejected because rendering it server-side would require a JS bridge
or a port of Quill's render logic.

**Sanitiser policy (`HtmlSanitizer.sanitize`):**

Allowed tags: `h1`–`h6`, `p`, `br`, `hr`, `strong`, `b`, `em`, `i`, `u`,
`s`, `blockquote`, `pre`, `code`, `ol`, `ul`, `li`, `a`, `img`.

Allowed attributes:
- `a`: `href` (only `http`, `https`, `mailto`), `target`, `rel`.
- `img`: `src` (only `data:image/png|jpeg|gif|webp` and `http`/`https`),
  `alt`, `width`, `height`.
- Everything else stripped, including all `on*` event handlers and
  inline `style` (Quill emits `style="..."` for some formatting, but for
  this scope we drop it; class-based formatting via Quill defaults is OK).

The sanitiser lives at `com.ulp.common.HtmlSanitizer` so other features
(comments, board posts) can reuse it later.

### D3 — Nested REST URL: `/lecturer/classes/{cid}/sections/{sid}/lessons/...`

Reflects the actual hierarchy. The controller validates that the
`sectionId` belongs to `classId` before any service call, mirroring the
existing `SectionsController` pattern but one level deeper. Flat URL
options (e.g. `/lecturer/lessons/...?section=`) were rejected because
they lose the hierarchy in browser history and make auth checks awkward.

### D4 — Schema delta: V14 ALTERs the existing `lessons` table

`lessons` already exists from V1 with the right shape minus three things:

1. `display_order` is `INT DEFAULT 0` and non-null. We need
   `SMALLINT NULL` to match the V13 soft-delete-safe pattern — when a
   lesson is soft-deleted, we clear its order to NULL so the unique
   key on `(section_id, display_order)` allows another lesson to take
   that slot. (Note: the V1 schema declares `idx_lesson_section` but
   not a unique key; we add the unique constraint as part of V14.)
2. The legacy `sort_order` column needs to be renamed to
   `display_order` for consistency with `sections.display_order`.
3. There is no `content_richtext` column yet. We add
   `content_richtext LONGTEXT NULL` (rather than reaching into
   `lesson_contents`) so a single SELECT delivers the editable
   form state. The `lesson_contents` row stays unused for ULP-4.0b.

V14 also drops `estimated_minutes` and `type` from the original schema:
the project no longer plans to discriminate lesson types at the row
level (PDF/video lessons in 4.0c will be modelled as attachments on a
single Lesson, not a different row type), and `estimated_minutes` was a
nice-to-have that never had a user story. Dropping now keeps the entity
clean.

The `activity_lessons` table from V3 already matches the
`activity_sections` pattern; no schema change required for the audit
log.

### D5 — Authorisation enforced one layer deeper

`SectionsService` calls `classesService.getEditable(classId, ...)`
directly because the URL already names the class. For Lessons the
URL gives `(classId, sectionId, lessonId)`; the controller must
guarantee the section belongs to the class before any service call,
and the service must look up the section's class id before delegating
to `ClassesService.getEditable`. Tests must cover the cross-class
attack vector: lecturer A cannot mutate a lesson in lecturer B's
section by guessing path variables.

### D6 — Activity log granularity

Activity types: `CREATED`, `UPDATED` (title or content change),
`PUBLISHED`, `UNPUBLISHED`, `DELETED`, `REORDERED`. We do not split
title vs content updates — the diff metadata captures both, and the
history UI just reads "Đã cập nhật". Status changes get their own
entry type because publishing is a user-meaningful event that should
stand out in the timeline.

## Risks / Trade-offs

- **[XSS through pasted content]** → Mitigation: Jsoup `Safelist`
  scoped to a small tag/attribute set; no `style`, no `script`, no event
  handlers. Tested with `<script>` payload in unit test.
- **[Quill bundle weight on slow networks]** → Mitigation: load from CDN
  with `defer`; editor only renders on the form page, not the list page.
- **[Data-URI images bloat the DB row]** → Acknowledged: a lecturer who
  pastes a 5 MB image inflates `content_richtext`. `LONGTEXT` accommodates
  up to ~4 GB so the DB does not error, but a row > 1 MB will be slow.
  Mitigation deferred to 4.0c (real attachment upload). For 4.0b we
  document the cap in the form help text.
- **[Soft-delete + unique key trap]** → Same risk as V13. Mitigation: V14
  applies the same nullable-`display_order` pattern, and
  `Lesson.markDeleted()` sets `displayOrder = null`.
- **[Two-phase reorder collides under concurrent edits]** → Mitigation:
  `@Transactional` boundary on `reorder`; verified manually in
  `LessonsServiceTest` with the same two-phase test as Sections.
- **[Cross-class enumeration]** → Mitigation: section-belongs-to-class
  check at controller entry; tested with negative-auth integration test.

## Migration Plan

1. **Deploy**: V14 runs automatically on Flyway boot. No data loss
   because the lessons table is empty in every environment today.
2. **Rollback**: if V14 fails (e.g. an unexpected production row violates
   the new CHECK), Flyway records the failure; the team manually drops
   `lessons` content_richtext, restores `sort_order`, and re-runs
   migration history through `flyway:repair`. The change is reversible
   while no real lesson rows exist.
3. **Backfill**: not required — table is empty.
4. **Compatibility**: the `lesson_contents` table is left untouched.
   ULP-4.0c will revisit it for attachments.

## Open Questions

- None at this point. The five user-facing decisions were settled in
  the conversation that preceded this change (Quill, HTML+Jsoup, nested
  URL, scope boundary, full proposal pipeline).
