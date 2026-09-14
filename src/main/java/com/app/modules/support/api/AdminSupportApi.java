package com.app.modules.support.api;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.app.common.ApiConstants;
import com.app.common.config.openapi.AuthenticationRequiredResponse;
import com.app.common.config.openapi.MalformedBodyErrorResponses;
import com.app.common.response.ApiResponse;
import com.app.modules.support.dto.request.EscalateSupportTicketRequest;
import com.app.modules.support.dto.request.RespondSupportTicketRequest;
import com.app.modules.support.dto.response.SupportTicketStaffResponse;
import com.app.modules.support.enums.SupportTicketStatus;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * OpenAPI contract for the staff half of the support centre.
 *
 * <p>Both staff roles reach every operation here, so each answers 403 to an ordinary account. The
 * role gate is only the outer one. The rule that actually decides an appeal lives in the service: a
 * moderator may read an appeal and may escalate it, but may not answer or close one, because unban,
 * unsuspend, revoke-warning and revoke-strike are administrator-only actions and a moderator
 * closing an appeal would be recording a verdict they cannot carry out. That refusal is also a 403,
 * and it depends on the ticket's category, which no annotation can see.
 */
@Tag(name = "Support", description = "Support requests, appeals and verification")
@RequestMapping(ApiConstants.Admin.ROOT)
public interface AdminSupportApi {

    /**
     * Lists the staff queue, optionally filtered by status; never shows unconfirmed submissions.
     */
    @Operation(
            summary = "List support tickets",
            description =
                    "Returns the staff queue newest first. A submission still awaiting its email"
                            + " confirmation is never listed, so the queue holds only tickets a"
                            + " real address has stood behind.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Tickets visible to staff"),
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
    @GetMapping(ApiConstants.Admin.SUPPORT_TICKETS)
    ResponseEntity<ApiResponse<List<SupportTicketStaffResponse>>> listTickets(
            @Parameter(description = "Restrict to one ticket status", example = "open")
                    @RequestParam(required = false)
                    SupportTicketStatus status,
            @Parameter(description = "How many tickets to return", example = "20")
                    @RequestParam(defaultValue = "20")
                    @Min(1)
                    @Max(100)
                    int limit);

    /** Reads one ticket as staff, including its internal note. */
    @Operation(
            summary = "Get a support ticket",
            description =
                    "Returns one ticket in its staff form, including the internal note, which is"
                            + " never shown to the person who opened it.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "The ticket in its staff form"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Moderator or administrator role required",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "No ticket with that id",
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
    @GetMapping(ApiConstants.Admin.SUPPORT_TICKET_BY_ID)
    ResponseEntity<ApiResponse<SupportTicketStaffResponse>> getTicket(
            @Parameter(description = "Ticket id", required = true) @PathVariable("ticketId")
                    UUID ticketId);

    /** Claims an unassigned ticket for the authenticated staff member. */
    @Operation(
            summary = "Claim a support ticket",
            description =
                    "Assigns the ticket to the calling staff member. A ticket another staff member"
                            + " already holds is refused rather than reassigned, so two people"
                            + " cannot answer the same request.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Ticket claimed"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Moderator or administrator role required",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "No ticket with that id",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description = "Another staff member already holds this ticket",
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
    @PostMapping(ApiConstants.Admin.SUPPORT_TICKET_CLAIM)
    ResponseEntity<ApiResponse<SupportTicketStaffResponse>> claimTicket(
            @Parameter(description = "Ticket id", required = true) @PathVariable("ticketId")
                    UUID ticketId);

    /** Answers a ticket and closes it, or closes it as rejected. */
    @Operation(
            summary = "Respond to a support ticket",
            description =
                    "Answers the ticket and closes it, mailing the reply to the address that opened"
                            + " it. Setting reject closes it as refused instead. A moderator"
                            + " attempting either on an appeal is refused, because the actions an"
                            + " appeal asks for are administrator-only.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Ticket answered or rejected and closed"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description =
                        "Moderator or administrator role required, a moderator attempted to decide"
                                + " an appeal, or the ticket appeals the caller's own action",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "No ticket with that id",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description = "The ticket is not claimed by the caller, or is already closed",
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
    @PostMapping(ApiConstants.Admin.SUPPORT_TICKET_RESPOND)
    ResponseEntity<ApiResponse<SupportTicketStaffResponse>> respondToTicket(
            @Parameter(description = "Ticket id", required = true) @PathVariable("ticketId")
                    UUID ticketId,
            @Parameter(description = "Close the ticket as rejected rather than answered")
                    @RequestParam(defaultValue = "false")
                    boolean reject,
            @Valid @RequestBody RespondSupportTicketRequest request);

    /** Hands a ticket up to an administrator. Permitted to both staff roles. */
    @Operation(
            summary = "Escalate a support ticket",
            description =
                    "Hands the ticket up to an administrator with a reason. Open to both staff"
                            + " roles: escalating is how a moderator moves an appeal they may read"
                            + " but may not decide.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Ticket escalated"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Moderator or administrator role required",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "No ticket with that id",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description = "The ticket cannot move to escalated from its current state",
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
    @PatchMapping(ApiConstants.Admin.SUPPORT_TICKET_ESCALATE)
    ResponseEntity<ApiResponse<SupportTicketStaffResponse>> escalateTicket(
            @Parameter(description = "Ticket id", required = true) @PathVariable("ticketId")
                    UUID ticketId,
            @Valid @RequestBody EscalateSupportTicketRequest request);
}
