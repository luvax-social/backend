package com.app.modules.recommendation.service.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.app.modules.recommendation.client.GorseClient;
import com.app.modules.recommendation.client.dto.GorseScore;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;

/**
 * The Gorse user-to-user source, behind the shared {@code gorse} circuit breaker.
 *
 * <p>Its own bean rather than a method on {@code SuggestionServiceImpl}, and that is load-bearing
 * rather than tidiness. {@code @CircuitBreaker} is applied by a Spring AOP proxy, and a call from
 * one method of a class to another method of the same class does not pass through that proxy. The
 * annotation on an internally invoked method is inert: the breaker would never open, never count a
 * failure, and the fallback would never run. Crossing a bean boundary is what makes it real.
 *
 * <p>An absent {@code [[recommend.user-to-user]]} recommender is not a failure. Gorse answers an
 * empty list for it, exactly as it does for a user with no neighbours yet, so both reach the caller
 * as a source contributing nothing. Only transport and server failures reach the breaker, which is
 * what a breaker is for: charging an empty list to it would open a breaker shared with the
 * personalized feed and take that down alongside a perfectly healthy Gorse.
 */
@Component
public class GorseNeighbourSource {

    private static final Logger log = LoggerFactory.getLogger(GorseNeighbourSource.class);

    private final GorseClient gorseClient;

    public GorseNeighbourSource(GorseClient gorseClient) {
        this.gorseClient = gorseClient;
    }

    /**
     * The accounts Gorse considers most similar to one user.
     *
     * @param userId the account to find neighbours for
     * @param limit how many neighbours to ask for
     * @return neighbour ids most-similar first, or empty when Gorse has nothing or is unreachable
     */
    @CircuitBreaker(name = "gorse", fallbackMethod = "neighboursFallback")
    public List<UUID> neighbours(UUID userId, int limit) {
        List<GorseScore> scores = gorseClient.userNeighbors(userId, limit);
        List<UUID> ids = new ArrayList<>(scores.size());
        for (GorseScore score : scores) {
            try {
                ids.add(UUID.fromString(score.id()));
            } catch (IllegalArgumentException ex) {
                // Gorse item ids are opaque strings. One that is not a UUID cannot be an account
                // here, so it is skipped rather than failing the whole source.
                log.debug("Skipping non-UUID Gorse neighbour id | id: {}", score.id());
            }
        }
        return ids;
    }

    @SuppressWarnings("unused")
    private List<UUID> neighboursFallback(UUID userId, int limit, Throwable throwable) {
        log.warn(
                "Gorse user-to-user neighbours unavailable, blending without them | viewer: {} |"
                        + " cause: {}",
                userId,
                throwable.toString());
        return List.of();
    }
}
