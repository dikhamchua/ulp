# Design — Comment hide/unhide (ULP-11.7)

## Context

Lesson comments (ULP-4.6) live in `features/comments/`. The read path already
filters to `moderation_status = APPROVED`; the entity already exposes
`MODERATION_REJECTED`; and V1 ships a `comment_moderation` audit table. This
change adds a moderator hide/unhide action on top of that infrastructure with
**no schema migration**.

## Key decisions

### D1 — Hidden state = `REJECTED` (no migration)
Reuse the existing `REJECTED` value rather than adding a new `HIDDEN` status.
The CHECK constraint (`IN ('PENDING','APPROVED','REJECTED')`) and the
`comment_moderation.action` CHECK (`IN ('APPROVED','REJECTED')`) already permit
it. Semantically "hidden by a moderator" maps onto `REJECTED` (not APPROVED =
not shown). Avoids a migration and keeps the worktree independent of the
messaging branch's `V21`.

### D2 — Moderator = owning lecturer ∪ ADMIN ∪ HEAD
`hide`/`unhide` and the moderator-aware listing are gated on:
```
isModerator = clazz.lecturerId == caller  ||  role ∈ {ADMIN, HEAD}
```
The caller `Role` comes from `UlpUserDetails.getRole()` (already resolved at
auth; no extra DB lookup) and is threaded from the controller into the service.

### D3 — Widen `authorize()` for ADMIN/HEAD only
Today `authorize()` admits the owning lecturer or an ACTIVE-enrolled student,
else 404. ADMIN/HEAD are typically not enrolled, so they need a bypass to open
the thread and moderate:
```
authorize(lessonId, userId, role):
    clazz = resolveByLesson(lessonId).clazz          // existing lesson gates
    if role ∈ {ADMIN, HEAD}: return clazz             // NEW bypass
    if lecturerOwns || activeEnrolled: return clazz    // unchanged
    throw EntityNotFoundException                      // no-leak 404
```
This is the only access-control change and the highest-risk part — the shared
lesson gates (live class / section / PUBLISHED) still run first, so ADMIN/HEAD
gain no access to comments on a deleted/unpublished lesson.

### D4 — Moderator-aware listing
`listPage(lessonId, userId, role, page, size)`:
- **Student** → unchanged: roots and replies filtered to `APPROVED`; a hidden
  (REJECTED) root is excluded and its thread drops (identical to a deleted root).
- **Moderator** → roots and replies loaded with `status IN (APPROVED, REJECTED)`;
  hidden nodes are kept and flagged. New repository methods take a
  `Collection<String>` of statuses; the existing single-status methods stay for
  the student path.

Assembler: when `moderator`, a `REJECTED` node becomes a real row with
`hidden=true, canModerate=true` (NOT pruned like a deleted node); an `APPROVED`
node gets `canModerate=true`. When not moderator, behaviour is unchanged and no
`REJECTED` node ever reaches the assembler.

### D5 — Idempotent transitions + audit
- `hide`: if already `REJECTED`, no-op (still returns ok); else set `REJECTED`
  and insert a `comment_moderation` row (`action=REJECTED`).
- `unhide`: symmetric, restoring `APPROVED` (`action=APPROVED`).
- `reason` is left null (no UI in this change); the column stays available.

### D6 — Endpoints
`POST /api/lessons/{lessonId}/comments/{commentId}/hide`
`POST /api/lessons/{lessonId}/comments/{commentId}/unhide`
Chosen over a generic `PUT status` to keep intent explicit and the JS trivial.
Both return the standard `AjaxResult` envelope; the client reloads the thread.
Errors map as the rest of the controller: `AccessDeniedException` → 403,
`EntityNotFoundException` → 404.

### D7 — Widen the page-level gate for moderators
The discussion thread only renders on the student lessons page
(`student/class-lessons.html`), served by `StudentLessonsController.view()`.
Its two read services gated strictly on ACTIVE enrollment, so a non-enrolled
moderator (owning lecturer / ADMIN / HEAD) hit a 404 **before** reaching the
thread — the D3 API widening had no UI entry point. Both are widened to mirror
`authorize()` (D3):
```
requireAccess(clazz, userId, role):
    if role ∈ {ADMIN, HEAD}: return             // bypass enrollment
    if lecturerOwns || activeEnrolled: return    // unchanged
    throw EntityNotFoundException                // no-leak 404
```
- `StudentLessonsService.listClassLessons` — loads the class first (so the
  lecturer check can read `lecturerId`), then applies `requireAccess`.
- `StudentLessonDetailService.getLessonDetail` — runs the shared lesson gates
  (live class / section-belongs / PUBLISHED) first, then `requireAccess`, so a
  moderator gains nothing on a deleted/unpublished lesson.
The caller `Role` threads from `StudentLessonsController` via
`UlpUserDetails.getRole()` (no extra DB lookup). Student behaviour is
unchanged: ACTIVE-enrolled sees the page; REMOVED/COMPLETED/non-enrolled
non-moderator still 404.

**Progress side-effect (D7a).** After the detail gate passes, `view()` calls
`recordOpenedQuietly` → `LearningProgressService.recordOpened`, whose gates
still require ACTIVE enrollment. A non-enrolled moderator would fail that gate
every open, be caught, and log a WARN + stacktrace — pure noise, since a
moderator viewing a lesson is not *learning* and correctly accrues no progress
row. Fix at the domain boundary: `recordOpenedQuietly(..., Role role)` skips
the write when `role != STUDENT`. Only students own progress, so the skip is
the expected path (no WARN), and it also spares the wasted enrollment query
`recordOpened` would otherwise run for moderators. The catch/WARN branch is
retained for genuine progress-write failures on the student path.

### D8 — Extract the shared access gate into `ClassAccessPolicy`
D3 and D7 left the same access formula (ADMIN/HEAD bypass → owning lecturer →
ACTIVE-enrolled → else no-leak 404) duplicated across `LessonCommentsService`,
`StudentLessonsService`, and `StudentLessonDetailService`. It is now a single
`@Component ClassAccessPolicy` in `com.ulp.features.lessons.support` (beside
`LessonAccessResolver`), exposing `isPrivileged`, `isModerator`, and
`requireModeratorOrEnrolled`. Kept separate from `LessonAccessResolver` (which
owns only lesson resolution + the PUBLISHED gate) so each caller still orders
the lesson gate and the access gate as its no-leak contract requires. Pure
refactor — same message, same order, no behaviour change, no migration.

## Error / edge cases
- Student calls hide/unhide → `AccessDeniedException` (403).
- Comment id not in this lesson / not found → 404 (no existence leak).
- Hide an already-hidden / unhide an already-visible comment → idempotent 200.
- Hidden comment is never returned to a student, and cannot anchor a placeholder
  (unlike deleted) — hiding a root removes the thread for students.
- Reply-eligibility (`create`) already requires the parent to be `APPROVED`, so a
  hidden parent cannot receive new replies — no change needed there.

## Testing strategy
Unit (service) + integration (controller), mirroring existing comment tests:
- moderator hide→listed as hidden; student list excludes it.
- student hide/unhide → 403.
- ADMIN/HEAD (not enrolled) can list + hide + unhide.
- idempotent hide/unhide.
- audit row written with correct `moderated_by` and `action`.
- 404 for foreign/nonexistent comment id.
