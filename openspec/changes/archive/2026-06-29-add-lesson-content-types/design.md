## Context

The lessons subsystem currently handles one shape of content: HTML rich-text authored in Quill, sanitised by Jsoup before persistence, and rendered as a single `<article>` block in the student view. Schema-wise, `lessons.content_richtext LONGTEXT` is the lone content column.

Sprint 3's open issues call for two more first-class shapes — PDF (issues #44, #171, #172) and video (added by the user during planning, no separate issue). Both already overlap with infrastructure built for ULP-4.0c lesson attachments: the PDF file lifecycle (upload, MIME validation, on-disk storage, authenticated streaming) is identical to what `LessonAttachmentStorageService` already does. Video is the new shape — it has both an external-URL flavor (paste YouTube/Vimeo) and an upload flavor (200 MB MP4) with file-streaming concerns that diverge from PDF attachments (range requests, larger max size).

Constraints carried in from the project:
- Spring Boot SSR with Thymeleaf templates; no SPA framework or bundler.
- Flyway owns the schema; Hibernate is in `validate` mode.
- Every public API guarded by `@PreAuthorize` based on `Role` enum; lecturer-owned class gates handled by `ClassesService.getEditable`.
- Comments in Java are English; user-facing strings in templates are Vietnamese.
- File limits per project rule: keep individual Java files under ~200 lines; split when crossing the line.

The brainstorm with the user (this conversation) locked nine decisions Q1–Q9 that constrain the design space; this document records the resulting architecture, not the deliberation.

## Goals / Non-Goals

**Goals:**
- Add `content_type` discriminator to lessons with three values RICHTEXT, PDF, VIDEO and a single state-machine for switching types.
- Reuse `lesson_attachments` infrastructure for the PDF case to avoid duplicating upload/MIME/storage code paths.
- Provide a separate, narrower video storage service that handles 200 MB MP4 uploads and emits range-friendly responses for HTML5 `<video>` seeking.
- Keep the existing form POST endpoint compatible — it still saves title/status/type/rich-text body. File uploads happen out-of-band via dedicated endpoints so submit stays small and XHR-friendly.
- Render exactly one viewer per `content_type` in the student detail page while keeping the existing attachments section visible to all types.
- Migrate existing rows to `content_type = RICHTEXT` via a column DEFAULT — no data backfill script needed.

**Non-Goals:**
- DOCX, PPTX, or any other document type (lecturer converts to PDF).
- Progress tracking (Sprint 3 ULP-4.5, separate change).
- Q&A discussion (Sprint 3 ULP-4.6, separate change).
- Video transcoding, adaptive streaming, thumbnails, subtitles, watch analytics.
- Backfilling old HtmlSanitizer-polluted rich-text (the sanitiser bug fix already in the working tree handles future saves; existing rows self-heal next time the lecturer saves).
- Multi-PDF lessons (the PDF type binds exactly one main PDF; accessory PDFs go through the existing attachments path).
- Migrating off MySQL or away from the on-disk uploads pattern.

## Decisions

### D1: Single-table inheritance with discriminator (`content_type`)

The polymorphic shape goes into one row in `lessons` with nullable type-specific columns and a `content_type` discriminator. CHECK constraints (`content_type='RICHTEXT' → content_richtext IS NOT NULL`, `'PDF' → pdf_attachment_id IS NOT NULL`, `'VIDEO' → video_provider AND video_url IS NOT NULL`) enforce the shape at the database layer.

Why over alternatives:
- **Class-table inheritance (lessons + lesson_richtext + lesson_pdf + lesson_video)** would force a JOIN for every list query the section page already issues, and the cardinality (3 types, small) does not justify the complexity.
- **JSON column `content_data`** loses type safety; validation would have to live in code and database CHECKs would be impossible.

Single-table fits because the set of types is fixed and small, and the dominant query (list lessons of a section in display order) already hits one row per lesson.

### D2: PDF storage reuses `lesson_attachments`

A PDF lesson's main file is stored as a regular row in `lesson_attachments`, referenced by `lessons.pdf_attachment_id`. The existing `LessonAttachmentStorageService` handles upload, MIME validation, storage path, and deletion.

Why over alternatives:
- **Separate `lesson_pdf_files` table** would duplicate upload code, MIME validation, and the streaming endpoint authn/authz already proven against XSS and cross-class enumeration.
- A PDF lesson should be allowed to ALSO carry accessory attachments (Q5 — the lecturer may attach companion exercises). One table for both makes that natural.

Side-effect to manage: deleting the row that `lessons.pdf_attachment_id` points to creates a dangling FK. The fix is in `LessonAttachmentsService.delete()` — before removing the row it checks whether any lesson's `pdf_attachment_id` equals this id and, if so, sets that FK to NULL first. This keeps the FK consistent and lets the existing delete cascade for accessory attachments run unchanged.

### D3: Video storage uses a dedicated service

Video files do not go through `LessonAttachmentStorageService`. A new `LessonVideoStorageService` stores MP4 uploads at `uploads/lessons/{lessonId}/video/{uuid}.mp4`, validates `MIME = video/mp4`, and enforces a 200 MB cap. A separate streaming endpoint returns `Content-Type: video/mp4` and supports HTTP Range so the HTML5 `<video>` element can seek.

Why a separate service:
- The attachment service caps file size at 20 MB and disallows `video/*` MIME types — relaxing it for the video case would weaken the security guarantee for the document case.
- The streaming endpoint needs range-request support, which the attachment download endpoint does not currently implement and should not need.
- Different storage path makes housekeeping easier (purge `*/video/*` separately from `*/attachments/*`).

### D4: Video URL validation by regex

External URLs are matched against two regexes:
- YouTube: `^https?://(www\.|m\.)?(youtube\.com/(watch\?v=|embed/)|youtu\.be/)[\w-]+(\?.*)?$`
- Vimeo: `^https?://(www\.|player\.)?vimeo\.com/(\d+|video/\d+)(\?.*)?$`

The stored value is the URL the lecturer typed (canonical, not embed form). The student-view code converts to embed form when constructing the iframe `src`. Storing the canonical URL keeps the database honest about what the lecturer chose and lets the embed strategy evolve without a data migration.

Why over alternatives:
- **Server-side oEmbed call** would add a network dependency at write time, leak the URL to YouTube/Vimeo for every save, and would not catch a typo that nonetheless validates as a real URL.
- **Allow any URL, render with `<iframe>`** would expose students to arbitrary content (XSS via `srcdoc`, mixed-content, malware).

### D5: PDF embed + nested fallback link

The student PDF viewer is one `<embed type="application/pdf" src="...">` containing a nested `<a target="_blank" rel="noopener" href="...">` fallback. Browsers that render the embed never see the fallback. Browsers without an inline PDF plugin (most mobile browsers) fall through to the link, which opens the PDF in a new tab where the OS handler (a PDF viewer app, or the browser's own viewer at the URL level) takes over.

The fallback link is not a "download" button — `Content-Disposition: inline` on the streaming endpoint means it opens in-tab rather than downloading. This satisfies Q3 "embed inline only — no separate download button" while remaining accessible on mobile.

### D6: Out-of-band upload endpoints for PDF and video

The lesson edit form is form-urlencoded and submits title/status/content_type/rich-text body. File uploads happen through three sibling endpoints:
- `POST .../lessons/{id}/content/pdf` — multipart, single file, sets `pdf_attachment_id`
- `POST .../lessons/{id}/content/video` — multipart, single file, sets `video_url` + `video_provider=UPLOAD`
- `POST .../lessons/{id}/content/video-url` — form-urlencoded, takes `provider` + `url`, sets `video_url` + `video_provider`

The form submit reads the lesson's current FK / URL state from the database; if the lecturer chose `content_type=PDF` but `pdf_attachment_id` is NULL, the form save is rejected with a user-facing message telling them to upload the PDF first. Same shape for VIDEO.

Why over alternatives:
- **One giant multipart form submit** would force a 200 MB POST through the form pipeline, blocking until the entire upload is on disk before any validation runs, and would defeat XHR upload-progress UX.
- The existing attachment uploader (`lesson-attachments.js`) already uses out-of-band XHR with a progress bar; reusing the pattern keeps the UX consistent.

UX consequence: for a freshly created lesson, the lecturer follows a two-step pattern — create the lesson as DRAFT first, then upload the PDF or MP4, then save again to switch into the new type or publish. The form-side JS hides the "Save as PDF/VIDEO" affordance until the requisite file is present, so the lecturer is never asked to submit a form they cannot complete.

### D7: Type-switch cleanup runs inside the update transaction

When `update()` sees `newContentType != lesson.contentType`, it:
1. Validates the lesson has the data needed for the new type. Missing data → throw `IllegalArgumentException`, transaction rolls back.
2. Captures references to any server-side files that belong to the old type.
3. For PDF → other: calls `LessonAttachmentsService.delete(pdf_attachment_id)` which deletes the on-disk file and the row, but first clears `lessons.pdf_attachment_id` to keep the FK valid during the delete.
4. For UPLOAD video → other: calls `LessonVideoStorageService.deleteByLessonId(lessonId)` which removes the on-disk file.
5. Nulls all fields not belonging to the new type.
6. Persists.

If step 2/3/4 throws (disk error, missing file), the transaction rolls back so the lesson's DB row remains in its old state — no half-switched state with broken FK / URL.

### D8: Embed URL builders are pure functions

Two utility methods turn a stored canonical URL into an embed URL at render time:
- `YouTubeEmbed.toEmbedUrl("https://www.youtube.com/watch?v=ABC")` → `"https://www.youtube.com/embed/ABC"`
- `VimeoEmbed.toEmbedUrl("https://vimeo.com/123")` → `"https://player.vimeo.com/video/123"`

They live in `com.ulp.features.lessons.support` so they are testable without going through the entity. The student view calls them when populating `LessonDetailView.videoUrl` so the template just emits the iframe `src`. This keeps Thymeleaf templates dumb and centralises the URL massage in one place.

### D9: Existing data backfill via column default

V16 adds `content_type VARCHAR(20) NOT NULL DEFAULT 'RICHTEXT'`. Every existing row populates `RICHTEXT` automatically, and since they already have a non-null `content_richtext` value (the form's NOT NULL constraint and Quill's empty-string fallback together), the CHECK constraint passes for all of them at the moment of migration. No SQL data migration step.

The CHECK constraint is written with `IS NOT NULL` and `IS NULL` predicates that allow `content_richtext` to be empty string but not NULL. The existing entity's `content_richtext` column is currently `LONGTEXT` (nullable per schema definition); V16 does NOT change its nullability — only the CHECK constraint conditionally requires it.

### D10: File-size discipline — split when necessary

Several Java files will grow past the 200-line guideline if the new logic lands in them naively. The plan:
- `LessonsService.java` already at ~210 lines. New type-switch logic moves to a new collaborator `LessonContentTypeSwitcher` (≤120 lines) injected into `LessonsService`. The service stays as the orchestration entry point.
- New controller `LessonContentApiController` carries the three upload/url endpoints separately from `LessonsLifecycleController`, mirroring how `LessonAttachmentsApiController` was split from `LessonsApiController` in ULP-4.0c.
- `lesson-form.html` already long. The new picker + conditional sections are HTML; they live in the same template since splitting Thymeleaf templates across files for a single form is more harmful than helpful (state and CSS scope get fragmented). The corresponding behavior moves to a new `lesson-form-type.js`.

## Risks / Trade-offs

- **PDF file size cap remains at 20 MB (the attachment service cap).** A lecturer with a 50 MB PDF must compress or split. → Mitigation: out-of-band upload returns a clear "file too large" message. If the cap ever needs raising, it is a single config change in the attachment service plus a UI hint update.
- **Video upload occupies 200 MB of disk per lesson and is served directly by Tomcat.** No CDN. → Mitigation: this is acceptable for the project's MVP usage; HLS/streaming is explicitly out of scope. The `LessonVideoStorageService.delete` path on type-switch and lesson-delete prevents long-term accumulation.
- **YouTube/Vimeo URL regex may need updates if those providers change their URL formats.** → Mitigation: keep the regex narrow and unit-tested against the canonical formats; reject anything else with a clear error. New formats are an additive fix (add a pattern, no migration).
- **Two-step UX (create draft → upload → save as new type) is a learning curve.** → Mitigation: the form-side JS hides the type "Save" affordance until the file is uploaded; the modal explains the consequence of switching. The `lesson-attachments.js` already established this pattern with XHR upload + progress bar — lecturers familiar with attaching files will recognize the flow.
- **CHECK constraint behavior under MySQL 8.0 is supported but only enforced from 8.0.16 onward.** → Mitigation: the project pins MySQL 8.0; the README/dev docs already require this version. CI and dev environments use the same minor.
- **HTML5 `<video>` requires HTTP Range for smooth seeking.** Spring Boot's static handler supports it, but a custom controller must implement it. → Mitigation: the new video stream endpoint uses `ResourceRegion`-based responses or delegates to `ResourceHttpRequestHandler` so Range is handled correctly. Covered by the integration test.
- **Open MIME-sniffing risk on the upload endpoints.** Browsers report a MIME type, but it is client-controlled. → Mitigation: the storage service performs a "magic-bytes" check (PDF: `%PDF-`; MP4: `ftyp` box at offset 4) in addition to the reported MIME. Reject if either disagrees. This matches what `LessonAttachmentStorageService` already does for the attachment whitelist.
- **Multipart max-file-size needs to allow 200 MB.** Spring Boot defaults at 1 MB. → Mitigation: update `application.properties` with `spring.servlet.multipart.max-file-size=200MB`, `spring.servlet.multipart.max-request-size=210MB` (10 MB headroom for the form payload). Endpoint guards inside the controller still reject anything actually over 200 MB.
- **Working-tree state at change start:** the HtmlSanitizer pretty-print fix from a prior turn is uncommitted. → Mitigation: the implementation step commits it as a separate logical commit alongside the V16 migration so the spec idempotency requirement reflects the existing fix without bundling unrelated changes.

## Migration Plan

1. **Flyway migration V16__lesson_content_types.sql** runs on app boot; it is additive (4 nullable columns + 1 FK + 1 CHECK + 1 index) with no data backfill needed since the column default handles existing rows.
2. **No downtime expected** — Hibernate is in `validate` mode and the new columns are NOT NULL only for `content_type`, which has a default. Existing controllers that do not know about the new columns continue to work; new code reads the discriminator and routes.
3. **Rollback strategy** — if V16 produces incidents, the back-out is to:
   - Stop the application.
   - Run a corrective migration that drops the CHECK constraint first, then `pdf_attachment_id`, `video_url`, `video_provider`, `content_type`. The existing rich-text rows survive because `content_richtext` is untouched.
   - Revert the application JAR to the prior version.
4. **Feature flag** — none. The picker defaults to RICHTEXT and existing lecturers see no behavioral change unless they pick PDF or VIDEO.
5. **Manual smoke** after deploy: create one lesson of each type, switch one between types, view as student, delete a PDF lesson and confirm the attachment row + on-disk file are both removed.
