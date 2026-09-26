package com.app.modules.support.converter;

import java.util.Locale;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import com.app.modules.support.enums.SupportCategory;

/** Bridges the uppercase Java enum to the lowercase PostgreSQL {@code support_category} enum. */
@Converter(autoApply = false)
public class SupportCategoryConverter implements AttributeConverter<SupportCategory, String> {

    @Override
    public String convertToDatabaseColumn(SupportCategory attribute) {
        return attribute == null ? null : attribute.name().toLowerCase(Locale.ROOT);
    }

    @Override
    public SupportCategory convertToEntityAttribute(String dbData) {
        return dbData == null ? null : SupportCategory.valueOf(dbData.toUpperCase(Locale.ROOT));
    }
}
