package com.app.modules.mail.controller;

import jakarta.validation.constraints.NotBlank;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.app.common.ApiConstants;
import com.app.common.base.BaseController;
import com.app.common.enums.ApiSuccessCode;
import com.app.common.response.ApiResponse;
import com.app.modules.mail.api.MailUnsubscribeApi;
import com.app.modules.mail.service.MailUnsubscribeService;

import io.github.resilience4j.ratelimiter.annotation.RateLimiter;

/**
 * Campaign mail opt-out.
 *
 * <p>Anonymous by necessity: the recipient following this link from a mail client has no session
 * and may well be banned, so requiring one would make the link useless for exactly the people most
 * likely to want it.
 *
 * <p>Opting out suppresses campaign mail only. Auth mail and moderation mail ignore the flag
 * entirely, and the mail footer says so, because an account cannot opt out of being told it has
 * been banned.
 */
@RestController
public class MailUnsubscribeController extends BaseController implements MailUnsubscribeApi {

    private final MailUnsubscribeService mailUnsubscribeService;

    public MailUnsubscribeController(MailUnsubscribeService mailUnsubscribeService) {
        this.mailUnsubscribeService = mailUnsubscribeService;
    }

    /** Opts the account behind the token out of campaign mail. Idempotent. */
    @Override
    @PostMapping(ApiConstants.Support.UNSUBSCRIBE)
    @RateLimiter(name = "lowTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<Void>> unsubscribe(
            @RequestParam("token") @NotBlank String token) {
        mailUnsubscribeService.unsubscribe(token);
        return ResponseEntity.ok(ApiResponse.success(ApiSuccessCode.OK, null));
    }
}
