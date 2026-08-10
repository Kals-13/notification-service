package com.example.notificationservice.channel;

import com.example.notificationservice.exception.ChannelNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ChannelFactory {

    private final Map<String, NotificationChannel> channelsByName;

    public ChannelFactory(Map<String, NotificationChannel> channels) {
        this.channelsByName = channels.values().stream()
                .collect(Collectors.toMap(
                        channel -> channel.getChannelName().toUpperCase(),
                        channel -> channel));
    }

    public NotificationChannel getChannel(String channelName) {
        NotificationChannel channel = channelsByName.get(channelName.toUpperCase());
        if (channel == null) {
            throw new ChannelNotFoundException("Channel not found: " + channelName);
        }
        return channel;
    }

    public NotificationChannel getChannel(NotificationChannel.ChannelType type) {
        return getChannel(type.name());
    }
}
