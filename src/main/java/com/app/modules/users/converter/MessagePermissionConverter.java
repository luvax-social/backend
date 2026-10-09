package com.app.modules.users.converter;

import java.util.Locale;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import com.app.modules.users.enums.MessagePermission;

/**
 * Bridges the uppercase Java {@link MessagePermission} enum to the lowercase Postgres {@code
 * message_permission} enum.
 *
 * <p>Both directions use {@link Locale#ROOT}, because a default locale such as Turkish maps {@code
 * I} to a dotless or dotted form and would turn {@code FOLLOWING} into a value the database enum
 * does not contain.
 */
@Converter(autoApply = false)
public class MessagePermissionConverter implements AttributeConverter<MessagePermission, String> {

    @Override
    public String convertToDatabaseColumn(MessagePermission attribute) {
        return attribute == null ? null : attribute.name().toLowerCase(Locale.ROOT);
    }

    @Override
    public MessagePermission convertToEntityAttribute(String dbData) {
        return dbData == null ? null : MessagePermission.valueOf(dbData.toUpperCase(Locale.ROOT));
    }
}
