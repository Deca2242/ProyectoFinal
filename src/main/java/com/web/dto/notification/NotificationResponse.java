package com.web.dto.notification;

import com.web.entity.Notification;

import java.io.Serializable;
import java.time.LocalDateTime;

public record NotificationResponse(
        Long id,
        Long userId,
        Notification.Channel channel,
        Notification.NotificationType type,
        String recipient,
        String message,
        Long tripId,
        Long ticketId,
        Notification.NotificationStatus status,
        LocalDateTime createdAt
) implements Serializable {
}
