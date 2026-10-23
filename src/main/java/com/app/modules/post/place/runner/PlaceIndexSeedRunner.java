package com.app.modules.post.place.runner;

import java.util.List;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.query.Query;
import org.springframework.stereotype.Component;

import com.app.modules.post.place.config.PlaceProperties;
import com.app.modules.post.place.repository.PlaceRepository;
import com.app.modules.post.place.search.PlaceDocument;
import com.app.modules.post.place.search.PlaceSearchRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Seeds the Elasticsearch {@code places} index from PostgreSQL on startup when the index is empty.
 * Controlled by {@code app.place.seed.enabled}. Seeding failures are non-fatal: Elasticsearch is a
 * rebuildable secondary tier, so the application starts even if it is unavailable, and place search
 * degrades to the PostgreSQL pg_trgm fallback.
 */
@Slf4j
@Component
public class PlaceIndexSeedRunner implements ApplicationRunner {

    private final PlaceRepository placeRepository;
    private final PlaceSearchRepository placeSearchRepository;
    private final ElasticsearchOperations elasticsearchOperations;
    private final PlaceProperties properties;

    public PlaceIndexSeedRunner(
            PlaceRepository placeRepository,
            PlaceSearchRepository placeSearchRepository,
            ElasticsearchOperations elasticsearchOperations,
            PlaceProperties properties) {
        this.placeRepository = placeRepository;
        this.placeSearchRepository = placeSearchRepository;
        this.elasticsearchOperations = elasticsearchOperations;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.getSeed().isEnabled()) {
            return;
        }
        try {
            IndexOperations indexOps = elasticsearchOperations.indexOps(PlaceDocument.class);
            // Repository auto-creation is disabled (createIndex = false), so create the index with
            // its ngram mapping here once Elasticsearch is confirmed reachable.
            if (!indexOps.exists()) {
                indexOps.createWithMapping();
            }
            long count = elasticsearchOperations.count(Query.findAll(), PlaceDocument.class);
            // Skip bootstrap if the index already has documents - partial population is also
            // skipped
            if (count > 0) {
                log.info("Place index already contains {} documents; skipping seed", count);
                return;
            }
            List<PlaceDocument> documents =
                    placeRepository.findAll().stream()
                            .filter(p -> p.getPostCount() > 0)
                            .filter(
                                    p ->
                                            p.getStatus()
                                                    == com.app.modules.post.place.enums.PlaceStatus
                                                            .ACTIVE)
                            .map(
                                    p ->
                                            PlaceDocument.builder()
                                                    .id(p.getId().toString())
                                                    .name(p.getName())
                                                    .normalizedName(p.getNormalizedName())
                                                    .postCount(p.getPostCount())
                                                    .createdAt(p.getCreatedAt())
                                                    .build())
                            .toList();
            placeSearchRepository.saveAll(documents);
            log.info("Place index seeded with {} documents", documents.size());
        } catch (Exception e) {
            // Non-fatal: never block startup on the rebuildable search tier.
            log.warn("Place index seeding skipped due to Elasticsearch error: {}", e.getMessage());
        }
    }
}
