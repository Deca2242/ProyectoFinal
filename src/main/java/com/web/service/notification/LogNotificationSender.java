package com.web.service.notification;

import com.web.entity.Notification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// Envío simulado (mock): no se conecta a ningún proveedor de WhatsApp/SMS, solo deja el mensaje en el log
@Slf4j
@Component
public class LogNotificationSender implements NotificationSender {

    @Override
    public void send(Notification.Channel channel, String recipient, String message) {
        log.info("[MOCK {}] Para {}: {}", channel, recipient, message);
    }
}
