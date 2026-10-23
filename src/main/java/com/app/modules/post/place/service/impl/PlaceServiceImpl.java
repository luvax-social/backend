package com.app.modules.post.place.service.impl;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.common.text.TextMatchNormalizer;
import com.app.modules.post.place.dto.response.PlaceResponse;
import com.app.modules.post.place.dto.response.PlaceSummaryResponse;
import com.app.modules.post.place.entity.Place;
import com.app.modules.post.place.enums.PlaceStatus;
import com.app.modules.post.place.repository.PlaceRepository;
import com.app.modules.post.place.service.PlaceService;

@Service
public class PlaceServiceImpl implements PlaceService {

    private static final int MIN_NAME_LENGTH = 2;
    private static final int MAX_NAME_LENGTH = 100;
    private static final Pattern CONTROL = Pattern.compile("\\p{Cc}");
    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

    private final PlaceRepository placeRepository;

    public PlaceServiceImpl(PlaceRepository placeRepository) {
        this.placeRepository = placeRepository;
    }

    @Override
    @Transactional
    public PlaceResponse findOrCreate(UUID actorId, String rawName) {
        Place place = findOrCreatePlace(actorId, rawName);
        return toResponse(place);
    }

    @Override
    @Transactional(readOnly = true)
    public PlaceResponse getUsable(UUID placeId) {
        Place place =
                placeRepository
                        .findById(placeId)
                        .orElseThrow(() -> new AppException(ApiErrorCode.PLACE_NOT_FOUND));
        if (place.getStatus() != PlaceStatus.ACTIVE) {
            throw new AppException(ApiErrorCode.PLACE_UNAVAILABLE);
        }
        return toResponse(place);
    }

    @Override
    @Transactional
    public Place resolveForPostWrite(UUID actorId, UUID placeId, String placeName) {
        if (placeId != null) {
            Place place =
                    placeRepository
                            .findById(placeId)
                            .orElseThrow(() -> new AppException(ApiErrorCode.PLACE_NOT_FOUND));
            assertActive(place);
            return place;
        }
        if (placeName == null || placeName.isBlank()) {
            return null;
        }
        return findOrCreatePlace(actorId, placeName);
    }

    // The scheduled publisher catches the refusal and returns the post to drafts in the same
    // transaction, so the refusal must not mark that transaction rollback-only.
    @Override
    @Transactional(readOnly = true, noRollbackFor = AppException.class)
    public void assertPublishable(UUID placeId) {
        if (placeId == null) {
            return;
        }
        placeRepository.findById(placeId).ifPresent(PlaceServiceImpl::assertActive);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, PlaceSummaryResponse> loadActiveSummaries(Collection<UUID> placeIds) {
        Collection<UUID> ids = placeIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, PlaceSummaryResponse> summaries = new HashMap<>();
        placeRepository.findAllByIdIn(ids).stream()
                .filter(p -> p.getStatus() == PlaceStatus.ACTIVE)
                .forEach(
                        p ->
                                summaries.put(
                                        p.getId(),
                                        new PlaceSummaryResponse(p.getId(), p.getName())));
        return summaries;
    }

    private Place findOrCreatePlace(UUID actorId, String rawName) {
        String name = cleanName(rawName);
        String key = TextMatchNormalizer.normalizeName(name);
        if (key.isEmpty() || key.length() > MAX_NAME_LENGTH) {
            throw new AppException(ApiErrorCode.PLACE_NAME_INVALID);
        }
        // Inserted only when absent, so a concurrent create of the same name resolves to one row
        // and an existing row, whatever its status, is never overwritten.
        placeRepository.insertIfAbsent(name, key, actorId);
        Place place =
                placeRepository
                        .findByNormalizedName(key)
                        .orElseThrow(() -> new AppException(ApiErrorCode.PLACE_NOT_FOUND));
        assertActive(place);
        return place;
    }

    // Collapses runs of whitespace so the stored display name matches what a reader would type,
    // and rejects anything that is not a plain label before the key is even computed.
    private static String cleanName(String rawName) {
        if (rawName == null) {
            throw new AppException(ApiErrorCode.PLACE_NAME_INVALID);
        }
        String name = WHITESPACE_RUN.matcher(rawName.strip()).replaceAll(" ");
        if (name.length() < MIN_NAME_LENGTH
                || name.length() > MAX_NAME_LENGTH
                || CONTROL.matcher(name).find()) {
            throw new AppException(ApiErrorCode.PLACE_NAME_INVALID);
        }
        return name;
    }

    private static void assertActive(Place place) {
        if (place.getStatus() != PlaceStatus.ACTIVE) {
            throw new AppException(ApiErrorCode.POST_PLACE_UNAVAILABLE);
        }
    }

    private static PlaceResponse toResponse(Place place) {
        return new PlaceResponse(place.getId(), place.getName(), place.getPostCount());
    }
}
