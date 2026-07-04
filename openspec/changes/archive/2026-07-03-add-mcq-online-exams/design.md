## Context

V1 created `tests`, `questions`, `question_options`, `test_attempts`, `test_responses`
but nothing uses them. The Flashcards epic just established the reusable patterns this
feature copies: feature-first package, `PageWindow` + `fragments/pager.html` pager,
`AjaxResult`/`AjaxResponses` JSON envelope, an access resolver (`DeckAccessResolver`)
returning 404-no-leak / 403-owner-only, deferred-save on forms, `UlpToast`, and
`textContent`-only rendering. Hibernate runs in `validate` mode, so schema changes go
through a Flyway migration. The user supplied four lecturer screens (live monitor, view
submissions, create exam, edit exam) that must exist but adapted from file-submission to
MCQ.

## Goals / Non-Goals

**Goals:**
- Student can take a timed MCQ/MR exam and get an auto-graded result + review.
- Two timer models: fixed exam window and per-student individual duration.
- Student can generate a personal practice test and see a readiness score.
- Lecturer can author (create/edit with a question builder), monitor live, and review
  all submissions for exams they own.
- Reuse existing patterns; keep Java files under ~200 lines; only additive schema.

**Non-Goals:**
- File-submission answers, FILL_IN / MATCHING types, manual grading / rubric / feedback,
  partial credit, per-answer autosave, per-topic weakness, and AI.

## Decisions

### Reuse `tests` for exams; extend additively (V20)
Exams ARE `tests`. Migration V20 adds `tests.start_at`, `tests.end_at`,
`tests.time_mode` (`FIXED_WINDOW` | `INDIVIDUAL`, default `FIXED_WINDOW`, CHECK-
constrained) and `test_attempts.last_activity_at`. No table is recreated; V1–V19 stay
untouched. The migration also seeds demo exams (one FIXED_WINDOW MOCK, one INDIVIDUAL
MODULE) in class 334 with MCQ+MR questions so the feature is demoable before the lecturer
UI is used. `SET NAMES utf8mb4` opens the seed for Vietnamese content.

### Grading is all-or-nothing, computed server-side (`GradingService`)
MCQ: correct iff the single selected option id is the one option with `is_correct = 1`.
MR: correct iff the selected option-id set equals the set of `is_correct = 1` option ids
exactly. Correct → `points_earned = question.points`, else 0. The attempt aggregates
`score = Σ points_earned`, `total_points = Σ question.points`, `correct_count`,
`total_questions`. Selected ids live in `test_responses.selected_option_ids` (JSON).
Grading never trusts the client's notion of correctness.

### Timer is client-countdown + authoritative server check
The take page runs a countdown and auto-submits at zero. The server recomputes the
deadline on submit: `FIXED_WINDOW` → `end_at`; `INDIVIDUAL` → `started_at +
duration_minutes`. If `now` is past the deadline (small grace), the attempt is stored as
`TIMED_OUT` but still graded over whatever responses arrived. This keeps a closed laptop
or a clock-skewed client from gaining time.

### Submit-all-at-once + heartbeat (no per-answer autosave)
Answers are held in the browser and POSTed as one JSON payload on submit/auto-submit.
A separate lightweight heartbeat AJAX (~30s) updates `last_activity_at` so live
monitoring can tell who is active — it carries no answers. This is a **conscious
trade-off**: closing the browser mid-attempt loses in-progress answers and the student
restarts a fresh attempt; we accept it to avoid the complexity of per-answer persistence,
which is out of scope. Documented so it is not mistaken for an oversight.

### Deterministic shuffle seeded by attempt id
When `shuffle_questions` / `shuffle_options` are on, ordering is shuffled with a seed
derived from `attempt.id`, so a resumed/reloaded attempt shows the same order and answers
still line up.

### Practice test copies questions (no M:N schema)
`questions` belong to exactly one `test` (FK), so a practice test gets its own rows.
`PracticeTestService` random-samples MCQ/MR questions from the accessible published pool
and copies each question + its options into a new `type = PRACTICE`, `created_by = student`,
`status = PUBLISHED` test owned only by that student. Simpler and schema-honest versus
introducing a question bank (explicitly out of scope).

### Readiness score is derived, not stored
`ReadinessService` computes, per request, the mean of best-attempt score-% over the
accessible MOCK/MODULE exams; an exam with no attempt contributes 0% (so coverage, not
just performance, moves the number). Bands: <50 "Chưa sẵn sàng", 50–79 "Khá", ≥80
"Sẵn sàng". No new columns.

### Authorization via `TestAccessResolver` (mirrors `DeckAccessResolver`)
Student side: viewable/attemptable iff PUBLISHED + not deleted + ACTIVE-enrolled in the
exam's class, or the student owns the PRACTICE test; otherwise 404. Lecturer side:
manageable iff LECTURER/HEAD/ADMIN AND (`created_by` = user OR `class.lecturer_id` =
user); non-owner → 403, missing/deleted → 404. Attempt/review is strictly per-user.

### Package & endpoints
`com.ulp.features.tests` split into `entity/ repository/ dto/ service/ support/
controller/`. Services stay small and single-purpose: `TestCatalogService` (lists),
`TestAttemptService` (start/resume/submit/heartbeat), `GradingService`,
`PracticeTestService`, `ReadinessService`, `LecturerExamService` (author),
`ExamMonitorService` (monitor/submissions). Student SSR under `/my/tests`
(`StudentTestController`) + a small JSON controller for submit/heartbeat; lecturer SSR
under `/lecturer/tests` (`LecturerTestController`) + a monitor-data JSON endpoint.
DTOs are records in `TestDtos`; shared strings go to `IConstant` via static import.
Vanilla `static/css|js/test-*`; the pager fragment and `AjaxResult`/`AjaxResponses` are
reused as-is.

## Risks / Trade-offs

- **Lost in-progress answers on disconnect** — accepted (see submit-all-at-once). Timer
  auto-submit covers the common timeout case; a hard disconnect is a known limitation.
- **"Active now" is heuristic** — based on `last_activity_at` within ~60s; a paused tab
  can appear idle. Acceptable for a monitoring aid, not attendance of record.
- **Practice copies duplicate question rows** — storage cost for a clean model; practice
  tests are personal and low-volume, so the duplication is bounded.
- **`th:href` restricted context** — must not call `T(...)` statics inside URL
  attributes; compute in `th:with` first (as done in `fragments/pager.html`).
- **Timezone consistency** — deadlines compare server `now` to stored `DATETIME`; seed
  and app must use the same zone to avoid the UTC/local skew seen when seeding raw SQL.
- **Demo seed depends on class 334 + its lecturer** — the migration must resolve the
  class's `lecturer_id` for `created_by`; if that data changes the seed must still be
  valid (guard with a SELECT, skip if absent).
