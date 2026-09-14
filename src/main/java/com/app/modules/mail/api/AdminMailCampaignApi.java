package com.app.modules.mail.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.app.common.ApiConstants;
import com.app.common.config.openapi.AuthenticationRequiredResponse;
import com.app.common.config.openapi.MalformedBodyErrorResponses;
import com.app.common.response.ApiResponse;
import com.app.modules.mail.dto.request.PreviewMailCampaignRequest;
import com.app.modules.mail.dto.request.SaveMailCampaignRequest;
import com.app.modules.mail.dto.response.MailCampaignDetailResponse;
import com.app.modules.mail.dto.response.MailCampaignSummaryResponse;
import com.app.modules.mail.dto.response.MailCampaignTemplateResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * OpenAPI contract for administrator-only custom mail campaigns.
 *
 * <p>Two independent gates guard every operation here: the {@code /api/v1/admin/mail/**} matcher
 * together with the controller's role annotation, and the service asserting the administrator role
 * again. A moderator has no access to any part of this, so every operation answers 403 to one.
 *
 * <p>A campaign is a draft until it is scheduled, and only a draft can be edited. Scheduling hands
 * it to the sender job, which is why nothing here sends mail synchronously.
 */
@Tag(name = "Administration", description = "Moderation actions and immutable audit history")
@RequestMapping(ApiConstants.Admin.ROOT)
public interface AdminMailCampaignApi {

    /** Lists the read-only samples an administrator can start a campaign from. */
    @Operation(
            summary = "List campaign templates",
            description =
                    "Returns the read-only samples a campaign can be started from. These are"
                            + " starting points copied into a draft, not templates the send path"
                            + " resolves later.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Available samples"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Administrator role required",
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
    @GetMapping(ApiConstants.Admin.MAIL_TEMPLATES)
    ResponseEntity<ApiResponse<List<MailCampaignTemplateResponse>>> listTemplates();

    /** Renders a Markdown body through the same pipeline the send path uses. */
    @Operation(
            summary = "Preview a campaign body",
            description =
                    "Renders a Markdown body to the HTML the send path would produce. Rendered on"
                            + " the server deliberately: one implementation means the preview"
                            + " cannot diverge from the mail actually sent, and there is no second"
                            + " sanitization surface in the browser.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Rendered HTML under the 'html' key"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Administrator role required",
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
    @PostMapping(ApiConstants.Admin.CAMPAIGN_PREVIEW)
    ResponseEntity<ApiResponse<Map<String, String>>> preview(
            @Valid @RequestBody PreviewMailCampaignRequest request);

    /** Creates a draft campaign. */
    @Operation(
            summary = "Create a draft campaign",
            description =
                    "Opens a campaign in draft. Nothing is sent and no recipient is resolved until"
                            + " the draft is scheduled.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Draft created"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Administrator role required",
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
    @PostMapping(ApiConstants.Admin.CAMPAIGNS)
    ResponseEntity<ApiResponse<MailCampaignDetailResponse>> createCampaign(
            @Valid @RequestBody SaveMailCampaignRequest request);

    /** Updates a draft campaign; refused once the campaign has left draft. */
    @Operation(
            summary = "Update a draft campaign",
            description =
                    "Replaces the draft's subject and body. Refused once the campaign has left"
                            + " draft, because a scheduled or sent campaign's content is the record"
                            + " of what recipients were sent.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Draft updated"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Administrator role required",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "No campaign with that id",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description = "The campaign has left draft and can no longer be edited",
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
    @PutMapping(ApiConstants.Admin.CAMPAIGN_BY_ID)
    ResponseEntity<ApiResponse<MailCampaignDetailResponse>> updateCampaign(
            @Parameter(description = "Campaign id", required = true) @PathVariable("campaignId")
                    UUID campaignId,
            @Valid @RequestBody SaveMailCampaignRequest request);

    /** Moves a draft to scheduled, after which the sender job claims and sends it. */
    @Operation(
            summary = "Schedule a campaign",
            description =
                    "Moves the draft to scheduled. From there the sender job claims it and sends"
                            + " in batches; this call does not send anything itself and returns as"
                            + " soon as the state has changed.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Campaign scheduled"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Administrator role required",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "No campaign with that id",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description = "The campaign is not in draft",
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
    @PatchMapping(ApiConstants.Admin.CAMPAIGN_SCHEDULE)
    ResponseEntity<ApiResponse<MailCampaignDetailResponse>> scheduleCampaign(
            @Parameter(description = "Campaign id", required = true) @PathVariable("campaignId")
                    UUID campaignId);

    /** Campaign history, newest first. */
    @Operation(
            summary = "List campaigns",
            description = "Returns campaign history newest first, in summary form.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Campaign history"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "limit outside 1-100",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Administrator role required",
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
    @GetMapping(ApiConstants.Admin.CAMPAIGNS)
    ResponseEntity<ApiResponse<List<MailCampaignSummaryResponse>>> listCampaigns(
            @Parameter(description = "How many campaigns to return", example = "20")
                    @RequestParam(defaultValue = "20")
                    @Min(1)
                    @Max(100)
                    int limit);

    /** One campaign with every recipient and their outcome, including opt-out skips. */
    @Operation(
            summary = "Get a campaign",
            description =
                    "Returns one campaign with every resolved recipient and the outcome recorded"
                            + " for each, including the ones skipped because the account had opted"
                            + " out or its domain was not permitted.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "The campaign and its recipient outcomes"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Administrator role required",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "No campaign with that id",
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
    @GetMapping(ApiConstants.Admin.CAMPAIGN_BY_ID)
    ResponseEntity<ApiResponse<MailCampaignDetailResponse>> getCampaign(
            @Parameter(description = "Campaign id", required = true) @PathVariable("campaignId")
                    UUID campaignId);
}
