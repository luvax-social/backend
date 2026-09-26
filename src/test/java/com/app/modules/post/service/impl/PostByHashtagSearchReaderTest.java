package com.app.modules.post.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;

import com.app.common.response.CursorPageResponse;
import com.app.modules.post.dto.response.PostResponse;
import com.app.modules.post.repository.PostRepository;
import com.app.modules.post.service.PostVisibilityService;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;

@ExtendWith(MockitoExtension.class)
class PostByHashtagSearchReaderTest {

    private static final UUID VIEWER_ID = UUID.randomUUID();
    private static final UUID HASHTAG_ID = UUID.randomUUID();

    @Mock private ElasticsearchOperations elasticsearchOperations;
    @Mock private PostRepository postRepository;
    @Mock private PostVisibilityService postVisibilityService;
    @Mock private PostResponseAssembler postResponseAssembler;
    @Mock private PostByHashtagPostgresReader postgresReader;

    private PostByHashtagSearchReader reader;

    @BeforeEach
    void setUp() {
        reader =
                new PostByHashtagSearchReader(
                        elasticsearchOperations,
                        postRepository,
                        postVisibilityService,
                        postResponseAssembler,
                        postgresReader);
    }

    private static CursorPageResponse<PostResponse> emptyPage() {
        return CursorPageResponse.of(List.of(), false, null, null, false);
    }

    // A transport failure is an availability failure, so the read degrades to the PostgreSQL join
    // rather than surfacing an error: post_hashtags is the source of truth for hashtag membership,
    // so the degraded answer is still complete.
    @Test
    void readFallback_transportFailure_readsFromPostgres() {
        when(postgresReader.read(VIEWER_ID, HASHTAG_ID, 0, 20)).thenReturn(emptyPage());

        CursorPageResponse<PostResponse> result =
                reader.readFallback(
                        VIEWER_ID,
                        HASHTAG_ID,
                        0,
                        20,
                        new DataAccessResourceFailureException("connection refused"));

        assertThat(result).isNotNull();
        verify(postgresReader).read(VIEWER_ID, HASHTAG_ID, 0, 20);
    }

    @Test
    void readFallback_ioFailureNestedInCause_readsFromPostgres() {
        when(postgresReader.read(VIEWER_ID, HASHTAG_ID, 40, 20)).thenReturn(emptyPage());

        reader.readFallback(
                VIEWER_ID,
                HASHTAG_ID,
                40,
                20,
                new RuntimeException("wrapped", new IOException("socket closed")));

        verify(postgresReader).read(VIEWER_ID, HASHTAG_ID, 40, 20);
    }

    @Test
    void readFallback_openCircuit_readsFromPostgres() {
        CallNotPermittedException openCircuit =
                CallNotPermittedException.createCallNotPermittedException(
                        CircuitBreaker.ofDefaults("elasticsearchSearch"));
        when(postgresReader.read(VIEWER_ID, HASHTAG_ID, 0, 20)).thenReturn(emptyPage());

        reader.readFallback(VIEWER_ID, HASHTAG_ID, 0, 20, openCircuit);

        verify(postgresReader).read(VIEWER_ID, HASHTAG_ID, 0, 20);
    }

    // The fallback preserves the caller's cursor position rather than restarting at zero, so a
    // request that degrades mid-pagination continues from where the client was.
    @Test
    void readFallback_preservesOffsetAndLimit() {
        when(postgresReader.read(VIEWER_ID, HASHTAG_ID, 60, 15)).thenReturn(emptyPage());

        reader.readFallback(
                VIEWER_ID, HASHTAG_ID, 60, 15, new DataAccessResourceFailureException("down"));

        verify(postgresReader).read(VIEWER_ID, HASHTAG_ID, 60, 15);
    }

    // A programming or data error must surface, not be reported as a successful degraded read.
    @Test
    void readFallback_nonAvailabilityFailure_rethrows() {
        assertThatThrownBy(
                        () ->
                                reader.readFallback(
                                        VIEWER_ID,
                                        HASHTAG_ID,
                                        0,
                                        20,
                                        new IllegalArgumentException("bug")))
                .isInstanceOf(IllegalArgumentException.class);

        verify(postgresReader, never()).read(any(), any(), anyInt(), anyInt());
    }
}
