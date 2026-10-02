package com.web.service.notification;

import com.web.entity.Notification;

// Canal de salida de las notificaciones; la implementación actual es simulada (solo log)
public interface NotificationSender {

    void send(Notification.Channel channel, String recipient, String message);
}
