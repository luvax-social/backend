package com.app.modules.post.place.converter;

import java.util.Locale;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import com.app.modules.post.place.enums.PlaceStatus;

/** Bridges the uppercase Java PlaceStatus enum to the lowercase Postgres place_status enum. */
@Converter(autoApply = false)
public class PlaceStatusConverter implements AttributeConverter<PlaceStatus, String> {

    @Override
    public String convertToDatabaseColumn(PlaceStatus attribute) {
        return attribute == null ? null : attribute.name().toLowerCase(Locale.ROOT);
    }

    @Override
    public PlaceStatus convertToEntityAttribute(String dbData) {
        return dbData == null ? null : PlaceStatus.valueOf(dbData.toUpperCase(Locale.ROOT));
    }
}
