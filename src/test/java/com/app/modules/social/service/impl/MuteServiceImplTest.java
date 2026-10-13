package com.app.modules.social.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.common.outbox.service.OutboxService;
import com.app.common.pagination.Cursor;
import com.app.common.pagination.CursorCodec;
import com.app.common.pagination.CursorScope;
import com.app.common.pagination.TimeCursors;
import com.app.common.response.CursorPageResponse;
import com.app.common.response.UserSummaryResponse;
import com.app.modules.social.dto.response.MuteResponse;
import com.app.modules.social.entity.UserMute;
import com.app.modules.social.entity.UserMuteId;
import com.app.modules.social.messaging.SocialEventTypes;
import com.app.modules.social.repository.BlockRepository;
import com.app.modules.social.repository.SocialUserRepository;
import com.app.modules.social.repository.UserMuteRepository;
import com.app.modules.users.service.UserSummaryService;

@ExtendWith(MockitoExtension.class)
class MuteServiceImplTest {

    @Mock private UserMuteRepository userMuteRepository;
    @Mock private SocialUserRepository socialUserRepository;
    @Mock private BlockRepository blockRepository;
    @Mock private UserSummaryService userSummaryService;
    @Mock private OutboxService outboxService;

    private MuteServiceImpl service;

    private final UUID userId = UUID.randomUUID();
    private final UUID targetId = UUID.randomUUID();
    private final UserSummaryResponse targetSummary =
            new UserSummaryResponse(targetId, "target", "Target", null, false, null);

    @BeforeEach
    void setUp() {
        service =
                new MuteServiceImpl(
                        userMuteRepository,
                        socialUserRepository,
                        blockRepository,
                        userSummaryService,
                        outboxService);
        lenient()
                .when(socialUserRepository.existsByIdAndDeletedAtIsNull(targetId))
                .thenReturn(true);
        lenient().when(blockRepository.existsBetween(userId, targetId)).thenReturn(false);
        lenient()
                .when(userSummaryService.loadSummaries(List.of(targetId)))
                .thenReturn(Map.of(targetId, targetSummary));
    }

    @Test
    void setMute_postsOnly_upsertsAndEnqueuesMutedEventWithBothScopes() {
        when(userMuteRepository.upsert(userId, targetId, true, false)).thenReturn(1);

        MuteResponse response = service.setMute(userId, targetId, true, false);

        assertThat(response.userId()).isEqualTo(targetId);
        assertThat(response.mutePosts()).isTrue();
        assertThat(response.muteStories()).isFalse();
        assertThat(response.user()).isEqualTo(targetSummary);
        verify(outboxService)
                .enqueue(
                        eq(SocialEventTypes.USER_MUTED_V1),
                        eq(SocialEventTypes.USER_MUTED_V1),
                        eq("user"),
                        eq(userId),
                        eq(userId),
                        eq(
                                Map.of(
                                        "targetUserId",
                                        targetId.toString(),
                                        "mutePosts",
                                        true,
                                        "muteStories",
                                        false)));
    }

    @Test
    void setMute_sameScopesAgain_changesNothingAndEnqueuesNoEvent() {
        when(userMuteRepository.upsert(userId, targetId, true, true)).thenReturn(0);

        MuteResponse response = service.setMute(userId, targetId, true, true);

        assertThat(response.mutePosts()).isTrue();
        assertThat(response.muteStories()).isTrue();
        verify(outboxService, never()).enqueue(any(), any(), any(), any(), any(), any());
    }

    @Test
    void setMute_bothOff_deletesRowAndEnqueuesUnmutedEvent() {
        when(userMuteRepository.deleteByUserAndMuted(userId, targetId)).thenReturn(1);

        MuteResponse response = service.setMute(userId, targetId, false, false);

        assertThat(response.mutePosts()).isFalse();
        assertThat(response.muteStories()).isFalse();
        verify(userMuteRepository, never())
                .upsert(any(), any(), any(Boolean.class), any(Boolean.class));
        verify(outboxService)
                .enqueue(
                        eq(SocialEventTypes.USER_UNMUTED_V1),
                        eq(SocialEventTypes.USER_UNMUTED_V1),
                        eq("user"),
                        eq(userId),
                        eq(userId),
                        eq(Map.of("targetUserId", targetId.toString())));
    }

    @Test
    void setMute_bothOffWithNoRow_enqueuesNothing() {
        when(userMuteRepository.deleteByUserAndMuted(userId, targetId)).thenReturn(0);

        service.setMute(userId, targetId, false, false);

        verify(outboxService, never()).enqueue(any(), any(), any(), any(), any(), any());
    }

    @Test
    void setMute_self_throwsBadRequest() {
        assertThatThrownBy(() -> service.setMute(userId, userId, true, true))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.BAD_REQUEST);
        verify(userMuteRepository, never())
                .upsert(any(), any(), any(Boolean.class), any(Boolean.class));
    }

    @Test
    void setMute_missingTarget_throwsNotFound() {
        when(socialUserRepository.existsByIdAndDeletedAtIsNull(targetId)).thenReturn(false);

        assertThatThrownBy(() -> service.setMute(userId, targetId, true, true))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.NOT_FOUND);
        verify(outboxService, never()).enqueue(any(), any(), any(), any(), any(), any());
    }

    @Test
    void setMute_blockedTargetEitherDirection_throwsTheSameNotFoundAsAMissingOne() {
        when(blockRepository.existsBetween(userId, targetId)).thenReturn(true);

        assertThatThrownBy(() -> service.setMute(userId, targetId, true, true))
                .isInstanceOf(AppException.class)
                .satisfies(
                        e -> {
                            AppException ex = (AppException) e;
                            assertThat(ex.getErrorCode()).isEqualTo(ApiErrorCode.NOT_FOUND);
                            assertThat(ex.getMessage()).isEqualTo("Target user not found");
                        });
        verify(userMuteRepository, never())
                .upsert(any(), any(), any(Boolean.class), any(Boolean.class));
    }

    @Test
    void unmute_rowExisted_enqueuesUnmutedEvent() {
        when(userMuteRepository.deleteByUserAndMuted(userId, targetId)).thenReturn(1);

        service.unmute(userId, targetId);

        verify(outboxService)
                .enqueue(
                        eq(SocialEventTypes.USER_UNMUTED_V1),
                        eq(SocialEventTypes.USER_UNMUTED_V1),
                        eq("user"),
                        eq(userId),
                        eq(userId),
                        eq(Map.of("targetUserId", targetId.toString())));
    }

    @Test
    void unmute_noRow_isIdempotentWithoutEvent() {
        when(userMuteRepository.deleteByUserAndMuted(userId, targetId)).thenReturn(0);

        service.unmute(userId, targetId);

        verify(outboxService, never()).enqueue(any(), any(), any(), any(), any(), any());
    }

    @Test
    void listMutes_firstPage_returnsRowsWithSummariesAndEncodesTheLastRowIntoTheCursor() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        OffsetDateTime t1 = OffsetDateTime.parse("2026-03-01T10:00:00.123456Z");
        OffsetDateTime t2 = OffsetDateTime.parse("2026-03-01T09:00:00Z");
        OffsetDateTime t3 = OffsetDateTime.parse("2026-03-01T08:00:00Z");
        when(userMuteRepository.findFirstMutes(eq(userId), any()))
                .thenReturn(
                        List.of(
                                mute(first, true, false, t1),
                                mute(second, false, true, t2),
                                mute(third, true, true, t3)));
        when(userSummaryService.loadSummaries(List.of(first, second)))
                .thenReturn(
                        Map.of(
                                first, summary(first),
                                second, summary(second)));

        CursorPageResponse<MuteResponse> page = service.listMutes(userId, null, 2);

        assertThat(page.getContent())
                .extracting(MuteResponse::userId)
                .containsExactly(first, second);
        assertThat(page.getContent().get(0).mutePosts()).isTrue();
        assertThat(page.getContent().get(0).muteStories()).isFalse();
        assertThat(page.getContent().get(1).muteStories()).isTrue();
        assertThat(page.getContent().get(0).user().id()).isEqualTo(first);
        assertThat(page.getPageInfo().isHasNextPage()).isTrue();
        Cursor end =
                CursorCodec.decode(page.getPageInfo().getEndCursor(), CursorScope.SOCIAL_MUTES);
        assertThat(end.id()).isEqualTo(second);
        assertThat(end.sortValueMicros()).isEqualTo(TimeCursors.toMicros(t2));
    }

    @Test
    void listMutes_emptyResult_returnsEmptyPage() {
        when(userMuteRepository.findFirstMutes(eq(userId), any())).thenReturn(List.of());

        CursorPageResponse<MuteResponse> page = service.listMutes(userId, null, 20);

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getPageInfo().isHasNextPage()).isFalse();
    }

    @Test
    void listMutes_cursorFromAnotherListing_throwsInvalidCursor() {
        String foreign =
                CursorCodec.encode(
                        new Cursor(TimeCursors.toMicros(OffsetDateTime.now()), UUID.randomUUID()),
                        CursorScope.SOCIAL_BLOCKED);

        assertThatThrownBy(() -> service.listMutes(userId, foreign, 20))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.INVALID_CURSOR);
    }

    private UserMute mute(UUID mutedId, boolean posts, boolean stories, OffsetDateTime updatedAt) {
        return UserMute.builder()
                .id(new UserMuteId(userId, mutedId))
                .mutePosts(posts)
                .muteStories(stories)
                .createdAt(updatedAt)
                .updatedAt(updatedAt)
                .build();
    }

    private static UserSummaryResponse summary(UUID id) {
        return new UserSummaryResponse(
                id, "u" + id.toString().substring(0, 4), "U", null, false, null);
    }
}
