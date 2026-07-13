## ADDED Requirements

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
Successful class enrollment SHALL create a `CLASS_ENROLLED` notification for the
enrolling student. Publishing a lesson SHALL create a `LESSON_PUBLISHED`
notification for every student currently enrolled in the lesson's class.

#### Scenario: Enrollment creates a notification
- **WHEN** a student successfully enrolls in a class (a new enrollment)
- **THEN** a `CLASS_ENROLLED` notification is created for that student
- **AND** it references the enrolled class

#### Scenario: Duplicate enrollment does not create a notification
- **WHEN** a student re-submits a join for a class they already belong to
- **THEN** no new `CLASS_ENROLLED` notification is created

#### Scenario: Publishing a lesson notifies enrolled students
- **WHEN** a lecturer publishes a lesson in a class with enrolled students
- **THEN** each enrolled student receives a `LESSON_PUBLISHED` notification
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
