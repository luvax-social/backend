package com.app.modules.post.service.impl;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.common.settings.service.SystemSettingService;
import com.app.modules.hashtag.service.HashtagService;

/**
 * Reads the hashtags a caption names and keeps {@code post_hashtags} consistent with them.
 *
 * <p>Shared by every path that writes a caption or moves a post in or out of publication, so the
 * extraction rule and the banned-hashtag refusal are the same wherever they run.
 */
@Component
public class PostCaptionHashtags {

    private static final Pattern HASHTAG_PATTERN = Pattern.compile("#([\\p{L}\\p{N}_]+)");
    private static final String MAX_HASHTAGS_PER_POST = "max_hashtags_per_post";

    private final HashtagService hashtagService;
    private final SystemSettingService systemSettingService;

    public PostCaptionHashtags(
            HashtagService hashtagService, SystemSettingService systemSettingService) {
        this.hashtagService = hashtagService;
        this.systemSettingService = systemSettingService;
    }

    /**
     * Returns the distinct hashtag tokens of a caption, up to the per-post limit.
     *
     * <p>Tokens are {@code #}-prefixed runs of Unicode letters, digits and underscores; the
     * normalisation and case-insensitive de-duplication are the hashtag service's responsibility.
     *
     * @param caption the caption; may be null
     * @return the tokens without the {@code #}, in order of first appearance
     */
    public List<String> extract(String caption) {
        if (caption == null || caption.isBlank()) {
            return List.of();
        }
        long maxTags = systemSettingService.getRequiredLong(MAX_HASHTAGS_PER_POST);
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        Matcher matcher = HASHTAG_PATTERN.matcher(caption);
        while (matcher.find() && tags.size() < maxTags) {
            tags.add(matcher.group(1));
        }
        return List.copyOf(tags);
    }

    /**
     * Refuses a caption naming a banned hashtag, before the caller mutates anything.
     *
     * <p>Called on every write path that could put a tag into {@code post_hashtags}, including the
     * draft ones. A draft carrying a banned tag could never be published, so refusing it where it
     * is written tells the author while the caption is still in front of them. The check reads the
     * caption being submitted, so removing the offending tag and retrying always succeeds.
     *
     * @param caption the caption being written
     * @throws AppException with {@code POST_BANNED_HASHTAG} naming the offending tags
     */
    public void rejectBanned(String caption) {
        List<String> banned = bannedNames(caption);
        if (!banned.isEmpty()) {
            throw new AppException(ApiErrorCode.POST_BANNED_HASHTAG, Map.of("bannedTags", banned));
        }
    }

    /**
     * Returns the banned hashtags a caption names, in normalised form.
     *
     * @param caption the caption; may be null
     * @return the banned names, empty when there are none
     */
    public List<String> bannedNames(String caption) {
        List<String> tags = extract(caption);
        return tags.isEmpty() ? List.of() : hashtagService.findBannedNames(tags);
    }

    /**
     * Associates the caption's hashtags with the post.
     *
     * @param postId the post
     * @param caption the caption; may be null
     */
    public void associate(UUID postId, String caption) {
        List<String> tags = extract(caption);
        if (!tags.isEmpty()) {
            hashtagService.upsertHashtagsForPost(postId, tags);
        }
    }

    /**
     * Associates the given hashtags with the post, leaving out the banned ones.
     *
     * <p>For the moderation restore only: every other write path refuses a banned hashtag.
     *
     * @param postId the post
     * @param tags the hashtag tokens the caption names
     * @return the normalised names that were banned and therefore not associated
     */
    public List<String> upsertSkippingBanned(UUID postId, List<String> tags) {
        return hashtagService.upsertHashtagsForPostSkippingBanned(postId, tags);
    }

    /**
     * Replaces the post's hashtag associations with the caption's current ones.
     *
     * @param postId the post
     * @param caption the caption; may be null
     */
    public void refresh(UUID postId, String caption) {
        hashtagService.removeHashtagsForPost(postId);
        associate(postId, caption);
    }

    /**
     * Removes every hashtag association of the post.
     *
     * @param postId the post
     */
    public void detach(UUID postId) {
        hashtagService.removeHashtagsForPost(postId);
    }
}
