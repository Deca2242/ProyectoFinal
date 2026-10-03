package com.web.service.dispatch;

import com.web.dto.trip.TripResponse;
import com.web.dto.trip.mapper.TripMapper;
import com.web.entity.Assignment;
import com.web.entity.Ticket;
import com.web.entity.Trip;
import com.web.exception.BusinessException;
import com.web.exception.InvalidStateTransitionException;
import com.web.exception.ResourceNotFoundException;
import com.web.repository.AssignmentRepository;
import com.web.repository.TicketRepository;
import com.web.repository.TripRepository;
import com.web.service.admin.ConfigService;
import com.web.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;


/**
 * Servicio para gestionar el proceso de abordaje de viajes
 * Controla transiciones de estado: SCHEDULED → BOARDING → IN_TRANSIT
 */
@Service
@RequiredArgsConstructor
public class BoardingServiceImpl implements BoardingService {

    private final TripRepository tripRepository;
    private final AssignmentRepository assignmentRepository;
    private final TripMapper tripMapper;
    private final TicketRepository ticketRepository;
    private final ConfigService configService;

    /** Inicia el proceso de abordaje cambiando estado a BOARDING (requiere conductor asignado).
     *  Si el abordaje ya se había cerrado, lo reabre */
    @Override
    @Transactional
    public TripResponse openBoarding(Long tripId) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new ResourceNotFoundException("Viaje", tripId));

        boolean reopening = trip.getStatus() == Trip.TripStatus.BOARDING && trip.getBoardingClosedAt() != null;
        if (trip.getStatus() != Trip.TripStatus.SCHEDULED && !reopening) {
            throw new InvalidStateTransitionException(
                    "Solo se puede abrir abordaje desde estado SCHEDULED o reabrir uno cerrado (actual: " + trip.getStatus() + ")");
        }

        // Sin conductor asignado nadie podría validar los QR ni dar la salida
        boolean hasDriver = assignmentRepository.findByTripId(tripId)
                .map(a -> a.getDriver() != null)
                .orElse(false);
        if (!hasDriver) {
            throw new BusinessException("El viaje necesita una asignación con conductor para abrir el abordaje",
                    HttpStatus.CONFLICT, "ASSIGNMENT_REQUIRED");
        }

        trip.setStatus(Trip.TripStatus.BOARDING);
        trip.setBoardingClosedAt(null);
        Trip updatedTrip = tripRepository.save(trip);



        return tripMapper.toResponse(updatedTrip);
    }

    /** Cierra el abordaje: quien no abordó en la parada de origen queda NO_SHOW (su silla vuelve a venta)
     *  y se registra boardingClosedAt. Cerrar un abordaje ya cerrado no cambia nada (idempotente).
     *  El viaje sigue en BOARDING hasta partir; un pasajero que llegue tarde aún puede abordar si su silla sigue libre */
    @Override
    @Transactional
    public TripResponse closeBoarding(Long tripId) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new ResourceNotFoundException("Viaje", tripId));

        if (trip.getStatus() != Trip.TripStatus.BOARDING) {
            throw new InvalidStateTransitionException(
                    "Solo se puede cerrar abordaje desde estado BOARDING (actual: " + trip.getStatus() + ")");
        }

        if (trip.getBoardingClosedAt() != null) {
            return tripMapper.toResponse(trip);
        }

        markNoShows(tripId);
        trip.setBoardingClosedAt(LocalDateTime.now());
        Trip updatedTrip = tripRepository.save(trip);



        return tripMapper.toResponse(updatedTrip);
    }

    @Override
    @Transactional
    public TripResponse departTrip(Long tripId) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new ResourceNotFoundException("Viaje", tripId));

        if (trip.getStatus() != Trip.TripStatus.BOARDING) {
            throw new InvalidStateTransitionException(
                    "Solo se puede partir desde estado BOARDING (actual: " + trip.getStatus() + ")");
        }

        Assignment assignment = assignmentRepository.findByTripId(tripId)
                .orElseThrow(() -> new BusinessException("El viaje no tiene asignación", HttpStatus.BAD_REQUEST, "NO_ASSIGNMENT"));

        // Autenticación del DRIVER en la salida: solo el conductor asignado puede partir
        requireAssignedDriver(assignment);

        // Validar checklist completo: checklistOk, SOAT vigente y revisión vigente el día del viaje
        if (!Boolean.TRUE.equals(assignment.getChecklistOk())) {
            throw new BusinessException("No se puede partir sin checklist aprobado", HttpStatus.BAD_REQUEST, "CHECKLIST_NOT_APPROVED");
        }

        // Con fechas de vencimiento en el bus manda la fecha; sin fechas, los booleanos del checklist
        List<String> expired = assignment.expiredDocuments();
        if (!expired.isEmpty()) {
            throw new BusinessException("Checklist vencido para el viaje del " + trip.getTripDate() + ": "
                    + String.join(", ", expired), HttpStatus.BAD_REQUEST, "CHECKLIST_EXPIRED");
        }

        if (!assignment.soatValidOnTripDate()) {
            throw new BusinessException("No se puede partir sin SOAT vigente", HttpStatus.BAD_REQUEST, "SOAT_NOT_VALID");
        }
        
        if (!assignment.reviewValidOnTripDate()) {
            throw new BusinessException("No se puede partir sin revisión técnica vigente", HttpStatus.BAD_REQUEST, "REVISION_NOT_VALID");
        }

        // Quien no abordó en la parada de origen queda NO_SHOW (independiente de la tarea programada)
        markNoShows(tripId);

        LocalDateTime now = LocalDateTime.now();
        trip.setStatus(Trip.TripStatus.DEPARTED);
        trip.setDepartedAt(now);
        // Partir cierra el abordaje si el despachador no lo había cerrado
        if (trip.getBoardingClosedAt() == null) {
            trip.setBoardingClosedAt(now);
        }
        Trip updatedTrip = tripRepository.save(trip);



        return tripMapper.toResponse(updatedTrip);
    }

    /** Registra la llegada del viaje (DEPARTED → ARRIVED) por el conductor asignado */
    @Override
    @Transactional
    public TripResponse arriveTrip(Long tripId) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new ResourceNotFoundException("Viaje", tripId));

        if (trip.getStatus() != Trip.TripStatus.DEPARTED) {
            throw new InvalidStateTransitionException(
                    "Solo se puede registrar la llegada desde estado DEPARTED (actual: " + trip.getStatus() + ")");
        }

        Assignment assignment = assignmentRepository.findByTripId(tripId)
                .orElseThrow(() -> new BusinessException("El viaje no tiene asignación", HttpStatus.BAD_REQUEST, "NO_ASSIGNMENT"));
        requireAssignedDriver(assignment);

        trip.setStatus(Trip.TripStatus.ARRIVED);
        trip.setArrivedAt(LocalDateTime.now());
        return tripMapper.toResponse(tripRepository.save(trip));
    }

    // Marca NO_SHOW los tickets vendidos sin abordar que suben en la parada de origen. El fee es el configurado
    // por el ADMIN (porcentaje del precio del ticket o monto fijo). Cerrar el abordaje o partir es una decisión
    // explícita del despacho: marca a todos los que faltan sin esperar la ventana de no-show de la tarea programada
    private void markNoShows(Long tripId) {
        List<Ticket> unboarded = ticketRepository.findUnboardedOriginTickets(tripId);
        if (unboarded.isEmpty()) {
            return;
        }
        for (Ticket ticket : unboarded) {
            ticket.setStatus(Ticket.TicketStatus.NO_SHOW);
            ticket.setNoShowFee(configService.computeNoShowFee(ticket.getPrice()));
        }
        ticketRepository.saveAll(unboarded);
    }

    // Si quien llama es un DRIVER, debe ser el conductor asignado (DISPATCHER/ADMIN no tienen esta restricción)
    private void requireAssignedDriver(Assignment assignment) {
        if (!SecurityUtils.hasRole("DRIVER")) {
            return;
        }
        String username = SecurityUtils.currentUsername().orElse("");
        if (assignment.getDriver() == null || !username.equalsIgnoreCase(assignment.getDriver().getEmail())) {
            throw new BusinessException("El conductor no está asignado a este viaje",
                    HttpStatus.FORBIDDEN, "DRIVER_NOT_ASSIGNED");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public TripResponse getTripStatus(Long tripId) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new ResourceNotFoundException("Viaje", tripId));
        return tripMapper.toResponse(trip);
    }
}

