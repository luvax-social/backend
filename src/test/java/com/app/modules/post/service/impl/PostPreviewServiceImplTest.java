package com.app.modules.post.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.app.modules.post.dto.response.PostPreviewResponse;
import com.app.modules.post.repository.PostPreviewRepository;
import com.app.modules.post.repository.PostPreviewRepository.PostPreviewRow;
import com.app.modules.post.service.PostVisibilityService;

@ExtendWith(MockitoExtension.class)
class PostPreviewServiceImplTest {

    @Mock private PostPreviewRepository postPreviewRepository;
    @Mock private PostVisibilityService postVisibilityService;

    private PostPreviewServiceImpl service;
    private final UUID viewer = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new PostPreviewServiceImpl(postPreviewRepository, postVisibilityService);
    }

    @Test
    void loadPreviews_publishedAndVisible_isAvailableWithItsFirstMedia() {
        PostPreviewRow row = row("published", false, false, owner);
        stub(row, Set.of(owner));

        PostPreviewResponse preview = service.loadPreviews(viewer, List.of(row.id())).get(row.id());

        assertThat(preview.available()).isTrue();
        assertThat(preview.media().url()).isEqualTo("https://cdn/p.jpg");
        assertThat(preview.media().mediaType()).isEqualTo("image");
    }

    @Test
    void loadPreviews_deletedModeratedDraftOrHiddenOwner_isUnavailableWithoutMedia() {
        PostPreviewRow deleted = row("published", true, false, owner);
        PostPreviewRow moderated = row("removed", false, true, owner);
        PostPreviewRow draft = row("draft", false, false, owner);
        PostPreviewRow hidden = row("published", false, false, UUID.randomUUID());
        when(postPreviewRepository.findPreviewRows(anyCollection()))
                .thenReturn(List.of(deleted, moderated, draft, hidden));
        when(postVisibilityService.filterVisibleOwnerIds(any(), any())).thenReturn(Set.of(owner));

        Map<UUID, PostPreviewResponse> previews =
                service.loadPreviews(
                        viewer, List.of(deleted.id(), moderated.id(), draft.id(), hidden.id()));

        assertThat(previews.values()).allSatisfy(p -> assertThat(p.available()).isFalse());
        assertThat(previews.values()).allSatisfy(p -> assertThat(p.media()).isNull());
    }

    @Test
    void loadPreviews_archivedPost_isAvailableToItsOwnerOnly() {
        PostPreviewRow archived = row("archived", false, false, viewer);
        stub(archived, Set.of(viewer));

        assertThat(
                        service.loadPreviews(viewer, List.of(archived.id()))
                                .get(archived.id())
                                .available())
                .isTrue();
    }

    @Test
    void loadPreviews_unknownPost_isUnavailable() {
        UUID missing = UUID.randomUUID();
        when(postPreviewRepository.findPreviewRows(anyCollection())).thenReturn(List.of());
        when(postVisibilityService.filterVisibleOwnerIds(any(), any())).thenReturn(Set.of());

        assertThat(service.loadPreviews(viewer, List.of(missing)).get(missing).available())
                .isFalse();
    }

    private void stub(PostPreviewRow row, Set<UUID> visibleOwners) {
        when(postPreviewRepository.findPreviewRows(anyCollection())).thenReturn(List.of(row));
        when(postVisibilityService.filterVisibleOwnerIds(any(), any())).thenReturn(visibleOwners);
    }

    private static PostPreviewRow row(
            String status, boolean deleted, boolean moderated, UUID ownerId) {
        return new PostPreviewRow(
                UUID.randomUUID(),
                ownerId,
                status,
                deleted,
                moderated,
                "https://cdn/p.jpg",
                "LKO2",
                "image",
                1080,
                1350);
    }
}
