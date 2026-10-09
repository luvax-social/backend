package com.app.modules.social.service.impl;

import java.util.List;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.common.pagination.Cursor;
import com.app.common.pagination.CursorCodec;
import com.app.common.pagination.CursorScope;
import com.app.common.pagination.KeysetPage;
import com.app.common.pagination.TimeCursors;
import com.app.common.response.CursorPageResponse;
import com.app.common.text.TextMatchNormalizer;
import com.app.modules.social.dto.response.AdminHiddenWordResponse;
import com.app.modules.social.entity.HiddenWord;
import com.app.modules.social.event.HiddenWordsChangedEvent;
import com.app.modules.social.repository.HiddenWordRepository;
import com.app.modules.social.service.HiddenWordLifecycleResult;
import com.app.modules.social.service.HiddenWordLifecycleService;
import com.app.modules.social.validation.HiddenWordPhrases;

@Service
public class HiddenWordLifecycleServiceImpl implements HiddenWordLifecycleService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final String LIKE_ESCAPE = "!";

    private final HiddenWordRepository hiddenWordRepository;
    private final ApplicationEventPublisher eventPublisher;

    public HiddenWordLifecycleServiceImpl(
            HiddenWordRepository hiddenWordRepository, ApplicationEventPublisher eventPublisher) {
        this.hiddenWordRepository = hiddenWordRepository;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional(readOnly = true)
    public CursorPageResponse<AdminHiddenWordResponse> list(
            String query, String cursor, int limit) {
        int pageSize = limit < 1 ? DEFAULT_PAGE_SIZE : Math.min(limit, MAX_PAGE_SIZE);
        Cursor decoded = CursorCodec.decode(cursor, CursorScope.ADMIN_HIDDEN_WORDS);
        String pattern = likePatternFor(query);
        if (pattern == null) {
            return toPage(List.of(), pageSize, cursor != null);
        }
        // One extra row proves a further page exists without a second query.
        PageRequest overFetch = PageRequest.of(0, pageSize + 1);
        List<HiddenWord> rows =
                decoded == null
                        ? hiddenWordRepository.findAdminPage(pattern, overFetch)
                        : hiddenWordRepository.findAdminPageAfter(
                                pattern,
                                TimeCursors.fromMicros(decoded.sortValueMicros()),
                                decoded.id(),
                                overFetch);
        return toPage(rows, pageSize, cursor != null);
    }

    @Override
    @Transactional
    public HiddenWordLifecycleResult create(UUID actorId, String phrase) {
        String normalized = HiddenWordPhrases.normalizeOrReject(phrase);
        if (hiddenWordRepository.existsByNormalizedPhrase(normalized)) {
            throw new AppException(ApiErrorCode.HIDDEN_WORD_DUPLICATE);
        }
        // Flushed so the database-generated timestamps are on the entity before it is returned. A
        // concurrent create that passed the check above is refused by the unique constraint, which
        // GlobalExceptionHandler answers as HIDDEN_WORD_DUPLICATE as well.
        HiddenWord saved =
                hiddenWordRepository.saveAndFlush(
                        HiddenWord.builder()
                                .phrase(phrase.strip())
                                .normalizedPhrase(normalized)
                                .createdBy(actorId)
                                .build());
        eventPublisher.publishEvent(new HiddenWordsChangedEvent());
        return result(saved, null, null);
    }

    @Override
    @Transactional
    public HiddenWordLifecycleResult update(UUID id, String phrase) {
        HiddenWord word = requireWord(id);
        String normalized = HiddenWordPhrases.normalizeOrReject(phrase);
        if (hiddenWordRepository.existsByNormalizedPhraseAndIdNot(normalized, id)) {
            throw new AppException(ApiErrorCode.HIDDEN_WORD_DUPLICATE);
        }
        String previousPhrase = word.getPhrase();
        String previousNormalized = word.getNormalizedPhrase();
        word.setPhrase(phrase.strip());
        word.setNormalizedPhrase(normalized);
        HiddenWord saved = hiddenWordRepository.saveAndFlush(word);
        eventPublisher.publishEvent(new HiddenWordsChangedEvent());
        return result(saved, previousPhrase, previousNormalized);
    }

    @Override
    @Transactional
    public HiddenWordLifecycleResult delete(UUID id) {
        HiddenWord word = requireWord(id);
        HiddenWordLifecycleResult result = result(word, null, null);
        hiddenWordRepository.delete(word);
        hiddenWordRepository.flush();
        eventPublisher.publishEvent(new HiddenWordsChangedEvent());
        return result;
    }

    private HiddenWord requireWord(UUID id) {
        return hiddenWordRepository
                .findById(id)
                .orElseThrow(() -> new AppException(ApiErrorCode.NOT_FOUND));
    }

    // Matching compares normalised forms, so the search term is normalised the same way before it
    // becomes a pattern; that is also what makes "NGU" find "Đồ Ngu" whatever the database
    // collation
    // does to non-ASCII letters. Null means the term has no searchable word, so nothing can match.
    private static String likePatternFor(String query) {
        if (query == null || query.isBlank()) {
            return "%";
        }
        String normalized = String.join(" ", TextMatchNormalizer.tokens(query));
        if (normalized.isEmpty()) {
            return null;
        }
        String escaped =
                normalized
                        .replace(LIKE_ESCAPE, LIKE_ESCAPE + LIKE_ESCAPE)
                        .replace("%", LIKE_ESCAPE + "%")
                        .replace("_", LIKE_ESCAPE + "_");
        return "%" + escaped + "%";
    }

    private static CursorPageResponse<AdminHiddenWordResponse> toPage(
            List<HiddenWord> rows, int pageSize, boolean hasPreviousPage) {
        KeysetPage.Result<HiddenWord> page =
                KeysetPage.of(
                        rows,
                        pageSize,
                        w -> new Cursor(TimeCursors.toMicros(w.getCreatedAt()), w.getId()),
                        CursorScope.ADMIN_HIDDEN_WORDS);
        return CursorPageResponse.of(
                page.content().stream().map(HiddenWordLifecycleServiceImpl::toResponse).toList(),
                page.hasNextPage(),
                page.startCursor(),
                page.endCursor(),
                hasPreviousPage);
    }

    private static HiddenWordLifecycleResult result(
            HiddenWord word, String previousPhrase, String previousNormalizedPhrase) {
        return new HiddenWordLifecycleResult(
                word.getId(),
                word.getPhrase(),
                word.getNormalizedPhrase(),
                previousPhrase,
                previousNormalizedPhrase,
                toResponse(word));
    }

    private static AdminHiddenWordResponse toResponse(HiddenWord word) {
        return new AdminHiddenWordResponse(
                word.getId(),
                word.getPhrase(),
                word.getNormalizedPhrase(),
                word.getCreatedBy(),
                word.getCreatedAt(),
                word.getUpdatedAt());
    }
}
