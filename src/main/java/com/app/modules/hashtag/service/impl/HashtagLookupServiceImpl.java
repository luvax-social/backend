package com.app.modules.hashtag.service.impl;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.modules.hashtag.dto.response.HashtagDetailResponse;
import com.app.modules.hashtag.entity.Hashtag;
import com.app.modules.hashtag.enums.HashtagStatus;
import com.app.modules.hashtag.repository.HashtagRepository;
import com.app.modules.hashtag.service.HashtagLookupService;
import com.app.modules.hashtag.service.HashtagService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class HashtagLookupServiceImpl implements HashtagLookupService {

    private final HashtagRepository hashtagRepository;
    private final HashtagService hashtagService;

    @Override
    @Transactional(readOnly = true)
    public HashtagDetailResponse getByName(String rawName) {
        // Delegated rather than reimplemented: HashtagServiceImpl.normalize is the same function
        // the caption extraction path runs before insert, so a name that produced a row on write
        // resolves to that row on read. A second normalizer here would drift from it silently.
        String normalized = hashtagService.normalize(rawName);
        if (normalized.isEmpty()) {
            throw new AppException(ApiErrorCode.HASHTAG_NOT_FOUND);
        }
        return toResponse(
                hashtagRepository
                        .findByName(normalized)
                        .orElseThrow(() -> new AppException(ApiErrorCode.HASHTAG_NOT_FOUND)));
    }

    @Override
    @Transactional(readOnly = true)
    public HashtagDetailResponse getById(UUID hashtagId) {
        return toResponse(
                hashtagRepository
                        .findById(hashtagId)
                        .orElseThrow(() -> new AppException(ApiErrorCode.HASHTAG_NOT_FOUND)));
    }

    private HashtagDetailResponse toResponse(Hashtag hashtag) {
        if (hashtag.getStatus() != HashtagStatus.ACTIVE) {
            throw new AppException(ApiErrorCode.HASHTAG_UNAVAILABLE);
        }
        return new HashtagDetailResponse(
                hashtag.getId(),
                hashtag.getName(),
                // Not hashtag.getPostCount(): that counts associations ever made, including posts
                // since removed or soft-deleted, and it is rendered immediately above a grid that
                // lists only published, non-deleted ones. The two were answering different
                // questions in the same visual unit - goldprice read "40 posts" over 36 tiles.
                hashtagRepository.countListablePosts(hashtag.getId()),
                hashtag.getStatus(),
                hashtag.getCreatedAt(),
                hashtag.getPinnedAt() != null);
    }
}
