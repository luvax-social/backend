package com.app.modules.post.place.search;

import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

/** Spring Data Elasticsearch repository for {@link PlaceDocument}. */
public interface PlaceSearchRepository extends ElasticsearchRepository<PlaceDocument, String> {}
