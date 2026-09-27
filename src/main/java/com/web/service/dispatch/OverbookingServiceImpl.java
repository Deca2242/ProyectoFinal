package com.web.service.dispatch;

import com.web.dto.dispatch.OverbookingApprovalResponse;
import com.web.entity.Incident;
import com.web.entity.Trip;
import com.web.entity.User;
import com.web.exception.BusinessException;
import com.web.exception.OverbookingNotAllowedException;
import com.web.exception.ResourceNotFoundException;
import com.web.repository.IncidentRepository;
import com.web.repository.TicketRepository;
import com.web.repository.TripRepository;
import com.web.repository.UserRepository;
import com.web.service.admin.ConfigService;
import com.web.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Overbooking controlado (regla 4 / caso de uso 3): con ocupación mayor al 95 % y menos de 30 minutos
 * para la salida, un DISPATCHER aprueba de a una silla extra, hasta el porcentaje máximo configurado.
 * Cada aprobación queda registrada como incidente OVERBOOK.
 */
@Service
@RequiredArgsConstructor
public class OverbookingServiceImpl implements OverbookingService {

    static final double MIN_OCCUPANCY_RATE = 0.95;
    static final long MAX_MINUTES_BEFORE_DEPARTURE = 30;

    private final TripRepository tripRepository;
    private final TicketRepository ticketRepository;
    private final IncidentRepository incidentRepository;
    private final UserRepository userRepository;
    private final ConfigService configService;

    @Override
    @Transactional
    public OverbookingApprovalResponse approveExtraSeat(Long tripId) {
        // Serializa con las ventas del mismo viaje
        tripRepository.lockById(tripId);
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new ResourceNotFoundException("Viaje", tripId));

        if (trip.getStatus() != Trip.TripStatus.SCHEDULED && trip.getStatus() != Trip.TripStatus.BOARDING) {
            throw new BusinessException("El viaje no admite ventas (estado: " + trip.getStatus() + ")",
                    HttpStatus.BAD_REQUEST, "TRIP_NOT_AVAILABLE");
        }

        LocalDateTime now = LocalDateTime.now();
        long minutesToDeparture = Duration.between(now, trip.getDepartureTime()).toMinutes();
        if (!trip.getDepartureTime().isAfter(now)) {
            throw new BusinessException("El viaje ya salió", HttpStatus.BAD_REQUEST, "TRIP_ALREADY_DEPARTED");
        }
        if (minutesToDeparture >= MAX_MINUTES_BEFORE_DEPARTURE) {
            throw new OverbookingNotAllowedException(
                    "El overbooking solo se aprueba cuando faltan menos de " + MAX_MINUTES_BEFORE_DEPARTURE
                            + " minutos para la salida (faltan " + minutesToDeparture + ")");
        }

        int capacity = trip.getBus().getCapacity();
        long soldSeats = ticketRepository.countSoldSeats(tripId);
        double occupancy = capacity > 0 ? (double) soldSeats / capacity : 0.0;
        if (occupancy <= MIN_OCCUPANCY_RATE) {
            throw new OverbookingNotAllowedException(String.format(
                    "El overbooking requiere una ocupación mayor al 95%% (actual: %.1f%%)", occupancy * 100));
        }

        int maxExtra = (int) Math.floor(capacity * configService.getOverbookingMaxPercentage() + 1e-9);
        int approved = trip.getOverbookingApprovedSeats() == null ? 0 : trip.getOverbookingApprovedSeats();
        if (approved >= maxExtra) {
            throw new OverbookingNotAllowedException(
                    "Se alcanzó el máximo de overbooking permitido para el viaje (" + maxExtra + " sillas)");
        }

        trip.setOverbookingApprovedSeats(approved + 1);
        tripRepository.save(trip);

        User dispatcher = SecurityUtils.currentUsername()
                .flatMap(userRepository::findByEmail)
                .orElse(null);
        incidentRepository.save(Incident.builder()
                .entityType(Incident.EntityType.TRIP)
                .entityId(tripId)
                .incidentType(Incident.IncidentType.OVERBOOK)
                .description("Overbooking aprobado: silla " + (capacity + approved + 1)
                        + " (" + (approved + 1) + "/" + maxExtra + ")")
                .reportedBy(dispatcher)
                .createdAt(now)
                .build());

        return new OverbookingApprovalResponse(
                tripId,
                capacity,
                soldSeats,
                occupancy * 100,
                approved + 1,
                maxExtra,
                capacity + approved + 1);
    }
}
