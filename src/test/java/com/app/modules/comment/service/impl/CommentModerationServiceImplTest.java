package com.app.modules.comment.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.app.modules.comment.service.CommentModerationService.ModerationResult;

class CommentModerationServiceImplTest {

    private final CommentModerationServiceImpl service = new CommentModerationServiceImpl();

    @Test
    void check_blankContent_returnsRejected() {
        ModerationResult result = service.check("   ");
        assertThat(result.rejected()).isTrue();
        assertThat(result.reason()).isEqualTo("empty");
    }

    @Test
    void check_exceededLength_returnsRejected() {
        String tooLong = "a".repeat(2201);
        ModerationResult result = service.check(tooLong);
        assertThat(result.rejected()).isTrue();
        assertThat(result.reason()).isEqualTo("too_long");
    }

    @Test
    void check_repeatedToken_returnsSpam() {
        ModerationResult result = service.check("buy buy buy buy buy");
        assertThat(result.rejected()).isTrue();
        assertThat(result.reason()).isEqualTo("spam");
    }

    @Test
    void check_longAllCapsContent_returnsSpam() {
        ModerationResult result =
                service.check("THIS IS A VERY LOUD COMMENT THAT KEEPS GOING FOR QUITE A WHILE NOW");
        assertThat(result.rejected()).isTrue();
        assertThat(result.reason()).isEqualTo("spam");
    }

    @Test
    void check_wordThatUsedToBeBlockedByConfiguration_isNoLongerRejected() {
        // Word lists moved to the Hidden Words registry, which hides a comment from its
        // recipient instead of rejecting it from the author.
        ModerationResult result = service.check("this is SpAmMy content");
        assertThat(result.rejected()).isFalse();
        assertThat(result.reason()).isNull();
    }

    @Test
    void check_validContent_returnsApproved() {
        ModerationResult result = service.check("a perfectly fine comment");
        assertThat(result.rejected()).isFalse();
        assertThat(result.reason()).isNull();
    }
}
