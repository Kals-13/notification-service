package com.example.notificationservice.exception;

public class ChannelNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ChannelNotFoundException(String message) {
        super(message);
    }
}
