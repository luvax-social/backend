package com.app.modules.users.converter;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import com.app.modules.users.enums.VerificationRevocationActor;

/** Maps {@link VerificationRevocationActor} to the lowercase PostgreSQL enum labels. */
@Converter(autoApply = false)
public class VerificationRevocationActorConverter
        implements AttributeConverter<VerificationRevocationActor, String> {

    @Override
    public String convertToDatabaseColumn(VerificationRevocationActor attribute) {
        return attribute == null ? null : attribute.name().toLowerCase();
    }

    @Override
    public VerificationRevocationActor convertToEntityAttribute(String dbData) {
        return dbData == null ? null : VerificationRevocationActor.valueOf(dbData.toUpperCase());
    }
}
