# Tasks: sprint3-progress-and-lesson-comments

## 1. Entities & repositories (DB subtasks #176, #180)

- [x] 1.1 Create `com.ulp.entities.LearningProgress` mapping `learning_progress` (status constants NOT_STARTED/IN_PROGRESS/COMPLETED, business helpers `markCompleted()`/`revertToInProgress()`, `@PrePersist`/`@PreUpdate` timestamps; follow `Lesson.java` style, English Javadoc)
- [x] 1.2 Create `com.ulp.entities.Comment` mapping `comments` (NO `@SQLRestriction` — deleted roots must stay queryable for placeholders; helpers `edit(content)`, `markDeleted()`; moderation constants)
- [x] 1.3 Create `LearningProgressRepository` (findByUserIdAndLessonId, query for COMPLETED lesson-id set of a user within a lesson-id collection)
- [x] 1.4 Create `LessonCommentRepository` (findByLessonIdOrderByCreatedAtAsc filtered to APPROVED, plus author-name projection or join fetch strategy per design D7) ← (verify: entities compile against real schema — run app or integration test; no ddl-auto drift; Comment queries can still see is_deleted=1 roots)

## 2. Learning progress backend (BE subtask #177)

- [x] 2.1 Create `LearningProgressService` with the four authz gates (mirror `StudentLessonDetailService`), `recordOpened(classId, lessonId, userId)` idempotent upsert catching `DataIntegrityViolationException`, and `toggleCompletion(...)` per spec
- [x] 2.2 Create `LearningProgressController` POST `/my/classes/{classId}/lessons/{lessonId}/progress/toggle` → PRG redirect to canonical lesson URL + `flashSuccess` Vietnamese message via IConstant; gate failure → 404 (`EntityNotFoundException` through GlobalExceptionHandler)
- [x] 2.3 Wire `recordOpened` into `StudentLessonsController.view()` after successful `getLessonDetail`, wrapped so failures log but never break rendering
- [x] 2.4 Extend `StudentLessonsService`/DTOs with progress aggregates per design D4 (completed flags, per-section counts, class totals + percent; `LessonDetailView.completed`) ← (verify: percent math excludes DRAFT + soft-deleted lessons; 0-lesson class → 0% without exception; single extra query, no N+1)

## 3. Learning progress frontend (FE subtask #178)

- [x] 3.1 Sidebar class card: overall progress bar + "n/m bài giảng · p%" in `class-lessons.html` (+ CSS in `student-lessons.css` or new file)
- [x] 3.2 Chapter switcher entries show per-section "x/y"; rail lesson cards show ✓ completed badge
- [x] 3.3 Toggle button on inlined lesson detail (form POST, two visual states) + flash-toast drain if the page lacks one (follow `#flash-data` + UlpToast pattern) ← (verify: button posts with CSRF, redirect returns to same section+lesson, toast shows; no inline alert divs anywhere)

## 4. Lesson comments backend (BE subtask #181)

- [x] 4.1 Create `LessonCommentsDtos` (CommentRow with canEdit/canDelete/lecturer/deleted/replies, CreateRequest/EditRequest with Bean Validation)
- [x] 4.2 Create `LessonCommentsService`: authz (gates + enrolled-or-owning-lecturer), list assembly (in-memory threading, deleted-root placeholder rule, APPROVED-only), create (trim + 1..2000 validation, parent same-lesson check, reply-to-reply flattening), editOwn (author only, sets is_edited), delete (author or owning lecturer)
- [x] 4.3 Create `LessonCommentsApiController` GET/POST/PUT/DELETE under `/api/lessons/{lessonId}/comments` using `AjaxResponses` envelope; exception mapping 400/403/404/500 per spec ← (verify: outsider and DRAFT-lesson calls return 404 not 403/200; cross-lesson parentId → 400; other-student delete → 403)

## 5. Lesson comments frontend (FE subtask #182)

- [x] 5.1 Comments panel shell in `class-lessons.html` under the lesson detail (data-lesson-id attr, only when lessonDetail != null) + `static/css/lesson-comments.css`
- [x] 5.2 Create `static/js/lesson-comments.js`: load list, render via createElement/textContent, composer + reply + edit + delete flows with inline confirm, CSRF meta headers, UlpToast feedback, empty/error states ← (verify: `<script>` in content renders as text; no innerHTML with user data anywhere in the file)

## 6. Tests (Test subtasks #179, #183)

- [x] 6.1 `LearningProgressServiceTest` (@SpringBootTest @Transactional): first-open creates IN_PROGRESS, re-open idempotent, toggle both directions, toggle-without-open, non-enrolled/draft denied, aggregate counts exclude DRAFT
- [x] 6.2 `LearningProgressControllerTest` (MockMvc): toggle redirect + flash, 404 for outsider, CSRF enforced
- [x] 6.3 `LessonCommentsServiceTest`: create root/reply, flatten reply-to-reply, blank/2000+ rejected, cross-lesson parent rejected, edit own vs other (403), delete own / lecturer-any / other-student denied, deleted-root placeholder + deleted-leaf omitted, DRAFT lesson 404
- [x] 6.4 `LessonCommentsApiControllerTest` (MockMvc): status codes per spec, JSON envelope shape, authz matrix ← (verify: run full `mvnw test` — all new AND existing tests green)

## 7. Constants & polish

- [x] 7.1 Add new IConstant entries (URLs, ATTR_*, MSG_* Vietnamese flash/toast messages) used ≥2 places; controllers use static import, no `implements`
- [x] 7.2 Self-review against project rules: files ≤ ~200 lines, English comments, no entity leak from controllers, UlpToast-only feedback ← (verify: `mvnw compile` clean; grep confirms no `alert(` / inline `<div class="alert">` added)
