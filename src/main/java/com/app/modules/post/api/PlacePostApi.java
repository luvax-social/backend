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
 * Posts-at-a-place read surface: the location page.
 *
 * <p>Routed under {@code /places} but declared in the post module, because it returns posts and
 * reuses the post visibility chain and response assembler, the same arrangement as the
 * posts-by-hashtag read.
 */
@Tag(name = "Places", description = "Place registry, search and location pages")
public interface PlacePostApi {

    @Operation(
            summary = "List posts tagged at a place",
            description =
                    "Published posts tagged at the place, newest publication first. Served from"
                            + " Elasticsearch, degrading to PostgreSQL when the search tier is"
                            + " unavailable; both tiers share one offset cursor. A banned or"
                            + " deleted place is refused with 404 PLACE_UNAVAILABLE rather than"
                            + " answered with an empty page, so a client can tell an unavailable"
                            + " place from one with no posts yet. A post whose owner or co-author"
                            + " is blocked with the caller is left out.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Cursor page of visible posts tagged at the place"),
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
                        "PLACE_NOT_FOUND when no place carries the id, or PLACE_UNAVAILABLE when it"
                                + " is banned or deleted",
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
    @GetMapping(ApiConstants.Places.POSTS)
    ResponseEntity<ApiResponse<CursorPageResponse<PostResponse>>> listPostsByPlace(
            @Parameter(description = "Place identifier") @PathVariable("placeId") UUID placeId,
            @Parameter(description = "Opaque cursor from a previous page; omit for the first page")
                    @RequestParam(value = "cursor", required = false)
                    String cursor,
            @Parameter(description = "Maximum posts per page")
                    @RequestParam(value = "limit", defaultValue = "20")
                    @Min(1)
                    @Max(100)
                    int limit);
}
