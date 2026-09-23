package com.app.modules.social.service.impl;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.common.pagination.Cursor;
import com.app.common.pagination.CursorCodec;
import com.app.common.pagination.CursorScope;
import com.app.common.pagination.TimeCursors;
import com.app.common.response.CursorPageResponse;
import com.app.common.response.UserListItemResponse;
import com.app.common.response.UserSummaryResponse;
import com.app.common.response.ViewerRelationshipResponse;
import com.app.modules.message.service.DirectConversationProvisioner;
import com.app.modules.report.enums.ReportType;
import com.app.modules.report.service.ReportedTargetService;
import com.app.modules.social.dto.response.FollowRequestResponse;
import com.app.modules.social.dto.response.FollowResponse;
import com.app.modules.social.entity.Block;
import com.app.modules.social.entity.BlockId;
import com.app.modules.social.entity.Follow;
import com.app.modules.social.entity.FollowId;
import com.app.modules.social.enums.FollowStatus;
import com.app.modules.social.repository.BlockRepository;
import com.app.modules.social.repository.FollowEdgeProjection;
import com.app.modules.social.repository.FollowRepository;
import com.app.modules.social.repository.SocialUserRepository;
import com.app.modules.social.service.SocialEventService;
import com.app.modules.social.service.SocialService;
import com.app.modules.users.entity.User;
import com.app.modules.users.service.UserSummaryService;

@Service
public class SocialServiceImpl implements SocialService {

    private static final String UNIQUE_VIOLATION_SQLSTATE = "23505";

    private final FollowRepository followRepository;
    private final BlockRepository blockRepository;
    private final SocialUserRepository socialUserRepository;
    private final SocialEventService socialEventService;
    private final UserSummaryService userSummaryService;
    private final ReportedTargetService reportedTargetService;
    private final DirectConversationProvisioner directConversationProvisioner;

    public SocialServiceImpl(
            FollowRepository followRepository,
            BlockRepository blockRepository,
            SocialUserRepository socialUserRepository,
            SocialEventService socialEventService,
            UserSummaryService userSummaryService,
            ReportedTargetService reportedTargetService,
            DirectConversationProvisioner directConversationProvisioner) {
        this.directConversationProvisioner = directConversationProvisioner;
        this.followRepository = followRepository;
        this.blockRepository = blockRepository;
        this.socialUserRepository = socialUserRepository;
        this.socialEventService = socialEventService;
        this.userSummaryService = userSummaryService;
        this.reportedTargetService = reportedTargetService;
    }

    @Override
    @Transactional
    public FollowResponse followUser(UUID currentUserId, UUID targetUserId) {
        if (currentUserId.equals(targetUserId)) {
            throw new AppException(ApiErrorCode.SOCIAL_SELF_FOLLOW);
        }

        User targetUser =
                socialUserRepository
                        .findByIdAndDeletedAtIsNull(targetUserId)
                        .orElseThrow(
                                () ->
                                        new AppException(
                                                ApiErrorCode.NOT_FOUND, "Target user not found"));

        // Stealth block model: a block in either direction must be indistinguishable from the
        // target not existing, so this collapses onto the same NOT_FOUND the nonexistent-target
        // check above throws, not a status that confirms a block relationship exists.
        if (blockRepository.existsById(new BlockId(currentUserId, targetUserId))
                || blockRepository.existsById(new BlockId(targetUserId, currentUserId))) {
            throw new AppException(ApiErrorCode.NOT_FOUND, "Target user not found");
        }

        FollowId followId = new FollowId(currentUserId, targetUserId);

        if (followRepository.existsById(followId)) {
            Follow existingFollow = followRepository.findById(followId).orElseThrow();

            if (existingFollow.getStatus() == FollowStatus.ACCEPTED) {
                throw new AppException(ApiErrorCode.SOCIAL_ALREADY_FOLLOWING);
            }

            throw new AppException(ApiErrorCode.SOCIAL_ALREADY_REQUESTED);
        }

        FollowStatus status = targetUser.isPrivate() ? FollowStatus.PENDING : FollowStatus.ACCEPTED;

        Follow follow;
        try {
            // Single-statement INSERT ... RETURNING: the composite primary key rejects a
            // concurrent duplicate atomically, unlike save()/merge which silently no-ops on an
            // already-committed row and would re-publish the follow event.
            follow = followRepository.insert(currentUserId, targetUserId, status);
        } catch (DataIntegrityViolationException ex) {
            if (isUniqueViolation(ex)) {
                throw new AppException(
                        status == FollowStatus.PENDING
                                ? ApiErrorCode.SOCIAL_ALREADY_REQUESTED
                                : ApiErrorCode.SOCIAL_ALREADY_FOLLOWING);
            }
            throw ex;
        }

        socialEventService.publishFollowCreated(follow);

        // A conversation is the product of a relationship, so it is created the moment the second
        // edge lands rather than waiting for someone to type. Inside this transaction, so the
        // follow and the conversation commit together or neither does.
        if (status == FollowStatus.ACCEPTED
                && followRepository.existsByIdAndStatus(
                        new FollowId(targetUserId, currentUserId), FollowStatus.ACCEPTED)) {
            directConversationProvisioner.ensureDirectConversation(currentUserId, targetUserId);
        }

        return new FollowResponse(currentUserId, targetUserId, status, follow.getCreatedAt());
    }

    private static boolean isUniqueViolation(DataIntegrityViolationException ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof SQLException sqlException) {
                return UNIQUE_VIOLATION_SQLSTATE.equals(sqlException.getSQLState());
            }
            current = current.getCause();
        }
        return false;
    }

    @Override
    @Transactional
    public void unfollowUser(UUID currentUserId, UUID targetUserId) {
        if (!socialUserRepository.existsByIdAndDeletedAtIsNull(targetUserId)) {
            throw new AppException(ApiErrorCode.NOT_FOUND, "Target user not found");
        }

        // The status is read first because the event names it: an unfollow retracts a follow
        // notification, a cancelled request withdraws the request.
        FollowStatus previousStatus =
                followRepository
                        .findById(new FollowId(currentUserId, targetUserId))
                        .map(Follow::getStatus)
                        .orElseThrow(
                                () ->
                                        new AppException(
                                                ApiErrorCode.NOT_FOUND,
                                                "Follow relationship not found"));

        // Conditional delete rather than load-then-delete(entity): the latter raises
        // ObjectOptimisticLockingFailureException (-> 500) when a concurrent duplicate request
        // already removed the same row, since Hibernate's entity-based DELETE always checks the
        // affected-row count. Branching on the returned count here instead makes the loser of the
        // race a clean 404, not a 500.
        int deleted =
                followRepository.deleteByFollowerIdAndFollowingIdAndStatus(
                        currentUserId, targetUserId, previousStatus);
        if (deleted == 0) {
            throw new AppException(ApiErrorCode.NOT_FOUND, "Follow relationship not found");
        }
        socialEventService.publishUnfollowed(currentUserId, targetUserId, previousStatus);

        // Only an empty conversation goes. One carrying messages is history, and history is not a
        // side effect of a follow button.
        directConversationProvisioner.discardEmptyDirectConversation(currentUserId, targetUserId);
    }

    @Override
    @Transactional
    public void respondToFollowRequest(UUID currentUserId, UUID requesterId, String action) {
        if (!socialUserRepository.existsByIdAndDeletedAtIsNull(requesterId)) {
            throw new AppException(ApiErrorCode.NOT_FOUND, "Requester not found");
        }

        if (!socialUserRepository.existsByIdAndDeletedAtIsNull(currentUserId)) {
            throw new AppException(ApiErrorCode.NOT_FOUND, "Current user not found");
        }

        // Conditional delete rather than load-then-delete(entity): the latter raises
        // ObjectOptimisticLockingFailureException (-> 500) when a concurrent duplicate request
        // already resolved the same pending row. Checked before the shared load below so a reject
        // never loads the entity it is only going to delete.
        if ("reject".equalsIgnoreCase(action)) {
            int deleted =
                    followRepository.deleteByFollowerIdAndFollowingIdAndStatus(
                            requesterId, currentUserId, FollowStatus.PENDING);
            if (deleted == 0) {
                throw new AppException(ApiErrorCode.SOCIAL_REQUEST_NOT_FOUND);
            }
            socialEventService.publishFollowRequestResolved(requesterId, currentUserId, false);
            return;
        }

        FollowId followId = new FollowId(requesterId, currentUserId);

        Follow follow =
                followRepository
                        .findByIdAndStatus(followId, FollowStatus.PENDING)
                        .orElseThrow(() -> new AppException(ApiErrorCode.SOCIAL_REQUEST_NOT_FOUND));

        if ("approve".equalsIgnoreCase(action)) {
            follow.setStatus(FollowStatus.ACCEPTED);
            followRepository.save(follow);
            socialEventService.publishFollowRequestResolved(requesterId, currentUserId, true);

            // The approved edge runs requester -> approver. The pair is mutual only if the
            // approver already follows the requester back.
            if (followRepository.existsByIdAndStatus(
                    new FollowId(currentUserId, requesterId), FollowStatus.ACCEPTED)) {
                directConversationProvisioner.ensureDirectConversation(currentUserId, requesterId);
            }
            return;
        }

        throw new AppException(ApiErrorCode.BAD_REQUEST, "Invalid follow request response action");
    }

    @Override
    @Transactional
    public void blockUser(UUID currentUserId, UUID targetUserId) {
        if (currentUserId.equals(targetUserId)) {
            throw new AppException(ApiErrorCode.SOCIAL_SELF_BLOCK);
        }

        if (!socialUserRepository.existsByIdAndDeletedAtIsNull(targetUserId)) {
            throw new AppException(ApiErrorCode.NOT_FOUND, "Target user not found");
        }

        BlockId blockId = new BlockId(currentUserId, targetUserId);

        if (blockRepository.existsById(blockId)) {
            throw new AppException(ApiErrorCode.SOCIAL_ALREADY_BLOCKED);
        }

        Block block = Block.builder().id(blockId).build();
        blockRepository.save(block);
        socialEventService.publishBlocked(currentUserId, targetUserId);

        FollowId followIdDirect = new FollowId(currentUserId, targetUserId);
        followRepository.findById(followIdDirect).ifPresent(followRepository::delete);

        FollowId followIdReverse = new FollowId(targetUserId, currentUserId);
        followRepository.findById(followIdReverse).ifPresent(followRepository::delete);

        // Blocking destroys both follow edges above, so the pair is no longer mutual. An empty
        // conversation provisioned by that relationship goes with it; one with messages stays and
        // is hidden by the block filtering on the conversation list instead.
        directConversationProvisioner.discardEmptyDirectConversation(currentUserId, targetUserId);
    }

    @Override
    @Transactional
    public void unblockUser(UUID currentUserId, UUID targetUserId) {
        // Conditional delete rather than load-then-delete(entity): the latter raises
        // ObjectOptimisticLockingFailureException (-> 500) when a concurrent duplicate request
        // already removed the same row.
        int deleted = blockRepository.deleteByBlockerIdAndBlockedId(currentUserId, targetUserId);
        if (deleted == 0) {
            throw new AppException(ApiErrorCode.NOT_FOUND, "Block relationship not found");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public CursorPageResponse<UserListItemResponse> getFollowers(
            UUID targetUserId, UUID currentUserId, String cursor, int limit) {

        User targetUser =
                socialUserRepository
                        .findByIdAndDeletedAtIsNull(targetUserId)
                        .orElseThrow(
                                () -> new AppException(ApiErrorCode.NOT_FOUND, "User not found"));

        checkCanViewSocialGraph(currentUserId, targetUserId, targetUser);

        int size = normalizeLimit(limit);
        Cursor decoded = decodeCursor(cursor, CursorScope.SOCIAL_FOLLOWERS);

        Pageable pageable = PageRequest.of(0, size + 1);

        List<Follow> follows =
                decoded == null
                        ? followRepository.findFirstFollowers(targetUserId, currentUserId, pageable)
                        : followRepository.findFollowersBefore(
                                targetUserId,
                                currentUserId,
                                TimeCursors.fromMicros(decoded.sortValueMicros()),
                                decoded.id(),
                                pageable);

        boolean hasNextPage = follows.size() > size;

        if (hasNextPage) {
            follows = follows.subList(0, size);
        }

        if (follows.isEmpty()) {
            return CursorPageResponse.of(
                    Collections.emptyList(), false, null, null, cursor != null);
        }

        List<UUID> followerIds = follows.stream().map(f -> f.getId().getFollowerId()).toList();

        Map<UUID, User> userMap =
                socialUserRepository.findAllByIdInAndDeletedAtIsNull(followerIds).stream()
                        .collect(Collectors.toMap(User::getId, user -> user));
        Map<UUID, ViewerRelationshipResponse> relationships =
                loadRelationships(currentUserId, followerIds);

        List<UserListItemResponse> content =
                follows.stream()
                        .map(f -> userMap.get(f.getId().getFollowerId()))
                        .filter(user -> user != null)
                        .map(user -> toUserListItemResponse(user, relationships))
                        .toList();

        Follow firstFollow = follows.get(0);
        Follow lastFollow = follows.get(follows.size() - 1);
        String startCursor =
                encodeCursor(
                        firstFollow.getCreatedAt(),
                        firstFollow.getId().getFollowerId(),
                        CursorScope.SOCIAL_FOLLOWERS);
        String endCursor =
                encodeCursor(
                        lastFollow.getCreatedAt(),
                        lastFollow.getId().getFollowerId(),
                        CursorScope.SOCIAL_FOLLOWERS);

        return CursorPageResponse.of(content, hasNextPage, startCursor, endCursor, cursor != null);
    }

    @Override
    @Transactional(readOnly = true)
    public CursorPageResponse<UserListItemResponse> getFollowing(
            UUID targetUserId, UUID currentUserId, String cursor, int limit) {

        User targetUser =
                socialUserRepository
                        .findByIdAndDeletedAtIsNull(targetUserId)
                        .orElseThrow(
                                () -> new AppException(ApiErrorCode.NOT_FOUND, "User not found"));

        checkCanViewSocialGraph(currentUserId, targetUserId, targetUser);

        int size = normalizeLimit(limit);
        Cursor decoded = decodeCursor(cursor, CursorScope.SOCIAL_FOLLOWING);

        Pageable pageable = PageRequest.of(0, size + 1);

        List<Follow> follows =
                decoded == null
                        ? followRepository.findFirstFollowing(targetUserId, currentUserId, pageable)
                        : followRepository.findFollowingBefore(
                                targetUserId,
                                currentUserId,
                                TimeCursors.fromMicros(decoded.sortValueMicros()),
                                decoded.id(),
                                pageable);

        boolean hasNextPage = follows.size() > size;

        if (hasNextPage) {
            follows = follows.subList(0, size);
        }

        if (follows.isEmpty()) {
            return CursorPageResponse.of(
                    Collections.emptyList(), false, null, null, cursor != null);
        }

        List<UUID> followingIds = follows.stream().map(f -> f.getId().getFollowingId()).toList();

        Map<UUID, User> userMap =
                socialUserRepository.findAllByIdInAndDeletedAtIsNull(followingIds).stream()
                        .collect(Collectors.toMap(User::getId, user -> user));
        Map<UUID, ViewerRelationshipResponse> relationships =
                loadRelationships(currentUserId, followingIds);

        List<UserListItemResponse> content =
                follows.stream()
                        .map(f -> userMap.get(f.getId().getFollowingId()))
                        .filter(user -> user != null)
                        .map(user -> toUserListItemResponse(user, relationships))
                        .toList();

        Follow firstFollow = follows.get(0);
        Follow lastFollow = follows.get(follows.size() - 1);
        String startCursor =
                encodeCursor(
                        firstFollow.getCreatedAt(),
                        firstFollow.getId().getFollowingId(),
                        CursorScope.SOCIAL_FOLLOWING);
        String endCursor =
                encodeCursor(
                        lastFollow.getCreatedAt(),
                        lastFollow.getId().getFollowingId(),
                        CursorScope.SOCIAL_FOLLOWING);

        return CursorPageResponse.of(content, hasNextPage, startCursor, endCursor, cursor != null);
    }

    @Override
    @Transactional(readOnly = true)
    public CursorPageResponse<UserListItemResponse> getBlockedUsers(
            UUID currentUserId, String cursor, int limit) {
        int size = normalizeLimit(limit);
        Cursor decoded = decodeCursor(cursor, CursorScope.SOCIAL_BLOCKED);
        Pageable pageable = PageRequest.of(0, size + 1);

        List<Block> blocks =
                decoded == null
                        ? blockRepository.findFirstBlocked(currentUserId, pageable)
                        : blockRepository.findBlockedBefore(
                                currentUserId,
                                TimeCursors.fromMicros(decoded.sortValueMicros()),
                                decoded.id(),
                                pageable);

        boolean hasNextPage = blocks.size() > size;
        if (hasNextPage) {
            blocks = blocks.subList(0, size);
        }

        if (blocks.isEmpty()) {
            return CursorPageResponse.of(
                    Collections.emptyList(), false, null, null, cursor != null);
        }

        List<UUID> blockedIds = blocks.stream().map(b -> b.getId().getBlockedId()).toList();

        // Placeholder rather than drop, so the page length matches the row count even when a
        // blocked account has since been soft-deleted.
        Map<UUID, UserSummaryResponse> summaries = userSummaryService.loadSummaries(blockedIds);
        Map<UUID, ViewerRelationshipResponse> relationships =
                loadRelationships(currentUserId, blockedIds);

        List<UserListItemResponse> content =
                blockedIds.stream()
                        .map(
                                id ->
                                        new UserListItemResponse(
                                                summaries.get(id),
                                                relationships.getOrDefault(
                                                        id, ViewerRelationshipResponse.NONE)))
                        .toList();

        Block first = blocks.get(0);
        Block last = blocks.get(blocks.size() - 1);
        String startCursor =
                encodeCursor(
                        first.getCreatedAt(),
                        first.getId().getBlockedId(),
                        CursorScope.SOCIAL_BLOCKED);
        String endCursor =
                encodeCursor(
                        last.getCreatedAt(),
                        last.getId().getBlockedId(),
                        CursorScope.SOCIAL_BLOCKED);

        return CursorPageResponse.of(content, hasNextPage, startCursor, endCursor, cursor != null);
    }

    @Override
    @Transactional(readOnly = true)
    public CursorPageResponse<FollowRequestResponse> getPendingFollowRequests(
            UUID currentUserId, String cursor, int limit) {
        int size = normalizeLimit(limit);
        Cursor decoded = decodeCursor(cursor, CursorScope.SOCIAL_PENDING_REQUESTS);
        Pageable pageable = PageRequest.of(0, size + 1);

        List<Follow> pendingFollows =
                decoded == null
                        ? followRepository.findFirstPendingRequests(currentUserId, pageable)
                        : followRepository.findPendingRequestsBefore(
                                currentUserId,
                                TimeCursors.fromMicros(decoded.sortValueMicros()),
                                decoded.id(),
                                pageable);

        boolean hasNextPage = pendingFollows.size() > size;
        if (hasNextPage) {
            pendingFollows = pendingFollows.subList(0, size);
        }

        if (pendingFollows.isEmpty()) {
            return CursorPageResponse.of(
                    Collections.emptyList(), false, null, null, cursor != null);
        }

        List<UUID> requesterIds =
                pendingFollows.stream().map(f -> f.getId().getFollowerId()).toList();

        // Batch-resolve every requester; a soft-deleted or unknown requester resolves to a
        // placeholder rather than being dropped, so the page size stays consistent with the row
        // count.
        Map<UUID, UserSummaryResponse> summaries = userSummaryService.loadSummaries(requesterIds);
        Map<UUID, ViewerRelationshipResponse> relationships =
                loadRelationships(currentUserId, requesterIds);

        List<FollowRequestResponse> content =
                pendingFollows.stream()
                        .map(
                                follow -> {
                                    UUID requesterId = follow.getId().getFollowerId();
                                    return new FollowRequestResponse(
                                            requesterId,
                                            summaries.get(requesterId),
                                            follow.getStatus(),
                                            follow.getCreatedAt(),
                                            relationships.getOrDefault(
                                                    requesterId, ViewerRelationshipResponse.NONE));
                                })
                        .toList();

        Follow first = pendingFollows.get(0);
        Follow last = pendingFollows.get(pendingFollows.size() - 1);
        String startCursor =
                encodeCursor(
                        first.getCreatedAt(),
                        first.getId().getFollowerId(),
                        CursorScope.SOCIAL_PENDING_REQUESTS);
        String endCursor =
                encodeCursor(
                        last.getCreatedAt(),
                        last.getId().getFollowerId(),
                        CursorScope.SOCIAL_PENDING_REQUESTS);

        return CursorPageResponse.of(content, hasNextPage, startCursor, endCursor, cursor != null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> getAcceptedFollowingExcludingBlocks(UUID viewerId) {
        List<Follow> accepted =
                followRepository.findByIdFollowerIdAndStatus(viewerId, FollowStatus.ACCEPTED);
        if (accepted.isEmpty()) {
            return Collections.emptyList();
        }
        Set<UUID> blockedOrBlocking = new HashSet<>();
        blockRepository
                .findByIdBlockerId(viewerId)
                .forEach(b -> blockedOrBlocking.add(b.getId().getBlockedId()));
        blockRepository
                .findByIdBlockedId(viewerId)
                .forEach(b -> blockedOrBlocking.add(b.getId().getBlockerId()));
        return accepted.stream()
                .map(f -> f.getId().getFollowingId())
                .filter(id -> !blockedOrBlocking.contains(id))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasAcceptedFollow(UUID followerId, UUID followingId) {
        return followRepository.existsByIdAndStatus(
                new FollowId(followerId, followingId), FollowStatus.ACCEPTED);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<FollowStatus> findFollowStatus(UUID followerId, UUID followingId) {
        return followRepository
                .findById(new FollowId(followerId, followingId))
                .map(Follow::getStatus);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isBlockedBetween(UUID userIdA, UUID userIdB) {
        return blockRepository.existsBetween(userIdA, userIdB);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, ViewerRelationshipResponse> loadRelationships(
            UUID viewerId, Collection<UUID> userIds) {
        if (viewerId == null || userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        Set<UUID> distinct = new LinkedHashSet<>(userIds);
        Map<UUID, Boolean> following = new HashMap<>();
        Map<UUID, Boolean> requested = new HashMap<>();
        Map<UUID, Boolean> followedBy = new HashMap<>();
        for (FollowEdgeProjection edge :
                followRepository.findRelationshipEdges(viewerId, distinct)) {
            boolean accepted = FollowStatus.ACCEPTED.toJson().equalsIgnoreCase(edge.getStatus());
            if (edge.getOutgoing()) {
                following.put(edge.getOtherId(), accepted);
                requested.put(edge.getOtherId(), !accepted);
            } else {
                followedBy.put(edge.getOtherId(), accepted);
            }
        }
        // Outgoing-only: no response surface renders "this user has blocked the viewer" under the
        // stealth block model, so the incoming direction has no caller.
        Set<UUID> blocking =
                new HashSet<>(blockRepository.findOutgoingBlockedIds(viewerId, distinct));
        Set<UUID> reported =
                reportedTargetService.loadReportedEntityIds(viewerId, ReportType.USER, distinct);
        Map<UUID, ViewerRelationshipResponse> result = new HashMap<>(distinct.size());
        for (UUID id : distinct) {
            result.put(
                    id,
                    new ViewerRelationshipResponse(
                            following.getOrDefault(id, false),
                            requested.getOrDefault(id, false),
                            followedBy.getOrDefault(id, false),
                            blocking.contains(id),
                            reported.contains(id)));
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> findBlockedEitherDirection(UUID viewerId) {
        return new HashSet<>(blockRepository.findBlockedCounterpartyIds(viewerId));
    }

    private void checkCanViewSocialGraph(UUID currentUserId, UUID targetUserId, User targetUser) {
        // Stealth block model: matches assemblePublicProfile's reference behaviour exactly - a
        // block in either direction must be indistinguishable from targetUserId not existing.
        // The private-account branch below is a separate, legitimate disclosure and is untouched:
        // private accounts are visibly private on real platforms.
        if (blockRepository.existsById(new BlockId(currentUserId, targetUserId))
                || blockRepository.existsById(new BlockId(targetUserId, currentUserId))) {
            throw new AppException(ApiErrorCode.NOT_FOUND, "User not found");
        }

        boolean isOwner = currentUserId.equals(targetUserId);

        boolean isAcceptedFollower =
                followRepository.existsByIdAndStatus(
                        new FollowId(currentUserId, targetUserId), FollowStatus.ACCEPTED);

        if (targetUser.isPrivate() && !isOwner && !isAcceptedFollower) {
            throw new AppException(ApiErrorCode.FORBIDDEN, "You cannot view this private account");
        }
    }

    private UserSummaryResponse toUserSummaryResponse(User user) {
        return new UserSummaryResponse(
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                user.getAvatarUrl(),
                user.isVerified(),
                user.getVerifiedCategory());
    }

    private UserListItemResponse toUserListItemResponse(
            User user, Map<UUID, ViewerRelationshipResponse> relationships) {
        return new UserListItemResponse(
                toUserSummaryResponse(user),
                relationships.getOrDefault(user.getId(), ViewerRelationshipResponse.NONE));
    }

    private int normalizeLimit(int limit) {
        return limit > 100 ? 100 : (limit < 1 ? 20 : limit);
    }

    private String encodeCursor(OffsetDateTime time, UUID tiebreaker, String scope) {
        if (time == null || tiebreaker == null) {
            return null;
        }
        return CursorCodec.encode(new Cursor(TimeCursors.toMicros(time), tiebreaker), scope);
    }

    private Cursor decodeCursor(String cursor, String scope) {
        return CursorCodec.decode(cursor, scope);
    }
}
