package com.app.modules.comment.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.app.common.response.UserSummaryResponse;
import com.app.modules.comment.entity.CommentMention;
import com.app.modules.comment.entity.CommentMentionId;
import com.app.modules.comment.repository.CommentMentionRepository;
import com.app.modules.comment.repository.CommentUserRepository;
import com.app.modules.post.dto.response.PostMentionResponse;
import com.app.modules.social.policy.InteractionDecision;
import com.app.modules.social.policy.InteractionOutcome;
import com.app.modules.social.policy.InteractionPolicyService;
import com.app.modules.social.policy.InteractionReason;
import com.app.modules.social.policy.InteractionRequest;
import com.app.modules.social.policy.InteractionType;
import com.app.modules.social.repository.BlockRepository;
import com.app.modules.users.entity.User;
import com.app.modules.users.service.UserSummaryService;

@ExtendWith(MockitoExtension.class)
class CommentMentionServiceImplTest {

    @Mock private CommentMentionRepository commentMentionRepository;
    @Mock private CommentUserRepository commentUserRepository;
    @Mock private InteractionPolicyService interactionPolicy;
    @Mock private UserSummaryService userSummaryService;
    @Mock private BlockRepository blockRepository;

    private CommentMentionServiceImpl service;

    private final UUID commentId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service =
                new CommentMentionServiceImpl(
                        commentMentionRepository,
                        commentUserRepository,
                        interactionPolicy,
                        userSummaryService,
                        blockRepository);
        lenient()
                .when(interactionPolicy.evaluate(any()))
                .thenReturn(
                        new InteractionDecision(InteractionOutcome.ALLOW, InteractionReason.NONE));
    }

    private InteractionRequest mentionRequestTo(UUID mentionedId) {
        return new InteractionRequest(
                actorId, mentionedId, InteractionType.MENTION, null, null, null);
    }

    private void stubUser(String handle, UUID userId) {
        when(commentUserRepository.findByUsernameAndDeletedAtIsNull(handle))
                .thenReturn(Optional.of(User.builder().id(userId).username(handle).build()));
    }

    @Test
    void replaceMentions_dottedUsername_resolvesTheWholeName() {
        UUID janeId = UUID.randomUUID();
        stubUser("jane.doe", janeId);

        List<String> notifiable = service.replaceMentions(commentId, actorId, "nice @jane.doe");

        assertThat(notifiable).containsExactly(janeId.toString());
        ArgumentCaptor<CommentMention> saved = ArgumentCaptor.forClass(CommentMention.class);
        verify(commentMentionRepository).save(saved.capture());
        assertThat(saved.getValue().getHandle()).isEqualTo("jane.doe");
        assertThat(saved.getValue().getMentionedUserId()).isEqualTo(janeId);
    }

    @Test
    void replaceMentions_mentionEndingASentence_dropsTheTrailingDot() {
        UUID janeId = UUID.randomUUID();
        stubUser("jane.doe", janeId);

        List<String> notifiable = service.replaceMentions(commentId, actorId, "thanks @jane.doe.");

        assertThat(notifiable).containsExactly(janeId.toString());
    }

    @Test
    void replaceMentions_differentCasing_keepsTheTypedHandle() {
        UUID janeId = UUID.randomUUID();
        stubUser("Jane.Doe", janeId);

        service.replaceMentions(commentId, actorId, "hi @Jane.Doe");

        ArgumentCaptor<CommentMention> saved = ArgumentCaptor.forClass(CommentMention.class);
        verify(commentMentionRepository).save(saved.capture());
        assertThat(saved.getValue().getHandle()).isEqualTo("Jane.Doe");
    }

    @Test
    void replaceMentions_emailAddress_isNotAMention() {
        List<String> notifiable =
                service.replaceMentions(commentId, actorId, "mail me@example.com");

        assertThat(notifiable).isEmpty();
        verify(commentUserRepository, never()).findByUsernameAndDeletedAtIsNull(any());
    }

    @Test
    void replaceMentions_unknownUsername_storesNothing() {
        when(commentUserRepository.findByUsernameAndDeletedAtIsNull("ghost"))
                .thenReturn(Optional.empty());

        List<String> notifiable = service.replaceMentions(commentId, actorId, "hi @ghost");

        assertThat(notifiable).isEmpty();
        verify(commentMentionRepository, never()).save(any());
    }

    @Test
    void replaceMentions_blockedAccount_isNeitherStoredNorNotified() {
        UUID openId = UUID.randomUUID();
        UUID blockedId = UUID.randomUUID();
        stubUser("open", openId);
        stubUser("blocked", blockedId);
        when(interactionPolicy.evaluate(mentionRequestTo(blockedId)))
                .thenReturn(
                        new InteractionDecision(
                                InteractionOutcome.DENY, InteractionReason.BLOCKED));

        List<String> notifiable =
                service.replaceMentions(commentId, actorId, "hi @open and @blocked");

        assertThat(notifiable).containsExactly(openId.toString());
        ArgumentCaptor<CommentMention> saved = ArgumentCaptor.forClass(CommentMention.class);
        verify(commentMentionRepository, times(1)).save(saved.capture());
        assertThat(saved.getValue().getMentionedUserId()).isEqualTo(openId);
    }

    @Test
    void replaceMentions_limitedTarget_isStoredButNotNotified() {
        UUID limitedId = UUID.randomUUID();
        stubUser("limited", limitedId);
        when(interactionPolicy.evaluate(mentionRequestTo(limitedId)))
                .thenReturn(
                        new InteractionDecision(
                                InteractionOutcome.ALLOW_HIDDEN,
                                InteractionReason.LIMITED_NON_FOLLOWER));

        List<String> notifiable = service.replaceMentions(commentId, actorId, "hi @limited");

        assertThat(notifiable).isEmpty();
        verify(commentMentionRepository, times(1)).save(any());
    }

    @Test
    void replaceMentions_sameAccountNamedTwice_isEvaluatedOnce() {
        UUID openId = UUID.randomUUID();
        stubUser("open", openId);

        List<String> notifiable = service.replaceMentions(commentId, actorId, "@open and @OPEN");

        assertThat(notifiable).containsExactly(openId.toString());
        verify(interactionPolicy, times(1)).evaluate(mentionRequestTo(openId));
        verify(commentMentionRepository, times(1)).save(any());
    }

    @Test
    void replaceMentions_selfMention_isStoredButNotNotified() {
        stubUser("me", actorId);

        List<String> notifiable = service.replaceMentions(commentId, actorId, "note to @me");

        assertThat(notifiable).isEmpty();
        verify(commentMentionRepository, times(1)).save(any());
    }

    @Test
    void replaceMentions_edit_clearsTheStoredRowsFirst() {
        service.replaceMentions(commentId, actorId, "no mentions left");

        verify(commentMentionRepository).deleteByCommentId(commentId);
    }

    @Test
    void loadForComments_rendersTheCurrentUsernameAndSkipsDeletedAndBlockedAccounts() {
        UUID viewerId = UUID.randomUUID();
        UUID renamedId = UUID.randomUUID();
        UUID deletedId = UUID.randomUUID();
        UUID blockedId = UUID.randomUUID();
        when(commentMentionRepository.findByCommentIdIn(anyCollection()))
                .thenReturn(
                        List.of(
                                row(renamedId, "old.name"),
                                row(deletedId, "gone"),
                                row(blockedId, "blocked.one")));
        when(blockRepository.findBlockedCounterpartyIds(viewerId)).thenReturn(List.of(blockedId));
        when(userSummaryService.loadSummaries(anyCollection()))
                .thenReturn(
                        Map.of(
                                renamedId,
                                new UserSummaryResponse(renamedId, "new.name", "New", null, false),
                                deletedId,
                                new UserSummaryResponse(deletedId, null, "Deleted", null, false),
                                blockedId,
                                new UserSummaryResponse(
                                        blockedId, "blocked.one", "B", null, false)));

        Map<UUID, List<PostMentionResponse>> result =
                service.loadForComments(viewerId, List.of(commentId));

        assertThat(result.get(commentId))
                .containsExactly(new PostMentionResponse("old.name", renamedId, "new.name"));
    }

    @Test
    void loadForComments_noIds_returnsAnEmptyMapWithoutQuerying() {
        assertThat(service.loadForComments(actorId, List.of())).isEmpty();

        verify(commentMentionRepository, never()).findByCommentIdIn(anyCollection());
    }

    private CommentMention row(UUID mentionedId, String handle) {
        return CommentMention.builder()
                .id(new CommentMentionId(commentId, mentionedId))
                .handle(handle)
                .build();
    }
}
