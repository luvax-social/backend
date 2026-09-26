package com.app.modules.support.converter;

import java.util.Locale;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import com.app.modules.support.enums.SupportTicketStatus;

/**
 * Bridges the uppercase Java enum to the lowercase PostgreSQL {@code support_ticket_status} enum.
 */
@Converter(autoApply = false)
public class SupportTicketStatusConverter
        implements AttributeConverter<SupportTicketStatus, String> {

    @Override
    public String convertToDatabaseColumn(SupportTicketStatus attribute) {
        return attribute == null ? null : attribute.name().toLowerCase(Locale.ROOT);
    }

    @Override
    public SupportTicketStatus convertToEntityAttribute(String dbData) {
        return dbData == null ? null : SupportTicketStatus.valueOf(dbData.toUpperCase(Locale.ROOT));
    }
}
