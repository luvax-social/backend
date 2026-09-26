package com.app.common.vocabulary.service.impl;

import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.common.vocabulary.dto.response.VocabularyResponse;
import com.app.common.vocabulary.repository.VocabularyRepository;
import com.app.common.vocabulary.service.VocabularyService;

@Service
public class VocabularyServiceImpl implements VocabularyService {

    private record Cached(
            java.util.List<com.app.common.vocabulary.dto.response.SupportCategoryVocabularyResponse>
                    value,
            Instant expiresAt) {}

    private volatile Cached cachedPublicCategories;

    private final VocabularyRepository vocabularyRepository;

    /**
     * How long a public category snapshot is served before it is read again.
     *
     * <p>The cache exists because this list is served to signed-out callers on an anonymous route:
     * without it every load of the public form reaches PostgreSQL, which is a database read
     * available to anyone who can reach the host.
     *
     * <p>The rows change on a migration, so this could in principle be held for the life of the
     * process. It is bounded anyway because the alternative is that correcting a category by hand
     * needs a restart to take effect, and because an unbounded cache is one more thing that can be
     * wrong for an unbounded time. Configurable so a deployment can shorten it and so a test can
     * set it to zero and observe the filter rather than a snapshot.
     */
    private final Duration cacheTtl;

    public VocabularyServiceImpl(
            VocabularyRepository vocabularyRepository,
            @Value("${app.vocabulary.public-categories-cache-ttl:10m}") Duration cacheTtl) {
        this.vocabularyRepository = vocabularyRepository;
        this.cacheTtl = cacheTtl;
    }

    @Override
    @Transactional(readOnly = true)
    public VocabularyResponse getVocabularies() {
        // One transaction over four reads rather than four, so a client cannot observe the tables
        // mid-change: an administrator disabling a reason and its notification type in one
        // migration would otherwise be visible half-applied.
        return new VocabularyResponse(
                vocabularyRepository.findReportReasons(),
                vocabularyRepository.findNotificationTypes(),
                vocabularyRepository.findModerationActions(),
                vocabularyRepository.findSupportCategories());
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.List<com.app.common.vocabulary.dto.response.SupportCategoryVocabularyResponse>
            getPublicSupportCategories() {
        Cached snapshot = cachedPublicCategories;
        if (snapshot != null && snapshot.expiresAt().isAfter(Instant.now())) {
            return snapshot.value();
        }
        java.util.List<com.app.common.vocabulary.dto.response.SupportCategoryVocabularyResponse>
                rows =
                        vocabularyRepository.findSupportCategories().stream()
                                .filter(row -> row.isEnabled() && row.allowsPublicForm())
                                .toList();
        // Racing readers may each compute this; the result is identical and the list is immutable,
        // so the last writer winning costs nothing and avoids holding a lock across a query.
        cachedPublicCategories = new Cached(rows, Instant.now().plus(cacheTtl));
        return rows;
    }

    @Override
    public boolean allowsPublicForm(String categoryKey) {
        if (categoryKey == null) {
            return false;
        }
        return getPublicSupportCategories().stream()
                .anyMatch(row -> row.categoryKey().equalsIgnoreCase(categoryKey));
    }
}
