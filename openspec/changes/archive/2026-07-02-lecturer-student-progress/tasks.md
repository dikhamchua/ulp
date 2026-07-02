# Tasks: lecturer-student-progress

## 1. Repository queries (no migration)

- [x] 1.1 Add to `LessonRepository`: count + id-list of PUBLISHED non-deleted lessons for a class (via its sections) — reuse existing section→lesson wiring; return ordered lesson ids grouped by section for the drill-down
- [x] 1.2 Add to `LearningProgressRepository`: aggregate query `SELECT user_id, SUM(CASE WHEN status='COMPLETED' THEN 1 ELSE 0 END), MAX(updated_at) FROM learning_progress WHERE lesson_id IN (:publishedIds) GROUP BY user_id` returning a projection (userId, completedCount, lastActivity); plus a per-student `findByUserIdAndLessonIdIn` for the drill-down status map ← (verify: query returns correct completed counts + max updated_at spanning any status; empty publishedIds handled without SQL error)

## 2. DTOs

- [x] 2.1 Create `com.ulp.features.classes.dto.ProgressDtos`: `StudentProgressRow(userId, fullName, email, avatarLabel, avatarGradient, completed, total, percent, joinedAt, lastActivity, status)`, `ProgressSummary(totalStudents, avgPercent, notStartedCount, completedCount)`, drill-down `LessonProgressRow(lessonTitle, status)` + `SectionProgressGroup(sectionTitle, lessons)`

## 3. Aggregation service (BE)

- [x] 3.1 Create `LecturerProgressService` with `getProgressPage(classId, userId, role, status, q, page, size)`: call `getViewable` (authz), load published lesson ids + total, load ACTIVE enrollments (JOIN FETCH user), load progress aggregate map, build full row list, compute `ProgressSummary` over the FULL list, then apply search (name/email contains, case-insensitive) → status filter → sort → paginate into a `PageImpl`
- [x] 3.2 Percent + bucket helper reused from the student-view definition (completed/total, half-up, 0 when total 0; buckets not-started/in-progress/completed); English comment pointing at the canonical `StudentLessonsService` formula
- [x] 3.3 Add `getStudentLessonBreakdown(classId, studentId, userId, role)`: authz gate + assert studentId is an ACTIVE member (else EntityNotFoundException), return sections→lessons with per-lesson status (COMPLETED / IN_PROGRESS / NOT_STARTED) ← (verify: percent/buckets match StudentLessonsService; DRAFT + soft-deleted lessons excluded from denominator and drill-down; summary stays over full cohort regardless of filter; no query-per-student)

## 4. Controllers

- [x] 4.1 Create `ClassProgressController` (`@Controller`, `BASE_LECTURER`, `PREAUTH_LECTURER_OR_ABOVE`): `GET /classes/{id}/progress` → populate sidebar via `ClassDetailModelSupport.populateDetail(..., TAB_PROGRESS, ...)`, add summary + Page + echoed filter/search/size, return `VIEW_CLASS_DETAIL_PROGRESS`
- [x] 4.2 Create `ClassProgressApiController` (`@RestController`, `PREAUTH_LECTURER_OR_ABOVE`): `GET /classes/{id}/progress/{studentId}/lessons` → JSON breakdown; map EntityNotFoundException→404, AccessDeniedException→403 via `AjaxResponses` ← (verify: non-owner lecturer 403; outsider/student 403 via Security; non-member studentId 404; JSON shape = {sections:[{title,lessons:[{title,status}]}]})

## 5. Sidebar + constants

- [x] 5.1 Add "Tiến độ" nav item to `fragments/class-sidebar.html` after "Bảng điểm" (`activeTab == 'progress'`), with an icon consistent with the existing set
- [x] 5.2 Add `IConstant` entries: `TAB_PROGRESS`, `VIEW_CLASS_DETAIL_PROGRESS`, ATTR_* (summary/page/filters), MSG_* Vietnamese copy; update `ClassDetailModelSupport.labelFor` if it drives the sidebar label

## 6. Frontend

- [x] 6.1 Create `templates/classes/detail-progress.html`: head + `class-sidebar(clazz,'progress')`, 4 stat cards, filter tab bar (links with `?status=`), search form (GET, preserves status), student table (name+email+avatar, progress bar+%, n/total, joined date, last activity), pager (links preserve status+q+size), empty states
- [x] 6.2 Create `static/css/class-progress.css` (cards, progress bar, table, filter tabs, side panel) matching existing class-detail styling
- [x] 6.3 Create `static/js/class-progress.js`: row click → fetch drill-down JSON (CSRF meta headers), build side panel via createElement/textContent, active-row highlight, UlpToast.error on failure, empty/error states ← (verify: no innerHTML with data; `<script>` in a title renders as literal text; CSRF header sent; toast on failure)

## 7. Tests

- [x] 7.1 `LecturerProgressServiceTest` (@SpringBootTest @Transactional): summary avg/not-started/100% over full cohort; percent excludes DRAFT+soft-deleted; buckets; search by name/email; status filter; pagination window; last-activity = max updated_at; not-started row; drill-down status mapping; drill-down non-member → exception
- [x] 7.2 `ClassProgressControllerTest` (MockMvc): tab renders for owner; non-owner lecturer 403; student 403; filter/search/page params honored
- [x] 7.3 `ClassProgressApiControllerTest` (MockMvc): drill-down JSON shape + status codes (owner 200, non-owner 403, non-member studentId 404) ← (verify: run `.\mvnw.cmd test -Dtest="LecturerProgress*,ClassProgress*"` plus existing classes/student suites — all green on real MySQL)

## 8. Polish

- [x] 8.1 Self-review vs project rules: files ≤ ~200 lines, English comments, IConstant static import (no implements), controllers return DTO/Page/view only (no entity leak), UlpToast-only feedback, no inline alert ← (verify: `.\mvnw.cmd compile` clean; grep no new `alert(` / `<div class="alert">`)
