package com.app.modules.mail.controller;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.app.common.ApiConstants;
import com.app.common.base.BaseController;
import com.app.common.enums.ApiSuccessCode;
import com.app.common.response.ApiResponse;
import com.app.common.security.util.SecurityUtils;
import com.app.common.web.StrictQueryParameters;
import com.app.modules.mail.api.AdminMailCampaignApi;
import com.app.modules.mail.dto.request.PreviewMailCampaignRequest;
import com.app.modules.mail.dto.request.SaveMailCampaignRequest;
import com.app.modules.mail.dto.response.MailCampaignDetailResponse;
import com.app.modules.mail.dto.response.MailCampaignSummaryResponse;
import com.app.modules.mail.dto.response.MailCampaignTemplateResponse;
import com.app.modules.mail.service.MailCampaignService;

import io.github.resilience4j.ratelimiter.annotation.RateLimiter;

/**
 * Administrator-only custom mail campaigns.
 *
 * <p>Two independent gates, as everywhere else on this surface: the class annotation and the {@code
 * /api/v1/admin/mail/**} matcher narrow to {@code ADMIN}, and {@code MailCampaignServiceImpl}
 * asserts the same thing again through {@code AdminAuthorizationService}. A moderator has no access
 * to any part of this.
 */
@RestController
@PreAuthorize("hasRole('ADMIN')")
public class AdminMailCampaignController extends BaseController implements AdminMailCampaignApi {

    private final MailCampaignService mailCampaignService;

    public AdminMailCampaignController(MailCampaignService mailCampaignService) {
        this.mailCampaignService = mailCampaignService;
    }

    /** Lists the read-only samples an administrator can start a campaign from. */
    @Override
    @GetMapping(ApiConstants.Admin.MAIL_TEMPLATES)
    @StrictQueryParameters
    @RateLimiter(name = "mediumTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<List<MailCampaignTemplateResponse>>> listTemplates() {
        return ResponseEntity.ok(
                ApiResponse.success(ApiSuccessCode.OK, mailCampaignService.listTemplates()));
    }

    /**
     * Renders a Markdown body through the same pipeline the send path uses.
     *
     * <p>Server-side deliberately. One implementation means the preview cannot diverge from the
     * mail that is actually sent, and there is no second sanitization surface in the browser.
     */
    @Override
    @PostMapping(ApiConstants.Admin.CAMPAIGN_PREVIEW)
    @RateLimiter(name = "mediumTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<Map<String, String>>> preview(
            @Valid @RequestBody PreviewMailCampaignRequest request) {
        return ResponseEntity.ok(
                ApiResponse.success(
                        ApiSuccessCode.OK,
                        Map.of("html", mailCampaignService.preview(request.body()))));
    }

    /** Creates a draft campaign. */
    @Override
    @PostMapping(ApiConstants.Admin.CAMPAIGNS)
    @RateLimiter(name = "lowTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<MailCampaignDetailResponse>> createCampaign(
            @Valid @RequestBody SaveMailCampaignRequest request) {
        return ResponseEntity.ok(
                ApiResponse.success(
                        ApiSuccessCode.CREATED,
                        mailCampaignService.createDraft(
                                SecurityUtils.getCurrentUserId(), request)));
    }

    /** Updates a draft campaign; refused once the campaign has left draft. */
    @Override
    @PutMapping(ApiConstants.Admin.CAMPAIGN_BY_ID)
    @RateLimiter(name = "lowTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<MailCampaignDetailResponse>> updateCampaign(
            @PathVariable("campaignId") UUID campaignId,
            @Valid @RequestBody SaveMailCampaignRequest request) {
        return ResponseEntity.ok(
                ApiResponse.success(
                        ApiSuccessCode.OK,
                        mailCampaignService.updateDraft(
                                SecurityUtils.getCurrentUserId(), campaignId, request)));
    }

    /** Moves a draft to scheduled, after which the sender job claims and sends it. */
    @Override
    @PatchMapping(ApiConstants.Admin.CAMPAIGN_SCHEDULE)
    @RateLimiter(name = "lowTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<MailCampaignDetailResponse>> scheduleCampaign(
            @PathVariable("campaignId") UUID campaignId) {
        return ResponseEntity.ok(
                ApiResponse.success(
                        ApiSuccessCode.OK,
                        mailCampaignService.schedule(
                                SecurityUtils.getCurrentUserId(), campaignId)));
    }

    /** Campaign history, newest first. */
    @Override
    @GetMapping(ApiConstants.Admin.CAMPAIGNS)
    @StrictQueryParameters
    @RateLimiter(name = "mediumTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<List<MailCampaignSummaryResponse>>> listCampaigns(
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return ResponseEntity.ok(
                ApiResponse.success(
                        ApiSuccessCode.OK,
                        mailCampaignService.listCampaigns(
                                SecurityUtils.getCurrentUserId(), limit)));
    }

    /** One campaign with every recipient and their outcome, including opt-out skips. */
    @Override
    @GetMapping(ApiConstants.Admin.CAMPAIGN_BY_ID)
    @StrictQueryParameters
    @RateLimiter(name = "mediumTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<MailCampaignDetailResponse>> getCampaign(
            @PathVariable("campaignId") UUID campaignId) {
        return ResponseEntity.ok(
                ApiResponse.success(
                        ApiSuccessCode.OK,
                        mailCampaignService.getCampaign(
                                SecurityUtils.getCurrentUserId(), campaignId)));
    }
}
