package com.app.modules.post.service.impl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.app.common.outbox.service.OutboxService;
import com.app.modules.hashtag.service.HashtagService;
import com.app.modules.post.entity.Post;
import com.app.modules.post.place.service.PlaceIndexEventPublisher;

@ExtendWith(MockitoExtension.class)
class PostIndexEventPublisherTest {

    private static final UUID POST_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID PLACE_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Mock private OutboxService outboxService;
    @Mock private HashtagService hashtagService;
    @Mock private PlaceIndexEventPublisher placeIndexEventPublisher;

    private PostIndexEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher =
                new PostIndexEventPublisher(
                        outboxService, hashtagService, placeIndexEventPublisher);
    }

    private Post post(UUID placeId) {
        return Post.builder()
                .id(POST_ID)
                .userId(OWNER_ID)
                .placeId(placeId)
                .createdAt(OffsetDateTime.parse("2026-01-01T10:00:00Z"))
                .publishedAt(OffsetDateTime.parse("2026-01-01T11:00:00Z"))
                .build();
    }

    @Test
    void enqueueUpsert_postAtAPlace_reconcilesThePlaceDocument() {
        when(hashtagService.getHashtagIdsForPosts(List.of(POST_ID))).thenReturn(Map.of());

        publisher.enqueueUpsert(post(PLACE_ID));

        verify(placeIndexEventPublisher).enqueueSync(PLACE_ID);
    }

    @Test
    void enqueueUpsert_postWithoutAPlace_enqueuesNoPlaceEvent() {
        when(hashtagService.getHashtagIdsForPosts(List.of(POST_ID))).thenReturn(Map.of());

        publisher.enqueueUpsert(post(null));

        verify(placeIndexEventPublisher, never()).enqueueSync(any());
    }

    @Test
    void enqueueDelete_postAtAPlace_reconcilesThePlaceDocument() {
        publisher.enqueueDelete(post(PLACE_ID));

        verify(placeIndexEventPublisher).enqueueSync(PLACE_ID);
    }

    @Test
    void syncPlace_nullPlace_enqueuesNothing() {
        publisher.syncPlace(null);

        verify(placeIndexEventPublisher, never()).enqueueSync(any());
    }
}
