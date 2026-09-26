package com.app.modules.mail.converter;

import java.util.Locale;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import com.app.modules.mail.enums.EmailDeliveryStatus;

/**
 * Bridges the uppercase Java enum to the lowercase PostgreSQL {@code email_delivery_status} enum.
 */
@Converter(autoApply = false)
public class EmailDeliveryStatusConverter
        implements AttributeConverter<EmailDeliveryStatus, String> {

    @Override
    public String convertToDatabaseColumn(EmailDeliveryStatus attribute) {
        return attribute == null ? null : attribute.name().toLowerCase(Locale.ROOT);
    }

    @Override
    public EmailDeliveryStatus convertToEntityAttribute(String dbData) {
        return dbData == null ? null : EmailDeliveryStatus.valueOf(dbData.toUpperCase(Locale.ROOT));
    }
}
