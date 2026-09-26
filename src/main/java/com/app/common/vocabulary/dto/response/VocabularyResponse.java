package com.app.common.vocabulary.dto.response;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Every display vocabulary the API requires a client to send back, in one response.
 *
 * <p>One call rather than three. A client loads this once at start-up and caches it, and three
 * round trips for three small closed sets is three chances to be half-loaded.
 *
 * <p>Disabled rows are present rather than filtered out. Filtering them would leave a client unable
 * to distinguish a reason an administrator has switched off from one that never existed, and a
 * label it had already rendered would disappear with no explanation.
 *
 * @param reportReasons selectable report reasons, ascending by sort order then key
 * @param notificationTypes notification types, ascending by key
 * @param moderationActions moderation action types, ascending by key
 */
@Schema(description = "Every display vocabulary the API requires a client to send back")
public record VocabularyResponse(
        @Schema(description = "Selectable report reasons, in display order")
                List<ReportReasonVocabularyResponse> reportReasons,
        @Schema(description = "Notification types, ascending by key")
                List<NotificationTypeVocabularyResponse> notificationTypes,
        @Schema(description = "Moderation action types, ascending by key")
                List<ModerationActionVocabularyResponse> moderationActions,
        @Schema(description = "Support ticket categories, in display order")
                List<SupportCategoryVocabularyResponse> supportCategories) {}
