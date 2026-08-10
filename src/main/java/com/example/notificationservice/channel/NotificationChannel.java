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

    boolean send(NotificationJob job);
}
