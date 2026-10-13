package com.app.common.response;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class ViewerRelationshipResponseTest {

    @Test
    void none_isAllFalse() {
        ViewerRelationshipResponse none = ViewerRelationshipResponse.NONE;

        assertThat(none.isFollowing()).isFalse();
        assertThat(none.isFollowRequested()).isFalse();
        assertThat(none.isFollowedBy()).isFalse();
        assertThat(none.isBlocking()).isFalse();
        assertThat(none.hasReported()).isFalse();
        assertThat(none.isMutingPosts()).isFalse();
        assertThat(none.isMutingStories()).isFalse();
        assertThat(none.isShowingFewer()).isFalse();
    }

    @Test
    void construction_setsEachFieldIndependently() {
        ViewerRelationshipResponse state =
                new ViewerRelationshipResponse(true, false, true, false, true, false, true, false);

        assertThat(state.isFollowing()).isTrue();
        assertThat(state.isFollowRequested()).isFalse();
        assertThat(state.isFollowedBy()).isTrue();
        assertThat(state.isBlocking()).isFalse();
        assertThat(state.hasReported()).isTrue();
        assertThat(state.isMutingPosts()).isFalse();
        assertThat(state.isMutingStories()).isTrue();
        assertThat(state.isShowingFewer()).isFalse();
    }

    @Test
    void construction_mutePostsAndShowFewer_doNotImplyEachOtherOrTheStoryScope() {
        ViewerRelationshipResponse state =
                new ViewerRelationshipResponse(
                        false, false, false, false, false, true, false, true);

        assertThat(state.isMutingPosts()).isTrue();
        assertThat(state.isMutingStories()).isFalse();
        assertThat(state.isShowingFewer()).isTrue();
    }

    @Test
    void serialization_newFlagsUseTheSameIsPrefixedNamesAsTheExistingOnes() {
        ViewerRelationshipResponse state =
                new ViewerRelationshipResponse(true, false, false, false, false, true, true, true);

        Map<?, ?> json =
                JsonMapper.builder()
                        .build()
                        .readValue(
                                JsonMapper.builder().build().writeValueAsString(state), Map.class);

        assertThat(Set.<Object>copyOf(json.keySet()))
                .containsExactlyInAnyOrder(
                        "isFollowing",
                        "isFollowRequested",
                        "isFollowedBy",
                        "isBlocking",
                        "hasReported",
                        "isMutingPosts",
                        "isMutingStories",
                        "isShowingFewer");
        assertThat(json.get("isMutingPosts")).isEqualTo(true);
        assertThat(json.get("isMutingStories")).isEqualTo(true);
        assertThat(json.get("isShowingFewer")).isEqualTo(true);
    }
}
