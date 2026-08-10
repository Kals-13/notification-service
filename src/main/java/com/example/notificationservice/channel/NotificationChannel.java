package com.example.notificationservice.channel;

import com.example.notificationservice.domain.NotificationJob;

public interface NotificationChannel {

    enum ChannelType {
        EMAIL,
        SMS,
        PUSH,
        INAPP
    }

    String getChannelName();

    /**
     * Sends notification via this channel with the rendered template body.
     */
    boolean send(NotificationJob job, String renderedBody);
}
