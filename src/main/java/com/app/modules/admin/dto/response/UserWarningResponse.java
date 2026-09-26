package com.app.modules.admin.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One warning as the warned account sees it.
 *
 * <p>Deliberately narrower than {@link AdminWarningResponse}: it omits the issuing moderator, whose
 * identity is not the account holder's business and whose disclosure invites retaliation, and it
 * omits the account id, which is always the caller's own.
 *
 * @param id warning identifier
 * @param reasonKey key of the {@code report_reason_configs} row cited
 * @param note what the moderator recorded, which is the part the account needs in order to act
 * @param adminActionId the audit row this warning was recorded under, which is what the in-product
 *     appeal endpoint opens an appeal against
 * @param createdAt moment the warning was issued
 */
@Schema(description = "One warning issued against the calling account")
public record UserWarningResponse(
        UUID id,
        String reasonKey,
        String note,
        // Carried so the account can contest the warning from the settings screen that lists it.
        // An opaque identifier and not a capability: the appeal endpoint re-reads the audit row and
        // compares its target against the caller, so holding this grants nothing on its own.
        UUID adminActionId,
        OffsetDateTime createdAt) {}
