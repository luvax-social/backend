package com.app.modules.comment.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.app.common.response.UserSummaryResponse;
import com.app.modules.comment.dto.response.CommentBroadcastResponse;
import com.app.modules.comment.dto.response.CommentResponse;
import com.app.modules.comment.entity.Comment;

/** Maps comment entities to API response DTOs. */
@Mapper(componentModel = "spring")
public interface CommentMapper {

    /**
     * Builds the comment response from the entity, its separately resolved author summary, and the
     * viewer's like and report state.
     *
     * @param comment the source comment; scalar fields map by name
     * @param author the comment author's public summary, resolved in a batch by the service
     * @param isLiked whether the requesting viewer has liked this comment, batch-resolved by the
     *     service
     * @param hasReported whether the requesting viewer has already reported this comment,
     *     batch-resolved by the service
     * @return the comment response with the author embedded and viewer state
     */
    @Mapping(source = "comment.id", target = "id")
    @Mapping(source = "author", target = "author")
    @Mapping(source = "isLiked", target = "isLiked")
    @Mapping(source = "hasReported", target = "hasReported")
    @Mapping(target = "pinned", constant = "false")
    @Mapping(target = "mentions", expression = "java(java.util.List.of())")
    CommentResponse toResponse(
            Comment comment, UserSummaryResponse author, boolean isLiked, boolean hasReported);

    /**
     * Projects a {@link CommentResponse} to its broadcast shape, dropping the viewer-dependent
     * {@code isLiked} and {@code hasReported} fields that cannot be resolved for a blob shared
     * across every subscriber.
     *
     * @param response the REST-shaped response to project
     * @return the broadcast response, identical except for the omitted viewer-dependent fields
     */
    CommentBroadcastResponse toBroadcastResponse(CommentResponse response);
}
