# Specification: lessons-crud

> Canonical spec. Last updated by change `add-lesson-content-types` (archived 2026-06-29).
> Prior change: `2026-06-26-add-lesson-crud`.

---

### Requirement: Create a lesson inside a section

A lecturer who owns the class containing a section SHALL be able to
create a new lesson inside that section by submitting a form with
title, status, and rich-text body. The lesson is appended to the
section's existing order. HEAD and ADMIN roles MUST also be able to
create lessons in any class; LECTURER role MUST be rejected with
HTTP 403 when attempting to create a lesson in a class they do not own.

#### Scenario: Lecturer creates a lesson with valid input
- **GIVEN** a class `C` owned by lecturer `L` containing section `S`
- **WHEN** `L` submits a create form with title `"Bài 1 — Giới thiệu"`,
  status `DRAFT`, and a 200-character HTML body
- **THEN** the system persists a lesson with the given title, status,
  the sanitised body, and `display_order = max(existing) + 1`
- **AND** redirects to the lessons list with a flash success message
- **AND** writes a `CREATED` row to `activity_lessons` referencing the
  new lesson and the actor

#### Scenario: Blank title is rejected with inline error
- **GIVEN** the create form is open
- **WHEN** the lecturer submits a blank title
- **THEN** the system re-renders the form with a binding error on the
  title field and no lesson is persisted

#### Scenario: Title over 300 characters is rejected
- **GIVEN** the create form is open
- **WHEN** the lecturer submits a 301-character title
- **THEN** the system re-renders the form with a length-validation
  error and no lesson is persisted

#### Scenario: A non-owning lecturer is rejected with 403
- **GIVEN** section `S` belongs to class `C` owned by lecturer `L1`
- **WHEN** lecturer `L2` POSTs the create endpoint for `S`
- **THEN** the system returns HTTP 403 and persists nothing

---

### Requirement: Edit a lesson's title, status, and body

A lecturer who owns the containing class SHALL be able to edit a lesson's title, status, content type, and the body-or-source-of-truth fields appropriate to that type. The edit MUST be idempotent on its own keys — re-submitting the same form produces the same state with no audit-log entry when nothing changed. Submitting a different `content_type` triggers the cleanup specified in "Switching a lesson's content type wipes orphaned data".

#### Scenario: Lecturer renames a lesson
- **GIVEN** an existing lesson with title `"Cũ"`
- **WHEN** the lecturer submits the edit form with title `"Mới"`
- **THEN** the lesson's title is `"Mới"`
- **AND** an `UPDATED` activity row is written with metadata `{"old":"Cũ","new":"Mới"}`

#### Scenario: Lecturer rewrites RICHTEXT lesson content
- **GIVEN** an existing RICHTEXT lesson
- **WHEN** the lecturer submits the edit form with new HTML body containing `<script>alert(1)</script><p>OK</p>` and `content_type=RICHTEXT`
- **THEN** the persisted body contains `<p>OK</p>` but no `<script>`
- **AND** an `UPDATED` activity row is written

#### Scenario: Re-submitting unchanged form does not pollute history
- **GIVEN** an existing lesson with title `"X"`, body `<p>Hello</p>`, and `content_type=RICHTEXT`
- **WHEN** the lecturer re-submits the same title, body, and type
- **THEN** no `UPDATED` activity row is written

#### Scenario: Switching content type writes an activity row
- **GIVEN** an existing RICHTEXT lesson
- **WHEN** the lecturer submits the edit form with `content_type=PDF` and a pre-uploaded PDF
- **THEN** an `UPDATED` activity row is written whose metadata records the `content_type` transition `{"old":"RICHTEXT","new":"PDF"}`
- **AND** the rich-text body is removed from the persisted lesson

---

### Requirement: Publish and unpublish a lesson

A lecturer who owns the containing class SHALL be able to toggle a
lesson's status between `DRAFT` and `PUBLISHED` without rewriting the
body. Status transitions MUST be auditable as their own activity
types.

#### Scenario: Lecturer publishes a draft lesson
- **GIVEN** a lesson with status `DRAFT`
- **WHEN** the lecturer triggers publish
- **THEN** the lesson's status is `PUBLISHED`
- **AND** a `PUBLISHED` activity row is written

#### Scenario: Lecturer unpublishes a published lesson
- **GIVEN** a lesson with status `PUBLISHED`
- **WHEN** the lecturer triggers unpublish
- **THEN** the lesson's status is `DRAFT`
- **AND** an `UNPUBLISHED` activity row is written

---

### Requirement: Soft-delete a lesson

A lecturer who owns the containing class SHALL be able to soft-delete a
lesson. The deleted lesson MUST disappear from default queries but its
audit history MUST remain intact, and its previous `display_order` slot
MUST be free for another lesson to claim.

#### Scenario: Lecturer deletes a lesson
- **GIVEN** an existing lesson with `display_order = 2`
- **WHEN** the lecturer triggers delete
- **THEN** the lesson's `is_deleted` is `1` and `display_order` is
  cleared to `NULL`
- **AND** the lesson no longer appears in
  `findBySectionIdOrderByDisplayOrderAsc`
- **AND** a `DELETED` activity row is written

#### Scenario: Recreate after delete does not collide with unique key
- **GIVEN** a section had three lessons with orders 0, 1, 2 and the
  one at order `2` was deleted
- **WHEN** the lecturer creates a new lesson in the same section
- **THEN** the new lesson is persisted with `display_order = 2`
- **AND** no `DataIntegrityViolationException` is raised

---

### Requirement: Reorder lessons within a section

A lecturer who owns the containing class SHALL be able to submit a new
ordering for the lessons of a section. The submitted list MUST be a
permutation of the section's live lessons; any mismatch (extra, missing,
or unknown id) MUST be rejected with HTTP 400.

#### Scenario: Lecturer reorders three lessons
- **GIVEN** lessons `A`, `B`, `C` with orders `0, 1, 2`
- **WHEN** the lecturer posts the ordered ids `[C, A, B]`
- **THEN** the lessons end with orders `A=1, B=2, C=0`
- **AND** a `REORDERED` activity row is written for every lesson whose
  position actually changed (not for lessons that stayed in place)

#### Scenario: Reorder with stale ids is rejected
- **GIVEN** a section currently has lessons `[A, B]`
- **WHEN** the lecturer posts the ordered ids `[A]` (missing `B`) or
  `[A, B, 999]` (unknown id)
- **THEN** the system returns HTTP 400 with a user-facing message
- **AND** no lesson order changes

---

### Requirement: Rich-text body is sanitised before persistence

The system SHALL strip script tags, event handlers, and unsafe URL schemes from any HTML submitted as a RICHTEXT lesson body. The sanitiser MUST allow heading, paragraph, list, link, image, blockquote, preformatted, and inline emphasis tags as specified in the design. Sanitisation MUST be idempotent — running the sanitiser twice on the same input produces the same output, so the body does not accumulate whitespace or empty paragraphs across save-load round trips.

The sanitiser is invoked only on the RICHTEXT path: a lesson whose `content_type` is PDF or VIDEO does not call the sanitiser because its body field is NULL.

#### Scenario: Script tag is stripped
- **WHEN** a lecturer submits body `<p>Safe</p><script>alert(1)</script>` for a RICHTEXT lesson
- **THEN** the persisted body is `<p>Safe</p>` with the script removed

#### Scenario: Event handler attribute is stripped
- **WHEN** a lecturer submits body `<p onclick="evil()">Click</p>`
- **THEN** the persisted body is `<p>Click</p>` with the handler removed

#### Scenario: Data-URI image is preserved
- **WHEN** a lecturer submits body `<p>See <img src="data:image/png;base64,iVBORw0KGgo..." alt="x"></p>`
- **THEN** the persisted body still contains the data-URI image

#### Scenario: javascript: URL is dropped
- **WHEN** a lecturer submits body `<a href="javascript:alert(1)">x</a>`
- **THEN** the persisted body removes the unsafe `href`

#### Scenario: Sanitiser is idempotent across saves
- **WHEN** the same body is sanitised twice in succession
- **THEN** the second pass returns the same string as the first — no extra newlines, no empty paragraphs accumulate between block elements

---

### Requirement: Lessons tab content column shows lessons of the selected section

When the lecturer selects a section in the folders column of the
lessons tab, the content column SHALL render the list of that section's
lessons together with their status pill and an action menu. The empty
state MUST distinguish between "no section selected" and "section
selected but empty".

#### Scenario: Lessons of the selected section are shown
- **GIVEN** the lecturer is on `/lecturer/classes/{id}/lessons?section={sid}`
  and the section has two lessons
- **THEN** the page renders both lesson titles and their status pills

#### Scenario: Selected section has no lessons
- **GIVEN** the section currently has zero lessons
- **THEN** the page renders an empty state with a call to action "Tạo
  bài giảng" pointing at the create form

#### Scenario: No section selected — "Tất cả bài giảng"
- **GIVEN** the lecturer is on `/lecturer/classes/{id}/lessons` (no
  `?section=` parameter)
- **THEN** the page renders an empty placeholder explaining that the
  lecturer should pick a section to manage its lessons

---

### Requirement: A lesson carries a content type discriminator

Every lesson SHALL declare exactly one `content_type` from the set `{RICHTEXT, PDF, VIDEO}`. The lecturer chooses the type when creating the lesson and MAY switch it later. The type discriminates which content fields are populated; fields of the non-active types MUST be NULL.

The default for a freshly created lesson with no explicit type is `RICHTEXT`, which preserves the existing behavior for lecturers who never touch the new picker.

#### Scenario: Lecturer creates a RICHTEXT lesson (default)
- **GIVEN** the create form is open with no type selected explicitly
- **WHEN** the lecturer submits title, status, and a rich-text body
- **THEN** the persisted lesson has `content_type = RICHTEXT`, the sanitised body in `content_richtext`, and NULL `pdf_attachment_id`, `video_url`, `video_provider`

#### Scenario: Lecturer creates a PDF lesson
- **GIVEN** the create form is open, the lecturer has uploaded one PDF via the dedicated content upload endpoint and the lesson now has a `pdf_attachment_id`
- **WHEN** the lecturer submits title, status, and `content_type = PDF`
- **THEN** the persisted lesson has `content_type = PDF`, the same `pdf_attachment_id`, and NULL `content_richtext`, `video_url`, `video_provider`

#### Scenario: Lecturer creates a VIDEO lesson with an external URL
- **GIVEN** the create form is open with `content_type = VIDEO` and `video_provider = YOUTUBE`
- **WHEN** the lecturer submits a valid YouTube URL
- **THEN** the persisted lesson has `content_type = VIDEO`, `video_provider = YOUTUBE`, the URL in `video_url`, and NULL `content_richtext`, `pdf_attachment_id`

#### Scenario: Lecturer creates a VIDEO lesson with an uploaded MP4
- **GIVEN** the lecturer has uploaded an MP4 via the video upload endpoint and the lesson now has `video_provider = UPLOAD` and a server-relative `video_url`
- **WHEN** the lecturer submits title, status, and `content_type = VIDEO`
- **THEN** the lesson keeps `video_provider = UPLOAD` and the stored path; `content_richtext` and `pdf_attachment_id` are NULL

#### Scenario: An unknown content type is rejected
- **WHEN** the lecturer submits `content_type = AUDIO`
- **THEN** the create or update endpoint returns HTTP 400 and nothing is persisted

---

### Requirement: PDF lesson content is uploaded out of band

A lecturer SHALL upload the lesson's main PDF through a dedicated multipart endpoint at `POST /lecturer/classes/{classId}/sections/{sectionId}/lessons/{lessonId}/content/pdf`. The endpoint MUST reject anything whose MIME is not `application/pdf` and create a `lesson_attachments` row, then set `lessons.pdf_attachment_id` to that row's id. The upload reuses the existing attachment storage path so PDF lessons can sit alongside accessory attachments without duplicating storage logic.

#### Scenario: Lecturer uploads a valid PDF
- **GIVEN** an existing lesson in DRAFT
- **WHEN** the lecturer uploads `slides.pdf` via the content/pdf endpoint
- **THEN** a `lesson_attachments` row is created, `lessons.pdf_attachment_id` is set to that row's id, and the endpoint returns the attachment metadata as JSON

#### Scenario: Non-PDF file is rejected
- **WHEN** the lecturer uploads `slides.docx` via the content/pdf endpoint
- **THEN** the endpoint returns HTTP 400 with a user-facing message and nothing is persisted

#### Scenario: Re-uploading replaces the previous main PDF
- **GIVEN** a lesson already has `pdf_attachment_id` pointing at `att1`
- **WHEN** the lecturer uploads a new PDF via the content/pdf endpoint
- **THEN** the previous attachment row and on-disk file are deleted, a new row is created, and `lessons.pdf_attachment_id` now points at the new row

#### Scenario: Deleting the main PDF attachment also clears the FK
- **GIVEN** `lessons.pdf_attachment_id` points at `att1`
- **WHEN** the lecturer deletes `att1` through the existing attachment delete endpoint
- **THEN** `lessons.pdf_attachment_id` is reset to NULL before the attachment row is removed, so no dangling FK exists

---

### Requirement: Video lesson supports external URL or uploaded MP4

A lecturer SHALL either paste an external video URL (YouTube or Vimeo) or upload an MP4 file. External URL setting uses `POST .../content/video-url` with `provider` and `url` form fields; the URL MUST match a YouTube or Vimeo pattern. MP4 upload uses `POST .../content/video` as multipart with a single `file` part. The endpoint MUST reject any file whose MIME is not `video/mp4` or whose size exceeds 200 MB.

#### Scenario: Lecturer sets an external YouTube URL
- **WHEN** the lecturer POSTs `provider=YOUTUBE` and `url=https://www.youtube.com/watch?v=dQw4w9WgXcQ`
- **THEN** the lesson stores `video_provider=YOUTUBE` and the canonical URL, and the endpoint returns 200 with the stored values

#### Scenario: Lecturer sets an external Vimeo URL
- **WHEN** the lecturer POSTs `provider=VIMEO` and `url=https://vimeo.com/123456789`
- **THEN** the lesson stores `video_provider=VIMEO` and the URL

#### Scenario: Non-YouTube / non-Vimeo URL is rejected
- **WHEN** the lecturer POSTs `provider=YOUTUBE` and `url=https://malicious.example/x`
- **THEN** the endpoint returns HTTP 400 and nothing is persisted

#### Scenario: Lecturer uploads a valid MP4
- **WHEN** the lecturer uploads `lecture.mp4` (50 MB, MIME `video/mp4`)
- **THEN** the file is stored at `uploads/lessons/{lessonId}/video/{uuid}.mp4`, the lesson's `video_provider=UPLOAD`, its `video_url` is set to the server-relative path, and any previously uploaded MP4 for the same lesson is deleted from disk

#### Scenario: MP4 over 200 MB is rejected
- **WHEN** the lecturer uploads an MP4 of 250 MB
- **THEN** the endpoint returns HTTP 400 with a "file too large" message and nothing is persisted

#### Scenario: Non-MP4 file is rejected
- **WHEN** the lecturer uploads `lecture.mov`
- **THEN** the endpoint returns HTTP 400 and nothing is persisted

---

### Requirement: Switching a lesson's content type wipes orphaned data

When the lecturer changes a lesson's `content_type` through the edit form, the service SHALL clear fields belonging to the previous type so no stale or orphaned data leaks across types. If the old type carried server-side files (PDF row + on-disk file or uploaded MP4), those files MUST be deleted before the FK or URL is nulled, so neither the database nor the filesystem holds dangling references.

The lecturer-facing form MUST present a confirm modal warning that switching the type will permanently remove the data from the previous type. The server-side cleanup runs in the same transaction as the type switch.

#### Scenario: Switching from RICHTEXT to PDF clears the rich-text body
- **GIVEN** a lesson with `content_type=RICHTEXT` and a 500-character body, and the lecturer has already uploaded a new PDF
- **WHEN** the lecturer submits the edit form with `content_type=PDF`
- **THEN** `content_richtext` becomes NULL, `pdf_attachment_id` is set to the new attachment, and `video_url`/`video_provider` stay NULL

#### Scenario: Switching from PDF to VIDEO deletes the PDF file
- **GIVEN** a lesson with `content_type=PDF` and a `pdf_attachment_id` pointing at `att1` (file on disk)
- **WHEN** the lecturer switches to `content_type=VIDEO` with a YouTube URL
- **THEN** `att1` and its on-disk file are deleted, `lessons.pdf_attachment_id` becomes NULL, `video_provider=YOUTUBE`, `video_url` holds the URL, and `content_richtext` stays NULL

#### Scenario: Switching from VIDEO UPLOAD to RICHTEXT deletes the MP4 file
- **GIVEN** a lesson with `content_type=VIDEO`, `video_provider=UPLOAD`, and a stored MP4 on disk
- **WHEN** the lecturer switches to `content_type=RICHTEXT` with a new body
- **THEN** the MP4 file is removed from disk, `video_url` and `video_provider` become NULL, and `content_richtext` holds the sanitised body

#### Scenario: Switching from VIDEO external to PDF does not touch the filesystem
- **GIVEN** a lesson with `content_type=VIDEO`, `video_provider=YOUTUBE`, and an external `video_url`
- **WHEN** the lecturer switches to `content_type=PDF`
- **THEN** `video_url` and `video_provider` become NULL and the new `pdf_attachment_id` is set; no file deletion is attempted since YouTube hosts the video

---

### Requirement: Switching a lesson's content type requires data for the new type

When the lecturer submits an edit changing the `content_type`, the service SHALL reject the submission if data needed by the new type is missing (no PDF uploaded for PDF, no `video_url` for VIDEO). The HTTP response MUST be 400 with a user-facing message, the form MUST re-render with the lecturer's choices preserved, and no data of the old type MUST be deleted.

#### Scenario: Switching to PDF without uploading a PDF first is rejected
- **GIVEN** a lesson with `content_type=RICHTEXT`, no `pdf_attachment_id`
- **WHEN** the lecturer submits the edit form with `content_type=PDF`
- **THEN** the endpoint returns HTTP 400 with a "PDF chưa được tải lên" message, the rich-text body remains intact, and `content_type` stays `RICHTEXT`

#### Scenario: Switching to VIDEO without provider+url is rejected
- **GIVEN** a lesson with `content_type=PDF`
- **WHEN** the lecturer submits `content_type=VIDEO` without providing `video_provider` and `video_url`
- **THEN** the endpoint returns HTTP 400 with a "Chưa cấu hình video" message and the PDF and its FK stay intact

---

### Requirement: Lesson list rows surface the content type to readers

The lecturer's section detail (`/lecturer/classes/{id}/lessons?section={sid}`) and the student's section detail (`/my/classes/{id}/lessons?section={sid}`) SHALL display a small visual badge per lesson row indicating its `content_type`. The badge MUST distinguish RICHTEXT (text icon), PDF (document icon), and VIDEO (play icon) so readers can scan a section's mix without opening each lesson.

#### Scenario: Section page renders type badges
- **GIVEN** a section with one RICHTEXT, one PDF, and one VIDEO lesson, all PUBLISHED
- **WHEN** the lecturer or student views the section detail
- **THEN** each lesson row shows its corresponding type badge

#### Scenario: Type badge updates when lecturer switches a lesson type
- **GIVEN** a RICHTEXT lesson visible on the section page
- **WHEN** the lecturer switches it to VIDEO and reloads the section page
- **THEN** the badge for that row now shows the VIDEO icon

---

### Requirement: Student lesson-detail renders one viewer per type

The student lesson-detail page (`/my/classes/{classId}/lessons/{lessonId}`) SHALL render the lesson's content using exactly one viewer matched to `content_type`, while keeping the attachments section visible for all types when accessory files exist.

The PDF viewer MUST use `<embed type="application/pdf">` with a nested `<a target="_blank" rel="noopener">` fallback so browsers without an inline PDF plugin (most mobile browsers) still let the student open the file in a new tab. The fallback link is the only client-facing way for a student to leave the page with the PDF; this satisfies "embed inline only" by not adding a separate download button while still being accessible to readers whose browsers cannot embed.

The VIDEO viewer MUST render an iframe pointing at the provider's embed URL for YouTube and Vimeo (built from the stored canonical URL) or an HTML5 `<video controls preload="metadata">` element for the UPLOAD provider. Both shapes are wrapped in a 16:9 responsive container.

The RICHTEXT viewer keeps the existing behavior: a single `<article>` element rendering the sanitised HTML body.

All three viewers MUST honour the existing authorization gates: the caller MUST be ACTIVE-enrolled in the class and the lesson MUST be PUBLISHED, otherwise the request collapses to the same `EntityNotFoundException` already used for the rich-text path.

#### Scenario: Student views a RICHTEXT lesson
- **GIVEN** a PUBLISHED RICHTEXT lesson with body `<p>Hello</p>`
- **WHEN** an enrolled student opens the detail page
- **THEN** the page renders an `<article>` with `<p>Hello</p>` and no PDF or video viewer markup

#### Scenario: Student views a PDF lesson
- **GIVEN** a PUBLISHED PDF lesson with `pdf_attachment_id` pointing at a stored file
- **WHEN** an enrolled student opens the detail page
- **THEN** the page renders an `<embed type="application/pdf">` whose `src` is the existing attachment download URL, plus a nested fallback `<a target="_blank">` pointing at the same URL

#### Scenario: Student views a YouTube VIDEO lesson
- **GIVEN** a PUBLISHED VIDEO lesson with `video_provider=YOUTUBE` and a canonical YouTube URL
- **WHEN** an enrolled student opens the detail page
- **THEN** the page renders an iframe whose `src` is the corresponding YouTube embed URL inside a 16:9 responsive wrapper

#### Scenario: Student views a Vimeo VIDEO lesson
- **GIVEN** a PUBLISHED VIDEO lesson with `video_provider=VIMEO` and a canonical Vimeo URL
- **WHEN** an enrolled student opens the detail page
- **THEN** the page renders an iframe whose `src` is the corresponding Vimeo player URL inside a 16:9 responsive wrapper

#### Scenario: Student views an uploaded MP4 VIDEO lesson
- **GIVEN** a PUBLISHED VIDEO lesson with `video_provider=UPLOAD` and a stored MP4 on disk
- **WHEN** an enrolled student opens the detail page
- **THEN** the page renders an HTML5 `<video controls preload="metadata">` whose `src` is the streaming URL for the uploaded file inside a 16:9 wrapper

#### Scenario: Attachments section is rendered alongside any viewer
- **GIVEN** a PUBLISHED PDF lesson with `pdf_attachment_id` plus two accessory attachments (a slides PDF and a quiz ZIP)
- **WHEN** an enrolled student opens the detail page
- **THEN** the PDF embed viewer renders the main PDF and the existing "Tệp đính kèm" section lists the two accessory files

#### Scenario: A non-enrolled student is denied across all types
- **GIVEN** a PUBLISHED lesson of any type in a class the student is not enrolled in
- **WHEN** the student requests the detail page
- **THEN** the system returns the same not-found response as the existing rich-text path and no viewer markup is rendered

---

### Requirement: Video and PDF streaming endpoints honour the same auth gates

The endpoints that stream the PDF embed source and the uploaded video file SHALL enforce the same authorization as the existing attachment download endpoint: an authenticated user who is ACTIVE-enrolled in the lesson's class and whose lesson is PUBLISHED. Lecturers and HEAD/ADMIN MUST always be able to stream their own lessons regardless of publish state.

#### Scenario: Enrolled student streams a PDF main attachment
- **GIVEN** an enrolled student and a PUBLISHED PDF lesson
- **WHEN** the student requests the PDF stream URL
- **THEN** the server returns the file bytes with `Content-Type: application/pdf` and `Content-Disposition: inline`

#### Scenario: Non-enrolled student is denied PDF stream
- **GIVEN** a student not enrolled in the class
- **WHEN** the student requests the PDF stream URL
- **THEN** the server returns HTTP 404

#### Scenario: Enrolled student streams an uploaded MP4
- **GIVEN** an enrolled student and a PUBLISHED VIDEO lesson with `video_provider=UPLOAD`
- **WHEN** the student requests the MP4 stream URL
- **THEN** the server returns the file bytes with `Content-Type: video/mp4` and supports HTTP Range so the player can seek

#### Scenario: Lecturer streams own DRAFT PDF
- **GIVEN** a lecturer who owns the class and a DRAFT PDF lesson
- **WHEN** the lecturer requests the PDF stream URL
- **THEN** the server returns the file bytes — DRAFT does not block the owner
