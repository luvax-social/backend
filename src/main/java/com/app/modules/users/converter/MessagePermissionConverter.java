package com.app.modules.users.converter;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import com.app.modules.users.enums.MessagePermission;

/**
 * Bridges the uppercase Java {@link MessagePermission} enum to the lowercase Postgres {@code
 * message_permission} enum.
 */
@Converter(autoApply = false)
public class MessagePermissionConverter implements AttributeConverter<MessagePermission, String> {

    @Override
    public String convertToDatabaseColumn(MessagePermission attribute) {
        return attribute == null ? null : attribute.name().toLowerCase();
    }

    @Override
    public MessagePermission convertToEntityAttribute(String dbData) {
        return dbData == null ? null : MessagePermission.valueOf(dbData.toUpperCase());
    }
}
