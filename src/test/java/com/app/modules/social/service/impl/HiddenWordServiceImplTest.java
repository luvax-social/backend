package com.app.modules.social.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.common.text.PhraseMatcher;
import com.app.common.text.TextMatchNormalizer;
import com.app.modules.social.dto.response.HiddenWordEntryResponse;
import com.app.modules.social.dto.response.HiddenWordListResponse;
import com.app.modules.social.entity.UserHiddenWord;
import com.app.modules.social.event.HiddenWordsChangedEvent;
import com.app.modules.social.repository.HiddenWordRepository;
import com.app.modules.social.repository.UserHiddenWordRepository;
import com.app.modules.users.repository.UserSettingsRepository;
import com.app.modules.users.service.UserPrivacySettingsService;
import com.app.modules.users.service.impl.UserPrivacySettingsServiceImpl;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

@ExtendWith(MockitoExtension.class)
class HiddenWordServiceImplTest {

    @Mock private UserHiddenWordRepository userHiddenWordRepository;
    @Mock private HiddenWordRepository hiddenWordRepository;
    @Mock private UserPrivacySettingsService privacySettings;
    @Mock private UserSettingsRepository settingsRepository;

    private HiddenWordServiceImpl service;
    private final UUID owner = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service =
                new HiddenWordServiceImpl(
                        userHiddenWordRepository, hiddenWordRepository, privacySettings);
    }

    @Test
    void addPersonal_phrase_isStoredInNormalisedFormAndReturned() {
        when(userHiddenWordRepository.countByUserId(owner)).thenReturn(0L);
        when(userHiddenWordRepository.existsByUserIdAndNormalizedPhrase(owner, "do ngu"))
                .thenReturn(false);
        OffsetDateTime createdAt = OffsetDateTime.now();
        UUID entryId = UUID.randomUUID();
        when(userHiddenWordRepository.saveAndFlush(any(UserHiddenWord.class)))
                .thenAnswer(
                        invocation -> {
                            UserHiddenWord saved = invocation.getArgument(0);
                            saved.setId(entryId);
                            saved.setCreatedAt(createdAt);
                            return saved;
                        });

        HiddenWordEntryResponse response = service.addPersonal(owner, "  Đồ  Ngu ");

        ArgumentCaptor<UserHiddenWord> stored = ArgumentCaptor.forClass(UserHiddenWord.class);
        verify(userHiddenWordRepository).lockPersonalList(owner);
        verify(userHiddenWordRepository).saveAndFlush(stored.capture());
        assertThat(stored.getValue().getUserId()).isEqualTo(owner);
        assertThat(stored.getValue().getPhrase()).isEqualTo("Đồ  Ngu");
        assertThat(stored.getValue().getNormalizedPhrase()).isEqualTo("do ngu");
        assertThat(response).isEqualTo(new HiddenWordEntryResponse(entryId, "Đồ  Ngu", createdAt));
    }

    @Test
    void addPersonal_sameNormalisedFormAsExistingEntry_throwsDuplicate() {
        when(userHiddenWordRepository.countByUserId(owner)).thenReturn(1L);
        when(userHiddenWordRepository.existsByUserIdAndNormalizedPhrase(owner, "do ngu"))
                .thenReturn(true);

        assertThatThrownBy(() -> service.addPersonal(owner, "do ngu"))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.HIDDEN_WORD_DUPLICATE);
        verify(userHiddenWordRepository, never()).saveAndFlush(any());
    }

    @Test
    void addPersonal_listAlreadyHoldsTwoHundredEntries_throwsLimitReached() {
        when(userHiddenWordRepository.countByUserId(owner)).thenReturn(200L);

        assertThatThrownBy(() -> service.addPersonal(owner, "one more"))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.HIDDEN_WORD_LIMIT_REACHED);
        verify(userHiddenWordRepository, never()).saveAndFlush(any());
    }

    @Test
    void addPersonal_invalidPhrases_throwInvalidWithoutTouchingTheDatabase() {
        String tooLong = "a".repeat(65);
        String sevenTokens = "one two three four five six seven";
        for (String phrase :
                new String[] {null, "", "   ", "?!.,", "...", "--", tooLong, sevenTokens}) {
            assertThatThrownBy(() -> service.addPersonal(owner, phrase))
                    .as("phrase %s", phrase)
                    .isInstanceOf(AppException.class)
                    .extracting(ex -> ((AppException) ex).getErrorCode())
                    .isEqualTo(ApiErrorCode.HIDDEN_WORD_INVALID);
        }
        verifyNoMoreInteractions(userHiddenWordRepository);
    }

    @Test
    void addPersonal_sixTokensAndSixtyFourCharacters_areAccepted() {
        when(userHiddenWordRepository.countByUserId(owner)).thenReturn(0L);
        when(userHiddenWordRepository.saveAndFlush(any(UserHiddenWord.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.addPersonal(owner, "one two three four five six");
        service.addPersonal(owner, "a".repeat(64));

        verify(userHiddenWordRepository, times(2)).saveAndFlush(any(UserHiddenWord.class));
    }

    @Test
    void addPersonal_sixtyFourCodePointsOutsideTheBmp_areAccepted() {
        String phrase = "ab".repeat(30) + "😀😀😀😀";
        assertThat(phrase.length()).isGreaterThan(64);
        assertThat(phrase.codePointCount(0, phrase.length())).isEqualTo(64);
        when(userHiddenWordRepository.countByUserId(owner)).thenReturn(0L);
        when(userHiddenWordRepository.saveAndFlush(any(UserHiddenWord.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.addPersonal(owner, phrase);

        verify(userHiddenWordRepository).saveAndFlush(any(UserHiddenWord.class));
    }

    @Test
    void removePersonal_entryOfAnotherUser_throwsNotFound() {
        UUID entryId = UUID.randomUUID();
        when(userHiddenWordRepository.deleteByIdAndUserId(entryId, owner)).thenReturn(0);

        assertThatThrownBy(() -> service.removePersonal(owner, entryId))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.NOT_FOUND);
    }

    @Test
    void removePersonal_ownEntry_deletesIt() {
        UUID entryId = UUID.randomUUID();
        when(userHiddenWordRepository.deleteByIdAndUserId(entryId, owner)).thenReturn(1);

        service.removePersonal(owner, entryId);

        verify(userHiddenWordRepository).deleteByIdAndUserId(entryId, owner);
    }

    @Test
    void listPersonal_returnsEntriesInRepositoryOrderWithTheLimit() {
        OffsetDateTime now = OffsetDateTime.now();
        when(userHiddenWordRepository.findByUserIdOrderByCreatedAtDescIdDesc(owner))
                .thenReturn(List.of(entry("b", now), entry("a", now.minusMinutes(1))));

        HiddenWordListResponse response = service.listPersonal(owner);

        assertThat(response.limit()).isEqualTo(200);
        assertThat(response.entries())
                .extracting(HiddenWordEntryResponse::phrase)
                .containsExactly("b", "a");
    }

    @Test
    void matcherFor_systemListEnabled_matchesPersonalAndSystemPhrases() {
        when(hiddenWordRepository.findAllNormalizedPhrases()).thenReturn(List.of("ass"));
        service.refreshSystemList();
        when(privacySettings.hiddenWordsSystemEnabled(owner)).thenReturn(true);
        when(userHiddenWordRepository.findNormalizedPhrasesByUserId(owner))
                .thenReturn(List.of("do ngu"));

        PhraseMatcher matcher = service.matcherFor(owner);

        assertThat(matcher.matches(TextMatchNormalizer.tokens("mày là đồ ngu"))).isTrue();
        assertThat(matcher.matches(TextMatchNormalizer.tokens("what an a$$"))).isTrue();
        assertThat(matcher.matches(TextMatchNormalizer.tokens("hello there"))).isFalse();
    }

    @Test
    void matcherFor_systemListDisabled_matchesOnlyPersonalPhrases() {
        when(hiddenWordRepository.findAllNormalizedPhrases()).thenReturn(List.of("ass"));
        service.refreshSystemList();
        when(privacySettings.hiddenWordsSystemEnabled(owner)).thenReturn(false);
        when(userHiddenWordRepository.findNormalizedPhrasesByUserId(owner))
                .thenReturn(List.of("do ngu"));

        PhraseMatcher matcher = service.matcherFor(owner);

        assertThat(matcher.matches(TextMatchNormalizer.tokens("đồ ngu"))).isTrue();
        assertThat(matcher.matches(TextMatchNormalizer.tokens("what an a$$"))).isFalse();
    }

    @Test
    void matcherFor_oneCall_costsOnePersonalQueryAndOneToggleLookup() {
        when(hiddenWordRepository.findAllNormalizedPhrases()).thenReturn(List.of("ass"));
        service.refreshSystemList();
        when(privacySettings.hiddenWordsSystemEnabled(owner)).thenReturn(true);
        when(userHiddenWordRepository.findNormalizedPhrasesByUserId(owner))
                .thenReturn(List.of("a", "b", "c"));

        service.matcherFor(owner);

        verify(userHiddenWordRepository, times(1)).findNormalizedPhrasesByUserId(owner);
        verify(privacySettings, times(1)).hiddenWordsSystemEnabled(owner);
        verify(hiddenWordRepository, times(1)).findAllNormalizedPhrases();
        verifyNoMoreInteractions(userHiddenWordRepository, privacySettings);
    }

    @Test
    void onHiddenWordsChanged_systemListChanged_replacesTheCachedList() {
        when(hiddenWordRepository.findAllNormalizedPhrases())
                .thenReturn(List.of("ass"))
                .thenReturn(List.of("scam"));
        when(privacySettings.hiddenWordsSystemEnabled(owner)).thenReturn(true);
        when(userHiddenWordRepository.findNormalizedPhrasesByUserId(owner)).thenReturn(List.of());
        service.refreshSystemList();
        assertThat(service.matcherFor(owner).matches(List.of("scam"))).isFalse();

        service.onHiddenWordsChanged(new HiddenWordsChangedEvent());

        PhraseMatcher matcher = service.matcherFor(owner);
        assertThat(matcher.matches(List.of("scam"))).isTrue();
        assertThat(matcher.matches(List.of("ass"))).isFalse();
    }

    @Test
    void addPersonal_controlCharacters_throwInvalidWithoutTouchingTheDatabase() {
        for (String phrase :
                new String[] {
                    "bad\u0000word",
                    "tab\tseparated",
                    "line\nbreak",
                    "del\u007fchar",
                    "next\u0085line"
                }) {
            assertThatThrownBy(() -> service.addPersonal(owner, phrase))
                    .as("phrase with control character at %s", phrase.length())
                    .isInstanceOf(AppException.class)
                    .extracting(ex -> ((AppException) ex).getErrorCode())
                    .isEqualTo(ApiErrorCode.HIDDEN_WORD_INVALID);
        }
        verifyNoMoreInteractions(userHiddenWordRepository);
    }

    @Test
    void addPersonal_surroundingWhitespaceIncludingNewlines_isStrippedNotRejected() {
        when(userHiddenWordRepository.countByUserId(owner)).thenReturn(0L);
        when(userHiddenWordRepository.saveAndFlush(any(UserHiddenWord.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.addPersonal(owner, "\n pasted phrase \r\n");

        ArgumentCaptor<UserHiddenWord> stored = ArgumentCaptor.forClass(UserHiddenWord.class);
        verify(userHiddenWordRepository).saveAndFlush(stored.capture());
        assertThat(stored.getValue().getPhrase()).isEqualTo("pasted phrase");
    }

    @Test
    void matcherFor_accountWithoutSettingsRow_usesTheColumnDefaultsInsteadOfThrowing() {
        UserPrivacySettingsServiceImpl realSettings =
                new UserPrivacySettingsServiceImpl(settingsRepository);
        HiddenWordServiceImpl withRealSettings =
                new HiddenWordServiceImpl(
                        userHiddenWordRepository, hiddenWordRepository, realSettings);
        when(settingsRepository.findById(owner)).thenReturn(Optional.empty());
        when(hiddenWordRepository.findAllNormalizedPhrases()).thenReturn(List.of("ass"));
        withRealSettings.refreshSystemList();
        when(userHiddenWordRepository.findNormalizedPhrasesByUserId(owner))
                .thenReturn(List.of("do ngu"));

        PhraseMatcher matcher = withRealSettings.matcherFor(owner);

        // Defaults: the platform-wide list is on, so both lists apply.
        assertThat(matcher.matches(TextMatchNormalizer.tokens("what an a$$"))).isTrue();
        assertThat(matcher.matches(TextMatchNormalizer.tokens("đồ ngu"))).isTrue();
    }

    @Test
    void refreshSystemList_concurrentRefreshes_leaveTheLaterSnapshotInPlace() throws Exception {
        CountDownLatch firstIsReading = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondIsReading = new CountDownLatch(1);
        AtomicInteger reads = new AtomicInteger();
        when(hiddenWordRepository.findAllNormalizedPhrases())
                .thenAnswer(
                        invocation -> {
                            if (reads.incrementAndGet() == 1) {
                                firstIsReading.countDown();
                                releaseFirst.await();
                                return List.of("older");
                            }
                            secondIsReading.countDown();
                            return List.of("newer");
                        });
        when(privacySettings.hiddenWordsSystemEnabled(owner)).thenReturn(true);
        when(userHiddenWordRepository.findNormalizedPhrasesByUserId(owner)).thenReturn(List.of());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(service::refreshSystemList);
            assertThat(firstIsReading.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> second = pool.submit(service::refreshSystemList);
            // An unsynchronised second refresh reads while the first is still parked; a
            // synchronised one waits for the first to finish, so this wait times out by design.
            secondIsReading.await(500, TimeUnit.MILLISECONDS);
            releaseFirst.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        PhraseMatcher matcher = service.matcherFor(owner);
        assertThat(matcher.matches(List.of("newer"))).isTrue();
        assertThat(matcher.matches(List.of("older"))).isFalse();
    }

    @Test
    void refreshSystemList_readFails_logsAtErrorKeepsTheOldListAndRethrows() {
        when(hiddenWordRepository.findAllNormalizedPhrases())
                .thenReturn(List.of("ass"))
                .thenThrow(new IllegalStateException("database down"));
        when(privacySettings.hiddenWordsSystemEnabled(owner)).thenReturn(true);
        when(userHiddenWordRepository.findNormalizedPhrasesByUserId(owner)).thenReturn(List.of());
        service.refreshSystemList();

        Logger logger = (Logger) LoggerFactory.getLogger(HiddenWordServiceImpl.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThatThrownBy(service::refreshSystemList)
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list).extracting(ILoggingEvent::getLevel).contains(Level.ERROR);
        assertThat(service.matcherFor(owner).matches(List.of("ass"))).isTrue();
    }

    @Test
    void onHiddenWordsChanged_refreshFails_doesNotFailTheCommittedRequest() {
        when(hiddenWordRepository.findAllNormalizedPhrases())
                .thenThrow(new IllegalStateException("database down"));

        service.onHiddenWordsChanged(new HiddenWordsChangedEvent());

        verify(hiddenWordRepository).findAllNormalizedPhrases();
    }

    private static UserHiddenWord entry(String phrase, OffsetDateTime createdAt) {
        return UserHiddenWord.builder()
                .id(UUID.randomUUID())
                .phrase(phrase)
                .normalizedPhrase(phrase)
                .createdAt(createdAt)
                .build();
    }
}
