package com.web.service.notification;

import com.web.entity.Notification;
import com.web.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// Envía una notificación y guarda su registro en una transacción PROPIA (REQUIRES_NEW): si el guardado falla
// (p. ej. DataIntegrityViolationException) solo se deshace esta transacción, nunca la de la operación de negocio.
// Bean separado de NotificationServiceImpl para que la llamada pase por el proxy transaccional
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationDelivery {

    private final NotificationRepository notificationRepository;
    private final NotificationSender notificationSender;

    // Un fallo del envío queda registrado como FAILED; un fallo del guardado se propaga a quien llama
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Notification deliver(Notification notification) {
        try {
            notificationSender.send(notification.getChannel(), notification.getRecipient(), notification.getMessage());
        } catch (RuntimeException e) {
            log.warn("Falló el envío {} a {}: {}", notification.getChannel(), notification.getRecipient(), e.getMessage());
            notification.setStatus(Notification.NotificationStatus.FAILED);
        }
        return notificationRepository.save(notification);
    }

    // Envío por el canal simulado sin dejar registro (teléfonos que no son de un usuario, p. ej. encomiendas);
    // devuelve si el envío tuvo éxito
    public boolean sendOnly(Notification.Channel channel, String recipient, String message) {
        try {
            notificationSender.send(channel, recipient, message);
            return true;
        } catch (RuntimeException e) {
            log.warn("Falló el envío {} a {}: {}", channel, recipient, e.getMessage());
            return false;
        }
    }

    // Solo registra (en transacción propia) un aviso ya enviado
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Notification record(Notification notification) {
        return notificationRepository.save(notification);
    }
}
