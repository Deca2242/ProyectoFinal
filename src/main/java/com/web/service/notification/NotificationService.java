package com.web.service.notification;

import com.web.dto.notification.NotificationResponse;
import com.web.entity.Ticket;
import com.web.entity.Trip;

import java.util.List;

// Notificaciones simuladas por WhatsApp/SMS. Ningún envío hace fallar la operación de negocio que lo origina:
// se entregan al confirmar esa operación, cada una en su propia transacción (NotificationDelivery)
public interface NotificationService {

    void notifyTicketPurchased(Ticket ticket);

    // A todos los pasajeros con ticket SOLD del viaje
    void notifyPlatformChanged(Trip trip, String oldPlatform);

    void notifyArrivalSoon(Trip trip);

    // Debe llamarse antes de cancelar los tickets del viaje (se notifica a los que siguen SOLD)
    void notifyTripCancelled(Trip trip);

    void notifyTripRescheduled(Trip trip);

    // Del usuario autenticado, más recientes primero; con unreadOnly solo las no leídas
    List<NotificationResponse> getMyNotifications(boolean unreadOnly);

    // Marca como leída una notificación del usuario autenticado (403 si es de otro usuario)
    NotificationResponse markAsRead(Long notificationId);

    // Todas las notificaciones, o solo las de un viaje si se indica tripId
    List<NotificationResponse> getNotifications(Long tripId);
}
