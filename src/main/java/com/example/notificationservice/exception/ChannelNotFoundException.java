package com.example.notificationservice.exception;

public class ChannelNotFoundException extends RuntimeException {

    public ChannelNotFoundException(String channelName) {
        super("Channel not found: " + channelName);
    }
}
