package com.app.modules.post.api;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import com.app.common.ApiConstants;
import com.app.common.config.openapi.AuthenticationRequiredResponse;
import com.app.common.response.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/** OpenAPI contract for the viewer's negative feedback on a post. */
@Tag(name = "Post Feedback", description = "Mark and unmark a post as not interesting")
@RequestMapping(ApiConstants.Posts.ROOT)
public interface PostFeedbackApi {

    @Operation(
            summary = "Mark a post as not interested",
            description =
                    "Removes this post from the viewer's Following, For You and Explore feeds and"
                            + " records that the viewer wants to see less like it. Repeating the call"
                            + " changes nothing. The author is not told.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "204",
                description = "Post marked, or already marked"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "POST_FEEDBACK_SELF: the post is the viewer's own",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description =
                        "POST_NOT_FOUND: the post does not exist or is not visible to the viewer",
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
    @PostMapping(ApiConstants.Posts.NOT_INTERESTED)
    ResponseEntity<Void> markNotInterested(@PathVariable("postId") UUID postId);

    @Operation(
            summary = "Take back a not-interested mark",
            description =
                    "Removes the viewer's mark on the post. Removing a mark that does not exist"
                            + " succeeds and changes nothing.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "204",
                description = "Mark removed, or none existed"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "429",
                description = "Rate limit exceeded",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @AuthenticationRequiredResponse
    @DeleteMapping(ApiConstants.Posts.NOT_INTERESTED)
    ResponseEntity<Void> undoNotInterested(@PathVariable("postId") UUID postId);
}
