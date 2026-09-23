package com.app.modules.story.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.app.modules.story.entity.Story;
import com.app.modules.story.repository.StoryPreviewRepository;
import com.app.modules.story.repository.StoryPreviewRepository.StoryPreviewRow;
import com.app.modules.story.repository.StoryRepository;
import com.app.modules.story.service.StoryVisibilityService;

@ExtendWith(MockitoExtension.class)
class StoryPreviewServiceImplTest {

    private static final OffsetDateTime LATER = OffsetDateTime.now(ZoneOffset.UTC).plusHours(3);

    @Mock private StoryPreviewRepository storyPreviewRepository;
    @Mock private StoryRepository storyRepository;
    @Mock private StoryVisibilityService storyVisibilityService;

    private StoryPreviewServiceImpl service;
    private final UUID owner = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service =
                new StoryPreviewServiceImpl(
                        storyPreviewRepository, storyRepository, storyVisibilityService);
    }

    @Test
    void loadPreviews_liveStoryOfTheViewer_isAvailableWithoutAVisibilityLookup() {
        StoryPreviewRow row = row(false, false);
        when(storyPreviewRepository.findPreviewRows(anyCollection())).thenReturn(List.of(row));

        var preview = service.loadPreviews(owner, List.of(row.id())).get(row.id());

        assertThat(preview.available()).isTrue();
        assertThat(preview.media().url()).isEqualTo("https://cdn/s.jpg");
        assertThat(preview.expiresAt()).isEqualTo(LATER);
        verify(storyRepository, never()).findById(row.id());
    }

    @Test
    void loadPreviews_expiredOrTombstonedStory_isUnavailable() {
        StoryPreviewRow expired = row(false, true);
        StoryPreviewRow removed = row(true, false);
        when(storyPreviewRepository.findPreviewRows(anyCollection()))
                .thenReturn(List.of(expired, removed));

        var previews = service.loadPreviews(owner, List.of(expired.id(), removed.id()));

        assertThat(previews.get(expired.id()).available()).isFalse();
        assertThat(previews.get(expired.id()).media()).isNull();
        assertThat(previews.get(removed.id()).available()).isFalse();
    }

    @Test
    void loadPreviews_anotherViewer_isDecidedByStoryVisibility() {
        UUID viewer = UUID.randomUUID();
        StoryPreviewRow row = row(false, false);
        Story story = Story.builder().id(row.id()).userId(owner).build();
        when(storyPreviewRepository.findPreviewRows(anyCollection())).thenReturn(List.of(row));
        when(storyRepository.findById(row.id())).thenReturn(Optional.of(story));
        when(storyVisibilityService.isVisibleTo(viewer, story)).thenReturn(false);

        assertThat(service.loadPreviews(viewer, List.of(row.id())).get(row.id()).available())
                .isFalse();
    }

    private StoryPreviewRow row(boolean hidden, boolean expired) {
        return new StoryPreviewRow(
                UUID.randomUUID(),
                owner,
                hidden,
                expired,
                LATER,
                "https://cdn/s.jpg",
                null,
                "image",
                1080,
                1920);
    }
}
