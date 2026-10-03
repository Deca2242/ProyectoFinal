package com.web.service.dispatch;

import com.web.dto.dispatch.Assignment.AssignmentCreateRequest;
import com.web.dto.dispatch.Assignment.AssignmentResponse;
import com.web.dto.dispatch.Assignment.AssignmentUpdateRequest;
import com.web.dto.dispatch.Assignment.mapper.AssignmentMapper;
import com.web.entity.Assignment;
import com.web.entity.Bus;
import com.web.entity.SeatHold;
import com.web.entity.Ticket;
import com.web.entity.Trip;
import com.web.entity.User;
import com.web.exception.BusinessException;
import com.web.exception.ResourceNotFoundException;
import com.web.repository.AssignmentRepository;
import com.web.repository.BusRepository;
import com.web.repository.SeatHoldRepository;
import com.web.repository.TicketRepository;
import com.web.repository.TripRepository;
import com.web.repository.UserRepository;
import com.web.exception.InvalidStateTransitionException;
import com.web.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;


@Service
@RequiredArgsConstructor
public class AssignmentServiceImpl implements AssignmentService {

    private final AssignmentRepository assignmentRepository;
    private final TripRepository tripRepository;
    private final UserRepository userRepository;
    private final AssignmentMapper assignmentMapper;
    private final BusRepository busRepository;
    private final TicketRepository ticketRepository;
    private final SeatHoldRepository seatHoldRepository;

    // Asigna un conductor (y opcionalmente otro bus) a un viaje SCHEDULED o BOARDING sin conflictos de horario
    @Override
    @Transactional
    public AssignmentResponse assignTrip(AssignmentCreateRequest request) {
        Trip trip = tripRepository.findById(request.tripId())
                .orElseThrow(() -> new ResourceNotFoundException("Viaje", request.tripId()));

        requireBeforeDeparture(trip, "Solo se pueden asignar viajes en estado SCHEDULED o BOARDING");

        // Verificar si ya existe una asignación
        if (assignmentRepository.findByTripId(request.tripId()).isPresent()) {
            throw new BusinessException("Este viaje ya tiene una asignación", HttpStatus.CONFLICT, "ASSIGNMENT_EXISTS");
        }

        User driver = userRepository.findById(request.driverId())
                .orElseThrow(() -> new ResourceNotFoundException("Conductor", request.driverId()));

        if (driver.getRole() != User.Role.DRIVER) {
            throw new BusinessException("El usuario no es un conductor", HttpStatus.BAD_REQUEST, "INVALID_DRIVER_ROLE");
        }
        requireDriverAvailable(driver, trip);

        User dispatcher = resolveDispatcher(request.dispatcherId());

        changeBusIfRequested(trip, request.busId());

        Assignment assignment = assignmentMapper.toEntity(request);
        // Establecer las relaciones manualmente
        assignment.setTrip(trip);
        assignment.setDriver(driver);
        assignment.setDispatcher(dispatcher);
        assignment.setAssignedAt(LocalDateTime.now());

        Assignment savedAssignment = assignmentRepository.save(assignment);


        return assignmentMapper.toResponse(savedAssignment);
    }

    // Obtiene la asignación de un viaje; un DRIVER solo puede ver la de sus propios viajes
    @Override
    @Transactional(readOnly = true)
    public AssignmentResponse getAssignmentByTrip(Long tripId) {
        Assignment assignment = assignmentRepository.findByTripId(tripId)
                .orElseThrow(() -> new ResourceNotFoundException("Asignación para viaje: " + tripId));
        checkAssignedDriver(assignment);
        return assignmentMapper.toResponse(assignment);
    }

    // Si quien llama es un DRIVER, debe ser el conductor asignado al viaje (403); otros roles pasan sin restricción
    @Override
    @Transactional(readOnly = true)
    public void requireAssignedDriver(Long tripId) {
        if (!SecurityUtils.hasRole("DRIVER")) {
            return;
        }
        Assignment assignment = assignmentRepository.findByTripId(tripId)
                .orElseThrow(() -> new BusinessException("El conductor no está asignado a este viaje",
                        HttpStatus.FORBIDDEN, "DRIVER_NOT_ASSIGNED"));
        checkAssignedDriver(assignment);
    }

    // Actualiza el checklist de una asignación (SOAT, revisión técnica, etc.), el conductor o el bus
    @Override
    @Transactional
    public AssignmentResponse updateChecklist(Long assignmentId, AssignmentUpdateRequest request) {
        Assignment assignment = assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Asignación", assignmentId));

        // El checklist, el conductor y el bus solo se modifican antes de la salida
        Trip.TripStatus tripStatus = assignment.getTrip().getStatus();
        if (tripStatus != Trip.TripStatus.SCHEDULED && tripStatus != Trip.TripStatus.BOARDING) {
            throw new InvalidStateTransitionException(
                    "La asignación no se puede modificar con el viaje en estado " + tripStatus);
        }

        assignmentMapper.updateEntityFromRequest(request, assignment);

        // El mapper ignora driverId: el cambio de conductor se valida y aplica aquí
        if (request.driverId() != null) {
            User driver = userRepository.findById(request.driverId())
                    .orElseThrow(() -> new ResourceNotFoundException("Conductor", request.driverId()));
            if (driver.getRole() != User.Role.DRIVER) {
                throw new BusinessException("El usuario no es un conductor", HttpStatus.BAD_REQUEST, "INVALID_DRIVER_ROLE");
            }
            if (!driver.getId().equals(assignment.getDriver() == null ? null : assignment.getDriver().getId())) {
                requireDriverAvailable(driver, assignment.getTrip());
            }
            assignment.setDriver(driver);
        }

        changeBusIfRequested(assignment.getTrip(), request.busId());

        // No se aprueba un checklist con SOAT o revisión vencidos para el día del viaje
        if (Boolean.TRUE.equals(request.checklistOk())) {
            List<String> expired = assignment.expiredDocuments();
            if (!expired.isEmpty()) {
                throw new BusinessException("Checklist vencido para el viaje del " + assignment.getTrip().getTripDate()
                        + ": " + String.join(", ", expired), HttpStatus.BAD_REQUEST, "CHECKLIST_EXPIRED");
            }
        }

        Assignment updatedAssignment = assignmentRepository.save(assignment);


        return assignmentMapper.toResponse(updatedAssignment);
    }

    // Obtiene todas las asignaciones de un conductor (filtro por fecha opcional)
    @Override
    @Transactional(readOnly = true)
    public List<AssignmentResponse> getDriverAssignments(Long driverId, LocalDate date) {
        List<Assignment> assignments = date != null
                ? assignmentRepository.findDriverAssignmentsForDate(driverId, date)
                : assignmentRepository.findByDriverId(driverId);
        return assignmentMapper.toResponseList(assignments);
    }

    // Obtiene las asignaciones realizadas por un despachador desde la fecha actual
    @Override
    @Transactional(readOnly = true)
    public List<AssignmentResponse> getDispatcherAssignments(Long dispatcherId) {
        List<Assignment> assignments = assignmentRepository.findByDispatcherId(dispatcherId, LocalDate.now());
        return assignmentMapper.toResponseList(assignments);
    }

    // Asignaciones de un despachador para una fecha (sin fecha, las de hoy en adelante)
    @Override
    @Transactional(readOnly = true)
    public List<AssignmentResponse> getDispatcherAssignments(Long dispatcherId, LocalDate date) {
        if (date == null) {
            return getDispatcherAssignments(dispatcherId);
        }
        return assignmentMapper.toResponseList(assignmentRepository.findDispatcherAssignmentsForDate(dispatcherId, date));
    }

    // Asignar o cambiar el bus solo tiene sentido antes de la salida (422 en otro estado)
    private void requireBeforeDeparture(Trip trip, String message) {
        if (trip.getStatus() != Trip.TripStatus.SCHEDULED && trip.getStatus() != Trip.TripStatus.BOARDING) {
            throw new InvalidStateTransitionException(message + " (actual: " + trip.getStatus() + ")");
        }
    }

    // El despachador es el usuario autenticado; el dispatcherId del body, si viene, debe ser el suyo.
    // Sin usuario DISPATCHER autenticado (procesos internos) se usa el del body
    private User resolveDispatcher(Long requestedDispatcherId) {
        Optional<User> authenticated = SecurityUtils.hasRole("DISPATCHER")
                ? SecurityUtils.currentUsername().flatMap(userRepository::findByEmail)
                : Optional.empty();

        if (authenticated.isPresent()) {
            User dispatcher = authenticated.get();
            if (requestedDispatcherId != null && !requestedDispatcherId.equals(dispatcher.getId())) {
                throw new BusinessException("El dispatcherId no corresponde al despachador autenticado",
                        HttpStatus.BAD_REQUEST, "DISPATCHER_MISMATCH");
            }
            return dispatcher;
        }

        User dispatcher = Optional.ofNullable(requestedDispatcherId)
                .flatMap(userRepository::findById)
                .orElseThrow(() -> new ResourceNotFoundException("Despachador", requestedDispatcherId));
        if (dispatcher.getRole() != User.Role.DISPATCHER) {
            throw new BusinessException("El usuario no es un despachador", HttpStatus.BAD_REQUEST, "INVALID_DISPATCHER_ROLE");
        }
        return dispatcher;
    }

    // Cambia el bus del viaje con las mismas reglas que la reprogramación: bus ACTIVE, sin otro viaje que se
    // cruce en horario y con las sillas ya vendidas dentro de la capacidad nueva (+ overbooking aprobado)
    private void changeBusIfRequested(Trip trip, Long busId) {
        if (busId == null || (trip.getBus() != null && busId.equals(trip.getBus().getId()))) {
            return;
        }
        Bus bus = busRepository.findById(busId)
                .orElseThrow(() -> new ResourceNotFoundException("Bus", busId));
        if (bus.getStatus() != Bus.BusStatus.ACTIVE) {
            throw new BusinessException("El bus no está disponible", HttpStatus.BAD_REQUEST, "BUS_NOT_AVAILABLE");
        }

        LocalDateTime eta = trip.getArrivalEta() != null ? trip.getArrivalEta() : trip.getDepartureTime();
        if (tripRepository.existsOverlappingBusTrip(bus.getId(), trip.getId(), trip.getDepartureTime(), eta)) {
            throw new BusinessException("El bus ya tiene un viaje que se cruza con este horario",
                    HttpStatus.CONFLICT, "BUS_BUSY");
        }

        int approvedOverbooking = trip.getOverbookingApprovedSeats() == null ? 0 : trip.getOverbookingApprovedSeats();
        int sellableSeats = bus.getCapacity() + approvedOverbooking;
        boolean seatOutOfRange = ticketRepository.findByTripIdAndStatus(trip.getId(), Ticket.TicketStatus.SOLD).stream()
                .anyMatch(t -> t.getSeatNumber() > sellableSeats);
        if (seatOutOfRange) {
            throw new BusinessException("Hay tiquetes vendidos en sillas que no existen en el bus nuevo",
                    HttpStatus.CONFLICT, "SEATS_EXCEED_CAPACITY");
        }

        // Los holds activos sobre sillas que no existen en el bus nuevo se liberan
        List<SeatHold> orphanHolds = seatHoldRepository.findActiveHoldsByTrip(trip.getId(), LocalDateTime.now()).stream()
                .filter(h -> h.getSeatNumber() > sellableSeats)
                .toList();
        if (!orphanHolds.isEmpty()) {
            orphanHolds.forEach(h -> h.setStatus(SeatHold.HoldStatus.EXPIRED));
            seatHoldRepository.saveAll(orphanHolds);
        }

        trip.setBus(bus);
        tripRepository.save(trip);
    }

    // El conductor debe estar activo y sin otro viaje que se cruce en horario (en cualquier fecha)
    private void requireDriverAvailable(User driver, Trip trip) {
        if (driver.getStatus() == User.Status.INACTIVE) {
            throw new BusinessException("El conductor está inactivo", HttpStatus.BAD_REQUEST, "DRIVER_INACTIVE");
        }
        LocalDateTime eta = trip.getArrivalEta() != null ? trip.getArrivalEta() : trip.getDepartureTime();
        if (!assignmentRepository.isDriverAvailableExcludingTrip(driver.getId(), trip.getId(), trip.getDepartureTime(), eta)) {
            throw new BusinessException("El conductor ya tiene un viaje asignado en ese horario",
                    HttpStatus.CONFLICT, "DRIVER_NOT_AVAILABLE");
        }
    }

    // Un DRIVER solo accede a la asignación de los viajes que tiene asignados
    private void checkAssignedDriver(Assignment assignment) {
        if (!SecurityUtils.hasRole("DRIVER")) {
            return;
        }
        String username = SecurityUtils.currentUsername().orElse("");
        if (assignment.getDriver() == null || !username.equalsIgnoreCase(assignment.getDriver().getEmail())) {
            throw new BusinessException("El conductor no está asignado a este viaje",
                    HttpStatus.FORBIDDEN, "DRIVER_NOT_ASSIGNED");
        }
    }
}
