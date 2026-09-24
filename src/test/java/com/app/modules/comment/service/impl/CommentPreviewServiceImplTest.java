package com.app.modules.comment.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
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
                        "the colours on this",
                        false);
        when(commentPreviewRepository.findPreviewRows(anyCollection())).thenReturn(List.of(row));

        CommentPreviewResponse preview = service.loadPreviews(List.of(row.id())).get(row.id());

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
                        "removed words",
                        true);
        when(commentPreviewRepository.findPreviewRows(anyCollection())).thenReturn(List.of(row));

        CommentPreviewResponse preview = service.loadPreviews(List.of(row.id())).get(row.id());

        assertThat(preview.snippet()).isEqualTo("agreed");
        assertThat(preview.parentSnippet()).isNull();
    }

    @Test
    void loadPreviews_deletedOrRemovedComment_isUnavailableWithoutText() {
        CommentPreviewRow row =
                new CommentPreviewRow(
                        UUID.randomUUID(), UUID.randomUUID(), null, "gone", true, null, true);
        UUID missing = UUID.randomUUID();
        when(commentPreviewRepository.findPreviewRows(anyCollection())).thenReturn(List.of(row));

        var previews = service.loadPreviews(List.of(row.id(), missing));

        assertThat(previews.get(row.id()).available()).isFalse();
        assertThat(previews.get(row.id()).snippet()).isNull();
        assertThat(previews.get(row.id()).postId()).isEqualTo(row.postId());
        assertThat(previews.get(missing).available()).isFalse();
    }
}
