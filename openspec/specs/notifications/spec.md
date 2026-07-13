## Requirements

### Requirement: View own notifications list
The system SHALL provide an authenticated page at `/my/notifications` that lists
only the signed-in user's notifications, ordered newest first, with unread
notifications visually emphasized. Access SHALL be restricted to authenticated
users of any role (STUDENT, LECTURER, HEAD, ADMIN).

#### Scenario: Authenticated user views their notifications
- **WHEN** an authenticated user opens `/my/notifications`
- **THEN** the page shows that user's notifications, newest first
- **AND** unread notifications are visually distinguished from read ones

#### Scenario: User has no notifications
- **WHEN** an authenticated user with no notifications opens `/my/notifications`
- **THEN** the page renders an empty state instead of an error

#### Scenario: Anonymous access is blocked
- **WHEN** an unauthenticated visitor requests `/my/notifications`
- **THEN** the system redirects to the login page (Spring Security)

#### Scenario: A user cannot see another user's notifications
- **WHEN** user A opens `/my/notifications`
- **THEN** only notifications whose `user_id` equals A's id are listed
- **AND** no notification belonging to any other user is shown

### Requirement: Unread notification count badge
The system SHALL expose the signed-in user's unread notification count to every
server-rendered view so the shared header can display a bell badge. For
anonymous requests the exposed count SHALL be zero.

#### Scenario: Badge reflects unread count
- **WHEN** an authenticated user with N unread notifications loads any page
- **THEN** the header bell badge shows N

#### Scenario: No badge for anonymous users
- **WHEN** an unauthenticated visitor loads a public page
- **THEN** the exposed unread count is zero and the bell badge is hidden

#### Scenario: Polling endpoint returns unread count
- **WHEN** the client requests the unread-count JSON endpoint while authenticated
- **THEN** the response contains the caller's current unread notification count

### Requirement: Mark notification as read
The system SHALL mark a notification as read when its owner opens it, recording
the read timestamp and decrementing the unread count. A user SHALL only be able
to mark their own notifications as read.

#### Scenario: Owner opens an unread notification
- **WHEN** the owner opens one of their unread notifications
- **THEN** the notification's `is_read` becomes true and `read_at` is set
- **AND** the unread count decreases by one

#### Scenario: Opening a notification with a reference redirects
- **WHEN** the owner opens a notification that carries a reference (e.g. a class)
- **THEN** the notification is marked read
- **AND** the user is redirected to the linked resource

#### Scenario: Opening a notification without a reference stays on the list
- **WHEN** the owner opens a notification that has no reference
- **THEN** the notification is marked read
- **AND** the user remains on the notifications page

#### Scenario: Non-owner cannot mark a notification read
- **WHEN** a user attempts to mark a notification that belongs to another user
- **THEN** the notification is not modified (no-leak; treated as not found)

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

### Requirement: Best-effort email for important notifications
The system SHALL send an email through the existing mail service when a
notification of an email-whitelisted type (`LESSON_PUBLISHED`) is created, and
SHALL set `is_email_sent` to true only when delivery succeeds. Email delivery
SHALL be best-effort: failure or unconfigured SMTP MUST NOT prevent creation or
display of the in-app notification. Non-whitelisted types MUST NOT trigger email.

#### Scenario: Whitelisted notification triggers email
- **WHEN** a `LESSON_PUBLISHED` notification is created and SMTP is configured
- **THEN** an email is sent to the recipient's address
- **AND** the notification's `is_email_sent` is set to true

#### Scenario: Non-whitelisted notification does not trigger email
- **WHEN** a `CLASS_ENROLLED` notification is created
- **THEN** no email is sent
- **AND** the notification's `is_email_sent` remains false

#### Scenario: Email failure does not break in-app notification
- **WHEN** a `LESSON_PUBLISHED` notification is created but email delivery fails
- **THEN** the in-app notification is still persisted and visible
- **AND** its `is_email_sent` remains false

### Requirement: Assignment notification types
The notifications capability SHALL support two additional types for the
assignment lifecycle: `ASSIGNMENT_PUBLISHED` (emitted to enrolled students when
an assignment is published) and `ASSIGNMENT_GRADED` (emitted to a student when
their submission is graded), plus an `ASSIGNMENT` reference type. Only
`ASSIGNMENT_PUBLISHED` SHALL be added to the email whitelist; `ASSIGNMENT_GRADED`
SHALL NOT trigger email. Existing notification behavior is unchanged.

#### Scenario: Publish notification is email-whitelisted
- **WHEN** an `ASSIGNMENT_PUBLISHED` notification is created and SMTP is configured
- **THEN** a best-effort email is sent to the recipient
- **AND** the notification references the assignment (reference type `ASSIGNMENT`)

#### Scenario: Grade notification does not trigger email
- **WHEN** an `ASSIGNMENT_GRADED` notification is created
- **THEN** no email is sent
- **AND** the in-app notification is still created for the student

#### Scenario: Assignment notifications are owner-scoped like all others
- **WHEN** a student opens their notifications
- **THEN** they see only their own assignment notifications (no existence leak)

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
