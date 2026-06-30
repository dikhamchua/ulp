# Implementation Tasks — `add-lesson-crud`

Tasks group by domain. Each task has a one-line outcome; the verifier
should focus on the `← (verify: ...)` annotations at the end of major
groups.

## 1. Schema & dependencies

- [x] 1.1 Add `org.jsoup:jsoup` (latest 1.17.x or 1.18.x stable) to `pom.xml` under the existing dependencies block.
- [x] 1.2 Create `src/main/resources/db/migration/V14__lessons_table.sql`:
  - Drop legacy columns: `type`, `estimated_minutes`.
  - Rename `sort_order INT` → `display_order SMALLINT NULL` (soft-delete-safe pattern from V13).
  - Adjust `status` CHECK: `('DRAFT', 'PUBLISHED')` — drop `ARCHIVED`.
  - Add `content_richtext LONGTEXT NULL` column for the inline rich-text body.
  - Drop the old `idx_lesson_section` index and replace with the same index plus a `UNIQUE KEY uk_lesson_section_order (section_id, display_order)` (MySQL allows multiple NULLs in a unique index so soft-deleted lessons release their slot).
  - Header comment in the migration file explaining each change. ← (verify: V14 runs cleanly on a fresh DB AND on a DB at V13; unique key constraint actually applied; sample insert with NULL display_order accepted)

> Implementation note: V14 ended up DROPping + re-CREATEing `lessons`
> instead of ALTERing in place. The V1 schema baked an auto-named MySQL
> CHECK constraint into `status VARCHAR(20) CHECK (...)`; without a
> stable name, we cannot drop and re-add it across a sequence of ALTERs.
> Both `lessons` and `activity_lessons` are empty in every environment
> today, so the DROP + CREATE is safe and the resulting schema matches
> the design D4 contract verbatim (named `chk_lesson_status`, nullable
> `display_order`, `uk_lesson_section_order` unique key).

## 2. Backend entities + repositories

- [x] 2.1 Create `src/main/java/com/ulp/entities/Lesson.java`:
  - `@Entity @Table(name = "lessons") @SQLRestriction("is_deleted = 0")`.
  - Fields: `id`, `sectionId`, `title`, `status`, `displayOrder` (`Short`, nullable), `contentRichtext`, `createdBy`, `publishedAt`, `createdAt`, `updatedAt`, `deleted` (boolean mapped to `is_deleted`).
  - Constructors: protected no-arg + business constructor (sectionId, title, displayOrder, createdBy).
  - Business methods: `rename(String)`, `updateContent(String)`, `publish()` (sets status + publishedAt), `unpublish()` (clears publishedAt), `changeOrder(short)`, `markDeleted()` (sets deleted=true AND displayOrder=null).
  - `@PrePersist`/`@PreUpdate` to maintain `createdAt`/`updatedAt`.
- [x] 2.2 Create `src/main/java/com/ulp/entities/LessonActivity.java` mirroring `SectionActivity`: fields `id`, `lessonId`, `type`, `description`, `metadata` (JSON string), `createdBy`, `createdAt`. Public constants for type strings: `TYPE_CREATED`, `TYPE_UPDATED`, `TYPE_PUBLISHED`, `TYPE_UNPUBLISHED`, `TYPE_DELETED`, `TYPE_REORDERED`.
- [x] 2.3 Create `src/main/java/com/ulp/features/lessons/repository/LessonRepository.java`:
  - Extends `JpaRepository<Lesson, Long>`.
  - `List<Lesson> findBySectionIdOrderByDisplayOrderAsc(Long sectionId)`.
  - `Optional<Lesson> findByIdAndSectionId(Long id, Long sectionId)`.
  - `@Query(nativeQuery = true)` `findMaxDisplayOrder(@Param("sectionId") Long sectionId)` returning `short`, bypassing `@SQLRestriction` so live-only rows count.
- [x] 2.4 Create `src/main/java/com/ulp/features/lessons/repository/LessonActivityRepository.java`:
  - `Page<LessonActivity> findByLessonIdOrderByCreatedAtDesc(Long lessonId, Pageable pageable)`. ← (verify: queries compile, `findMaxDisplayOrder` returns `-1` when section is empty; entity has plain getters not Lombok `@Data`)

## 3. HTML sanitiser

- [x] 3.1 Create `src/main/java/com/ulp/common/HtmlSanitizer.java`:
  - Public static `sanitize(String html)` returning sanitised HTML or empty string when input is null/blank.
  - Uses Jsoup `Safelist` configured per the design D2 policy (h1–h6, p, br, hr, strong, b, em, i, u, s, blockquote, pre, code, ol, ul, li, a, img + allowed attributes for `a` and `img`).
  - Allows `data:image/*` URI scheme on `img.src` via `addProtocols("img", "src", "data", "http", "https")`.
  - Allows `http`, `https`, `mailto` on `a.href`.
  - Javadoc explains why this lives in `common` (reusable for future comments / board posts). ← (verify: unit test calls sanitize on a payload including `<script>`, `onclick=`, `javascript:`, data-URI image → first three are stripped, last preserved)

## 4. DTOs + IConstant additions

- [x] 4.1 Create `src/main/java/com/ulp/features/lessons/dto/LessonDtos.java` as a final holder class with records:
  - `LessonRow(Long id, String title, String status, short displayOrder)`.
  - `LessonForm(@NotBlank @Size(max=300) String title, @Pattern(regexp="DRAFT|PUBLISHED") String status, String contentHtml)`.
  - `LessonReorderRequest(List<Long> orderedIds)`.
  - `LessonActivityRow(Long id, String type, String typeLabel, String description, LocalDateTime createdAt)`.
  - Re-use `AjaxResult` from `SectionDtos` — do not duplicate.
- [x] 4.2 Update `src/main/java/com/ulp/common/IConstant.java` adding:
  - View: `VIEW_LESSON_FORM = "classes/lesson-form"`.
  - Attributes (only the ones used across the lessons template + this controller): `ATTR_LESSON = "lesson"`, `ATTR_LESSONS = "lessons"`, `ATTR_LESSON_ID = "lessonId"`, `ATTR_SELECTED_LESSON = "selectedLesson"`.
  - Flash messages: `MSG_LESSON_CREATED`, `MSG_LESSON_UPDATED`, `MSG_LESSON_DELETED`, `MSG_LESSON_PUBLISHED`, `MSG_LESSON_UNPUBLISHED`, `MSG_LESSON_NOT_FOUND`.
  - Status discriminators: `LESSON_STATUS_DRAFT = "DRAFT"`, `LESSON_STATUS_PUBLISHED = "PUBLISHED"`.
  - All values in Vietnamese for UI text; ASCII keys.

## 5. Backend service + writer

- [x] 5.1 Create `src/main/java/com/ulp/features/lessons/service/LessonActivityWriter.java` mirroring `SectionActivityWriter`: constructor injects `LessonActivityRepository` + `ObjectMapper`; expose `write(Long lessonId, String type, String description, Long actorId)` and overload with metadata map.
- [x] 5.2 Create `src/main/java/com/ulp/features/lessons/service/LessonsService.java`:
  - Injects `LessonRepository`, `SectionRepository`, `ClassesService`, `LessonActivityWriter`.
  - `TEMP_ORDER_OFFSET` constant copied from `SectionsService` with the same documenting Javadoc (trần SMALLINT explanation).
  - `listForSection(Long sectionId, Long classId, Long userId, Role role)` — auth via `classesService.getEditable(classId, ...)`; returns `List<LessonRow>` ordered by `display_order`.
  - `create(Long classId, Long sectionId, String title, String status, String contentHtmlRaw, Long userId, Role role)` — auth check, validate section belongs to class via `sectionRepository.findByIdAndClassId`, sanitise content, compute next `display_order = max + 1`, save lesson, write `CREATED` activity.
  - `update(Long classId, Long sectionId, Long lessonId, String title, String status, String contentHtmlRaw, Long userId, Role role)` — auth check, sanitise content, write `UPDATED` only when title OR content actually changed; if status differs, write `PUBLISHED`/`UNPUBLISHED` activity (separate from `UPDATED`).
  - `publish(...)`, `unpublish(...)` — toggle status + activity.
  - `delete(...)` — soft-delete via `lesson.markDeleted()`, write `DELETED` activity.
  - `reorder(Long classId, Long sectionId, List<Long> orderedIds, Long userId, Role role)` — two-phase write copied from `SectionsService.reorder`; emit `REORDERED` activity only for lessons whose index changed.
  - Helper `verifyOrderingMatches` ported from `SectionsService`. ← (verify: every public method calls `classesService.getEditable` exactly once before mutating; sanitiser called before save on every body write; two-phase reorder identical structure to SectionsService)

## 6. Backend controller

- [x] 6.1 Create `src/main/java/com/ulp/features/lessons/controller/LessonsController.java`:
  - `@Controller @RequestMapping("/lecturer/classes/{classId}/sections/{sectionId}/lessons") @PreAuthorize(Roles.PREAUTH_LECTURER_OR_ABOVE)`.
  - `import static com.ulp.common.IConstant.*;` (NO `implements IConstant`).
  - Endpoints, all path-prefixed by `(classId, sectionId)`:
    - `GET /new` — render create form (model: form, mode=create, lessonClass + section context, cancel URL pointing at `/lecturer/classes/{classId}/lessons?section={sectionId}`).
    - `POST /` — submit create; on validation error re-render form; on success redirect back to the lessons tab with the section selected.
    - `GET /{lessonId}/edit` — render edit form with pre-filled `LessonForm`, eager-load activity page (mirror `SectionsController.renderRenameForm`), accept `?tab=info|history` and `?page=` parameters.
    - `POST /{lessonId}/edit` — submit edit; on validation error re-render with activity page included; on success redirect to the same edit URL (stay on page like `renameSection`).
    - `POST /{lessonId}/publish` — service publish + flash + redirect.
    - `POST /{lessonId}/unpublish` — service unpublish + flash + redirect.
    - `DELETE /{lessonId}` — JSON `AjaxResult` envelope, mirror `SectionsController.deleteSection`.
    - `POST /reorder` — JSON, mirror `SectionsController.reorderSections`.
  - Error handling helpers `forbidden()`, `notFound()`, `badRequest()`, `internalError()` copied from `SectionsController`.
  - Helper `buildEditUrl(classId, sectionId, lessonId)`, `buildLessonsTabUrl(classId, sectionId)`.
- [x] 6.2 Update `src/main/java/com/ulp/features/lessons/controller/SectionsController.java`:
  - Inject `LessonRepository` (or new `LessonsService` if preferred for symmetry — service is cleaner for auth).
  - In `renderLessonsPage`, when `selectedSection != null`, fetch lessons of the section and add to model under `ATTR_LESSONS`; when null, add an empty list (template handles the empty state). ← (verify: `LessonsControllerIntegrationTest` covers all happy paths AND a cross-class attack — lecturer L2 posting to L1's class returns 403; reorder rejects mismatched ids with 400)

## 7. Frontend — lesson form template

- [x] 7.1 Create `src/main/resources/templates/classes/lesson-form.html`:
  - CSS load order: `app-shell` → `class-detail` → `detail-page` → `admin` → `classes` → `sections` → `quill.snow` (CDN).
  - Sticky toolbar with Back link (`cancelUrl`) and Save submit referencing `form="lessonForm"`.
  - Header: title (Tạo / Đổi tên bài giảng), status pill (mode badge `TẠO MỚI` or `CHỈNH SỬA`), meta showing class name + section name.
  - Tab strip rendered only when `mode='edit'`, two tabs: Thông tin + Lịch sử. Client-side toggle pattern copied from `section-form.html` (data-tab-target buttons + JS hide/show + URL `?tab=` sync via `history.replaceState`).
  - Info panel inside `<form id="lessonForm">`:
    - Title input (text, `th:field="*{title}"`, required, maxlength 300, inline error).
    - Status select (DRAFT / PUBLISHED) with Vietnamese labels.
    - Quill editor mounted on a `<div id="lessonContentEditor">`; hidden `<input name="contentHtml" id="lessonContentHtml">` that JS populates on submit.
  - History panel: `<table class="detail-table">` audit listing identical to section-form history tab.
  - End-of-page script: load Quill from `cdn.jsdelivr.net/npm/quill@2.x`, init editor with toolbar `[ [{header:[1,2,3,false]}], ['bold','italic','underline'], [{list:'ordered'},{list:'bullet'}], ['link','image'], ['clean'] ]`, copy `editor.root.innerHTML` to the hidden input on form submit.
- [x] 7.2 Add CSS in `src/main/resources/static/css/sections.css` (do NOT create a new file — lessons live on the same UX page so styles co-locate):
  - `.lesson-item`, `.lesson-card`, `.lesson-status-pill` modifiers `.is-draft` (amber, using `--color-warning*`) and `.is-published` (green, using `--color-success*`).
  - `.lesson-list` grid / list layout consistent with the existing folder list rhythm.
  - `.ql-editor { min-height: 320px }` and override font to inherit project default. ← (verify: lesson-form renders with Quill toolbar, content survives a round-trip without losing formatting; tab toggle costs zero network)

## 8. Frontend — lessons tab list update

- [x] 8.1 Update `src/main/resources/templates/classes/detail-lessons.html`:
  - Replace the `Sắp ra mắt (ULP-4.0b)` content of the right column with:
    - Header band: title (`Bài giảng của Chương X` or `Tất cả bài giảng`), plus a primary "Tạo bài giảng" button linking to `/lecturer/classes/{cid}/sections/{sid}/lessons/new` — disabled with tooltip when `selectedSectionId == null`.
    - When `selectedSectionId == null`: empty hint card "Chọn một chương ở cột bên trái để xem bài giảng".
    - When `selectedSectionId != null` and `lessons` is empty: empty state with primary CTA pointing at the create form.
    - When lessons exist: `.lesson-list` rendering each lesson's title, status pill, last-updated timestamp, and a 3-dot menu (Sửa link, Xuất bản/Chuyển nháp form-POST, Xoá button data-lesson-id).
- [x] 8.2 Update `src/main/resources/static/js/sections.js`:
  - Add `initLessonMenus()`: delegate clicks on `.lesson-menu-trigger` to open the 3-dot dropdown for that lesson row, close on outside-click or `Esc`.
  - Add `onLessonListClick()`: handle `.lesson-btn-del` AJAX DELETE to the lesson endpoint, optimistic DOM remove, toast feedback.
  - Add `applyLessonSearch()`: filter `.lesson-item` by title text (reuse the existing `normalise()` helper) — wire to a new input if the lesson search bar is added in 8.1; otherwise skip until the search is needed. ← (verify: clicking the 3-dot opens just one menu at a time; outside-click closes it; AJAX delete removes the DOM node and toasts success; search input filters lesson items but not folders)

> Implementation note for 8.2: the design didn't surface a lesson-side
> search input in 8.1, so `applyLessonSearch()` is intentionally NOT
> added — it would have nothing to bind against. The unified
> `closeAllMenus` + `bindMenuToggles` plumbing replaced the
> section-only menu code; both `#sectionList` and `#lessonList` now
> share the same single-open-menu UX with shared outside-click + Esc
> handlers. If a lesson search bar lands later, the existing
> `normalise()` helper is ready to be reused without churn.

## 9. Tests

- [x] 9.1 Create `src/test/java/com/ulp/features/lessons/service/LessonsServiceTest.java`:
  - Bootstrap helpers (saveClass, saveSection, ensureExtraLecturer) borrowed from `SectionsServiceTest`.
  - `create_two_lessons_returns_rows_with_increasing_display_order`.
  - `update_changes_title_and_writes_updated_activity` + metadata old/new check.
  - `update_with_unchanged_fields_does_not_pollute_history`.
  - `update_sanitises_html` — input `<p>OK</p><script>x()</script>`, expect persisted body has no `<script>`.
  - `publish_changes_status_and_writes_published_activity`; symmetric `unpublish_*`.
  - `delete_clears_display_order_and_writes_deleted_activity`.
  - `create_after_delete_does_not_collide` (V13-style regression).
  - `reorder_writes_reordered_activity_only_for_moved_lessons` (mirrors `SectionsService` test).
  - `reorder_with_wrong_ids_throws_illegal_argument`.
  - `non_owner_lecturer_cannot_create_or_update_or_delete`.
- [x] 9.2 Create `src/test/java/com/ulp/features/lessons/controller/LessonsControllerIntegrationTest.java`:
  - `get_new_form_renders_for_owner` (200 + view name + `mode=create`).
  - `post_create_with_valid_input_redirects_with_flash` (302 + flashSuccess + DB row).
  - `post_create_with_blank_title_re_renders_form_with_field_error`.
  - `post_create_sanitises_script_in_body`.
  - `get_edit_form_pre_fills_form_and_eager_loads_activity_page`.
  - `get_edit_tab_history_marks_active_tab`.
  - `post_edit_with_valid_input_redirects_to_edit_page`.
  - `post_publish_changes_status` and `post_unpublish_changes_status`.
  - `delete_returns_json_and_soft_deletes_lesson`.
  - `post_reorder_with_full_ordered_ids_returns_ok`.
  - `student_cannot_access_lesson_endpoints` (GET → 403).
  - `anonymous_redirects_to_login`.
  - `lecturer_from_another_class_is_rejected` — explicitly tests the cross-class attack vector (path-variable enumeration).
- [x] 9.3 Run `./mvnw.cmd test` — expect total to grow from 326 to ~350+ tests, all pass. ← (verify: NO test skipped, NO test using `@Disabled`; total count documented in the verify report)

> Final test count: **357 tests, 0 failures, 0 errors, 0 skipped**
> (326 baseline + 31 new = 11 LessonsServiceTest + 14 LessonsControllerIntegrationTest + 6 HtmlSanitizerTest).

## 10. Manual smoke verification

- [ ] 10.1 Boot the app locally: `./mvnw.cmd spring-boot:run`. Confirm Flyway logs `Migrating schema 'ulp_db' to version 14`.
- [ ] 10.2 Log in as `lecturer@ulp.edu.vn`, open a class, go to the Bài giảng tab. Folders column lists sections; content column shows the "Chọn một chương" empty state.
- [ ] 10.3 Click a section, confirm content column updates to that section's lessons (zero-state on a brand-new section).
- [ ] 10.4 Click "Tạo bài giảng", fill title + body (paste a `<script>` to verify sanitisation), pick PUBLISHED, submit. Verify redirect to the lessons tab with the new lesson visible, status pill `Xuất bản`.
- [ ] 10.5 Click the 3-dot → Sửa, change title, save. Verify redirect to `?tab=info` and the History tab on edit page contains both `CREATED` and `UPDATED` rows.
- [ ] 10.6 Trigger Xuất bản / Chuyển nháp from the action menu; verify the status pill updates and a new history row exists.
- [ ] 10.7 Drag-reorder two lessons; verify the order persists after a page refresh.
- [ ] 10.8 Delete a lesson; verify it disappears and is hidden from the list (DB row remains with `is_deleted = 1`, `display_order = NULL`). Recreate a lesson and verify the freed slot is reused. ← (verify: every step above produced the expected screen and DB state; no JS console errors at any step)

> Group 10 (manual smoke) is left unchecked for the verifier to run
> through the local Spring Boot instance; the automated test suite
> already exercises the behaviour from 10.4–10.8 in code.
