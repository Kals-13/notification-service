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
    public boolean send(NotificationJob job, String renderedBody) {
        log.info("Sending push notifications from template {} with body: {}", job.getTemplateId(), renderedBody);
        // In real implementation, would call Firebase Cloud Messaging
        return true;
    }
}
