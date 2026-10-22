package com.app.modules.post.service.impl;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.common.response.ViewerRelationshipResponse;
import com.app.modules.post.entity.Post;
import com.app.modules.post.entity.PostCollaborator;
import com.app.modules.post.enums.CollaborationStatus;
import com.app.modules.post.repository.PostCollaboratorRepository;
import com.app.modules.post.repository.PostUserRepository;
import com.app.modules.post.service.PostVisibilityService;
import com.app.modules.social.service.SocialService;
import com.app.modules.users.entity.User;

@Service
public class PostVisibilityServiceImpl implements PostVisibilityService {

    private final PostUserRepository postUserRepository;
    private final SocialService socialService;
    private final PostCollaboratorRepository postCollaboratorRepository;

    public PostVisibilityServiceImpl(
            PostUserRepository postUserRepository,
            SocialService socialService,
            PostCollaboratorRepository postCollaboratorRepository) {
        this.postUserRepository = postUserRepository;
        this.socialService = socialService;
        this.postCollaboratorRepository = postCollaboratorRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isVisibleTo(UUID viewerId, Post post) {
        UUID ownerId = post.getUserId();
        if (viewerId.equals(ownerId)) {
            return true;
        }
        // A co-author is an author of the post and always sees it. For everyone else a block with
        // any author hides it, because the viewer would otherwise meet an account that blocked
        // them, or that they blocked, through a post the other account holds a share of.
        List<UUID> coAuthorIds = postCollaboratorRepository.findAcceptedUserIds(post.getId());
        if (coAuthorIds.contains(viewerId)) {
            return true;
        }
        if (socialService.isBlockedBetween(viewerId, ownerId)) {
            return false;
        }
        for (UUID coAuthorId : coAuthorIds) {
            if (socialService.isBlockedBetween(viewerId, coAuthorId)) {
                return false;
            }
        }
        // A soft-deleted owner hides all of their content even though the post rows remain live.
        User owner = postUserRepository.findByIdAndDeletedAtIsNull(ownerId).orElse(null);
        if (owner == null) {
            return false;
        }
        if (owner.isPrivate()) {
            return socialService.hasAcceptedFollow(viewerId, ownerId);
        }
        return true;
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> filterVisibleOwnerIds(UUID viewerId, Collection<UUID> ownerIds) {
        if (ownerIds.isEmpty()) {
            return Set.of();
        }
        // Three fixed-count batched round trips (blocks, follows, owners) replace what would
        // otherwise be up to three queries per candidate owner if isVisibleTo were called in a
        // loop. Block direction comes from findBlockedEitherDirection, not loadRelationships: the
        // latter is scoped to the stealth-block UI surface and deliberately omits the incoming
        // direction, which this internal visibility decision still needs in full.
        Set<UUID> blockedEitherDirection = socialService.findBlockedEitherDirection(viewerId);
        Map<UUID, ViewerRelationshipResponse> relationships =
                socialService.loadRelationships(viewerId, ownerIds);
        List<User> owners = postUserRepository.findAllByIdInAndDeletedAtIsNull(ownerIds);
        Map<UUID, User> ownersById =
                owners.stream().collect(Collectors.toMap(User::getId, Function.identity()));

        Set<UUID> visible = new HashSet<>();
        for (UUID ownerId : ownerIds) {
            if (viewerId.equals(ownerId)) {
                visible.add(ownerId);
                continue;
            }
            if (blockedEitherDirection.contains(ownerId)) {
                continue;
            }
            // A soft-deleted owner is absent from ownersById and hides all of their content.
            User owner = ownersById.get(ownerId);
            if (owner == null) {
                continue;
            }
            ViewerRelationshipResponse relationship =
                    relationships.getOrDefault(ownerId, ViewerRelationshipResponse.NONE);
            if (owner.isPrivate() && !relationship.isFollowing()) {
                continue;
            }
            visible.add(ownerId);
        }
        return visible;
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> filterVisiblePostIds(UUID viewerId, Map<UUID, UUID> ownerByPostId) {
        if (ownerByPostId.isEmpty()) {
            return Set.of();
        }
        Set<UUID> visibleOwners = filterVisibleOwnerIds(viewerId, ownerByPostId.values());
        Map<UUID, Set<UUID>> coAuthorsByPost = new HashMap<>();
        for (PostCollaborator row :
                postCollaboratorRepository.findByPostIdIn(ownerByPostId.keySet())) {
            if (row.getStatus() == CollaborationStatus.ACCEPTED) {
                coAuthorsByPost
                        .computeIfAbsent(row.getPostId(), id -> new HashSet<>())
                        .add(row.getUserId());
            }
        }
        // Read only when some candidate has a co-author, so a page of ordinary posts pays nothing.
        Set<UUID> blocked =
                coAuthorsByPost.isEmpty()
                        ? Set.of()
                        : socialService.findBlockedEitherDirection(viewerId);
        Set<UUID> visible = new HashSet<>();
        for (Map.Entry<UUID, UUID> entry : ownerByPostId.entrySet()) {
            UUID postId = entry.getKey();
            UUID ownerId = entry.getValue();
            Set<UUID> coAuthors = coAuthorsByPost.getOrDefault(postId, Set.of());
            if (viewerId.equals(ownerId) || coAuthors.contains(viewerId)) {
                visible.add(postId);
                continue;
            }
            if (!visibleOwners.contains(ownerId)) {
                continue;
            }
            if (coAuthors.stream().anyMatch(blocked::contains)) {
                continue;
            }
            visible.add(postId);
        }
        return visible;
    }
}
