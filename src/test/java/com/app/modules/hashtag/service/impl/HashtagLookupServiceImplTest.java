package com.app.modules.hashtag.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.modules.hashtag.dto.response.HashtagDetailResponse;
import com.app.modules.hashtag.entity.Hashtag;
import com.app.modules.hashtag.enums.HashtagStatus;
import com.app.modules.hashtag.repository.HashtagRepository;
import com.app.modules.hashtag.service.HashtagService;

@ExtendWith(MockitoExtension.class)
class HashtagLookupServiceImplTest {

    private static final UUID HASHTAG_ID = UUID.randomUUID();

    @Mock private HashtagRepository hashtagRepository;
    @Mock private HashtagService hashtagService;

    private HashtagLookupServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new HashtagLookupServiceImpl(hashtagRepository, hashtagService);
    }

    private static Hashtag hashtag(HashtagStatus status) {
        Hashtag hashtag = new Hashtag();
        hashtag.setId(HASHTAG_ID);
        hashtag.setName("devlife");
        hashtag.setStatus(status);
        hashtag.setCreatedAt(OffsetDateTime.now());
        return hashtag;
    }

    @Test
    void getByName_activeHashtag_returnsRecord() {
        when(hashtagService.normalize("devlife")).thenReturn("devlife");
        when(hashtagRepository.findByName("devlife"))
                .thenReturn(Optional.of(hashtag(HashtagStatus.ACTIVE)));

        HashtagDetailResponse result = service.getByName("devlife");

        assertThat(result.id()).isEqualTo(HASHTAG_ID);
        assertThat(result.name()).isEqualTo("devlife");
        assertThat(result.status()).isEqualTo(HashtagStatus.ACTIVE);
    }

    // hashtags.post_count counts associations ever made: removing or soft-deleting a post leaves
    // its post_hashtags row behind. Printed above a grid that lists only published, non-deleted
    // posts, it read "40 posts" over 36 tiles on the seeded goldprice page.
    @Test
    void getByName_reportsListablePostsRatherThanTheTriggerMaintainedAssociationCount() {
        Hashtag hashtag = hashtag(HashtagStatus.ACTIVE);
        hashtag.setPostCount(40);
        when(hashtagService.normalize("devlife")).thenReturn("devlife");
        when(hashtagRepository.findByName("devlife")).thenReturn(Optional.of(hashtag));
        when(hashtagRepository.countListablePosts(HASHTAG_ID)).thenReturn(37);

        HashtagDetailResponse result = service.getByName("devlife");

        assertThat(result.postCount()).isEqualTo(37);
    }

    // The whole point of delegating to HashtagService.normalize is that the read path and the
    // caption write path agree on what a raw name resolves to. Asserting the lookup is performed
    // with the normalized form, never the raw input, is what pins that.
    @ParameterizedTest
    @ValueSource(strings = {"#devlife", "  #DevLife  ", "DEVLIFE", "#  "})
    void getByName_rawInput_looksUpTheNormalizedName(String raw) {
        when(hashtagService.normalize(raw)).thenReturn("devlife");
        when(hashtagRepository.findByName("devlife"))
                .thenReturn(Optional.of(hashtag(HashtagStatus.ACTIVE)));

        service.getByName(raw);

        verify(hashtagRepository).findByName("devlife");
        verify(hashtagRepository, never()).findByName(raw);
    }

    @Test
    void getByName_normalizerRejectsInput_throwsNotFoundWithoutQuerying() {
        when(hashtagService.normalize(anyString())).thenReturn("");

        assertThatThrownBy(() -> service.getByName("#"))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ApiErrorCode.HASHTAG_NOT_FOUND);

        verify(hashtagRepository, never()).findByName(anyString());
    }

    @Test
    void getByName_noRow_throwsNotFound() {
        when(hashtagService.normalize("nope")).thenReturn("nope");
        when(hashtagRepository.findByName("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getByName("nope"))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ApiErrorCode.HASHTAG_NOT_FOUND);
    }

    // A banned or deleted hashtag must be refused rather than returned, so a client can word
    // "not available" differently from "no posts yet". An empty page would collapse the two.
    @ParameterizedTest
    @ValueSource(strings = {"BANNED", "DELETED"})
    void getByName_outOfCirculation_throwsUnavailable(String status) {
        when(hashtagService.normalize("devlife")).thenReturn("devlife");
        when(hashtagRepository.findByName("devlife"))
                .thenReturn(Optional.of(hashtag(HashtagStatus.valueOf(status))));

        assertThatThrownBy(() -> service.getByName("devlife"))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ApiErrorCode.HASHTAG_UNAVAILABLE);
    }

    @Test
    void getById_activeHashtag_returnsRecord() {
        when(hashtagRepository.findById(HASHTAG_ID))
                .thenReturn(Optional.of(hashtag(HashtagStatus.ACTIVE)));

        assertThat(service.getById(HASHTAG_ID).id()).isEqualTo(HASHTAG_ID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"BANNED", "DELETED"})
    void getById_outOfCirculation_throwsUnavailable(String status) {
        when(hashtagRepository.findById(HASHTAG_ID))
                .thenReturn(Optional.of(hashtag(HashtagStatus.valueOf(status))));

        assertThatThrownBy(() -> service.getById(HASHTAG_ID))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ApiErrorCode.HASHTAG_UNAVAILABLE);
    }

    @Test
    void getById_noRow_throwsNotFound() {
        when(hashtagRepository.findById(HASHTAG_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(HASHTAG_ID))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ApiErrorCode.HASHTAG_NOT_FOUND);
    }
}
