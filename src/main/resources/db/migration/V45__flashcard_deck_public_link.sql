-- flashcard-share-redesign — a deck may additionally be reachable by public link.
--
-- Two columns rather than one: is_public is the on/off switch, share_token is
-- the identity. Turning the link off keeps the token, so turning it back on
-- revives links already sent out. Using "token IS NULL" as the flag would mint
-- a new token on every toggle and permanently kill previously shared URLs.
--
-- There is no down-migration — rollback is a database restore.

-- 1. The public-link switch plus its token. VARCHAR(40) holds the 32-char
-- base64url token minted by InviteTokenGenerator, with headroom for a future
-- length change.
ALTER TABLE flashcard_decks
    ADD COLUMN share_token VARCHAR(40) NULL,
    ADD COLUMN is_public TINYINT(1) NOT NULL DEFAULT 0;

-- 2. Unique so token lookup cannot resolve to two decks. MySQL does not treat
-- NULLs as duplicates in a unique index, so every never-shared deck coexists.
CREATE UNIQUE INDEX idx_fd_share_token ON flashcard_decks (share_token);
