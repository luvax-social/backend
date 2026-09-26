package com.app.modules.admin.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
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

import com.app.modules.admin.dto.response.ModerationNoticeResponse;
import com.app.modules.admin.repository.ModerationNoticeRepository;
import com.app.modules.admin.repository.ModerationNoticeRepository.NoticeRow;

@ExtendWith(MockitoExtension.class)
class ModerationNoticeServiceImplTest {

    private static final OffsetDateTime WRITTEN =
            OffsetDateTime.of(2026, 9, 12, 8, 0, 0, 0, ZoneOffset.UTC);

    @Mock private ModerationNoticeRepository moderationNoticeRepository;

    private ModerationNoticeServiceImpl service;
    private final UUID author = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ModerationNoticeServiceImpl(moderationNoticeRepository);
    }

    @Test
    void loadNotices_removalOfTheRecipientsComment_showsKindDateSnippetAndIsAppealable() {
        NoticeRow row =
                new NoticeRow(
                        UUID.randomUUID(),
                        "remove_comment",
                        "harassment",
                        author,
                        "comment",
                        "you are all\nidiots",
                        WRITTEN);
        when(moderationNoticeRepository.findNoticeRows(anyCollection())).thenReturn(List.of(row));

        ModerationNoticeResponse notice =
                service.loadNotices(author, List.of(row.id())).get(row.id());

        assertThat(notice.affectedKind()).isEqualTo("comment");
        assertThat(notice.affectedSnippet()).isEqualTo("you are all idiots");
        assertThat(notice.affectedAt()).isEqualTo(WRITTEN);
        assertThat(notice.appealable()).isTrue();
    }

    @Test
    void loadNotices_reporterToldTheOutcome_neverSeesTheReportedText() {
        NoticeRow row =
                new NoticeRow(
                        UUID.randomUUID(),
                        "remove_post",
                        "spam",
                        author,
                        "post",
                        "buy now",
                        WRITTEN);
        UUID reporter = UUID.randomUUID();
        when(moderationNoticeRepository.findNoticeRows(anyCollection())).thenReturn(List.of(row));

        ModerationNoticeResponse notice =
                service.loadNotices(reporter, List.of(row.id())).get(row.id());

        assertThat(notice.affectedSnippet()).isNull();
        assertThat(notice.affectedAt()).isNull();
        assertThat(notice.appealable()).isFalse();
    }

    @Test
    void loadNotices_warningAndRestore_areAccountLevelAndOnlyTheWarningIsAppealable() {
        NoticeRow warning =
                new NoticeRow(UUID.randomUUID(), "warn_user", "spam", author, "user", null, null);
        NoticeRow restore =
                new NoticeRow(
                        UUID.randomUUID(),
                        "restore_post",
                        "appeal upheld",
                        author,
                        "post",
                        "sunset",
                        WRITTEN);
        when(moderationNoticeRepository.findNoticeRows(anyCollection()))
                .thenReturn(List.of(warning, restore));

        var notices = service.loadNotices(author, List.of(warning.id(), restore.id()));

        assertThat(notices.get(warning.id()).affectedKind()).isEqualTo("account");
        assertThat(notices.get(warning.id()).appealable()).isTrue();
        assertThat(notices.get(restore.id()).appealable()).isFalse();
    }
}
