package com.app.modules.post.place.api;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.app.common.ApiConstants;
import com.app.common.config.openapi.AuthenticationRequiredResponse;
import com.app.common.config.openapi.CursorErrorResponses;
import com.app.common.response.ApiResponse;
import com.app.common.response.CursorPageResponse;
import com.app.modules.post.place.dto.request.CreatePlaceRequest;
import com.app.modules.post.place.dto.response.PlaceResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/** OpenAPI contract for places: creating one by name, searching, and reading one. */
@Tag(name = "Places", description = "Named places a post can be tagged at")
@RequestMapping(ApiConstants.Places.ROOT)
public interface PlaceApi {

    @Operation(
            summary = "Create a place or find the one with the same name",
            description =
                    "Returns the place whose normalised name matches, creating it when none does."
                            + " Case, accents and spacing never create a duplicate. A name that"
                            + " matches a banned or deleted place is refused.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "201",
                description = "The place"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "The name is too short or long or holds no letter or digit",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "415",
                description = "Request body was sent with an unsupported Content-Type",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "422",
                description = "POST_PLACE_UNAVAILABLE: the name belongs to a removed place",
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
    @PostMapping
    ResponseEntity<ApiResponse<PlaceResponse>> createPlace(
            @Valid @RequestBody CreatePlaceRequest request);

    @Operation(
            summary = "Search places",
            description =
                    "Active places that at least one published post uses, matched by name and"
                            + " ordered by popularity. Served from Elasticsearch, degrading to a"
                            + " PostgreSQL trigram search when the search tier is unavailable.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Cursor page of places"),
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
    @GetMapping(ApiConstants.Places.SEARCH)
    ResponseEntity<ApiResponse<CursorPageResponse<PlaceResponse>>> searchPlaces(
            @Parameter(description = "Text typed so far")
                    @RequestParam("q")
                    @Size(min = 1, max = 100)
                    String q,
            @Parameter(description = "Opaque cursor from a previous page")
                    @RequestParam(value = "cursor", required = false)
                    String cursor,
            @Parameter(description = "Maximum places per page")
                    @RequestParam(value = "limit", defaultValue = "20")
                    @Min(1)
                    @Max(100)
                    int limit);

    @Operation(
            summary = "Get a place",
            description = "Returns an active place. A banned or deleted place is refused.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "The place"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description =
                        "PLACE_NOT_FOUND when no place carries the id, or PLACE_UNAVAILABLE when"
                                + " it is banned or deleted",
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
    @GetMapping(ApiConstants.Places.BY_ID)
    ResponseEntity<ApiResponse<PlaceResponse>> getPlace(
            @Parameter(description = "Place identifier") @PathVariable("placeId") UUID placeId);
}
