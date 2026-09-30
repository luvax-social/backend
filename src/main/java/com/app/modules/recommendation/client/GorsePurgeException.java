package com.app.modules.recommendation.client;

/** Raised when Gorse's store cannot be inspected or emptied. */
public class GorsePurgeException extends RuntimeException {

    public GorsePurgeException(String message, Throwable cause) {
        super(message, cause);
    }

    public GorsePurgeException(String message) {
        super(message);
    }
}
