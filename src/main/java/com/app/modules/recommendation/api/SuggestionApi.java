package com.app.modules.recommendation.api;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.app.common.ApiConstants;
import com.app.common.config.openapi.AuthenticationRequiredResponse;
import com.app.common.response.ApiResponse;
import com.app.common.response.UserListItemResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * OpenAPI contract for people-you-may-know.
 *
 * <p>Both operations require a session. The list is personal to the caller and the dismissal is
 * recorded against them, so neither has an anonymous form.
 *
 * <p>The list is filtered at read time rather than at write time, so an account that was blocked,
 * followed or deactivated after the candidate set was built does not appear. When nothing
 * personalised survives those filters the verified cold-start list answers instead, which is why a
 * brand new account is never shown an empty rail.
 */
@Tag(name = "Recommendations", description = "Personalized content recommendation endpoints")
@RequestMapping(ApiConstants.Recommendations.ROOT)
public interface SuggestionApi {

    /** Lists accounts the caller may know, filtered at read time. */
    @Operation(
            summary = "List suggested accounts",
            description =
                    "Returns accounts the caller may know, blending the follow graph, the"
                            + " recommender's user-to-user neighbours and hashtag interest overlap"
                            + " on rank rather than on scores. Blocked, already-followed, dismissed"
                            + " and opted-out accounts are removed at read time. An account with no"
                            + " follows and no history is served the verified cold-start list"
                            + " instead of an empty one.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Suggested accounts, most relevant first"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "limit outside 1-50",
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
    @GetMapping(ApiConstants.Recommendations.SUGGESTIONS)
    ResponseEntity<ApiResponse<List<UserListItemResponse>>> suggestions(
            @Parameter(description = "How many accounts to return", example = "10")
                    @RequestParam(defaultValue = "10")
                    @Min(1)
                    @Max(50)
                    int limit);

    /** Permanently removes one account from the caller's suggestions. */
    @Operation(
            summary = "Dismiss a suggested account",
            description =
                    "Removes one account from the caller's suggestions permanently. This is not a"
                            + " block: the dismissed account keeps appearing in search, on profiles"
                            + " and everywhere else, and is never told. Idempotent, so repeating it"
                            + " is not an error.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Account dismissed, or already was"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "429",
                description = "Rate limit exceeded",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @AuthenticationRequiredResponse
    @PostMapping(ApiConstants.Recommendations.SUGGESTION_DISMISS)
    ResponseEntity<ApiResponse<Void>> dismiss(
            @Parameter(description = "Account to stop suggesting", required = true) @PathVariable
                    UUID userId);
}
