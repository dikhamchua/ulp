## Requirements

### Requirement: Invite join creates a pending enrollment
When an authenticated user redeems a valid invite CODE or LINK for a joinable class, the system MUST create or transition their enrollment for that class to status PENDING (not ACTIVE). Lecturer-initiated channels (IMPORT, MANUAL) MUST continue to create ACTIVE enrollments immediately.

#### Scenario: First-time CODE join becomes PENDING
- **WHEN** a user with no enrollment submits a valid CODE invite for a class with capacity
- **THEN** an enrollment row is stored with status PENDING and joined_via CODE
- **AND** the invite use_count is not incremented
- **AND** the user does not gain class content access

#### Scenario: First-time LINK join becomes PENDING
- **WHEN** a user with no enrollment opens a valid LINK invite (`/j/{token}`) for a class with capacity
- **THEN** an enrollment row is stored with status PENDING and joined_via LINK
- **AND** the invite use_count is not incremented

#### Scenario: Import remains immediate ACTIVE
- **WHEN** a lecturer confirms an Excel import that adds a student
- **THEN** the enrollment is ACTIVE with joined_via IMPORT without requiring approval

### Requirement: Existing enrollment status on re-join
The system MUST apply the following rules when a user redeems CODE/LINK while an enrollment row already exists for the same class.

#### Scenario: Already ACTIVE is a no-op
- **WHEN** an ACTIVE member redeems a valid invite for the same class
- **THEN** the system does not change enrollment status
- **AND** surfaces an already-joined outcome (no second PENDING)

#### Scenario: Already PENDING is idempotent
- **WHEN** a PENDING member redeems a valid invite for the same class
- **THEN** the enrollment remains PENDING
- **AND** use_count is not incremented again
- **AND** the user is informed the request is already awaiting approval

#### Scenario: REJECTED may re-request
- **WHEN** a REJECTED member redeems a valid invite for the same class
- **THEN** the enrollment status becomes PENDING again
- **AND** the class owner is notified of a new request

#### Scenario: REMOVED re-join requires approval
- **WHEN** a REMOVED member redeems a valid CODE or LINK invite
- **THEN** the enrollment becomes PENDING (not auto ACTIVE)

#### Scenario: COMPLETED cannot re-join
- **WHEN** a COMPLETED member redeems an invite for the same class
- **THEN** the join is rejected with the existing already-completed reason

### Requirement: Capacity checks for pending join and approve
Capacity (`max_students`) MUST be evaluated against the count of ACTIVE enrollments only. The system MUST enforce capacity when creating/transitioning to PENDING and again when approving.

#### Scenario: Request blocked when class is full
- **WHEN** ACTIVE member count is already at max_students
- **AND** a user attempts CODE/LINK join
- **THEN** the join is rejected as class full
- **AND** no PENDING enrollment is created for that attempt

#### Scenario: Approve blocked when class became full
- **WHEN** a PENDING enrollment is approved
- **AND** ACTIVE count is already at max_students
- **THEN** approval fails without changing the PENDING status
- **AND** use_count is not incremented

### Requirement: Lecturer approves or rejects pending members
The class owner lecturer MUST be able to list PENDING enrollments for their class and approve or reject each request. Non-owners MUST NOT perform approve/reject.

#### Scenario: List pending on Members tab
- **WHEN** the class owner opens the Members tab
- **THEN** the UI shows the real count and list of PENDING students (not a hard-coded zero)

#### Scenario: Approve pending student
- **WHEN** the class owner approves a PENDING enrollment
- **THEN** the enrollment status becomes ACTIVE
- **AND** the related invite use_count is incremented by one when invite_code_id is present and max_uses allows
- **AND** the student gains class content access

#### Scenario: Reject pending student
- **WHEN** the class owner rejects a PENDING enrollment
- **THEN** the enrollment status becomes REJECTED
- **AND** the row is retained
- **AND** use_count is not changed
- **AND** the student still cannot access class content

#### Scenario: Non-owner cannot approve
- **WHEN** a lecturer who is not the class owner attempts approve or reject
- **THEN** the system denies the action

#### Scenario: Invite disabled after request still approvable
- **WHEN** a PENDING enrollment exists
- **AND** the invite token used is later disabled or expired
- **THEN** the owner can still approve that existing PENDING enrollment
- **AND** new join attempts with that token remain blocked by join validation

### Requirement: Pending students cannot access class content
The system MUST NOT treat PENDING or REJECTED enrollments as admitted membership for learning surfaces (lessons, tests, flashcards, progress, messaging class gates, attachments, etc.).

#### Scenario: PENDING blocked from lessons
- **WHEN** a PENDING student opens a student lesson list or lesson detail for that class
- **THEN** access is denied under the same no-leak policy used for non-members

#### Scenario: Student sees waiting state on my classes
- **WHEN** a student has one or more PENDING enrollments
- **THEN** `/my/classes` surfaces those classes as awaiting approval ("đang chờ duyệt")
- **AND** does not present them as fully enrolled ACTIVE classes with unrestricted entry
