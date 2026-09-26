package com.app.modules.hashtag.service;

import java.util.UUID;

import com.app.modules.hashtag.dto.response.HashtagDetailResponse;

/**
 * Single-hashtag reads for the hashtag detail surface, by name or by id.
 *
 * <p>Both methods apply the same lifecycle gate: a row an administrator has taken out of
 * circulation is refused rather than returned empty, because an empty page cannot be told apart
 * from a hashtag that simply has no posts yet, and a client has to word those two states
 * differently.
 */
public interface HashtagLookupService {

    /**
     * Resolves a hashtag by its name, so a shared or deep-linked URL such as {@code /tags/devlife}
     * reaches the same record the post write path created.
     *
     * <p>The supplied name is normalized with {@link HashtagService#normalize(String)} - the same
     * function the caption extraction path uses - so a leading {@code #}, surrounding whitespace
     * and upper case all resolve to the stored row. A divergence between write-side and read-side
     * normalization is what makes a hashtag reachable on write and unreachable on read.
     *
     * @param rawName hashtag name as supplied by the client, with or without a leading {@code #}
     * @return the hashtag record including its post count and lifecycle status
     * @throws com.app.common.exception.AppException {@code HASHTAG_NOT_FOUND} when no row carries
     *     the normalized name, or {@code HASHTAG_UNAVAILABLE} when the row is banned or deleted
     */
    HashtagDetailResponse getByName(String rawName);

    /**
     * Resolves a hashtag by its identifier, applying the same lifecycle gate as {@link
     * #getByName(String)}.
     *
     * @param hashtagId hashtag identifier
     * @return the hashtag record including its post count and lifecycle status
     * @throws com.app.common.exception.AppException {@code HASHTAG_NOT_FOUND} when no row carries
     *     the id, or {@code HASHTAG_UNAVAILABLE} when the row is banned or deleted
     */
    HashtagDetailResponse getById(UUID hashtagId);
}
