package com.app.modules.notification.api;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.app.common.ApiConstants;
import com.app.common.config.openapi.AuthenticationRequiredResponse;
import com.app.common.config.openapi.CursorErrorResponses;
import com.app.common.response.ApiResponse;
import com.app.modules.notification.dto.request.AdvanceSeenRequest;
import com.app.modules.notification.dto.request.ReadAllRequest;
import com.app.modules.notification.dto.response.NotificationPageResponse;
import com.app.modules.notification.dto.response.NotificationReadStateResponse;
import com.app.modules.notification.dto.response.NotificationStateResponse;
import com.app.modules.notification.dto.response.ReadAllResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * OpenAPI contract for the activity feed.
 *
 * <p>Seen and read are separate: opening the feed advances the seen watermark ({@code POST /seen}),
 * which clears the badge; a notification becomes read only when it is opened or marked read. Every
 * endpoint requires a bearer JWT and answers 401 without one and 429 when the shared rate limiter
 * is exhausted.
 */
@Tag(name = "Notifications", description = "Activity feed, seen watermark and read state")
@RequestMapping(ApiConstants.Notifications.ROOT)
public interface NotificationApi {

    /** Returns one page of the caller's activity feed for a filter; requires a bearer JWT. */
    @Operation(
            summary = "List notifications",
            description =
                    "Newest first by activityAt. filter is one of all, unread, comments,"
                            + " mentions, follows, system or verified. Pending follow requests are"
                            + " never rows; they are summarised by the pinned entry in"
                            + " GET /state. The first page carries head, the position to advance"
                            + " the seen watermark to after rendering it.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Feed page"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description =
                        "BAD_REQUEST for an unknown filter; VALIDATION_ERROR for a limit"
                                + " outside 1..100",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "401",
                description = "Missing or invalid access token",
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
    @CursorErrorResponses
    @GetMapping
    ResponseEntity<ApiResponse<NotificationPageResponse>> listNotifications(
            @Parameter(
                            description =
                                    "all (default), unread, comments, mentions, follows, system,"
                                            + " verified")
                    @RequestParam(value = "filter", required = false)
                    String filter,
            @Parameter(description = "Opaque cursor from the previous page's endCursor")
                    @RequestParam(value = "cursor", required = false)
                    String cursor,
            @Parameter(description = "Page size, 1 to 100, default 20")
                    @RequestParam(value = "limit", defaultValue = "20")
                    @Min(1)
                    @Max(100)
                    int limit);

    /** Returns the caller's badge, watermarks and pinned follow-request entry; requires a JWT. */
    @Operation(
            summary = "Get feed state",
            description =
                    "unseen is the badge (count capped at 99, capped true means 99+). seen and"
                            + " previous are the watermarks; followRequests feeds the pinned entry.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Feed state"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "401",
                description = "Missing or invalid access token",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @GetMapping(ApiConstants.Notifications.STATE)
    ResponseEntity<ApiResponse<NotificationStateResponse>> getState();

    /** Advances the caller's seen watermark to the newest row they rendered; requires a JWT. */
    @Operation(
            summary = "Advance the seen watermark",
            description =
                    "Send head from the first page, or the newest item rendered since. The position"
                            + " is clamped to the row's current position and the server clock and"
                            + " never moves backwards, so the call is idempotent. Returns the new"
                            + " state.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "State after the advance"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "415",
                description = "Request body was sent with an unsupported Content-Type",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "VALIDATION_ERROR: activityAt or id missing",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "FORBIDDEN: the notification is not the caller's",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @AuthenticationRequiredResponse
    @PostMapping(ApiConstants.Notifications.SEEN)
    ResponseEntity<ApiResponse<NotificationStateResponse>> advanceSeen(
            @Valid @RequestBody AdvanceSeenRequest request);

    /** Marks one of the caller's notifications read; idempotent; requires a bearer JWT. */
    @Operation(summary = "Mark a notification read")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Read state"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "FORBIDDEN: not the caller's, or deleted",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @AuthenticationRequiredResponse
    @PutMapping(ApiConstants.Notifications.READ)
    ResponseEntity<ApiResponse<NotificationReadStateResponse>> markRead(
            @PathVariable("notificationId") UUID notificationId);

    /** Marks one of the caller's notifications unread; idempotent; requires a bearer JWT. */
    @Operation(summary = "Mark a notification unread")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Read state, readAt null"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "FORBIDDEN: not the caller's, or deleted",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @AuthenticationRequiredResponse
    @DeleteMapping(ApiConstants.Notifications.READ)
    ResponseEntity<ApiResponse<NotificationReadStateResponse>> markUnread(
            @PathVariable("notificationId") UUID notificationId);

    /** Marks read every notification at or below the given position; requires a bearer JWT. */
    @Operation(
            summary = "Mark all notifications read",
            description =
                    "Marks read every visible, unread notification at or below upTo, the newest row"
                            + " the client rendered; anything that arrived later stays unread.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "How many changed"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "415",
                description = "Request body was sent with an unsupported Content-Type",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "VALIDATION_ERROR: upTo missing or incomplete",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @AuthenticationRequiredResponse
    @PatchMapping(ApiConstants.Notifications.READ_ALL)
    ResponseEntity<ApiResponse<ReadAllResponse>> markReadUpTo(
            @Valid @RequestBody ReadAllRequest request);

    /** Removes one of the caller's notifications from their feed; idempotent; requires a JWT. */
    @Operation(summary = "Delete a notification")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "204",
                description = "Deleted, or already deleted"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "FORBIDDEN: not the caller's",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @AuthenticationRequiredResponse
    @DeleteMapping(ApiConstants.Notifications.BY_ID)
    ResponseEntity<Void> delete(@PathVariable("notificationId") UUID notificationId);
}
