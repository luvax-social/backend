package com.app.modules.users.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.common.pagination.OffsetCursorCodec;
import com.app.modules.recommendation.service.UserEventRecorder;
import com.app.modules.social.service.SocialService;
import com.app.modules.users.repository.UserRepository;
import com.app.modules.users.service.UserSearchService;

@ExtendWith(MockitoExtension.class)
class UserSearchServiceImplTest {

    @Mock private UserRepository userRepository;
    @Mock private SocialService socialService;
    @Mock private UserEventRecorder userEventRecorder;

    private UserSearchService service;

    @BeforeEach
    void setUp() {
        service = new UserSearchServiceImpl(userRepository, socialService, userEventRecorder);
    }

    @Test
    void searchUsers_singleCharacterQuery_rejectedBeforeAnyQuery() {
        assertThatThrownBy(() -> service.searchUsers(UUID.randomUUID(), "a", null, 20))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.BAD_REQUEST);

        verify(userRepository, never()).searchByUsername(anyString(), any(), anyInt(), anyInt());
    }

    @Test
    void searchUsers_blankAndWhitespaceQuery_rejectedBeforeAnyQuery() {
        UUID viewer = UUID.randomUUID();

        assertThatThrownBy(() -> service.searchUsers(viewer, "   ", null, 20))
                .isInstanceOf(AppException.class);
        assertThatThrownBy(() -> service.searchUsers(viewer, "", null, 20))
                .isInstanceOf(AppException.class);
        assertThatThrownBy(() -> service.searchUsers(viewer, null, null, 20))
                .isInstanceOf(AppException.class);

        verify(userRepository, never()).searchByUsername(anyString(), any(), anyInt(), anyInt());
    }

    @Test
    void searchUsers_queryIsTrimmedBeforeMatching() {
        UUID viewer = UUID.randomUUID();
        when(userRepository.searchByUsername(anyString(), any(), anyInt(), anyInt()))
                .thenReturn(List.of());

        service.searchUsers(viewer, "  jane  ", null, 20);

        verify(userRepository).searchByUsername("jane", viewer, 21, 0);
    }

    @Test
    void searchUsers_overfetchesOneRowToDetectNextPage() {
        UUID viewer = UUID.randomUUID();
        when(userRepository.searchByUsername(anyString(), any(), anyInt(), anyInt()))
                .thenReturn(List.of());

        service.searchUsers(viewer, "jane", null, 5);

        verify(userRepository).searchByUsername("jane", viewer, 6, 0);
    }

    @Test
    void searchUsers_limitIsClampedAndDefaulted() {
        UUID viewer = UUID.randomUUID();
        when(userRepository.searchByUsername(anyString(), any(), anyInt(), anyInt()))
                .thenReturn(List.of());

        service.searchUsers(viewer, "jane", null, 5_000);
        verify(userRepository).searchByUsername("jane", viewer, 101, 0);

        service.searchUsers(viewer, "jane", null, 0);
        verify(userRepository).searchByUsername("jane", viewer, 21, 0);
    }

    @Test
    void searchUsers_cursorBeyondCap_throwsInvalidCursor() {
        String tooDeep = OffsetCursorCodec.encode(OffsetCursorCodec.MAX_OFFSET + 1);

        assertThatThrownBy(() -> service.searchUsers(UUID.randomUUID(), "jane", tooDeep, 20))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.INVALID_CURSOR);

        verify(userRepository, never()).searchByUsername(anyString(), any(), anyInt(), anyInt());
    }

    @Test
    void searchUsers_emptyResult_returnsEmptyPageWithNoRelationshipLookup() {
        UUID viewer = UUID.randomUUID();
        when(userRepository.searchByUsername(anyString(), any(), anyInt(), anyInt()))
                .thenReturn(List.of());

        var page = service.searchUsers(viewer, "nobody", null, 20);

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getPageInfo().isHasNextPage()).isFalse();
        assertThat(page.getPageInfo().getEndCursor()).isNull();
        verify(socialService, never()).loadRelationships(any(), any());
    }

    /**
     * The substitute for the design's "search backend unavailable" case. There is no Elasticsearch
     * tier here and deliberately no circuit breaker, so the property under test is the inverse of
     * the hashtag precedent: a database availability failure must reach the caller, never be masked
     * as an empty page. An empty page would be indistinguishable from "no such user".
     */
    @Test
    void searchUsers_databaseUnavailable_propagatesInsteadOfReturningEmptyPage() {
        UUID viewer = UUID.randomUUID();
        when(userRepository.searchByUsername(anyString(), any(), anyInt(), anyInt()))
                .thenThrow(new DataAccessResourceFailureException("connection refused"));

        assertThatThrownBy(() -> service.searchUsers(viewer, "jane", null, 20))
                .isInstanceOf(DataAccessResourceFailureException.class)
                .hasMessageContaining("connection refused");
    }

    @Test
    void searchUsers_relationshipsAreResolvedInOneBatchForThePage() {
        UUID viewer = UUID.randomUUID();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(userRepository.searchByUsername(anyString(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(hit(a, "aaa"), hit(b, "bbb")));
        when(socialService.loadRelationships(viewer, List.of(a, b))).thenReturn(Map.of());

        var page = service.searchUsers(viewer, "aa", null, 20);

        assertThat(page.getContent()).hasSize(2);
        // Absent from the relationship map must default to all-false, never null.
        assertThat(page.getContent().get(0).viewerState()).isNotNull();
        assertThat(page.getContent().get(0).viewerState().isFollowing()).isFalse();
        verify(socialService).loadRelationships(viewer, List.of(a, b));
    }

    private static com.app.modules.users.repository.UserSearchProjection hit(
            UUID id, String username) {
        return new com.app.modules.users.repository.UserSearchProjection() {
            @Override
            public UUID getId() {
                return id;
            }

            @Override
            public String getVerifiedCategory() {
                return null;
            }

            @Override
            public String getUsername() {
                return username;
            }

            @Override
            public String getDisplayName() {
                return username;
            }

            @Override
            public String getAvatarUrl() {
                return null;
            }

            @Override
            public boolean getIsVerified() {
                return false;
            }
        };
    }
}
