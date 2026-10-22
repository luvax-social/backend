package com.app.modules.post.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.app.common.outbox.service.OutboxService;
import com.app.common.response.UserSummaryResponse;
import com.app.modules.post.dto.response.PostMentionResponse;
import com.app.modules.post.entity.Post;
import com.app.modules.post.entity.PostMention;
import com.app.modules.post.entity.PostMentionId;
import com.app.modules.post.messaging.PostEventTypes;
import com.app.modules.post.repository.PostMentionRepository;
import com.app.modules.post.repository.PostUserRepository;
import com.app.modules.post.service.PostMentionService.MentionChange;
import com.app.modules.social.policy.InteractionDecision;
import com.app.modules.social.policy.InteractionOutcome;
import com.app.modules.social.policy.InteractionPolicyService;
import com.app.modules.social.policy.InteractionReason;
import com.app.modules.social.policy.InteractionRequest;
import com.app.modules.social.policy.InteractionType;
import com.app.modules.social.service.SocialService;
import com.app.modules.users.entity.User;
import com.app.modules.users.service.UserSummaryService;

@ExtendWith(MockitoExtension.class)
class PostMentionServiceImplTest {

    @Mock private PostMentionRepository postMentionRepository;
    @Mock private PostUserRepository postUserRepository;
    @Mock private InteractionPolicyService interactionPolicyService;
    @Mock private UserSummaryService userSummaryService;
    @Mock private SocialService socialService;
    @Mock private OutboxService outboxService;

    private PostMentionServiceImpl service;

    private final UUID ownerId = UUID.randomUUID();
    private final UUID postId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service =
                new PostMentionServiceImpl(
                        postMentionRepository,
                        postUserRepository,
                        interactionPolicyService,
                        userSummaryService,
                        socialService,
                        outboxService);
        lenient().when(postMentionRepository.findByPostId(postId)).thenReturn(List.of());
        lenient()
                .when(interactionPolicyService.evaluate(any(InteractionRequest.class)))
                .thenReturn(
                        new InteractionDecision(InteractionOutcome.ALLOW, InteractionReason.NONE));
    }

    private Post post(String caption) {
        return Post.builder().id(postId).userId(ownerId).caption(caption).build();
    }

    private UUID knownUser(String handle) {
        UUID id = UUID.randomUUID();
        when(postUserRepository.findActiveByUsername(handle))
                .thenReturn(Optional.of(User.builder().id(id).username(handle).build()));
        return id;
    }

    private PostMention stored(UUID userId, String handle) {
        return PostMention.builder().id(new PostMentionId(postId, userId)).handle(handle).build();
    }

    @Test
    void replaceCaptionMentions_dottedAndMixedCaseHandles_resolveAndKeepTheTypedHandle() {
        UUID jane = knownUser("Jane.Doe");

        MentionChange change = service.replaceCaptionMentions(post("hello @Jane.Doe."));

        assertThat(change.stored()).containsExactly(jane);
        assertThat(change.notifiable()).containsExactly(jane);
        assertThat(change.added()).containsExactly(jane);
        ArgumentCaptor<PostMention> saved = ArgumentCaptor.forClass(PostMention.class);
        verify(postMentionRepository).save(saved.capture());
        assertThat(saved.getValue().getHandle()).isEqualTo("Jane.Doe");
    }

    @Test
    void replaceCaptionMentions_unknownHandle_storesNothing() {
        when(postUserRepository.findActiveByUsername("ghost")).thenReturn(Optional.empty());

        MentionChange change = service.replaceCaptionMentions(post("hi @ghost"));

        assertThat(change.stored()).isEmpty();
        verify(postMentionRepository, never()).save(any());
    }

    @Test
    void replaceCaptionMentions_blockedAccount_storesNothing() {
        UUID blocked = knownUser("blocked");
        when(interactionPolicyService.evaluate(any(InteractionRequest.class)))
                .thenReturn(
                        new InteractionDecision(
                                InteractionOutcome.DENY, InteractionReason.BLOCKED));

        MentionChange change = service.replaceCaptionMentions(post("hi @blocked"));

        assertThat(change.stored()).doesNotContain(blocked);
        assertThat(change.notifiable()).isEmpty();
        verify(postMentionRepository, never()).save(any());
    }

    @Test
    void replaceCaptionMentions_limitedTarget_isStoredButNotNotifiable() {
        UUID limited = knownUser("limited");
        when(interactionPolicyService.evaluate(any(InteractionRequest.class)))
                .thenReturn(
                        new InteractionDecision(
                                InteractionOutcome.ALLOW_HIDDEN,
                                InteractionReason.LIMITED_NON_FOLLOWER));

        MentionChange change = service.replaceCaptionMentions(post("hi @limited"));

        assertThat(change.stored()).containsExactly(limited);
        assertThat(change.notifiable()).isEmpty();
    }

    @Test
    void replaceCaptionMentions_authorMentioningThemselves_isStoredButNeverNotifiable() {
        when(postUserRepository.findActiveByUsername("me"))
                .thenReturn(Optional.of(User.builder().id(ownerId).username("me").build()));

        MentionChange change = service.replaceCaptionMentions(post("note to @me"));

        assertThat(change.stored()).containsExactly(ownerId);
        assertThat(change.notifiable()).isEmpty();
    }

    @Test
    void replaceCaptionMentions_sameAccountInTwoSpellings_isEvaluatedOnce() {
        knownUser("open");

        service.replaceCaptionMentions(post("@open and @OPEN"));

        verify(interactionPolicyService, times(1)).evaluate(any(InteractionRequest.class));
        verify(postMentionRepository, times(1)).save(any());
    }

    @Test
    void replaceCaptionMentions_policyIsAskedForAMentionByTheAuthor() {
        UUID open = knownUser("open");

        service.replaceCaptionMentions(post("hi @open"));

        ArgumentCaptor<InteractionRequest> request =
                ArgumentCaptor.forClass(InteractionRequest.class);
        verify(interactionPolicyService).evaluate(request.capture());
        assertThat(request.getValue().type()).isEqualTo(InteractionType.MENTION);
        assertThat(request.getValue().actorId()).isEqualTo(ownerId);
        assertThat(request.getValue().targetUserId()).isEqualTo(open);
    }

    @Test
    void replaceCaptionMentions_edit_deletesRowsNoLongerNamedAndLeavesTheKeptOnesAlone() {
        UUID kept = knownUser("kept");
        UUID dropped = UUID.randomUUID();
        PostMention keptRow = stored(kept, "kept");
        PostMention droppedRow = stored(dropped, "dropped");
        when(postMentionRepository.findByPostId(postId)).thenReturn(List.of(keptRow, droppedRow));

        MentionChange change = service.replaceCaptionMentions(post("only @kept now"));

        assertThat(change.added()).isEmpty();
        assertThat(change.stored()).containsExactly(kept);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<PostMention>> deleted = ArgumentCaptor.forClass(Iterable.class);
        verify(postMentionRepository).deleteAll(deleted.capture());
        assertThat(deleted.getValue()).containsExactly(droppedRow);
        verify(postMentionRepository, never()).save(any());
    }

    @Test
    void replaceCaptionMentions_changedSpellingOfAKeptMention_updatesTheHandle() {
        UUID jane = knownUser("JANE");
        PostMention row = stored(jane, "jane");
        when(postMentionRepository.findByPostId(postId)).thenReturn(List.of(row));

        service.replaceCaptionMentions(post("hi @JANE"));

        assertThat(row.getHandle()).isEqualTo("JANE");
    }

    @Test
    void replaceCaptionMentions_moreThanTenHandles_readsOnlyTheFirstTen() {
        StringBuilder caption = new StringBuilder();
        for (int i = 0; i < 14; i++) {
            String handle = "user" + i;
            caption.append("@").append(handle).append(' ');
            lenient()
                    .when(postUserRepository.findActiveByUsername(handle))
                    .thenReturn(
                            Optional.of(
                                    User.builder().id(UUID.randomUUID()).username(handle).build()));
        }

        MentionChange change = service.replaceCaptionMentions(post(caption.toString()));

        assertThat(change.stored()).hasSize(10);
    }

    @Test
    void announce_emitsOneEventPerAccountCarryingTheNotifyFlag() {
        UUID notified = UUID.randomUUID();
        UUID quiet = UUID.randomUUID();

        service.announce(post("x"), List.of(notified, quiet), List.of(notified));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> data = ArgumentCaptor.forClass(Map.class);
        verify(outboxService, times(2))
                .enqueue(
                        eq(PostEventTypes.POST_MENTION_CREATED_V1),
                        eq(PostEventTypes.POST_MENTION_CREATED_V1),
                        eq("post"),
                        eq(postId),
                        eq(ownerId),
                        data.capture());
        assertThat(data.getAllValues())
                .extracting(d -> d.get("notify"))
                .containsExactly(true, false);
    }

    @Test
    void loadForPosts_showsTheCurrentUsernameAndSkipsDeletedAndBlockedAccounts() {
        UUID viewer = UUID.randomUUID();
        UUID renamed = UUID.randomUUID();
        UUID deleted = UUID.randomUUID();
        UUID blocked = UUID.randomUUID();
        when(postMentionRepository.findByPostIdIn(anyCollection()))
                .thenReturn(
                        List.of(
                                stored(renamed, "old.name"),
                                stored(deleted, "gone"),
                                stored(blocked, "blocked.one")));
        when(socialService.findBlockedEitherDirection(viewer)).thenReturn(Set.of(blocked));
        when(userSummaryService.loadSummaries(anyCollection()))
                .thenAnswer(
                        invocation ->
                                Map.of(
                                        renamed,
                                        new UserSummaryResponse(
                                                renamed, "new.name", "N", null, false),
                                        deleted,
                                        new UserSummaryResponse(
                                                deleted, null, "Deleted", null, false),
                                        blocked,
                                        new UserSummaryResponse(
                                                blocked, "blocked.one", "B", null, false)));

        Map<UUID, List<PostMentionResponse>> result = service.loadForPosts(viewer, List.of(postId));

        assertThat(result.get(postId))
                .containsExactly(new PostMentionResponse("old.name", renamed, "new.name"));
    }

    @Test
    void loadForPosts_noPosts_returnsAnEmptyMapWithoutQuerying() {
        Collection<UUID> none = List.of();

        assertThat(service.loadForPosts(ownerId, none)).isEmpty();

        verify(postMentionRepository, never()).findByPostIdIn(anyCollection());
    }
}
