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
import com.web.exception.InvalidStateTransitionException;
import com.web.exception.ResourceNotFoundException;
import com.web.repository.AssignmentRepository;
import com.web.repository.BusRepository;
import com.web.repository.SeatHoldRepository;
import com.web.repository.TicketRepository;
import com.web.repository.TripRepository;
import com.web.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

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
    @Mock
    private BusRepository busRepository;
    @Mock
    private TicketRepository ticketRepository;
    @Mock
    private SeatHoldRepository seatHoldRepository;

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
        lenient().when(assignmentRepository.isDriverAvailableExcludingTrip(any(), any(), any(), any())).thenReturn(true);
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
        , null, null, null, null, null, null, null, false, false);
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

    // 422: estado inválido para la transición (solo SCHEDULED o BOARDING admiten asignación)
    @ParameterizedTest
    @EnumSource(value = Trip.TripStatus.class, names = {"DEPARTED", "ARRIVED", "CANCELLED"})
    void shouldAssignTrip_WithTripAlreadyDepartedOrClosed_ThrowInvalidStateTransition(Trip.TripStatus status) {
        // Given
        trip.setStatus(status);
        AssignmentCreateRequest request = new AssignmentCreateRequest(1L, 1L, 2L);

        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));

        // When/Then
        assertThatThrownBy(() -> assignmentService.assignTrip(request))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("SCHEDULED")
                .hasMessageContaining(status.name())
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        verifyNoInteractions(assignmentRepository, userRepository);
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
    void shouldAssignTrip_WithBoardingTrip_Assign() {
        // Given: un viaje en BOARDING sin asignación todavía admite asignar conductor
        trip.setStatus(Trip.TripStatus.BOARDING);
        AssignmentCreateRequest request = new AssignmentCreateRequest(1L, 1L, 2L);
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(assignmentRepository.findByTripId(1L)).thenReturn(Optional.empty());
        when(userRepository.findById(1L)).thenReturn(Optional.of(driver));
        when(userRepository.findById(2L)).thenReturn(Optional.of(dispatcher));
        when(assignmentMapper.toEntity(request)).thenReturn(assignment);
        when(assignmentRepository.save(any(Assignment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(assignmentMapper.toResponse(any(Assignment.class))).thenReturn(assignmentResponse);

        // When
        AssignmentResponse result = assignmentService.assignTrip(request);

        // Then
        assertThat(result).isNotNull();
        verify(assignmentRepository).save(assignment);
    }

    // ---------- Disponibilidad por intervalo (sin filtro de fecha) ----------

    @Test
    void shouldAssignTrip_WithDriverBusyInOvernightTripOfPreviousDay_ThrowConflict() {
        // Given: el viaje sale a la 01:00; el conductor tiene otro que salió el día anterior a las 22:00 y llega a las 02:00
        LocalDate date = LocalDate.now().plusDays(3);
        trip.setTripDate(date);
        trip.setDepartureTime(date.atTime(1, 0));
        trip.setArrivalEta(date.atTime(5, 0));
        AssignmentCreateRequest request = new AssignmentCreateRequest(1L, 1L, 2L);
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(assignmentRepository.findByTripId(1L)).thenReturn(Optional.empty());
        when(userRepository.findById(1L)).thenReturn(Optional.of(driver));
        when(assignmentRepository.isDriverAvailableExcludingTrip(1L, 1L, date.atTime(1, 0), date.atTime(5, 0)))
                .thenReturn(false);

        // When/Then: la consulta es por intervalo, no por la fecha del viaje
        assertThatThrownBy(() -> assignmentService.assignTrip(request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    assertThat(((BusinessException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(((BusinessException) ex).getCode()).isEqualTo("DRIVER_NOT_AVAILABLE");
                });
        verify(assignmentRepository, never()).save(any());
    }

    // ---------- Cambio de bus (busId) ----------

    private Bus bus(Long id, int capacity, Bus.BusStatus status) {
        return Bus.builder().id(id).plate("BUS-" + id).capacity(capacity).status(status).build();
    }

    private void givenScheduledTripWithBus() {
        LocalDate date = LocalDate.now().plusDays(3);
        trip.setTripDate(date);
        trip.setDepartureTime(date.atTime(8, 0));
        trip.setArrivalEta(date.atTime(12, 0));
        trip.setBus(bus(30L, 40, Bus.BusStatus.ACTIVE));
    }

    @Test
    void shouldAssignTrip_WithBusId_ChangeTripBusAndReleaseOrphanHolds() {
        // Given
        givenScheduledTripWithBus();
        Bus newBus = bus(31L, 20, Bus.BusStatus.ACTIVE);
        AssignmentCreateRequest request = new AssignmentCreateRequest(1L, 1L, 2L, 31L);
        SeatHold inRange = SeatHold.builder().id(1L).seatNumber(5).status(SeatHold.HoldStatus.HOLD).build();
        SeatHold orphan = SeatHold.builder().id(2L).seatNumber(35).status(SeatHold.HoldStatus.HOLD).build();
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(assignmentRepository.findByTripId(1L)).thenReturn(Optional.empty());
        when(userRepository.findById(1L)).thenReturn(Optional.of(driver));
        when(userRepository.findById(2L)).thenReturn(Optional.of(dispatcher));
        when(busRepository.findById(31L)).thenReturn(Optional.of(newBus));
        when(tripRepository.existsOverlappingBusTrip(31L, 1L, trip.getDepartureTime(), trip.getArrivalEta())).thenReturn(false);
        when(ticketRepository.findByTripIdAndStatus(1L, Ticket.TicketStatus.SOLD))
                .thenReturn(List.of(Ticket.builder().id(1L).seatNumber(12).build()));
        when(seatHoldRepository.findActiveHoldsByTrip(eq(1L), any())).thenReturn(List.of(inRange, orphan));
        when(assignmentMapper.toEntity(request)).thenReturn(assignment);
        when(assignmentRepository.save(any(Assignment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(assignmentMapper.toResponse(any(Assignment.class))).thenReturn(assignmentResponse);

        // When
        assignmentService.assignTrip(request);

        // Then
        assertThat(trip.getBus()).isSameAs(newBus);
        verify(tripRepository).save(trip);
        assertThat(orphan.getStatus()).isEqualTo(SeatHold.HoldStatus.EXPIRED);
        assertThat(inRange.getStatus()).isEqualTo(SeatHold.HoldStatus.HOLD);
        verify(seatHoldRepository).saveAll(List.of(orphan));
    }

    @Test
    void shouldUpdateChecklist_WithBusId_ChangeBus() {
        // Given
        givenScheduledTripWithBus();
        Bus newBus = bus(31L, 45, Bus.BusStatus.ACTIVE);
        AssignmentUpdateRequest request = new AssignmentUpdateRequest(null, null, null, null, 31L);
        when(assignmentRepository.findById(1L)).thenReturn(Optional.of(assignment));
        when(busRepository.findById(31L)).thenReturn(Optional.of(newBus));
        when(tripRepository.existsOverlappingBusTrip(any(), any(), any(), any())).thenReturn(false);
        when(ticketRepository.findByTripIdAndStatus(1L, Ticket.TicketStatus.SOLD)).thenReturn(List.of());
        when(seatHoldRepository.findActiveHoldsByTrip(eq(1L), any())).thenReturn(List.of());
        when(assignmentRepository.save(assignment)).thenReturn(assignment);
        when(assignmentMapper.toResponse(assignment)).thenReturn(assignmentResponse);

        // When
        assignmentService.updateChecklist(1L, request);

        // Then
        assertThat(trip.getBus()).isSameAs(newBus);
        verify(tripRepository).save(trip);
        verify(seatHoldRepository, never()).saveAll(any());
    }

    @Test
    void shouldUpdateChecklist_WithSameBusId_NotValidateNorChangeBus() {
        // Given
        givenScheduledTripWithBus();
        Bus current = trip.getBus();
        when(assignmentRepository.findById(1L)).thenReturn(Optional.of(assignment));
        when(assignmentRepository.save(assignment)).thenReturn(assignment);

        // When
        assignmentService.updateChecklist(1L, new AssignmentUpdateRequest(null, null, null, null, 30L));

        // Then
        assertThat(trip.getBus()).isSameAs(current);
        verifyNoInteractions(busRepository, ticketRepository, seatHoldRepository);
    }

    @Test
    void shouldUpdateChecklist_WithBusyBus_ThrowConflict() {
        // Given
        givenScheduledTripWithBus();
        when(assignmentRepository.findById(1L)).thenReturn(Optional.of(assignment));
        when(busRepository.findById(31L)).thenReturn(Optional.of(bus(31L, 40, Bus.BusStatus.ACTIVE)));
        when(tripRepository.existsOverlappingBusTrip(31L, 1L, trip.getDepartureTime(), trip.getArrivalEta())).thenReturn(true);

        // When/Then
        assertThatThrownBy(() -> assignmentService.updateChecklist(1L, new AssignmentUpdateRequest(null, null, null, null, 31L)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    assertThat(((BusinessException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(((BusinessException) ex).getCode()).isEqualTo("BUS_BUSY");
                });
        assertThat(trip.getBus().getId()).isEqualTo(30L);
        verify(assignmentRepository, never()).save(any());
    }

    @Test
    void shouldUpdateChecklist_WithBusTooSmallForSoldSeats_ThrowConflict() {
        // Given: silla 25 vendida, el bus nuevo tiene 20 sillas + 2 de overbooking aprobado
        givenScheduledTripWithBus();
        trip.setOverbookingApprovedSeats(2);
        when(assignmentRepository.findById(1L)).thenReturn(Optional.of(assignment));
        when(busRepository.findById(31L)).thenReturn(Optional.of(bus(31L, 20, Bus.BusStatus.ACTIVE)));
        when(tripRepository.existsOverlappingBusTrip(any(), any(), any(), any())).thenReturn(false);
        when(ticketRepository.findByTripIdAndStatus(1L, Ticket.TicketStatus.SOLD))
                .thenReturn(List.of(Ticket.builder().id(9L).seatNumber(25).build()));

        // When/Then
        assertThatThrownBy(() -> assignmentService.updateChecklist(1L, new AssignmentUpdateRequest(null, null, null, null, 31L)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    assertThat(((BusinessException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(((BusinessException) ex).getCode()).isEqualTo("SEATS_EXCEED_CAPACITY");
                });
        verify(tripRepository, never()).save(any());
    }

    @Test
    void shouldUpdateChecklist_WithInactiveBus_ThrowBadRequest() {
        // Given
        givenScheduledTripWithBus();
        when(assignmentRepository.findById(1L)).thenReturn(Optional.of(assignment));
        when(busRepository.findById(31L)).thenReturn(Optional.of(bus(31L, 40, Bus.BusStatus.MAINTENANCE)));

        // When/Then
        assertThatThrownBy(() -> assignmentService.updateChecklist(1L, new AssignmentUpdateRequest(null, null, null, null, 31L)))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("BUS_NOT_AVAILABLE");
        verify(tripRepository, never()).existsOverlappingBusTrip(any(), any(), any(), any());
    }

    @Test
    void shouldUpdateChecklist_WithUnknownBus_ThrowNotFound() {
        // Given
        givenScheduledTripWithBus();
        when(assignmentRepository.findById(1L)).thenReturn(Optional.of(assignment));
        when(busRepository.findById(99L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> assignmentService.updateChecklist(1L, new AssignmentUpdateRequest(null, null, null, null, 99L)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---------- Checklist con vigencia ----------

    @Test
    void shouldUpdateChecklist_ApprovingWithExpiredSoat_ThrowBadRequestWithDetail() {
        // Given: el SOAT vence el día antes del viaje
        givenScheduledTripWithBus();
        trip.getBus().setSoatExpiresAt(trip.getTripDate().minusDays(1));
        trip.getBus().setTechnicalReviewExpiresAt(trip.getTripDate().plusMonths(6));
        when(assignmentRepository.findById(1L)).thenReturn(Optional.of(assignment));

        // When/Then
        assertThatThrownBy(() -> assignmentService.updateChecklist(1L, new AssignmentUpdateRequest(null, true, true, true)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("SOAT vencido el " + trip.getTripDate().minusDays(1))
                .satisfies(ex -> {
                    assertThat(((BusinessException) ex).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(((BusinessException) ex).getCode()).isEqualTo("CHECKLIST_EXPIRED");
                });
        verify(assignmentRepository, never()).save(any());
    }

    @Test
    void shouldUpdateChecklist_ApprovingWithDocumentsExpiringOnTripDate_Approve() {
        // Given: vencer el mismo día del viaje sigue siendo vigente
        givenScheduledTripWithBus();
        trip.getBus().setSoatExpiresAt(trip.getTripDate());
        trip.getBus().setTechnicalReviewExpiresAt(trip.getTripDate());
        when(assignmentRepository.findById(1L)).thenReturn(Optional.of(assignment));
        when(assignmentRepository.save(assignment)).thenReturn(assignment);

        // When
        assignmentService.updateChecklist(1L, new AssignmentUpdateRequest(null, true, null, null));

        // Then
        verify(assignmentRepository).save(assignment);
        assertThat(assignment.soatValidOnTripDate()).isTrue();
        assertThat(assignment.reviewValidOnTripDate()).isTrue();
    }

    @Test
    void shouldComputeChecklistValidity_WithoutDates_UseBooleans() {
        // Given: bus sin fechas de vencimiento
        givenScheduledTripWithBus();
        assignment.setSoatValid(true);
        assignment.setRevisionValid(false);

        // When/Then
        assertThat(assignment.soatValidOnTripDate()).isTrue();
        assertThat(assignment.reviewValidOnTripDate()).isFalse();
        assertThat(assignment.expiredDocuments()).isEmpty();
    }

    // ---------- Acceso del conductor ----------

    private void authenticateAs(String email, String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                email, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldGetAssignmentByTrip_AsOtherDriver_ThrowForbidden() {
        // Given
        authenticateAs("otro@example.com", "DRIVER");
        when(assignmentRepository.findByTripId(1L)).thenReturn(Optional.of(assignment));

        // When/Then
        assertThatThrownBy(() -> assignmentService.getAssignmentByTrip(1L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    assertThat(((BusinessException) ex).getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(((BusinessException) ex).getCode()).isEqualTo("DRIVER_NOT_ASSIGNED");
                });
        verifyNoInteractions(assignmentMapper);
    }

    @Test
    void shouldGetAssignmentByTrip_AsAssignedDriver_ReturnAssignment() {
        // Given
        authenticateAs("driver@example.com", "DRIVER");
        when(assignmentRepository.findByTripId(1L)).thenReturn(Optional.of(assignment));
        when(assignmentMapper.toResponse(assignment)).thenReturn(assignmentResponse);

        // When/Then
        assertThat(assignmentService.getAssignmentByTrip(1L)).isSameAs(assignmentResponse);
    }

    @Test
    void shouldRequireAssignedDriver_AsDriverWithoutAssignment_ThrowForbidden() {
        // Given
        authenticateAs("driver@example.com", "DRIVER");
        when(assignmentRepository.findByTripId(1L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> assignmentService.requireAssignedDriver(1L))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("DRIVER_NOT_ASSIGNED");
    }

    @Test
    void shouldRequireAssignedDriver_AsClerk_NotCheckAssignment() {
        // Given
        authenticateAs("clerk@example.com", "CLERK");

        // When
        assignmentService.requireAssignedDriver(1L);

        // Then
        verifyNoInteractions(assignmentRepository);
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
