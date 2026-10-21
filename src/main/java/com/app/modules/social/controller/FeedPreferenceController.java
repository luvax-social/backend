package com.app.modules.social.controller;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.app.common.base.BaseController;
import com.app.common.security.util.SecurityUtils;
import com.app.modules.social.api.FeedPreferenceApi;
import com.app.modules.social.service.FeedPreferenceService;

/**
 * Show-fewer endpoints: the authenticated caller asking to see less of an author in their
 * recommendations, and taking that request back.
 *
 * <p>Both endpoints require an authenticated caller, resolved via {@link
 * SecurityUtils#getCurrentUserId()}.
 */
@RestController
public class FeedPreferenceController extends BaseController implements FeedPreferenceApi {

    private final FeedPreferenceService feedPreferenceService;

    public FeedPreferenceController(FeedPreferenceService feedPreferenceService) {
        this.feedPreferenceService = feedPreferenceService;
    }

    @Override
    public ResponseEntity<Void> showFewer(UUID authorId) {
        feedPreferenceService.showFewer(SecurityUtils.getCurrentUserId(), authorId);

        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Void> undoShowFewer(UUID authorId) {
        feedPreferenceService.undoShowFewer(SecurityUtils.getCurrentUserId(), authorId);

        return ResponseEntity.noContent().build();
    }
}
