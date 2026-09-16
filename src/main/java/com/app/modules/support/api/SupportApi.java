package com.app.modules.support.api;

import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

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
import com.app.common.vocabulary.dto.response.SupportCategoryVocabularyResponse;
import com.app.modules.support.dto.request.CreateSupportTicketRequest;
import com.app.modules.support.dto.request.InProductAppealRequest;
import com.app.modules.support.dto.request.PublicSupportTicketRequest;
import com.app.modules.support.dto.request.ResendAppealLinkRequest;
import com.app.modules.support.dto.request.SignedAppealRequest;
import com.app.modules.support.dto.response.AppealLinkResponse;
import com.app.modules.support.dto.response.AppealSubmittedResponse;
import com.app.modules.support.dto.response.SupportTicketResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * OpenAPI contract for the user-facing half of the support centre.
 *
 * <p>Four of these operations are anonymous, and that is the point of the module. Only an {@code
 * ACTIVE} account can authenticate, so a banned or suspended user - the population most likely to
 * need support - cannot reach an authenticated endpoint at all. The appeal, public-form,
 * confirmation and public-category paths therefore carry their own controls rather than a session:
 * a single-use token for the first, Turnstile plus email confirmation for the second.
 *
 * <p>Each anonymous operation declares {@code security = {@SecurityRequirement(name = "")}} rather
 * than {@code security = {}}. A genuinely empty array is indistinguishable from the attribute's
 * unset default under Java's annotation model, so the marker is what survives to be rewritten into
 * an empty security list in the document.
 *
 * <p>None of the anonymous paths issues a session, a token pair or a refresh token row.
 */
@Tag(name = "Support", description = "Support requests, appeals and verification")
@RequestMapping(ApiConstants.Support.ROOT)
public interface SupportApi {

    /** Opens a support ticket for the authenticated account. */
    @Operation(
            summary = "Open a support ticket",
            description =
                    "Opens a ticket for the calling account. One account may hold only one open"
                            + " ticket at a time, so a second is refused rather than queued.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Ticket opened"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description = "The account already has an open ticket",
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
    @PostMapping(ApiConstants.Support.TICKETS)
    ResponseEntity<ApiResponse<SupportTicketResponse>> createTicket(
            @Valid @RequestBody CreateSupportTicketRequest request);

    /** Lists the authenticated account's own support tickets, newest first. */
    @Operation(
            summary = "List my support tickets",
            description =
                    "Returns the calling account's own tickets newest first, in the requester's"
                            + " form: the staff internal note is never included.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "The account's own tickets"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "limit outside 1-100",
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
    @GetMapping(ApiConstants.Support.TICKETS)
    ResponseEntity<ApiResponse<List<SupportTicketResponse>>> listOwnTickets(
            @Parameter(description = "How many tickets to return", example = "20")
                    @RequestParam(defaultValue = "20")
                    @Min(1)
                    @Max(100)
                    int limit);

    /** Reads one of the authenticated account's own support tickets. */
    @Operation(
            summary = "Get one of my support tickets",
            description =
                    "Returns one of the calling account's own tickets. A ticket belonging to"
                            + " another account answers not-found rather than forbidden, so the"
                            + " response does not confirm that the id exists.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "The ticket in its requester form"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "No such ticket belonging to the calling account",
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
    @GetMapping(ApiConstants.Support.TICKET_BY_ID)
    ResponseEntity<ApiResponse<SupportTicketResponse>> getOwnTicket(
            @Parameter(description = "Ticket id", required = true) @PathVariable("ticketId")
                    UUID ticketId);

    /** Opens an appeal by redeeming the single-use token from a moderation notice. */
    @Operation(
            summary = "Open an appeal from a signed link",
            description =
                    "Redeems the single-use token from a moderation notice and opens exactly one"
                            + " appeal ticket. Anonymous by necessity: the account this authorises"
                            + " is banned or suspended and cannot authenticate. Minting no session"
                            + " is deliberate - the link authorises one ticket against one audit"
                            + " row and nothing else.",
            security = {@SecurityRequirement(name = "")})
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Appeal opened"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description = "An appeal against that decision is already open",
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
    @PostMapping(ApiConstants.Support.APPEAL)
    ResponseEntity<ApiResponse<AppealSubmittedResponse>> createAppeal(
            @Valid @RequestBody SignedAppealRequest request);

    /** Re-sends the appeal link for the most recent un-appealed decision on an account. */
    @Operation(
            summary = "Request a replacement appeal link",
            description =
                    "Re-mints the appeal link for the most recent decision on the account holding"
                            + " the given address that has not already been contested, and mails it"
                            + " to that account's own verified address. The answer is identical"
                            + " whether or not the address matches anything, in body, in status"
                            + " code and in elapsed time, so the route cannot be used to test"
                            + " whether an address is registered. Turnstile is required and fails"
                            + " closed: this route has no per-caller identity and it sends mail, so"
                            + " the challenge is its only real control.",
            security = {@SecurityRequirement(name = "")})
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Accepted. Answers the same whether or not the address matched."),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "The verification challenge was not accepted",
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
    @PostMapping(ApiConstants.Support.APPEAL_RESEND)
    ResponseEntity<ApiResponse<Void>> resendAppealLink(
            @Valid @RequestBody ResendAppealLinkRequest request, HttpServletRequest servletRequest);

    /** Reads one appeal for an appellant who holds a status token and no session. */
    @Operation(
            summary = "Read your appeal with a status token",
            description =
                    "Reads one appeal in the shape its own author sees, for an appellant who holds"
                            + " no session. The appeal token is spent by the redemption that"
                            + " created the ticket, so this token is the only thing that can reach"
                            + " it afterwards. Read-only and idempotent: it never consumes the"
                            + " token, because a status link is meant to be followed repeatedly."
                            + " Carries no internal note, no assignee and no escalation reason -"
                            + " it reuses the owner-facing shape, which has no field for any of"
                            + " them. Every negative case answers identically.",
            security = {@SecurityRequirement(name = "")})
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "The appeal"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "The token is unknown or has expired",
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
    @GetMapping(ApiConstants.Support.APPEAL_STATUS)
    ResponseEntity<ApiResponse<SupportTicketResponse>> readAppealStatus(
            @RequestParam("token") @NotBlank String token);

    /** Opens an appeal from a session, against a moderation decision the caller owns. */
    @Operation(
            summary = "Open an appeal against your own moderation decision",
            description =
                    "Opens exactly one appeal ticket against an audit row the caller is the target"
                            + " of. The second entry to the same ticket as the signed link, for the"
                            + " appellant who still holds a session and therefore never needed one."
                            + " Ownership is read server-side from the audit row; the identifier in"
                            + " the request proves nothing. A row belonging to another account"
                            + " answers exactly as an unknown one does. Any unspent signed link for"
                            + " the same decision is revoked, so the two entries can never both be"
                            + " used against one decision.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Appeal opened"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "That decision carries no appeal",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "No appealable decision was found",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description = "That decision has already been appealed",
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
    @PostMapping(ApiConstants.Support.APPEALS)
    ResponseEntity<ApiResponse<SupportTicketResponse>> createInProductAppeal(
            @Valid @RequestBody InProductAppealRequest request);

    /** Accepts a public support request, held invisible to staff until the address is confirmed. */
    @Operation(
            summary = "Submit the public support form",
            description =
                    "Accepts an anonymous support request behind a Turnstile challenge and holds it"
                            + " invisible to staff until the submitted address is confirmed. No"
                            + " ticket is returned: echoing one back would tell an anonymous caller"
                            + " that a submission succeeded for an address they may not own, and"
                            + " the ticket is not real until confirmed.",
            security = {@SecurityRequirement(name = "")})
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Submission accepted; a confirmation mail has been queued"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "429",
                description =
                        "Rate limit exceeded, or this address has reached its daily submission cap",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @MalformedBodyErrorResponses
    @PostMapping(ApiConstants.Support.PUBLIC_TICKET)
    ResponseEntity<ApiResponse<Void>> createPublicTicket(
            @Valid @RequestBody PublicSupportTicketRequest request,
            HttpServletRequest servletRequest);

    /** Reports whether an appeal link is still redeemable, without redeeming it. */
    @Operation(
            summary = "Check an appeal link",
            description =
                    "Reports whether an appeal link is still redeemable and which category it"
                            + " concerns, without spending it. Read-only by construction: the"
                            + " landing screen calls this on mount, so redeeming here would spend"
                            + " the link merely by opening the page, and a mail client prefetching"
                            + " the URL would spend it before the reader saw it at all.",
            security = {@SecurityRequirement(name = "")})
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Whether the link is still redeemable, and its appeal category"),
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
    @GetMapping(ApiConstants.Support.APPEAL_VALIDATE)
    ResponseEntity<ApiResponse<AppealLinkResponse>> validateAppealLink(
            @Parameter(description = "Appeal token from the moderation notice", required = true)
                    @RequestParam("token")
                    @NotBlank
                    String token);

    /** Lists the categories the public form may offer, without a session. */
    @Operation(
            summary = "List public support categories",
            description =
                    "Returns the support categories the anonymous form may offer. Strictly less"
                            + " than the authenticated vocabulary: support categories only, and"
                            + " only those flagged enabled and public-form. It carries display"
                            + " metadata and no account data. It exists so the anonymous form does"
                            + " not need a client-side copy of the category table, which drifts the"
                            + " moment a category is added or disabled.",
            security = {@SecurityRequirement(name = "")})
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Categories the public form may offer"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "429",
                description = "Rate limit exceeded",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @GetMapping(ApiConstants.Support.PUBLIC_CATEGORIES)
    ResponseEntity<ApiResponse<List<SupportCategoryVocabularyResponse>>> listPublicCategories();

    /** Confirms a public submission and moves it into the staff queue. */
    @Operation(
            summary = "Confirm a public support request",
            description =
                    "Redeems the confirmation token mailed to the submitted address and moves the"
                            + " held submission into the staff queue. Until this is done the"
                            + " submission is invisible to staff, so an address nobody owns never"
                            + " produces a ticket.",
            security = {@SecurityRequirement(name = "")})
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Submission confirmed and queued for staff"),
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
    @PostMapping(ApiConstants.Support.CONFIRM)
    ResponseEntity<ApiResponse<Void>> confirmPublicTicket(
            @Parameter(description = "Confirmation token from the mailed link", required = true)
                    @RequestParam("token")
                    @NotBlank
                    String token);
}
