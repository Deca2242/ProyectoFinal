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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;


@ExtendWith(MockitoExtension.class)
class AssignmentServiceImplTest {

    @Mock
    private AssignmentRepository assignmentRepository;
    @Mock
    private TripRepository tripRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private AssignmentMapper assignmentMapper;

    @InjectMocks
    private AssignmentServiceImpl assignmentService;

    private Trip trip;
    private User driver;
    private User dispatcher;
    private Assignment assignment;
    private AssignmentResponse assignmentResponse;

    @BeforeEach
    void setUp() {
        // Por defecto el conductor está libre en el horario del viaje
        lenient().when(assignmentRepository.isDriverAvailable(any(), any(), any(), any())).thenReturn(true);
        trip = Trip.builder()
                .id(1L)
                .status(Trip.TripStatus.SCHEDULED)
                .build();

        driver = User.builder()
                .id(1L)
                .name("Driver Name")
                .email("driver@example.com")
                .role(User.Role.DRIVER)
                .build();

        dispatcher = User.builder()
                .id(2L)
                .name("Dispatcher Name")
                .email("dispatcher@example.com")
                .role(User.Role.DISPATCHER)
                .build();

        assignment = Assignment.builder()
                .id(1L)
                .trip(trip)
                .driver(driver)
                .dispatcher(dispatcher)
                .checklistOk(false)
                .soatValid(false)
                .revisionValid(false)
                .assignedAt(LocalDateTime.now())
                .build();

        assignmentResponse = new AssignmentResponse(
                1L, 1L, 1L, "Driver Name", "123456789",
                2L, "Dispatcher Name",
                false, false, false,
                LocalDateTime.now()
        , null, null, null, null, null);
    }

    @Test
    void shouldAssignTrip_WithValidRequest_ReturnAssignmentResponse() {
        // Given
        AssignmentCreateRequest request = new AssignmentCreateRequest(1L, 1L, 2L);

        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(assignmentRepository.findByTripId(1L)).thenReturn(Optional.empty());
        when(userRepository.findById(1L)).thenReturn(Optional.of(driver));
        when(userRepository.findById(2L)).thenReturn(Optional.of(dispatcher));
        when(assignmentMapper.toEntity(request)).thenReturn(assignment);
        when(assignmentRepository.save(any(Assignment.class))).thenAnswer(inv -> {
            Assignment a = inv.getArgument(0);
            a.setId(1L);
            return a;
        });
        when(assignmentMapper.toResponse(any(Assignment.class))).thenReturn(assignmentResponse);

        // When
        AssignmentResponse result = assignmentService.assignTrip(request);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.id()).isEqualTo(1L);
        verify(tripRepository).findById(1L);
        verify(assignmentRepository).save(any(Assignment.class));
    }

    @Test
    void shouldAssignTrip_WithInvalidTripStatus_ThrowException() {
        // Given
        trip.setStatus(Trip.TripStatus.DEPARTED);
        AssignmentCreateRequest request = new AssignmentCreateRequest(1L, 1L, 2L);

        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));

        // When/Then
        assertThatThrownBy(() -> assignmentService.assignTrip(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("SCHEDULED");
    }

    @Test
    void shouldAssignTrip_WithInvalidDriverRole_ThrowException() {
        // Given
        driver.setRole(User.Role.PASSENGER);
        AssignmentCreateRequest request = new AssignmentCreateRequest(1L, 1L, 2L);

        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(assignmentRepository.findByTripId(1L)).thenReturn(Optional.empty());
        when(userRepository.findById(1L)).thenReturn(Optional.of(driver));

        // When/Then
        assertThatThrownBy(() -> assignmentService.assignTrip(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("conductor");
    }

    @Test
    void shouldUpdateChecklist_WithValidRequest_UpdateFields() {
        // Given
        AssignmentUpdateRequest request = new AssignmentUpdateRequest(
                null, true, true, true
        );

        when(assignmentRepository.findById(1L)).thenReturn(Optional.of(assignment));
        when(assignmentRepository.save(any(Assignment.class))).thenReturn(assignment);
        when(assignmentMapper.toResponse(any(Assignment.class))).thenReturn(assignmentResponse);

        // When
        AssignmentResponse result = assignmentService.updateChecklist(1L, request);

        // Then
        assertThat(result).isNotNull();
        verify(assignmentMapper).updateEntityFromRequest(request, assignment);
        verify(assignmentRepository).save(assignment);
    }

    @Test
    void shouldGetAssignmentByTrip_WithValidTripId_ReturnAssignment() {
        // Given
        when(assignmentRepository.findByTripId(1L)).thenReturn(Optional.of(assignment));
        when(assignmentMapper.toResponse(assignment)).thenReturn(assignmentResponse);

        // When
        AssignmentResponse result = assignmentService.getAssignmentByTrip(1L);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.id()).isEqualTo(1L);
        verify(assignmentRepository).findByTripId(1L);
    }

    @Test
    void shouldGetDriverAssignments_WithValidDriverId_ReturnList() {
        // Given: sin fecha se devuelven todas las asignaciones del conductor
        List<Assignment> assignments = List.of(assignment);
        List<AssignmentResponse> responses = List.of(assignmentResponse);

        when(assignmentRepository.findByDriverId(1L)).thenReturn(assignments);
        when(assignmentMapper.toResponseList(assignments)).thenReturn(responses);

        // When
        List<AssignmentResponse> result = assignmentService.getDriverAssignments(1L, null);

        // Then
        assertThat(result).isNotNull();
        assertThat(result).hasSize(1);
        verify(assignmentRepository).findByDriverId(1L);
    }

    @Test
    void shouldGetDriverAssignments_WithDate_FilterByDate() {
        // Given: con fecha se filtra por los viajes de ese día
        LocalDate date = LocalDate.now();
        List<Assignment> assignments = List.of(assignment);
        List<AssignmentResponse> responses = List.of(assignmentResponse);

        when(assignmentRepository.findDriverAssignmentsForDate(1L, date)).thenReturn(assignments);
        when(assignmentMapper.toResponseList(assignments)).thenReturn(responses);

        // When
        List<AssignmentResponse> result = assignmentService.getDriverAssignments(1L, date);

        // Then
        assertThat(result).hasSize(1);
        verify(assignmentRepository).findDriverAssignmentsForDate(1L, date);
        verify(assignmentRepository, never()).findByDriverId(anyLong());
    }

    @Test
    void shouldAssignTrip_WithValidRequest_SetRelationsAndAssignedAt() {
        // Given: el mapper devuelve una entidad sin relaciones, el servicio debe completarlas
        AssignmentCreateRequest request = new AssignmentCreateRequest(1L, 1L, 2L);
        Assignment fromMapper = Assignment.builder().assignedAt(null).build();
        LocalDateTime before = LocalDateTime.now();

        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(assignmentRepository.findByTripId(1L)).thenReturn(Optional.empty());
        when(userRepository.findById(1L)).thenReturn(Optional.of(driver));
        when(userRepository.findById(2L)).thenReturn(Optional.of(dispatcher));
        when(assignmentMapper.toEntity(request)).thenReturn(fromMapper);
        when(assignmentRepository.save(any(Assignment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(assignmentMapper.toResponse(any(Assignment.class))).thenReturn(assignmentResponse);

        // When
        assignmentService.assignTrip(request);

        // Then
        ArgumentCaptor<Assignment> captor = ArgumentCaptor.forClass(Assignment.class);
        verify(assignmentRepository).save(captor.capture());
        Assignment saved = captor.getValue();
        assertThat(saved.getTrip()).isSameAs(trip);
        assertThat(saved.getDriver()).isSameAs(driver);
        assertThat(saved.getDispatcher()).isSameAs(dispatcher);
        assertThat(saved.getAssignedAt()).isNotNull().isAfterOrEqualTo(before);
    }

    @Test
    void shouldAssignTrip_WithNonExistentTrip_ThrowResourceNotFound() {
        // Given
        AssignmentCreateRequest request = new AssignmentCreateRequest(99L, 1L, 2L);
        when(tripRepository.findById(99L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> assignmentService.assignTrip(request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");
        verifyNoInteractions(userRepository, assignmentMapper);
        verify(assignmentRepository, never()).save(any());
    }

    @Test
    void shouldAssignTrip_WhenAssignmentAlreadyExists_ThrowConflict() {
        // Given: el viaje ya tiene una asignación previa
        AssignmentCreateRequest request = new AssignmentCreateRequest(1L, 1L, 2L);
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(assignmentRepository.findByTripId(1L)).thenReturn(Optional.of(assignment));

        // When/Then
        assertThatThrownBy(() -> assignmentService.assignTrip(request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(be.getCode()).isEqualTo("ASSIGNMENT_EXISTS");
                });
        verifyNoInteractions(userRepository);
        verify(assignmentRepository, never()).save(any());
    }

    @Test
    void shouldAssignTrip_WithBoardingTrip_ReturnInvalidTripStatusCode() {
        // Given: un viaje en BOARDING ya no admite asignación
        trip.setStatus(Trip.TripStatus.BOARDING);
        AssignmentCreateRequest request = new AssignmentCreateRequest(1L, 1L, 2L);
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));

        // When/Then
        assertThatThrownBy(() -> assignmentService.assignTrip(request))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo("INVALID_TRIP_STATUS");
        verifyNoInteractions(assignmentRepository, userRepository);
    }

    @Test
    void shouldAssignTrip_WithNonExistentDriver_ThrowResourceNotFound() {
        // Given
        AssignmentCreateRequest request = new AssignmentCreateRequest(1L, 50L, 2L);
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(assignmentRepository.findByTripId(1L)).thenReturn(Optional.empty());
        when(userRepository.findById(50L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> assignmentService.assignTrip(request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Conductor");
        verify(assignmentRepository, never()).save(any());
    }

    @Test
    void shouldAssignTrip_WithNonExistentDispatcher_ThrowResourceNotFound() {
        // Given
        AssignmentCreateRequest request = new AssignmentCreateRequest(1L, 1L, 60L);
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(assignmentRepository.findByTripId(1L)).thenReturn(Optional.empty());
        when(userRepository.findById(1L)).thenReturn(Optional.of(driver));
        when(userRepository.findById(60L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> assignmentService.assignTrip(request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Despachador");
        verify(assignmentRepository, never()).save(any());
    }

    @Test
    void shouldAssignTrip_WithInvalidDispatcherRole_ThrowException() {
        // Given: el usuario indicado como despachador es un CLERK
        dispatcher.setRole(User.Role.CLERK);
        AssignmentCreateRequest request = new AssignmentCreateRequest(1L, 1L, 2L);
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(assignmentRepository.findByTripId(1L)).thenReturn(Optional.empty());
        when(userRepository.findById(1L)).thenReturn(Optional.of(driver));
        when(userRepository.findById(2L)).thenReturn(Optional.of(dispatcher));

        // When/Then
        assertThatThrownBy(() -> assignmentService.assignTrip(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("despachador")
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo("INVALID_DISPATCHER_ROLE");
        verify(assignmentRepository, never()).save(any());
        verifyNoInteractions(assignmentMapper);
    }

    @Test
    void shouldGetAssignmentByTrip_WithNonExistentTrip_ThrowResourceNotFound() {
        // Given
        when(assignmentRepository.findByTripId(99L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> assignmentService.getAssignmentByTrip(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");
        verifyNoInteractions(assignmentMapper);
    }

    @Test
    void shouldUpdateChecklist_WithNonExistentAssignment_ThrowResourceNotFound() {
        // Given
        AssignmentUpdateRequest request = new AssignmentUpdateRequest(null, true, true, true);
        when(assignmentRepository.findById(99L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> assignmentService.updateChecklist(99L, request))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(assignmentMapper, userRepository);
        verify(assignmentRepository, never()).save(any());
    }

    @Test
    void shouldUpdateChecklist_WithoutDriverId_KeepCurrentDriver() {
        // Given: sin driverId no se consulta el repositorio de usuarios
        AssignmentUpdateRequest request = new AssignmentUpdateRequest(null, true, false, true);
        when(assignmentRepository.findById(1L)).thenReturn(Optional.of(assignment));
        when(assignmentRepository.save(assignment)).thenReturn(assignment);
        when(assignmentMapper.toResponse(assignment)).thenReturn(assignmentResponse);

        // When
        assignmentService.updateChecklist(1L, request);

        // Then
        assertThat(assignment.getDriver()).isSameAs(driver);
        verifyNoInteractions(userRepository);
    }

    @Test
    void shouldUpdateChecklist_WithValidDriverId_ChangeDriver() {
        // Given: el nuevo conductor existe y tiene rol DRIVER
        User newDriver = User.builder()
                .id(5L)
                .name("Nuevo Conductor")
                .role(User.Role.DRIVER)
                .build();
        AssignmentUpdateRequest request = new AssignmentUpdateRequest(5L, null, null, null);

        when(assignmentRepository.findById(1L)).thenReturn(Optional.of(assignment));
        when(userRepository.findById(5L)).thenReturn(Optional.of(newDriver));
        when(assignmentRepository.save(assignment)).thenReturn(assignment);
        when(assignmentMapper.toResponse(assignment)).thenReturn(assignmentResponse);

        // When
        AssignmentResponse result = assignmentService.updateChecklist(1L, request);

        // Then
        assertThat(result).isNotNull();
        ArgumentCaptor<Assignment> captor = ArgumentCaptor.forClass(Assignment.class);
        verify(assignmentRepository).save(captor.capture());
        assertThat(captor.getValue().getDriver()).isSameAs(newDriver);
        verify(assignmentMapper).updateEntityFromRequest(request, assignment);
    }

    @Test
    void shouldUpdateChecklist_WithNonDriverUser_ThrowExceptionAndNotSave() {
        // Given: el usuario indicado no tiene rol DRIVER
        User passenger = User.builder()
                .id(7L)
                .role(User.Role.PASSENGER)
                .build();
        AssignmentUpdateRequest request = new AssignmentUpdateRequest(7L, true, true, true);

        when(assignmentRepository.findById(1L)).thenReturn(Optional.of(assignment));
        when(userRepository.findById(7L)).thenReturn(Optional.of(passenger));

        // When/Then
        assertThatThrownBy(() -> assignmentService.updateChecklist(1L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("conductor")
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo("INVALID_DRIVER_ROLE");
        assertThat(assignment.getDriver()).isSameAs(driver);
        verify(assignmentRepository, never()).save(any());
    }

    @Test
    void shouldUpdateChecklist_WithNonExistentDriver_ThrowResourceNotFound() {
        // Given
        AssignmentUpdateRequest request = new AssignmentUpdateRequest(77L, null, null, null);
        when(assignmentRepository.findById(1L)).thenReturn(Optional.of(assignment));
        when(userRepository.findById(77L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> assignmentService.updateChecklist(1L, request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("77");
        verify(assignmentRepository, never()).save(any());
    }

    @Test
    void shouldGetDriverAssignments_WithoutAssignments_ReturnEmptyList() {
        // Given
        when(assignmentRepository.findByDriverId(3L)).thenReturn(List.of());
        when(assignmentMapper.toResponseList(List.of())).thenReturn(List.of());

        // When
        List<AssignmentResponse> result = assignmentService.getDriverAssignments(3L, null);

        // Then
        assertThat(result).isEmpty();
        verify(assignmentRepository, never()).findDriverAssignmentsForDate(anyLong(), any());
    }

    @Test
    void shouldGetDispatcherAssignments_UseCurrentDate() {
        // Given: el filtro de fecha es siempre la fecha actual
        List<Assignment> assignments = List.of(assignment);
        List<AssignmentResponse> responses = List.of(assignmentResponse);
        when(assignmentRepository.findByDispatcherId(eq(2L), any(LocalDate.class))).thenReturn(assignments);
        when(assignmentMapper.toResponseList(assignments)).thenReturn(responses);

        // When
        List<AssignmentResponse> result = assignmentService.getDispatcherAssignments(2L);

        // Then
        assertThat(result).containsExactly(assignmentResponse);
        ArgumentCaptor<LocalDate> dateCaptor = ArgumentCaptor.forClass(LocalDate.class);
        verify(assignmentRepository).findByDispatcherId(eq(2L), dateCaptor.capture());
        assertThat(dateCaptor.getValue()).isEqualTo(LocalDate.now());
    }

    @Test
    void shouldGetDispatcherAssignments_WithDate_FilterByThatDate() {
        // Given
        LocalDate date = LocalDate.of(2026, 12, 20);
        List<Assignment> assignments = List.of(assignment);
        when(assignmentRepository.findDispatcherAssignmentsForDate(2L, date)).thenReturn(assignments);
        when(assignmentMapper.toResponseList(assignments)).thenReturn(List.of(assignmentResponse));

        // When
        List<AssignmentResponse> result = assignmentService.getDispatcherAssignments(2L, date);

        // Then
        assertThat(result).containsExactly(assignmentResponse);
        verify(assignmentRepository, never()).findByDispatcherId(anyLong(), any());
    }

    @Test
    void shouldGetDispatcherAssignments_WithNullDate_ReturnFromToday() {
        // Given
        when(assignmentRepository.findByDispatcherId(2L, LocalDate.now())).thenReturn(List.of());
        when(assignmentMapper.toResponseList(List.of())).thenReturn(List.of());

        // When
        List<AssignmentResponse> result = assignmentService.getDispatcherAssignments(2L, null);

        // Then
        assertThat(result).isEmpty();
        verify(assignmentRepository, never()).findDispatcherAssignmentsForDate(anyLong(), any());
    }
}
