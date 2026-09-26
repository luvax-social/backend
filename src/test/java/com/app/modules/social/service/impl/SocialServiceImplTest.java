package com.app.modules.social.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.common.response.CursorPageResponse;
import com.app.common.response.UserListItemResponse;
import com.app.common.response.UserSummaryResponse;
import com.app.modules.message.service.DirectConversationProvisioner;
import com.app.modules.report.service.ReportedTargetService;
import com.app.modules.social.dto.response.FollowRequestResponse;
import com.app.modules.social.dto.response.FollowResponse;
import com.app.modules.social.entity.Block;
import com.app.modules.social.entity.BlockId;
import com.app.modules.social.entity.Follow;
import com.app.modules.social.entity.FollowId;
import com.app.modules.social.enums.FollowStatus;
import com.app.modules.social.repository.BlockRepository;
import com.app.modules.social.repository.FollowRepository;
import com.app.modules.social.repository.SocialUserRepository;
import com.app.modules.social.service.SocialEventService;
import com.app.modules.users.entity.User;
import com.app.modules.users.service.UserSummaryService;

@ExtendWith(MockitoExtension.class)
class SocialServiceImplTest {

    @Mock private FollowRepository followRepository;
    @Mock private BlockRepository blockRepository;
    @Mock private SocialUserRepository socialUserRepository;
    @Mock private SocialEventService socialEventService;
    @Mock private UserSummaryService userSummaryService;
    @Mock private ReportedTargetService reportedTargetService;
    @Mock private DirectConversationProvisioner directConversationProvisioner;

    private SocialServiceImpl service;

    @BeforeEach
    void setUp() {
        service =
                new SocialServiceImpl(
                        followRepository,
                        blockRepository,
                        socialUserRepository,
                        socialEventService,
                        userSummaryService,
                        reportedTargetService,
                        directConversationProvisioner);
    }

    @Test
    void followUser_self_throwsSelfFollow() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> service.followUser(id, id))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.SOCIAL_SELF_FOLLOW);

        verify(socialEventService, never()).publishFollowCreated(any());
    }

    @Test
    void followUser_targetMissing_throwsNotFound() {
        UUID follower = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        when(socialUserRepository.findByIdAndDeletedAtIsNull(target)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.followUser(follower, target))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.NOT_FOUND);
    }

    @Test
    void followUser_blockExists_throwsNotFound() {
        // Stealth block model: a blocked target must be indistinguishable from a nonexistent one.
        UUID follower = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        when(socialUserRepository.findByIdAndDeletedAtIsNull(target))
                .thenReturn(Optional.of(user(target, false)));
        when(blockRepository.existsById(new BlockId(follower, target))).thenReturn(true);

        assertThatThrownBy(() -> service.followUser(follower, target))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.NOT_FOUND);

        verify(followRepository, never()).insert(any(), any(), any());
    }

    @Test
    void followUser_alreadyAccepted_throwsAlreadyFollowing() {
        UUID follower = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        FollowId followId = new FollowId(follower, target);
        when(socialUserRepository.findByIdAndDeletedAtIsNull(target))
                .thenReturn(Optional.of(user(target, false)));
        when(blockRepository.existsById(any())).thenReturn(false);
        when(followRepository.existsById(followId)).thenReturn(true);
        when(followRepository.findById(followId))
                .thenReturn(Optional.of(follow(follower, target, FollowStatus.ACCEPTED)));

        assertThatThrownBy(() -> service.followUser(follower, target))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.SOCIAL_ALREADY_FOLLOWING);
    }

    @Test
    void followUser_alreadyPending_throwsAlreadyRequested() {
        UUID follower = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        FollowId followId = new FollowId(follower, target);
        when(socialUserRepository.findByIdAndDeletedAtIsNull(target))
                .thenReturn(Optional.of(user(target, true)));
        when(blockRepository.existsById(any())).thenReturn(false);
        when(followRepository.existsById(followId)).thenReturn(true);
        when(followRepository.findById(followId))
                .thenReturn(Optional.of(follow(follower, target, FollowStatus.PENDING)));

        assertThatThrownBy(() -> service.followUser(follower, target))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.SOCIAL_ALREADY_REQUESTED);
    }

    @Test
    void followUser_publicTarget_insertsAcceptedAndPublishes() {
        UUID follower = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        when(socialUserRepository.findByIdAndDeletedAtIsNull(target))
                .thenReturn(Optional.of(user(target, false)));
        when(blockRepository.existsById(any())).thenReturn(false);
        when(followRepository.existsById(any())).thenReturn(false);
        when(followRepository.insert(follower, target, FollowStatus.ACCEPTED))
                .thenReturn(follow(follower, target, FollowStatus.ACCEPTED));

        FollowResponse response = service.followUser(follower, target);

        assertThat(response.status()).isEqualTo(FollowStatus.ACCEPTED);
        verify(followRepository).insert(follower, target, FollowStatus.ACCEPTED);
        verify(socialEventService).publishFollowCreated(any(Follow.class));
    }

    @Test
    void followUser_privateTarget_insertsPending() {
        UUID follower = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        when(socialUserRepository.findByIdAndDeletedAtIsNull(target))
                .thenReturn(Optional.of(user(target, true)));
        when(blockRepository.existsById(any())).thenReturn(false);
        when(followRepository.existsById(any())).thenReturn(false);
        when(followRepository.insert(follower, target, FollowStatus.PENDING))
                .thenReturn(follow(follower, target, FollowStatus.PENDING));

        FollowResponse response = service.followUser(follower, target);

        assertThat(response.status()).isEqualTo(FollowStatus.PENDING);
    }

    @Test
    void followUser_concurrentDuplicateInsert_publicTarget_throwsAlreadyFollowing() {
        UUID follower = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        when(socialUserRepository.findByIdAndDeletedAtIsNull(target))
                .thenReturn(Optional.of(user(target, false)));
        when(blockRepository.existsById(any())).thenReturn(false);
        when(followRepository.existsById(any())).thenReturn(false);
        when(followRepository.insert(follower, target, FollowStatus.ACCEPTED))
                .thenThrow(uniqueViolation());

        assertThatThrownBy(() -> service.followUser(follower, target))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.SOCIAL_ALREADY_FOLLOWING);

        verify(socialEventService, never()).publishFollowCreated(any());
    }

    @Test
    void followUser_concurrentDuplicateInsert_privateTarget_throwsAlreadyRequested() {
        UUID follower = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        when(socialUserRepository.findByIdAndDeletedAtIsNull(target))
                .thenReturn(Optional.of(user(target, true)));
        when(blockRepository.existsById(any())).thenReturn(false);
        when(followRepository.existsById(any())).thenReturn(false);
        when(followRepository.insert(follower, target, FollowStatus.PENDING))
                .thenThrow(uniqueViolation());

        assertThatThrownBy(() -> service.followUser(follower, target))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.SOCIAL_ALREADY_REQUESTED);

        verify(socialEventService, never()).publishFollowCreated(any());
    }

    @Test
    void followUser_nonUniqueIntegrityViolation_isRethrown() {
        UUID follower = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        when(socialUserRepository.findByIdAndDeletedAtIsNull(target))
                .thenReturn(Optional.of(user(target, false)));
        when(blockRepository.existsById(any())).thenReturn(false);
        when(followRepository.existsById(any())).thenReturn(false);
        DataIntegrityViolationException fkViolation =
                new DataIntegrityViolationException(
                        "fk", new SQLException("violates foreign key constraint", "23503"));
        when(followRepository.insert(follower, target, FollowStatus.ACCEPTED))
                .thenThrow(fkViolation);

        assertThatThrownBy(() -> service.followUser(follower, target)).isSameAs(fkViolation);

        verify(socialEventService, never()).publishFollowCreated(any());
    }

    @Test
    void unfollowUser_targetMissing_throwsNotFound() {
        UUID follower = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        when(socialUserRepository.existsByIdAndDeletedAtIsNull(target)).thenReturn(false);

        assertThatThrownBy(() -> service.unfollowUser(follower, target))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.NOT_FOUND);
    }

    @Test
    void unfollowUser_noRelationship_throwsNotFound() {
        UUID follower = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        when(socialUserRepository.existsByIdAndDeletedAtIsNull(target)).thenReturn(true);
        when(followRepository.findById(new FollowId(follower, target)))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.unfollowUser(follower, target))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.NOT_FOUND);
    }

    @Test
    void unfollowUser_existing_deletesAndPublishesTheStatusItHad() {
        UUID follower = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        when(socialUserRepository.existsByIdAndDeletedAtIsNull(target)).thenReturn(true);
        when(followRepository.findById(new FollowId(follower, target)))
                .thenReturn(Optional.of(follow(follower, target, FollowStatus.ACCEPTED)));
        when(followRepository.deleteByFollowerIdAndFollowingIdAndStatus(
                        follower, target, FollowStatus.ACCEPTED))
                .thenReturn(1);

        service.unfollowUser(follower, target);

        verify(socialEventService).publishUnfollowed(follower, target, FollowStatus.ACCEPTED);
    }

    @Test
    void unfollowUser_pendingRequest_publishesACancelledRequest() {
        UUID follower = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        when(socialUserRepository.existsByIdAndDeletedAtIsNull(target)).thenReturn(true);
        when(followRepository.findById(new FollowId(follower, target)))
                .thenReturn(Optional.of(follow(follower, target, FollowStatus.PENDING)));
        when(followRepository.deleteByFollowerIdAndFollowingIdAndStatus(
                        follower, target, FollowStatus.PENDING))
                .thenReturn(1);

        service.unfollowUser(follower, target);

        verify(socialEventService).publishUnfollowed(follower, target, FollowStatus.PENDING);
    }

    @Test
    void unfollowUser_concurrentDuplicate_throwsNotFoundAndPublishesNothing() {
        UUID follower = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        when(socialUserRepository.existsByIdAndDeletedAtIsNull(target)).thenReturn(true);
        when(followRepository.findById(new FollowId(follower, target)))
                .thenReturn(Optional.of(follow(follower, target, FollowStatus.ACCEPTED)));
        when(followRepository.deleteByFollowerIdAndFollowingIdAndStatus(
                        follower, target, FollowStatus.ACCEPTED))
                .thenReturn(0);

        assertThatThrownBy(() -> service.unfollowUser(follower, target))
                .isInstanceOf(AppException.class);
        verify(socialEventService, never()).publishUnfollowed(any(), any(), any());
    }

    @Test
    void respondToFollowRequest_approve_setsAccepted() {
        UUID current = UUID.randomUUID();
        UUID requester = UUID.randomUUID();
        Follow follow = follow(requester, current, FollowStatus.PENDING);
        when(socialUserRepository.existsByIdAndDeletedAtIsNull(requester)).thenReturn(true);
        when(socialUserRepository.existsByIdAndDeletedAtIsNull(current)).thenReturn(true);
        when(followRepository.findByIdAndStatus(
                        new FollowId(requester, current), FollowStatus.PENDING))
                .thenReturn(Optional.of(follow));

        service.respondToFollowRequest(current, requester, "approve");

        assertThat(follow.getStatus()).isEqualTo(FollowStatus.ACCEPTED);
        verify(followRepository).save(follow);
        verify(socialEventService).publishFollowRequestResolved(requester, current, true);
    }

    @Test
    void respondToFollowRequest_reject_deletes() {
        UUID current = UUID.randomUUID();
        UUID requester = UUID.randomUUID();
        when(socialUserRepository.existsByIdAndDeletedAtIsNull(requester)).thenReturn(true);
        when(socialUserRepository.existsByIdAndDeletedAtIsNull(current)).thenReturn(true);
        when(followRepository.deleteByFollowerIdAndFollowingIdAndStatus(
                        requester, current, FollowStatus.PENDING))
                .thenReturn(1);

        service.respondToFollowRequest(current, requester, "reject");

        verify(followRepository)
                .deleteByFollowerIdAndFollowingIdAndStatus(
                        requester, current, FollowStatus.PENDING);
        verify(socialEventService).publishFollowRequestResolved(requester, current, false);
    }

    @Test
    void respondToFollowRequest_rejectConcurrentDuplicate_throwsRequestNotFoundNotServerError() {
        // Proves the fix for the load-then-delete(entity) race: a second concurrent reject that
        // finds zero rows affected must produce a clean SOCIAL_REQUEST_NOT_FOUND, not an
        // ObjectOptimisticLockingFailureException bubbling up as 500.
        UUID current = UUID.randomUUID();
        UUID requester = UUID.randomUUID();
        when(socialUserRepository.existsByIdAndDeletedAtIsNull(requester)).thenReturn(true);
        when(socialUserRepository.existsByIdAndDeletedAtIsNull(current)).thenReturn(true);
        when(followRepository.deleteByFollowerIdAndFollowingIdAndStatus(
                        requester, current, FollowStatus.PENDING))
                .thenReturn(0);

        assertThatThrownBy(() -> service.respondToFollowRequest(current, requester, "reject"))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.SOCIAL_REQUEST_NOT_FOUND);
    }

    @Test
    void respondToFollowRequest_invalidAction_throwsBadRequest() {
        UUID current = UUID.randomUUID();
        UUID requester = UUID.randomUUID();
        when(socialUserRepository.existsByIdAndDeletedAtIsNull(requester)).thenReturn(true);
        when(socialUserRepository.existsByIdAndDeletedAtIsNull(current)).thenReturn(true);
        when(followRepository.findByIdAndStatus(
                        new FollowId(requester, current), FollowStatus.PENDING))
                .thenReturn(Optional.of(follow(requester, current, FollowStatus.PENDING)));

        assertThatThrownBy(() -> service.respondToFollowRequest(current, requester, "maybe"))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.BAD_REQUEST);
    }

    @Test
    void respondToFollowRequest_noPendingRequest_throwsRequestNotFound() {
        UUID current = UUID.randomUUID();
        UUID requester = UUID.randomUUID();
        when(socialUserRepository.existsByIdAndDeletedAtIsNull(requester)).thenReturn(true);
        when(socialUserRepository.existsByIdAndDeletedAtIsNull(current)).thenReturn(true);
        when(followRepository.findByIdAndStatus(
                        new FollowId(requester, current), FollowStatus.PENDING))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.respondToFollowRequest(current, requester, "approve"))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.SOCIAL_REQUEST_NOT_FOUND);
    }

    @Test
    void blockUser_self_throwsSelfBlock() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> service.blockUser(id, id))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.SOCIAL_SELF_BLOCK);
    }

    @Test
    void blockUser_alreadyBlocked_throwsAlreadyBlocked() {
        UUID current = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        when(socialUserRepository.existsByIdAndDeletedAtIsNull(target)).thenReturn(true);
        when(blockRepository.existsById(new BlockId(current, target))).thenReturn(true);

        assertThatThrownBy(() -> service.blockUser(current, target))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.SOCIAL_ALREADY_BLOCKED);
    }

    @Test
    void blockUser_valid_savesBlockAndPurgesBothFollowDirections() {
        UUID current = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        Follow direct = follow(current, target, FollowStatus.ACCEPTED);
        Follow reverse = follow(target, current, FollowStatus.ACCEPTED);
        when(socialUserRepository.existsByIdAndDeletedAtIsNull(target)).thenReturn(true);
        when(blockRepository.existsById(new BlockId(current, target))).thenReturn(false);
        when(followRepository.findById(new FollowId(current, target)))
                .thenReturn(Optional.of(direct));
        when(followRepository.findById(new FollowId(target, current)))
                .thenReturn(Optional.of(reverse));

        service.blockUser(current, target);

        verify(blockRepository).save(any(Block.class));
        verify(followRepository).delete(direct);
        verify(followRepository).delete(reverse);
        verify(socialEventService).publishBlocked(current, target);
    }

    @Test
    void unblockUser_notBlocked_throwsNotFound() {
        UUID current = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        when(blockRepository.deleteByBlockerIdAndBlockedId(current, target)).thenReturn(0);

        assertThatThrownBy(() -> service.unblockUser(current, target))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.NOT_FOUND);
    }

    @Test
    void unblockUser_existing_deletes() {
        UUID current = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        when(blockRepository.deleteByBlockerIdAndBlockedId(current, target)).thenReturn(1);

        service.unblockUser(current, target);

        verify(blockRepository).deleteByBlockerIdAndBlockedId(current, target);
    }

    @Test
    void getFollowers_blocked_throwsNotFound() {
        // Stealth block model: a blocked target must be indistinguishable from a nonexistent one.
        UUID viewer = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        when(socialUserRepository.findByIdAndDeletedAtIsNull(target))
                .thenReturn(Optional.of(user(target, false)));
        when(blockRepository.existsById(new BlockId(viewer, target))).thenReturn(true);

        assertThatThrownBy(() -> service.getFollowers(target, viewer, null, 20))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.NOT_FOUND);
    }

    @Test
    void getFollowers_privateNonFollower_throwsForbidden() {
        UUID viewer = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        when(socialUserRepository.findByIdAndDeletedAtIsNull(target))
                .thenReturn(Optional.of(user(target, true)));
        when(blockRepository.existsById(any())).thenReturn(false);
        when(followRepository.existsByIdAndStatus(
                        new FollowId(viewer, target), FollowStatus.ACCEPTED))
                .thenReturn(false);

        assertThatThrownBy(() -> service.getFollowers(target, viewer, null, 20))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.FORBIDDEN);
    }

    @Test
    void getFollowers_invalidCursor_throwsInvalidCursor() {
        UUID viewer = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        when(socialUserRepository.findByIdAndDeletedAtIsNull(target))
                .thenReturn(Optional.of(user(target, false)));
        when(blockRepository.existsById(any())).thenReturn(false);

        assertThatThrownBy(() -> service.getFollowers(target, viewer, "!!!not-base64!!!", 20))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.INVALID_CURSOR);
    }

    @Test
    void getFollowers_emptyResult_returnsEmptyPage() {
        UUID viewer = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        when(socialUserRepository.findByIdAndDeletedAtIsNull(target))
                .thenReturn(Optional.of(user(target, false)));
        when(blockRepository.existsById(any())).thenReturn(false);
        when(followRepository.findFirstFollowers(any(), any(), any())).thenReturn(List.of());

        CursorPageResponse<UserListItemResponse> page =
                service.getFollowers(target, viewer, null, 20);

        assertThat(page.getContent()).isEmpty();
    }

    @Test
    void getFollowers_withContent_mapsSummaries() {
        UUID viewer = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        UUID followerId = UUID.randomUUID();
        Follow follow = follow(followerId, target, FollowStatus.ACCEPTED);
        when(socialUserRepository.findByIdAndDeletedAtIsNull(target))
                .thenReturn(Optional.of(user(target, false)));
        when(blockRepository.existsById(any())).thenReturn(false);
        when(followRepository.findFirstFollowers(any(), any(), any())).thenReturn(List.of(follow));
        when(socialUserRepository.findAllByIdInAndDeletedAtIsNull(List.of(followerId)))
                .thenReturn(List.of(user(followerId, false)));

        CursorPageResponse<UserListItemResponse> page =
                service.getFollowers(target, viewer, null, 20);

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).user().id()).isEqualTo(followerId);
    }

    @Test
    void getPendingFollowRequests_empty_returnsEmptyPage() {
        UUID current = UUID.randomUUID();
        when(followRepository.findFirstPendingRequests(eq(current), any())).thenReturn(List.of());

        CursorPageResponse<FollowRequestResponse> result =
                service.getPendingFollowRequests(current, null, 20);

        assertThat(result.getContent()).isEmpty();
    }

    @Test
    void getPendingFollowRequests_withRequests_mapsResponses() {
        UUID current = UUID.randomUUID();
        UUID requester = UUID.randomUUID();
        Follow pending = follow(requester, current, FollowStatus.PENDING);
        when(followRepository.findFirstPendingRequests(eq(current), any()))
                .thenReturn(List.of(pending));
        when(userSummaryService.loadSummaries(List.of(requester)))
                .thenReturn(
                        Map.of(
                                requester,
                                new UserSummaryResponse(
                                        requester, "requester", "Requester", null, false)));

        CursorPageResponse<FollowRequestResponse> result =
                service.getPendingFollowRequests(current, null, 20);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).id()).isEqualTo(requester);
    }

    @Test
    void getPendingFollowRequests_deletedRequester_returnsPlaceholderNotDropped() {
        UUID current = UUID.randomUUID();
        UUID requester = UUID.randomUUID();
        Follow pending = follow(requester, current, FollowStatus.PENDING);
        when(followRepository.findFirstPendingRequests(eq(current), any()))
                .thenReturn(List.of(pending));
        when(userSummaryService.loadSummaries(List.of(requester)))
                .thenReturn(
                        Map.of(
                                requester,
                                new UserSummaryResponse(
                                        requester, null, "Deleted user", null, false)));

        CursorPageResponse<FollowRequestResponse> result =
                service.getPendingFollowRequests(current, null, 20);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).follower().username()).isNull();
    }

    @Test
    void getAcceptedFollowingExcludingBlocks_noFollows_returnsEmpty() {
        UUID viewer = UUID.randomUUID();
        when(followRepository.findByIdFollowerIdAndStatus(viewer, FollowStatus.ACCEPTED))
                .thenReturn(List.of());

        List<UUID> result = service.getAcceptedFollowingExcludingBlocks(viewer);

        assertThat(result).isEmpty();
        verifyNoInteractions(blockRepository);
    }

    @Test
    void getAcceptedFollowingExcludingBlocks_noBlocks_returnsAllFollowingIds() {
        UUID viewer = UUID.randomUUID();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(followRepository.findByIdFollowerIdAndStatus(viewer, FollowStatus.ACCEPTED))
                .thenReturn(
                        List.of(
                                follow(viewer, a, FollowStatus.ACCEPTED),
                                follow(viewer, b, FollowStatus.ACCEPTED)));
        when(blockRepository.findByIdBlockerId(viewer)).thenReturn(List.of());
        when(blockRepository.findByIdBlockedId(viewer)).thenReturn(List.of());

        List<UUID> result = service.getAcceptedFollowingExcludingBlocks(viewer);

        assertThat(result).containsExactlyInAnyOrder(a, b);
    }

    @Test
    void getAcceptedFollowingExcludingBlocks_viewerBlockedSomeone_excludesBlockedId() {
        UUID viewer = UUID.randomUUID();
        UUID kept = UUID.randomUUID();
        UUID blocked = UUID.randomUUID();
        Block blockRow = Block.builder().id(new BlockId(viewer, blocked)).build();
        when(followRepository.findByIdFollowerIdAndStatus(viewer, FollowStatus.ACCEPTED))
                .thenReturn(
                        List.of(
                                follow(viewer, kept, FollowStatus.ACCEPTED),
                                follow(viewer, blocked, FollowStatus.ACCEPTED)));
        when(blockRepository.findByIdBlockerId(viewer)).thenReturn(List.of(blockRow));
        when(blockRepository.findByIdBlockedId(viewer)).thenReturn(List.of());

        List<UUID> result = service.getAcceptedFollowingExcludingBlocks(viewer);

        assertThat(result).containsExactly(kept);
    }

    @Test
    void getAcceptedFollowingExcludingBlocks_viewerIsBlockedBySomeone_excludesBlockerId() {
        UUID viewer = UUID.randomUUID();
        UUID kept = UUID.randomUUID();
        UUID blocker = UUID.randomUUID();
        Block blockRow = Block.builder().id(new BlockId(blocker, viewer)).build();
        when(followRepository.findByIdFollowerIdAndStatus(viewer, FollowStatus.ACCEPTED))
                .thenReturn(
                        List.of(
                                follow(viewer, kept, FollowStatus.ACCEPTED),
                                follow(viewer, blocker, FollowStatus.ACCEPTED)));
        when(blockRepository.findByIdBlockerId(viewer)).thenReturn(List.of());
        when(blockRepository.findByIdBlockedId(viewer)).thenReturn(List.of(blockRow));

        List<UUID> result = service.getAcceptedFollowingExcludingBlocks(viewer);

        assertThat(result).containsExactly(kept);
    }

    @Test
    void getAcceptedFollowingExcludingBlocks_allFollowedUsersBlocked_returnsEmpty() {
        UUID viewer = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        Block blockRow = Block.builder().id(new BlockId(viewer, target)).build();
        when(followRepository.findByIdFollowerIdAndStatus(viewer, FollowStatus.ACCEPTED))
                .thenReturn(List.of(follow(viewer, target, FollowStatus.ACCEPTED)));
        when(blockRepository.findByIdBlockerId(viewer)).thenReturn(List.of(blockRow));
        when(blockRepository.findByIdBlockedId(viewer)).thenReturn(List.of());

        List<UUID> result = service.getAcceptedFollowingExcludingBlocks(viewer);

        assertThat(result).isEmpty();
    }

    @Test
    void hasAcceptedFollow_delegatesToRepository() {
        UUID follower = UUID.randomUUID();
        UUID following = UUID.randomUUID();
        when(followRepository.existsByIdAndStatus(
                        new FollowId(follower, following), FollowStatus.ACCEPTED))
                .thenReturn(true);

        assertThat(service.hasAcceptedFollow(follower, following)).isTrue();
    }

    @Test
    void isBlockedBetween_delegatesToRepository() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(blockRepository.existsBetween(a, b)).thenReturn(true);

        assertThat(service.isBlockedBetween(a, b)).isTrue();
    }

    private static User user(UUID id, boolean isPrivate) {
        return User.builder()
                .id(id)
                .username("user-" + id.toString().substring(0, 8))
                .displayName("Display")
                .avatarUrl(null)
                .isPrivate(isPrivate)
                .isVerified(false)
                .build();
    }

    private static DataIntegrityViolationException uniqueViolation() {
        return new DataIntegrityViolationException(
                "duplicate key", new SQLException("duplicate key value", "23505"));
    }

    private static Follow follow(UUID follower, UUID following, FollowStatus status) {
        return Follow.builder()
                .id(new FollowId(follower, following))
                .status(status)
                .createdAt(OffsetDateTime.now())
                .build();
    }
}
