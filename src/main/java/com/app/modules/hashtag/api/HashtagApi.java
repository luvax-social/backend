package com.app.modules.hashtag.api;

import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.app.common.ApiConstants;
import com.app.common.config.openapi.AuthenticationRequiredResponse;
import com.app.common.response.ApiResponse;
import com.app.common.response.CursorPageResponse;
import com.app.common.response.PageResponse;
import com.app.modules.hashtag.dto.response.HashtagDetailResponse;
import com.app.modules.hashtag.dto.response.HashtagResponse;
import com.app.modules.hashtag.dto.response.HashtagTrendingResponse;
import com.app.modules.hashtag.dto.response.TrendingPreviewResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * OpenAPI contract for the hashtag module.
 *
 * <p>Both anonymous operations below declare {@code security = {@SecurityRequirement(name = "")}}
 * rather than {@code security = {}}. A truly empty array is indistinguishable from the annotation
 * attribute's unset default, so springdoc silently falls back to the global {@code bearerAuth}
 * requirement instead of emitting {@code security: []}. A single requirement with an empty scheme
 * name is springdoc's documented idiom for an explicit override to no security.
 */
@Tag(name = "Hashtags", description = "Hashtag search and trending endpoints")
@RequestMapping(ApiConstants.Hashtags.ROOT)
public interface HashtagApi {

    @Operation(
            summary = "Search hashtags",
            description =
                    "Fuzzy hashtag name search using Elasticsearch ngram matching. Falls back to"
                            + " PostgreSQL pg_trgm when Elasticsearch is unavailable.",
            security = {@SecurityRequirement(name = "")})
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Search results"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "Validation failure",
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
    @GetMapping(ApiConstants.Hashtags.SEARCH)
    ResponseEntity<ApiResponse<CursorPageResponse<HashtagResponse>>> search(
            @Parameter(description = "Search term; 1-100 characters", required = true)
                    @RequestParam("q")
                    @NotBlank
                    @Size(min = 1, max = 100)
                    String q,
            @Parameter(
                            description =
                                    "Opaque cursor from the previous page; omit for the first page")
                    @RequestParam(required = false)
                    String cursor,
            @Parameter(description = "Maximum results per page; defaults to 20")
                    @RequestParam(defaultValue = "20")
                    @Min(1)
                    @Max(100)
                    int limit);

    @Operation(
            summary = "Resolve a hashtag by name",
            description =
                    "Resolves a hashtag name to its record so a shared or deep-linked URL such as"
                            + " /tags/devlife reaches the same row the post write path created. The"
                            + " name is normalized with the same function caption extraction uses,"
                            + " so a leading #, surrounding whitespace and upper case all resolve."
                            + " A banned or deleted hashtag is refused with 404"
                            + " HASHTAG_UNAVAILABLE rather than returned, keeping 'not available'"
                            + " distinguishable from 'no posts yet'.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "The hashtag record with its post count and lifecycle status"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description =
                        "HASHTAG_NOT_FOUND when no row carries the normalized name, or"
                                + " HASHTAG_UNAVAILABLE when it is banned or deleted",
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
    @GetMapping(ApiConstants.Hashtags.BY_NAME)
    ResponseEntity<ApiResponse<HashtagDetailResponse>> getByName(
            @Parameter(
                            description = "Hashtag name, with or without a leading #",
                            example = "devlife")
                    @PathVariable("name")
                    @NotBlank
                    @Size(max = 100)
                    String name);

    @Operation(
            summary = "List trending hashtags",
            description = "Returns the latest trending hashtag snapshot ordered by rank ascending.",
            security = {@SecurityRequirement(name = "")})
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Trending hashtag list"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "page or size out of range",
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
    @GetMapping(ApiConstants.Hashtags.TRENDING)
    ResponseEntity<ApiResponse<PageResponse<HashtagTrendingResponse>>> trending(
            @Parameter(description = "Zero-based page index")
                    @RequestParam(defaultValue = "0")
                    @Min(0)
                    int page,
            @Parameter(description = "Page size; maximum 100")
                    @RequestParam(defaultValue = "20")
                    @Min(1)
                    @Max(100)
                    int size);

    @Operation(
            summary = "List trending hashtags ranked for the caller",
            description =
                    "Blends the platform trending snapshot with the caller's own hashtag affinity"
                            + " on rank, never on the two scores directly, because an affinity score is"
                            + " a share of one user's own total and a trending score is a post count"
                            + " over a window. A share of the page is reserved for hashtags adjacent to"
                            + " the caller's interests but not among them. A caller with no computed"
                            + " affinity silently receives the platform list, so this tab is never"
                            + " empty. Same response shape as the platform surface.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Trending hashtags ranked for the caller, pinned first"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "429",
                description = "Rate limit exceeded",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @AuthenticationRequiredResponse
    @GetMapping(ApiConstants.Hashtags.TRENDING_FOR_YOU)
    ResponseEntity<ApiResponse<PageResponse<HashtagTrendingResponse>>> trendingForYou(
            @Parameter(description = "Zero-based page index")
                    @RequestParam(defaultValue = "0")
                    @Min(0)
                    int page,
            @Parameter(description = "Page size; maximum 100")
                    @RequestParam(defaultValue = "20")
                    @Min(1)
                    @Max(100)
                    int size);

    /** Trending hashtags paired with one post cover each, for the in-feed trending card. */
    @Operation(
            summary = "Trending hashtags with a post preview",
            description =
                    "Trending hashtags, each paired with the cover image of its newest published"
                            + " post, for the in-feed trending card. Exists so a client does not issue"
                            + " one posts request per hashtag. A hashtag whose top post carries no"
                            + " renderable media, or whose candidate posts all belong to private"
                            + " accounts, is still returned with a null previewUrl rather than being"
                            + " dropped.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Trending hashtags with covers, pinned first"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "429",
                description = "Rate limit exceeded",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @AuthenticationRequiredResponse
    @GetMapping(ApiConstants.Hashtags.TRENDING_PREVIEWS)
    ResponseEntity<ApiResponse<List<TrendingPreviewResponse>>> trendingPreviews(
            @Parameter(description = "How many hashtags to return; maximum 10")
                    @RequestParam(defaultValue = "3")
                    @Min(1)
                    @Max(10)
                    int size,
            @Parameter(
                            description =
                                    "for-you for the personalised blend, platform for the"
                                            + " platform-wide snapshot")
                    @RequestParam(defaultValue = "for-you")
                    String scope);
}
