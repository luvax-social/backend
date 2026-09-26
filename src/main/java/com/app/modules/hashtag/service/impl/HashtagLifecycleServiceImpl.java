package com.app.modules.hashtag.service.impl;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.springframework.dao.DataIntegrityViolationException;
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
import com.app.modules.hashtag.dto.response.HashtagAdminResponse;
import com.app.modules.hashtag.entity.Hashtag;
import com.app.modules.hashtag.enums.HashtagStatus;
import com.app.modules.hashtag.repository.HashtagRepository;
import com.app.modules.hashtag.repository.HashtagTrendingRepository;
import com.app.modules.hashtag.service.HashtagIndexEventPublisher;
import com.app.modules.hashtag.service.HashtagLifecycleResult;
import com.app.modules.hashtag.service.HashtagLifecycleService;
import com.app.modules.hashtag.service.HashtagService;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class HashtagLifecycleServiceImpl implements HashtagLifecycleService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    // Matches the public search's own lower bound, declared on HashtagApi.search as @Size(min = 1).
    private static final int MIN_QUERY_LENGTH = 1;

    private final HashtagRepository hashtagRepository;
    private final EntityManager entityManager;
    private final HashtagTrendingRepository hashtagTrendingRepository;
    private final HashtagService hashtagService;
    private final HashtagIndexEventPublisher indexEventPublisher;

    public HashtagLifecycleServiceImpl(
            HashtagRepository hashtagRepository,
            EntityManager entityManager,
            HashtagTrendingRepository hashtagTrendingRepository,
            HashtagService hashtagService,
            HashtagIndexEventPublisher indexEventPublisher) {
        this.hashtagRepository = hashtagRepository;
        this.entityManager = entityManager;
        this.hashtagTrendingRepository = hashtagTrendingRepository;
        this.hashtagService = hashtagService;
        this.indexEventPublisher = indexEventPublisher;
    }

    @Override
    @Transactional(readOnly = true)
    public CursorPageResponse<HashtagAdminResponse> list(
            HashtagStatus status, String cursor, int limit) {
        int pageSize = normalizeLimit(limit);
        Cursor decoded = CursorCodec.decode(cursor, CursorScope.ADMIN_HASHTAGS);
        List<Hashtag> rows =
                hashtagRepository.findAdminPage(
                        status,
                        decoded == null ? null : TimeCursors.fromMicros(decoded.sortValueMicros()),
                        decoded == null ? null : decoded.id(),
                        pageSize + 1);
        return toPage(rows, pageSize, cursor != null, CursorScope.ADMIN_HASHTAGS);
    }

    @Override
    @Transactional(readOnly = true)
    public CursorPageResponse<HashtagAdminResponse> search(
            String query, HashtagStatus status, String cursor, int limit) {
        String normalized = normalizeQuery(query);
        int pageSize = normalizeLimit(limit);
        Cursor decoded = CursorCodec.decode(cursor, CursorScope.ADMIN_HASHTAG_SEARCH);
        List<Hashtag> rows =
                hashtagRepository.searchAdmin(
                        normalized,
                        status,
                        decoded == null ? null : TimeCursors.fromMicros(decoded.sortValueMicros()),
                        decoded == null ? null : decoded.id(),
                        pageSize + 1);
        return toPage(rows, pageSize, cursor != null, CursorScope.ADMIN_HASHTAG_SEARCH);
    }

    @Override
    @Transactional
    public HashtagLifecycleResult create(
            UUID actorId, String rawName, HashtagStatus status, String note) {
        String name = hashtagService.normalize(rawName);
        if (name.isBlank()) {
            throw new AppException(
                    ApiErrorCode.BAD_REQUEST, "Hashtag name is empty after normalization");
        }
        if (status == HashtagStatus.DELETED) {
            throw new AppException(
                    ApiErrorCode.BAD_REQUEST, "A hashtag cannot be created already deleted");
        }
        Hashtag hashtag =
                Hashtag.builder()
                        .name(name)
                        .status(status)
                        .statusNote(note)
                        .statusAt(OffsetDateTime.now())
                        .statusBy(actorId)
                        .build();
        try {
            // The unique index on name is the authoritative guard against a concurrent create;
            // flushing here makes the violation surface as a conflict rather than a late 500 from
            // the commit. There is no ON CONFLICT clause on purpose: this is the administrative
            // create and it must report a conflict, not silently succeed against a row somebody
            // else owns. Ordinary first-use traffic goes through upsertByName, which does the
            // opposite for exactly the same reason.
            hashtagRepository.saveAndFlush(hashtag);
        } catch (DataIntegrityViolationException ex) {
            throw new AppException(ApiErrorCode.HASHTAG_ALREADY_EXISTS);
        }
        // created_at is database-defaulted and mapped insertable = false, so the persisted instance
        // does not carry it until the row is read back.
        entityManager.refresh(hashtag);
        log.info("Hashtag created by administrator: name={}, status={}", name, status);
        // No index event. A hashtag nothing has used has a zero post_count, and the consumer's
        // post_count gate would drop the document anyway, so the event would be pure noise.
        return new HashtagLifecycleResult(
                hashtag.getId(), name, null, status, 0, toResponse(hashtag));
    }

    @Override
    @Transactional
    public HashtagLifecycleResult changeStatus(
            UUID actorId, UUID hashtagId, HashtagStatus target, String note) {
        Hashtag hashtag =
                hashtagRepository
                        .findById(hashtagId)
                        .orElseThrow(() -> new AppException(ApiErrorCode.HASHTAG_NOT_FOUND));
        HashtagStatus previous = hashtag.getStatus();
        if (previous == target) {
            throw new AppException(ApiErrorCode.ADMIN_INVALID_TRANSITION);
        }
        hashtag.setStatus(target);
        hashtag.setStatusNote(note);
        hashtag.setStatusAt(OffsetDateTime.now());
        hashtag.setStatusBy(actorId);
        // A pin holds a hashtag at the top of the trending list, so leaving one on a tag that has
        // just been taken out of circulation would keep promoting the exact term an administrator
        // acted to suppress. Cleared here rather than in the admin layer so no future caller of
        // changeStatus can forget it.
        if (target != HashtagStatus.ACTIVE) {
            hashtag.setPinnedAt(null);
            hashtag.setPinnedBy(null);
        }
        hashtagRepository.save(hashtag);

        // Purged in the same transaction as the status change rather than left to the next job
        // run. A hashtag is usually taken out of circulation in reaction to something happening
        // right now, which is exactly when it is at the top of the trending list, so another job
        // cycle is the worst possible hour to leave it there.
        int purged =
                target == HashtagStatus.ACTIVE
                        ? 0
                        : hashtagTrendingRepository.deleteAllByHashtagId(hashtagId);

        // One event either way. The consumer reads the row's current status and post_count, so it
        // turns this into an index delete when the tag has left circulation and into an upsert when
        // it has come back, without a second event type.
        indexEventPublisher.enqueueSync(hashtagId);

        log.info(
                "Hashtag status changed: hashtagId={}, from={}, to={}, trendingRowsPurged={}",
                hashtagId,
                previous,
                target,
                purged);
        return new HashtagLifecycleResult(
                hashtagId, hashtag.getName(), previous, target, purged, toResponse(hashtag));
    }

    @Override
    @Transactional
    public HashtagLifecycleResult pin(UUID actorId, UUID hashtagId) {
        Hashtag hashtag = requireHashtag(hashtagId);
        // Refused rather than silently accepted: a pin promotes a term platform-wide, and a term
        // out of circulation must not be promoted. This is a caller error, so it surfaces as one.
        if (hashtag.getStatus() != HashtagStatus.ACTIVE) {
            throw new AppException(ApiErrorCode.HASHTAG_UNAVAILABLE);
        }
        if (hashtag.getPinnedAt() != null) {
            throw new AppException(ApiErrorCode.ADMIN_INVALID_TRANSITION);
        }
        hashtag.setPinnedAt(OffsetDateTime.now());
        hashtag.setPinnedBy(actorId);
        hashtagRepository.save(hashtag);
        log.info("Hashtag pinned: hashtagId={}, actorId={}", hashtagId, actorId);
        return new HashtagLifecycleResult(
                hashtagId,
                hashtag.getName(),
                hashtag.getStatus(),
                hashtag.getStatus(),
                0,
                toResponse(hashtag));
    }

    @Override
    @Transactional
    public HashtagLifecycleResult unpin(UUID actorId, UUID hashtagId) {
        Hashtag hashtag = requireHashtag(hashtagId);
        if (hashtag.getPinnedAt() == null) {
            throw new AppException(ApiErrorCode.ADMIN_INVALID_TRANSITION);
        }
        hashtag.setPinnedAt(null);
        hashtag.setPinnedBy(null);
        hashtagRepository.save(hashtag);
        log.info("Hashtag unpinned: hashtagId={}, actorId={}", hashtagId, actorId);
        return new HashtagLifecycleResult(
                hashtagId,
                hashtag.getName(),
                hashtag.getStatus(),
                hashtag.getStatus(),
                0,
                toResponse(hashtag));
    }

    private Hashtag requireHashtag(UUID hashtagId) {
        return hashtagRepository
                .findById(hashtagId)
                .orElseThrow(() -> new AppException(ApiErrorCode.HASHTAG_NOT_FOUND));
    }

    private CursorPageResponse<HashtagAdminResponse> toPage(
            List<Hashtag> rows, int pageSize, boolean hasPreviousPage, String scope) {
        KeysetPage.Result<Hashtag> page =
                KeysetPage.of(
                        rows,
                        pageSize,
                        h -> new Cursor(TimeCursors.toMicros(h.getCreatedAt()), h.getId()),
                        scope);
        return CursorPageResponse.of(
                page.content().stream().map(HashtagLifecycleServiceImpl::toResponse).toList(),
                page.hasNextPage(),
                page.startCursor(),
                page.endCursor(),
                hasPreviousPage);
    }

    private static HashtagAdminResponse toResponse(Hashtag hashtag) {
        return new HashtagAdminResponse(
                hashtag.getId(),
                hashtag.getName(),
                hashtag.getPostCount(),
                hashtag.getStatus(),
                hashtag.getStatusNote(),
                hashtag.getStatusAt(),
                hashtag.getStatusBy(),
                hashtag.getCreatedAt(),
                hashtag.getPinnedAt(),
                hashtag.getPinnedBy());
    }

    private static String normalizeQuery(String raw) {
        String trimmed = raw == null ? "" : raw.strip();
        if (trimmed.length() < MIN_QUERY_LENGTH) {
            throw new AppException(
                    ApiErrorCode.BAD_REQUEST,
                    "Search query must be at least " + MIN_QUERY_LENGTH + " characters");
        }
        return trimmed;
    }

    private static int normalizeLimit(int limit) {
        return limit < 1 ? DEFAULT_PAGE_SIZE : Math.min(limit, MAX_PAGE_SIZE);
    }
}
