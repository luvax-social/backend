-- Adds the thirteen event types the interaction controls record: not interested, show fewer, mute,
-- message reactions, message request resolution, hidden and unhidden comments, and a change of the
-- interaction limit. The list is rewritten in full, with ordinals 1 to 20 unchanged, so every part
-- already on disk stays valid and the statement is a metadata change that rewrites no data.
-- Running it again after the column already carries this list changes nothing, which is what lets
-- the runner repeat a script whose history row was never written.
ALTER TABLE user_events MODIFY COLUMN event_type Enum8(
    'post_view' = 1, 'post_like' = 2, 'post_unlike' = 3, 'post_save' = 4, 'post_unsave' = 5,
    'post_share' = 6, 'post_comment' = 7, 'story_view' = 8, 'story_reply' = 9, 'profile_view' = 10,
    'profile_follow' = 11, 'profile_unfollow' = 12, 'search' = 13, 'hashtag_click' = 14,
    'comment_like' = 15, 'comment_reply' = 16, 'message_send' = 17, 'session_start' = 18,
    'session_end' = 19, 'app_open' = 20,
    'post_not_interested' = 21, 'post_not_interested_undo' = 22, 'account_show_fewer' = 23,
    'account_show_fewer_undo' = 24, 'account_mute' = 25, 'account_unmute' = 26,
    'message_reaction' = 27, 'message_reaction_remove' = 28, 'message_request_accept' = 29,
    'message_request_delete' = 30, 'comment_hidden' = 31, 'comment_unhide' = 32,
    'interaction_limit_change' = 33);
