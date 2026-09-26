package com.app.modules.support.converter;

import java.util.Locale;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import com.app.modules.support.enums.SupportSource;

/** Bridges the uppercase Java enum to the lowercase PostgreSQL {@code support_source} enum. */
@Converter(autoApply = false)
public class SupportSourceConverter implements AttributeConverter<SupportSource, String> {

    @Override
    public String convertToDatabaseColumn(SupportSource attribute) {
        return attribute == null ? null : attribute.name().toLowerCase(Locale.ROOT);
    }

    @Override
    public SupportSource convertToEntityAttribute(String dbData) {
        return dbData == null ? null : SupportSource.valueOf(dbData.toUpperCase(Locale.ROOT));
    }
}
