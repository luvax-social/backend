package com.app.modules.post.converter;

import java.util.Locale;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import com.app.modules.post.enums.TagStatus;

/** Bridges the uppercase Java TagStatus enum to the lowercase Postgres tag_status enum. */
@Converter(autoApply = false)
public class TagStatusConverter implements AttributeConverter<TagStatus, String> {

    @Override
    public String convertToDatabaseColumn(TagStatus attribute) {
        return attribute == null ? null : attribute.name().toLowerCase(Locale.ROOT);
    }

    @Override
    public TagStatus convertToEntityAttribute(String dbData) {
        return dbData == null ? null : TagStatus.valueOf(dbData.toUpperCase(Locale.ROOT));
    }
}
