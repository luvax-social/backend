package com.app.modules.post.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.common.pagination.OffsetCursorCodec;
import com.app.common.response.CursorPageResponse;
import com.app.modules.hashtag.service.HashtagLookupService;
import com.app.modules.post.dto.response.PostResponse;

@ExtendWith(MockitoExtension.class)
class PostByHashtagServiceImplTest {

    private static final UUID VIEWER_ID = UUID.randomUUID();
    private static final UUID HASHTAG_ID = UUID.randomUUID();

    @Mock private HashtagLookupService hashtagLookupService;
    @Mock private PostByHashtagSearchReader searchReader;

    private PostByHashtagServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PostByHashtagServiceImpl(hashtagLookupService, searchReader);
    }

    private static CursorPageResponse<PostResponse> emptyPage() {
        return CursorPageResponse.of(List.of(), false, null, null, false);
    }

    @Test
    void findPostsByHashtag_firstPage_delegatesWithDecodedOffsetAndClampedLimit() {
        when(searchReader.read(VIEWER_ID, HASHTAG_ID, 0, 20)).thenReturn(emptyPage());

        CursorPageResponse<PostResponse> result =
                service.findPostsByHashtag(VIEWER_ID, HASHTAG_ID, null, 20);

        assertThat(result).isNotNull();
        verify(searchReader).read(VIEWER_ID, HASHTAG_ID, 0, 20);
    }

    @ParameterizedTest
    @CsvSource({"0, 20", "-5, 20", "1000, 100"})
    void findPostsByHashtag_limitOutOfRange_isClamped(int requested, int expected) {
        when(searchReader.read(eq(VIEWER_ID), eq(HASHTAG_ID), eq(0), eq(expected)))
                .thenReturn(emptyPage());

        service.findPostsByHashtag(VIEWER_ID, HASHTAG_ID, null, requested);

        verify(searchReader).read(VIEWER_ID, HASHTAG_ID, 0, expected);
    }

    // The lifecycle gate must run before the Elasticsearch read, not after, so a banned hashtag
    // costs a single indexed row read rather than a search round trip.
    @Test
    void findPostsByHashtag_appliesLifecycleGateBeforeReading() {
        when(searchReader.read(VIEWER_ID, HASHTAG_ID, 0, 20)).thenReturn(emptyPage());

        service.findPostsByHashtag(VIEWER_ID, HASHTAG_ID, null, 20);

        InOrder inOrder = Mockito.inOrder(hashtagLookupService, searchReader);
        inOrder.verify(hashtagLookupService).getById(HASHTAG_ID);
        inOrder.verify(searchReader).read(VIEWER_ID, HASHTAG_ID, 0, 20);
    }

    // The refusal has to propagate rather than be swallowed into an empty page, and it must not
    // reach the breaker-wrapped reader, because a caller error is not an Elasticsearch outage.
    @Test
    void findPostsByHashtag_unavailableHashtag_propagatesWithoutReading() {
        Mockito.doThrow(new AppException(ApiErrorCode.HASHTAG_UNAVAILABLE))
                .when(hashtagLookupService)
                .getById(HASHTAG_ID);

        assertThatThrownBy(() -> service.findPostsByHashtag(VIEWER_ID, HASHTAG_ID, null, 20))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ApiErrorCode.HASHTAG_UNAVAILABLE);

        verify(searchReader, never()).read(any(), any(), anyInt(), anyInt());
    }

    // A window past Elasticsearch's max_result_window must be refused with its own code. Left to
    // fail naturally it surfaces as an UncategorizedElasticsearchException, which the availability
    // classifier cannot tell from an outage: the request would silently degrade to a linear
    // PostgreSQL skip and, worse, charge a failure to a circuit breaker shared with two other
    // search surfaces.
    @Test
    void findPostsByHashtag_windowBeyondMaxResultWindow_throwsDepthExceeded() {
        String deepCursor = OffsetCursorCodec.encode(9_999);

        assertThatThrownBy(() -> service.findPostsByHashtag(VIEWER_ID, HASHTAG_ID, deepCursor, 100))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ApiErrorCode.PAGINATION_DEPTH_EXCEEDED);

        verify(searchReader, never()).read(any(), any(), anyInt(), anyInt());
    }

    // The depth guard runs before the lifecycle gate, so an out-of-range request costs no database
    // read at all.
    @Test
    void findPostsByHashtag_depthExceeded_doesNotEvenResolveTheHashtag() {
        String deepCursor = OffsetCursorCodec.encode(10_000);

        assertThatThrownBy(() -> service.findPostsByHashtag(VIEWER_ID, HASHTAG_ID, deepCursor, 20))
                .isInstanceOf(AppException.class);

        verify(hashtagLookupService, never()).getById(any());
    }

    // The boundary itself must be servable: offset + limit + 1 exactly equal to the window is the
    // deepest legal request, and rejecting it would refuse a page Elasticsearch can serve.
    @Test
    void findPostsByHashtag_windowExactlyAtLimit_isServed() {
        String cursor = OffsetCursorCodec.encode(9_979);
        when(searchReader.read(VIEWER_ID, HASHTAG_ID, 9_979, 20)).thenReturn(emptyPage());

        service.findPostsByHashtag(VIEWER_ID, HASHTAG_ID, cursor, 20);

        verify(searchReader).read(VIEWER_ID, HASHTAG_ID, 9_979, 20);
    }
}
