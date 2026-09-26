package com.app.modules.recommendation.client;

import java.util.List;
import java.util.UUID;

import com.app.modules.recommendation.client.dto.GorseFeedback;
import com.app.modules.recommendation.client.dto.GorseItem;
import com.app.modules.recommendation.client.dto.GorseScore;
import com.app.modules.recommendation.client.dto.GorseUser;

/**
 * REST client contract for the Gorse recommender service (v0.5.11).
 *
 * <p>Methods propagate {@link org.springframework.web.client.RestClientException} on transport or
 * server failure; resilience (circuit breaking on the read path, retry/dead-letter on the write
 * path) is the caller's responsibility.
 */
public interface GorseClient {

    /**
     * Fetches personalized recommendations for a user.
     *
     * <p>Users unknown to Gorse are served by its configured fallback recommenders, so this call
     * also covers cold-start users.
     *
     * @param userId app user id
     * @param n maximum number of items to return
     * @param offset zero-based offset into the cached recommendation list
     * @return scored item ids ordered best-first; empty when Gorse has nothing cached
     */
    List<GorseScore> recommend(UUID userId, int n, int offset);

    /**
     * Fetches the non-personalized popularity ranking (recommender name {@code popular}).
     *
     * @param n maximum number of items to return
     * @param offset zero-based offset into the popularity list
     * @return scored item ids ordered by popularity; empty when not yet computed
     */
    List<GorseScore> popular(int n, int offset);

    /**
     * Fetches the time-decayed trending ranking (recommender name {@code trending}).
     *
     * <p>Used both as a discovery surface on its own and as the source {@link
     * com.app.modules.recommendation.service.impl.feed.RecommendationSource} tops up from once a
     * user's personalized candidate list runs short.
     *
     * @param n maximum number of items to return
     * @param offset zero-based offset into the trending list
     * @return scored item ids ordered by trending score; empty when not yet computed
     */
    List<GorseScore> trending(int n, int offset);

    /**
     * Fetches the accounts Gorse considers most similar to one user.
     *
     * <p>Backed by the {@code [[recommend.user-to-user]]} recommender and its background task. Two
     * conditions make this return an empty list rather than fail: the recommender may not be
     * configured at all, and its task may not have completed since the last retrain. Both are
     * indistinguishable from "this user has no neighbours yet" at the wire level, and all three are
     * handled the same way by the caller: a source that contributes nothing.
     *
     * <p>That is why an empty answer here must never be treated as a failure. Charging it to the
     * circuit breaker would open the breaker on a correctly working Gorse that simply has no
     * user-to-user recommender, and take the personalized feed down with it.
     *
     * @param userId app user id
     * @param n maximum number of neighbours to return
     * @return scored user ids ordered most-similar first; empty when Gorse has nothing
     */
    List<GorseScore> userNeighbors(UUID userId, int n);

    /**
     * Inserts or updates users.
     *
     * @param users users in Gorse wire format
     */
    void upsertUsers(List<GorseUser> users);

    /**
     * Inserts or updates items.
     *
     * @param items items in Gorse wire format
     */
    void upsertItems(List<GorseItem> items);

    /**
     * Hides an existing item so it is never recommended again.
     *
     * <p>Only effective for an item Gorse already holds. Verified against v0.5.11: called with an
     * unknown id it answers {@code 200} with {@code RowAffected: 1} and stores nothing, so the item
     * stays absent and {@code auto_insert_item} later creates it visible from feedback alone.
     * Upsert the item with {@code IsHidden} set instead when its presence is not guaranteed.
     *
     * @param itemId item id to hide
     */
    void hideItem(String itemId);

    /**
     * Inserts feedback rows.
     *
     * <p>Row-idempotent in v0.5.11: re-inserting an existing (type, user, item) tuple keeps a
     * single row rather than duplicating it.
     *
     * <p>The row's {@code Value} is <b>accumulated, not overwritten</b>. Measured: inserting 2.0
     * then 5.0 for one tuple leaves one row holding 7.0. So a value is a running total for that
     * pair - the intended reading for dwell seconds - and a replay that reached this method twice
     * would inflate it. Redelivery is safe only because the inbox guard skips the handler on a
     * duplicate event id; do not weaken that guard on the assumption this call is fully idempotent.
     *
     * @param feedback feedback rows in Gorse wire format
     */
    void insertFeedback(List<GorseFeedback> feedback);
}
