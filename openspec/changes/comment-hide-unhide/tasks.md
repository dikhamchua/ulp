## 1. Audit-log entity (maps existing table — no migration)

- [x] 1.1 Create `entities/CommentModeration.java` mapping `comment_moderation` (id PK, comment_id, moderated_by, action ∈ {APPROVED,REJECTED}, reason NULL, created_at) with a factory `record(commentId, moderatedBy, action)`; expose `ACTION_APPROVED`/`ACTION_REJECTED` constants
- [x] 1.2 Create `repository/CommentModerationRepository extends JpaRepository<CommentModeration, Long>` ← (verify: entity maps the V1 table exactly; no schema/migration added)

## 2. Entity + repository read paths

- [x] 2.1 `entities/Comment.java` — add `hide()` (set `moderationStatus = REJECTED`) and `unhide()` (set `APPROVED`) helpers
- [x] 2.2 `repository/LessonCommentRepository.java` — add moderator-listing variants taking a status set: `findByLessonIdAndParentIdIsNullAndDeletedFalseAndModerationStatusIn(lessonId, Collection<String> statuses, Pageable)` and `findByParentIdInAndModerationStatusIn(parentIds, Collection<String> statuses)`; keep the existing single-status methods for the student path ← (verify: student path still calls APPROVED-only methods; empty IN () still guarded)

## 3. Service — authz, listing, hide/unhide

- [x] 3.1 `LessonCommentsService.authorize(lessonId, userId, Role role)` — run existing lesson gates, then admit ADMIN/HEAD (bypass enrollment) OR owning lecturer OR ACTIVE-enrolled student; else 404
- [x] 3.2 Add `private boolean isModerator(ClassEntity clazz, Long userId, Role role)` = owning lecturer OR role ∈ {ADMIN,HEAD}
- [x] 3.3 `listPage(lessonId, userId, Role role, page, size)` — moderator → load roots+replies with status IN (APPROVED, REJECTED) and pass `moderator=true` to the assembler; student → unchanged APPROVED-only path
- [x] 3.4 Add `hide(lessonId, commentId, userId, Role role)` — require `isModerator` else `AccessDeniedException`; load live comment (404 if foreign); if APPROVED set REJECTED + write `comment_moderation` (action REJECTED); idempotent if already REJECTED
- [x] 3.5 Add `unhide(lessonId, commentId, userId, Role role)` — symmetric restore to APPROVED + audit (action APPROVED); idempotent ← (verify: student caller → 403; ADMIN/HEAD non-enrolled → allowed; audit row has correct moderated_by/action; foreign id → 404)

## 4. Assembler + DTO

- [x] 4.1 `dto/LessonCommentsDtos.CommentRow` — add `boolean hidden` and `boolean canModerate`
- [x] 4.2 `service/CommentThreadAssembler.java` — thread a `moderator` flag through `assemble`/`buildLevel`/`row`; a REJECTED node → real row `hidden=true, canModerate=true` (do NOT prune like deleted); APPROVED node for moderator → `canModerate=true`; non-moderator output unchanged (`hidden=false, canModerate=false`) ← (verify: student assembly identical to before; deleted-node placeholder logic untouched)

## 5. Controller

- [x] 5.1 `controller/LessonCommentsApiController.java` — pass `user.getRole()` into `listPage`, `create`/`edit`/`delete` authorize path as needed
- [x] 5.2 Add `POST /{commentId}/hide` and `POST /{commentId}/unhide` → call service, return `AjaxResult.success()`; map `AccessDeniedException`→403, `EntityNotFoundException`→404, other→500 (logged) ← (verify: routes require auth; 403 for students; 404 no-leak)

## 6. Constants

- [x] 6.1 `common/IConstant.java` — add `MSG_COMMENT_MODERATE_FORBIDDEN` and any UI label constants needed server-side (keep JS labels in JS)

## 7. Frontend

- [x] 7.1 `static/js/lesson-comments-render.js` — in `actionRow`, when `c.canModerate` add an `Ẩn` button (visible comment) or `Mở lại` (when `c.hidden`), using the existing inline-confirm pattern; render the bubble dimmed with an "Đã ẩn" label when `c.hidden`
- [x] 7.2 `static/js/lesson-comments.js` — no data-flow change needed beyond issuing `POST base + '/' + id + '/hide'` and `/unhide` via `mutate` (reuse `api`/`mutate`/`reload`)
- [x] 7.3 `static/css/lesson-comment-thread.css` — add `.is-hidden` styling (reduced opacity + "Đã ẩn" tag) distinct from `.is-deleted` ← (verify: hidden style is visually distinct; students never receive hidden rows so never see this state)

## 8. Tests

- [x] 8.1 `LessonCommentsServiceTest` — moderator hide→listed hidden to moderator, absent for student; student hide/unhide→403; ADMIN/HEAD non-enrolled allowed; idempotent hide/unhide; audit row asserted; foreign id→404
- [x] 8.2 `LessonCommentsApiControllerTest` — 200 hide/unhide as lecturer/ADMIN; 403 as student; 404 foreign id ← (verify: all 8 spec scenarios covered; existing comment tests still green)

## 9. Verification

- [x] 9.1 `mvnw.cmd test` green (new + existing comment tests)
- [ ] 9.2 Manual smoke: lecturer view (`classes/detail-lessons.html`) shows Ẩn/Mở lại; student view (`student/class-lessons.html`) never shows hidden comments
- [x] 9.3 `openspec` change consistency: proposal/design/spec/tasks aligned; no migration added

## 10. Page-level gate widening for moderators (design D7)

- [x] 10.1 `StudentLessonsService.listClassLessons(classId, userId, Role role)` — load class first, then admit ADMIN/HEAD (bypass enrollment) OR owning lecturer OR ACTIVE-enrolled student; else no-leak 404. Student behaviour unchanged
- [x] 10.2 `StudentLessonDetailService.getLessonDetail(classId, lessonId, userId, Role role)` — run the shared lesson gates first (moderator gains nothing on a deleted/unpublished lesson), then the same widened access gate
- [x] 10.3 `StudentLessonsController.view()` + `redirectStandaloneLesson()` — thread `user.getRole()` into both service calls
- [x] 10.4 Update existing callers/tests for the new signature (`LearningProgressServiceTest` passes `Role.STUDENT`); add tests: owning-lecturer / ADMIN / HEAD (not enrolled) can list + view detail; non-enrolled non-moderator → 404; deleted class → 404 even for ADMIN ← (verify: student path identical; no migration added)
- [x] 10.5 `StudentLessonsController.recordOpenedQuietly(...)` — skip progress recording for non-STUDENT callers so a moderator opening a lesson never runs `recordOpened` (avoids its enrollment gate throwing + the resulting WARN log noise); progress is a student-only artifact. Add controller test: owning-lecturer opening a lesson writes NO progress row ← (verify: student still accrues progress; no WARN emitted for moderators)
- [x] 10.6 Tighten `moderator_open_records_no_progress_and_no_warn()` so it actually locks the D7 guard: the pre-existing `progressRepository...isEmpty()` assert holds even without the guard (recordOpened's gate throws before persisting), so add `@MockitoSpyBean LearningProgressService` + `verify(learningProgressService, never()).recordOpened(anyLong(), anyLong(), anyLong())` — spy (not mock) keeps STUDENT tests' real progress writes intact; reverting the guard now fails the verify ← (verify: all 8 tests in the class stay green; STUDENT progress path unchanged)
