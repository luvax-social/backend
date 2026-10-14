package com.app.modules.message.converter;

import java.util.Locale;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import com.app.modules.message.enums.ConversationRequestStatus;

/**
 * Bridges the uppercase Java {@link ConversationRequestStatus} enum to the lowercase Postgres
 * {@code conversation_request_status} enum, using {@link Locale#ROOT} in both directions.
 */
@Converter(autoApply = false)
public class ConversationRequestStatusConverter
        implements AttributeConverter<ConversationRequestStatus, String> {

    @Override
    public String convertToDatabaseColumn(ConversationRequestStatus attribute) {
        return attribute == null ? null : attribute.name().toLowerCase(Locale.ROOT);
    }

    @Override
    public ConversationRequestStatus convertToEntityAttribute(String dbData) {
        return dbData == null
                ? null
                : ConversationRequestStatus.valueOf(dbData.toUpperCase(Locale.ROOT));
    }
}
