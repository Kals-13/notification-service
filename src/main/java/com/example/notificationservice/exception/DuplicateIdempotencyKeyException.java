package com.example.notificationservice.exception;

public class DuplicateIdempotencyKeyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DuplicateIdempotencyKeyException(String message) {
        super(message);
    }
}
