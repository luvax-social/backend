package com.app.modules.post.place.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.app.modules.post.place.entity.Place;
import com.app.modules.post.place.enums.PlaceStatus;

/** Native keyset reads behind the administrative places registry. */
public interface PlaceRepositoryCustom {

    /**
     * One keyset page of places, newest first, spanning every status unless one is named.
     *
     * @param status status to match, or null for every status
     * @param cursorCreatedAt {@code created_at} of the cursor row, or null for the first page
     * @param cursorId id of the cursor row, or null for the first page
     * @param limit rows to return; callers pass one more than the page size
     * @return places ordered by {@code (created_at, id)} descending
     */
    List<Place> findAdminPage(
            PlaceStatus status, OffsetDateTime cursorCreatedAt, UUID cursorId, int limit);

    /**
     * One keyset page of places whose name contains the text, spanning every status unless one is
     * named.
     *
     * @param queryText case-insensitive substring of the display name or the normalised key
     * @param status status to match, or null for every status
     * @param cursorCreatedAt {@code created_at} of the cursor row, or null for the first page
     * @param cursorId id of the cursor row, or null for the first page
     * @param limit rows to return; callers pass one more than the page size
     * @return places ordered by {@code (created_at, id)} descending
     */
    List<Place> searchAdmin(
            String queryText,
            PlaceStatus status,
            OffsetDateTime cursorCreatedAt,
            UUID cursorId,
            int limit);
}
