package com.app.modules.recommendation.client.impl;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.app.modules.recommendation.client.GorseClient;
import com.app.modules.recommendation.client.dto.GorseFeedback;
import com.app.modules.recommendation.client.dto.GorseItem;
import com.app.modules.recommendation.client.dto.GorseItemPage;
import com.app.modules.recommendation.client.dto.GorseScore;
import com.app.modules.recommendation.client.dto.GorseUser;

@Component
public class GorseClientImpl implements GorseClient {

    private static final ParameterizedTypeReference<List<GorseScore>> SCORE_LIST =
            new ParameterizedTypeReference<>() {};

    private static final ParameterizedTypeReference<List<GorseFeedback>> FEEDBACK_LIST =
            new ParameterizedTypeReference<>() {};

    private final RestClient gorseRestClient;

    public GorseClientImpl(RestClient gorseRestClient) {
        this.gorseRestClient = gorseRestClient;
    }

    @Override
    public List<GorseScore> recommend(UUID userId, int n, int offset) {
        List<GorseScore> body =
                gorseRestClient
                        .get()
                        .uri(
                                uri ->
                                        uri.path("/api/recommend/{userId}")
                                                .queryParam("n", n)
                                                .queryParam("offset", offset)
                                                .build(userId))
                        .retrieve()
                        .body(SCORE_LIST);
        return body == null ? List.of() : body;
    }

    @Override
    public List<GorseScore> popular(int n, int offset) {
        List<GorseScore> body =
                gorseRestClient
                        .get()
                        .uri(
                                uri ->
                                        uri.path("/api/non-personalized/popular")
                                                .queryParam("n", n)
                                                .queryParam("offset", offset)
                                                .build())
                        .retrieve()
                        .body(SCORE_LIST);
        return body == null ? List.of() : body;
    }

    @Override
    public List<GorseScore> trending(int n, int offset) {
        List<GorseScore> body =
                gorseRestClient
                        .get()
                        .uri(
                                uri ->
                                        uri.path("/api/non-personalized/trending")
                                                .queryParam("n", n)
                                                .queryParam("offset", offset)
                                                .build())
                        .retrieve()
                        .body(SCORE_LIST);
        return body == null ? List.of() : body;
    }

    @Override
    public List<GorseScore> userNeighbors(UUID userId, int n) {
        List<GorseScore> body =
                gorseRestClient
                        .get()
                        .uri(
                                uri ->
                                        uri.path("/api/user/{userId}/neighbors")
                                                .queryParam("n", n)
                                                .build(userId))
                        .retrieve()
                        .body(SCORE_LIST);
        return body == null ? List.of() : body;
    }

    @Override
    public void upsertUsers(List<GorseUser> users) {
        gorseRestClient.post().uri("/api/users").body(users).retrieve().toBodilessEntity();
    }

    @Override
    public void upsertItems(List<GorseItem> items) {
        gorseRestClient.post().uri("/api/items").body(items).retrieve().toBodilessEntity();
    }

    @Override
    public void hideItem(String itemId) {
        gorseRestClient
                .patch()
                .uri("/api/item/{itemId}", itemId)
                .body(Map.of("IsHidden", true))
                .retrieve()
                .toBodilessEntity();
    }

    @Override
    public void insertFeedback(List<GorseFeedback> feedback) {
        gorseRestClient.post().uri("/api/feedback").body(feedback).retrieve().toBodilessEntity();
    }

    @Override
    public void upsertFeedback(List<GorseFeedback> feedback) {
        gorseRestClient.put().uri("/api/feedback").body(feedback).retrieve().toBodilessEntity();
    }

    @Override
    public GorseItemPage listItems(String cursor, int n) {
        GorseItemPage body =
                gorseRestClient
                        .get()
                        .uri(
                                uri -> {
                                    uri.path("/api/items").queryParam("n", n);
                                    if (cursor != null && !cursor.isEmpty()) {
                                        uri.queryParam("cursor", cursor);
                                    }
                                    return uri.build();
                                })
                        .retrieve()
                        .body(GorseItemPage.class);
        return body == null ? new GorseItemPage("", List.of()) : body;
    }

    @Override
    public Optional<GorseFeedback> getFeedback(String feedbackType, String userId, String itemId) {
        // The user's own list of this type rather than GET /api/feedback/{type}/{user}/{item}: on
        // v0.5.11 that single-tuple endpoint panics on a tuple Gorse does not hold and closes the
        // connection with no reply, which is indistinguishable from an outage. The list endpoint
        // answers 200 with an empty array for an unknown user or an empty type.
        List<GorseFeedback> body =
                gorseRestClient
                        .get()
                        .uri("/api/user/{user}/feedback/{type}", userId, feedbackType)
                        .retrieve()
                        .body(FEEDBACK_LIST);
        if (body == null) {
            return Optional.empty();
        }
        return body.stream().filter(feedback -> itemId.equals(feedback.itemId())).findFirst();
    }
}
