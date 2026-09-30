package com.app.modules.admin.enums;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Bucket widths a statistics series can be read at. Only half-hour buckets are stored; a day is
 * computed from them when a series is read.
 */
public enum StatGranularity {
    HALF_HOUR,
    DAY;

    @JsonCreator
    public static StatGranularity fromJson(String value) {
        return value == null || value.isBlank()
                ? null
                : StatGranularity.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    @JsonValue
    public String toJson() {
        return name().toLowerCase(Locale.ROOT);
    }
}
