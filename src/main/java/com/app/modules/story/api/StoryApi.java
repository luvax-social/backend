package com.app.modules.story.api;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import com.app.common.ApiConstants;
import com.app.common.config.openapi.AuthenticationRequiredResponse;
import com.app.common.config.openapi.CursorErrorResponses;
import com.app.common.config.openapi.MalformedBodyErrorResponses;
import com.app.common.response.ApiResponse;
import com.app.common.response.CursorPageResponse;
import com.app.modules.story.dto.request.CreateStoryRequest;
import com.app.modules.story.dto.response.StoryFeedItemResponse;
import com.app.modules.story.dto.response.StoryLikeActionResponse;
import com.app.modules.story.dto.response.StoryResponse;
import com.app.modules.story.dto.response.StoryViewActionResponse;
import com.app.modules.story.dto.response.StoryViewerResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/** OpenAPI contract for story creation, feed tray, reads, and deletion. */
@Tag(name = "Stories", description = "Ephemeral 24-hour stories: creation, feed tray, and reads")
public interface StoryApi {

    @Operation(
            summary = "Create a story",
            description =
                    "Creates a story backed by a media asset the caller owns. The story type is"
                            + " derived from the asset's media type; expiry comes from the"
                            + " story_duration_hours system setting. Requires authentication.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "201",
                description = "Story created"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Media asset not owned by the caller",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "Media asset not found",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @MalformedBodyErrorResponses
    @AuthenticationRequiredResponse
    @PostMapping(ApiConstants.Stories.ROOT)
    ResponseEntity<ApiResponse<StoryResponse>> createStory(
            @Valid @RequestBody CreateStoryRequest request);

    @Operation(
            summary = "Get the story feed tray",
            description =
                    "Returns the caller plus followed authors that have active stories, grouped by"
                            + " author. The caller's own entry is first; remaining authors are"
                            + " ordered unseen-first, then by newest story. Requires"
                            + " authentication.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Story feed tray; empty when nobody has active stories")
    })
    @AuthenticationRequiredResponse
    @GetMapping(ApiConstants.Stories.ROOT + ApiConstants.Stories.FEED)
    ResponseEntity<ApiResponse<List<StoryFeedItemResponse>>> getStoryFeed();

    @Operation(
            summary = "Discover stories",
            description =
                    "Returns active stories from suggested accounts the caller does not follow, for"
                            + " the in-feed story card, grouped by author and ordered by suggestion"
                            + " rank. Private accounts never appear. Blocked accounts in either"
                            + " direction, dismissed suggestions, accounts that opted out of being"
                            + " suggested and already-followed accounts are all excluded, the last"
                            + " because the caller's own tray already carries them. Requires"
                            + " authentication.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Discoverable stories; empty when nothing qualifies")
    })
    @AuthenticationRequiredResponse
    @GetMapping(ApiConstants.Stories.ROOT + ApiConstants.Stories.DISCOVERY)
    ResponseEntity<ApiResponse<List<StoryFeedItemResponse>>> discoverStories(
            @Parameter(description = "How many authors to return; maximum 20")
                    @RequestParam(defaultValue = "8")
                    @Min(1)
                    @Max(20)
                    int limit);

    @Operation(
            summary = "List a user's active stories",
            description =
                    "Returns the target user's active stories in playback order (oldest first)."
                            + " Private accounts require an accepted follow. Requires"
                            + " authentication.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Active stories of the target user"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Blocked, or private account without an accepted follow",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "Target user not found",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @AuthenticationRequiredResponse
    @GetMapping(ApiConstants.Stories.ROOT + ApiConstants.Stories.USER_STORIES)
    ResponseEntity<ApiResponse<List<StoryResponse>>> listUserStories(
            @PathVariable("userId") UUID userId);

    @Operation(
            summary = "Get a single story",
            description =
                    "Returns one active story. Expired or deleted stories are indistinguishable"
                            + " from missing ones. Owners receive the view count; other viewers"
                            + " receive their seen flag. Requires authentication.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "The story"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Story not visible to the caller",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "Story missing, deleted, or expired",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @AuthenticationRequiredResponse
    @GetMapping(ApiConstants.Stories.ROOT + ApiConstants.Stories.BY_ID)
    ResponseEntity<ApiResponse<StoryResponse>> getStoryById(@PathVariable("storyId") UUID storyId);

    @Operation(
            summary = "Delete an owned story",
            description =
                    "Soft-deletes a story owned by the caller. Expired-but-live stories remain"
                            + " deletable. Requires authentication as the owner.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Story deleted"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Story owned by another user",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "Story missing or already deleted",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @AuthenticationRequiredResponse
    @DeleteMapping(ApiConstants.Stories.ROOT + ApiConstants.Stories.BY_ID)
    ResponseEntity<ApiResponse<Void>> deleteStory(@PathVariable("storyId") UUID storyId);

    @Operation(
            summary = "Record a story view",
            description =
                    "Records that the caller viewed the story, deduplicated by (story, viewer)."
                            + " Owner views and repeat views are idempotent no-ops that still return"
                            + " 200 with the current view count. Requires authentication.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "View recorded (or already present)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "Story missing, deleted, expired, or not visible to the caller",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @AuthenticationRequiredResponse
    @PostMapping(ApiConstants.Stories.ROOT + ApiConstants.Stories.VIEWS)
    ResponseEntity<ApiResponse<StoryViewActionResponse>> recordView(
            @PathVariable("storyId") UUID storyId);

    @Operation(
            summary = "List a story's viewers",
            description =
                    "Cursor-paginated viewers of the story, newest view first. Owner-only."
                            + " Requires authentication as the owner.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Cursor page of viewers"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Requester is not the story owner",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "Story missing, deleted, or expired",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @CursorErrorResponses
    @AuthenticationRequiredResponse
    @GetMapping(ApiConstants.Stories.ROOT + ApiConstants.Stories.VIEWS)
    ResponseEntity<ApiResponse<CursorPageResponse<StoryViewerResponse>>> listViewers(
            @PathVariable("storyId") UUID storyId,
            @Parameter(description = "Opaque cursor from the previous page")
                    @RequestParam(value = "cursor", required = false)
                    String cursor,
            @Parameter(description = "Page size, 1-100, default 20")
                    @RequestParam(value = "limit", defaultValue = "20")
                    @Min(1)
                    @Max(100)
                    int limit);

    @Operation(
            summary = "Like a story",
            description =
                    "Likes an active story visible to the caller. Self-like is permitted. Liking"
                            + " an already-liked story returns a conflict. Requires"
                            + " authentication.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "201",
                description = "Story liked"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "Story missing, deleted, expired, or not visible to the caller",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description = "Story already liked",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @AuthenticationRequiredResponse
    @PostMapping(ApiConstants.Stories.ROOT + ApiConstants.Stories.LIKES)
    ResponseEntity<ApiResponse<StoryLikeActionResponse>> likeStory(
            @PathVariable("storyId") UUID storyId);

    @Operation(
            summary = "Unlike a story",
            description = "Removes the caller's like. Unliking a story that is not liked fails.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Story unliked"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "Story or like not found",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @AuthenticationRequiredResponse
    @DeleteMapping(ApiConstants.Stories.ROOT + ApiConstants.Stories.LIKES)
    ResponseEntity<ApiResponse<StoryLikeActionResponse>> unlikeStory(
            @PathVariable("storyId") UUID storyId);
}
