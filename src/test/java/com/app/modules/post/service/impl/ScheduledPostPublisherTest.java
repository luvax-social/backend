package com.app.modules.post.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;

import com.app.common.enums.ApiErrorCode;
import com.app.modules.post.entity.Post;
import com.app.modules.post.enums.PostStatus;
import com.app.modules.post.repository.PostRepository;
import com.app.modules.post.service.PostPublicationException;
import com.app.modules.post.service.PostPublicationException.Reason;
import com.app.modules.post.service.PostPublicationService;
import com.app.modules.post.service.PostPublicationService.Source;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@ExtendWith(MockitoExtension.class)
class ScheduledPostPublisherTest {

    @Mock private PostRepository postRepository;
    @Mock private PostPublicationService postPublicationService;
    @Mock private PlatformTransactionManager transactionManager;

    private SimpleMeterRegistry meters;
    private ScheduledPostPublisher publisher;

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
        publisher =
                new ScheduledPostPublisher(
                        postRepository, postPublicationService, transactionManager, meters);
    }

    private Post duePost(UUID id) {
        return Post.builder()
                .id(id)
                .userId(UUID.randomUUID())
                .status(PostStatus.SCHEDULED)
                .scheduledAt(OffsetDateTime.now().minusSeconds(20))
                .build();
    }

    @Test
    void publishDue_eachDuePostRunsInItsOwnNewTransaction() {
        UUID id = UUID.randomUUID();
        when(postRepository.findDueScheduledIds(50)).thenReturn(List.of(id));
        when(postRepository.claimDueScheduledPost(id)).thenReturn(Optional.of(duePost(id)));

        publisher.publishDue(50);

        org.mockito.ArgumentCaptor<TransactionDefinition> definition =
                org.mockito.ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager).getTransaction(definition.capture());
        assertThat(definition.getValue().getPropagationBehavior())
                .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Test
    void publishDue_claimedPost_isValidatedThenPublishedByTheScheduler() {
        UUID id = UUID.randomUUID();
        Post post = duePost(id);
        when(postRepository.findDueScheduledIds(50)).thenReturn(List.of(id));
        when(postRepository.claimDueScheduledPost(id)).thenReturn(Optional.of(post));

        int published = publisher.publishDue(50);

        assertThat(published).isEqualTo(1);
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(postPublicationService);
        order.verify(postPublicationService).validate(post);
        order.verify(postPublicationService).publish(post, Source.SCHEDULER);
        assertThat(meters.counter("luvax.posts.scheduled.published").count()).isEqualTo(1.0);
        assertThat(meters.timer("luvax.posts.scheduled.lag").count()).isEqualTo(1L);
    }

    @Test
    void publishDue_postAnotherInstanceOrTheOwnerAlreadyTook_isSkipped() {
        UUID id = UUID.randomUUID();
        when(postRepository.findDueScheduledIds(50)).thenReturn(List.of(id));
        when(postRepository.claimDueScheduledPost(id)).thenReturn(Optional.empty());

        int published = publisher.publishDue(50);

        assertThat(published).isZero();
        verify(postPublicationService, never()).validate(any());
        verify(postPublicationService, never()).publish(any(), any());
    }

    @Test
    void publishDue_postThatCannotBePublished_goesBackToDraftsAndIsCountedAsFailed() {
        UUID id = UUID.randomUUID();
        Post post = duePost(id);
        PostPublicationException refusal =
                new PostPublicationException(
                        Reason.BANNED_HASHTAG, ApiErrorCode.POST_BANNED_HASHTAG);
        when(postRepository.findDueScheduledIds(50)).thenReturn(List.of(id));
        when(postRepository.claimDueScheduledPost(id)).thenReturn(Optional.of(post));
        doThrow(refusal).when(postPublicationService).validate(post);

        int published = publisher.publishDue(50);

        assertThat(published).isZero();
        verify(postPublicationService).failSchedule(post, refusal);
        verify(postPublicationService, never()).publish(any(), any());
        assertThat(meters.counter("luvax.posts.scheduled.failed").count()).isEqualTo(1.0);
    }

    @Test
    void publishDue_unexpectedFailureOnOnePost_doesNotStopTheRestOfTheBatch() {
        UUID broken = UUID.randomUUID();
        UUID fine = UUID.randomUUID();
        Post finePost = duePost(fine);
        when(postRepository.findDueScheduledIds(50)).thenReturn(List.of(broken, fine));
        when(postRepository.claimDueScheduledPost(broken))
                .thenThrow(new IllegalStateException("database went away"));
        when(postRepository.claimDueScheduledPost(fine)).thenReturn(Optional.of(finePost));

        int published = publisher.publishDue(50);

        assertThat(published).isEqualTo(1);
        verify(postPublicationService).publish(finePost, Source.SCHEDULER);
    }

    @Test
    void publishDue_nothingDue_doesNothing() {
        when(postRepository.findDueScheduledIds(50)).thenReturn(List.of());

        assertThat(publisher.publishDue(50)).isZero();

        verify(postRepository, never()).claimDueScheduledPost(any());
    }
}
