package com.app.modules.post.place.service.impl;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import com.app.modules.post.place.dto.response.PlaceAdminResponse;
import com.app.modules.post.place.entity.Place;
import com.app.modules.post.place.enums.PlaceStatus;
import com.app.modules.post.place.repository.PlaceRepository;
import com.app.modules.post.place.service.PlaceIndexEventPublisher;
import com.app.modules.post.place.service.PlaceLifecycleResult;
import com.app.modules.post.place.service.PlaceLifecycleService;

@Service
public class PlaceLifecycleServiceImpl implements PlaceLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(PlaceLifecycleServiceImpl.class);
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final PlaceRepository placeRepository;
    private final PlaceIndexEventPublisher indexEventPublisher;

    public PlaceLifecycleServiceImpl(
            PlaceRepository placeRepository, PlaceIndexEventPublisher indexEventPublisher) {
        this.placeRepository = placeRepository;
        this.indexEventPublisher = indexEventPublisher;
    }

    @Override
    @Transactional(readOnly = true)
    public CursorPageResponse<PlaceAdminResponse> list(
            PlaceStatus status, String cursor, int limit) {
        int pageSize = normalizeLimit(limit);
        Cursor decoded = CursorCodec.decode(cursor, CursorScope.ADMIN_PLACES);
        List<Place> rows =
                placeRepository.findAdminPage(
                        status,
                        decoded == null ? null : TimeCursors.fromMicros(decoded.sortValueMicros()),
                        decoded == null ? null : decoded.id(),
                        pageSize + 1);
        return toPage(rows, pageSize, cursor != null, CursorScope.ADMIN_PLACES);
    }

    @Override
    @Transactional(readOnly = true)
    public CursorPageResponse<PlaceAdminResponse> search(
            String query, PlaceStatus status, String cursor, int limit) {
        if (query == null || query.isBlank()) {
            throw new AppException(ApiErrorCode.BAD_REQUEST, "Search text must not be blank");
        }
        int pageSize = normalizeLimit(limit);
        Cursor decoded = CursorCodec.decode(cursor, CursorScope.ADMIN_PLACE_SEARCH);
        List<Place> rows =
                placeRepository.searchAdmin(
                        query.strip(),
                        status,
                        decoded == null ? null : TimeCursors.fromMicros(decoded.sortValueMicros()),
                        decoded == null ? null : decoded.id(),
                        pageSize + 1);
        return toPage(rows, pageSize, cursor != null, CursorScope.ADMIN_PLACE_SEARCH);
    }

    @Override
    @Transactional
    public PlaceLifecycleResult changeStatus(
            UUID actorId, UUID placeId, PlaceStatus target, String note) {
        Place place =
                placeRepository
                        .findById(placeId)
                        .orElseThrow(() -> new AppException(ApiErrorCode.PLACE_NOT_FOUND));
        PlaceStatus previous = place.getStatus();
        if (previous == target) {
            throw new AppException(ApiErrorCode.ADMIN_INVALID_TRANSITION);
        }
        place.setStatus(target);
        place.setStatusNote(note);
        place.setStatusAt(OffsetDateTime.now());
        place.setStatusBy(actorId);
        placeRepository.save(place);

        // One event either way. The consumer reads the row's current status and post_count, so it
        // turns this into an index delete when the place has left circulation and into an upsert
        // when it has come back, without a second event type.
        indexEventPublisher.enqueueSync(placeId);

        log.info("Place status changed: placeId={}, from={}, to={}", placeId, previous, target);
        return new PlaceLifecycleResult(
                placeId,
                place.getName(),
                previous,
                target,
                place.getPostCount(),
                toResponse(place));
    }

    private static int normalizeLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(limit, MAX_PAGE_SIZE);
    }

    private CursorPageResponse<PlaceAdminResponse> toPage(
            List<Place> rows, int pageSize, boolean hasPreviousPage, String scope) {
        KeysetPage.Result<Place> page =
                KeysetPage.of(
                        rows,
                        pageSize,
                        p -> new Cursor(TimeCursors.toMicros(p.getCreatedAt()), p.getId()),
                        scope);
        return CursorPageResponse.of(
                page.content().stream().map(PlaceLifecycleServiceImpl::toResponse).toList(),
                page.hasNextPage(),
                page.startCursor(),
                page.endCursor(),
                hasPreviousPage);
    }

    private static PlaceAdminResponse toResponse(Place place) {
        return new PlaceAdminResponse(
                place.getId(),
                place.getName(),
                place.getNormalizedName(),
                place.getStatus(),
                place.getStatusNote(),
                place.getStatusAt(),
                place.getStatusBy(),
                place.getCreatedBy(),
                place.getPostCount(),
                place.getCreatedAt());
    }
}
