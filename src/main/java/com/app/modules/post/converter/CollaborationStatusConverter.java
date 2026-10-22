package com.app.modules.post.converter;

import java.util.Locale;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import com.app.modules.post.enums.CollaborationStatus;

/**
 * Bridges the uppercase Java CollaborationStatus enum to the lowercase Postgres
 * collaboration_status enum.
 */
@Converter(autoApply = false)
public class CollaborationStatusConverter
        implements AttributeConverter<CollaborationStatus, String> {

    @Override
    public String convertToDatabaseColumn(CollaborationStatus attribute) {
        return attribute == null ? null : attribute.name().toLowerCase(Locale.ROOT);
    }

    @Override
    public CollaborationStatus convertToEntityAttribute(String dbData) {
        return dbData == null ? null : CollaborationStatus.valueOf(dbData.toUpperCase(Locale.ROOT));
    }
}
