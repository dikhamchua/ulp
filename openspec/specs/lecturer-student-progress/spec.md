# lecturer-student-progress Specification

## Purpose
TBD - created by archiving change lecturer-student-progress. Update Purpose after archive.
## Requirements
### Requirement: Access control for the progress tab
The endpoints `GET /lecturer/classes/{id}/progress` and `GET /lecturer/classes/{id}/progress/{studentId}/lessons` SHALL be restricted to LECTURER, HEAD, and ADMIN roles, and SHALL enforce class ownership through the same gate as other class-detail tabs (`classesService.getViewable(id, userId, role)`). A STUDENT or anonymous caller SHALL be denied by Spring Security. A lecturer who does not own the class (and is not HEAD/ADMIN) SHALL receive 403. A missing class SHALL yield 404.

#### Scenario: Owning lecturer opens the tab
- **WHEN** the class's owning lecturer requests the progress tab
- **THEN** the page renders with the progress dashboard

#### Scenario: Non-owning lecturer denied
- **WHEN** a lecturer who does not own the class requests the progress tab
- **THEN** the response is 403

#### Scenario: Student denied
- **WHEN** a STUDENT requests the progress tab
- **THEN** the request is rejected by Spring Security (403)

### Requirement: Cohort summary metrics
The page SHALL show four metrics computed over ALL ACTIVE-enrolled students of the class (independent of any active search, filter, or page): total student count; average completion percent (mean of each student's percent, integer rounded half-up, 0 when there are no students); count of students who have completed 0 lessons ("Chưa bắt đầu"); count of students who have completed all published lessons ("Hoàn thành 100%", only when the class has at least one published lesson). Each student's percent SHALL be completed ÷ total-published-lessons (integer, rounded half-up, 0 when the class has no published lessons), matching the student-facing definition.

#### Scenario: Averages over whole cohort
- **WHEN** a class has 4 published lessons and three ACTIVE students completed 4, 2, and 0 respectively
- **THEN** total = 3, average = round((100+50+0)/3) = 50%, not-started = 1, completed-100% = 1

#### Scenario: Metrics ignore active filter
- **WHEN** the lecturer applies the "Đang học" filter or a search term
- **THEN** the four summary metrics are unchanged (they always describe the full cohort)

#### Scenario: Class with no published lessons
- **WHEN** the class has 0 published lessons
- **THEN** every student's percent is 0, average is 0, completed-100% is 0, and no division error occurs

### Requirement: Paginated student progress table
The tab SHALL render a table of ACTIVE students with columns: student (full name, email, avatar initials), progress (bar + percent), lessons done ("n/total"), enrollment date, and last activity. Last activity SHALL be the most recent `learning_progress.updated_at` for that student across the class's lessons, displayed as a date; a student with no progress rows SHALL show "Chưa bắt đầu". The table SHALL be server-side paginated (default page size 10) returning a proper page window, and the aggregation SHALL NOT issue one query per student (no N+1).

#### Scenario: Page window
- **WHEN** a class has 25 ACTIVE students and the lecturer views page 1 at size 10
- **THEN** 10 rows render and the pager shows 3 pages

#### Scenario: Row values
- **WHEN** a student completed 2 of 4 published lessons
- **THEN** their row shows 50%, "2/4", their enrollment date, and their last-activity date

#### Scenario: Never-started student
- **WHEN** a student has no `learning_progress` rows in the class
- **THEN** their row shows 0%, "0/total", and "Chưa bắt đầu" as last activity

### Requirement: Server-side search
The table SHALL accept a search term that filters rows to students whose full name OR email contains the term (case-insensitive). Search SHALL be applied server-side before pagination. An empty term SHALL return all students.

#### Scenario: Search by name
- **WHEN** the lecturer searches "nguyen"
- **THEN** only students whose name or email contains "nguyen" (case-insensitive) appear, paginated

#### Scenario: Search resets to full set when cleared
- **WHEN** the search term is empty
- **THEN** all ACTIVE students are eligible for the table

### Requirement: Server-side status filter
The table SHALL support a status filter with values all / completed / in-progress / not-started, applied server-side. "not-started" selects students with 0 completed lessons; "in-progress" selects students with completed strictly between 0 and total; "completed" selects students with completed equal to total (only when total > 0); "all" applies no status restriction. The filter combines with search (both applied) and drives pagination, but does NOT change the summary metrics.

#### Scenario: In-progress filter
- **WHEN** the lecturer selects "Đang học"
- **THEN** only students with 0 < completed < total appear

#### Scenario: Completed filter with no published lessons
- **WHEN** the class has 0 published lessons and the lecturer selects "Hoàn thành"
- **THEN** no students appear (completed==total is not satisfied because total is 0)

### Requirement: Per-student lesson drill-down
`GET /lecturer/classes/{id}/progress/{studentId}/lessons` SHALL return, as JSON, the class's PUBLISHED non-deleted lessons grouped by section in display order, each annotated with the target student's status: COMPLETED, IN_PROGRESS, or NOT_STARTED (no progress row). The target `studentId` MUST be an ACTIVE member of the class, otherwise the endpoint SHALL respond 404 using the shared JSON failure envelope. The response SHALL be rendered client-side using `textContent` only (no `innerHTML` with user/content data).

#### Scenario: Drill-down status mapping
- **WHEN** the lecturer opens the drill-down for a student who completed lesson A, opened (but not completed) lesson B, and never opened lesson C
- **THEN** the JSON marks A COMPLETED, B IN_PROGRESS, C NOT_STARTED, grouped under their sections in order

#### Scenario: Drill-down for a non-member
- **WHEN** the lecturer requests the drill-down for a userId that is not an ACTIVE member of the class
- **THEN** the response is 404 with the shared failure envelope

#### Scenario: XSS-safe rendering
- **WHEN** a lesson or section title contains HTML markup
- **THEN** the drill-down panel renders it as literal text and executes no script

