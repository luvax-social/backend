package com.app.modules.social.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.app.common.exception.AppException;
import com.app.common.response.CursorPageResponse;
import com.app.common.response.UserListItemResponse;
import com.app.common.response.ViewerRelationshipResponse;
import com.app.modules.social.dto.response.FollowRequestResponse;
import com.app.modules.social.dto.response.FollowResponse;
import com.app.modules.social.enums.FollowStatus;

public interface SocialService {

    /**
     * Creates a follow relationship from the current user to the target user.
     *
     * <p>Self-follow is rejected, as is following a user with an active block in either direction.
     * The resulting status is {@code PENDING} when the target account is private and {@code
     * ACCEPTED} otherwise; counters only increment on acceptance. The insert is a single
     * INSERT-RETURNING statement so a concurrent duplicate is rejected atomically rather than
     * silently no-oping.
     *
     * @param currentUserId user initiating the follow
     * @param targetUserId user being followed
     * @return the created follow relationship and its resulting status
     * @throws AppException when following self, a block exists between the pair, or the
     *     relationship already exists (pending or accepted)
     */
    FollowResponse followUser(UUID currentUserId, UUID targetUserId);

    /**
     * Removes an existing follow relationship from the current user to the target user.
     *
     * <p>Applies regardless of whether the relationship was pending or accepted, which also serves
     * as the cancellation path for a follow request the current user sent.
     *
     * @param currentUserId user removing their own follow
     * @param targetUserId user currently being followed
     * @throws AppException when the target user does not exist or no follow relationship exists
     */
    void unfollowUser(UUID currentUserId, UUID targetUserId);

    /**
     * Approves or rejects a pending follow request sent to the current user.
     *
     * @param currentUserId user who received the follow request
     * @param requesterId user who sent the follow request
     * @param action {@code "approve"} to accept the request or {@code "reject"} to delete it, case
     *     insensitive
     * @throws AppException when no pending request exists from the requester, or {@code action} is
     *     neither recognized value
     */
    void respondToFollowRequest(UUID currentUserId, UUID requesterId, String action);

    /**
     * Blocks a target user, deleting any follow relationship between the pair in either direction.
     *
     * @param currentUserId user creating the block
     * @param targetUserId user being blocked
     * @throws AppException when blocking self, the target user does not exist, or the block already
     *     exists
     */
    void blockUser(UUID currentUserId, UUID targetUserId);

    /**
     * Removes an existing block from the current user to the target user.
     *
     * <p>Does not restore any follow relationship that was deleted when the block was created.
     *
     * @param currentUserId user who created the block
     * @param targetUserId user who was blocked
     * @throws AppException when no block relationship exists
     */
    void unblockUser(UUID currentUserId, UUID targetUserId);

    /**
     * Cursor-paginated list of a target user's followers, newest follow first.
     *
     * <p>A private target account is only visible to its owner or an accepted follower; a block in
     * either direction between viewer and target hides the list entirely.
     *
     * @param targetUserId user whose followers are listed
     * @param currentUserId authenticated viewer, used for the visibility and block checks and to
     *     resolve each row's viewer relationship
     * @param cursor opaque base64 cursor from the previous page; null or blank for the first page
     * @param limit requested page size, normalized to 1-100 with a default of 20
     * @return cursor page of followers with viewer relationship state
     * @throws AppException when the target user does not exist, is blocked, or is a private account
     *     the viewer cannot see
     */
    CursorPageResponse<UserListItemResponse> getFollowers(
            UUID targetUserId, UUID currentUserId, String cursor, int limit);

    /**
     * Cursor-paginated list of the users a target user follows, newest follow first.
     *
     * <p>A private target account is only visible to its owner or an accepted follower; a block in
     * either direction between viewer and target hides the list entirely.
     *
     * @param targetUserId user whose following list is listed
     * @param currentUserId authenticated viewer, used for the visibility and block checks and to
     *     resolve each row's viewer relationship
     * @param cursor opaque base64 cursor from the previous page; null or blank for the first page
     * @param limit requested page size, normalized to 1-100 with a default of 20
     * @return cursor page of followed users with viewer relationship state
     * @throws AppException when the target user does not exist, is blocked, or is a private account
     *     the viewer cannot see
     */
    CursorPageResponse<UserListItemResponse> getFollowing(
            UUID targetUserId, UUID currentUserId, String cursor, int limit);

    /**
     * Cursor-paginated list of the users the current user has blocked, newest block first.
     *
     * <p>Self-scoped: it lists only the viewer's <em>outgoing</em> blocks, so there is no target
     * whose privacy could be at stake and no visibility gate applies. Users who blocked the viewer
     * are not included; no endpoint exposes incoming blocks.
     *
     * <p>A blocked account that has since been soft-deleted resolves to a placeholder rather than
     * being dropped, so the page length stays consistent with the row count. {@code isBlocking} is
     * tautologically true on every row and is emitted anyway, so a client never has to branch on
     * which endpoint produced the row.
     *
     * @param currentUserId authenticated user whose outgoing blocks are listed
     * @param cursor opaque base64 cursor from the previous page; null or blank for the first page
     * @param limit requested page size, normalized to 1-100 with a default of 20
     * @return cursor page of blocked users with viewer relationship state
     */
    CursorPageResponse<UserListItemResponse> getBlockedUsers(
            UUID currentUserId, String cursor, int limit);

    /**
     * Cursor-paginated pending follow requests targeting the current user, newest first.
     *
     * <p>A request from a soft-deleted or unknown account resolves to a placeholder rather than
     * being dropped, so the page size stays consistent with the row count.
     *
     * @param currentUserId authenticated user whose pending requests are listed
     * @param cursor opaque base64 cursor from the previous page; null or blank for the first page
     * @param limit requested page size, normalized to 1–100 with a default of 20
     * @return cursor page of pending follow requests
     */
    CursorPageResponse<FollowRequestResponse> getPendingFollowRequests(
            UUID currentUserId, String cursor, int limit);

    /**
     * Returns the IDs of users the viewer currently follows with accepted status, excluding any
     * user involved in a block relationship with the viewer in either direction.
     *
     * <p>Pending follow requests do not count as accepted follows and are excluded. A user is
     * suppressed from the result when either the viewer has blocked them or they have blocked the
     * viewer; the block table stores a single directional row but the practical effect is
     * bidirectional. An empty result means the viewer has no valid feed authors; callers should
     * short-circuit and return an empty page without querying posts.
     *
     * @param viewerId authenticated viewer requesting their feed
     * @return list of user IDs eligible to appear as post authors in the viewer's feed
     */
    List<UUID> getAcceptedFollowingExcludingBlocks(UUID viewerId);

    /**
     * Checks whether an accepted follow relationship exists from the follower to the target user.
     *
     * <p>Pending follow requests do not count as accepted follows; counters and content visibility
     * are gated on accepted status only.
     *
     * @param followerId user performing the follow
     * @param followingId user being followed
     * @return true when a follow row with accepted status exists
     */
    boolean hasAcceptedFollow(UUID followerId, UUID followingId);

    /**
     * Checks whether a block relationship exists between two users in either direction.
     *
     * <p>Blocks are bidirectional in effect: a single row in either direction suppresses all
     * interaction and content visibility between the pair.
     *
     * @param userIdA first user of the pair
     * @param userIdB second user of the pair
     * @return true when either user has blocked the other
     */
    boolean isBlockedBetween(UUID userIdA, UUID userIdB);

    /**
     * Returns the current status of the follow edge from {@code followerId} to {@code followingId}.
     *
     * <p>Lets an asynchronous consumer act on the relationship as it stands when the event is
     * processed rather than as the event described it, so a redelivered or reordered follow,
     * unfollow or approval event cannot leave a notification describing a relationship that no
     * longer exists.
     *
     * @param followerId the following user
     * @param followingId the followed user
     * @return the edge's status, or empty when no edge exists
     */
    Optional<FollowStatus> findFollowStatus(UUID followerId, UUID followingId);

    /**
     * Resolves the viewer's follow and block relationship to each of {@code userIds} in two batched
     * queries, one for follows and one for blocks, each covering both directions in a single round
     * trip.
     *
     * @param viewerId the requesting viewer; a null viewer (anonymous) short-circuits to an empty
     *     map without querying
     * @param userIds candidate user ids; may contain duplicates
     * @return a map from each distinct id to its relationship; an id absent from the map (or when
     *     {@code viewerId} is null) has no relationship and must default to {@link
     *     ViewerRelationshipResponse#NONE}
     */
    Map<UUID, ViewerRelationshipResponse> loadRelationships(
            UUID viewerId, Collection<UUID> userIds);

    /**
     * Every user id blocking or blocked by the viewer, in either direction, in one query.
     *
     * <p>For internal visibility decisions only - never render this on a response surface. The
     * stealth block model requires that a blocked viewer cannot distinguish "this account does not
     * exist" from "this account has blocked me"; {@link #loadRelationships} therefore deliberately
     * omits the incoming-block direction from what it exposes. This method restores that direction
     * for callers that need the full bidirectional truth to decide whether content is visible at
     * all, as opposed to deciding what to display about a relationship that is already visible.
     *
     * @param viewerId the user whose block relationships are resolved
     * @return every id blocking or blocked by {@code viewerId}; empty when there are none
     */
    Set<UUID> findBlockedEitherDirection(UUID viewerId);
}
