package com.example.notificationservice.exception;

public class RateLimitExceededException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RateLimitExceededException(String message) {
        super(message);
    }

    public RateLimitExceededException(String message, Throwable cause) {
        super(message, cause);
    }
}
