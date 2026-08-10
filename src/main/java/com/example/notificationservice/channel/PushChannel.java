package com.example.notificationservice.channel;

import com.example.notificationservice.domain.NotificationJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PushChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(PushChannel.class);

    @Override
    public String getChannelName() {
        return "PUSH";
    }

    @Override
    public boolean send(NotificationJob job) {
        log.info("Sending push notification from template {}", job.getTemplateId());
        // In real implementation, would call Firebase Cloud Messaging
        return true;
    }
}
