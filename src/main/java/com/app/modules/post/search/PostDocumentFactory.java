package com.app.modules.post.search;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.app.modules.hashtag.service.HashtagService;
import com.app.modules.post.entity.Post;
import com.app.modules.post.entity.PostCollaborator;
import com.app.modules.post.enums.CollaborationStatus;
import com.app.modules.post.mapper.PostMapper;
import com.app.modules.post.place.dto.response.PlaceSummaryResponse;
import com.app.modules.post.place.service.PlaceService;
import com.app.modules.post.repository.PostCollaboratorRepository;

/**
 * Builds the Elasticsearch documents of posts from the source-of-truth rows.
 *
 * <p>The index consumer, the startup seed and the backfill all build documents here, so a document
 * has the same fields whichever path wrote it. The place name is left out when the place was banned
 * or deleted, and the author list holds the owner and every accepted co-author.
 */
@Component
public class PostDocumentFactory {

    private final PostMapper postMapper;
    private final HashtagService hashtagService;
    private final PlaceService placeService;
    private final PostCollaboratorRepository postCollaboratorRepository;

    public PostDocumentFactory(
            PostMapper postMapper,
            HashtagService hashtagService,
            PlaceService placeService,
            PostCollaboratorRepository postCollaboratorRepository) {
        this.postMapper = postMapper;
        this.hashtagService = hashtagService;
        this.placeService = placeService;
        this.postCollaboratorRepository = postCollaboratorRepository;
    }

    /**
     * Builds one document.
     *
     * @param post the post to index
     * @param hashtagIds the hashtag ids associated with the post, in string form
     * @return the document
     */
    public PostDocument build(Post post, List<String> hashtagIds) {
        return buildAll(List.of(post), Map.of(post.getId(), hashtagIds)).get(0);
    }

    /**
     * Builds the documents of a batch of posts with a fixed number of queries.
     *
     * @param posts the posts to index
     * @return one document per post, in the same order
     */
    public List<PostDocument> buildAll(List<Post> posts) {
        List<UUID> ids = posts.stream().map(Post::getId).toList();
        Map<UUID, List<String>> hashtagIds = new HashMap<>();
        hashtagService
                .getHashtagIdsForPosts(ids)
                .forEach(
                        (postId, tagIds) ->
                                hashtagIds.put(
                                        postId, tagIds.stream().map(UUID::toString).toList()));
        return buildAll(posts, hashtagIds);
    }

    private List<PostDocument> buildAll(List<Post> posts, Map<UUID, List<String>> hashtagIds) {
        Collection<UUID> postIds = posts.stream().map(Post::getId).toList();
        Collection<UUID> placeIds =
                posts.stream()
                        .map(Post::getPlaceId)
                        .filter(id -> id != null)
                        .collect(Collectors.toSet());
        Map<UUID, PlaceSummaryResponse> places =
                placeIds.isEmpty() ? Map.of() : placeService.loadActiveSummaries(placeIds);
        Map<UUID, List<UUID>> coAuthors = new HashMap<>();
        for (PostCollaborator row : postCollaboratorRepository.findByPostIdIn(postIds)) {
            if (row.getStatus() == CollaborationStatus.ACCEPTED) {
                coAuthors
                        .computeIfAbsent(row.getPostId(), id -> new ArrayList<>())
                        .add(row.getUserId());
            }
        }
        List<PostDocument> documents = new ArrayList<>(posts.size());
        for (Post post : posts) {
            List<String> authorIds = new ArrayList<>();
            authorIds.add(post.getUserId().toString());
            coAuthors
                    .getOrDefault(post.getId(), List.of())
                    .forEach(id -> authorIds.add(id.toString()));
            PlaceSummaryResponse place =
                    post.getPlaceId() == null ? null : places.get(post.getPlaceId());
            PostDocument document =
                    postMapper.toDocument(
                            post,
                            hashtagIds.getOrDefault(post.getId(), List.of()),
                            place == null ? null : place.name(),
                            authorIds);
            // A banned or deleted place leaves no trace in the index, so a place page and a search
            // by place can never surface the post through it.
            if (place == null) {
                document.setPlaceId(null);
            }
            documents.add(document);
        }
        return documents;
    }
}
