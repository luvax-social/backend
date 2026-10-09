package com.app.modules.users.converter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;

import org.junit.jupiter.api.Test;

import com.app.modules.users.enums.MessagePermission;

class MessagePermissionConverterTest {

    private final MessagePermissionConverter converter = new MessagePermissionConverter();

    @Test
    void convert_everyValue_roundTripsUnderTurkishDefaultLocale() {
        Locale original = Locale.getDefault();
        Locale.setDefault(new Locale("tr"));
        try {
            for (MessagePermission permission : MessagePermission.values()) {
                String column = converter.convertToDatabaseColumn(permission);

                assertThat(column).isEqualTo(permission.name().toLowerCase(Locale.ROOT));
                assertThat(converter.convertToEntityAttribute(column)).isEqualTo(permission);
            }
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void convert_nullValues_stayNull() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }
}
