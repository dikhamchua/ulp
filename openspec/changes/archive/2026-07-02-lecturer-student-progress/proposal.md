# Proposal: lecturer-student-progress

## Why

The just-shipped ULP-4.5 work lets a student see only their own completion percentage. A lecturer running a class has no way to see how the whole cohort is doing — who has finished, who is stuck, who never started. Without this, lecturers cannot identify at-risk students or gauge overall engagement. The underlying data (`learning_progress`, `enrollments`, `lessons`) already exists and is populated, so this is purely a new read surface.

## What Changes

- **New lecturer tab "Tiến độ"** in the class-detail sidebar → `GET /lecturer/classes/{id}/progress`, restricted to the owning lecturer / HEAD / ADMIN (same ownership gate as every other class-detail tab).
- **Cohort summary cards** over the class's ACTIVE students: total students, average completion %, count not started, count 100% complete.
- **Server-side paginated table** of students: name + email + avatar, a progress bar with %, "n/total" lessons done, enrollment date, and last-activity timestamp. Includes a server-side search box (name/email) and server-side filter tabs (Tất cả / Hoàn thành / Đang học / Chưa bắt đầu). The summary cards always reflect the whole class, independent of the active filter/search/page.
- **Per-student drill-down** — clicking a row opens an AJAX side panel that lists every published lesson (grouped by section) with that student's status (Hoàn thành / Đang học / Chưa mở), served by a new JSON endpoint `GET /lecturer/classes/{id}/progress/{studentId}/lessons`.
- **No schema migration** — completion percentage reuses the exact definition from the student view (completed ÷ total published lessons); computed over PUBLISHED, non-soft-deleted lessons only.

## Capabilities

### New Capabilities

- `lecturer-student-progress`: a lecturer-facing dashboard aggregating every enrolled student's lesson-completion progress in a class — cohort summary metrics, a searchable/filterable/paginated student table, and a per-student per-lesson drill-down.

### Modified Capabilities

<!-- none — the archived student-scoped `learning-progress` spec is not changed; this adds a distinct lecturer capability -->

## Impact

- **New code**: a lecturer progress controller (class-detail tab GET + drill-down JSON endpoint), a progress-aggregation service, new DTOs, new repository queries on `LearningProgressRepository` / `EnrollmentRepository` / `LessonRepository`; new template `templates/classes/detail-progress.html`, new CSS + JS under `static/`; new `IConstant` entries.
- **Modified code**: `fragments/class-sidebar.html` (new "Tiến độ" nav item + `activeTab='progress'`); possibly `ClassDetailModelSupport.labelFor` for the tab label.
- **DB**: read-only aggregation over existing `learning_progress`, `enrollments`, `lessons`; no migration.
- **Security**: reuse `classesService.getViewable` ownership gate; drill-down additionally verifies the target student is an ACTIVE member; failures collapse to 403/404 via the shared `AjaxResponses` envelope.
- **Tests**: new `@SpringBootTest @Transactional` service tests (aggregation correctness, buckets, search, filter, pagination) and MockMvc controller tests (tab render, authz, drill-down JSON); existing suites unaffected.
