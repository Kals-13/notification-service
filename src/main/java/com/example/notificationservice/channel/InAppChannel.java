package com.example.notificationservice.channel;

import com.example.notificationservice.domain.NotificationJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class InAppChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(InAppChannel.class);

    @Override
    public String getChannelName() {
        return "INAPP";
    }

    @Override
    public boolean send(NotificationJob job, String renderedBody) {
        log.info("Sending in-app notification from template {} with body: {}", job.getTemplateId(), renderedBody);
        // In real implementation, would store in database or cache
        return true;
    }
}
