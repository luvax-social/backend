package com.app.modules.notification.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.app.common.response.PreviewMediaResponse;
import com.app.common.response.UserSummaryResponse;
import com.app.common.response.ViewerRelationshipResponse;
import com.app.modules.admin.dto.response.ModerationNoticeResponse;
import com.app.modules.admin.service.ModerationNoticeService;
import com.app.modules.comment.dto.response.CommentPreviewResponse;
import com.app.modules.comment.service.CommentPreviewService;
import com.app.modules.notification.dto.response.NotificationItemResponse;
import com.app.modules.notification.entity.enums.NotificationType;
import com.app.modules.notification.repository.NotificationFeedRepository;
import com.app.modules.notification.repository.NotificationFeedRepository.FeedRow;
import com.app.modules.notification.repository.NotificationFeedRepository.Key;
import com.app.modules.post.dto.response.PostPreviewResponse;
import com.app.modules.post.service.PostPreviewService;
import com.app.modules.social.service.SocialService;
import com.app.modules.story.dto.response.StoryPreviewResponse;
import com.app.modules.story.service.StoryPreviewService;
import com.app.modules.support.service.SupportTicketPreviewService;
import com.app.modules.users.service.UserSummaryService;

@ExtendWith(MockitoExtension.class)
class NotificationItemAssemblerTest {

    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 9, 1, 12, 0, 0, 0, ZoneOffset.UTC);
    private static final PreviewMediaResponse MEDIA =
            new PreviewMediaResponse("https://cdn/p.jpg", "LKO2", "image", 1080, 1350);

    @Mock private NotificationFeedRepository feedRepository;
    @Mock private UserSummaryService userSummaryService;
    @Mock private SocialService socialService;
    @Mock private PostPreviewService postPreviewService;
    @Mock private CommentPreviewService commentPreviewService;
    @Mock private StoryPreviewService storyPreviewService;
    @Mock private ModerationNoticeService moderationNoticeService;
    @Mock private SupportTicketPreviewService supportTicketPreviewService;

    private NotificationItemAssembler assembler;
    private final UUID viewer = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        assembler =
                new NotificationItemAssembler(
                        feedRepository,
                        userSummaryService,
                        socialService,
                        postPreviewService,
                        commentPreviewService,
                        storyPreviewService,
                        moderationNoticeService,
                        supportTicketPreviewService);
        lenient().when(feedRepository.findDisplayActors(any(), any())).thenReturn(Map.of());
        lenient().when(feedRepository.countHiddenActors(any(), any())).thenReturn(Map.of());
        lenient().when(userSummaryService.loadSummaries(any())).thenReturn(Map.of());
        lenient().when(socialService.findPendingRequesters(any(), any())).thenReturn(Set.of());
    }

    @Test
    void assemble_likeGroup_showsTwoActorsAndCountsOnlyVisibleOnes() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        UUID post = UUID.randomUUID();
        FeedRow row = row(NotificationType.LIKE_POST, post, post, null, 14);
        when(feedRepository.findDisplayActors(viewer, List.of(row.id())))
                .thenReturn(Map.of(row.id(), List.of(a, b, c)));
        when(feedRepository.countHiddenActors(viewer, List.of(row.id())))
                .thenReturn(Map.of(row.id(), 2));
        when(userSummaryService.loadSummaries(any()))
                .thenReturn(Map.of(a, user(a), b, user(b), c, user(c)));
        when(postPreviewService.loadPreviews(viewer, Set.of(post)))
                .thenReturn(Map.of(post, new PostPreviewResponse(post, true, MEDIA)));

        NotificationItemResponse item = assembler.assemble(viewer, List.of(row), null).get(0);

        assertThat(item.actors()).extracting(UserSummaryResponse::id).containsExactly(a, b);
        assertThat(item.actorCount()).isEqualTo(12);
        assertThat(item.target().kind()).isEqualTo("post");
        assertThat(item.target().available()).isTrue();
        assertThat(item.preview().media()).isEqualTo(MEDIA);
        assertThat(item.isNew()).isTrue();
    }

    @Test
    void assemble_unavailablePost_hasNoPreview() {
        UUID post = UUID.randomUUID();
        FeedRow row = row(NotificationType.LIKE_POST, post, post, null, 1);
        when(postPreviewService.loadPreviews(viewer, Set.of(post)))
                .thenReturn(Map.of(post, PostPreviewResponse.unavailable(post)));

        NotificationItemResponse item = assembler.assemble(viewer, List.of(row), null).get(0);

        assertThat(item.target().available()).isFalse();
        assertThat(item.preview()).isNull();
    }

    @Test
    void assemble_reply_needsCommentAndPostAvailable_andShowsTheParentSnippet() {
        UUID post = UUID.randomUUID();
        UUID reply = UUID.randomUUID();
        FeedRow row = row(NotificationType.REPLY_COMMENT, reply, post, null, 1);
        when(commentPreviewService.loadPreviews(Set.of(reply)))
                .thenReturn(
                        Map.of(
                                reply,
                                new CommentPreviewResponse(
                                        reply,
                                        post,
                                        UUID.randomUUID(),
                                        "agreed",
                                        "the colours",
                                        true)));
        when(postPreviewService.loadPreviews(viewer, Set.of(post)))
                .thenReturn(Map.of(post, new PostPreviewResponse(post, true, MEDIA)));

        NotificationItemResponse item = assembler.assemble(viewer, List.of(row), null).get(0);

        assertThat(item.target().kind()).isEqualTo("comment");
        assertThat(item.target().commentId()).isEqualTo(reply);
        assertThat(item.target().postId()).isEqualTo(post);
        assertThat(item.preview().commentSnippet()).isEqualTo("agreed");
        assertThat(item.preview().parentCommentSnippet()).isEqualTo("the colours");
    }

    @Test
    void assemble_commentOnAPostTheViewerCanNoLongerSee_isUnavailable() {
        UUID post = UUID.randomUUID();
        UUID comment = UUID.randomUUID();
        FeedRow row = row(NotificationType.MENTION_COMMENT, comment, post, null, 1);
        when(commentPreviewService.loadPreviews(Set.of(comment)))
                .thenReturn(
                        Map.of(
                                comment,
                                new CommentPreviewResponse(comment, post, null, "hi", null, true)));
        when(postPreviewService.loadPreviews(viewer, Set.of(post)))
                .thenReturn(Map.of(post, PostPreviewResponse.unavailable(post)));

        NotificationItemResponse item = assembler.assemble(viewer, List.of(row), null).get(0);

        assertThat(item.target().available()).isFalse();
        assertThat(item.preview()).isNull();
    }

    @Test
    void assemble_expiredStory_isUnavailable() {
        UUID story = UUID.randomUUID();
        FeedRow row = row(NotificationType.STORY_VIEW, story, null, null, 3);
        when(storyPreviewService.loadPreviews(viewer, Set.of(story)))
                .thenReturn(Map.of(story, StoryPreviewResponse.unavailable(story, NOW)));

        NotificationItemResponse item = assembler.assemble(viewer, List.of(row), null).get(0);

        assertThat(item.target().kind()).isEqualTo("story");
        assertThat(item.target().available()).isFalse();
    }

    @Test
    void assemble_follow_carriesTheRelationshipToTheNewestActor() {
        UUID follower = UUID.randomUUID();
        FeedRow row = row(NotificationType.FOLLOW, null, null, null, 1);
        when(feedRepository.findDisplayActors(viewer, List.of(row.id())))
                .thenReturn(Map.of(row.id(), List.of(follower)));
        when(userSummaryService.loadSummaries(any())).thenReturn(Map.of(follower, user(follower)));
        when(socialService.loadRelationships(viewer, List.of(follower)))
                .thenReturn(
                        Map.of(
                                follower,
                                new ViewerRelationshipResponse(false, true, true, false, false)));

        NotificationItemResponse item = assembler.assemble(viewer, List.of(row), null).get(0);

        assertThat(item.target().kind()).isEqualTo("user");
        assertThat(item.target().userId()).isEqualTo(follower);
        assertThat(item.relationship().isFollowing()).isFalse();
        assertThat(item.relationship().isRequested()).isTrue();
        assertThat(item.relationship().hasPendingRequestFrom()).isFalse();
    }

    @Test
    void assemble_moderationNotice_hasNoActorsAndOffersTheAppeal() {
        UUID audit = UUID.randomUUID();
        FeedRow row = row(NotificationType.COMMENT_REMOVED, audit, null, audit, 0);
        when(moderationNoticeService.loadNotices(viewer, Set.of(audit)))
                .thenReturn(
                        Map.of(
                                audit,
                                new ModerationNoticeResponse(
                                        audit,
                                        "remove_comment",
                                        "harassment",
                                        "comment",
                                        "you are",
                                        NOW,
                                        true)));

        NotificationItemResponse item = assembler.assemble(viewer, List.of(row), null).get(0);

        assertThat(item.actors()).isEmpty();
        assertThat(item.actorCount()).isZero();
        assertThat(item.target().kind()).isEqualTo("moderation");
        assertThat(item.moderation().appealActionId()).isEqualTo(audit);
        assertThat(item.moderation().affectedSnippet()).isEqualTo("you are");
        verify(userSummaryService).loadSummaries(Set.of());
    }

    @Test
    void assemble_unlinkedModerationNotice_fallsBackToTheRecordedReason() {
        FeedRow row =
                new FeedRow(
                        UUID.randomUUID(),
                        NotificationType.POST_REMOVED,
                        NotificationType.POST_REMOVED.category(),
                        "post",
                        UUID.randomUUID(),
                        null,
                        "spam",
                        null,
                        null,
                        NOW,
                        NOW,
                        0);

        NotificationItemResponse item = assembler.assemble(viewer, List.of(row), null).get(0);

        assertThat(item.moderation().reason()).isEqualTo("spam");
        assertThat(item.moderation().appealable()).isFalse();
        verify(moderationNoticeService, never()).loadNotices(any(), any());
    }

    @Test
    void assemble_isNew_comparesAgainstTheBoundaryTuple() {
        UUID low = new UUID(0, 1);
        UUID high = new UUID(-1, 1);
        FeedRow tieAbove = rowAt(high, NOW);
        FeedRow tieBelow = rowAt(low, NOW);
        FeedRow older = rowAt(UUID.randomUUID(), NOW.minusSeconds(1));
        Key boundary = new Key(NOW, new UUID(1, 1));

        List<NotificationItemResponse> items =
                assembler.assemble(viewer, List.of(tieAbove, tieBelow, older), boundary);

        assertThat(items)
                .extracting(NotificationItemResponse::isNew)
                .containsExactly(true, false, false);
    }

    private static FeedRow row(
            NotificationType type, UUID entityId, UUID postId, UUID adminActionId, int actorCount) {
        return new FeedRow(
                UUID.randomUUID(),
                type,
                type.category(),
                null,
                entityId,
                postId,
                null,
                adminActionId,
                null,
                NOW,
                NOW,
                actorCount);
    }

    private static FeedRow rowAt(UUID id, OffsetDateTime activityAt) {
        return new FeedRow(
                id,
                NotificationType.WARNING,
                NotificationType.WARNING.category(),
                null,
                null,
                null,
                null,
                null,
                null,
                activityAt,
                activityAt,
                0);
    }

    private static UserSummaryResponse user(UUID id) {
        return new UserSummaryResponse(
                id, "u" + id.toString().substring(0, 6), "User", null, false, null);
    }
}
