package com.web.service.notification;

import com.web.dto.notification.NotificationResponse;
import com.web.entity.Parcel;
import com.web.entity.Ticket;
import com.web.entity.Trip;

import java.util.List;

// Notificaciones simuladas por WhatsApp/SMS. Ningún envío hace fallar la operación de negocio que lo origina
public interface NotificationService {

    void notifyTicketPurchased(Ticket ticket);

    // A todos los pasajeros con ticket SOLD del viaje
    void notifyPlatformChanged(Trip trip, String oldPlatform);

    void notifyArrivalSoon(Trip trip);

    // Debe llamarse antes de cancelar los tickets del viaje (se notifica a los que siguen SOLD)
    void notifyTripCancelled(Trip trip);

    void notifyTripRescheduled(Trip trip);

    List<NotificationResponse> getMyNotifications();

    // Todas las notificaciones, o solo las de un viaje si se indica tripId
    List<NotificationResponse> getNotifications(Long tripId);

    // Encomienda registrada: al destinatario el OTP de entrega y al remitente el código de rastreo
    void notifyParcelCreated(Parcel parcel, String otp);
}
