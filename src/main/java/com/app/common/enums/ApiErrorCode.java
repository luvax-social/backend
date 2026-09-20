package com.app.common.enums;

import org.springframework.http.HttpStatus;

import lombok.Getter;

/** Machine-readable error codes for all API failure responses. */
@Getter
public enum ApiErrorCode {
    // spotless:off

    // Common
    VALIDATION_ERROR("VALIDATION_ERROR", "Request validation failed", HttpStatus.BAD_REQUEST),
    BAD_REQUEST("BAD_REQUEST", "Invalid request", HttpStatus.BAD_REQUEST),
    INVALID_CURSOR("INVALID_CURSOR", "Malformed pagination cursor", HttpStatus.BAD_REQUEST),
    // Distinct from INVALID_CURSOR: the cursor is well formed, the requested depth is simply
    // past what either storage tier will serve. Reported explicitly rather than left to fail as
    // an Elasticsearch result-window error, which the availability classifier cannot tell from a
    // genuine outage and would therefore charge to a shared circuit breaker.
    PAGINATION_DEPTH_EXCEEDED("PAGINATION_DEPTH_EXCEEDED", "Requested pagination depth is beyond the maximum this endpoint serves", HttpStatus.BAD_REQUEST),
    MALFORMED_REQUEST_BODY("MALFORMED_REQUEST_BODY", "Request body could not be read", HttpStatus.BAD_REQUEST),
    MISSING_REQUIRED_PARAMETER("MISSING_REQUIRED_PARAMETER", "A required request parameter is missing", HttpStatus.BAD_REQUEST),
    METHOD_NOT_ALLOWED("METHOD_NOT_ALLOWED", "This HTTP method is not supported for this route", HttpStatus.METHOD_NOT_ALLOWED),
    UNSUPPORTED_MEDIA_TYPE("UNSUPPORTED_MEDIA_TYPE", "Content-Type is not supported", HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    NOT_ACCEPTABLE("NOT_ACCEPTABLE", "None of the Accept header's media types are supported", HttpStatus.NOT_ACCEPTABLE),
    NOT_FOUND("NOT_FOUND", "Requested resource was not found", HttpStatus.NOT_FOUND),
    FORBIDDEN("FORBIDDEN", "Access to this resource is forbidden", HttpStatus.FORBIDDEN),
    UNAUTHORIZED("UNAUTHORIZED", "Authentication is required", HttpStatus.UNAUTHORIZED),
    INTERNAL_ERROR("INTERNAL_ERROR", "An unexpected error occurred", HttpStatus.INTERNAL_SERVER_ERROR),
    // Raised only when the caller exceeds their own per-endpoint quota, never for server load,
    // so the message must not attribute it to the system being busy.
    TOO_MANY_REQUESTS("TOO_MANY_REQUESTS", "Too many requests. Please wait before trying again.", HttpStatus.TOO_MANY_REQUESTS),
    SERVICE_UNAVAILABLE("SERVICE_UNAVAILABLE", "External service temporarily unavailable. Please try again later.", HttpStatus.SERVICE_UNAVAILABLE),
    // The provider refused the message itself, not the connection: a malformed or undeliverable
    // recipient, a rejected payload. Retrying reproduces the same rejection for ever, so consumers
    // treat this as permanent and dead-letter on the first attempt rather than burning the ladder.
    MAIL_PERMANENTLY_REJECTED("MAIL_PERMANENTLY_REJECTED", "The mail provider permanently rejected this message. It will not be retried.", HttpStatus.UNPROCESSABLE_ENTITY),
    // This deployment is configured not to send to the recipient's domain. Permanent by nature:
    // the address will not become allowed by retrying, so the message is recorded and dropped.
    MAIL_RECIPIENT_NOT_ALLOWED("MAIL_RECIPIENT_NOT_ALLOWED", "This deployment is not permitted to send mail to that recipient domain.", HttpStatus.UNPROCESSABLE_ENTITY),

    // Auth
    AUTH_INVALID_CREDENTIALS("AUTH_INVALID_CREDENTIALS", "Invalid email or password", HttpStatus.UNAUTHORIZED),
    AUTH_TOKEN_EXPIRED("AUTH_TOKEN_EXPIRED", "Access token has expired", HttpStatus.UNAUTHORIZED),
    AUTH_TOKEN_INVALID("AUTH_TOKEN_INVALID", "Invalid access token", HttpStatus.UNAUTHORIZED),
    AUTH_REFRESH_TOKEN_EXPIRED("AUTH_REFRESH_TOKEN_EXPIRED", "Refresh token has expired", HttpStatus.UNAUTHORIZED),
    AUTH_REFRESH_TOKEN_INVALID("AUTH_REFRESH_TOKEN_INVALID", "Invalid or revoked refresh token", HttpStatus.UNAUTHORIZED),
    // The three account states below are the only conditions that produce a 403 from
    // UserStateValidator, and each message names the state that produced it. AUTH_ACCOUNT_LOCKED
    // previously read "temporarily locked due to too many failed attempts", which was false on
    // every count: it is raised only for a permanent ban, and no failed-attempt lockout exists.
    AUTH_ACCOUNT_LOCKED("AUTH_ACCOUNT_LOCKED", "This account has been banned", HttpStatus.FORBIDDEN),
    AUTH_ACCOUNT_INACTIVE("AUTH_ACCOUNT_INACTIVE", "This account is suspended or deactivated", HttpStatus.FORBIDDEN),
    AUTH_EMAIL_NOT_VERIFIED("AUTH_EMAIL_NOT_VERIFIED", "This account's email address has not been verified", HttpStatus.FORBIDDEN),
    AUTH_PASSWORD_MISMATCH("AUTH_PASSWORD_MISMATCH", "Current password is incorrect", HttpStatus.BAD_REQUEST),
    AUTH_RESET_TOKEN_INVALID("AUTH_RESET_TOKEN_INVALID", "Invalid or expired reset token", HttpStatus.BAD_REQUEST),
    AUTH_RESET_TOKEN_EXPIRED("AUTH_RESET_TOKEN_EXPIRED", "Reset token has expired", HttpStatus.GONE),
    AUTH_RESET_TOKEN_USED("AUTH_RESET_TOKEN_USED", "Reset token has already been used", HttpStatus.CONFLICT),
    AUTH_VERIFY_TOKEN_INVALID("AUTH_VERIFY_TOKEN_INVALID", "Email verification token is invalid or has expired", HttpStatus.BAD_REQUEST),
    AUTH_OAUTH2_EXCHANGE_CODE_INVALID("AUTH_OAUTH2_EXCHANGE_CODE_INVALID", "OAuth2 exchange code is invalid or has expired", HttpStatus.BAD_REQUEST),
    // Raised by every surface behind AuthTurnstileGuard, the report submission included, so there
    // is one captcha shape for the whole fail-open group rather than one per endpoint. Deliberately
    // says nothing about which failure occurred: distinguishing a bad token from an expired or
    // replayed one tells an attacker which of the three to fix.
    AUTH_CAPTCHA_FAILED("AUTH_CAPTCHA_FAILED", "The verification challenge was not accepted", HttpStatus.BAD_REQUEST),

    // Users
    USER_ALREADY_EXISTS("USER_ALREADY_EXISTS", "An account with these details already exists", HttpStatus.CONFLICT),
    USER_EMAIL_ALREADY_EXISTS("USER_EMAIL_ALREADY_EXISTS", "An account with this email already exists", HttpStatus.CONFLICT),
    USER_USERNAME_ALREADY_EXISTS("USER_USERNAME_ALREADY_EXISTS", "Username is already taken", HttpStatus.CONFLICT),
    USER_NOT_FOUND("USER_NOT_FOUND", "User not found", HttpStatus.NOT_FOUND),

    // Social
    SOCIAL_SELF_FOLLOW("SOCIAL_SELF_FOLLOW", "You cannot follow yourself", HttpStatus.BAD_REQUEST),
    SOCIAL_ALREADY_FOLLOWING("SOCIAL_ALREADY_FOLLOWING", "You are already following this user", HttpStatus.CONFLICT),
    SOCIAL_ALREADY_REQUESTED("SOCIAL_ALREADY_REQUESTED", "Follow request already sent", HttpStatus.CONFLICT),
    SOCIAL_REQUEST_NOT_FOUND("SOCIAL_REQUEST_NOT_FOUND", "Follow request not found", HttpStatus.NOT_FOUND),
    SOCIAL_SELF_BLOCK("SOCIAL_SELF_BLOCK", "You cannot block yourself", HttpStatus.BAD_REQUEST),
    SOCIAL_ALREADY_BLOCKED("SOCIAL_ALREADY_BLOCKED", "User already blocked", HttpStatus.CONFLICT),

    // Media
    MEDIA_INVALID_METADATA("MEDIA_INVALID_METADATA", "Media metadata is invalid", HttpStatus.BAD_REQUEST),
    MEDIA_STORAGE_KEY_ALREADY_EXISTS("MEDIA_STORAGE_KEY_ALREADY_EXISTS", "Media storage key already exists", HttpStatus.CONFLICT),
    MEDIA_CDN_NOT_CONFIGURED("MEDIA_CDN_NOT_CONFIGURED", "Media CDN is not configured", HttpStatus.SERVICE_UNAVAILABLE),
    MEDIA_STORAGE_NOT_CONFIGURED("MEDIA_STORAGE_NOT_CONFIGURED", "Media object storage is not configured", HttpStatus.SERVICE_UNAVAILABLE),
    MEDIA_UPLOAD_URL_FAILED("MEDIA_UPLOAD_URL_FAILED", "Media upload URL could not be generated", HttpStatus.SERVICE_UNAVAILABLE),
    MEDIA_OBJECT_NOT_UPLOADED("MEDIA_OBJECT_NOT_UPLOADED", "No uploaded object exists for this storage key", HttpStatus.UNPROCESSABLE_ENTITY),
    MEDIA_OBJECT_METADATA_MISMATCH("MEDIA_OBJECT_METADATA_MISMATCH", "Submitted metadata does not match the uploaded object", HttpStatus.UNPROCESSABLE_ENTITY),
    MEDIA_STORAGE_UNAVAILABLE("MEDIA_STORAGE_UNAVAILABLE", "Media object storage is temporarily unavailable. Please try again later.", HttpStatus.SERVICE_UNAVAILABLE),

    // Hashtag
    HASHTAG_NOT_FOUND("HASHTAG_NOT_FOUND", "Hashtag not found", HttpStatus.NOT_FOUND),
    HASHTAG_ALREADY_EXISTS("HASHTAG_ALREADY_EXISTS", "Hashtag already exists", HttpStatus.CONFLICT),
    // Distinct from HASHTAG_NOT_FOUND, which means no row carries the name or id at all. This one
    // means the row exists but an administrator took the term out of circulation, so a client can
    // word "this hashtag is not available" differently from "this hashtag has no posts yet".
    // Returning an empty page instead would make those two states indistinguishable.
    HASHTAG_UNAVAILABLE("HASHTAG_UNAVAILABLE", "Hashtag is no longer available", HttpStatus.NOT_FOUND),

    // Post
    POST_NOT_FOUND("POST_NOT_FOUND", "Post not found", HttpStatus.NOT_FOUND),
    POST_FORBIDDEN("POST_FORBIDDEN", "You do not have access to this post", HttpStatus.FORBIDDEN),
    POST_ALREADY_LIKED("POST_ALREADY_LIKED", "Post already liked", HttpStatus.CONFLICT),
    POST_ALREADY_SAVED("POST_ALREADY_SAVED", "Post already saved", HttpStatus.CONFLICT),
    // 422 rather than 400: the caption is syntactically fine and the request is well formed, but a
    // tag it names is one an administrator has taken out of circulation. The response body carries
    // the offending names under data.bannedTags, in normalized form, so a client can highlight them
    // in the caption instead of making the author guess which tag was refused.
    POST_BANNED_HASHTAG("POST_BANNED_HASHTAG", "Caption contains banned hashtags", HttpStatus.UNPROCESSABLE_ENTITY),

    // Comment
    COMMENT_NOT_FOUND("COMMENT_NOT_FOUND", "Comment not found", HttpStatus.NOT_FOUND),
    COMMENT_FORBIDDEN("COMMENT_FORBIDDEN", "You do not have permission to perform this action on the comment", HttpStatus.FORBIDDEN),
    COMMENT_MODERATION_REJECTED("COMMENT_MODERATION_REJECTED", "Comment content was rejected by moderation", HttpStatus.UNPROCESSABLE_ENTITY),
    COMMENT_DEPTH_EXCEEDED("COMMENT_DEPTH_EXCEEDED", "Maximum comment nesting depth exceeded", HttpStatus.BAD_REQUEST),
    COMMENT_ALREADY_LIKED("COMMENT_ALREADY_LIKED", "Comment already liked", HttpStatus.CONFLICT),
    COMMENT_NOT_LIKED("COMMENT_NOT_LIKED", "Comment has not been liked", HttpStatus.CONFLICT),
    COMMENT_SLOW_MODE_ACTIVE("COMMENT_SLOW_MODE_ACTIVE", "Slow mode is active. Please wait before commenting again.", HttpStatus.TOO_MANY_REQUESTS),
    COMMENT_IDEMPOTENCY_CONFLICT("COMMENT_IDEMPOTENCY_CONFLICT", "Idempotency key reused with different request payload", HttpStatus.CONFLICT),
    POST_COMMENTING_RESTRICTED("POST_COMMENTING_RESTRICTED", "You do not have access to comment on this post", HttpStatus.FORBIDDEN),

    // Story
    STORY_NOT_FOUND("STORY_NOT_FOUND", "Story not found", HttpStatus.NOT_FOUND),
    STORY_FORBIDDEN("STORY_FORBIDDEN", "You do not have access to this story", HttpStatus.FORBIDDEN),
    STORY_ALREADY_LIKED("STORY_ALREADY_LIKED", "Story already liked", HttpStatus.CONFLICT),

    // Message
    CONVERSATION_NOT_FOUND("CONVERSATION_NOT_FOUND", "Conversation not found", HttpStatus.NOT_FOUND),
    CONVERSATION_FORBIDDEN("CONVERSATION_FORBIDDEN", "You are not a participant of this conversation", HttpStatus.FORBIDDEN),
    CONVERSATION_INVALID_PARTICIPANTS("CONVERSATION_INVALID_PARTICIPANTS", "Invalid participant list", HttpStatus.BAD_REQUEST),
    PARTICIPANT_NOT_FOUND("PARTICIPANT_NOT_FOUND", "Participant not found in this conversation", HttpStatus.NOT_FOUND),
    MESSAGE_REQUEST_NOT_ALLOWED("MESSAGE_REQUEST_NOT_ALLOWED", "This user is not accepting message requests", HttpStatus.FORBIDDEN),
    MESSAGE_NOT_FOUND("MESSAGE_NOT_FOUND", "Message not found", HttpStatus.NOT_FOUND),
    MESSAGE_FORBIDDEN("MESSAGE_FORBIDDEN", "You do not have access to this message", HttpStatus.FORBIDDEN),
    MESSAGE_INVALID_PAYLOAD("MESSAGE_INVALID_PAYLOAD", "Message payload does not match its type", HttpStatus.BAD_REQUEST),
    MESSAGE_IDEMPOTENCY_CONFLICT("MESSAGE_IDEMPOTENCY_CONFLICT", "Idempotency key reused with a different payload", HttpStatus.CONFLICT),

    // Report
    REPORT_NOT_FOUND("REPORT_NOT_FOUND", "Report not found", HttpStatus.NOT_FOUND),
    REPORT_TARGET_NOT_FOUND("REPORT_TARGET_NOT_FOUND", "Reported entity not found", HttpStatus.NOT_FOUND),
    REPORT_DUPLICATE("REPORT_DUPLICATE", "You have already reported this entity", HttpStatus.CONFLICT),
    REPORT_SELF_NOT_ALLOWED("REPORT_SELF_NOT_ALLOWED", "You cannot report your own content", HttpStatus.BAD_REQUEST),
    REPORT_INVALID_TRANSITION("REPORT_INVALID_TRANSITION", "Invalid report status transition", HttpStatus.CONFLICT),
    // Distinct from REPORT_TARGET_NOT_FOUND, which is raised when a user submits a report
    // against something that never existed. This one means the report is real and the
    // entity it points at has since been hard-deleted: entity_id carries no foreign key, so
    // that leaves the report pointing at nothing.
    REPORT_TARGET_GONE("REPORT_TARGET_GONE", "The reported entity no longer exists", HttpStatus.GONE),

    // Admin
    ADMIN_ACTION_NOT_FOUND("ADMIN_ACTION_NOT_FOUND", "Admin action not found", HttpStatus.NOT_FOUND),
    ADMIN_INVALID_ACTION("ADMIN_INVALID_ACTION", "Action is not valid for this target", HttpStatus.BAD_REQUEST),
    ADMIN_INVALID_TRANSITION("ADMIN_INVALID_TRANSITION", "Target is already in the requested moderation state", HttpStatus.CONFLICT),
    ADMIN_SELF_ACTION_NOT_ALLOWED("ADMIN_SELF_ACTION_NOT_ALLOWED", "You cannot apply a moderation action to your own account", HttpStatus.CONFLICT),
    // Only an ordinary account can be warned. Three warnings produce a strike and a strike changes
    // users.status, so a warnable moderator or administrator would hand any moderator a route to
    // an administrator's account status, which no endpoint grants directly.
    ADMIN_TARGET_NOT_WARNABLE("ADMIN_TARGET_NOT_WARNABLE", "Only an ordinary account can be warned", HttpStatus.FORBIDDEN),
    WARNING_NOT_FOUND("WARNING_NOT_FOUND", "Warning not found", HttpStatus.NOT_FOUND),
    STRIKE_NOT_FOUND("STRIKE_NOT_FOUND", "Strike not found", HttpStatus.NOT_FOUND),
    // Covers an unknown reason key and a disabled one alike. Splitting them would let a caller
    // enumerate which reasons exist but are currently switched off.
    WARNING_REASON_DISABLED("WARNING_REASON_DISABLED", "That reason is not available", HttpStatus.UNPROCESSABLE_ENTITY),
    // No API caller may change an administrator's account status. Removing a rogue administrator is
    // deliberately a database-level operation: an in-application lockout of the whole administrator
    // tier has no recovery path, whereas an escalation requiring database access does.
    ADMIN_TARGET_PROTECTED("ADMIN_TARGET_PROTECTED", "This account is protected and cannot be changed through the API", HttpStatus.FORBIDDEN),
    // Covers every rejected role transition: a skip-level promotion, an administrator target, and a
    // no-op. The three are one class of error to the caller - the requested transition is not one
    // the policy permits - and splitting them would let a caller map out the matrix by probing.
    // Named NOT_ALLOWED rather than FORBIDDEN because it answers 409: every other *_FORBIDDEN
    // constant here maps to 403, and one that did not would make the naming stop predicting the
    // status. The status is right as it is - the caller has the authority, the transition is the
    // problem - so the name moved rather than the code.
    ADMIN_ROLE_TRANSITION_NOT_ALLOWED("ADMIN_ROLE_TRANSITION_NOT_ALLOWED", "The requested role transition is not permitted", HttpStatus.CONFLICT),

    // Support
    SUPPORT_TICKET_NOT_FOUND("SUPPORT_TICKET_NOT_FOUND", "Support ticket not found", HttpStatus.NOT_FOUND),
    SUPPORT_TICKET_ALREADY_OPEN("SUPPORT_TICKET_ALREADY_OPEN", "You already have an open support ticket", HttpStatus.CONFLICT),
    SUPPORT_TICKET_INVALID_TRANSITION("SUPPORT_TICKET_INVALID_TRANSITION", "The ticket cannot move to the requested state", HttpStatus.CONFLICT),
    SUPPORT_TICKET_ALREADY_CLAIMED("SUPPORT_TICKET_ALREADY_CLAIMED", "Another staff member has already claimed this ticket", HttpStatus.CONFLICT),
    SUPPORT_TICKET_NOT_CLAIMED("SUPPORT_TICKET_NOT_CLAIMED", "Claim this ticket before acting on it", HttpStatus.CONFLICT),
    // A moderator may read an appeal and may escalate it, but may not answer or close one. Unban,
    // unsuspend, revoke_warning and revoke_strike are all administrator-only, so a moderator
    // closing an appeal would be issuing a verdict they have no capability to carry out.
    SUPPORT_APPEAL_REQUIRES_ADMIN("SUPPORT_APPEAL_REQUIRES_ADMIN", "Only an administrator can decide an appeal", HttpStatus.FORBIDDEN),
    // Distinct from a plain 403 so the client can explain the refusal rather than showing a generic
    // permission error for something the staff member could otherwise do.
    SUPPORT_CONFLICT_OF_INTEREST("SUPPORT_CONFLICT_OF_INTEREST", "You cannot act on a ticket appealing a decision you made", HttpStatus.FORBIDDEN),
    SUPPORT_CATEGORY_NOT_PUBLIC("SUPPORT_CATEGORY_NOT_PUBLIC", "This category cannot be used on the public form", HttpStatus.BAD_REQUEST),
    SUPPORT_TOKEN_INVALID("SUPPORT_TOKEN_INVALID", "This link is invalid or has already been used", HttpStatus.BAD_REQUEST),
    SUPPORT_CAPTCHA_FAILED("SUPPORT_CAPTCHA_FAILED", "The verification challenge was not accepted", HttpStatus.BAD_REQUEST),
    SUPPORT_DAILY_LIMIT_REACHED("SUPPORT_DAILY_LIMIT_REACHED", "Too many support requests from this address today", HttpStatus.TOO_MANY_REQUESTS),
    // One decision, one appeal. A second ticket against the same audit row would give the same
    // decision two independent verdicts, and a rejected appeal could be reopened indefinitely by
    // filing again. Distinct from SUPPORT_TICKET_ALREADY_OPEN, which is about the account's
    // ticket lane rather than about this decision, and which a terminal first appeal would not
    // trip.
    SUPPORT_APPEAL_ALREADY_FILED("SUPPORT_APPEAL_ALREADY_FILED", "You have already appealed this decision", HttpStatus.CONFLICT),
    // Deliberately the same NOT_FOUND answer an unknown id gets. An audit row belonging to
    // somebody else and an audit row that does not exist must be indistinguishable, or the
    // endpoint becomes an oracle for whether a given id names a real moderation decision.
    SUPPORT_APPEAL_ACTION_NOT_FOUND("SUPPORT_APPEAL_ACTION_NOT_FOUND", "No appealable decision was found", HttpStatus.NOT_FOUND),
    // A moderation action outside the appealable set: the two reinstating actions, the two support
    // ticket notices, and every administrative action that targets no user.
    SUPPORT_APPEAL_NOT_APPEALABLE("SUPPORT_APPEAL_NOT_APPEALABLE", "This decision cannot be appealed", HttpStatus.BAD_REQUEST),

    // Verification
    // Its own code rather than a generic validation failure, because the three-field rule is a
    // policy the client has to explain before submission rather than a malformed-input error.
    VERIFICATION_INSUFFICIENT_EVIDENCE("VERIFICATION_INSUFFICIENT_EVIDENCE", "Fill at least three evidence fields before submitting", HttpStatus.UNPROCESSABLE_ENTITY),
    VERIFICATION_CATEGORY_NOT_FOUND("VERIFICATION_CATEGORY_NOT_FOUND", "Verification category not found", HttpStatus.NOT_FOUND),
    VERIFICATION_ALREADY_VERIFIED("VERIFICATION_ALREADY_VERIFIED", "This account already holds a verified badge", HttpStatus.CONFLICT),
    VERIFICATION_NOT_ACTIVE("VERIFICATION_NOT_ACTIVE", "This account holds no verified badge to revoke", HttpStatus.CONFLICT),
    VERIFICATION_REQUEST_NOT_FOUND("VERIFICATION_REQUEST_NOT_FOUND", "Verification request not found", HttpStatus.NOT_FOUND);

    // spotless:on

    private final String code;
    private final String defaultMessage;
    private final HttpStatus httpStatus;

    ApiErrorCode(String code, String defaultMessage, HttpStatus httpStatus) {
        this.code = code;
        this.defaultMessage = defaultMessage;
        this.httpStatus = httpStatus;
    }
}
