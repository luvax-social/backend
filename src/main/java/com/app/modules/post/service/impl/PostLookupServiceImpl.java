package com.app.modules.post.service.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.modules.post.dto.response.FeedPostResponse;
import com.app.modules.post.entity.Post;
import com.app.modules.post.entity.PostCollaborator;
import com.app.modules.post.enums.CollaborationStatus;
import com.app.modules.post.repository.PostCollaboratorRepository;
import com.app.modules.post.repository.PostRepository;
import com.app.modules.post.service.PostLookupService;

@Service
public class PostLookupServiceImpl implements PostLookupService {

    private final PostRepository postRepository;
    private final PostCollaboratorRepository postCollaboratorRepository;
    private final PostResponseAssembler postResponseAssembler;

    public PostLookupServiceImpl(
            PostRepository postRepository,
            PostCollaboratorRepository postCollaboratorRepository,
            PostResponseAssembler postResponseAssembler) {
        this.postRepository = postRepository;
        this.postCollaboratorRepository = postCollaboratorRepository;
        this.postResponseAssembler = postResponseAssembler;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Post> findActiveByIds(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        // Fetch-join media so returned entities stay usable after this transaction closes;
        // the ranking pipeline assembles them outside any persistence session.
        return postRepository.findAllWithMediaByIdIn(ids);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, List<UUID>> findAcceptedCoAuthors(Collection<UUID> postIds) {
        if (postIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<UUID>> result = new HashMap<>();
        for (PostCollaborator row : postCollaboratorRepository.findByPostIdIn(postIds)) {
            if (row.getStatus() == CollaborationStatus.ACCEPTED) {
                result.computeIfAbsent(row.getPostId(), id -> new ArrayList<>())
                        .add(row.getUserId());
            }
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public List<FeedPostResponse> assembleFeed(UUID viewerId, List<Post> posts) {
        return postResponseAssembler.assembleFeed(viewerId, posts);
    }
}
