## Why

Lesson discussion threads (ULP-4.6) currently have no moderation. Any enrolled
student or the owning lecturer can post, but a moderator has no way to take down
an inappropriate comment without hard-deleting it (which is destructive and
attributed to the author). Story ULP-11.7 (#90) asks for the ability to **hide**
an unsuitable comment and **unhide** it later — post-moderation, not pre-approval.

The data model already supports this: `comments.moderation_status` accepts
`REJECTED`, a `comment_moderation` audit-log table exists, and the read path
already filters to `APPROVED` only. This change wires those pieces into a
lecturer/admin-facing hide/unhide action. No schema migration is required.

## What Changes

- Add a **hide** action that sets a comment's `moderation_status` to `REJECTED`,
  and an **unhide** action that restores it to `APPROVED`. Both are idempotent.
- Restrict the action to **moderators**: the owning lecturer of the class, plus
  any `ADMIN` or `HEAD`. Students can never hide/unhide.
- **Widen `authorize()`** so `ADMIN`/`HEAD` may view a lesson's thread even
  without an ACTIVE enrollment (needed so they can moderate). Enrolled students
  and the owning lecturer keep their existing access unchanged.
- **Moderator-aware listing**: when the caller is a moderator, the thread returns
  hidden comments too (flagged `hidden=true`) so they can be seen and unhidden.
  For students the read path is unchanged — hidden comments **disappear entirely**,
  and a hidden root drops its whole thread (same behaviour as a deleted root).
- **Audit every action** into the existing `comment_moderation` table
  (`comment_id`, `moderated_by`, `action` ∈ {APPROVED, REJECTED}, `reason` null).
- **Inline UI**: on a comment a moderator sees, render a `Ẩn` button; on a hidden
  comment (shown dimmed with an "Đã ẩn" label) render `Mở lại`. Reuses the
  existing inline-confirm pattern; feedback via `UlpToast`.
- **Scope guard (YAGNI)**: single-comment actions only. No bulk hide, no reason
  input, no separate admin moderation page in this change.

## Capabilities

### New Capabilities
- `comment-moderation`: moderator hide/unhide of a lesson comment — the authz
  rule (owning lecturer / ADMIN / HEAD), status transitions APPROVED↔REJECTED,
  audit logging, moderator-aware listing, and the inline hide/unhide UI.

### Modified Capabilities
<!-- The lesson-comments read path (ULP-4.6) is not owned by an existing openspec
     spec, so its behaviour change (moderator sees hidden comments) is captured in
     the new comment-moderation spec below rather than as a spec delta. -->

## Impact

- **Modified backend** `features/comments/`:
  - `service/LessonCommentsService.java` — `authorize()` takes the caller `Role`
    (ADMIN/HEAD bypass enrollment); new `hide()`/`unhide()`; `listPage()` returns
    hidden comments to moderators.
  - `repository/LessonCommentRepository.java` — status-set (IN) variants for the
    moderator listing path.
  - `service/CommentThreadAssembler.java` — emit hidden rows (no prune) and the
    `canModerate` flag when the caller is a moderator.
  - `dto/LessonCommentsDtos.java` — add `hidden`, `canModerate` to `CommentRow`.
  - `controller/LessonCommentsApiController.java` — `POST /{commentId}/hide` and
    `POST /{commentId}/unhide`, passing the caller role.
- **New** `entities/CommentModeration.java` + `repository/CommentModerationRepository.java`
  (maps the existing `comment_moderation` table).
- **Modified frontend** `static/js/lesson-comments-render.js`,
  `static/js/lesson-comments.js`, `static/css/lesson-comment-thread.css`.
- **Modified** `common/IConstant.java` — new comment moderation messages.
- **Modified backend** `features/student/` (page-level gate, design D7): the
  discussion thread is only reachable through the student lessons page, whose
  read services gated on ACTIVE enrollment — blocking the very moderators this
  change enables. Both are widened to mirror `authorize()` (D3):
  - `service/StudentLessonsService.listClassLessons(classId, userId, Role role)`
    — admits ADMIN/HEAD (bypass enrollment) OR owning lecturer OR ACTIVE-enrolled
    student; else no-leak 404. Load class first so the lecturer check has
    `lecturerId`.
  - `service/StudentLessonDetailService.getLessonDetail(classId, lessonId, userId, Role role)`
    — same widening; lesson gates (live class / section / PUBLISHED) still run
    first so a moderator gains nothing on a deleted/unpublished lesson.
  - `controller/StudentLessonsController` — threads `user.getRole()` into both
    calls.
- **No migration** — `REJECTED` and `comment_moderation` already exist in V1.
- **No change** to `SecurityConfig` (endpoints stay under authenticated `/api/lessons/**`).
