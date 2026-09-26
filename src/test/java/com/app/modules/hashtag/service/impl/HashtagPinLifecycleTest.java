package com.app.modules.hashtag.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.modules.hashtag.entity.Hashtag;
import com.app.modules.hashtag.enums.HashtagStatus;
import com.app.modules.hashtag.repository.HashtagRepository;
import com.app.modules.hashtag.repository.HashtagTrendingRepository;
import com.app.modules.hashtag.service.HashtagIndexEventPublisher;
import com.app.modules.hashtag.service.HashtagService;

@ExtendWith(MockitoExtension.class)
class HashtagPinLifecycleTest {

    private static final UUID HASHTAG_ID = UUID.randomUUID();
    private static final UUID ACTOR_ID = UUID.randomUUID();

    @Mock private HashtagRepository hashtagRepository;
    @Mock private EntityManager entityManager;
    @Mock private HashtagTrendingRepository hashtagTrendingRepository;
    @Mock private HashtagIndexEventPublisher indexEventPublisher;
    @Mock private HashtagService hashtagService;

    private HashtagLifecycleServiceImpl service;

    @BeforeEach
    void setUp() {
        service =
                new HashtagLifecycleServiceImpl(
                        hashtagRepository,
                        entityManager,
                        hashtagTrendingRepository,
                        hashtagService,
                        indexEventPublisher);
    }

    private Hashtag hashtag(HashtagStatus status, OffsetDateTime pinnedAt) {
        Hashtag hashtag = new Hashtag();
        hashtag.setId(HASHTAG_ID);
        hashtag.setName("devlife");
        hashtag.setStatus(status);
        hashtag.setPinnedAt(pinnedAt);
        hashtag.setPinnedBy(pinnedAt == null ? null : ACTOR_ID);
        return hashtag;
    }

    @Test
    void pin_activeUnpinnedHashtag_setsPinAndActor() {
        Hashtag hashtag = hashtag(HashtagStatus.ACTIVE, null);
        when(hashtagRepository.findById(HASHTAG_ID)).thenReturn(Optional.of(hashtag));

        service.pin(ACTOR_ID, HASHTAG_ID);

        assertThat(hashtag.getPinnedAt()).isNotNull();
        assertThat(hashtag.getPinnedBy()).isEqualTo(ACTOR_ID);
        verify(hashtagRepository).save(hashtag);
    }

    // A pin promotes a term platform-wide, so a term an administrator has taken out of circulation
    // must never be pinned. This is a caller error and surfaces as one.
    @ParameterizedTest
    @ValueSource(strings = {"BANNED", "DELETED"})
    void pin_hashtagOutOfCirculation_isRejected(String status) {
        when(hashtagRepository.findById(HASHTAG_ID))
                .thenReturn(Optional.of(hashtag(HashtagStatus.valueOf(status), null)));

        assertThatThrownBy(() -> service.pin(ACTOR_ID, HASHTAG_ID))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ApiErrorCode.HASHTAG_UNAVAILABLE);

        verify(hashtagRepository, never()).save(any());
    }

    @Test
    void pin_alreadyPinned_isRejected() {
        when(hashtagRepository.findById(HASHTAG_ID))
                .thenReturn(Optional.of(hashtag(HashtagStatus.ACTIVE, OffsetDateTime.now())));

        assertThatThrownBy(() -> service.pin(ACTOR_ID, HASHTAG_ID))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ApiErrorCode.ADMIN_INVALID_TRANSITION);
    }

    @Test
    void pin_missingHashtag_isNotFound() {
        when(hashtagRepository.findById(HASHTAG_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.pin(ACTOR_ID, HASHTAG_ID))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ApiErrorCode.HASHTAG_NOT_FOUND);
    }

    @Test
    void unpin_pinnedHashtag_clearsBothColumns() {
        Hashtag hashtag = hashtag(HashtagStatus.ACTIVE, OffsetDateTime.now());
        when(hashtagRepository.findById(HASHTAG_ID)).thenReturn(Optional.of(hashtag));

        service.unpin(ACTOR_ID, HASHTAG_ID);

        assertThat(hashtag.getPinnedAt()).isNull();
        assertThat(hashtag.getPinnedBy()).isNull();
    }

    @Test
    void unpin_notPinned_isRejected() {
        when(hashtagRepository.findById(HASHTAG_ID))
                .thenReturn(Optional.of(hashtag(HashtagStatus.ACTIVE, null)));

        assertThatThrownBy(() -> service.unpin(ACTOR_ID, HASHTAG_ID))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ApiErrorCode.ADMIN_INVALID_TRANSITION);
    }

    // Leaving a pin on a banned hashtag would keep promoting the exact term the administrator acted
    // to suppress. Cleared inside changeStatus so no future caller of it can forget.
    @ParameterizedTest
    @ValueSource(strings = {"BANNED", "DELETED"})
    void changeStatus_outOfCirculation_clearsThePin(String status) {
        Hashtag hashtag = hashtag(HashtagStatus.ACTIVE, OffsetDateTime.now());
        when(hashtagRepository.findById(HASHTAG_ID)).thenReturn(Optional.of(hashtag));
        when(hashtagTrendingRepository.deleteAllByHashtagId(HASHTAG_ID)).thenReturn(1);

        service.changeStatus(ACTOR_ID, HASHTAG_ID, HashtagStatus.valueOf(status), "spam wave");

        assertThat(hashtag.getPinnedAt()).isNull();
        assertThat(hashtag.getPinnedBy()).isNull();
    }
}
