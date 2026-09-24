package com.app.modules.notification.entity.converter;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import com.app.modules.notification.entity.enums.NotificationCategory;

@Converter(autoApply = false)
public class NotificationCategoryConverter
        implements AttributeConverter<NotificationCategory, String> {

    @Override
    public String convertToDatabaseColumn(NotificationCategory attribute) {
        return attribute == null ? null : attribute.toJson();
    }

    @Override
    public NotificationCategory convertToEntityAttribute(String dbData) {
        return dbData == null ? null : NotificationCategory.fromValue(dbData);
    }
}
