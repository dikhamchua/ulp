## ADDED Requirements

### Requirement: Recipient eligibility gate
The system SHALL restrict who a user may start a new conversation with. A student MAY
start a conversation only with a lecturer (role LECTURER or HEAD) of a class in which
the student has an ACTIVE enrollment. A lecturer MAY start a conversation only with a
student who has an ACTIVE enrollment in a class the lecturer teaches. The system SHALL
reject student↔student and lecturer↔lecturer pairs. Either eligible party MAY initiate.
The gate SHALL apply ONLY when creating a conversation or searching for recipients, and
SHALL NOT be re-evaluated for an existing conversation.

#### Scenario: Student starts conversation with lecturer of an enrolled class
- **WHEN** a student opens a new conversation targeting the lecturer of a class where the student's enrollment is ACTIVE
- **THEN** the system creates (or reuses) the conversation and opens it

#### Scenario: Lecturer starts conversation with a student in a taught class
- **WHEN** a lecturer opens a new conversation targeting a student with an ACTIVE enrollment in a class the lecturer teaches
- **THEN** the system creates (or reuses) the conversation and opens it

#### Scenario: Ineligible recipient is rejected without leaking existence
- **WHEN** a student targets a lecturer they share no ACTIVE-enrollment class with, or targets another student
- **THEN** the system denies the request with a not-found/forbidden response that does not reveal whether the target user exists

#### Scenario: Recipient search returns only eligible peers
- **WHEN** a user searches for recipients to start a new conversation
- **THEN** the system returns only users that pass the eligibility gate for the caller

### Requirement: Conversation creation is idempotent per pair
The system SHALL maintain at most one conversation per unordered pair of users. Starting
a conversation with a peer for whom a conversation already exists SHALL open the existing
conversation rather than creating a duplicate.

#### Scenario: Reopening an existing conversation
- **WHEN** a user starts a new conversation with a peer they already have a conversation with
- **THEN** the system opens the existing conversation and does not create a second one

### Requirement: Sending a message
The system SHALL allow a participant of a conversation to send a text message of at most
2000 characters. The message SHALL be persisted with its sender and creation time, and the
conversation's last-activity timestamp SHALL be updated. Membership (caller is one of the
two participants) SHALL be enforced; enrollment SHALL NOT be re-checked.

#### Scenario: Participant sends a valid message
- **WHEN** a participant submits a non-empty message of at most 2000 characters
- **THEN** the system stores the message, updates the conversation's last-activity time, and shows it in the thread

#### Scenario: Message exceeding the length limit is rejected
- **WHEN** a participant submits a message longer than 2000 characters
- **THEN** the system rejects it and does not persist the message

#### Scenario: Non-participant cannot send to a conversation
- **WHEN** a user who is not one of the two participants attempts to send to a conversation
- **THEN** the system denies the request

#### Scenario: Removed student can still use an existing conversation
- **WHEN** a student whose enrollment later became REMOVED sends a message in a conversation that already exists with the lecturer
- **THEN** the system accepts the message without re-checking enrollment

### Requirement: Real-time message delivery
The system SHALL deliver a newly sent message to the recipient in real time over a
WebSocket (STOMP) connection, without requiring the recipient to reload the page. The
handshake SHALL require an authenticated session.

#### Scenario: Recipient sees a new message live
- **WHEN** a message is sent to a recipient who has the app open with an active WebSocket connection
- **THEN** the recipient receives the message push and, if the relevant conversation is open, the message appears in the thread without a reload

#### Scenario: Unauthenticated handshake is rejected
- **WHEN** an unauthenticated client attempts the WebSocket handshake
- **THEN** the system denies the connection

### Requirement: Viewing conversations
The system SHALL present a list of the caller's conversations ordered by most recent
activity, and SHALL allow opening a single conversation to view its messages in
chronological order. Both the conversation list and the message thread SHALL be paginated.

#### Scenario: Listing conversations
- **WHEN** a user opens the messaging page
- **THEN** the system lists their conversations, each showing the peer's name and a snippet of the last message, most-recent first

#### Scenario: Opening a conversation shows its thread
- **WHEN** a user opens one of their conversations
- **THEN** the system shows the messages in chronological order with the caller's messages and the peer's messages visually distinguished

#### Scenario: Empty state when no conversations exist
- **WHEN** a user with no conversations opens the messaging page
- **THEN** the system shows an empty state rather than an error
