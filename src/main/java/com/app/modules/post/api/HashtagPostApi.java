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
import com.app.modules.post.dto.response.PostResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Posts-by-hashtag read surface.
 *
 * <p>Routed under {@code /hashtags} but declared in the post module, because it returns posts and
 * reuses the post visibility chain and response assembler. Keeping it out of the hashtag module
 * avoids a bidirectional module dependency, since the post module already depends on the hashtag
 * module for banned-name checks at write time.
 */
@Tag(name = "Hashtags", description = "Hashtag discovery surfaces")
public interface HashtagPostApi {

    @Operation(
            summary = "List posts carrying a hashtag",
            description =
                    "Published posts tagged with the hashtag, newest first. Served from"
                            + " Elasticsearch, degrading to a PostgreSQL join through"
                            + " post_hashtags when the search tier is unavailable; both tiers share"
                            + " one offset cursor. A banned or deleted hashtag is refused with 404"
                            + " HASHTAG_UNAVAILABLE rather than answered with an empty page, so a"
                            + " client can distinguish an unavailable hashtag from one with no"
                            + " posts yet.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Cursor page of visible posts carrying the hashtag"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description =
                        "PAGINATION_DEPTH_EXCEEDED when offset plus limit would exceed the 10000"
                                + " result window, or INVALID_CURSOR when the cursor is malformed",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description =
                        "HASHTAG_NOT_FOUND when no hashtag carries the id, or HASHTAG_UNAVAILABLE"
                                + " when it is banned or deleted",
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
    @GetMapping(ApiConstants.Hashtags.POSTS)
    ResponseEntity<ApiResponse<CursorPageResponse<PostResponse>>> listPostsByHashtag(
            @Parameter(description = "Hashtag identifier") @PathVariable("hashtagId")
                    UUID hashtagId,
            @Parameter(description = "Opaque cursor from a previous page; omit for the first page")
                    @RequestParam(value = "cursor", required = false)
                    String cursor,
            @Parameter(description = "Maximum posts per page")
                    @RequestParam(value = "limit", defaultValue = "20")
                    @Min(1)
                    @Max(100)
                    int limit);
}
