package com.app.modules.social.service.impl;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.annotation.PostConstruct;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

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
import com.app.modules.social.service.HiddenWordService;
import com.app.modules.users.service.UserPrivacySettingsService;

@Service
public class HiddenWordServiceImpl implements HiddenWordService {

    private final UserHiddenWordRepository userHiddenWordRepository;
    private final HiddenWordRepository hiddenWordRepository;
    private final UserPrivacySettingsService privacySettings;

    // The platform-wide list is held in this JVM and reloaded after every committed change. A
    // single application instance is the documented deployment, so no cross-instance invalidation
    // exists; a second instance would keep serving its own copy until it restarted.
    private final AtomicReference<PhraseMatcher> systemList =
            new AtomicReference<>(PhraseMatcher.compile(List.of()));

    public HiddenWordServiceImpl(
            UserHiddenWordRepository userHiddenWordRepository,
            HiddenWordRepository hiddenWordRepository,
            UserPrivacySettingsService privacySettings) {
        this.userHiddenWordRepository = userHiddenWordRepository;
        this.hiddenWordRepository = hiddenWordRepository;
        this.privacySettings = privacySettings;
    }

    @PostConstruct
    void loadSystemListOnStartup() {
        refreshSystemList();
    }

    @Override
    @Transactional
    public HiddenWordEntryResponse addPersonal(UUID ownerId, String phrase) {
        String normalized = normalizedOrReject(phrase);

        // Serialises the count, the duplicate check and the insert per account.
        userHiddenWordRepository.lockPersonalList(ownerId);
        if (userHiddenWordRepository.countByUserId(ownerId) >= PERSONAL_LIMIT) {
            throw new AppException(ApiErrorCode.HIDDEN_WORD_LIMIT_REACHED);
        }
        if (userHiddenWordRepository.existsByUserIdAndNormalizedPhrase(ownerId, normalized)) {
            throw new AppException(ApiErrorCode.HIDDEN_WORD_DUPLICATE);
        }

        // Flushed so the database-generated created_at is on the entity before it is returned.
        UserHiddenWord saved =
                userHiddenWordRepository.saveAndFlush(
                        UserHiddenWord.builder()
                                .userId(ownerId)
                                .phrase(phrase.strip())
                                .normalizedPhrase(normalized)
                                .build());
        return toResponse(saved);
    }

    @Override
    @Transactional
    public void removePersonal(UUID ownerId, UUID entryId) {
        if (userHiddenWordRepository.deleteByIdAndUserId(entryId, ownerId) == 0) {
            throw new AppException(ApiErrorCode.NOT_FOUND);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public HiddenWordListResponse listPersonal(UUID ownerId) {
        List<HiddenWordEntryResponse> entries =
                userHiddenWordRepository.findByUserIdOrderByCreatedAtDescIdDesc(ownerId).stream()
                        .map(HiddenWordServiceImpl::toResponse)
                        .toList();
        return new HiddenWordListResponse(entries, PERSONAL_LIMIT);
    }

    @Override
    @Transactional(readOnly = true)
    public PhraseMatcher matcherFor(UUID ownerId) {
        boolean includeSystemList = privacySettings.hiddenWordsSystemEnabled(ownerId);
        PhraseMatcher personal =
                PhraseMatcher.compile(
                        userHiddenWordRepository.findNormalizedPhrasesByUserId(ownerId));
        return includeSystemList ? personal.or(systemList.get()) : personal;
    }

    @Override
    public void refreshSystemList() {
        systemList.set(PhraseMatcher.compile(hiddenWordRepository.findAllNormalizedPhrases()));
    }

    /**
     * Rebuilds the cached platform-wide list once the changing transaction has committed.
     *
     * <p>{@code fallbackExecution} also reloads when the event is published outside a transaction,
     * so a caller that forgot to open one still leaves the cache correct.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onHiddenWordsChanged(HiddenWordsChangedEvent event) {
        refreshSystemList();
    }

    private static String normalizedOrReject(String phrase) {
        if (phrase == null) {
            throw new AppException(ApiErrorCode.HIDDEN_WORD_INVALID);
        }
        int characters = phrase.codePointCount(0, phrase.length());
        if (characters < 1 || characters > MAX_PHRASE_CHARACTERS) {
            throw new AppException(ApiErrorCode.HIDDEN_WORD_INVALID);
        }
        List<String> tokens = TextMatchNormalizer.tokens(phrase);
        if (tokens.isEmpty() || tokens.size() > MAX_PHRASE_TOKENS) {
            throw new AppException(ApiErrorCode.HIDDEN_WORD_INVALID);
        }
        return String.join(" ", tokens);
    }

    private static HiddenWordEntryResponse toResponse(UserHiddenWord entry) {
        return new HiddenWordEntryResponse(entry.getId(), entry.getPhrase(), entry.getCreatedAt());
    }
}
