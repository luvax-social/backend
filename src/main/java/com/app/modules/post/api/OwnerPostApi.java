package com.app.modules.post.api;

import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import com.app.common.ApiConstants;
import com.app.common.config.openapi.AuthenticationRequiredResponse;
import com.app.common.config.openapi.CursorErrorResponses;
import com.app.common.response.ApiResponse;
import com.app.common.response.CursorPageResponse;
import com.app.modules.post.dto.response.CollaborationInviteResponse;
import com.app.modules.post.dto.response.OwnerPostCountsResponse;
import com.app.modules.post.dto.response.PostResponse;
import com.app.modules.post.dto.response.TagRequestResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Reads that belong to the signed-in user rather than to one post: their drafts, scheduled and
 * archived posts, the tags and invitations waiting for an answer, and the posts they are tagged in.
 *
 * <p>Routed under {@code /users} but declared in the post module, because every one returns posts
 * and reuses the post response assembler; the users module has no mapping on these paths.
 */
@Tag(
        name = "Post Creation",
        description =
                "Drafts, alt text, places, people tags and collaborative posts on a post the"
                        + " caller owns or is involved in")
public interface OwnerPostApi {

    @Operation(
            summary = "List the caller's drafts, scheduled posts or archived posts",
            description =
                    "Cursor-paginated posts of the caller in one unpublished state. Drafts and"
                            + " archived posts come last-changed first; scheduled posts come"
                            + " soonest-due first. The status is part of the cursor, so a cursor"
                            + " cannot be replayed against another status.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Cursor page of the caller's posts in the requested state"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "429",
                description = "Rate limit exceeded",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @AuthenticationRequiredResponse
    @CursorErrorResponses
    @GetMapping(ApiConstants.Users.ME_POSTS)
    ResponseEntity<ApiResponse<CursorPageResponse<PostResponse>>> listOwnerPosts(
            @Parameter(
                            description = "Which unpublished state to list",
                            required = true,
                            schema =
                                    @Schema(
                                            type = "string",
                                            allowableValues = {"draft", "scheduled", "archived"}))
                    @RequestParam("status")
                    String status,
            @Parameter(description = "Opaque cursor from a previous page; omit for the first page")
                    @RequestParam(value = "cursor", required = false)
                    String cursor,
            @Parameter(description = "Maximum posts per page")
                    @RequestParam(value = "limit", defaultValue = "20")
                    @Min(1)
                    @Max(100)
                    int limit);

    @Operation(
            summary = "Count the caller's unpublished posts",
            description =
                    "How many drafts, scheduled posts and archived posts the caller holds, and the"
                            + " limit on drafts and scheduled posts together.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "The counts and the limit"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "429",
                description = "Rate limit exceeded",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @AuthenticationRequiredResponse
    @GetMapping(ApiConstants.Users.ME_POST_COUNTS)
    ResponseEntity<ApiResponse<OwnerPostCountsResponse>> getOwnerPostCounts();

    @Operation(
            summary = "List the tags of the caller that wait for approval",
            description =
                    "Cursor-paginated pending tags of the caller on published posts, newest first."
                            + " A tag the caller's limits routed to the hidden requests is listed"
                            + " here too; the caller was just not notified of it.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Cursor page of tag requests"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "429",
                description = "Rate limit exceeded",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @AuthenticationRequiredResponse
    @CursorErrorResponses
    @GetMapping(ApiConstants.Users.ME_PENDING_TAGS)
    ResponseEntity<ApiResponse<CursorPageResponse<TagRequestResponse>>> listPendingTags(
            @Parameter(description = "Opaque cursor from a previous page; omit for the first page")
                    @RequestParam(value = "cursor", required = false)
                    String cursor,
            @Parameter(description = "Maximum requests per page")
                    @RequestParam(value = "limit", defaultValue = "20")
                    @Min(1)
                    @Max(100)
                    int limit);

    @Operation(
            summary = "List the invitations to co-author that the caller has not answered",
            description =
                    "Cursor-paginated invitations on published posts, newest first. Accept or"
                            + " decline through the collaboration endpoints of the post.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Cursor page of invitations"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "429",
                description = "Rate limit exceeded",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @AuthenticationRequiredResponse
    @CursorErrorResponses
    @GetMapping(ApiConstants.Users.ME_COLLABORATION_INVITES)
    ResponseEntity<ApiResponse<CursorPageResponse<CollaborationInviteResponse>>>
            listCollaborationInvites(
                    @Parameter(
                                    description =
                                            "Opaque cursor from a previous page; omit for the"
                                                    + " first page")
                            @RequestParam(value = "cursor", required = false)
                            String cursor,
                    @Parameter(description = "Maximum invitations per page")
                            @RequestParam(value = "limit", defaultValue = "20")
                            @Min(1)
                            @Max(100)
                            int limit);

    @Operation(
            summary = "List the published posts a user is tagged in",
            description =
                    "The Tagged tab of a profile: published posts in which the user holds an"
                            + " approved tag, newest first, limited to posts the caller may see. A"
                            + " private account needs an accepted follow, and a block in either"
                            + " direction answers as an unknown user.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Cursor page of posts"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "The account is private and the caller does not follow it",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "No such user, or a block exists between the caller and the user",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "429",
                description = "Rate limit exceeded",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @AuthenticationRequiredResponse
    @CursorErrorResponses
    @GetMapping(ApiConstants.Users.TAGGED_POSTS)
    ResponseEntity<ApiResponse<CursorPageResponse<PostResponse>>> listTaggedPosts(
            @Parameter(description = "The tagged person") @PathVariable("userId") UUID userId,
            @Parameter(description = "Opaque cursor from a previous page; omit for the first page")
                    @RequestParam(value = "cursor", required = false)
                    String cursor,
            @Parameter(description = "Maximum posts per page")
                    @RequestParam(value = "limit", defaultValue = "20")
                    @Min(1)
                    @Max(100)
                    int limit);
}
