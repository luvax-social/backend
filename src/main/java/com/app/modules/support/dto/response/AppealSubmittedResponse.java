package com.app.modules.support.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The answer to a redeemed appeal link: the ticket, plus the credential to watch it with.
 *
 * <p>The appeal token is spent by the redemption that produced this, so without the second field
 * the appellant walks away holding nothing. They have no session either - that is the premise of
 * the signed path - so they could not otherwise learn whether their appeal was ever read.
 *
 * <p>Carried here rather than as a field on {@link SupportTicketResponse}, because that record is
 * returned by every owner-facing read and a status token belongs to exactly one moment: the
 * redemption that minted it. A field on the shared record would be null everywhere else and would
 * invite a client to look for it there.
 *
 * @param ticket the appeal as its author sees it
 * @param statusToken the read-only token for the status link, shown once and never re-issued
 */
public record AppealSubmittedResponse(
        @Schema(description = "The appeal as its author sees it") SupportTicketResponse ticket,
        @Schema(
                        description =
                                "Read-only token for the status link. Shown once: the appellant"
                                        + " holds no session and this is their only way back to"
                                        + " the appeal.")
                String statusToken) {}
