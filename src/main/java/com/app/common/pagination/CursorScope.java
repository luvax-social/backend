package com.app.common.pagination;

/**
 * Short, stable, non-secret scope tags embedded in every {@link CursorCodec} cursor.
 *
 * <p>One constant per issuing endpoint. Renaming a constant's value is a breaking change for any
 * cursor already in flight; adding a new one is always safe.
 */
public final class CursorScope {

    private CursorScope() {}

    public static final String ADMIN_ACTIONS = "adma";
    public static final String ADMIN_ACTIONS_FOR_USER = "admu";
    public static final String ADMIN_USERS = "admul";
    // One tag per role, not one per endpoint, because the violations listing returns different
    // rows to a moderator and to an administrator. A shared tag would let a moderator replay an
    // administrator's cursor and page into strike rows its own listing never produces.
    public static final String ADMIN_VIOLATIONS_WARNINGS = "admvw";
    public static final String ADMIN_VIOLATIONS_FULL = "admvf";
    // Including revoked rows is a different result set, so a cursor issued for one listing
    // must not be replayable on the other. Four scopes, one per role-and-inclusion pair.
    public static final String ADMIN_VIOLATIONS_WARNINGS_ALL = "admvwa";
    public static final String ADMIN_VIOLATIONS_FULL_ALL = "admvfa";
    public static final String OWN_WARNINGS = "ownw";
    public static final String ADMIN_USER_SEARCH = "admus";
    public static final String ADMIN_USER_EVENTS = "admue";
    // One tag per endpoint, not one shared "admin hashtags" tag. The listing and the search apply
    // different predicates over the same keyset ordering, so a shared tag would let a cursor from
    // one be replayed into the other and page into rows that endpoint never produced.
    public static final String ADMIN_CONTENT_POSTS = "admcp";
    public static final String ADMIN_CONTENT_COMMENTS = "admcc";
    public static final String ADMIN_HASHTAGS = "admh";
    public static final String ADMIN_HASHTAG_SEARCH = "admhs";
    // One tag for the listing and its optional search term: unlike the hashtag pair, both walk the
    // same keyset ordering over the same table, so a position from one is valid in the other.
    public static final String ADMIN_HIDDEN_WORDS = "admhw";
    public static final String COMMENTS_TOP_LEVEL = "cmt";
    public static final String COMMENTS_TOP_LEVEL_NEWEST = "cmtn";
    public static final String COMMENT_REPLIES = "rpl";
    public static final String COMMENTS_HIDDEN = "cmh";
    public static final String CONVERSATIONS = "cnv";
    public static final String NOTIFICATIONS = "ntf";
    public static final String POST_USER_POSTS = "pst";
    public static final String POST_FEED = "feed";
    public static final String POST_EDIT_HISTORY = "edh";
    public static final String POST_LIKERS = "lik";
    public static final String POST_LIKED_POSTS = "lkd";
    public static final String POST_SAVES = "sav";
    public static final String REPORTS = "rpt";
    // A moderator's report listing is narrowed to the open statuses, so its cursor is
    // tagged separately. Sharing REPORTS would let a moderator replay an
    // administrator's cursor and page into rows its own listing never produces.
    public static final String REPORTS_MODERATOR = "rptm";
    public static final String PENDING_REPORTS = "prpt";
    // The caller's own escalations. Its own scope because the row set is per caller: a
    // cursor issued to one moderator names a position in a list nobody else has.
    public static final String REPORTS_ESCALATED_BY_ME = "rptem";
    public static final String SOCIAL_FOLLOWERS = "flw";
    public static final String SOCIAL_FOLLOWING = "flg";
    public static final String SOCIAL_BLOCKED = "blk";
    public static final String SOCIAL_PENDING_REQUESTS = "frq";
    public static final String STORY_VIEWERS = "stv";
}
