package com.app.modules.hashtag.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.app.modules.hashtag.dto.response.HashtagSummaryResponse;

/** Manages canonical hashtags and their associations to posts. */
public interface HashtagService {

    /**
     * Normalizes a raw tag by stripping a leading {@code #}, trimming, and lowercasing.
     *
     * <p>Returns an empty string for a {@code null} or blank input so callers can drop it. Also
     * returns an empty string when the normalized tag exceeds the {@code hashtags.name} column
     * bound of 100 Unicode code points, so callers drop it instead of failing the insert.
     *
     * @param raw the raw tag as supplied by the client
     * @return the normalized lowercase hashtag name, or an empty string when {@code raw} is null,
     *     blank, or exceeds 100 Unicode code points
     */
    String normalize(String raw);

    /**
     * Normalizes and de-duplicates the supplied tags, upserts the hashtag rows, and associates them
     * to the post via {@code post_hashtags} rows in an idempotent manner.
     *
     * <p>For each affected hashtag an index-upsert event is enqueued in the transactional outbox,
     * propagating the change to Elasticsearch asynchronously after commit. The {@code
     * hashtags.post_count} counter is maintained by the Postgres trigger {@code
     * trg_hashtag_post_count} and is never written from application code.
     *
     * <p>Refuses the whole call when any supplied tag names a banned hashtag. This is the last line
     * rather than the first: every post write path checks before it mutates anything, so a rejected
     * write leaves no post row and no edit-history row. The check here exists so a future caller
     * that forgets fails loudly instead of silently associating a banned tag.
     *
     * @param postId the post the hashtags are associated with
     * @param rawTags the raw tags to normalize and associate
     * @throws com.app.common.exception.AppException with {@code POST_BANNED_HASHTAG} when any tag
     *     is banned
     */
    void upsertHashtagsForPost(UUID postId, List<String> rawTags);

    /**
     * Associates the supplied tags to the post, skipping any that name a banned hashtag.
     *
     * <p>The single write path that tolerates a banned tag instead of refusing. A moderator
     * restoring a post it removed by mistake is correcting its own error and must not be blocked by
     * an unrelated administrator decision it has no power to reverse; refusing would create a
     * deadlock with no in-role resolution. The skipped names are returned so the caller can record
     * them and tell the moderator what was dropped.
     *
     * @param postId the post the hashtags are associated with
     * @param rawTags the raw tags to normalize and associate
     * @return the normalized names that were skipped because they are banned, in caption order;
     *     empty when none was
     */
    List<String> upsertHashtagsForPostSkippingBanned(UUID postId, List<String> rawTags);

    /**
     * Returns the subset of the supplied names that name a banned hashtag.
     *
     * <p>Names are normalized by this method, so a caller may pass raw caption tags. The returned
     * names are in normalized form, which is what a client needs to highlight them in the caption.
     *
     * @param rawNames tags to test; may be empty
     * @return the banned subset in input order, de-duplicated; empty for empty input
     */
    List<String> findBannedNames(Collection<String> rawNames);

    /**
     * Returns the hashtags to list on each of the given posts.
     *
     * <p>Deleted hashtags are omitted. Their {@code post_hashtags} rows are deliberately left in
     * place, so nothing is lost if the tag is restored; the caption keeps its literal {@code #tag}
     * text either way and a client simply does not render it as a link. A banned hashtag is still
     * listed, because banning hides the tag's own surfaces and not the posts that used it.
     *
     * @param postIds the posts to hydrate
     * @return hashtags grouped by post id, ordered by name; posts with none are absent
     */
    Map<UUID, List<HashtagSummaryResponse>> getVisibleHashtagsForPosts(Collection<UUID> postIds);

    /**
     * Removes all {@code post_hashtags} associations for the post; the Postgres trigger decrements
     * {@code hashtags.post_count} for each removed row.
     *
     * <p>Used on post unpublish or soft-delete. For each affected hashtag an outbox index event is
     * enqueued: an upsert when the hashtag still has associated posts, otherwise a delete event to
     * drop it from the search index.
     *
     * @param postId the post whose hashtag associations are removed
     */
    void removeHashtagsForPost(UUID postId);

    /**
     * Returns the hashtag ids associated with each of the given posts.
     *
     * <p>Posts without hashtag associations are absent from the returned map.
     *
     * @param postIds the posts to look up
     * @return hashtag ids grouped by post id; an empty map for empty input
     */
    Map<UUID, List<UUID>> getHashtagIdsForPosts(Collection<UUID> postIds);

    /**
     * Returns the hashtag names associated with the given post, ordered by name.
     *
     * <p>Names rather than ids, because the recommender computes tag similarity on the label
     * strings themselves.
     *
     * @param postId the post to look up
     * @return hashtag names for the post; empty when it carries none
     */
    List<String> getHashtagNamesForPost(UUID postId);

    /**
     * Returns the hashtag names of many posts at once, each list ordered by name.
     *
     * <p>Applies exactly the rule of {@link #getHashtagNamesForPost}, so a bulk push of items to
     * the recommender labels them the way the live sync does.
     *
     * @param postIds the posts to look up
     * @return names grouped by post id; posts without a hashtag are absent, and empty input gives
     *     an empty map
     */
    Map<UUID, List<String>> getHashtagNamesForPosts(Collection<UUID> postIds);
}
