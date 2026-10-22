package com.app.modules.post.service.impl;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.app.modules.post.entity.Post;
import com.app.modules.post.repository.PostRepository;
import com.app.modules.post.service.PostPublicationException;
import com.app.modules.post.service.PostPublicationService;
import com.app.modules.post.service.PostPublicationService.Source;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;

/**
 * Publishes the scheduled posts that have come due, one transaction per post.
 *
 * <p>A pass reads the ids of due posts without locking, then handles each id on its own. The
 * predicate is repeated under {@code FOR UPDATE SKIP LOCKED}, so a post the owner just rescheduled,
 * archived or deleted, or a moderator just removed, is not claimed, and a second instance running
 * the same pass gets nothing for a post the first holds. That is what makes a post publish at most
 * once without a distributed lock.
 */
@Slf4j
@Component
public class ScheduledPostPublisher {

    /** What became of one due post in a pass. */
    enum Outcome {
        PUBLISHED,
        FAILED,
        SKIPPED
    }

    private final PostRepository postRepository;
    private final PostPublicationService postPublicationService;
    private final TransactionTemplate perPostTransaction;
    private final Counter published;
    private final Counter failed;
    private final Timer lag;

    public ScheduledPostPublisher(
            PostRepository postRepository,
            PostPublicationService postPublicationService,
            PlatformTransactionManager transactionManager,
            MeterRegistry meterRegistry) {
        this.postRepository = postRepository;
        this.postPublicationService = postPublicationService;
        // REQUIRES_NEW isolates each post: one that cannot be published, or whose publication
        // throws, must not roll back the posts that were already published earlier in the pass.
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.perPostTransaction = template;
        this.published = Counter.builder("luvax.posts.scheduled.published").register(meterRegistry);
        this.failed = Counter.builder("luvax.posts.scheduled.failed").register(meterRegistry);
        this.lag = Timer.builder("luvax.posts.scheduled.lag").register(meterRegistry);
    }

    /**
     * Publishes up to {@code batchSize} due posts.
     *
     * @param batchSize the most posts to try in this pass
     * @return how many were published
     */
    public int publishDue(int batchSize) {
        List<UUID> due = postRepository.findDueScheduledIds(batchSize);
        int publishedCount = 0;
        for (UUID postId : due) {
            try {
                if (publishOne(postId) == Outcome.PUBLISHED) {
                    publishedCount++;
                }
            } catch (RuntimeException e) {
                // The post stays scheduled and the next pass tries it again; one failing post must
                // not stop the rest of the batch.
                log.error("Scheduled publication failed unexpectedly: postId={}", postId, e);
            }
        }
        return publishedCount;
    }

    Outcome publishOne(UUID postId) {
        return perPostTransaction.execute(status -> publishClaimed(postId));
    }

    private Outcome publishClaimed(UUID postId) {
        Optional<Post> claimed = postRepository.claimDueScheduledPost(postId);
        if (claimed.isEmpty()) {
            return Outcome.SKIPPED;
        }
        Post post = claimed.get();
        OffsetDateTime dueAt = post.getScheduledAt();
        try {
            postPublicationService.validate(post);
        } catch (PostPublicationException failure) {
            postPublicationService.failSchedule(post, failure);
            failed.increment();
            return Outcome.FAILED;
        }
        postPublicationService.publish(post, Source.SCHEDULER);
        published.increment();
        if (dueAt != null) {
            lag.record(Duration.between(dueAt, OffsetDateTime.now()).abs());
        }
        return Outcome.PUBLISHED;
    }
}
