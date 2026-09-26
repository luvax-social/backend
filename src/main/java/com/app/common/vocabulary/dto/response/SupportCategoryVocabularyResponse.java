package com.app.common.vocabulary.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One support ticket category and its display metadata.
 *
 * @param categoryKey the enum value, matching {@code support_category}
 * @param displayName the label a client shows
 * @param description one line explaining when to choose it
 * @param isAppeal true for the four appeal categories, which only an administrator may decide
 * @param allowsPublicForm whether the category may be chosen on the anonymous public form; false
 *     for every appeal, because an appeal needs an audit row that only a signed link supplies
 * @param isEnabled whether the category is currently offered
 * @param sortOrder display order
 */
public record SupportCategoryVocabularyResponse(
        @Schema(description = "Enum value") String categoryKey,
        @Schema(description = "Label to show") String displayName,
        @Schema(description = "When to choose it", nullable = true) String description,
        @Schema(description = "True for the four appeal categories") boolean isAppeal,
        @Schema(description = "Selectable on the public form") boolean allowsPublicForm,
        @Schema(description = "Currently offered") boolean isEnabled,
        @Schema(description = "Display order") short sortOrder) {}
