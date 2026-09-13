package com.app.modules.support.api;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.app.common.ApiConstants;
import com.app.common.config.openapi.AuthenticationRequiredResponse;
import com.app.common.config.openapi.MalformedBodyErrorResponses;
import com.app.common.response.ApiResponse;
import com.app.modules.support.dto.request.VerificationDecisionRequest;
import com.app.modules.support.dto.response.VerificationQueueItemResponse;
import com.app.modules.support.enums.SupportTicketStatus;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * OpenAPI contract for the staff-facing verification review queue.
 *
 * <p>Both staff roles are admitted and both may decide. Verification is a discretionary grant
 * rather than an enforcement action, so the narrowing that keeps unban and unsuspend
 * administrator-only does not apply here: a moderator granting a badge records no verdict they
 * cannot carry out. Every operation therefore answers 403 to an ordinary account, and the service
 * gates again on the actor-and-target rules and on holding the ticket's claim.
 *
 * <p>A revocation is not routed through a ticket, because it is a decision about an account rather
 * than an answer to a request.
 */
@Tag(name = "Support", description = "Support requests, appeals and verification")
@RequestMapping(ApiConstants.Admin.ROOT)
public interface AdminVerificationApi {

    /** Lists verification requests for review, newest first, optionally filtered by status. */
    @Operation(
            summary = "List verification requests",
            description =
                    "Returns verification requests for review, newest first. Filtering by status"
                            + " narrows the queue to one stage; omitting it returns every stage.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Requests awaiting or past review"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "status outside its enumerated set, or limit outside 1-100",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Moderator or administrator role required",
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
    @GetMapping(ApiConstants.Admin.VERIFICATION_QUEUE)
    ResponseEntity<ApiResponse<List<VerificationQueueItemResponse>>> queue(
            @Parameter(description = "Restrict to one ticket status", example = "open")
                    @RequestParam(required = false)
                    SupportTicketStatus status,
            @Parameter(description = "How many requests to return", example = "20")
                    @RequestParam(defaultValue = "20")
                    @Min(1)
                    @Max(100)
                    int limit);

    /** Returns one verification request with the requester's full grant history. */
    @Operation(
            summary = "Get a verification request",
            description =
                    "Returns one verification request together with every badge the requester has"
                            + " been granted and had revoked, so a reviewer decides with the"
                            + " account's whole history rather than this request alone.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "The request and the requester's grant history"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Moderator or administrator role required",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "No verification request with that id",
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
    @GetMapping(ApiConstants.Admin.VERIFICATION_REQUEST_BY_ID)
    ResponseEntity<ApiResponse<VerificationQueueItemResponse>> getRequest(
            @Parameter(description = "Verification request ticket id", required = true)
                    @PathVariable
                    UUID ticketId);

    /** Approves a verification request, granting the badge and mailing the requester. */
    @Operation(
            summary = "Approve a verification request",
            description =
                    "Grants the badge, records the decision in the moderation audit log and mails"
                            + " the requester, all in one transaction. The ticket must be claimed"
                            + " by the deciding staff member first.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Badge granted; the decided request is returned"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description =
                        "Moderator or administrator role required, or the decision would act on a"
                                + " ticket appealing the caller's own action",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "No verification request with that id",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description =
                        "The ticket is not claimed by the caller, is already decided, or the"
                                + " account already holds a badge",
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
    @PostMapping(ApiConstants.Admin.VERIFICATION_APPROVE)
    ResponseEntity<ApiResponse<VerificationQueueItemResponse>> approve(
            @Parameter(description = "Verification request ticket id", required = true)
                    @PathVariable
                    UUID ticketId,
            @Valid @RequestBody VerificationDecisionRequest request);

    /** Rejects a verification request, granting nothing and mailing the requester the reason. */
    @Operation(
            summary = "Reject a verification request",
            description =
                    "Closes the request without granting a badge, records the decision in the"
                            + " moderation audit log and mails the requester the reason, which is"
                            + " shown to them while their next request is being written.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Request rejected; the decided request is returned"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description =
                        "Moderator or administrator role required, or the decision would act on a"
                                + " ticket appealing the caller's own action",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "No verification request with that id",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description = "The ticket is not claimed by the caller, or is already decided",
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
    @PostMapping(ApiConstants.Admin.VERIFICATION_REJECT)
    ResponseEntity<ApiResponse<VerificationQueueItemResponse>> reject(
            @Parameter(description = "Verification request ticket id", required = true)
                    @PathVariable
                    UUID ticketId,
            @Valid @RequestBody VerificationDecisionRequest request);

    /** Withdraws an account's verified badge. */
    @Operation(
            summary = "Revoke a verified badge",
            description =
                    "Withdraws the badge from an account. Not routed through a ticket, because a"
                            + " revocation is a decision about an account rather than an answer to"
                            + " a request. The grant row is kept with its revocation recorded, so"
                            + " the next reviewer sees what was granted and why it was taken back.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Badge withdrawn"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Moderator or administrator role required",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "No account with that id",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description = "The account holds no badge to withdraw",
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
    @PostMapping(ApiConstants.Admin.VERIFICATION_REVOKE)
    ResponseEntity<ApiResponse<Void>> revoke(
            @Parameter(description = "Account whose badge is withdrawn", required = true)
                    @PathVariable
                    UUID userId,
            @Valid @RequestBody VerificationDecisionRequest request);
}
