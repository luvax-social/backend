package com.app.modules.recommendation.client.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One page of Gorse's item listing.
 *
 * @param cursor opaque position of the next page; empty when this was the last one
 * @param items the items of this page, in Gorse's own order
 */
public record GorseItemPage(
        @JsonProperty("Cursor") String cursor, @JsonProperty("Items") List<GorseItem> items) {}
