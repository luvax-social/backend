package com.app.modules.social.api;

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

/** OpenAPI contract for asking to see less of an author in recommendations. */
@Tag(name = "Feed Preferences", description = "Show fewer posts from an account")
@RequestMapping(ApiConstants.Social.ROOT)
public interface FeedPreferenceApi {

    @Operation(
            summary = "Show fewer posts from an account",
            description =
                    "Keeps this author's posts out of the caller's For You and Explore feeds. The"
                            + " Following feed, profile pages, search and every other surface are"
                            + " unchanged, and the author is not told. Repeating the call changes"
                            + " nothing.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "204",
                description = "Request recorded, or already recorded"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description =
                        "The author is the caller, does not exist, or is in a block relationship"
                                + " with the caller; all three answer identically",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @AuthenticationRequiredResponse
    @PostMapping(ApiConstants.Social.SHOW_FEWER)
    ResponseEntity<Void> showFewer(@PathVariable("authorId") UUID authorId);

    @Operation(
            summary = "Stop showing fewer posts from an account",
            description =
                    "Removes the caller's show-fewer request. Removing one that does not exist"
                            + " succeeds and changes nothing.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "204",
                description = "Request removed, or none existed")
    })
    @AuthenticationRequiredResponse
    @DeleteMapping(ApiConstants.Social.SHOW_FEWER)
    ResponseEntity<Void> undoShowFewer(@PathVariable("authorId") UUID authorId);
}
