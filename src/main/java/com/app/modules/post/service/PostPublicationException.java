package com.app.modules.post.service;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;

/**
 * A post cannot be published right now.
 *
 * <p>For an owner publishing by hand it is an ordinary refusal carrying the API error the client
 * acts on. For the scheduled publisher it is the signal to return the post to drafts, and {@link
 * #getReason()} is the code recorded with the failure notice.
 */
public class PostPublicationException extends AppException {

    /** Why publication was refused. */
    public enum Reason {
        BANNED_HASHTAG,
        PLACE_UNAVAILABLE,
        OWNER_UNAVAILABLE,
        MEDIA_UNAVAILABLE,
        INVALID_STATE
    }

    private final Reason reason;

    public PostPublicationException(Reason reason, ApiErrorCode errorCode) {
        super(errorCode);
        this.reason = reason;
    }

    public PostPublicationException(Reason reason, ApiErrorCode errorCode, Object details) {
        super(errorCode, details);
        this.reason = reason;
    }

    /**
     * Returns why publication was refused.
     *
     * @return the reason
     */
    public Reason getReason() {
        return reason;
    }
}
