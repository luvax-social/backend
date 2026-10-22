package com.app.modules.post.service.impl;

import java.util.List;
import java.util.UUID;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.app.modules.post.repository.PostCollaboratorRepository;
import com.app.modules.post.repository.PostRepository;
import com.app.modules.post.repository.PostUserTagRepository;
import com.app.modules.social.event.UserBlockedEvent;

import lombok.RequiredArgsConstructor;

/**
 * Removes what ties two accounts together in posts when one blocks the other.
 *
 * <p>Runs inside the blocking transaction, because the event is published synchronously by the
 * social module, so a block and the end of every tag and co-authorship between the pair commit
 * together. A post one of them co-authors for the other has its search document refreshed, since
 * its author list changed.
 */
@Component
@RequiredArgsConstructor
public class PostBlockCleanupListener {

    private final PostUserTagRepository postUserTagRepository;
    private final PostCollaboratorRepository postCollaboratorRepository;
    private final PostRepository postRepository;
    private final PostIndexEventPublisher postIndexEventPublisher;

    @EventListener
    public void onUserBlocked(UserBlockedEvent event) {
        UUID blocker = event.blockerId();
        UUID blocked = event.blockedId();
        List<UUID> affectedPostIds =
                postCollaboratorRepository.findAcceptedPostIdsBetween(blocker, blocked);
        postCollaboratorRepository.removeBetween(blocker, blocked);
        postUserTagRepository.deleteBetween(blocker, blocked);
        for (UUID postId : affectedPostIds) {
            postRepository
                    .findByIdAndDeletedAtIsNull(postId)
                    .ifPresent(postIndexEventPublisher::enqueueUpsert);
        }
    }
}
