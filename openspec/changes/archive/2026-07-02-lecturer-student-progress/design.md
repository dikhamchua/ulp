# Design: lecturer-student-progress

## Context

The class-detail area (`/lecturer/classes/{id}/...`, template family `templates/classes/detail-*.html`) renders a 280px sidebar (`fragments/class-sidebar.html`) plus a tab body. Tabs are handled by `ClassDetailController` (board/members/settings implemented, several placeholders) using `ClassDetailModelSupport.populateDetail(model, clazz, tab, userId, role)` to inject the sidebar model and invite data, with ownership enforced by `classesService.getViewable`. The lessons tab is owned by a separate controller (`SectionsController`/`LessonsTabController`) to avoid ambiguous mappings. The `learning_progress` and `comments` features were just added; `learning_progress` holds per-(user, lesson) status COMPLETED/IN_PROGRESS + timestamps. `enrollments` links users↔classes (ACTIVE status, `joined_at`), `EnrollmentRepository` already fetches members with `JOIN FETCH user`. Member rows already compute avatar initials + a gradient (`ClassMembersService.toRow`). No lecturer view of aggregate progress exists.

## Goals / Non-Goals

**Goals:**
- A lecturer/HEAD/ADMIN dashboard of every ACTIVE student's completion progress in one class, matching the provided screenshot: 4 summary cards, a server-side searchable/filterable/paginated table, and a per-student per-lesson drill-down.
- Reuse the exact percent definition from the student view so both surfaces agree.
- No N+1, no schema migration.

**Non-Goals:**
- Editing/overriding a student's progress from this screen (read-only).
- Progress for tests/assignments (only lesson completion this sprint).
- CSV/Excel export, charts/graphs, cross-class rollups.
- A student-facing version (already shipped) or notifications.

## Decisions

### D1 — Where the code lives
New controller `ClassProgressController` in `com.ulp.features.classes.controller` (mirrors `ClassDetailController`, `@RequestMapping(BASE_LECTURER)`, `@PreAuthorize(Roles.PREAUTH_LECTURER_OR_ABOVE)`), plus a new `LecturerProgressService` in `com.ulp.features.classes.service`. DTOs in a new `com.ulp.features.classes.dto.ProgressDtos`. Reason: this is a class-detail tab, conceptually part of the classes feature; reuses `getViewable`, `ClassDetailModelSupport`, `EnrollmentRepository`. Keeps each file ≤200 lines by splitting the drill-down JSON endpoint into its own tiny `@RestController` (`ClassProgressApiController`) if the tab controller grows.

### D2 — Aggregation strategy (avoid N+1, return a real Page)
The dashboard needs, per student: completed-count, last-activity, plus name/email/joined_at. Strategy:
1. **Denominator once**: `lessonRepository` count of PUBLISHED non-deleted lessons for the class (+ the list of their ids for the drill-down and completed-count scoping).
2. **Cohort aggregate in bounded queries** (NOT per-student): one query over `enrollments JOIN users` for ACTIVE members (already exists), and one aggregate query on `learning_progress` grouped by `user_id` returning `(userId, completedCount, lastActivity)` scoped to the class's published lesson ids and `status='COMPLETED'` for the count while `MAX(updated_at)` spans any status. Because "last activity" must include IN_PROGRESS opens, split into: `SELECT user_id, SUM(status='COMPLETED'), MAX(updated_at) FROM learning_progress WHERE lesson_id IN (:publishedIds) GROUP BY user_id` (native or JPQL with CASE). Map into a per-user lookup.
3. **Compute rows in the service**, then apply **search → status filter → sort → pagination** in-memory and wrap in a `PageImpl`. Summary cards are computed from the FULL unfiltered row list before filtering.

**Tradeoff (documented):** pagination/filter/search are applied in the service over the full in-memory cohort rather than pushed into SQL `LIMIT/OFFSET`. Rationale: a single class's ACTIVE roster is bounded (tens, at most low hundreds — the screenshot's 302 is a whole-academy figure, not one class); status/percent filters depend on a computed ratio that is awkward and fragile to express as SQL `HAVING` alongside a Spring `Pageable` count query. Two aggregate queries + in-memory shaping is correct, simple, and easily unit-tested, and still returns a proper `Page` to the view. If a single class ever exceeds a few thousand members, revisit with a native windowed query — the controller/DTO contract already returns `Page` so that change stays behind the service.

### D3 — Percent + buckets (single source of truth)
Reuse the student-view formula: `percent = total==0 ? 0 : Math.round(completed*100.0/total)` (half-up via `Math.round`). Buckets: not-started `completed==0`; completed `total>0 && completed==total`; in-progress otherwise-with-`completed>0`. Extract a small helper so the value matches `StudentLessonsService`; if practical, move the shared formula to a tiny util referenced by both (optional — duplication of one line is acceptable, note it).

### D4 — Controller surface
- `GET /lecturer/classes/{id}/progress?status=&q=&page=&size=` → renders `classes/detail-progress.html`. Params: `status` (all|completed|in-progress|not-started, default all), `q` (search, default empty), `page` (0-based, default 0), `size` (default 10). Model: sidebar via `populateDetail(..., TAB_PROGRESS, ...)`, `summary` DTO, `Page<StudentProgressRow>`, plus current filter/search/size echoed back for the template controls.
- `GET /lecturer/classes/{id}/progress/{studentId}/lessons` → JSON `@RestController`; authz gate + target-is-ACTIVE-member check; returns `{ sections: [ { title, lessons: [ { title, status } ] } ] }`; failures via `AjaxResponses.notFound/forbidden`.

### D5 — Frontend
`templates/classes/detail-progress.html`: `fragments/head` + `fragments/class-sidebar(clazz,'progress')` + body with 4 stat cards, filter tab bar (links carrying `?status=`), a search form (GET, preserves status), the table, and a pager (links preserving status+q+size). Server-rendered table (no JS needed for the list itself — pagination is via links, matching the SSR nature of the app). New `static/css/class-progress.css`. New `static/js/class-progress.js` ONLY for the drill-down: row click → fetch JSON (CSRF meta pattern from `admin-settings.js`) → build a side panel with `document.createElement` + `textContent`; `UlpToast.error` on failure; highlight the active row (mirrors screenshot). Empty states (no students / no matches) and the drill-down empty/error states are Vietnamese copy.

### D6 — Last-activity display
Render `updated_at` as `dd/MM/yyyy` in the template via `#temporals.format`; a simple relative hint ("Hôm nay" when the date is today) may be added in the template with a date comparison — kept minimal (no relative-time library, KISS). Students with no rows show the literal "Chưa bắt đầu".

## Risks / Trade-offs

- [In-memory pagination/filter] → mitigated by class-roster bounded size (see D2); `Page` contract preserved so a future SQL-windowed implementation is a drop-in behind the service.
- [Last-activity semantics] → defined as max `updated_at` over any status in the class; a student who only opened (IN_PROGRESS) still shows a recent activity date, which is the intended meaning.
- [Percent-formula duplication with `StudentLessonsService`] → one-line formula; acceptable duplication with a code comment pointing at the canonical definition; optional shared helper noted.
- [Two aggregate queries touch `learning_progress` scoped by `lesson_id IN (:publishedIds)`] → published-id list is small (lessons per class); parameter list bounded. If a class had thousands of lessons, switch to a join on lessons; not a concern at current scale.
- [Manual test rows already in `learning_progress`] → harmless; integration tests build their own transactional fixtures.

## Migration Plan

Code-only; deploy normally. Rollback = revert the commit. No schema or data changes.

## Open Questions

None — all decisions locked during exploration.
