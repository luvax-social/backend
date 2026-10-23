package com.app.modules.report.enums;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Type of domain entity targeted by a report. */
public enum ReportType {
    POST,
    COMMENT,
    USER,
    STORY,
    MESSAGE,
    MESSAGE_REACTION,
    PLACE;

    @JsonCreator
    public static ReportType fromJson(String value) {
        return value == null || value.isBlank()
                ? null
                : ReportType.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    @JsonValue
    public String toJson() {
        return name().toLowerCase(Locale.ROOT);
    }
}
