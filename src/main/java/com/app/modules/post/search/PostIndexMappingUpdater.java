package com.app.modules.post.search;

import java.util.Map;

import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Adds the fields a release introduced to the live {@code posts} mapping.
 *
 * <p>The index is created with its mapping only when it does not exist, so an index built by an
 * earlier release keeps the old mapping. Adding a field to an existing mapping is allowed and
 * touches no document, which is what makes this safe on a populated index: search keeps answering
 * while the documents are rewritten by {@code PostIndexBackfillRunner}.
 */
@Slf4j
@Component
public class PostIndexMappingUpdater {

    /** The field the publication ordering relies on; its absence marks a pre-release mapping. */
    static final String PUBLISHED_AT_FIELD = "published_at";

    private final ElasticsearchOperations elasticsearchOperations;

    public PostIndexMappingUpdater(ElasticsearchOperations elasticsearchOperations) {
        this.elasticsearchOperations = elasticsearchOperations;
    }

    /**
     * Whether the live mapping lacks a field this release added.
     *
     * @return true when the index exists and carries no {@code published_at} field
     */
    public boolean isMappingOutdated() {
        IndexOperations indexOps = elasticsearchOperations.indexOps(PostDocument.class);
        if (!indexOps.exists()) {
            return false;
        }
        Map<String, Object> mapping = indexOps.getMapping();
        Object properties = mapping.get("properties");
        return !(properties instanceof Map<?, ?> fields) || !fields.containsKey(PUBLISHED_AT_FIELD);
    }

    /**
     * Puts the mapping declared by the document class onto the live index.
     *
     * <p>Idempotent: fields that are already mapped identically are left as they are.
     */
    public void applyMapping() {
        IndexOperations indexOps = elasticsearchOperations.indexOps(PostDocument.class);
        indexOps.putMapping(indexOps.createMapping(PostDocument.class));
        log.info("Posts index mapping updated with the publication, place and author fields");
    }
}
