package com.example.notificationservice.channel;

import com.example.notificationservice.domain.NotificationJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class EmailChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(EmailChannel.class);

    @Override
    public String getChannelName() {
        return "EMAIL";
    }

    @Override
    public boolean send(NotificationJob job, String renderedBody) {
        log.info("Sending email to {} with body: {}", job.getRecipientEmail(), renderedBody);
        // In real implementation, would call SMTP/SendGrid/AWS SES
        return true;
    }
}
