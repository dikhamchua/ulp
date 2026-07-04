## ADDED Requirements

### Requirement: Unread count derivation
The system SHALL compute a user's unread message count as the number of messages in
their conversations that have not been read and were not sent by that user. The count
SHALL be derivable at any time from persisted message state (no separate counter store).

#### Scenario: Unread count reflects messages from peers
- **WHEN** a user has received messages they have not yet opened
- **THEN** the unread count equals the number of those unread, peer-sent messages

#### Scenario: Own messages never count as unread
- **WHEN** a user sends messages in a conversation
- **THEN** those messages do not contribute to that user's own unread count

### Requirement: Header badge display
The system SHALL show a chat badge in the app header with the caller's total unread
count. The badge SHALL be rendered server-side on page load and SHALL be hidden when the
count is zero.

#### Scenario: Badge shows a positive count
- **WHEN** a page loads for a user with one or more unread messages
- **THEN** the header shows the chat badge with the total unread count

#### Scenario: Badge hidden at zero
- **WHEN** a page loads for a user with no unread messages
- **THEN** the header shows no unread badge

### Requirement: Live badge update
The system SHALL update the header badge in real time over the WebSocket connection when
a new message arrives, without requiring a page reload.

#### Scenario: Badge increments on incoming message
- **WHEN** the user receives a new message while the app is open
- **THEN** the header badge count increases without a reload

### Requirement: Marking read on open
The system SHALL mark a conversation's peer-sent unread messages as read when the user
opens that conversation, and the header badge SHALL decrease accordingly.

#### Scenario: Opening a conversation clears its unread messages
- **WHEN** a user opens a conversation containing unread peer-sent messages
- **THEN** those messages become read and the header badge decreases by that conversation's unread count

#### Scenario: Unread-count endpoint for fallback
- **WHEN** a client requests the current unread count via the fallback endpoint
- **THEN** the system returns the caller's current total unread count
