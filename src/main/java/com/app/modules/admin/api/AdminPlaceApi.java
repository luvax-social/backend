package com.app.modules.admin.api;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.app.common.ApiConstants;
import com.app.common.config.openapi.AuthenticationRequiredResponse;
import com.app.common.config.openapi.CursorErrorResponses;
import com.app.common.config.openapi.MalformedBodyErrorResponses;
import com.app.common.response.ApiResponse;
import com.app.common.response.CursorPageResponse;
import com.app.modules.admin.dto.request.AdminUpdatePlaceRequest;
import com.app.modules.admin.dto.response.AdminActionResponse;
import com.app.modules.post.place.dto.response.PlaceAdminResponse;
import com.app.modules.post.place.enums.PlaceStatus;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * OpenAPI contract for the administrative places registry.
 *
 * <p>Every operation here requires the administrator role. The reads span every lifecycle status,
 * unlike the public place surfaces, which show active places only. Banning or deleting a place
 * hides the name, never the posts that used it.
 */
@Tag(name = "Administration", description = "Moderation actions and immutable audit history")
@RequestMapping(ApiConstants.Admin.ROOT)
public interface AdminPlaceApi {

    @Operation(
            summary = "List places",
            description =
                    "Returns one cursor page of places ordered by creation time descending,"
                            + " spanning every lifecycle status.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Cursor page of places"),
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
    @GetMapping(ApiConstants.Admin.PLACES)
    ResponseEntity<ApiResponse<CursorPageResponse<PlaceAdminResponse>>> listPlaces(
            @Parameter(description = "Restrict to places holding this lifecycle status")
                    @RequestParam(required = false)
                    PlaceStatus status,
            @Parameter(description = "Opaque cursor from a previous page")
                    @RequestParam(required = false)
                    String cursor,
            @Parameter(description = "Page size")
                    @RequestParam(defaultValue = "20")
                    @Min(1)
                    @Max(100)
                    int limit);

    @Operation(
            summary = "Search places",
            description =
                    "Matches the query as a case-insensitive substring of the place name or its"
                            + " folded key, spanning every lifecycle status.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Cursor page of matching places"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "Blank search text or malformed cursor",
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
    @GetMapping(ApiConstants.Admin.PLACE_SEARCH)
    ResponseEntity<ApiResponse<CursorPageResponse<PlaceAdminResponse>>> searchPlaces(
            @Parameter(description = "Text to look for in the place name") @RequestParam("q")
                    String query,
            @Parameter(description = "Restrict to places holding this lifecycle status")
                    @RequestParam(required = false)
                    PlaceStatus status,
            @Parameter(description = "Opaque cursor from a previous page")
                    @RequestParam(required = false)
                    String cursor,
            @Parameter(description = "Page size")
                    @RequestParam(defaultValue = "20")
                    @Min(1)
                    @Max(100)
                    int limit);

    @Operation(
            summary = "Ban, unban or delete a place",
            description =
                    "Moves the place to the requested status and writes an audit row in the same"
                            + " transaction: ban_place, delete_place, or unban_place, which also"
                            + " brings a deleted place back. A reason is required to ban or delete."
                            + " When the body names a report about the place, banning or deleting"
                            + " resolves it.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "The audit row the decision wrote"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "Invalid payload, or a missing reason for a ban or delete",
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
                responseCode = "404",
                description = "No place carries the id, or the linked report does not exist",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description = "The place already holds the requested status",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @AuthenticationRequiredResponse
    @MalformedBodyErrorResponses
    @PatchMapping(ApiConstants.Admin.PLACE_BY_ID)
    ResponseEntity<ApiResponse<AdminActionResponse>> updatePlace(
            @Parameter(description = "Place identifier") @PathVariable("placeId") UUID placeId,
            @Valid @RequestBody AdminUpdatePlaceRequest request);
}
