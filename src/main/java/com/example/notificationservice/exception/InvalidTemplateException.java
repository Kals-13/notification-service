package com.example.notificationservice.exception;

public class InvalidTemplateException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidTemplateException(String message) {
        super(message);
    }

    public InvalidTemplateException(String message, Throwable cause) {
        super(message, cause);
    }
}
