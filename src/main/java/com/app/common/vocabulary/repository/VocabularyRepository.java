package com.app.common.vocabulary.repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.app.common.vocabulary.dto.response.ModerationActionVocabularyResponse;
import com.app.common.vocabulary.dto.response.NotificationTypeVocabularyResponse;
import com.app.common.vocabulary.dto.response.ReportReasonVocabularyResponse;
import com.app.common.vocabulary.dto.response.SupportCategoryVocabularyResponse;

/**
 * Read-only access to the three configuration tables that carry display metadata for enum values.
 *
 * <p>Native, with no entity behind any of them, for the same reason {@code
 * ReportReasonConfigReader} is: these are configuration rows, not domain aggregates, and mapping
 * them through JPA would put three entities and their lifecycles behind three tables that are only
 * ever read.
 *
 * <p>Every row is returned including disabled ones. The enabled flag is the answer a client needs,
 * not a filter to apply on its behalf.
 */
@Repository
public class VocabularyRepository {

    private final JdbcClient jdbcClient;

    public VocabularyRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * Reads every report reason, in the display order the table declares.
     *
     * <p>Ordered by {@code sort_order} then {@code reason_key}, so two rows sharing a sort order
     * still come back in a stable order rather than whichever the heap yields.
     *
     * @return every row of {@code report_reason_configs}, enabled or not
     */
    public List<ReportReasonVocabularyResponse> findReportReasons() {
        return jdbcClient
                .sql(
                        "SELECT reason_key, display_name, description, applies_to, is_enabled,"
                                + " sort_order FROM report_reason_configs"
                                + " ORDER BY sort_order ASC, reason_key ASC")
                .query(
                        (ResultSet rs, int rowNum) ->
                                new ReportReasonVocabularyResponse(
                                        rs.getString("reason_key"),
                                        rs.getString("display_name"),
                                        rs.getString("description"),
                                        toStringList(rs.getArray("applies_to")),
                                        rs.getBoolean("is_enabled"),
                                        rs.getShort("sort_order")))
                .list();
    }

    /**
     * Reads every notification type.
     *
     * @return every row of {@code notification_type_configs}, enabled or not
     */
    public List<NotificationTypeVocabularyResponse> findNotificationTypes() {
        return jdbcClient
                .sql(
                        "SELECT type_key, display_name, template_key, is_user_toggleable,"
                                + " is_enabled FROM notification_type_configs"
                                + " ORDER BY type_key ASC")
                .query(
                        (ResultSet rs, int rowNum) ->
                                new NotificationTypeVocabularyResponse(
                                        rs.getString("type_key"),
                                        rs.getString("display_name"),
                                        rs.getString("template_key"),
                                        rs.getBoolean("is_user_toggleable"),
                                        rs.getBoolean("is_enabled")))
                .list();
    }

    /**
     * Reads every moderation action type.
     *
     * @return every row of {@code moderation_action_configs}, enabled or not
     */
    public List<ModerationActionVocabularyResponse> findModerationActions() {
        return jdbcClient
                .sql(
                        "SELECT action_key, display_name, requires_reason, is_reversible,"
                                + " is_enabled FROM moderation_action_configs"
                                + " ORDER BY action_key ASC")
                .query(
                        (ResultSet rs, int rowNum) ->
                                new ModerationActionVocabularyResponse(
                                        rs.getString("action_key"),
                                        rs.getString("display_name"),
                                        rs.getBoolean("requires_reason"),
                                        rs.getBoolean("is_reversible"),
                                        rs.getBoolean("is_enabled")))
                .list();
    }

    /**
     * Reads every support ticket category, in the display order the table declares.
     *
     * <p>Ordered by {@code sort_order} then key, matching the report reason read above, so two rows
     * sharing a sort order still come back in a stable order.
     *
     * @return every row of {@code support_category_configs}, enabled or not
     */
    public List<SupportCategoryVocabularyResponse> findSupportCategories() {
        return jdbcClient
                .sql(
                        "SELECT category_key, display_name, description, is_appeal,"
                                + " allows_public_form, is_enabled, sort_order"
                                + " FROM support_category_configs"
                                + " ORDER BY sort_order ASC, category_key ASC")
                .query(
                        (ResultSet rs, int rowNum) ->
                                new SupportCategoryVocabularyResponse(
                                        rs.getString("category_key"),
                                        rs.getString("display_name"),
                                        rs.getString("description"),
                                        rs.getBoolean("is_appeal"),
                                        rs.getBoolean("allows_public_form"),
                                        rs.getBoolean("is_enabled"),
                                        rs.getShort("sort_order")))
                .list();
    }

    // An empty applies_to means the reason is valid for every report type, and the column is NOT
    // NULL, so an empty list here is the value rather than a missing one.
    private static List<String> toStringList(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        try {
            Object[] values = (Object[]) array.getArray();
            return Arrays.stream(values).map(String::valueOf).toList();
        } finally {
            array.free();
        }
    }
}
