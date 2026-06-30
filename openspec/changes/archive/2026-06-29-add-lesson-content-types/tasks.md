## 1. Database migration

- [x] 1.1 Create `src/main/resources/db/migration/V16__lesson_content_types.sql` adding `content_type VARCHAR(20) NOT NULL DEFAULT 'RICHTEXT'`, `pdf_attachment_id BIGINT NULL` with FK to `lesson_attachments(id)` (no ON DELETE referential action — MySQL 8 rejects SET NULL on a CHECK column; cleared by service-level call), `video_url VARCHAR(500) NULL`, `video_provider VARCHAR(20) NULL`, plus a CHECK constraint that enforces type-specific column non-null and an index `idx_lessons_pdf_attachment` for FK lookups
- [x] 1.2 Run the migration against the dev MySQL instance and confirm existing rows pass the CHECK constraint — verified by running the full test suite which boots Flyway against the live `ulp_db` schema and migrates V16 cleanly

## 2. Configuration

- [x] 2.1 Update `src/main/resources/application.properties` so `spring.servlet.multipart.max-file-size=200MB` and `spring.servlet.multipart.max-request-size=210MB` are set without breaking the existing 20 MB attachment cap (endpoint-level guards still enforce attachment's 20 MB and video's 200 MB independently)

## 3. Constants

- [x] 3.1 Add to `src/main/java/com/ulp/common/IConstant.java` the constants `CONTENT_TYPE_RICHTEXT`, `CONTENT_TYPE_PDF`, `CONTENT_TYPE_VIDEO`, `VIDEO_PROVIDER_YOUTUBE`, `VIDEO_PROVIDER_VIMEO`, `VIDEO_PROVIDER_UPLOAD`, `MAX_VIDEO_SIZE_BYTES = 200L * 1024L * 1024L`, `MSG_LESSON_CONTENT_TYPE_REQUIRED`, `MSG_LESSON_PDF_NOT_UPLOADED`, `MSG_LESSON_VIDEO_NOT_CONFIGURED`, `MSG_VIDEO_URL_INVALID`, `MSG_VIDEO_FILE_TOO_LARGE`, `MSG_VIDEO_FILE_NOT_MP4`

## 4. Entity

- [x] 4.1 Add fields `contentType`, `pdfAttachmentId`, `videoUrl`, `videoProvider` (with `@Column` mappings) to `src/main/java/com/ulp/entities/Lesson.java`; expose getters and setters
- [x] 4.2 Add an instance method `Lesson.switchContentTypeTo(String newType)` that nulls fields not belonging to `newType` and sets `contentType = newType`; file deletion side-effects are orchestrated by `LessonContentTypeSwitcher`
- [x] 4.3 Add a `private static final` set of valid content types and a guard `validateContentType(...)` that throws `IllegalArgumentException` for unknown values (also called from `switchContentTypeTo`)

## 5. DTOs

- [x] 5.1 In `src/main/java/com/ulp/features/lessons/dto/LessonDtos.java`, extend `LessonForm` with `contentType`, `videoUrl`, `videoProvider` plus validation annotations (`@Pattern` for video provider, content type)
- [x] 5.2 Extend `LessonRow` with `contentType` so list views can show the badge
- [x] 5.3 Extend `LessonDetailView` (student) with `contentType`, `pdfDownloadUrl`, `videoUrl`, `videoProvider` so the student template can switch on type
- [x] 5.4 Add `LessonContentSummary` record for the three content endpoints' JSON response

## 6. Embed URL helpers

- [x] 6.1 Create `src/main/java/com/ulp/features/lessons/support/YouTubeEmbedUrl.java` with a static `toEmbedUrl(String canonical)` plus a `matches(String url)` regex check
- [x] 6.2 Create `src/main/java/com/ulp/features/lessons/support/VimeoEmbedUrl.java` with the same shape for Vimeo
- [x] 6.3 Write `src/test/java/com/ulp/features/lessons/support/YouTubeEmbedUrlTest.java` covering canonical `watch?v=` form, short `youtu.be/` form, mobile `m.youtube.com`, the embed form, and rejected non-YouTube URLs
- [x] 6.4 Write `src/test/java/com/ulp/features/lessons/support/VimeoEmbedUrlTest.java` covering numeric `vimeo.com/{id}`, `player.vimeo.com/video/{id}`, and rejected non-Vimeo URLs

## 7. Video storage service

- [x] 7.1 Create `src/main/java/com/ulp/features/upload/LessonVideoStorageService.java` with `store(MultipartFile, Long lessonId): StoredVideo` that validates `content_type = video/mp4`, validates size ≤ 200 MB, performs magic-bytes check (`ftyp` box at offset 4), stores at `uploads/lessons/{lessonId}/video/{uuid}.mp4`, and returns the stored relative path
- [x] 7.2 Add `LessonVideoStorageService.deleteByLessonId(Long lessonId)` that removes all MP4s under `uploads/lessons/{lessonId}/video/`; safe to call when no video exists
- [x] 7.3 Write `src/test/java/com/ulp/features/upload/LessonVideoStorageServiceTest.java` covering happy path, non-MP4 MIME rejected, oversize rejected, magic-bytes mismatch rejected, delete-when-empty no-op, delete-removes-file, replace-on-re-upload, traversal rejected

## 8. Attachment service FK clearing

- [x] 8.1 In `src/main/java/com/ulp/features/lessons/service/LessonAttachmentsService.java`, before deleting an attachment row in `delete()` (and `deleteAllByLesson()`), call `LessonRepository.clearPdfAttachmentId(attachmentId)` to clear any FK from a lesson row pointing at the attachment
- [x] 8.2 Add `LessonRepository.clearPdfAttachmentId(Long attachmentId)` as a `@Modifying` `@Query` setting `pdf_attachment_id = NULL` where `pdf_attachment_id = :id`
- [x] 8.3 Add a unit test in `src/test/java/com/ulp/features/lessons/service/LessonAttachmentsServiceTest.java` that creates a PDF lesson with `pdf_attachment_id` set, deletes the referenced attachment, and asserts that `lessons.pdf_attachment_id` is NULL afterwards
- [x] 8.4 Add `LessonAttachmentsService.uploadMainPdf(...)` that wraps the attachment upload + replaces any previous main PDF + sets `lessons.pdf_attachment_id`

## 9. Type-switch collaborator

- [x] 9.1 Create `src/main/java/com/ulp/features/lessons/service/LessonContentTypeSwitcher.java` with method `applyTo(Lesson lesson, LessonForm form)`; it must (a) validate the new type has its required data, (b) trigger file cleanup for the old type via `LessonAttachmentsService` / `LessonVideoStorageService`, (c) call `lesson.switchContentTypeTo(newType)`
- [x] 9.2 Throw `IllegalArgumentException` with the locked message constants when the new type's data is missing
- [x] 9.3 Mark the method `@Transactional(propagation = REQUIRED)` so it joins the caller's transaction; the entity is flushed AFTER the switch so the CHECK constraint sees the new type-shape, then file deletion runs

## 10. Lesson service dispatch

- [x] 10.1 Update `LessonsService.create()` so it always lands as RICHTEXT (the lecturer flips later via the dedicated content endpoints + edit form) and runs sanitiser only on the RICHTEXT path
- [x] 10.2 Add a new `LessonsService.update(...)` overload accepting a `LessonForm` to detect type changes and delegate to `LessonContentTypeSwitcher.applyTo` for cross-type updates; same-type RICHTEXT updates keep the existing sanitise path; same-type PDF/VIDEO leaves the body fields untouched. Keep the legacy `(String title, String status, String contentHtmlRaw, ...)` overload that wraps the form with the lesson's current type
- [x] 10.3 Update audit-log call in `update()` to also record `content_type` change in the diff metadata when it changed
- [x] 10.4 Adjust `delete()` to also call `LessonVideoStorageService.deleteByLessonId(lessonId)` for VIDEO/UPLOAD case before soft-deleting the lesson (PDF cleanup happens via the existing attachment cascade)
- [x] 10.5 Add `setExternalVideo(...)`, `setUploadedVideo(...)`, `getEditableLesson(...)` helpers used by the content endpoints

## 11. Lecturer controller — form save

- [x] 11.1 Update `LessonsController.create()` and `update()` to bind the new `LessonForm` fields (`contentType`, `videoUrl`, `videoProvider`) and surface validation errors via existing re-render + flash mechanism
- [x] 11.2 Default the form picker to RICHTEXT on create; preselect the lesson's current type on edit

## 12. Lecturer controller — content endpoints

- [x] 12.1 Create `src/main/java/com/ulp/features/lessons/controller/LessonContentApiController.java` with `@PreAuthorize(Roles.PREAUTH_LECTURER_OR_ABOVE)` and three endpoints:
  - `POST /lecturer/classes/{cid}/sections/{sid}/lessons/{lid}/content/pdf` — multipart upload, calls `LessonAttachmentsService.uploadMainPdf` + sets `lessons.pdf_attachment_id`
  - `POST /lecturer/classes/{cid}/sections/{sid}/lessons/{lid}/content/video` — multipart upload, calls `LessonVideoStorageService.store` + sets `video_url`/`video_provider=UPLOAD`
  - `POST /lecturer/classes/{cid}/sections/{sid}/lessons/{lid}/content/video-url` — form-urlencoded, validates `provider` + `url` via the embed helpers, sets `video_url`/`video_provider`
- [x] 12.2 Each endpoint returns JSON via `AjaxResponses`; failures use `badRequest`, `forbidden`, `notFound`, `internalError` patterns from the attachment controller
- [x] 12.3 Authorisation chain reuses `ClassesService.getEditable` (class ownership) and `LessonsReorderService.verifySectionBelongsToClass` (cross-class enumeration guard)

## 13. Video streaming endpoint

- [x] 13.1 Create `src/main/java/com/ulp/features/lessons/controller/LessonVideoStreamController.java` with `GET /api/lessons/{lessonId}/video/stream` `@PreAuthorize("isAuthenticated()")`; the controller resolves the stored MP4 + applies enrolment/PUBLISHED gates and returns the MP4 with `Content-Type: video/mp4`, `Content-Disposition: inline`, and HTTP Range support
- [x] 13.2 Implement Range support via `org.springframework.core.io.support.ResourceRegion` so the HTML5 video element seeks correctly (1 MB default chunk)
- [x] 13.3 Lecturer/HEAD/ADMIN of the class bypass the PUBLISHED gate

## 14. Student detail service

- [x] 14.1 Update `src/main/java/com/ulp/features/student/service/StudentLessonDetailService.java` to populate `contentType`, `pdfDownloadUrl` (built from `pdf_attachment_id` via the same URL pattern as accessory attachments), `videoUrl` (embed form via the URL helpers for external providers, or the stream endpoint URL for UPLOAD), `videoProvider`
- [x] 14.2 Keep the existing four-gate authorization chain unchanged — the new type fields only affect the view-model populated AFTER auth succeeds
- [x] 14.3 Skip the main PDF row from the accessory attachments list so the embed viewer doesn't double up
- [x] 14.4 Extend `StudentLessonDetailServiceTest` with cases for each type and for accessory attachments alongside a PDF main file

## 15. Lecturer template — lesson form

- [x] 15.1 Update `src/main/resources/templates/classes/lesson-form.html` to add a type picker (3 radio cards) right above the existing rich-text editor; bind it via `th:field="*{contentType}"`
- [x] 15.2 Wrap the Quill editor section so it is only visible when `contentType=RICHTEXT` (toggled by `lesson-form-type.js`)
- [x] 15.3 Add a PDF sub-section visible when `contentType=PDF` — show current `pdf_attachment_id` filename if any, plus an upload button that triggers the `/content/pdf` endpoint; show "Bạn cần tải PDF lên trước khi lưu" hint when missing
- [x] 15.4 Add a VIDEO sub-section visible when `contentType=VIDEO` — provider radio (YouTube/Vimeo/Upload), URL input for external, file input for upload, hide/show as the provider changes
- [x] 15.5 Add a hidden confirm modal that appears when the lecturer attempts to submit a different type from the lesson's current type; modal text warns that the data of the old type will be removed permanently
- [x] 15.6 Keep the existing attachments card unchanged — it remains visible for all types

## 16. Lecturer template — lesson list badge

- [x] 16.1 Update `src/main/resources/templates/classes/detail-lessons.html` to render a small type badge per lesson row using `lessonRow.contentType()`

## 17. Student template — lesson detail viewer

- [x] 17.1 Update `src/main/resources/templates/student/lesson-detail.html` to switch the main content block on `lessonDetail.contentType()`:
  - `RICHTEXT`: keep current `<article>` block
  - `PDF`: render `<embed src="..." type="application/pdf">` with a nested `<a target="_blank" rel="noopener">Mở PDF trong tab mới</a>` fallback
  - `VIDEO`: render a 16:9 wrapper containing either an iframe (YOUTUBE/VIMEO) or an HTML5 `<video controls preload="metadata">` (UPLOAD)
- [x] 17.2 Keep the attachments section untouched — it renders alongside any viewer

## 18. Static assets

- [x] 18.1 Create `src/main/resources/static/js/lesson-form-type.js` that listens to the type radio's `change` event, toggles which sub-section is visible, wires the upload buttons (XHR with progress for MP4, fetch for PDF + URL endpoint), pops the confirm modal on attempted type change for an already-saved lesson
- [x] 18.2 Create `src/main/resources/static/css/lesson-content-type.css` covering the type picker cards, the PDF embed wrapper, the 16:9 video wrapper, and the confirm modal
- [x] 18.3 Reference the new JS and CSS files in `lesson-form.html`, `detail-lessons.html`, and `student/lesson-detail.html` as appropriate

## 19. Unit tests

- [x] 19.1 Extend `src/test/java/com/ulp/features/lessons/service/LessonsServiceTest.java` with cases:
  - `create_richtext_persists_sanitised_body`
  - `create_pdf_requires_pre_uploaded_attachment_id`
  - `create_video_youtube_validates_url`
  - `create_video_upload_stores_path`
  - `update_switch_richtext_to_pdf_nulls_body`
  - `update_switch_pdf_to_video_deletes_pdf_attachment_row`
  - `update_switch_video_upload_to_richtext_clears_video_fields`
  - `update_switch_to_video_without_data_rejects_and_preserves_old`
  - `update_records_content_type_change_in_audit_metadata`
- [x] 19.2 Extend `LessonAttachmentsServiceTest` with the FK-clearing case `delete_main_pdf_clears_lesson_pdf_attachment_id_fk`
- [x] 19.3 Extend `StudentLessonDetailServiceTest` with one case per type (`richtext_lesson_populates_content_type_richtext`, `pdf_lesson_returns_pdf_url_and_skips_main_attachment_from_accessory_list`, `video_youtube_lesson_returns_embed_url`, `video_vimeo_lesson_returns_player_url`, `video_uploaded_lesson_returns_stream_endpoint`)

## 20. Integration test

- [x] 20.1 Create `src/test/java/com/ulp/features/lessons/Sprint3LessonContentTypesIntegrationTest.java` covering: lecturer uploads a PDF via the content endpoint and switches the type via the edit form; lecturer sets a YouTube URL; the content endpoint rejects malicious URLs; enrolled student sees PDF viewer markup; enrolled student sees YouTube iframe — full HTTP flow via MockMvc

## 21. Manual smoke + GitHub housekeeping

- [x] 21.1 Build + full test suite passes (451 tests, 0 failures, 0 errors). Manual smoke pass deferred to the verify step in the autopilot pipeline.
- [ ] 21.2 After the change is merged, close GitHub issues #44, #171, #172 with a comment referencing this change name; bonus: tag the change in the commit body so it links back automatically
