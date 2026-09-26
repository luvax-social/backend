package com.app.modules.comment.api;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import com.app.common.ApiConstants;
import com.app.common.config.openapi.AuthenticationRequiredResponse;
import com.app.common.config.openapi.CursorErrorResponses;
import com.app.common.config.openapi.MalformedBodyErrorResponses;
import com.app.common.response.ApiResponse;
import com.app.common.response.CursorPageResponse;
import com.app.modules.comment.dto.request.CreateCommentRequest;
import com.app.modules.comment.dto.request.EditCommentRequest;
import com.app.modules.comment.dto.response.CommentContextResponse;
import com.app.modules.comment.dto.response.CommentDeletionScopeResponse;
import com.app.modules.comment.dto.response.CommentResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/** OpenAPI contract for comment creation, nested replies, likes, edit, and soft delete. */
@Tag(
        name = "Comments",
        description = "Comment creation, nested replies, likes, edit and soft delete")
public interface CommentApi {

    @Operation(
            summary = "Create a comment or reply",
            description =
                    "Creates a top-level comment or a reply (when parentId is set) on the path post."
                            + " The body post id must match the path post id. Content is normalized and"
                            + " moderated synchronously. An optional Idempotency-Key header replays the"
                            + " original response on retry.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "415",
                description = "Request body was sent with an unsupported Content-Type",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "201",
                description = "Comment created"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "Validation failure, path/body mismatch, or depth exceeded",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Commenting not permitted on this post",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "Post or parent comment not found",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description = "Idempotency key reused with a different payload",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "422",
                description = "Content rejected by moderation",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "429",
                description = "Slow mode active or rate limit exceeded",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @AuthenticationRequiredResponse
    @PostMapping(ApiConstants.Posts.ROOT + ApiConstants.Posts.COMMENTS)
    ResponseEntity<ApiResponse<CommentResponse>> createComment(
            @PathVariable("postId") UUID postId,
            @Valid @RequestBody CreateCommentRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey);

    @Operation(
            summary = "List top-level comments for a post",
            description =
                    "Cursor-paginated approved top-level comments, newest first in both sort"
                            + " modes. `sort=top`, the default, precedes the first page with up to"
                            + " three top comments ranked by like count, each flagged with"
                            + " `pinned: true` and additional to the requested page size; those"
                            + " comments are excluded from the chronological body of every page,"
                            + " so each is returned exactly once. `sort=newest` suppresses the"
                            + " block entirely: nothing is prepended, nothing is excluded, and"
                            + " `pinned` is false on every row. Neither mode ranks the paginated"
                            + " stream by like count. A cursor is bound to the mode that issued"
                            + " it and is rejected under the other, because the two modes return"
                            + " different row sets for the same position. Requires"
                            + " authentication; private posts are visible only to the owner and"
                            + " accepted followers.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Cursor page of comments"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Post hidden by a block or a private account",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @CursorErrorResponses
    @AuthenticationRequiredResponse
    @GetMapping(ApiConstants.Posts.ROOT + ApiConstants.Posts.COMMENTS)
    ResponseEntity<ApiResponse<CursorPageResponse<CommentResponse>>> listTopLevelComments(
            @PathVariable("postId") UUID postId,
            @Parameter(
                            description =
                                    "Sort mode. `top` prepends the pinned block of most-liked"
                                            + " comments to the first page; `newest` suppresses"
                                            + " it. Both order the body newest first. An"
                                            + " unrecognised value is rejected rather than"
                                            + " falling back to the default.",
                            schema =
                                    @Schema(
                                            allowableValues = {"top", "newest"},
                                            defaultValue = "top"))
                    @RequestParam(value = "sort", required = false)
                    String sort,
            @Parameter(
                            description =
                                    "Opaque cursor from the previous page, valid only under the"
                                            + " sort mode that issued it")
                    @RequestParam(value = "cursor", required = false)
                    String cursor,
            @Parameter(description = "Page size (1–100, default 20)")
                    @RequestParam(value = "limit", defaultValue = "20")
                    @Min(1)
                    @Max(100)
                    int limit);

    @Operation(
            summary = "List replies to a comment",
            description =
                    "Cursor-paginated approved direct replies to a comment, newest first. Requires"
                            + " authentication; private posts are visible only to the owner and accepted"
                            + " followers.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Cursor page of replies"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Post hidden by a block or a private account",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "Parent comment not found",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @CursorErrorResponses
    @AuthenticationRequiredResponse
    @GetMapping(ApiConstants.Comments.ROOT + ApiConstants.Comments.REPLIES)
    ResponseEntity<ApiResponse<CursorPageResponse<CommentResponse>>> listReplies(
            @PathVariable("commentId") UUID commentId,
            @Parameter(description = "Opaque cursor from the previous page")
                    @RequestParam(value = "cursor", required = false)
                    String cursor,
            @Parameter(description = "Page size (1–100, default 20)")
                    @RequestParam(value = "limit", defaultValue = "20")
                    @Min(1)
                    @Max(100)
                    int limit);

    @Operation(
            summary = "Get a comment in its thread",
            description =
                    "Returns the comment with every ancestor above it, top-level first, so a"
                            + " notification can open a reply wherever it sits. Applies the list"
                            + " endpoints' visibility rules to every comment in the chain.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "The thread down to the comment"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description =
                        "COMMENT_NOT_FOUND: the comment or an ancestor is missing, deleted,"
                                + " removed, unapproved or hidden by a block, or the post is"
                                + " not visible to the viewer",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @AuthenticationRequiredResponse
    @GetMapping(ApiConstants.Comments.ROOT + ApiConstants.Comments.CONTEXT)
    ResponseEntity<ApiResponse<CommentContextResponse>> getContext(
            @PathVariable("commentId") UUID commentId);

    @Operation(
            summary = "Edit a comment",
            description = "Replaces the body of an owned comment; content is re-moderated.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Updated comment"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Requester is not the comment owner",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "Comment not found",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "422",
                description = "Content rejected by moderation",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @MalformedBodyErrorResponses
    @AuthenticationRequiredResponse
    @PatchMapping(ApiConstants.Comments.ROOT + ApiConstants.Comments.BY_ID)
    ResponseEntity<ApiResponse<CommentResponse>> editComment(
            @PathVariable("commentId") UUID commentId,
            @Valid @RequestBody EditCommentRequest request);

    @Operation(
            summary = "Count the comments a deletion would remove",
            description =
                    "Reports how many comments deleting this comment would soft-delete, counting"
                            + " the comment itself plus every descendant at any depth and excluding"
                            + " descendants already soft-deleted. Intended for the confirmation"
                            + " dialogue shown before the delete, because `replyCount` counts only"
                            + " direct replies and understates the scope of a subtree removal."
                            + " The number is an estimate, not a reservation: the subtree can"
                            + " change between this call and the delete, and no lock is taken to"
                            + " prevent that, so treat the count returned by the delete itself as"
                            + " the authoritative figure. Requires the same authority as the"
                            + " delete; anything the caller may not delete is reported as not"
                            + " found rather than forbidden.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Number of comments the deletion would cover"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description =
                        "Comment not found, already deleted, or not deletable by the requester",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @AuthenticationRequiredResponse
    @GetMapping(ApiConstants.Comments.ROOT + ApiConstants.Comments.DELETION_SCOPE)
    ResponseEntity<ApiResponse<CommentDeletionScopeResponse>> getDeletionScope(
            @PathVariable("commentId") UUID commentId);

    @Operation(
            summary = "Delete a comment",
            description =
                    "Soft-deletes a comment and its entire subtree. Owner or admin only; returns"
                            + " the number of comments actually removed, counting the comment"
                            + " itself.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Comment soft-deleted, with the number of comments removed"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Requester is neither the owner nor an admin",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "Comment not found",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @AuthenticationRequiredResponse
    @DeleteMapping(ApiConstants.Comments.ROOT + ApiConstants.Comments.BY_ID)
    ResponseEntity<ApiResponse<CommentDeletionScopeResponse>> deleteComment(
            @PathVariable("commentId") UUID commentId);

    @Operation(
            summary = "Like a comment",
            description =
                    "Adds the caller's like to a comment. Self-like is permitted and produces no"
                            + " notification.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Comment liked"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Post hidden by a block or a private account",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "Comment not found",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description = "Comment already liked",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @AuthenticationRequiredResponse
    @PostMapping(ApiConstants.Comments.ROOT + ApiConstants.Comments.LIKE)
    ResponseEntity<ApiResponse<Void>> likeComment(@PathVariable("commentId") UUID commentId);

    @Operation(
            summary = "Unlike a comment",
            description = "Removes the caller's like from a comment.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Comment unliked"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Post hidden by a block or a private account",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "Comment not found",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description = "Comment has not been liked",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @AuthenticationRequiredResponse
    @DeleteMapping(ApiConstants.Comments.ROOT + ApiConstants.Comments.LIKE)
    ResponseEntity<ApiResponse<Void>> unlikeComment(@PathVariable("commentId") UUID commentId);
}
