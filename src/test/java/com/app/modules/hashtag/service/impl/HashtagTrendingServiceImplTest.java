package com.app.modules.hashtag.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.app.common.response.PageResponse;
import com.app.modules.hashtag.config.HashtagProperties;
import com.app.modules.hashtag.dto.response.HashtagTrendingResponse;
import com.app.modules.hashtag.entity.Hashtag;
import com.app.modules.hashtag.entity.HashtagTrending;
import com.app.modules.hashtag.entity.HashtagTrendingId;
import com.app.modules.hashtag.enums.HashtagStatus;
import com.app.modules.hashtag.enums.TrendingSource;
import com.app.modules.hashtag.mapper.HashtagMapper;
import com.app.modules.hashtag.repository.HashtagRepository;
import com.app.modules.hashtag.repository.HashtagTrendingRepository;

@ExtendWith(MockitoExtension.class)
class HashtagTrendingServiceImplTest {

    @Mock private HashtagTrendingRepository hashtagTrendingRepository;
    @Mock private HashtagRepository hashtagRepository;
    @Mock private HashtagMapper hashtagMapper;
    @Mock private HashtagProperties properties;
    @Mock private JdbcTemplate jdbcTemplate;

    private HashtagTrendingServiceImpl service;

    @BeforeEach
    void setUp() {
        service =
                new HashtagTrendingServiceImpl(
                        hashtagTrendingRepository,
                        hashtagRepository,
                        hashtagMapper,
                        properties,
                        jdbcTemplate);
    }

    @Test
    void getTrending_noSnapshot_returnsEmptyPageWithoutQueryingRows() {
        // No latest period exists: queryForObject returns null for a NULL aggregate result.
        when(jdbcTemplate.queryForObject(anyString(), eq(OffsetDateTime.class))).thenReturn(null);

        PageResponse<HashtagTrendingResponse> page = service.getTrending(PageRequest.of(0, 20));

        assertThat(page.getContent()).isEmpty();
        verifyNoInteractions(hashtagTrendingRepository, hashtagRepository);
    }

    // P7-BE-008. A hashtag outside the current snapshot has no window count, so postCount is null
    // rather than the lifetime association total. The two are different measurements and putting
    // them in one column makes "1 posts" this window read as smaller than "40 posts" since 2025 -
    // a comparison the reader cannot make and is not told they are making. The wire type is
    // Integer for exactly this reason, and every consumer needs a null branch, so the contract is
    // pinned here rather than left to a renderer to discover.
    @Test
    void describeHashtags_hashtagOutsideTheSnapshot_hasNoWindowCount() {
        UUID hashtagId = UUID.randomUUID();
        Hashtag hashtag =
                Hashtag.builder()
                        .id(hashtagId)
                        .name("goldprice")
                        .status(HashtagStatus.ACTIVE)
                        // The lifetime total, which must not be substituted for a window count.
                        .postCount(40)
                        .build();
        when(jdbcTemplate.queryForObject(anyString(), eq(OffsetDateTime.class))).thenReturn(null);
        when(hashtagRepository.findAllById(List.of(hashtagId))).thenReturn(List.of(hashtag));

        List<HashtagTrendingResponse> described = service.describeHashtags(List.of(hashtagId));

        assertThat(described).hasSize(1);
        assertThat(described.get(0).postCount()).isNull();
        assertThat(described.get(0).rank()).isZero();
        assertThat(described.get(0).name()).isEqualTo("goldprice");
    }

    @Test
    void getTrending_withSnapshot_returnsHashtagContent() {
        OffsetDateTime latestPeriod = OffsetDateTime.now(ZoneOffset.UTC);
        UUID hashtagId = UUID.randomUUID();
        HashtagTrendingId trendingId = new HashtagTrendingId(hashtagId, latestPeriod);
        HashtagTrending row =
                HashtagTrending.builder().id(trendingId).rank(1).postCount(42).build();
        Hashtag hashtag = Hashtag.builder().id(hashtagId).name("java").build();
        when(jdbcTemplate.queryForObject(anyString(), eq(OffsetDateTime.class)))
                .thenReturn(latestPeriod);
        when(hashtagTrendingRepository.findByPeriodPinnedFirst(eq(latestPeriod), eq(20), eq(0)))
                .thenReturn(List.of(row));
        when(hashtagRepository.findAllById(List.of(hashtagId))).thenReturn(List.of(hashtag));
        when(hashtagTrendingRepository.countByIdPeriodStart(latestPeriod)).thenReturn(1L);

        PageResponse<HashtagTrendingResponse> page = service.getTrending(PageRequest.of(0, 20));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).hashtagId()).isEqualTo(hashtagId);
        assertThat(page.getContent().get(0).name()).isEqualTo("java");
        assertThat(page.getContent().get(0).pinned()).isFalse();
        assertThat(page.getContent().get(0).source()).isEqualTo(TrendingSource.PLATFORM);
        assertThat(page.getTotalElements()).isEqualTo(1L);
    }

    @Test
    void getTrending_withSnapshotButNoRows_returnsEmptyPage() {
        OffsetDateTime latestPeriod = OffsetDateTime.now(ZoneOffset.UTC);

        when(jdbcTemplate.queryForObject(anyString(), eq(OffsetDateTime.class)))
                .thenReturn(latestPeriod);
        when(hashtagTrendingRepository.findByPeriodPinnedFirst(eq(latestPeriod), eq(20), eq(0)))
                .thenReturn(List.of());
        when(hashtagRepository.findAllById(List.of())).thenReturn(List.of());
        when(hashtagTrendingRepository.countByIdPeriodStart(latestPeriod)).thenReturn(0L);

        PageResponse<HashtagTrendingResponse> page = service.getTrending(PageRequest.of(0, 20));

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
        verifyNoInteractions(hashtagMapper);
    }
}
