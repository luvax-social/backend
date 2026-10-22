package com.app.modules.post.place.service;

import com.app.common.response.CursorPageResponse;
import com.app.modules.post.place.dto.response.PlaceResponse;

/** Name search over the places index, degrading to PostgreSQL trigram search when it is down. */
public interface PlaceSearchService {

    /**
     * Searches usable places by name, most used first.
     *
     * @param query the text typed so far
     * @param cursor opaque cursor from the prior page, or null for the first page
     * @param limit requested page size
     * @return a page of active places that at least one published post uses
     */
    CursorPageResponse<PlaceResponse> search(String query, String cursor, int limit);
}
