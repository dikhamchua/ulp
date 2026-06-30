## Why

Lessons currently support only a single content format: HTML rich-text authored with Quill and sanitised through Jsoup. Sprint 3's learning experience demands three first-class content formats so lecturers can deliver material in whichever shape fits the topic — long-form notes, reference documents, or recorded explanations — without forcing one format to imitate the others.

This change closes Sprint 3 issues #44 (story), #171 (BE), and #172 (FE) for PDF viewing, and adds video support as a co-equal content type so the lesson library is feature-complete for the sprint.

## What Changes

- **NEW**: Lecturer chooses a `content_type` when creating a lesson: `RICHTEXT` (existing), `PDF` (upload a PDF, students see it embedded inline), or `VIDEO` (paste a YouTube/Vimeo URL OR upload an MP4 ≤ 200 MB).
- **NEW**: Lesson edit form lets lecturer switch a lesson's `content_type` after creation; a confirm modal warns that data tied to the old type will be removed, and the service-layer cleanup wipes orphaned files (PDF attachment row + on-disk file, uploaded MP4 + on-disk file) so nothing leaks.
- **NEW**: Student lesson-detail page renders different DOM per type — sanitised rich-text article (RICHTEXT), `<embed type="application/pdf">` with a "Open in new tab" fallback link for browsers without inline PDF (PDF), 16:9 iframe for YouTube/Vimeo or HTML5 `<video controls>` for the uploaded MP4 case (VIDEO).
- **NEW**: Lesson list (lecturer + student section list) shows a small badge per row so the format is visible without opening the lesson.
- **NEW**: Two service classes — `LessonVideoStorageService` (uploads/lessons/{id}/video/{uuid}.mp4, max 200 MB, MIME = `video/mp4` only) and a thin reuse layer over `LessonAttachmentStorageService` to bind a single PDF attachment as the lesson's "main" PDF via FK `lessons.pdf_attachment_id`.
- **NEW**: Three REST endpoints under `/lecturer/.../lessons/{id}/content/` — `pdf` (multipart PDF upload → set `pdf_attachment_id`), `video` (multipart MP4 upload → set `video_url` + `video_provider=UPLOAD`), `video-url` (set external URL after validating YouTube/Vimeo pattern + provider). Existing form POST (`/lecturer/.../lessons/{id}`) still handles title/status/type/rich-text — file uploads happen out-of-band so submit stays lightweight.
- **MODIFIED**: `LessonAttachmentsService.delete()` now also clears `lessons.pdf_attachment_id` if the deleted attachment is the lesson's main PDF, preventing dangling FKs.
- **MODIFIED**: Student `LessonDetailView` carries `contentType`, `pdfDownloadUrl`, `videoUrl`, `videoProvider`; service builds these per type with the same enrollment + PUBLISHED gates already enforced for rich-text + attachment downloads.
- **DEPRECATED-IN-SCOPE**: No DOCX support — lecturers convert to PDF. This is a conscious tradeoff to avoid third-party viewer (Google Docs Viewer leaks the file URL) or a server-side conversion dependency (LibreOffice headless) that the project does not currently need.
- **NEW**: Flyway migration `V16__lesson_content_types.sql` adds `content_type` (NOT NULL DEFAULT `'RICHTEXT'`), `pdf_attachment_id` (BIGINT NULL FK), `video_url` (VARCHAR(500) NULL), `video_provider` (VARCHAR(20) NULL). A CHECK constraint enforces type-specific column non-null. Existing lessons backfill to RICHTEXT via the column default — no separate migration script needed.

## Capabilities

### New Capabilities

(none — this change extends the existing `lessons-crud` capability)

### Modified Capabilities

- `lessons-crud`: Adds content-type discriminator + PDF and VIDEO format requirements alongside the existing RICHTEXT requirements. Adds type-switch behavior. Adds PDF/Video upload sub-flows.

## Impact

- **Affected code (production)**: `Lesson` entity, `LessonDtos`, `LessonsService` (create/update dispatch + type-switch cleanup), `LessonsLifecycleController` (existing form POST unchanged in shape but accepts new `contentType` field), new `LessonContentApiController` for the 3 upload/url endpoints, new `LessonVideoStorageService`, `LessonAttachmentsService.delete()` (clear FK side-effect), `StudentLessonDetailService` (per-type view-model), `IConstant` (new constants). Templates: `classes/lesson-form.html` (type picker + conditional UI + confirm modal), `student/lesson-detail.html` (per-type render block), `classes/detail-lessons.html` (type badge). New static asset `js/lesson-form-type.js` and CSS additions to `lesson-attachments.css` or a new file.
- **Schema**: 1 Flyway migration (V16). Adds 4 columns + 1 FK + 1 CHECK + 1 index. Backward compatible — existing rows default to RICHTEXT and pass the CHECK (their `content_richtext` is already populated).
- **APIs**: 3 new endpoints under `/lecturer/classes/{classId}/sections/{sectionId}/lessons/{lessonId}/content/`. No breaking changes to existing endpoints; the existing form POST gains an optional `contentType` parameter (defaults to RICHTEXT for create, preserved on update).
- **Configuration**: `spring.servlet.multipart.max-file-size` and `max-request-size` need to allow at least 200 MB for the video upload endpoint scope. Confirm at apply time whether the global limit or an endpoint-scoped limit fits the project pattern.
- **Storage**: New folder `uploads/lessons/{id}/video/` for MP4 files. The existing `uploads/lessons/{id}/attachments/` continues to host PDFs (PDF lesson = one attachment row marked as main + zero or more accessory attachments).
- **Tests**: New service unit tests for type dispatch + type-switch cleanup; new student detail view tests per type; one integration test exercising the three flows end-to-end.
- **Out of scope**: DOCX, progress tracking (ULP-4.5), Q&A (ULP-4.6), transcoding, thumbnails, HLS/DASH, subtitles, watch analytics, backfilling old HtmlSanitizer-polluted rich-text content (the sanitiser bug fix in the working tree is sufficient; old rows self-heal when next saved).
