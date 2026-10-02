package com.web.service.dispatch;

import com.web.dto.dispatch.Assignment.AssignmentCreateRequest;
import com.web.dto.dispatch.Assignment.AssignmentResponse;
import com.web.dto.dispatch.Assignment.AssignmentUpdateRequest;
import com.web.dto.dispatch.Assignment.mapper.AssignmentMapper;
import com.web.entity.Assignment;
import com.web.entity.Trip;
import com.web.entity.User;
import com.web.exception.BusinessException;
import com.web.exception.ResourceNotFoundException;
import com.web.repository.AssignmentRepository;
import com.web.repository.TripRepository;
import com.web.repository.UserRepository;
import com.web.exception.InvalidStateTransitionException;
import com.web.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;


@Service
@RequiredArgsConstructor
public class AssignmentServiceImpl implements AssignmentService {

    private final AssignmentRepository assignmentRepository;
    private final TripRepository tripRepository;
    private final UserRepository userRepository;
    private final AssignmentMapper assignmentMapper;

    // Asigna un conductor a un viaje verificando que no tenga conflictos de horario
    @Override

    @Transactional
    public AssignmentResponse assignTrip(AssignmentCreateRequest request) {
        Trip trip = tripRepository.findById(request.tripId())
                .orElseThrow(() -> new ResourceNotFoundException("Viaje", request.tripId()));

        if (trip.getStatus() != Trip.TripStatus.SCHEDULED) {
            throw new BusinessException("Solo se pueden asignar viajes en estado SCHEDULED", HttpStatus.BAD_REQUEST, "INVALID_TRIP_STATUS");
        }

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

        // El despachador es quien hace la asignación; el dispatcherId del body solo se usa si no hay uno autenticado
        User dispatcher = (SecurityUtils.hasRole("DISPATCHER")
                ? SecurityUtils.currentUsername().flatMap(userRepository::findByEmail)
                : java.util.Optional.<User>empty())
                .or(() -> request.dispatcherId() == null ? java.util.Optional.empty() : userRepository.findById(request.dispatcherId()))
                .orElseThrow(() -> new ResourceNotFoundException("Despachador", request.dispatcherId()));

        if (dispatcher.getRole() != User.Role.DISPATCHER) {
            throw new BusinessException("El usuario no es un despachador", HttpStatus.BAD_REQUEST, "INVALID_DISPATCHER_ROLE");
        }

        Assignment assignment = assignmentMapper.toEntity(request);
        // Establecer las relaciones manualmente
        assignment.setTrip(trip);
        assignment.setDriver(driver);
        assignment.setDispatcher(dispatcher);
        assignment.setAssignedAt(LocalDateTime.now());
        
        Assignment savedAssignment = assignmentRepository.save(assignment);


        return assignmentMapper.toResponse(savedAssignment);
    }

    // Obtiene la asignación de un viaje específico por su ID
    @Override
    @Transactional(readOnly = true)
    public AssignmentResponse getAssignmentByTrip(Long tripId) {
        Assignment assignment = assignmentRepository.findByTripId(tripId)
                .orElseThrow(() -> new ResourceNotFoundException("Asignación para viaje: " + tripId));
        return assignmentMapper.toResponse(assignment);
    }

    // Actualiza el checklist de una asignación (SOAT, revisión técnica, etc.)
    @Override
    @Transactional
    public AssignmentResponse updateChecklist(Long assignmentId, AssignmentUpdateRequest request) {
        Assignment assignment = assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Asignación", assignmentId));

        // El checklist y el conductor solo se modifican antes de la salida
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

    // El conductor debe estar activo y sin otro viaje que se cruce en horario
    private void requireDriverAvailable(User driver, Trip trip) {
        if (driver.getStatus() == User.Status.INACTIVE) {
            throw new BusinessException("El conductor está inactivo", HttpStatus.BAD_REQUEST, "DRIVER_INACTIVE");
        }
        LocalDateTime eta = trip.getArrivalEta() != null ? trip.getArrivalEta() : trip.getDepartureTime();
        if (!assignmentRepository.isDriverAvailable(driver.getId(), trip.getTripDate(), trip.getDepartureTime(), eta)) {
            throw new BusinessException("El conductor ya tiene un viaje asignado en ese horario",
                    HttpStatus.CONFLICT, "DRIVER_NOT_AVAILABLE");
        }
    }
}

