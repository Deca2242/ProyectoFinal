package com.web.service.dispatch;

import com.web.dto.trip.TripResponse;
import com.web.dto.trip.mapper.TripMapper;
import com.web.entity.Assignment;
import com.web.entity.Bus;
import com.web.entity.Ticket;
import com.web.entity.Trip;
import com.web.entity.User;
import com.web.exception.BusinessException;
import com.web.exception.InvalidStateTransitionException;
import com.web.exception.ResourceNotFoundException;
import com.web.repository.AssignmentRepository;
import com.web.repository.TicketRepository;
import com.web.repository.TripRepository;
import com.web.service.admin.ConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;


@ExtendWith(MockitoExtension.class)
class BoardingServiceImplTest {

    @Mock
    private TripRepository tripRepository;
    @Mock
    private AssignmentRepository assignmentRepository;
    @Mock
    private TripMapper tripMapper;

    @Mock
    private TicketRepository ticketRepository;
    @Mock
    private ConfigService configService;

    @InjectMocks
    private BoardingServiceImpl boardingService;

    private Trip trip;
    private Assignment assignment;
    private TripResponse tripResponse;

    @BeforeEach
    void setUp() {
        trip = Trip.builder()
                .id(1L)
                .status(Trip.TripStatus.SCHEDULED)
                .build();

        assignment = Assignment.builder()
                .id(1L)
                .trip(trip)
                .driver(User.builder().id(10L).email("driver@test.com").role(User.Role.DRIVER).build())
                .checklistOk(true)
                .soatValid(true)
                .revisionValid(true)
                .build();

        tripResponse = new TripResponse(
                1L, 1L, "Route Name", "Origin", "Destination",
                1L, "Bus Plate", 40,
                null, null, null,
                Trip.TripStatus.SCHEDULED, 0, 0.0, null, null, null, null
        );
    }

    @Test
    void shouldOpenBoarding_WithScheduledTrip_ChangeStatusToBoarding() {
        // Given
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(assignmentRepository.findByTripId(1L)).thenReturn(Optional.of(assignment));
        when(tripRepository.save(any(Trip.class))).thenReturn(trip);
        when(tripMapper.toResponse(any(Trip.class))).thenReturn(tripResponse);

        // When
        TripResponse result = boardingService.openBoarding(1L);

        // Then
        assertThat(result).isNotNull();
        verify(tripRepository).save(argThat(t -> 
            t.getStatus() == Trip.TripStatus.BOARDING
        ));
    }

    @Test
    void shouldOpenBoarding_WithInvalidStatus_ThrowException() {
        // Given
        trip.setStatus(Trip.TripStatus.DEPARTED);
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));

        // When/Then
        assertThatThrownBy(() -> boardingService.openBoarding(1L))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("SCHEDULED");
    }

    @Test
    void shouldCloseBoarding_WithBoardingTrip_ChangeStatus() {
        // Given
        trip.setStatus(Trip.TripStatus.BOARDING);
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(tripRepository.save(any(Trip.class))).thenReturn(trip);
        when(tripMapper.toResponse(any(Trip.class))).thenReturn(tripResponse);

        // When
        TripResponse result = boardingService.closeBoarding(1L);

        // Then
        assertThat(result).isNotNull();
        verify(tripRepository).save(any(Trip.class));
    }

    @Test
    void shouldDepartTrip_WithCompleteChecklist_ChangeStatusToDeparted() {
        // Given
        trip.setStatus(Trip.TripStatus.BOARDING);
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(assignmentRepository.findByTripId(1L)).thenReturn(Optional.of(assignment));
        when(tripRepository.save(any(Trip.class))).thenReturn(trip);
        when(tripMapper.toResponse(any(Trip.class))).thenReturn(tripResponse);

        // When
        TripResponse result = boardingService.departTrip(1L);

        // Then
        assertThat(result).isNotNull();
        verify(tripRepository).save(argThat(t -> 
            t.getStatus() == Trip.TripStatus.DEPARTED
        ));
    }

    @Test
    void shouldDepartTrip_WithoutChecklistOk_ThrowException() {
        // Given
        trip.setStatus(Trip.TripStatus.BOARDING);
        assignment.setChecklistOk(false);
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(assignmentRepository.findByTripId(1L)).thenReturn(Optional.of(assignment));

        // When/Then
        assertThatThrownBy(() -> boardingService.departTrip(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("checklist");
    }

    @Test
    void shouldDepartTrip_WithoutSoatValid_ThrowException() {
        // Given
        trip.setStatus(Trip.TripStatus.BOARDING);
        assignment.setSoatValid(false);
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(assignmentRepository.findByTripId(1L)).thenReturn(Optional.of(assignment));

        // When/Then
        assertThatThrownBy(() -> boardingService.departTrip(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("SOAT");
    }

    @Test
    void shouldDepartTrip_WithoutRevisionValid_ThrowException() {
        // Given
        trip.setStatus(Trip.TripStatus.BOARDING);
        assignment.setRevisionValid(false);
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(assignmentRepository.findByTripId(1L)).thenReturn(Optional.of(assignment));

        // When/Then
        assertThatThrownBy(() -> boardingService.departTrip(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("revisión");
    }

    @Test
    void shouldOpenBoarding_WithNonExistentTrip_ThrowResourceNotFound() {
        // Given
        when(tripRepository.findById(99L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> boardingService.openBoarding(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");
        verify(tripRepository, never()).save(any());
    }

    @Test
    void shouldOpenBoarding_WithBoardingTrip_ThrowAndKeepStatus() {
        // Given: el abordaje ya está abierto
        trip.setStatus(Trip.TripStatus.BOARDING);
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));

        // When/Then
        assertThatThrownBy(() -> boardingService.openBoarding(1L))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("BOARDING");
        assertThat(trip.getStatus()).isEqualTo(Trip.TripStatus.BOARDING);
        verify(tripRepository, never()).save(any());
        verifyNoInteractions(tripMapper);
    }

    @Test
    void shouldCloseBoarding_WithBoardingTrip_KeepBoardingStatus() {
        // Given: cerrar el abordaje no cambia el estado hasta la salida
        trip.setStatus(Trip.TripStatus.BOARDING);
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(tripRepository.save(trip)).thenReturn(trip);
        when(tripMapper.toResponse(trip)).thenReturn(tripResponse);

        // When
        TripResponse result = boardingService.closeBoarding(1L);

        // Then
        assertThat(result).isSameAs(tripResponse);
        verify(tripRepository).save(argThat(t -> t.getStatus() == Trip.TripStatus.BOARDING));
    }

    @Test
    void shouldCloseBoarding_WithScheduledTrip_ThrowInvalidStateTransition() {
        // Given: no se puede cerrar un abordaje que nunca se abrió
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));

        // When/Then
        assertThatThrownBy(() -> boardingService.closeBoarding(1L))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("SCHEDULED");
        verify(tripRepository, never()).save(any());
    }

    @Test
    void shouldCloseBoarding_WithNonExistentTrip_ThrowResourceNotFound() {
        // Given
        when(tripRepository.findById(99L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> boardingService.closeBoarding(99L))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(tripRepository, never()).save(any());
    }

    @Test
    void shouldDepartTrip_WithNonExistentTrip_ThrowResourceNotFound() {
        // Given
        when(tripRepository.findById(99L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> boardingService.departTrip(99L))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(assignmentRepository);
    }

    @Test
    void shouldDepartTrip_WithScheduledTrip_ThrowInvalidStateTransition() {
        // Given: no se puede partir sin haber abierto el abordaje
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));

        // When/Then
        assertThatThrownBy(() -> boardingService.departTrip(1L))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("BOARDING");
        verifyNoInteractions(assignmentRepository);
        verify(tripRepository, never()).save(any());
    }

    @Test
    void shouldDepartTrip_WithoutAssignment_ThrowNoAssignment() {
        // Given: el viaje está en abordaje pero no tiene conductor asignado
        trip.setStatus(Trip.TripStatus.BOARDING);
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(assignmentRepository.findByTripId(1L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> boardingService.departTrip(1L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo("NO_ASSIGNMENT");
        assertThat(trip.getStatus()).isEqualTo(Trip.TripStatus.BOARDING);
        verify(tripRepository, never()).save(any());
    }

    @Test
    void shouldDepartTrip_WithoutChecklistOk_NotChangeStatus() {
        // Given
        trip.setStatus(Trip.TripStatus.BOARDING);
        assignment.setChecklistOk(false);
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(assignmentRepository.findByTripId(1L)).thenReturn(Optional.of(assignment));

        // When/Then
        assertThatThrownBy(() -> boardingService.departTrip(1L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo("CHECKLIST_NOT_APPROVED");
        assertThat(trip.getStatus()).isEqualTo(Trip.TripStatus.BOARDING);
        verify(tripRepository, never()).save(any());
    }

    @Test
    void shouldGetTripStatus_WithValidId_ReturnTripResponse() {
        // Given
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(tripMapper.toResponse(trip)).thenReturn(tripResponse);

        // When
        TripResponse result = boardingService.getTripStatus(1L);

        // Then
        assertThat(result).isSameAs(tripResponse);
        verify(tripRepository, never()).save(any());
    }

    @Test
    void shouldGetTripStatus_WithNonExistentTrip_ThrowResourceNotFound() {
        // Given
        when(tripRepository.findById(99L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> boardingService.getTripStatus(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");
        verifyNoInteractions(tripMapper);
    }

    // ---------- No-show al cerrar abordaje y al dar salida ----------

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(String username, String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                username, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private Ticket unboardedTicket(long id) {
        return Ticket.builder().id(id).trip(trip).seatNumber((int) id).status(Ticket.TicketStatus.SOLD).build();
    }

    @Test
    void shouldCloseBoarding_WithUnboardedOriginTickets_MarkNoShowWithFee() {
        // Given
        trip.setStatus(Trip.TripStatus.BOARDING);
        Ticket t1 = unboardedTicket(1L);
        Ticket t2 = unboardedTicket(2L);
        List<Ticket> unboarded = List.of(t1, t2);
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(ticketRepository.findUnboardedOriginTickets(1L)).thenReturn(unboarded);
        // El fee configurable se calcula sobre el precio de cada ticket (porcentaje o monto fijo)
        t1.setPrice(BigDecimal.valueOf(50000));
        t2.setPrice(BigDecimal.valueOf(80000));
        when(configService.computeNoShowFee(BigDecimal.valueOf(50000))).thenReturn(BigDecimal.valueOf(5000));
        when(configService.computeNoShowFee(BigDecimal.valueOf(80000))).thenReturn(BigDecimal.valueOf(8000));
        when(tripRepository.save(trip)).thenReturn(trip);
        when(tripMapper.toResponse(trip)).thenReturn(tripResponse);
        LocalDateTime before = LocalDateTime.now();

        // When
        boardingService.closeBoarding(1L);

        // Then
        assertThat(t1.getStatus()).isEqualTo(Ticket.TicketStatus.NO_SHOW);
        assertThat(t1.getNoShowFee()).isEqualByComparingTo("5000");
        assertThat(t2.getStatus()).isEqualTo(Ticket.TicketStatus.NO_SHOW);
        assertThat(t2.getNoShowFee()).isEqualByComparingTo("8000");
        verify(ticketRepository).saveAll(unboarded);
        verify(configService, never()).getNoShowFee();
        assertThat(trip.getStatus()).isEqualTo(Trip.TripStatus.BOARDING);
        assertThat(trip.getBoardingClosedAt()).isNotNull().isBetween(before, LocalDateTime.now());
    }

    @Test
    void shouldCloseBoarding_Twice_BeIdempotent() {
        // Given: el abordaje ya se cerró hace 10 minutos
        LocalDateTime closedAt = LocalDateTime.now().minusMinutes(10);
        trip.setStatus(Trip.TripStatus.BOARDING);
        trip.setBoardingClosedAt(closedAt);
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(tripMapper.toResponse(trip)).thenReturn(tripResponse);

        // When
        TripResponse result = boardingService.closeBoarding(1L);

        // Then: no se vuelve a marcar no-show ni cambia la hora de cierre
        assertThat(result).isSameAs(tripResponse);
        assertThat(trip.getBoardingClosedAt()).isEqualTo(closedAt);
        verifyNoInteractions(ticketRepository, configService);
        verify(tripRepository, never()).save(any());
    }

    @Test
    void shouldOpenBoarding_WithoutAssignment_ThrowConflict() {
        // Given
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(assignmentRepository.findByTripId(1L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> boardingService.openBoarding(1L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    assertThat(((BusinessException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(((BusinessException) ex).getCode()).isEqualTo("ASSIGNMENT_REQUIRED");
                });
        assertThat(trip.getStatus()).isEqualTo(Trip.TripStatus.SCHEDULED);
        verify(tripRepository, never()).save(any());
    }

    @Test
    void shouldOpenBoarding_WithAssignmentWithoutDriver_ThrowConflict() {
        // Given
        assignment.setDriver(null);
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(assignmentRepository.findByTripId(1L)).thenReturn(Optional.of(assignment));

        // When/Then
        assertThatThrownBy(() -> boardingService.openBoarding(1L))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("ASSIGNMENT_REQUIRED");
    }

    @Test
    void shouldOpenBoarding_AfterClose_ReopenAndClearBoardingClosedAt() {
        // Given
        trip.setStatus(Trip.TripStatus.BOARDING);
        trip.setBoardingClosedAt(LocalDateTime.now().minusMinutes(5));
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(assignmentRepository.findByTripId(1L)).thenReturn(Optional.of(assignment));
        when(tripRepository.save(trip)).thenReturn(trip);
        when(tripMapper.toResponse(trip)).thenReturn(tripResponse);

        // When
        boardingService.openBoarding(1L);

        // Then
        assertThat(trip.getStatus()).isEqualTo(Trip.TripStatus.BOARDING);
        assertThat(trip.getBoardingClosedAt()).isNull();
    }

    // ---------- Checklist con vigencia en la salida ----------

    @Test
    void shouldDepartTrip_WithExpiredReviewDate_ThrowChecklistExpiredWithDetail() {
        // Given: la revisión técnico-mecánica venció antes del viaje (aunque el booleano diga que es válida)
        LocalDate tripDate = LocalDate.now().plusDays(1);
        trip.setStatus(Trip.TripStatus.BOARDING);
        trip.setTripDate(tripDate);
        trip.setBus(Bus.builder().id(3L).soatExpiresAt(tripDate.plusYears(1))
                .technicalReviewExpiresAt(tripDate.minusDays(2)).build());
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(assignmentRepository.findByTripId(1L)).thenReturn(Optional.of(assignment));

        // When/Then
        assertThatThrownBy(() -> boardingService.departTrip(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("revisión técnico-mecánica vencida el " + tripDate.minusDays(2))
                .satisfies(ex -> {
                    assertThat(((BusinessException) ex).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(((BusinessException) ex).getCode()).isEqualTo("CHECKLIST_EXPIRED");
                });
        assertThat(trip.getStatus()).isEqualTo(Trip.TripStatus.BOARDING);
        verify(tripRepository, never()).save(any());
    }

    @Test
    void shouldDepartTrip_WithValidDates_IgnoreBooleans() {
        // Given: con fechas vigentes no importan los booleanos del checklist
        LocalDate tripDate = LocalDate.now().plusDays(1);
        trip.setStatus(Trip.TripStatus.BOARDING);
        trip.setTripDate(tripDate);
        trip.setBus(Bus.builder().id(3L).soatExpiresAt(tripDate).technicalReviewExpiresAt(tripDate.plusMonths(3)).build());
        assignment.setSoatValid(false);
        assignment.setRevisionValid(false);
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(assignmentRepository.findByTripId(1L)).thenReturn(Optional.of(assignment));
        when(ticketRepository.findUnboardedOriginTickets(1L)).thenReturn(List.of());
        when(tripRepository.save(trip)).thenReturn(trip);
        when(tripMapper.toResponse(trip)).thenReturn(tripResponse);

        // When
        boardingService.departTrip(1L);

        // Then
        assertThat(trip.getStatus()).isEqualTo(Trip.TripStatus.DEPARTED);
        assertThat(trip.getBoardingClosedAt()).isEqualTo(trip.getDepartedAt());
    }

    @Test
    void shouldCloseBoarding_WithoutUnboardedTickets_NotQueryFeeNorSave() {
        // Given
        trip.setStatus(Trip.TripStatus.BOARDING);
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(ticketRepository.findUnboardedOriginTickets(1L)).thenReturn(List.of());
        when(tripRepository.save(trip)).thenReturn(trip);
        when(tripMapper.toResponse(trip)).thenReturn(tripResponse);

        // When
        boardingService.closeBoarding(1L);

        // Then
        verifyNoInteractions(configService);
        verify(ticketRepository, never()).saveAll(anyList());
    }

    @Test
    void shouldDepartTrip_WithUnboardedOriginTickets_MarkNoShowAndSetDepartedAt() {
        // Given
        trip.setStatus(Trip.TripStatus.BOARDING);
        Ticket t1 = unboardedTicket(1L);
        List<Ticket> unboarded = List.of(t1);
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(assignmentRepository.findByTripId(1L)).thenReturn(Optional.of(assignment));
        when(ticketRepository.findUnboardedOriginTickets(1L)).thenReturn(unboarded);
        when(configService.computeNoShowFee(any())).thenReturn(BigDecimal.valueOf(7000));
        when(tripRepository.save(trip)).thenReturn(trip);
        when(tripMapper.toResponse(trip)).thenReturn(tripResponse);
        LocalDateTime before = LocalDateTime.now();

        // When
        boardingService.departTrip(1L);

        // Then
        assertThat(t1.getStatus()).isEqualTo(Ticket.TicketStatus.NO_SHOW);
        assertThat(t1.getNoShowFee()).isEqualByComparingTo("7000");
        verify(ticketRepository).saveAll(unboarded);
        assertThat(trip.getStatus()).isEqualTo(Trip.TripStatus.DEPARTED);
        assertThat(trip.getDepartedAt()).isNotNull().isBetween(before, LocalDateTime.now());
    }

    @Test
    void shouldDepartTrip_WithoutUnboardedTickets_NotQueryFeeAndSetDepartedAt() {
        // Given
        trip.setStatus(Trip.TripStatus.BOARDING);
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(assignmentRepository.findByTripId(1L)).thenReturn(Optional.of(assignment));
        when(ticketRepository.findUnboardedOriginTickets(1L)).thenReturn(List.of());
        when(tripRepository.save(trip)).thenReturn(trip);
        when(tripMapper.toResponse(trip)).thenReturn(tripResponse);

        // When
        boardingService.departTrip(1L);

        // Then
        verifyNoInteractions(configService);
        verify(ticketRepository, never()).saveAll(anyList());
        assertThat(trip.getDepartedAt()).isNotNull();
    }

    @Test
    void shouldDepartTrip_WithChecklistFailing_NotMarkNoShows() {
        // Given: la validación del checklist ocurre antes del no-show
        trip.setStatus(Trip.TripStatus.BOARDING);
        assignment.setSoatValid(false);
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(assignmentRepository.findByTripId(1L)).thenReturn(Optional.of(assignment));

        // When/Then
        assertThatThrownBy(() -> boardingService.departTrip(1L))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("SOAT_NOT_VALID");
        verifyNoInteractions(ticketRepository, configService);
        assertThat(trip.getDepartedAt()).isNull();
    }

    // ---------- Conductor asignado ----------

    @Test
    void shouldDepartTrip_WithDriverNotAssigned_ThrowForbiddenDriverNotAssigned() {
        // Given
        authenticate("other.driver@test.com", "DRIVER");
        trip.setStatus(Trip.TripStatus.BOARDING);
        assignment.setDriver(User.builder().id(5L).email("driver@test.com").build());
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(assignmentRepository.findByTripId(1L)).thenReturn(Optional.of(assignment));

        // When/Then
        assertThatThrownBy(() -> boardingService.departTrip(1L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(be.getCode()).isEqualTo("DRIVER_NOT_ASSIGNED");
                });
        assertThat(trip.getStatus()).isEqualTo(Trip.TripStatus.BOARDING);
        verify(tripRepository, never()).save(any());
        verifyNoInteractions(ticketRepository, configService);
    }

    @Test
    void shouldDepartTrip_WithDriverRoleAndAssignmentWithoutDriver_ThrowForbidden() {
        // Given
        authenticate("driver@test.com", "DRIVER");
        trip.setStatus(Trip.TripStatus.BOARDING);
        assignment.setDriver(null);
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(assignmentRepository.findByTripId(1L)).thenReturn(Optional.of(assignment));

        // When/Then
        assertThatThrownBy(() -> boardingService.departTrip(1L))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("DRIVER_NOT_ASSIGNED");
    }

    @Test
    void shouldDepartTrip_WithAssignedDriver_IgnoreEmailCase() {
        // Given
        authenticate("Driver@Test.com", "DRIVER");
        trip.setStatus(Trip.TripStatus.BOARDING);
        assignment.setDriver(User.builder().id(5L).email("driver@test.com").build());
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(assignmentRepository.findByTripId(1L)).thenReturn(Optional.of(assignment));
        when(tripRepository.save(trip)).thenReturn(trip);
        when(tripMapper.toResponse(trip)).thenReturn(tripResponse);

        // When
        TripResponse result = boardingService.departTrip(1L);

        // Then
        assertThat(result).isSameAs(tripResponse);
        assertThat(trip.getStatus()).isEqualTo(Trip.TripStatus.DEPARTED);
    }

    @Test
    void shouldDepartTrip_WithDispatcherRole_NotRequireDriverAssignment() {
        // Given: un DISPATCHER no tiene la restricción de conductor asignado
        authenticate("dispatcher@test.com", "DISPATCHER");
        trip.setStatus(Trip.TripStatus.BOARDING);
        assignment.setDriver(User.builder().id(5L).email("driver@test.com").build());
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(assignmentRepository.findByTripId(1L)).thenReturn(Optional.of(assignment));
        when(tripRepository.save(trip)).thenReturn(trip);
        when(tripMapper.toResponse(trip)).thenReturn(tripResponse);

        // When
        boardingService.departTrip(1L);

        // Then
        assertThat(trip.getStatus()).isEqualTo(Trip.TripStatus.DEPARTED);
    }

    // ---------- Llegada ----------

    @Test
    void shouldArriveTrip_WithNonExistentTrip_ThrowResourceNotFound() {
        // Given
        when(tripRepository.findById(99L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> boardingService.arriveTrip(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");
        verifyNoInteractions(assignmentRepository);
    }

    @ParameterizedTest
    @EnumSource(value = Trip.TripStatus.class, names = {"SCHEDULED", "BOARDING", "ARRIVED", "CANCELLED"})
    void shouldArriveTrip_WithTripNotDeparted_ThrowUnprocessableEntity(Trip.TripStatus status) {
        // Given
        trip.setStatus(status);
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));

        // When/Then
        assertThatThrownBy(() -> boardingService.arriveTrip(1L))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("DEPARTED")
                .extracting("status").isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        verifyNoInteractions(assignmentRepository);
        verify(tripRepository, never()).save(any());
        assertThat(trip.getArrivedAt()).isNull();
    }

    @Test
    void shouldArriveTrip_WithoutAssignment_ThrowNoAssignment() {
        // Given
        trip.setStatus(Trip.TripStatus.DEPARTED);
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(assignmentRepository.findByTripId(1L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> boardingService.arriveTrip(1L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(be.getCode()).isEqualTo("NO_ASSIGNMENT");
                });
        verify(tripRepository, never()).save(any());
    }

    @Test
    void shouldArriveTrip_WithDriverNotAssigned_ThrowForbidden() {
        // Given
        authenticate("other.driver@test.com", "DRIVER");
        trip.setStatus(Trip.TripStatus.DEPARTED);
        assignment.setDriver(User.builder().id(5L).email("driver@test.com").build());
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(assignmentRepository.findByTripId(1L)).thenReturn(Optional.of(assignment));

        // When/Then
        assertThatThrownBy(() -> boardingService.arriveTrip(1L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(be.getCode()).isEqualTo("DRIVER_NOT_ASSIGNED");
                });
        assertThat(trip.getStatus()).isEqualTo(Trip.TripStatus.DEPARTED);
        assertThat(trip.getArrivedAt()).isNull();
        verify(tripRepository, never()).save(any());
    }

    @Test
    void shouldArriveTrip_WithAssignedDriver_SetArrivedStatusAndTime() {
        // Given
        authenticate("driver@test.com", "DRIVER");
        trip.setStatus(Trip.TripStatus.DEPARTED);
        assignment.setDriver(User.builder().id(5L).email("driver@test.com").build());
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(assignmentRepository.findByTripId(1L)).thenReturn(Optional.of(assignment));
        when(tripRepository.save(trip)).thenReturn(trip);
        when(tripMapper.toResponse(trip)).thenReturn(tripResponse);
        LocalDateTime before = LocalDateTime.now();

        // When
        TripResponse result = boardingService.arriveTrip(1L);

        // Then
        assertThat(result).isSameAs(tripResponse);
        verify(tripRepository).save(argThat(t -> t.getStatus() == Trip.TripStatus.ARRIVED));
        assertThat(trip.getArrivedAt()).isNotNull().isBetween(before, LocalDateTime.now());
        verifyNoInteractions(ticketRepository, configService);
    }

    @Test
    void shouldArriveTrip_WithoutAuthentication_NotRequireDriverAssignment() {
        // Given: sin usuario autenticado (p. ej. proceso interno) no aplica la restricción de DRIVER
        trip.setStatus(Trip.TripStatus.DEPARTED);
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(assignmentRepository.findByTripId(1L)).thenReturn(Optional.of(assignment));
        when(tripRepository.save(trip)).thenReturn(trip);
        when(tripMapper.toResponse(trip)).thenReturn(tripResponse);

        // When
        boardingService.arriveTrip(1L);

        // Then
        assertThat(trip.getStatus()).isEqualTo(Trip.TripStatus.ARRIVED);
        assertThat(trip.getArrivedAt()).isNotNull();
    }
}

