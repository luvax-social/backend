package com.app.modules.support.api;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

import com.app.common.ApiConstants;
import com.app.common.config.openapi.AuthenticationRequiredResponse;
import com.app.common.config.openapi.MalformedBodyErrorResponses;
import com.app.common.response.ApiResponse;
import com.app.modules.support.dto.request.CreateVerificationRequest;
import com.app.modules.support.dto.response.VerificationCategoryResponse;
import com.app.modules.support.dto.response.VerificationStateResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * OpenAPI contract for the requester-facing half of verification.
 *
 * <p>Unlike the rest of the support centre, every operation here requires a session. A verification
 * request is about an account, so it needs one; there is no anonymous path and no signed link,
 * because there is nothing to contest until a decision exists.
 *
 * <p>No identity document is collected and there is no file upload. A request names a category and
 * the name being claimed, and carries free-text evidence fields.
 */
@Tag(name = "Support", description = "Support requests, appeals and verification")
@RequestMapping(ApiConstants.Support.ROOT)
public interface VerificationApi {

    /** Lists the verification categories a requester can choose, each with its glyph key. */
    @Operation(
            summary = "List verification categories",
            description =
                    "Returns the categories a verification request may claim, each with the glyph"
                            + " key the badge renders for it. Read from the configuration table, so"
                            + " a category disabled there stops being offered without a"
                            + " deployment.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Selectable categories"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "429",
                description = "Rate limit exceeded",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @AuthenticationRequiredResponse
    @GetMapping(ApiConstants.Support.VERIFICATION_CATEGORIES)
    ResponseEntity<ApiResponse<List<VerificationCategoryResponse>>> listCategories();

    /** Returns the calling account's badge, outstanding request, or neither. */
    @Operation(
            summary = "Get my verification state",
            description =
                    "Returns the calling account's verification state: the badge it holds, the"
                            + " request still being decided, or neither. The reason a previous"
                            + " request was refused is carried here so it can be shown while the"
                            + " next one is being written.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Current verification state"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "429",
                description = "Rate limit exceeded",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @AuthenticationRequiredResponse
    @GetMapping(ApiConstants.Support.VERIFICATION_ME)
    ResponseEntity<ApiResponse<VerificationStateResponse>> myState();

    /** Submits a verification request for the calling account. */
    @Operation(
            summary = "Submit a verification request",
            description =
                    "Opens a verification request for the calling account. At least three of the"
                            + " evidence fields must carry text. An account that already holds a"
                            + " badge, or that already has an open request, is refused rather than"
                            + " opening a second one.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Request opened; the new state is returned"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "The claimed category does not exist",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description = "The account already holds a badge, or already has an open request",
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
    @PostMapping(ApiConstants.Support.VERIFICATION_REQUESTS)
    ResponseEntity<ApiResponse<VerificationStateResponse>> submit(
            @Valid @RequestBody CreateVerificationRequest request);
}
