package com.app.modules.social.api;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.app.common.ApiConstants;
import com.app.common.config.openapi.AuthenticationRequiredResponse;
import com.app.common.config.openapi.CursorErrorResponses;
import com.app.common.config.openapi.MalformedBodyErrorResponses;
import com.app.common.response.ApiResponse;
import com.app.common.response.CursorPageResponse;
import com.app.modules.social.dto.request.MuteRequest;
import com.app.modules.social.dto.response.MuteResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/** OpenAPI contract for muting another account's posts, stories or both. */
@Tag(name = "Mutes", description = "Mute and unmute an account's posts and stories")
@RequestMapping(ApiConstants.Social.ROOT)
public interface MuteApi {

    @Operation(
            summary = "Set what is muted for an account",
            description =
                    "Creates or replaces the caller's mute of the account. Posts, stories or both"
                            + " can be muted; sending both as false removes the mute. The muted"
                            + " account is not told.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "The resulting mute; both flags false after an unmute"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "The target is the caller, or the body is malformed",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description =
                        "The account does not exist or is in a block relationship with the caller;"
                                + " both answer identically",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @MalformedBodyErrorResponses
    @AuthenticationRequiredResponse
    @PutMapping(ApiConstants.Social.MUTE_BY_ID)
    ResponseEntity<ApiResponse<MuteResponse>> setMute(
            @PathVariable("targetUserId") UUID targetUserId,
            @Valid @RequestBody MuteRequest request);

    @Operation(
            summary = "Unmute an account",
            description =
                    "Removes the caller's mute of the account. Removing one that does not exist"
                            + " succeeds and changes nothing.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "204",
                description = "Mute removed, or none existed")
    })
    @AuthenticationRequiredResponse
    @DeleteMapping(ApiConstants.Social.MUTE_BY_ID)
    ResponseEntity<Void> unmute(@PathVariable("targetUserId") UUID targetUserId);

    @Operation(
            summary = "List muted accounts",
            description =
                    "Cursor-paginated accounts the caller has muted, most recently set first, with"
                            + " which of posts and stories is muted. A muted account since"
                            + " soft-deleted is returned as a placeholder rather than dropped.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Muted accounts returned"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "429",
                description = "Rate limit exceeded",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @CursorErrorResponses
    @AuthenticationRequiredResponse
    @GetMapping(ApiConstants.Social.MUTES)
    ResponseEntity<ApiResponse<CursorPageResponse<MuteResponse>>> listMutes(
            @RequestParam(required = false) @Size(max = 512) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit);
}
