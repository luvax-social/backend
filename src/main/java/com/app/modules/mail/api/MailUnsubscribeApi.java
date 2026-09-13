package com.app.modules.mail.api;

import jakarta.validation.constraints.NotBlank;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.app.common.ApiConstants;
import com.app.common.response.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * OpenAPI contract for campaign mail opt-out.
 *
 * <p>Anonymous by necessity rather than by convenience: the recipient following this link from a
 * mail client has no session and may well be banned, so requiring one would make the link useless
 * for exactly the people most likely to want it. The single-use token in the link is the control.
 *
 * <p>Opting out suppresses campaign mail only. Auth mail and moderation mail ignore the flag
 * entirely, and the mail footer says so, because an account cannot opt out of being told it has
 * been banned.
 */
@Tag(name = "Support", description = "Support requests, appeals and verification")
@RequestMapping(ApiConstants.Support.ROOT)
public interface MailUnsubscribeApi {

    /** Opts the account behind the token out of campaign mail. Idempotent. */
    @Operation(
            summary = "Unsubscribe from campaign mail",
            description =
                    "Opts the account the token identifies out of campaign mail. Idempotent, so"
                            + " following the link twice is not an error - a mail client that"
                            + " prefetches the link must not see a failure. Auth and moderation"
                            + " mail are unaffected.",
            security = {@SecurityRequirement(name = "")})
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Opted out, or already was"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "The token is absent, malformed, or no longer valid",
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
    @PostMapping(ApiConstants.Support.UNSUBSCRIBE)
    ResponseEntity<ApiResponse<Void>> unsubscribe(
            @Parameter(
                            description = "Single-use opt-out token from the mail footer",
                            required = true)
                    @RequestParam("token")
                    @NotBlank
                    String token);
}
