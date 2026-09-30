package com.app.modules.admin.entity;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.app.modules.admin.converter.AdminActionTypeConverter;
import com.app.modules.admin.enums.AdminActionType;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Maps one immutable moderation audit event to the {@code admin_actions} table. */
@Entity
@Table(name = "admin_actions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdminAction {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "admin_id", updatable = false)
    private UUID adminId;

    @Convert(converter = AdminActionTypeConverter.class)
    @Column(
            name = "action_type",
            nullable = false,
            updatable = false,
            columnDefinition = "admin_action_type")
    private AdminActionType actionType;

    @Column(name = "target_user_id", updatable = false)
    private UUID targetUserId;

    @Column(name = "target_entity_type", length = 50, updatable = false)
    private String targetEntityType;

    @Column(name = "target_entity_id", updatable = false)
    private UUID targetEntityId;

    @Column(name = "report_id", updatable = false)
    private UUID reportId;

    @Column(name = "reason", columnDefinition = "TEXT", updatable = false)
    private String reason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb", updatable = false)
    private Map<String, Object> metadata;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /**
     * Change counter a trigger bumps on every real update, read by the ClickHouse replica so the
     * newest state of a row wins. Never written from Java.
     */
    @Column(name = "row_version", insertable = false, updatable = false)
    private Long rowVersion;
}
