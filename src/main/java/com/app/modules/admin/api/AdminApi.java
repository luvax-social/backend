package com.app.modules.admin.api;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.app.common.ApiConstants;
import com.app.common.config.openapi.AuthenticationRequiredResponse;
import com.app.common.config.openapi.CursorErrorResponses;
import com.app.common.config.openapi.MalformedBodyErrorResponses;
import com.app.common.response.ApiResponse;
import com.app.common.response.CursorPageResponse;
import com.app.modules.admin.dto.request.AdminActionRequest;
import com.app.modules.admin.dto.request.AdminEscalateReportRequest;
import com.app.modules.admin.dto.request.AdminSuspendUserRequest;
import com.app.modules.admin.dto.response.AdminActionResponse;
import com.app.modules.admin.dto.response.AdminActionSummaryResponse;
import com.app.modules.admin.dto.response.AdminPostRestoreResponse;
import com.app.modules.admin.dto.response.AdminReportTargetResponse;
import com.app.modules.admin.dto.response.EscalatedReportCountResponse;
import com.app.modules.admin.enums.AdminActionType;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/** OpenAPI contract for role-restricted moderation actions and immutable audit history. */
@Tag(name = "Administration", description = "Moderation actions and immutable audit history")
@RequestMapping(ApiConstants.Admin.ROOT)
public interface AdminApi {

    /** Bans a user and returns the persisted audit event. */
    @Operation(summary = "Ban a user")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "User banned"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Administrator role required, or the target account is protected",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "User not found",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description = "Invalid user status transition, or the actor is the target",
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
    @MalformedBodyErrorResponses
    @AuthenticationRequiredResponse
    @PatchMapping(ApiConstants.Admin.BAN_USER)
    ResponseEntity<ApiResponse<AdminActionResponse>> banUser(
            @PathVariable("userId") UUID userId, @Valid @RequestBody AdminActionRequest request);

    /** Unbans a banned user and returns the persisted audit event. */
    @Operation(summary = "Unban a user")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "User unbanned"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Administrator role required, or the target account is protected",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "User not found",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description = "Invalid user status transition, or the actor is the target",
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
    @MalformedBodyErrorResponses
    @AuthenticationRequiredResponse
    @PatchMapping(ApiConstants.Admin.UNBAN_USER)
    ResponseEntity<ApiResponse<AdminActionResponse>> unbanUser(
            @PathVariable("userId") UUID userId, @Valid @RequestBody AdminActionRequest request);

    /** Suspends an active user and returns the persisted audit event. */
    @Operation(
            summary = "Suspend a user",
            description =
                    "Supplying durationDays fixes the term: the first authentication attempt after"
                            + " it lapses returns the account to active, and a periodic sweep does"
                            + " the same for an account nobody signs into. Omitting it makes the"
                            + " suspension indefinite, and nothing reinstates it automatically."
                            + " Both are supported and a consumer may offer either. An indefinite"
                            + " suspension stores a null suspendedUntil, which the reinstatement"
                            + " sweep never matches, so the account stays suspended until an"
                            + " administrator unsuspends it explicitly. The field is genuinely"
                            + " optional: omitting it is a deliberate choice rather than an"
                            + " incomplete request.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "User suspended"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Administrator role required, or the target account is protected",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "User not found",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description = "Invalid user status transition, or the actor is the target",
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
    @MalformedBodyErrorResponses
    @AuthenticationRequiredResponse
    @PatchMapping(ApiConstants.Admin.SUSPEND_USER)
    ResponseEntity<ApiResponse<AdminActionResponse>> suspendUser(
            @PathVariable("userId") UUID userId,
            @Valid @RequestBody AdminSuspendUserRequest request);

    /** Unsuspends a suspended user and returns the persisted audit event. */
    @Operation(summary = "Unsuspend a user")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "User unsuspended"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Administrator role required, or the target account is protected",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "User not found",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description = "Invalid user status transition, or the actor is the target",
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
    @MalformedBodyErrorResponses
    @AuthenticationRequiredResponse
    @PatchMapping(ApiConstants.Admin.UNSUSPEND_USER)
    ResponseEntity<ApiResponse<AdminActionResponse>> unsuspendUser(
            @PathVariable("userId") UUID userId, @Valid @RequestBody AdminActionRequest request);

    /** Removes a post and returns the persisted audit event. */
    @Operation(
            summary = "Remove a post",
            description =
                    "Performs the same side effects as an owner removal: the post is soft-deleted,"
                            + " its hashtag associations are detached, and its search-index document is"
                            + " deleted. The status it held is recorded so restore can return it"
                            + " there.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Post removed"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Moderator or administrator role required",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "Post or linked report not found",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description = "Invalid post status transition",
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
    @MalformedBodyErrorResponses
    @AuthenticationRequiredResponse
    @PatchMapping(ApiConstants.Admin.REMOVE_POST)
    ResponseEntity<ApiResponse<AdminActionResponse>> removePost(
            @PathVariable("postId") UUID postId, @Valid @RequestBody AdminActionRequest request);

    /** Restores a removed post to its pre-removal status and reports what the restore dropped. */
    @Operation(
            summary = "Restore a post",
            description =
                    "Returns the post to the status it held before the moderation removal, which is"
                            + " not necessarily published: a post that was a draft when it was removed"
                            + " comes back a draft. A post removed before that status was recorded"
                            + " comes back published. The resulting status is reported in the audit"
                            + " event's metadata as resultingStatus. Hashtag associations are"
                            + " re-derived and the search index is refreshed only when the post comes"
                            + " back published. Restore is the one write path that strips a banned"
                            + " hashtag instead of refusing, so the post can come back carrying"
                            + " fewer tags than its caption names; remainingBannedHashtags names"
                            + " the banned ones the caption still holds. That list is the post's"
                            + " state after the restore rather than the set this call changed, so"
                            + " restoring the same post twice returns the same names both times."
                            + " Moderator or administrator role required.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Post restored"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Moderator or administrator role required",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "Post or linked report not found",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description = "Invalid post status transition",
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
    @MalformedBodyErrorResponses
    @AuthenticationRequiredResponse
    @PatchMapping(ApiConstants.Admin.RESTORE_POST)
    ResponseEntity<ApiResponse<AdminPostRestoreResponse>> restorePost(
            @PathVariable("postId") UUID postId, @Valid @RequestBody AdminActionRequest request);

    /** Removes a comment and returns the persisted audit event. */
    @Operation(summary = "Remove a comment")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Comment removed"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Moderator or administrator role required",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "Comment or linked report not found",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description = "Invalid comment status transition",
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
    @MalformedBodyErrorResponses
    @AuthenticationRequiredResponse
    @PatchMapping(ApiConstants.Admin.REMOVE_COMMENT)
    ResponseEntity<ApiResponse<AdminActionResponse>> removeComment(
            @PathVariable("commentId") UUID commentId,
            @Valid @RequestBody AdminActionRequest request);

    /** Restores a removed comment and returns the persisted audit event. */
    @Operation(summary = "Restore a comment")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Comment restored"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Moderator or administrator role required",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "Comment or linked report not found",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description = "Invalid comment status transition",
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
    @MalformedBodyErrorResponses
    @AuthenticationRequiredResponse
    @PatchMapping(ApiConstants.Admin.RESTORE_COMMENT)
    ResponseEntity<ApiResponse<AdminActionResponse>> restoreComment(
            @PathVariable("commentId") UUID commentId,
            @Valid @RequestBody AdminActionRequest request);

    /** Removes a story and returns the persisted audit event. */
    @Operation(
            summary = "Removes a story and returns the persisted audit event",
            description =
                    "Takes a reported story down. The story stops appearing in its owner's profile"
                            + " and in every viewer's story feed immediately. Expiry is untouched,"
                            + " so a story removed close to its twenty-fourth hour may expire while"
                            + " removed; once a story is both removed and expired the cleanup job"
                            + " hard-deletes it and it can no longer be restored.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Story removed"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Moderator or administrator role required",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "Story or linked report not found",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description = "Story is already removed",
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
    @MalformedBodyErrorResponses
    @AuthenticationRequiredResponse
    @PatchMapping(ApiConstants.Admin.REMOVE_STORY)
    ResponseEntity<ApiResponse<AdminActionResponse>> removeStory(
            @PathVariable("storyId") UUID storyId, @Valid @RequestBody AdminActionRequest request);

    /** Restores a removed story and returns the persisted audit event. */
    @Operation(
            summary = "Restores a removed story and returns the persisted audit event",
            description =
                    "Reverses a story removal. Expiry continues to decide visibility, so a story"
                            + " that expired while it was removed comes back to a live row that no"
                            + " feed will show; this is deliberate, because a restore must not"
                            + " resurrect content past its lifetime. A story the cleanup job has"
                            + " already hard-deleted answers not-found.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Story restored"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Moderator or administrator role required",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "Story or linked report not found",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description = "Story is not removed",
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
    @MalformedBodyErrorResponses
    @AuthenticationRequiredResponse
    @PatchMapping(ApiConstants.Admin.RESTORE_STORY)
    ResponseEntity<ApiResponse<AdminActionResponse>> restoreStory(
            @PathVariable("storyId") UUID storyId, @Valid @RequestBody AdminActionRequest request);

    /** Removes a message and returns the persisted audit event. */
    @Operation(
            summary = "Removes a message and returns the persisted audit event",
            description =
                    "Takes a reported message down for both participants. Its text, media and any"
                            + " shared post or story stop being served, and the thread shows the"
                            + " same \"message deleted\" placeholder it already shows when a sender"
                            + " deletes their own message. The row keeps its payload so a restore"
                            + " can return it, and a deletion the sender performed is left"
                            + " untouched.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Message removed"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Moderator or administrator role required",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "Message or linked report not found",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description = "Message is already removed",
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
    @MalformedBodyErrorResponses
    @AuthenticationRequiredResponse
    @PatchMapping(ApiConstants.Admin.REMOVE_MESSAGE)
    ResponseEntity<ApiResponse<AdminActionResponse>> removeMessage(
            @PathVariable("messageId") UUID messageId,
            @Valid @RequestBody AdminActionRequest request);

    /** Restores a removed message and returns the persisted audit event. */
    @Operation(
            summary = "Restores a removed message and returns the persisted audit event",
            description =
                    "Reverses a message removal, returning the message's text, media and shares to"
                            + " both participants. A message the sender had also deleted stays"
                            + " deleted: a restore corrects a moderation decision, not the"
                            + " sender's.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Message restored"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Moderator or administrator role required",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "Message or linked report not found",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description = "Message is not removed",
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
    @MalformedBodyErrorResponses
    @AuthenticationRequiredResponse
    @PatchMapping(ApiConstants.Admin.RESTORE_MESSAGE)
    ResponseEntity<ApiResponse<AdminActionResponse>> restoreMessage(
            @PathVariable("messageId") UUID messageId,
            @Valid @RequestBody AdminActionRequest request);

    /** Resolves a report and returns the persisted audit event. */
    @Operation(summary = "Resolve a report")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Report resolved"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Moderator or administrator role required",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "Report not found",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description = "Report is already terminal",
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
    @MalformedBodyErrorResponses
    @AuthenticationRequiredResponse
    @PatchMapping(ApiConstants.Admin.RESOLVE_REPORT)
    ResponseEntity<ApiResponse<AdminActionResponse>> resolveReport(
            @PathVariable("reportId") UUID reportId,
            @Valid @RequestBody AdminActionRequest request);

    /** Dismisses a report and returns the persisted audit event. */
    @Operation(summary = "Dismiss a report")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Report dismissed"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Moderator or administrator role required",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "Report not found",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description = "Report is already terminal",
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
    @MalformedBodyErrorResponses
    @AuthenticationRequiredResponse
    @PatchMapping(ApiConstants.Admin.DISMISS_REPORT)
    ResponseEntity<ApiResponse<AdminActionResponse>> dismissReport(
            @PathVariable("reportId") UUID reportId,
            @Valid @RequestBody AdminActionRequest request);

    /** Hands a report up to an administrator and returns the persisted audit event. */
    @Operation(
            summary = "Escalate a report",
            description =
                    "Moves an open report out of the moderator queue and into the administrator's."
                            + " The escalating moderator can still read it, but only an administrator"
                            + " may resolve or dismiss it, and there is no transition back to pending"
                            + " or reviewing. No notification is pushed: the escalated count endpoint"
                            + " is the only signal that one is waiting. Requires MODERATOR or ADMIN.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Report escalated"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Moderator or administrator role required",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "Report not found",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description = "Report is already closed or already escalated",
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
    @MalformedBodyErrorResponses
    @AuthenticationRequiredResponse
    @PatchMapping(ApiConstants.Admin.ESCALATE_REPORT)
    ResponseEntity<ApiResponse<AdminActionResponse>> escalateReport(
            @PathVariable("reportId") UUID reportId,
            @Valid @RequestBody AdminEscalateReportRequest request);

    /** Counts the reports waiting on an administrator. */
    @Operation(
            summary = "Count escalated reports",
            description =
                    "Returns how many reports are in the escalated state. Escalation pushes no"
                            + " notification, so this is the only signal that one is waiting; a"
                            + " dashboard that does not surface it makes escalation a black hole."
                            + " Requires ADMIN.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Escalated report count"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Administrator role required",
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
    @GetMapping(ApiConstants.Admin.ESCALATED_REPORT_COUNT)
    ResponseEntity<ApiResponse<EscalatedReportCountResponse>> countEscalatedReports();

    /** Returns the reported entity, rendered for moderation review. */
    @Operation(
            summary = "Review a report's target",
            description =
                    "Returns the entity the report points at, discriminated by report type, so a"
                            + " moderator can see what was reported. Deliberately bypasses the ordinary"
                            + " visibility rules: a private account's post, or one by someone who has"
                            + " blocked the reviewing moderator, is exactly what has to be reviewable"
                            + " once it is reported. The report is the anchor and the only way in, so a"
                            + " moderator sees what somebody flagged and nothing else. The response is"
                            + " not cacheable and the read is logged rather than written to the audit"
                            + " log. Requires MODERATOR or ADMIN.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Reported entity returned"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Moderator or administrator role required",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "Report not found",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "410",
                description = "The reported entity has since been deleted",
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
    @GetMapping(ApiConstants.Admin.REPORT_TARGET)
    ResponseEntity<ApiResponse<AdminReportTargetResponse>> getReportTarget(
            @PathVariable("reportId") UUID reportId);

    /** Lists audit-event summaries with optional actor and action-type filters. */
    @Operation(
            summary = "List moderation audit events",
            description =
                    "An administrator sees every audit row. A moderator sees only the rows it"
                            + " authored, whatever adminId filter it supplies. The list is read from"
                            + " an analytics replica that trails a committed action by a few"
                            + " seconds, and from the transactional database when the replica is"
                            + " unavailable; the detail endpoint always reads the source of truth.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Audit event page returned"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Moderator or administrator role required",
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
    @AuthenticationRequiredResponse
    @GetMapping(ApiConstants.Admin.ACTIONS)
    ResponseEntity<ApiResponse<CursorPageResponse<AdminActionSummaryResponse>>> getActions(
            @RequestParam(required = false) UUID adminId,
            @RequestParam(required = false) AdminActionType actionType,
            @Parameter(description = "Account the action was taken against")
                    @RequestParam(required = false)
                    UUID targetUserId,
            @Parameter(
                            description =
                                    "Inclusive lower bound on when the action was recorded, ISO"
                                            + " 8601")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    OffsetDateTime from,
            @Parameter(
                            description =
                                    "Exclusive upper bound on when the action was recorded, ISO"
                                            + " 8601. The window is half-open, so two adjacent"
                                            + " windows partition the log with no row counted twice"
                                            + " and none skipped.")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    OffsetDateTime to,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit);

    /** Returns one audit event by identifier. */
    @Operation(
            summary = "Get a moderation audit event",
            description =
                    "A moderator may read only a row it authored. A row authored by anyone else is"
                            + " reported as not found rather than forbidden, so the response does"
                            + " not confirm that a row the caller may not read exists.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Audit event returned"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Moderator or administrator role required",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "Audit event not found",
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
    @GetMapping(ApiConstants.Admin.ACTION_BY_ID)
    ResponseEntity<ApiResponse<AdminActionResponse>> getActionById(
            @PathVariable("actionId") UUID actionId);

    /** Lists audit-event summaries for one affected user. */
    @Operation(
            summary = "List moderation audit events for a user",
            description =
                    "An administrator sees every audit row against the user. A moderator sees only"
                            + " the rows it authored against them.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "User audit event page returned"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Moderator or administrator role required",
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
    @AuthenticationRequiredResponse
    @GetMapping(ApiConstants.Admin.ACTIONS_FOR_USER)
    ResponseEntity<ApiResponse<CursorPageResponse<AdminActionSummaryResponse>>> getActionsForUser(
            @PathVariable("userId") UUID userId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit);
}
