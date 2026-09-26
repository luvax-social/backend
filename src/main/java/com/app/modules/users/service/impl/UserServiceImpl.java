package com.app.modules.users.service.impl;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.common.response.ViewerRelationshipResponse;
import com.app.modules.recommendation.service.UserEventRecorder;
import com.app.modules.social.service.SocialService;
import com.app.modules.users.dto.request.UpdateProfileRequest;
import com.app.modules.users.dto.request.UpdateSettingsRequest;
import com.app.modules.users.dto.response.PublicUserProfileResponse;
import com.app.modules.users.dto.response.UserProfileResponse;
import com.app.modules.users.dto.response.UserSettingsResponse;
import com.app.modules.users.entity.User;
import com.app.modules.users.entity.UserSettings;
import com.app.modules.users.mapper.UserMapper;
import com.app.modules.users.repository.UserRepository;
import com.app.modules.users.repository.UserSettingsRepository;
import com.app.modules.users.service.UserService;

@Service
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final UserSettingsRepository settingsRepository;
    private final UserMapper userMapper;
    private final SocialService socialService;
    private final UserEventRecorder userEventRecorder;

    public UserServiceImpl(
            UserRepository userRepository,
            UserSettingsRepository settingsRepository,
            UserMapper userMapper,
            SocialService socialService,
            UserEventRecorder userEventRecorder) {
        this.userRepository = userRepository;
        this.settingsRepository = settingsRepository;
        this.userMapper = userMapper;
        this.socialService = socialService;
        this.userEventRecorder = userEventRecorder;
    }

    @Override
    @Transactional(readOnly = true)
    public UserProfileResponse getMyProfile(UUID userId) {
        User user =
                userRepository
                        .findByIdAndDeletedAtIsNull(userId)
                        .orElseThrow(() -> new AppException(ApiErrorCode.NOT_FOUND));
        return userMapper.toProfileResponse(user);
    }

    @Override
    @Transactional
    public UserProfileResponse updateMyProfile(UUID userId, UpdateProfileRequest request) {
        User user =
                userRepository
                        .findByIdAndDeletedAtIsNull(userId)
                        .orElseThrow(() -> new AppException(ApiErrorCode.NOT_FOUND));

        if (request.username() != null) {
            // Stored exactly as submitted: identity is case-insensitive, display is
            // case-preserving. The availability check below compares case-insensitively, so a
            // case-only collision surfaces as a 409 rather than a raw constraint violation.
            String newUsername = request.username();
            // The username UNIQUE constraint is table-wide and soft delete does not release a
            // username, so the availability check must span soft-deleted rows too. A partial check
            // would let a collision with a soft-deleted account fall through to a database error
            // whose response differs from the active-collision response and reveals account state.
            //
            // The "unchanged" test ignores case because identity is case-insensitive: recasing
            // your own name is a display change, not a claim on someone else's name. A
            // case-sensitive test here would send that request into the availability check, where
            // it would match the caller's own row and be rejected as already taken.
            if (!newUsername.equalsIgnoreCase(user.getUsername())
                    && userRepository.existsByUsername(newUsername)) {
                throw new AppException(ApiErrorCode.USER_USERNAME_ALREADY_EXISTS);
            }
            user.setUsername(newUsername);
        }

        if (request.displayName() != null) {
            user.setDisplayName(
                    StringUtils.hasText(request.displayName()) ? request.displayName() : null);
        }

        if (request.bio() != null) {
            user.setBio(StringUtils.hasText(request.bio()) ? request.bio() : null);
        }

        if (request.avatarUrl() != null) {
            user.setAvatarUrl(
                    StringUtils.hasText(request.avatarUrl()) ? request.avatarUrl() : null);
        }

        if (request.bannerUrl() != null) {
            user.setBannerUrl(
                    StringUtils.hasText(request.bannerUrl()) ? request.bannerUrl() : null);
        }

        if (request.websiteUrl() != null) {
            user.setWebsiteUrl(
                    StringUtils.hasText(request.websiteUrl()) ? request.websiteUrl() : null);
        }

        if (request.isPrivate() != null) {
            user.setPrivate(request.isPrivate());
        }

        userRepository.save(user);
        return userMapper.toProfileResponse(user);
    }

    @Override
    @Transactional(readOnly = true)
    public PublicUserProfileResponse getUserProfile(UUID viewerId, UUID targetUserId) {
        User user =
                userRepository
                        .findByIdAndDeletedAtIsNull(targetUserId)
                        .orElseThrow(() -> new AppException(ApiErrorCode.NOT_FOUND));
        return assemblePublicProfile(viewerId, user);
    }

    @Override
    @Transactional(readOnly = true)
    public PublicUserProfileResponse getUserProfileByUsername(UUID viewerId, String username) {
        // Case-sensitive by necessity: users.username carries a plain UNIQUE on the raw column, so
        // a case-insensitive match could resolve to more than one legal account.
        User user =
                userRepository
                        .findByUsernameAndDeletedAtIsNull(username)
                        .orElseThrow(() -> new AppException(ApiErrorCode.NOT_FOUND));
        return assemblePublicProfile(viewerId, user);
    }

    // Single gating and assembly path for both lookups, so the id and username endpoints cannot
    // drift apart on block handling, counter masking, or viewer state.
    private PublicUserProfileResponse assemblePublicProfile(UUID viewerId, User user) {
        UUID targetUserId = user.getId();

        boolean isOwner = viewerId != null && viewerId.equals(targetUserId);

        // A block in either direction hides the account entirely; return NOT_FOUND so a blocked
        // caller cannot even confirm the account exists.
        if (!isOwner
                && viewerId != null
                && socialService.isBlockedBetween(viewerId, targetUserId)) {
            throw new AppException(ApiErrorCode.NOT_FOUND);
        }

        // Self-views are excluded rather than filtered out later. Every account reads its own
        // profile constantly, so recording those would bury the views that matter under the ones
        // that never do, and make every investigation start by discarding most of the table.
        // Placed after the block check so a view that resolves to NOT_FOUND records nothing.
        if (!isOwner && viewerId != null) {
            userEventRecorder.recordProfileView(viewerId, targetUserId);
        }

        // Social counts are relationship-gated: the owner always sees them, a private account
        // reveals them only to accepted followers, and a public account reveals them to any
        // authenticated caller. Everyone else receives the profile card with counts masked to null.
        boolean detailed;
        if (isOwner) {
            detailed = true;
        } else if (user.isPrivate()) {
            detailed = viewerId != null && socialService.hasAcceptedFollow(viewerId, targetUserId);
        } else {
            detailed = viewerId != null;
        }

        Integer followerCount = detailed ? user.getFollowerCount() : null;
        Integer followingCount = detailed ? user.getFollowingCount() : null;
        Integer postCount = detailed ? user.getPostCount() : null;

        // A null viewer short-circuits to NONE without querying; a self-view naturally resolves
        // to NONE too, since a follow or block row against oneself cannot exist.
        Map<UUID, ViewerRelationshipResponse> relationships =
                socialService.loadRelationships(viewerId, List.of(targetUserId));
        ViewerRelationshipResponse viewerState =
                relationships.getOrDefault(targetUserId, ViewerRelationshipResponse.NONE);

        return userMapper.toPublicProfileResponse(
                user, followerCount, followingCount, postCount, viewerState);
    }

    @Override
    @Transactional(readOnly = true)
    public UserSettingsResponse getMySettings(UUID userId) {
        UserSettings settings =
                settingsRepository
                        .findById(userId)
                        .orElseThrow(() -> new AppException(ApiErrorCode.NOT_FOUND));
        return userMapper.toSettingsResponse(settings);
    }

    @Override
    @Transactional
    public UserSettingsResponse updateMySettings(UUID userId, UpdateSettingsRequest request) {
        UserSettings settings =
                settingsRepository
                        .findById(userId)
                        .orElseThrow(() -> new AppException(ApiErrorCode.NOT_FOUND));

        if (request.notifyLikes() != null) {
            settings.setNotifyLikes(request.notifyLikes());
        }
        if (request.notifyComments() != null) {
            settings.setNotifyComments(request.notifyComments());
        }
        if (request.notifyFollows() != null) {
            settings.setNotifyFollows(request.notifyFollows());
        }
        if (request.notifyMentions() != null) {
            settings.setNotifyMentions(request.notifyMentions());
        }
        if (request.notifyMessages() != null) {
            settings.setNotifyMessages(request.notifyMessages());
        }
        if (request.showActivityStatus() != null) {
            settings.setShowActivityStatus(request.showActivityStatus());
        }
        if (request.allowStoryReplies() != null) {
            settings.setAllowStoryReplies(request.allowStoryReplies());
        }
        if (request.suggestible() != null) {
            settings.setSuggestible(request.suggestible());
        }
        if (request.allowMessageRequests() != null) {
            settings.setAllowMessageRequests(request.allowMessageRequests());
        }

        settingsRepository.save(settings);
        return userMapper.toSettingsResponse(settings);
    }
}
