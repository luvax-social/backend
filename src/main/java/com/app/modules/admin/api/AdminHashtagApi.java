package com.app.modules.admin.api;

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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.app.common.ApiConstants;
import com.app.common.config.openapi.AuthenticationRequiredResponse;
import com.app.common.config.openapi.CursorErrorResponses;
import com.app.common.config.openapi.MalformedBodyErrorResponses;
import com.app.common.response.ApiResponse;
import com.app.common.response.CursorPageResponse;
import com.app.modules.admin.dto.request.AdminCreateHashtagRequest;
import com.app.modules.admin.dto.request.AdminDeleteHashtagRequest;
import com.app.modules.admin.dto.request.AdminHashtagPinRequest;
import com.app.modules.admin.dto.request.AdminUpdateHashtagRequest;
import com.app.modules.admin.dto.response.AdminActionResponse;
import com.app.modules.hashtag.dto.response.HashtagAdminResponse;
import com.app.modules.hashtag.enums.HashtagStatus;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * OpenAPI contract for the administrative hashtag registry.
 *
 * <p>Every operation here requires the administrator role. The reads deliberately span every
 * lifecycle status, unlike the public hashtag surfaces, which show active hashtags only.
 *
 * <p>Banning a hashtag hides the tag, never the posts. A post already carrying a banned tag keeps
 * appearing in the feed, on its author's profile, and in search exactly as before; what changes is
 * that the tag itself stops being discoverable and stops being accepted on new writes.
 */
@Tag(name = "Administration", description = "Moderation actions and immutable audit history")
@RequestMapping(ApiConstants.Admin.ROOT)
public interface AdminHashtagApi {

    /** Lists hashtags newest first, optionally narrowed to one lifecycle status. */
    @Operation(
            summary = "List hashtags",
            description =
                    "Returns one cursor page of hashtags ordered by creation time descending."
                            + " Spans every lifecycle status, so a banned or deleted hashtag that"
                            + " no public surface shows is still visible here.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Cursor page of hashtags"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Administrator role required",
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
    @GetMapping(ApiConstants.Admin.HASHTAGS)
    ResponseEntity<ApiResponse<CursorPageResponse<HashtagAdminResponse>>> listHashtags(
            @Parameter(description = "Restrict to hashtags holding this lifecycle status")
                    @RequestParam(required = false)
                    HashtagStatus status,
            @Parameter(description = "Opaque cursor from a previous page")
                    @RequestParam(required = false)
                    String cursor,
            @Parameter(description = "Page size")
                    @RequestParam(defaultValue = "20")
                    @Min(1)
                    @Max(100)
                    int limit);

    /** Searches hashtags by case-insensitive substring of the name. */
    @Operation(
            summary = "Search hashtags",
            description =
                    "Matches the query against the hashtag name as a case-insensitive substring,"
                            + " spanning every lifecycle status. Substring rather than the trigram"
                            + " similarity the public search uses: an administrator looking for a"
                            + " term it is about to ban needs the match to be predictable, and a"
                            + " similarity threshold can silently miss the exact term.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Cursor page of matching hashtags"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "Empty query, or malformed cursor",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Administrator role required",
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
    @GetMapping(ApiConstants.Admin.HASHTAG_SEARCH)
    ResponseEntity<ApiResponse<CursorPageResponse<HashtagAdminResponse>>> searchHashtags(
            @Parameter(description = "Search term; at least one character", required = true)
                    @RequestParam("q")
                    String query,
            @Parameter(description = "Restrict to hashtags holding this lifecycle status")
                    @RequestParam(required = false)
                    HashtagStatus status,
            @Parameter(description = "Opaque cursor from a previous page")
                    @RequestParam(required = false)
                    String cursor,
            @Parameter(description = "Page size")
                    @RequestParam(defaultValue = "20")
                    @Min(1)
                    @Max(100)
                    int limit);

    /** Creates a hashtag directly in a chosen lifecycle state, ahead of any post using it. */
    @Operation(
            summary = "Create a hashtag",
            description =
                    "Creates a hashtag before any post has used it. Serves seeding a term ahead of"
                            + " an event so it is already in the registry when traffic arrives, and"
                            + " banning a term before it can be used at all. Creating one already"
                            + " deleted is refused. Writes one create_hashtag audit row.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "201",
                description = "Hashtag created; the audit row is returned"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "415",
                description = "Request body was sent with an unsupported Content-Type",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description =
                        "Validation failure, a name that normalizes to nothing, or a request to"
                                + " create the hashtag already deleted",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Administrator role required",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description = "A hashtag with that name already exists",
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
    @PostMapping(ApiConstants.Admin.HASHTAGS)
    ResponseEntity<ApiResponse<AdminActionResponse>> createHashtag(
            @Valid @RequestBody AdminCreateHashtagRequest request);

    /** Moves a hashtag to a new lifecycle state. */
    @Operation(
            summary = "Change a hashtag's lifecycle state",
            description =
                    "Bans, unbans, deletes or restores one hashtag, recording who decided and why"
                            + " on the hashtag row and in the audit log. Taking a hashtag out of"
                            + " circulation also removes its trending rows in the same"
                            + " transaction, because a tag is usually banned while it is at the top"
                            + " of the trending list. The name is immutable and this body carries"
                            + " no name field: a body containing one is rejected as malformed"
                            + " rather than quietly ignored.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "State changed; the audit row is returned"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Administrator role required",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "Hashtag not found",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description = "The hashtag already holds the requested state",
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
    @MalformedBodyErrorResponses
    @PatchMapping(ApiConstants.Admin.HASHTAG_BY_ID)
    ResponseEntity<ApiResponse<AdminActionResponse>> updateHashtag(
            @PathVariable("hashtagId") UUID hashtagId,
            @Valid @RequestBody AdminUpdateHashtagRequest request);

    /** Marks a hashtag deleted without removing its row. */
    @Operation(
            summary = "Delete a hashtag",
            description =
                    "Sets the hashtag's state to deleted. Never a row removal: removing the row"
                            + " would cascade to the post associations and drive the post_count"
                            + " trigger over every post that used the tag, rewriting history"
                            + " nothing asked to rewrite. The associations and the counter survive"
                            + " untouched, so the deletion is reversible. A deleted hashtag is not"
                            + " listed on a post, while the caption keeps its literal text."
                            + " Writes one delete_hashtag audit row.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Hashtag deleted; the audit row is returned"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Administrator role required",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "Hashtag not found",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description = "The hashtag is already deleted",
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
    @MalformedBodyErrorResponses
    @DeleteMapping(ApiConstants.Admin.HASHTAG_BY_ID)
    ResponseEntity<ApiResponse<AdminActionResponse>> deleteHashtag(
            @PathVariable("hashtagId") UUID hashtagId,
            @Valid @RequestBody AdminDeleteHashtagRequest request);

    @Operation(
            summary = "Pin a hashtag",
            description =
                    "Pins a hashtag platform-wide so it leads the trending list. Rejected with 404"
                            + " HASHTAG_UNAVAILABLE when the hashtag is banned or deleted, because a"
                            + " pin promotes a term and a term out of circulation must not be"
                            + " promoted. Rejected with ADMIN_INVALID_TRANSITION when it is already"
                            + " pinned. Banning a pinned hashtag clears the pin.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Hashtag pinned; the audit row is returned"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Administrator role required",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "Hashtag not found, or banned or deleted and so not promotable",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description = "The hashtag is already pinned",
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
    @MalformedBodyErrorResponses
    @PostMapping(ApiConstants.Admin.HASHTAG_PIN)
    ResponseEntity<ApiResponse<AdminActionResponse>> pinHashtag(
            @PathVariable("hashtagId") UUID hashtagId,
            @Valid @RequestBody AdminHashtagPinRequest request);

    @Operation(
            summary = "Unpin a hashtag",
            description =
                    "Removes a platform-wide pin. Rejected with ADMIN_INVALID_TRANSITION when the"
                            + " hashtag is not pinned.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Pin removed; the audit row is returned"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Administrator role required",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "Hashtag not found",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description = "The hashtag is not pinned",
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
    @MalformedBodyErrorResponses
    @DeleteMapping(ApiConstants.Admin.HASHTAG_PIN)
    ResponseEntity<ApiResponse<AdminActionResponse>> unpinHashtag(
            @PathVariable("hashtagId") UUID hashtagId,
            @Valid @RequestBody AdminHashtagPinRequest request);
}
