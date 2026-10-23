-- Persists @mentions by user id, so a mention keeps pointing at the person it named.
--
-- A mention used to exist only as literal text in a caption or comment, resolved against whatever
-- the username meant at read time. After a rename, or after an account is deleted and the name is
-- taken by someone else, that text would link to a stranger. These rows record who was mentioned
-- when the text was written; the client renders the current username of that user id and renders
-- any other @name as plain text.
--
-- handle is the text exactly as the author typed it (without the @), and is how the client finds
-- the occurrence in the text. Both user and parent are ON DELETE CASCADE: when the mentioned
-- account disappears the row goes with it and the text reads as plain text.
--
-- Existing comments are not back-filled. Resolving their text against today's usernames could
-- attach a mention to a different person than the one the author meant.

CREATE TABLE post_mentions (
    post_id             UUID            NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    mentioned_user_id   UUID            NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    handle              VARCHAR(30)     NOT NULL,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    PRIMARY KEY (post_id, mentioned_user_id)
);

CREATE TABLE comment_mentions (
    comment_id          UUID            NOT NULL REFERENCES comments(id) ON DELETE CASCADE,
    mentioned_user_id   UUID            NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    handle              VARCHAR(30)     NOT NULL,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    PRIMARY KEY (comment_id, mentioned_user_id)
);
