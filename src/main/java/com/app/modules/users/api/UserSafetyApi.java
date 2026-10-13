package com.app.modules.users.api;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

import com.app.common.ApiConstants;
import com.app.common.config.openapi.MalformedBodyErrorResponses;
import com.app.common.response.ApiResponse;
import com.app.modules.social.dto.request.CreateHiddenWordRequest;
import com.app.modules.social.dto.request.UpdateInteractionLimitRequest;
import com.app.modules.social.dto.response.HiddenWordEntryResponse;
import com.app.modules.social.dto.response.HiddenWordListResponse;
import com.app.modules.social.dto.response.InteractionLimitResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

/** OpenAPI contract for the interaction-safety controls an account manages about itself. */
@Tag(name = "Users", description = "User profile and settings management")
@RequestMapping(ApiConstants.Users.ROOT)
public interface UserSafetyApi {

    @Operation(
            summary = "List my hidden words",
            description =
                    "Returns the authenticated user's personal hidden words, newest first, with the"
                            + " most entries one account may keep.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Entries returned"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "401",
                description = "Missing or invalid access token",
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
    @GetMapping(ApiConstants.Users.ME_HIDDEN_WORDS)
    ResponseEntity<ApiResponse<HiddenWordListResponse>> listMyHiddenWords();

    @Operation(
            summary = "Add a hidden word",
            description =
                    "Adds a word or phrase to the authenticated user's personal list. Comments and"
                            + " message requests containing it, compared without case, accents or"
                            + " common letter substitutions, are hidden from the user. The phrase"
                            + " may be 1 to 64 characters and 1 to 6 words, and the list holds at"
                            + " most 200 entries.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "201",
                description = "Entry created"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description =
                        "HIDDEN_WORD_INVALID: the phrase is missing, longer than 64 characters,"
                                + " has no letters or digits, or has more than 6 words",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "401",
                description = "Missing or invalid access token",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description =
                        "HIDDEN_WORD_DUPLICATE: an entry with the same normalised form exists",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "422",
                description = "HIDDEN_WORD_LIMIT_REACHED: the list already holds 200 entries",
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
    @MalformedBodyErrorResponses
    @PostMapping(ApiConstants.Users.ME_HIDDEN_WORDS)
    ResponseEntity<ApiResponse<HiddenWordEntryResponse>> addMyHiddenWord(
            @Valid @RequestBody CreateHiddenWordRequest request);

    @Operation(
            summary = "Remove a hidden word",
            description = "Removes one entry from the authenticated user's personal list.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "204",
                description = "Entry removed"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "Path variable is not a valid UUID",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "401",
                description = "Missing or invalid access token",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "No such entry on the caller's list, including another user's entry",
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
    @DeleteMapping(ApiConstants.Users.ME_HIDDEN_WORD_BY_ID)
    ResponseEntity<Void> removeMyHiddenWord(@PathVariable UUID entryId);

    @Operation(
            summary = "Get my interaction limit",
            description =
                    "Returns the authenticated user's temporary interaction limit. active is true"
                            + " while expiresAt is in the future, so an expired limit reads inactive"
                            + " with no cleanup needed. An account that never set one gets both"
                            + " toggles off, no expiry and active false.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Limit returned"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "401",
                description = "Missing or invalid access token",
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
    @GetMapping(ApiConstants.Users.ME_INTERACTION_LIMITS)
    ResponseEntity<ApiResponse<InteractionLimitResponse>> getMyInteractionLimit();

    @Operation(
            summary = "Turn on my interaction limit",
            description =
                    "Limits who may interact with the authenticated user for a preset period,"
                            + " replacing any limit already set. At least one of the two audiences"
                            + " must be on, and durationDays must be 1, 3, 7, 14 or 28. The end time"
                            + " is computed by the server from the moment of the request.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Limit stored"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description =
                        "VALIDATION_ERROR: a field is missing, both audiences are off, or durationDays is not a preset",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "401",
                description = "Missing or invalid access token",
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
                responseCode = "429",
                description = "Rate limit exceeded",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @PutMapping(ApiConstants.Users.ME_INTERACTION_LIMITS)
    ResponseEntity<ApiResponse<InteractionLimitResponse>> setMyInteractionLimit(
            @Valid @RequestBody UpdateInteractionLimitRequest request);

    @Operation(
            summary = "Turn off my interaction limit",
            description =
                    "Removes the authenticated user's interaction limit before it expires."
                            + " Idempotent: turning off a limit that is not set also succeeds.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "204",
                description = "Limit removed or none was set"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "401",
                description = "Missing or invalid access token",
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
    @DeleteMapping(ApiConstants.Users.ME_INTERACTION_LIMITS)
    ResponseEntity<Void> clearMyInteractionLimit();
}
