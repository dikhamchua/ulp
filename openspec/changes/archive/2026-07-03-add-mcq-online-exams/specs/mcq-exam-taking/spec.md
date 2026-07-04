## ADDED Requirements

### Requirement: Student exam list
The system SHALL show an authenticated student, at `/my/tests`, the exams they may
take: every non-deleted `PUBLISHED` exam whose `class_id` belongs to a class the
student is `ACTIVE`-enrolled in, plus every `PRACTICE` exam the student owns. The list
SHALL be paginated with the shared pager and reachable from a top-level nav item
"Bài test".

#### Scenario: Enrolled student sees a published class exam
- **WHEN** a student who is ACTIVE-enrolled in class C opens `/my/tests`
- **THEN** every non-deleted PUBLISHED exam with `class_id = C` appears in the list

#### Scenario: Non-enrolled and unpublished exams are hidden
- **WHEN** an exam is DRAFT/ARCHIVED, soft-deleted, or belongs to a class the student is not ACTIVE-enrolled in
- **THEN** that exam does NOT appear in the student's list

#### Scenario: Own practice tests are listed
- **WHEN** a student has created PRACTICE tests
- **THEN** those tests appear in the student's list regardless of class enrollment

### Requirement: Start or resume an attempt
The system SHALL let a student start an exam they may access, creating a
`test_attempts` row with status `IN_PROGRESS` and `started_at = now`. If the student
already has an `IN_PROGRESS` attempt for that exam, the system SHALL resume it rather
than create a second one. Starting an exam the student cannot access SHALL return 404
without revealing existence.

#### Scenario: First start creates an attempt
- **WHEN** a student with no open attempt starts an accessible exam
- **THEN** one IN_PROGRESS attempt is created with `started_at` set to the current time

#### Scenario: Resuming reuses the open attempt
- **WHEN** a student who already has an IN_PROGRESS attempt starts the same exam again
- **THEN** the existing attempt is reused and no second IN_PROGRESS attempt is created

#### Scenario: Inaccessible exam is not leaked
- **WHEN** a student starts an exam that is unpublished or outside their enrolled classes
- **THEN** the system responds 404 and creates no attempt

### Requirement: Taking screen with countdown and heartbeat
While an attempt is IN_PROGRESS the taking screen SHALL render each question with its
options (single-select for MCQ, multi-select for MR), a countdown timer, and SHALL send
a heartbeat roughly every 30 seconds that updates `test_attempts.last_activity_at`.
Question and option order SHALL follow `shuffle_questions` / `shuffle_options`, shuffled
deterministically by a seed derived from `attempt.id` so a resumed attempt keeps the
same order. Answers are held client-side and submitted together (no per-answer save).

#### Scenario: Heartbeat records activity
- **WHEN** the taking screen is open and its heartbeat fires
- **THEN** the attempt's `last_activity_at` is updated to the current time

#### Scenario: Deterministic shuffle on resume
- **WHEN** an exam has `shuffle_questions = 1` and the student reloads a resumed attempt
- **THEN** the question order is identical to the previous render for that attempt

### Requirement: Timer models and enforcement
The system SHALL support two `time_mode` values. For `FIXED_WINDOW`, the effective
deadline is `end_at` and the remaining time is `end_at - now` (a late starter loses
time). For `INDIVIDUAL`, the deadline is `started_at + duration_minutes`. The client
SHALL auto-submit when the countdown reaches zero, and on submit the server SHALL be
authoritative: if the deadline has passed the attempt is marked `TIMED_OUT` and only the
already-submitted responses are graded.

#### Scenario: Fixed-window remaining time counts to end_at
- **WHEN** a FIXED_WINDOW exam has `end_at` in the future and a student opens the taking screen
- **THEN** the countdown shows `end_at - now` regardless of when the student started

#### Scenario: Individual timer counts from start
- **WHEN** a student starts an INDIVIDUAL exam with `duration_minutes = 30`
- **THEN** the deadline is 30 minutes after `started_at` and is unaffected by `end_at`

#### Scenario: Late submit is timed out
- **WHEN** a submit arrives after the attempt's deadline
- **THEN** the attempt status is set to TIMED_OUT and only submitted responses are graded

### Requirement: Auto-grading on submit
On submit the system SHALL grade each response all-or-nothing and persist per-response
`is_correct` and `points_earned`, then set the attempt's `score` = sum of
`points_earned`, `total_points` = sum of question points, `correct_count`,
`total_questions`, `submitted_at`, `time_spent_seconds`, and status `SUBMITTED` (or
`TIMED_OUT`). An MCQ response is correct only when the single chosen option is the
correct option. An MR response is correct only when the set of chosen options equals the
set of correct options exactly.

#### Scenario: MCQ scored on exact single choice
- **WHEN** a student selects the one correct option for an MCQ worth 2 points
- **THEN** that response is `is_correct = true` with `points_earned = 2`

#### Scenario: MR requires the exact correct set
- **WHEN** an MR question's correct options are {A, C} and the student selects {A}
- **THEN** the response is `is_correct = false` with `points_earned = 0`

#### Scenario: MR full credit only on exact match
- **WHEN** an MR question's correct options are {A, C} and the student selects exactly {A, C}
- **THEN** the response is `is_correct = true` with the question's full points

### Requirement: Result summary
After submitting, the system SHALL show the student a result with the numeric score,
total points, pass/fail decision (`score >= passing_score`, or "no threshold" when
`passing_score` is null), correct-answer count over total questions, and time spent.

#### Scenario: Passing result
- **WHEN** an attempt's score meets or exceeds `passing_score`
- **THEN** the result screen marks the attempt as passed and shows score, correct count, and time

### Requirement: Per-question review
The system SHALL let the owning student review a submitted attempt question by question,
showing for each: the question, the student's selected option(s), the correct option(s),
whether it was correct, and the explanation. A student SHALL NOT review another student's
attempt (404).

#### Scenario: Owner reviews answers and explanations
- **WHEN** the owning student opens the review of their submitted attempt
- **THEN** each question shows their answer, the correct answer, correctness, and the explanation

#### Scenario: Cannot review someone else's attempt
- **WHEN** a student requests review of an attempt they do not own
- **THEN** the system responds 404

### Requirement: Practice test generation
The system SHALL let a student build a personal practice test by choosing a source scope
(a class or a specific exam they can access) and a question count, then random-sampling
that many MCQ/MR questions from the pool of accessible PUBLISHED exams. Sampled questions
and their options SHALL be copied into a new `PRACTICE` test with `created_by` = the
student and `status = PUBLISHED`, owned by that student only.

#### Scenario: Practice test is created from accessible questions
- **WHEN** a student requests a 10-question practice test from a class they are enrolled in
- **THEN** a new PRACTICE test owned by the student is created with up to 10 copied MCQ/MR questions and their options

#### Scenario: Practice draws only from accessible questions
- **WHEN** the accessible question pool is smaller than the requested count
- **THEN** the practice test is created with all available questions and no inaccessible questions are included

### Requirement: Exam readiness score
The system SHALL show the student, at `/my/tests/readiness`, a real-time readiness score
from 0–100 computed as the mean of best-attempt score-percentages across the MOCK and
MODULE exams the student can access, where an exam with no attempt contributes 0%. The
score SHALL carry a band label: below 50 = "Chưa sẵn sàng", 50–79 = "Khá", 80 and above
= "Sẵn sàng", plus a list of which exams are done and not done.

#### Scenario: Untaken exams pull the score down
- **WHEN** a student can access two MOCK/MODULE exams and has a best 100% on one and no attempt on the other
- **THEN** the readiness score is 50 and the untaken exam is listed as not done

#### Scenario: Band label reflects the score
- **WHEN** the computed readiness score is 82
- **THEN** the label shown is "Sẵn sàng"
