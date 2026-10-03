package com.web.service.notification;

import com.web.dto.notification.NotificationResponse;
import com.web.dto.notification.mapper.NotificationMapper;
import com.web.entity.Notification;
import com.web.entity.Parcel;
import com.web.entity.Stop;
import com.web.entity.Ticket;
import com.web.entity.Trip;
import com.web.entity.User;
import com.web.exception.BusinessException;
import com.web.exception.ResourceNotFoundException;
import com.web.repository.NotificationRepository;
import com.web.repository.TicketRepository;
import com.web.repository.UserRepository;
import com.web.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final NotificationRepository notificationRepository;
    private final TicketRepository ticketRepository;
    private final UserRepository userRepository;
    private final NotificationSender notificationSender;
    private final NotificationMapper notificationMapper;

    @Override
    @Transactional
    public void notifyTicketPurchased(Ticket ticket) {
        try {
            Trip trip = ticket.getTrip();
            String message = String.format(
                    "Compra confirmada: tiquete #%d, viaje %s, tramo %s → %s, silla %d, salida %s%s. "
                            + "Código QR para abordar: %s",
                    ticket.getId(), routeLabel(trip), stopName(ticket.getFromStop()), stopName(ticket.getToStop()),
                    ticket.getSeatNumber(), format(trip.getDepartureTime()), platformLabel(trip), ticket.getQrCode());
            send(ticket.getPassenger(), Notification.NotificationType.TICKET_PURCHASED, message, trip, ticket);
        } catch (RuntimeException e) {
            log.warn("No se pudo notificar la compra del ticket {}: {}", ticket.getId(), e.getMessage());
        }
    }

    @Override
    @Transactional
    public void notifyPlatformChanged(Trip trip, String oldPlatform) {
        String previous = oldPlatform == null ? "" : " (antes: " + oldPlatform + ")";
        notifyPassengers(trip, Notification.NotificationType.PLATFORM_CHANGED, ticket -> String.format(
                "Cambio de andén: tu viaje %s con salida %s sale por el andén %s%s. Silla %d.",
                routeLabel(trip), format(trip.getDepartureTime()), trip.getPlatform(), previous,
                ticket.getSeatNumber()));
    }

    @Override
    @Transactional
    public void notifyArrivalSoon(Trip trip) {
        notifyPassengers(trip, Notification.NotificationType.ARRIVAL_SOON, ticket -> String.format(
                "Llegada próxima: tu viaje %s llegará a su destino hacia las %s. Tu parada de bajada: %s.",
                routeLabel(trip), format(trip.getArrivalEta()), stopName(ticket.getToStop())));
    }

    @Override
    @Transactional
    public void notifyTripCancelled(Trip trip) {
        notifyPassengers(trip, Notification.NotificationType.TRIP_CANCELLED, ticket -> String.format(
                "Tu viaje %s con salida %s fue cancelado. Se reembolsará el 100%% de tu tiquete #%d.",
                routeLabel(trip), format(trip.getDepartureTime()), ticket.getId()));
    }

    @Override
    @Transactional
    public void notifyTripRescheduled(Trip trip) {
        notifyPassengers(trip, Notification.NotificationType.TRIP_RESCHEDULED, ticket -> String.format(
                "Tu viaje %s fue reprogramado: nueva salida %s, llegada estimada %s. Silla %d.",
                routeLabel(trip), format(trip.getDepartureTime()), format(trip.getArrivalEta()),
                ticket.getSeatNumber()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<NotificationResponse> getMyNotifications() {
        String email = SecurityUtils.currentUsername()
                .orElseThrow(() -> new BusinessException("Se requiere un usuario autenticado",
                        HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED"));
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario", email));
        return notificationMapper.toResponseList(
                notificationRepository.findByUserIdOrderByCreatedAtDescIdDesc(user.getId()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<NotificationResponse> getNotifications(Long tripId) {
        List<Notification> notifications = tripId == null
                ? notificationRepository.findAllByOrderByCreatedAtDescIdDesc()
                : notificationRepository.findByTripIdOrderByCreatedAtDescIdDesc(tripId);
        return notificationMapper.toResponseList(notifications);
    }

    // Regla de canal: los móviles colombianos (empiezan por 3, con o sin indicativo 57) reciben WhatsApp;
    // cualquier otro número (fijo o extranjero) recibe SMS
    static Notification.Channel resolveChannel(String phone) {
        String digits = phone.replaceAll("\\D", "");
        if (digits.length() == 12 && digits.startsWith("57")) {
            digits = digits.substring(2);
        }
        return digits.startsWith("3") ? Notification.Channel.WHATSAPP : Notification.Channel.SMS;
    }

    // Una notificación por pasajero con ticket SOLD en el viaje (aunque tenga varios tickets)
    private void notifyPassengers(Trip trip, Notification.NotificationType type, Function<Ticket, String> messageFor) {
        try {
            List<Ticket> soldTickets = ticketRepository.findByTripIdAndStatus(trip.getId(), Ticket.TicketStatus.SOLD);
            Set<Long> notified = new HashSet<>();
            for (Ticket ticket : soldTickets) {
                User passenger = ticket.getPassenger();
                if (passenger != null && notified.add(passenger.getId())) {
                    send(passenger, type, messageFor.apply(ticket), trip, ticket);
                }
            }
        } catch (RuntimeException e) {
            log.warn("No se pudo notificar {} del viaje {}: {}", type, trip.getId(), e.getMessage());
        }
    }

    // Envía por el canal simulado y deja registro; un fallo del envío queda como FAILED sin propagarse
    private void send(User user, Notification.NotificationType type, String message, Trip trip, Ticket ticket) {
        if (user == null || user.getPhone() == null || user.getPhone().isBlank()) {
            log.debug("Usuario sin teléfono: no se envía la notificación {}", type);
            return;
        }
        String recipient = user.getPhone().trim();
        Notification.Channel channel = resolveChannel(recipient);
        Notification.NotificationStatus status = Notification.NotificationStatus.SENT;
        try {
            notificationSender.send(channel, recipient, message);
        } catch (RuntimeException e) {
            log.warn("Falló el envío {} a {}: {}", channel, recipient, e.getMessage());
            status = Notification.NotificationStatus.FAILED;
        }
        notificationRepository.save(Notification.builder()
                .user(user)
                .channel(channel)
                .type(type)
                .recipient(recipient)
                .message(message)
                .trip(trip)
                .ticket(ticket)
                .status(status)
                .createdAt(LocalDateTime.now())
                .build());
    }

    private static String routeLabel(Trip trip) {
        if (trip.getRoute() == null) {
            return "#" + trip.getId();
        }
        return trip.getRoute().getOrigin() + " - " + trip.getRoute().getDestination();
    }

    private static String platformLabel(Trip trip) {
        return trip.getPlatform() == null ? "" : ", andén " + trip.getPlatform();
    }

    private static String stopName(Stop stop) {
        return stop == null ? "-" : stop.getName();
    }

    private static String format(LocalDateTime dateTime) {
        return dateTime == null ? "-" : dateTime.format(DATE_TIME);
    }

    @Override
    @Transactional
    public void notifyParcelCreated(Parcel parcel, String otp) {
        try {
            Trip trip = parcel.getTrip();
            String route = trip == null ? "-" : routeLabel(trip);
            String receiverText = "Tienes una encomienda de %s (código %s) en el viaje %s con entrega en %s. "
                    + "Código de entrega (OTP): %s. Preséntalo solo al recibirla.";
            sendToPhone(parcel.getReceiverPhone(),
                    String.format(receiverText, parcel.getSenderName(), parcel.getCode(), route,
                            stopName(parcel.getToStop()), otp),
                    // El registro no guarda el OTP en claro
                    String.format(receiverText, parcel.getSenderName(), parcel.getCode(), route,
                            stopName(parcel.getToStop()), "******"),
                    trip);
            String senderText = String.format(
                    "Encomienda registrada para %s: código de rastreo %s, tramo %s → %s, viaje %s.",
                    parcel.getReceiverName(), parcel.getCode(), stopName(parcel.getFromStop()),
                    stopName(parcel.getToStop()), route);
            sendToPhone(parcel.getSenderPhone(), senderText, senderText, trip);
        } catch (RuntimeException e) {
            log.warn("No se pudo notificar la encomienda {}: {}", parcel.getCode(), e.getMessage());
        }
    }

    // Remitente y destinatario de una encomienda no tienen por qué ser usuarios: siempre se envía al teléfono,
    // pero solo queda registro si el teléfono pertenece a un usuario (la notificación exige usuario)
    private void sendToPhone(String phone, String message, String loggedMessage, Trip trip) {
        if (phone == null || phone.isBlank()) {
            return;
        }
        String recipient = phone.trim();
        Notification.Channel channel = resolveChannel(recipient);
        Notification.NotificationStatus status = Notification.NotificationStatus.SENT;
        try {
            notificationSender.send(channel, recipient, message);
        } catch (RuntimeException e) {
            log.warn("Falló el envío {} a {}: {}", channel, recipient, e.getMessage());
            status = Notification.NotificationStatus.FAILED;
        }
        Notification.NotificationStatus finalStatus = status;
        userRepository.findFirstByPhone(recipient).ifPresent(user -> notificationRepository.save(Notification.builder()
                .user(user)
                .channel(channel)
                .type(Notification.NotificationType.PARCEL_CREATED)
                .recipient(recipient)
                .message(loggedMessage)
                .trip(trip)
                .status(finalStatus)
                .createdAt(LocalDateTime.now())
                .build()));
    }
}
