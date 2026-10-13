package com.app.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ApiConstantsSocialTest {

    @Test
    void social_constants_matchTheRoutesSocialApiActuallyServes() {
        assertThat(ApiConstants.Social.ROOT + ApiConstants.Social.FOLLOW)
                .isEqualTo("/api/v1/social/follow/{targetUserId}");
        assertThat(ApiConstants.Social.ROOT + ApiConstants.Social.BLOCK)
                .isEqualTo("/api/v1/social/block/{targetUserId}");
        assertThat(ApiConstants.Social.ROOT + ApiConstants.Social.FOLLOWERS)
                .isEqualTo("/api/v1/social/users/{userId}/followers");
        assertThat(ApiConstants.Social.ROOT + ApiConstants.Social.FOLLOWING)
                .isEqualTo("/api/v1/social/users/{userId}/following");
        assertThat(ApiConstants.Social.ROOT + ApiConstants.Social.FOLLOW_REQUESTS)
                .isEqualTo("/api/v1/social/follow-requests");
        assertThat(ApiConstants.Social.ROOT + ApiConstants.Social.FOLLOW_REQUEST_APPROVE)
                .isEqualTo("/api/v1/social/follow-requests/{requesterId}/approve");
        assertThat(ApiConstants.Social.ROOT + ApiConstants.Social.FOLLOW_REQUEST_REJECT)
                .isEqualTo("/api/v1/social/follow-requests/{requesterId}/reject");
        assertThat(ApiConstants.Social.ROOT + ApiConstants.Social.SHOW_FEWER)
                .isEqualTo("/api/v1/social/show-fewer/{authorId}");
        assertThat(ApiConstants.Social.ROOT + ApiConstants.Social.MUTES)
                .isEqualTo("/api/v1/social/mutes");
        assertThat(ApiConstants.Social.ROOT + ApiConstants.Social.MUTE_BY_ID)
                .isEqualTo("/api/v1/social/mutes/{targetUserId}");
    }
}
