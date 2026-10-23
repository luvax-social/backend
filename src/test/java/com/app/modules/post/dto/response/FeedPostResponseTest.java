package com.app.modules.post.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.app.common.response.UserSummaryResponse;
import com.app.modules.hashtag.dto.response.HashtagSummaryResponse;
import com.app.modules.post.enums.CollaborationStatus;
import com.app.modules.post.enums.PostStatus;
import com.app.modules.post.enums.PostType;
import com.app.modules.post.place.dto.response.PlaceSummaryResponse;

class FeedPostResponseTest {

    @Test
    void withRankingScore_replacesOnlyTheScore() {
        FeedPostResponse original = fullyPopulated();

        FeedPostResponse copy = original.withRankingScore(9.5);

        assertThat(copy.rankingScore()).isEqualTo(9.5);
        assertThat(copy).isEqualTo(expectedCopyOf(original, 9.5));
    }

    /**
     * Walks the record components reflectively rather than naming them, so a component added to the
     * header in future is covered here without this test being edited. The ranked feed is the only
     * caller of the copy method, and a component the copy silently drops or transposes reaches the
     * client as a missing or wrong field on every ranked post while the chronological feed still
     * renders it correctly.
     */
    @Test
    void withRankingScore_carriesEveryOtherComponentThrough() throws Exception {
        FeedPostResponse original = fullyPopulated();

        FeedPostResponse copy = original.withRankingScore(9.5);

        for (RecordComponent component : FeedPostResponse.class.getRecordComponents()) {
            if (component.getName().equals("rankingScore")) {
                continue;
            }
            assertThat(component.getAccessor().invoke(copy))
                    .describedAs("component %s", component.getName())
                    .isEqualTo(component.getAccessor().invoke(original));
        }
    }

    // Every component holds a distinct value so a transposition between two components of the same
    // type is a failure rather than a coincidence.
    private FeedPostResponse fullyPopulated() {
        return new FeedPostResponse(
                UUID.fromString("00000000-0000-0000-0000-0000000000a1"),
                new UserSummaryResponse(
                        UUID.fromString("00000000-0000-0000-0000-0000000000a2"),
                        "author",
                        "Author",
                        null,
                        false),
                "caption",
                PostType.IMAGE,
                PostStatus.PUBLISHED,
                1,
                2,
                3,
                true,
                false,
                true,
                4,
                new PlaceSummaryResponse(
                        UUID.fromString("00000000-0000-0000-0000-0000000000a4"), "Da Nang"),
                List.of(),
                List.of(
                        new HashtagSummaryResponse(
                                UUID.fromString("00000000-0000-0000-0000-0000000000a3"),
                                "hashtag")),
                List.of(
                        new PostMentionResponse(
                                "jane.doe",
                                UUID.fromString("00000000-0000-0000-0000-0000000000a5"),
                                "jane.doe")),
                List.of(),
                CollaborationStatus.INVITED,
                OffsetDateTime.parse("2026-01-01T00:00:00Z"),
                OffsetDateTime.parse("2026-01-02T00:00:00Z"),
                OffsetDateTime.parse("2026-01-03T00:00:00Z"),
                null);
    }

    private FeedPostResponse expectedCopyOf(FeedPostResponse source, Double score) {
        return new FeedPostResponse(
                source.id(),
                source.author(),
                source.caption(),
                source.postType(),
                source.status(),
                source.likeCount(),
                source.commentCount(),
                source.saveCount(),
                source.isLiked(),
                source.isSaved(),
                source.hasReported(),
                source.viewCount(),
                source.place(),
                source.media(),
                source.hashtags(),
                source.mentions(),
                source.collaborators(),
                source.viewerCollaboration(),
                source.createdAt(),
                source.updatedAt(),
                source.publishedAt(),
                score);
    }
}
