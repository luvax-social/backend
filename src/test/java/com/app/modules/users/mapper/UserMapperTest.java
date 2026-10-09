package com.app.modules.users.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.app.common.response.ViewerRelationshipResponse;
import com.app.modules.users.dto.response.PublicUserProfileResponse;
import com.app.modules.users.dto.response.UserProfileResponse;
import com.app.modules.users.dto.response.UserSettingsResponse;
import com.app.modules.users.entity.User;
import com.app.modules.users.entity.UserSettings;
import com.app.modules.users.enums.MessagePermission;
import com.app.modules.users.enums.UserRole;
import com.app.modules.users.enums.UserStatus;

class UserMapperTest {

    private final UserMapper mapper = new UserMapperImpl();

    @Test
    void toProfileResponse_mapsAllFieldsIncludingCounters() {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        User user =
                User.builder()
                        .id(id)
                        .username("alice")
                        .email("alice@example.com")
                        .displayName("Alice")
                        .bio("Coffee lover")
                        .avatarUrl("https://cdn.example.com/alice.jpg")
                        .bannerUrl("https://cdn.example.com/alice-banner.jpg")
                        .websiteUrl("https://alice.example.com")
                        .role(UserRole.USER)
                        .status(UserStatus.ACTIVE)
                        .isPrivate(false)
                        .isVerified(true)
                        .createdAt(now)
                        .build();

        UserProfileResponse response = mapper.toProfileResponse(user);

        assertThat(response.id()).isEqualTo(id);
        assertThat(response.username()).isEqualTo("alice");
        assertThat(response.email()).isEqualTo("alice@example.com");
        assertThat(response.displayName()).isEqualTo("Alice");
        assertThat(response.bio()).isEqualTo("Coffee lover");
        assertThat(response.avatarUrl()).isEqualTo("https://cdn.example.com/alice.jpg");
        assertThat(response.bannerUrl()).isEqualTo("https://cdn.example.com/alice-banner.jpg");
        assertThat(response.websiteUrl()).isEqualTo("https://alice.example.com");
        assertThat(response.isPrivate()).isFalse();
        assertThat(response.isVerified()).isTrue();
        assertThat(response.followerCount()).isZero();
        assertThat(response.followingCount()).isZero();
        assertThat(response.postCount()).isZero();
        assertThat(response.createdAt()).isEqualTo(now);
    }

    @Test
    void toPublicProfileResponse_excludesEmailAndRole_includesCounters() {
        UUID id = UUID.randomUUID();
        User user =
                User.builder()
                        .id(id)
                        .username("bob")
                        .email("bob@example.com")
                        .displayName("Bob")
                        .bio("Dev")
                        .avatarUrl("https://cdn.example.com/bob.jpg")
                        .bannerUrl("https://cdn.example.com/bob-banner.jpg")
                        .role(UserRole.ADMIN)
                        .status(UserStatus.ACTIVE)
                        .isPrivate(false)
                        .isVerified(false)
                        .build();

        PublicUserProfileResponse response =
                mapper.toPublicProfileResponse(user, 10, 5, 3, ViewerRelationshipResponse.NONE);

        assertThat(response.id()).isEqualTo(id);
        assertThat(response.username()).isEqualTo("bob");
        assertThat(response.displayName()).isEqualTo("Bob");
        assertThat(response.bio()).isEqualTo("Dev");
        assertThat(response.avatarUrl()).isEqualTo("https://cdn.example.com/bob.jpg");
        assertThat(response.bannerUrl()).isEqualTo("https://cdn.example.com/bob-banner.jpg");
        assertThat(response.isPrivate()).isFalse();
        assertThat(response.isVerified()).isFalse();
        assertThat(response.followerCount()).isEqualTo(10);
        assertThat(response.followingCount()).isEqualTo(5);
        assertThat(response.postCount()).isEqualTo(3);
        assertThat(response.viewerState()).isEqualTo(ViewerRelationshipResponse.NONE);
        // PublicUserProfileResponse record has no email or role fields
    }

    @Test
    void toPublicProfileResponse_nullCounters_preservedAsNull() {
        UUID id = UUID.randomUUID();
        User user =
                User.builder()
                        .id(id)
                        .username("carol")
                        .email("carol@example.com")
                        .role(UserRole.USER)
                        .status(UserStatus.ACTIVE)
                        .build();

        PublicUserProfileResponse response =
                mapper.toPublicProfileResponse(
                        user, null, null, null, ViewerRelationshipResponse.NONE);

        assertThat(response.followerCount()).isNull();
        assertThat(response.followingCount()).isNull();
        assertThat(response.postCount()).isNull();
    }

    @Test
    void toSettingsResponse_mapsAllFieldsAndUpdatedAt() {
        UUID userId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        UserSettings settings =
                UserSettings.builder()
                        .userId(userId)
                        .notifyLikes(false)
                        .notifyComments(true)
                        .notifyFollows(false)
                        .notifyMentions(true)
                        .notifyMessages(false)
                        .showActivityStatus(true)
                        .allowStoryReplies(false)
                        .messagePermission(MessagePermission.NOBODY)
                        .updatedAt(now)
                        .build();

        UserSettingsResponse response = mapper.toSettingsResponse(settings);

        assertThat(response.notifyLikes()).isFalse();
        assertThat(response.notifyComments()).isTrue();
        assertThat(response.notifyFollows()).isFalse();
        assertThat(response.notifyMentions()).isTrue();
        assertThat(response.notifyMessages()).isFalse();
        assertThat(response.showActivityStatus()).isTrue();
        assertThat(response.allowStoryReplies()).isFalse();
        assertThat(response.messagePermission()).isEqualTo(MessagePermission.NOBODY);
        assertThat(response.updatedAt()).isEqualTo(now);
    }
}
