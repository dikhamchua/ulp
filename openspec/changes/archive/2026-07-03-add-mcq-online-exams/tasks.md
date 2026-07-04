# Tasks — add-mcq-online-exams

## 1. Schema, entities, repositories

- [x] 1.1 Create `V20__mcq_exam_scheduling_and_seed.sql`: `SET NAMES utf8mb4`; `ALTER TABLE tests` ADD `start_at DATETIME NULL`, `end_at DATETIME NULL`, `time_mode VARCHAR(20) NOT NULL DEFAULT 'FIXED_WINDOW'` + CHECK `time_mode IN ('FIXED_WINDOW','INDIVIDUAL')`; `ALTER TABLE test_attempts` ADD `last_activity_at DATETIME NULL`.
- [x] 1.2 In the same migration, seed 2 demo exams in `class_id = 334` (created_by resolved from that class's `lecturer_id` via SELECT; skip cleanly if absent): one MOCK `FIXED_WINDOW` PUBLISHED and one MODULE `INDIVIDUAL` PUBLISHED (with `duration_minutes`), each 5–8 mixed MCQ/MR questions + options, Vietnamese SWE content, `start_at` recent-past / `end_at` future so it reads as ongoing.
- [x] 1.3 Entities in `com.ulp.features.tests.entity` mapping existing tables + new columns: `Test`, `Question`, `QuestionOption`, `TestAttempt`, `TestResponse` (no `@Data`; soft-delete `is_deleted` on `Test`; enums as String status/type/time_mode/question_type).
- [x] 1.4 Repositories in `.repository` for each entity with the finder methods the services need (published-by-class, own-practice, attempts-by-user-and-test, responses-by-attempt, questions-by-test, options-by-question). ← (verify: Flyway `validate` passes — entities match V1+V20 columns exactly, app boots)

## 2. Access control & grading core

- [x] 2.1 `support/TestAccessResolver` mirroring `DeckAccessResolver`: student `requireAttemptable` (PUBLISHED + not deleted + ACTIVE-enrolled OR own PRACTICE → else 404); lecturer `requireManageable` (LECTURER+ AND created_by==user OR class.lecturer_id==user → 403 non-owner, 404 missing/deleted); per-user attempt guard.
- [x] 2.2 `service/GradingService`: grade one response all-or-nothing (MCQ single-correct, MR exact-set) → `is_correct` + `points_earned`; aggregate an attempt (score, total_points, correct_count, total_questions).
- [x] 2.3 `dto/TestDtos` records for list rows, taking view, result, review, practice request, readiness, lecturer forms, monitor + submissions views (no entity leak).
- [x] 2.4 Unit tests: `GradingServiceTest` — MCQ correct/incorrect, MR exact-match vs subset vs superset, points aggregation. ← (verify: grading matches spec scenarios exactly, all-or-nothing)

## 3. Student — list & take

- [x] 3.1 `service/TestCatalogService.listForStudent(userId, page)` → published class exams (ACTIVE-enrolled) + own practice, paginated (`PageWindow`-compatible `Page`).
- [x] 3.2 `StudentTestController` GET `/my/tests` (list, pager, params) + nav item "Bài test" in `fragments/app-header.html` (active=='tests'); template `templates/tests/list.html` + `static/css/test-list.css`; reuse `fragments/pager.html`.
- [x] 3.3 `service/TestAttemptService.startOrResume(testId, userId)` — reuse open IN_PROGRESS or create; `heartbeat(attemptId, userId)` updates `last_activity_at`; deterministic shuffle (seed = attempt id) honoring `shuffle_questions`/`shuffle_options`.
- [x] 3.4 Taking screen GET `/my/tests/{id}/take` → `templates/tests/take.html` + `static/js/test-take.js` (countdown per `time_mode`, ~30s heartbeat, submit-all-at-once, auto-submit at 0, `UlpToast`, `textContent` rendering) + `static/css/test-take.css`.
- [x] 3.5 `TestApiController` (JSON, `AjaxResult`/`AjaxResponses`): POST submit (all answers) → `TestAttemptService.submit` grading + deadline enforcement (past deadline → `TIMED_OUT`, grade submitted only); POST heartbeat. ← (verify: start/resume single-attempt, timer both modes, late submit → TIMED_OUT graded, 404 no-leak on inaccessible)

## 4. Student — result & review

- [x] 4.1 `TestAttemptService` result view builder (score, total, pass/fail vs `passing_score` or "no threshold", correct/total, time). GET `/my/tests/{id}/result/{attemptId}` (owner-only, else 404) → `templates/tests/result.html` + `static/css/test-result.css`.
- [x] 4.2 Per-question review builder (question, student answer, correct answer, is_correct, explanation). GET `/my/tests/{id}/review/{attemptId}` (owner-only 404) → `templates/tests/review.html`. ← (verify: owner sees answers+explanations; non-owner review → 404)

## 5. Student — practice & readiness

- [x] 5.1 `service/PracticeTestService.create(userId, source, count)` — sample MCQ/MR from accessible published pool, copy questions+options into new PRACTICE test (created_by=user, PUBLISHED, owner-only); clamp count to pool size.
- [x] 5.2 GET `/my/tests/practice/new` form + POST create → `templates/tests/practice-new.html` + `static/js/test-practice.js`; redirect to the new test.
- [x] 5.3 `service/ReadinessService.compute(userId)` — mean best-attempt score% over accessible MOCK/MODULE (untaken=0), band label, done/not-done list. GET `/my/tests/readiness` → `templates/tests/readiness.html` + `static/css/test-readiness.css`.
- [x] 5.4 Unit tests: `PracticeTestServiceTest` (sampling/copy, count clamp, only-accessible), `ReadinessServiceTest` (untaken=0 mean, band boundaries 49/50/79/80). ← (verify: practice copies only accessible questions; readiness formula + bands match spec)

## 6. Lecturer — author (create & edit)

- [x] 6.1 `service/LecturerExamService`: create/update exam with full question-set replacement (`replaceQuestions`), re-derive `total_questions`; validate per question (≥2 options, ≥1 correct; MCQ exactly one correct; MR ≥1); ownership via `TestAccessResolver.requireManageable`.
- [x] 6.2 `LecturerTestController` GET `/lecturer/tests` (own exams list, pager) + GET `/lecturer/tests/new` and `/lecturer/tests/{id}/edit` forms (class picker = led classes) → `templates/tests/lecturer-form.html` + `static/js/test-lecturer-form.js` (question builder: add/remove question, add/remove option, mark correct, time_mode toggle shows duration; deferred-save single submit) + `static/css/test-lecturer-form.css`.
- [x] 6.3 POST create/update via form or `TestApiController` JSON save; field-level validation errors inline, top-level via `UlpToast`. ← (verify: MCQ one-correct rule enforced; ≥2 options/≥1 correct enforced; non-owner create/edit → 403; edit replaces question set + total_questions)

## 7. Lecturer — monitor & submissions

- [x] 7.1 `service/ExamMonitorService`: monitor snapshot (submitted count, active IN_PROGRESS with `last_activity_at` within ~60s, exam status, per-enrolled-student state not-started/in-progress/submitted + last activity, countdown to `end_at`); submissions page (stats submitted/late/status, attempts paginated + student search, per-student attempt count, late = submitted_at>end_at for FIXED_WINDOW).
- [x] 7.2 GET `/lecturer/tests/{id}/monitor` → `templates/tests/monitor.html` + `static/js/test-monitor.js` (poll `/lecturer/tests/{id}/monitor/data` JSON ~30s, `textContent`); GET monitor-data JSON endpoint (owner-only) + `static/css/test-monitor.css`.
- [x] 7.3 GET `/lecturer/tests/{id}/submissions` (owner-only, pager, `q` search) → `templates/tests/submissions.html` + `static/css/test-submissions.css`; each row links to a lecturer attempt-review view (reuse review builder, lecturer-viewable for owned exam). ← (verify: active-now heuristic (20s active / 5min idle), late flag on FIXED_WINDOW, search filters, monitor JSON shape, owner-only 403/404)

## 8. Integration tests & wiring

- [x] 8.1 `TestCatalogControllerTest` / student flow MockMvc: list authz (404 no-leak on inaccessible), start→submit→result→review happy path, resume single-attempt, late submit → TIMED_OUT graded, review 404 for non-owner.
- [x] 8.2 Lecturer MockMvc: create/edit/publish, class-ownership 403, validation rejects (MCQ multi-correct, <2 options), monitor-data JSON for owned vs 403 for non-owned, submissions list + late count + search.
- [x] 8.3 Add shared constants to `com.ulp.common.IConstant` (routes/views/attrs/messages) via static import; confirm no hardcoded duplicate strings across the new controllers. ← (verify: full `mvnw.cmd test` green, Flyway validate ok, no entity leaked from controllers, all notifications via UlpToast)
