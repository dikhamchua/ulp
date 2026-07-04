## Why

The platform has empty `tests` / `questions` / `test_attempts` tables from V1 but no
feature on top of them, so students cannot take timed exams and lecturers cannot run
them. Epic #11 (Sprint 4) delivers the learning-and-practice half of the product: an
MCQ/MR online exam that auto-grades on submit, enforces a countdown timer, and gives
lecturers live monitoring plus a submissions overview — the natural companion to the
just-shipped Flashcards epic.

## What Changes

- **New student area `/my/tests`**: list published exams for enrolled classes + own
  practice tests (reusing the shared `PageWindow` pager); a top-level nav item
  "Bài test".
- **Take an exam**: MCQ (single) + MR (multiple) questions, a countdown timer with two
  time models (fixed exam window vs individual duration), submit-all-at-once, a ~30s
  heartbeat that feeds live monitoring, and auto-submit on timeout.
- **Auto-grading**: all-or-nothing per question (MCQ correct = the one right option
  chosen; MR correct = selected option set equals the correct set exactly). Result
  screen (score, pass/fail, correct count, time) and a per-question review with the
  student's answer, the correct answer, and the explanation.
- **Practice test**: a student builds a personal `PRACTICE` test by random-sampling
  MCQ/MR questions (copied with their options) from the pool of published exams they
  can access.
- **Exam Readiness Score**: a real-time 0–100 dashboard = mean of best-attempt score%
  across accessible MOCK/MODULE exams (untaken counts as 0), with a readiness band.
- **New lecturer area `/lecturer/tests`** (LECTURER/HEAD/ADMIN): create and edit exams
  with an inline MCQ/MR question builder; a live-monitoring screen (submitted / in-
  progress / status, per-student activity, auto-refresh every 30s); and a submissions
  screen listing every attempt (student, score, submit time, attempt count) with a
  detail/review link and student search.
- **Schema (migration V20, additive)**: add `tests.start_at`, `tests.end_at`,
  `tests.time_mode`, and `test_attempts.last_activity_at`; seed demo exams so the
  feature is demoable before lecturer authoring exists in the UI.
- **Authorization**: a new `TestAccessResolver` (mirroring `DeckAccessResolver`) —
  404 no-leak for inaccessible exams, 403 for owner/lecturer-only actions, per-user
  attempt state.

Out of scope: file-submission answers, FILL_IN / MATCHING question types, manual
grading / rubric / lecturer feedback, partial credit, per-answer autosave, per-topic
weakness analysis, and any AI features.

## Capabilities

### New Capabilities
- `mcq-exam-taking`: student-facing exam list, timed exam attempt (MCQ/MR, two time
  models, heartbeat, auto-submit), auto-grading, result, per-question review, personal
  practice-test generation, and exam readiness score.
- `mcq-exam-management`: lecturer-facing exam authoring (create/edit + MCQ/MR question
  builder, publish lifecycle), live attempt monitoring, and submissions overview —
  scoped to classes the lecturer owns.

### Modified Capabilities
<!-- None. This change only adds new capabilities; existing specs are untouched. -->

## Impact

- **New code**: `com.ulp.features.tests` (entity, repository, dto, service, support,
  controller); Thymeleaf templates under `templates/tests/`; `static/css|js/test-*`.
- **Schema**: `V20__mcq_exam_scheduling_and_seed.sql` (additive columns + demo seed).
  Flyway stays in `validate` mode; V1–V19 untouched.
- **Reused**: `PageWindow` + `fragments/pager.html` + `pager.css`,
  `AjaxResult`/`AjaxResponses`, enrollment repository, `GlobalExceptionHandler`,
  `app-header.html` (new nav item), `IConstant` (shared strings).
- **Roles**: STUDENT (take/practice/readiness); LECTURER/HEAD/ADMIN (author/monitor).
- **Data**: writes to `tests`, `questions`, `question_options`, `test_attempts`,
  `test_responses`; demo seed rows added to class 334.
- **Issues**: Epic #11; stories #54–59; subtasks #200–217.
