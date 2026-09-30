package com.app.common.analytics.migration;

/**
 * Published once every ClickHouse migration has applied in this process. The analytics listener
 * containers start on it, and not before, so a consumer never writes to a table that does not exist
 * yet.
 */
public record AnalyticsSchemaReadyEvent() {}
