package com.app.modules.users.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.app.common.response.UserSummaryResponse;
import com.app.modules.users.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class UserSummaryServiceImplTest {

    @Mock private UserRepository userRepository;

    private UserSummaryServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new UserSummaryServiceImpl(userRepository);
    }

    @Test
    void exists_rowPresent_isTrueWhetherOrNotItIsSoftDeleted() {
        UUID id = UUID.randomUUID();
        when(userRepository.existsIncludingSoftDeleted(id)).thenReturn(true);

        assertThat(service.exists(id)).isTrue();
    }

    @Test
    void exists_rowAbsent_isFalse() {
        UUID id = UUID.randomUUID();
        when(userRepository.existsIncludingSoftDeleted(id)).thenReturn(false);

        assertThat(service.exists(id)).isFalse();
    }

    @Test
    void exists_nullId_isFalseWithoutQuery() {
        assertThat(service.exists(null)).isFalse();
        verify(userRepository, times(0))
                .existsIncludingSoftDeleted(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void loadSummaries_emptyInput_returnsEmptyWithoutQuery() {
        assertThat(service.loadSummaries(List.of())).isEmpty();
        verify(userRepository, times(0)).findSummariesByIdIn(anyCollection());
    }

    @Test
    void loadSummaries_duplicateIds_queriedOnceAndReturnsSingleEntry() {
        UUID id = UUID.randomUUID();
        UserSummaryResponse summary = new UserSummaryResponse(id, "alice", "Alice", null, true);
        when(userRepository.findSummariesByIdIn(anyCollection())).thenReturn(List.of(summary));

        Map<UUID, UserSummaryResponse> result = service.loadSummaries(List.of(id, id, id));

        assertThat(result).hasSize(1).containsEntry(id, summary);
        ArgumentCaptor<java.util.Collection<UUID>> captor =
                ArgumentCaptor.forClass(java.util.Collection.class);
        verify(userRepository, times(1)).findSummariesByIdIn(captor.capture());
        assertThat(captor.getValue()).containsExactly(id);
    }

    @Test
    void loadSummaries_unknownOrDeletedId_returnsPlaceholder() {
        UUID live = UUID.randomUUID();
        UUID missing = UUID.randomUUID();
        UserSummaryResponse liveSummary = new UserSummaryResponse(live, "bob", "Bob", "cdn", false);
        when(userRepository.findSummariesByIdIn(anyCollection())).thenReturn(List.of(liveSummary));

        Map<UUID, UserSummaryResponse> result = service.loadSummaries(List.of(live, missing));

        assertThat(result).hasSize(2);
        assertThat(result.get(live)).isEqualTo(liveSummary);
        assertThat(result.get(missing))
                .isEqualTo(
                        new UserSummaryResponse(
                                missing,
                                null,
                                UserSummaryServiceImpl.DELETED_DISPLAY_NAME,
                                null,
                                false));
    }
}
