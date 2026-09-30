package com.app.modules.admin.api;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.app.common.ApiConstants;
import com.app.common.config.openapi.AuthenticationRequiredResponse;
import com.app.common.response.ApiResponse;
import com.app.common.response.CursorPageResponse;
import com.app.modules.recommendation.dto.response.UserEventResponse;
import com.app.modules.recommendation.enums.UserEventType;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * OpenAPI contract for the administrative behavioural activity log.
 *
 * <p><strong>Only three event types are ever written.</strong> A session start on any route that
 * issues a session, a search on either search surface, and a view of another account's profile. The
 * {@code event_type} enum declares seventeen further values that exist in the schema and have no
 * writer, so a log showing only three kinds of row is the system working as designed and not a
 * defect. A view of one's own profile is deliberately not recorded.
 *
 * <p>The time window is mandatory. {@code user_events} is stored in ClickHouse, partitioned by
 * month on the event timestamp, so a window is the only thing that limits how much of the table a
 * query reads; a request bounded only by account would read every partition that account has rows
 * in. An investigator works a month at a time. While ClickHouse is unavailable the log answers 503
 * {@code ANALYTICS_UNAVAILABLE}: it has no other store to fall back to.
 */
@Tag(name = "Administration", description = "Moderation actions and immutable audit history")
@RequestMapping(ApiConstants.Admin.ROOT)
public interface AdminUserEventApi {

    /** Lists behavioural events newest first inside a mandatory bounded window. */
    @Operation(
            summary = "List behavioural events",
            description =
                    "Returns one cursor page of recorded behavioural events ordered by event time"
                            + " descending. Both bounds are required and the window may span at"
                            + " most 30 days. Only session_start, search and profile_view are ever"
                            + " produced; every other event_type value exists in the schema without"
                            + " a writer. A search carries the term and the surface it ran against"
                            + " in its metadata; a profile view carries the viewed account as its"
                            + " target entity. Viewing one's own profile records nothing.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Cursor page of events"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description =
                        "A bound is absent, 'to' is not after 'from', the window exceeds 30 days,"
                                + " or the cursor is malformed",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Administrator role required",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "503",
                description = "Analytics are temporarily unavailable (ANALYTICS_UNAVAILABLE)",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "429",
                description = "Rate limit exceeded",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @AuthenticationRequiredResponse
    @GetMapping(ApiConstants.Admin.USER_EVENTS)
    ResponseEntity<ApiResponse<CursorPageResponse<UserEventResponse>>> listUserEvents(
            @Parameter(
                            description =
                                    "Restrict to one account; omit for every account in the window")
                    @RequestParam(required = false)
                    UUID userId,
            @Parameter(
                            description = "Inclusive lower bound, ISO-8601 with offset",
                            example = "2026-08-01T00:00:00Z",
                            required = true)
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    OffsetDateTime from,
            @Parameter(
                            description =
                                    "Exclusive upper bound, ISO-8601 with offset; at most 30 days"
                                            + " after 'from'",
                            example = "2026-08-19T00:00:00Z",
                            required = true)
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    OffsetDateTime to,
            @Parameter(
                            description =
                                    "Restrict to one event type. Only session_start, search and"
                                            + " profile_view can match anything.")
                    @RequestParam(required = false)
                    UserEventType eventType,
            @Parameter(description = "Opaque cursor from a previous page")
                    @RequestParam(required = false)
                    String cursor,
            @Parameter(description = "Page size")
                    @RequestParam(defaultValue = "20")
                    @Min(1)
                    @Max(100)
                    int limit);
}
