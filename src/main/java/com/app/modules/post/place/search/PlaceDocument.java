package com.app.modules.post.place.search;

import java.time.OffsetDateTime;

import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.DateFormat;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.elasticsearch.annotations.InnerField;
import org.springframework.data.elasticsearch.annotations.MultiField;
import org.springframework.data.elasticsearch.annotations.Setting;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Elasticsearch projection of {@code places} for fuzzy name search.
 *
 * <p>The {@code normalized_name} field has a keyword main type and an {@code ngram} sub-field using
 * {@code ngram_analyzer} for prefix and fuzzy matching. Only active places that a published post
 * uses are indexed.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
// createIndex = false: the repository must not connect to Elasticsearch at startup. The index is
// created with its ngram mapping by PlaceIndexSeedRunner when Elasticsearch is reachable.
@Document(indexName = "places", createIndex = false)
@Setting(settingPath = "/elasticsearch/settings/places.json")
public class PlaceDocument {

    @Id private String id;

    @Field(type = FieldType.Keyword)
    private String name;

    // The normalised key is what search matches, so a query typed without accents finds a name
    // written with them; the display name above is only what the result shows.
    @MultiField(
            mainField = @Field(type = FieldType.Keyword, name = "normalized_name"),
            otherFields = {
                @InnerField(
                        suffix = "ngram",
                        type = FieldType.Text,
                        analyzer = "ngram_analyzer",
                        searchAnalyzer = "standard")
            })
    private String normalizedName;

    @Field(type = FieldType.Integer, name = "post_count")
    private int postCount;

    @Field(type = FieldType.Date, name = "created_at", format = DateFormat.date_time)
    private OffsetDateTime createdAt;
}
