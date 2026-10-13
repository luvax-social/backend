package com.app.modules.comment.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.app.modules.comment.dto.response.CommentPreviewResponse;
import com.app.modules.comment.repository.CommentPreviewRepository;
import com.app.modules.comment.repository.CommentPreviewRepository.CommentPreviewRow;

@ExtendWith(MockitoExtension.class)
class CommentPreviewServiceImplTest {

    @Mock private CommentPreviewRepository commentPreviewRepository;

    private CommentPreviewServiceImpl service;
    private final UUID viewer = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new CommentPreviewServiceImpl(commentPreviewRepository);
    }

    @Test
    void loadPreviews_liveReply_carriesItsTextAndItsParentsText() {
        CommentPreviewRow row =
                new CommentPreviewRow(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "agreed",
                        false,
                        UUID.randomUUID(),
                        "approved",
                        false,
                        "the colours on this",
                        false,
                        UUID.randomUUID(),
                        "approved",
                        false);
        when(commentPreviewRepository.findPreviewRows(any(), anyCollection()))
                .thenReturn(List.of(row));

        CommentPreviewResponse preview =
                service.loadPreviews(viewer, List.of(row.id())).get(row.id());

        assertThat(preview.available()).isTrue();
        assertThat(preview.snippet()).isEqualTo("agreed");
        assertThat(preview.parentSnippet()).isEqualTo("the colours on this");
    }

    @Test
    void loadPreviews_parentRemoved_dropsOnlyTheParentText() {
        CommentPreviewRow row =
                new CommentPreviewRow(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "agreed",
                        false,
                        UUID.randomUUID(),
                        "approved",
                        false,
                        "removed words",
                        true,
                        UUID.randomUUID(),
                        "approved",
                        false);
        when(commentPreviewRepository.findPreviewRows(any(), anyCollection()))
                .thenReturn(List.of(row));

        CommentPreviewResponse preview =
                service.loadPreviews(viewer, List.of(row.id())).get(row.id());

        assertThat(preview.snippet()).isEqualTo("agreed");
        assertThat(preview.parentSnippet()).isNull();
    }

    @Test
    void loadPreviews_deletedOrRemovedComment_isUnavailableWithoutText() {
        CommentPreviewRow row =
                new CommentPreviewRow(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        null,
                        "gone",
                        true,
                        UUID.randomUUID(),
                        "approved",
                        false,
                        null,
                        true,
                        null,
                        null,
                        false);
        UUID missing = UUID.randomUUID();
        when(commentPreviewRepository.findPreviewRows(any(), anyCollection()))
                .thenReturn(List.of(row));

        var previews = service.loadPreviews(viewer, List.of(row.id(), missing));

        assertThat(previews.get(row.id()).available()).isFalse();
        assertThat(previews.get(row.id()).snippet()).isNull();
        assertThat(previews.get(row.id()).postId()).isEqualTo(row.postId());
        assertThat(previews.get(missing).available()).isFalse();
    }
}
