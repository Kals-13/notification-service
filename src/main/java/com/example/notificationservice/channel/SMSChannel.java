package com.example.notificationservice.channel;

import com.example.notificationservice.domain.NotificationJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class SMSChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(SMSChannel.class);

    @Override
    public String getChannelName() {
        return "SMS";
    }

    @Override
    public boolean send(NotificationJob job) {
        log.info("Sending SMS to {} from template {}", job.getRecipientPhone(), job.getTemplateId());
        // In real implementation, would call Twilio/AWS SNS
        return true;
    }
}
