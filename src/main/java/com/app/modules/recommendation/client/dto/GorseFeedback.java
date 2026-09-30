package com.app.modules.recommendation.client.dto;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A user-item feedback row in Gorse wire format. Gorse also returns {@code Updated} and {@code
 * Comment} when feedback is read back, which nothing here uses.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GorseFeedback(
        @JsonProperty("FeedbackType") String feedbackType,
        @JsonProperty("UserId") String userId,
        @JsonProperty("ItemId") String itemId,
        @JsonProperty("Timestamp") OffsetDateTime timestamp,
        // Carried so a future positive_feedback_types threshold can promote long dwells to
        // positive feedback without a code change. No threshold is configured today, so v0.5.11
        // reads this field and ignores it.
        @JsonProperty("Value") double value) {}
