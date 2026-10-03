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
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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

    /** Inicia el proceso de abordaje cambiando estado a BOARDING */
    @Override
    @Transactional
    public TripResponse openBoarding(Long tripId) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new ResourceNotFoundException("Viaje", tripId));

        if (trip.getStatus() != Trip.TripStatus.SCHEDULED) {
            throw new InvalidStateTransitionException(
                    "Solo se puede abrir abordaje desde estado SCHEDULED (actual: " + trip.getStatus() + ")");
        }

        trip.setStatus(Trip.TripStatus.BOARDING);
        Trip updatedTrip = tripRepository.save(trip);



        return tripMapper.toResponse(updatedTrip);
    }

    /** Cierra el abordaje: quien no abordó en la parada de origen queda NO_SHOW (su silla vuelve a venta).
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

        markNoShows(tripId);
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

        // Validar checklist completo: checklistOk, SOAT válido y revisión válida
        if (!assignment.getChecklistOk()) {
            throw new BusinessException("No se puede partir sin checklist aprobado", HttpStatus.BAD_REQUEST, "CHECKLIST_NOT_APPROVED");
        }
        
        if (!assignment.getSoatValid()) {
            throw new BusinessException("No se puede partir sin SOAT vigente", HttpStatus.BAD_REQUEST, "SOAT_NOT_VALID");
        }
        
        if (!assignment.getRevisionValid()) {
            throw new BusinessException("No se puede partir sin revisión técnica vigente", HttpStatus.BAD_REQUEST, "REVISION_NOT_VALID");
        }

        // Quien no abordó en la parada de origen queda NO_SHOW (independiente de la tarea programada)
        markNoShows(tripId);

        trip.setStatus(Trip.TripStatus.DEPARTED);
        trip.setDepartedAt(LocalDateTime.now());
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

    // Marca NO_SHOW (con su fee) los tickets vendidos sin abordar que suben en la parada de origen
    private void markNoShows(Long tripId) {
        List<Ticket> unboarded = ticketRepository.findUnboardedOriginTickets(tripId);
        if (unboarded.isEmpty()) {
            return;
        }
        BigDecimal fee = configService.getNoShowFee();
        for (Ticket ticket : unboarded) {
            ticket.setStatus(Ticket.TicketStatus.NO_SHOW);
            ticket.setNoShowFee(fee);
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

