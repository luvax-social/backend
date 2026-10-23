package com.app.modules.users.converter;

import java.util.Locale;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import com.app.modules.users.enums.TagPermission;

/**
 * Bridges the uppercase Java {@link TagPermission} enum to the lowercase Postgres {@code
 * tag_permission} enum, using {@link Locale#ROOT} in both directions for the same reason {@link
 * MessagePermissionConverter} does.
 */
@Converter(autoApply = false)
public class TagPermissionConverter implements AttributeConverter<TagPermission, String> {

    @Override
    public String convertToDatabaseColumn(TagPermission attribute) {
        return attribute == null ? null : attribute.name().toLowerCase(Locale.ROOT);
    }

    @Override
    public TagPermission convertToEntityAttribute(String dbData) {
        return dbData == null ? null : TagPermission.valueOf(dbData.toUpperCase(Locale.ROOT));
    }
}
