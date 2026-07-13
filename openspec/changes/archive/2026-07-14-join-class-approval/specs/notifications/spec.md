## ADDED Requirements

### Requirement: Join approval notification types
The notifications capability SHALL support three additional in-app types for the join-approval lifecycle: `JOIN_REQUEST` (to the class owner when a student creates or re-opens a PENDING enrollment via CODE/LINK), `JOIN_APPROVED` (to the student when the owner approves), and `JOIN_REJECTED` (to the student when the owner rejects). These types MUST NOT be added to the email whitelist. Notification creation remains best-effort and MUST NOT roll back the enrollment transaction on failure.

#### Scenario: Lecturer notified on new join request
- **WHEN** a student successfully creates or re-opens a PENDING enrollment via CODE or LINK
- **THEN** a `JOIN_REQUEST` notification is created for the class owner lecturer
- **AND** it references the class

#### Scenario: Student notified on approve
- **WHEN** the class owner approves a PENDING enrollment
- **THEN** a `JOIN_APPROVED` notification is created for the student
- **AND** a `CLASS_ENROLLED` notification is also created for the student to reflect successful admission

#### Scenario: Student notified on reject
- **WHEN** the class owner rejects a PENDING enrollment
- **THEN** a `JOIN_REJECTED` notification is created for the student
- **AND** no `CLASS_ENROLLED` notification is created

#### Scenario: Idempotent pending re-join does not re-notify
- **WHEN** a student who is already PENDING redeems the invite again
- **THEN** no additional `JOIN_REQUEST` notification is required

#### Scenario: Join approval types do not email
- **WHEN** a `JOIN_REQUEST`, `JOIN_APPROVED`, or `JOIN_REJECTED` notification is created
- **THEN** no email is sent for that notification type

## MODIFIED Requirements

### Requirement: Event-driven notification creation
The system SHALL create a notification when a domain event of interest occurs.
Successful class **admission** (enrollment becoming ACTIVE through lecturer approval of a CODE/LINK request, or through lecturer-initiated IMPORT/MANUAL paths that create ACTIVE enrollments) SHALL create a `CLASS_ENROLLED` notification for the student. Creating a PENDING join request MUST NOT create `CLASS_ENROLLED`. Publishing a lesson SHALL create a `LESSON_PUBLISHED` notification for every student currently enrolled (ACTIVE) in the lesson's class.

#### Scenario: Enrollment creates a notification
- **WHEN** a student becomes ACTIVE in a class (approved invite join or lecturer-initiated ACTIVE enrollment)
- **THEN** a `CLASS_ENROLLED` notification is created for that student
- **AND** it references the enrolled class

#### Scenario: Pending request does not create CLASS_ENROLLED
- **WHEN** a student submits a CODE/LINK join that results in PENDING
- **THEN** no `CLASS_ENROLLED` notification is created for that student

#### Scenario: Duplicate enrollment does not create a notification
- **WHEN** a student re-submits a join for a class they already belong to as ACTIVE
- **THEN** no new `CLASS_ENROLLED` notification is created

#### Scenario: Publishing a lesson notifies enrolled students
- **WHEN** a lecturer publishes a lesson in a class with ACTIVE enrolled students
- **THEN** each ACTIVE enrolled student receives a `LESSON_PUBLISHED` notification
- **AND** it references the published lesson
