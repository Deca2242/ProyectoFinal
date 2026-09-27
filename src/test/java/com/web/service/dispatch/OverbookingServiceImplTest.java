package com.web.service.dispatch;

import com.web.dto.dispatch.OverbookingApprovalResponse;
import com.web.entity.Bus;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

// Aprobación de sillas de overbooking por el DISPATCHER (ocupación > 95 % y menos de 30 min para salir)
@ExtendWith(MockitoExtension.class)
class OverbookingServiceImplTest {

    @Mock
    private TripRepository tripRepository;
    @Mock
    private TicketRepository ticketRepository;
    @Mock
    private IncidentRepository incidentRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private ConfigService configService;

    @InjectMocks
    private OverbookingServiceImpl overbookingService;

    private Bus bus;
    private Trip trip;

    @BeforeEach
    void setUp() {
        bus = Bus.builder().id(1L).plate("ABC123").capacity(40).build();
        trip = Trip.builder()
                .id(1L)
                .bus(bus)
                .status(Trip.TripStatus.BOARDING)
                .departureTime(LocalDateTime.now().plusMinutes(20))
                .overbookingApprovedSeats(0)
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(String username, String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                username, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    // ---------- Validaciones previas ----------

    @Test
    void shouldApproveExtraSeat_WithNonExistentTrip_ThrowResourceNotFoundAfterLocking() {
        // Given
        when(tripRepository.findById(99L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> overbookingService.approveExtraSeat(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");
        verify(tripRepository).lockById(99L);
        verifyNoInteractions(ticketRepository, incidentRepository, configService);
    }

    @Test
    void shouldApproveExtraSeat_LockTripBeforeReadingIt() {
        // Given
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(ticketRepository.countSoldSeats(1L)).thenReturn(40L);
        when(configService.getOverbookingMaxPercentage()).thenReturn(0.05);

        // When
        overbookingService.approveExtraSeat(1L);

        // Then: el bloqueo (SELECT ... FOR UPDATE) va antes de la lectura del viaje
        InOrder inOrder = inOrder(tripRepository);
        inOrder.verify(tripRepository).lockById(1L);
        inOrder.verify(tripRepository).findById(1L);
        inOrder.verify(tripRepository).save(trip);
    }

    @ParameterizedTest
    @EnumSource(value = Trip.TripStatus.class, names = {"DEPARTED", "ARRIVED", "CANCELLED"})
    void shouldApproveExtraSeat_WithTripNotOpenForSales_ThrowTripNotAvailable(Trip.TripStatus status) {
        // Given
        trip.setStatus(status);
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));

        // When/Then
        assertThatThrownBy(() -> overbookingService.approveExtraSeat(1L))
                .isInstanceOf(BusinessException.class)
                .isNotInstanceOf(OverbookingNotAllowedException.class)
                .hasMessageContaining(status.name())
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(be.getCode()).isEqualTo("TRIP_NOT_AVAILABLE");
                });
        verify(tripRepository, never()).save(any());
        verifyNoInteractions(ticketRepository, incidentRepository);
    }

    @ParameterizedTest
    @EnumSource(value = Trip.TripStatus.class, names = {"SCHEDULED", "BOARDING"})
    void shouldApproveExtraSeat_WithScheduledOrBoardingTrip_Approve(Trip.TripStatus status) {
        // Given
        trip.setStatus(status);
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(ticketRepository.countSoldSeats(1L)).thenReturn(39L);
        when(configService.getOverbookingMaxPercentage()).thenReturn(0.05);

        // When
        OverbookingApprovalResponse response = overbookingService.approveExtraSeat(1L);

        // Then
        assertThat(response.approvedExtraSeats()).isEqualTo(1);
    }

    @Test
    void shouldApproveExtraSeat_WithDepartureInThePast_ThrowTripAlreadyDeparted() {
        // Given: el viaje sigue en BOARDING pero su hora de salida ya pasó
        trip.setDepartureTime(LocalDateTime.now().minusMinutes(1));
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));

        // When/Then
        assertThatThrownBy(() -> overbookingService.approveExtraSeat(1L))
                .isInstanceOf(BusinessException.class)
                .isNotInstanceOf(OverbookingNotAllowedException.class)
                .extracting("code").isEqualTo("TRIP_ALREADY_DEPARTED");
        verify(tripRepository, never()).save(any());
        verifyNoInteractions(ticketRepository);
    }

    @ParameterizedTest
    @ValueSource(longs = {31, 45, 120, 60 * 24})
    void shouldApproveExtraSeat_WithThirtyMinutesOrMoreToDeparture_ThrowForbidden(long minutes) {
        // Given
        trip.setDepartureTime(LocalDateTime.now().plusMinutes(minutes));
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));

        // When/Then
        assertThatThrownBy(() -> overbookingService.approveExtraSeat(1L))
                .isInstanceOf(OverbookingNotAllowedException.class)
                .hasMessageContaining("30 minutos")
                .satisfies(ex -> {
                    OverbookingNotAllowedException oe = (OverbookingNotAllowedException) ex;
                    assertThat(oe.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(oe.getCode()).isEqualTo("OVERBOOKING_NOT_ALLOWED");
                });
        verifyNoInteractions(ticketRepository, incidentRepository);
    }

    @Test
    void shouldApproveExtraSeat_WithExactlyThirtyMinutesToDeparture_ThrowForbidden() {
        // Given: 30 minutos y unos segundos (toMinutes = 30) no cumple "menos de 30"
        trip.setDepartureTime(LocalDateTime.now().plusMinutes(30).plusSeconds(20));
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));

        // When/Then
        assertThatThrownBy(() -> overbookingService.approveExtraSeat(1L))
                .isInstanceOf(OverbookingNotAllowedException.class);
    }

    @Test
    void shouldApproveExtraSeat_WithLessThanThirtyMinutesToDeparture_Approve() {
        // Given: 29 minutos para la salida
        trip.setDepartureTime(LocalDateTime.now().plusMinutes(29).plusSeconds(30));
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(ticketRepository.countSoldSeats(1L)).thenReturn(40L);
        when(configService.getOverbookingMaxPercentage()).thenReturn(0.05);

        // When
        OverbookingApprovalResponse response = overbookingService.approveExtraSeat(1L);

        // Then
        assertThat(response.approvedSeatNumber()).isEqualTo(41);
    }

    // ---------- Ocupación ----------

    @ParameterizedTest
    @ValueSource(longs = {0, 20, 37, 38})
    void shouldApproveExtraSeat_WithOccupancyAtMostNinetyFivePercent_ThrowForbidden(long soldSeats) {
        // Given: 38/40 = 95 % exacto no supera el umbral
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(ticketRepository.countSoldSeats(1L)).thenReturn(soldSeats);

        // When/Then
        assertThatThrownBy(() -> overbookingService.approveExtraSeat(1L))
                .isInstanceOf(OverbookingNotAllowedException.class)
                .hasMessageContaining("95");
        verify(tripRepository, never()).save(any());
        verifyNoInteractions(configService, incidentRepository);
    }

    @Test
    void shouldApproveExtraSeat_WithZeroCapacityBus_ThrowForbiddenWithoutDividingByZero() {
        // Given
        bus.setCapacity(0);
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(ticketRepository.countSoldSeats(1L)).thenReturn(0L);

        // When/Then
        assertThatThrownBy(() -> overbookingService.approveExtraSeat(1L))
                .isInstanceOf(OverbookingNotAllowedException.class);
        verifyNoInteractions(configService);
    }

    // ---------- Máximo de overbooking ----------

    @Test
    void shouldApproveExtraSeat_WithMaximumReached_ThrowForbidden() {
        // Given: 5 % de 40 = 2 sillas, ya aprobadas
        trip.setOverbookingApprovedSeats(2);
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(ticketRepository.countSoldSeats(1L)).thenReturn(42L);
        when(configService.getOverbookingMaxPercentage()).thenReturn(0.05);

        // When/Then
        assertThatThrownBy(() -> overbookingService.approveExtraSeat(1L))
                .isInstanceOf(OverbookingNotAllowedException.class)
                .hasMessageContaining("máximo")
                .hasMessageContaining("2 sillas");
        assertThat(trip.getOverbookingApprovedSeats()).isEqualTo(2);
        verify(tripRepository, never()).save(any());
        verifyNoInteractions(incidentRepository);
    }

    @Test
    void shouldApproveExtraSeat_WithZeroPercentConfigured_ThrowForbidden() {
        // Given: overbooking deshabilitado (0 %)
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(ticketRepository.countSoldSeats(1L)).thenReturn(40L);
        when(configService.getOverbookingMaxPercentage()).thenReturn(0.0);

        // When/Then
        assertThatThrownBy(() -> overbookingService.approveExtraSeat(1L))
                .isInstanceOf(OverbookingNotAllowedException.class)
                .hasMessageContaining("0 sillas");
        verify(tripRepository, never()).save(any());
    }

    @Test
    void shouldApproveExtraSeat_WithFloatingPointPercentage_RoundMaximumCorrectly() {
        // Given: 100 * 0.29 = 28.999999999999996 en double; el máximo debe ser 29, no 28
        bus.setCapacity(100);
        trip.setOverbookingApprovedSeats(28);
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(ticketRepository.countSoldSeats(1L)).thenReturn(128L);
        when(configService.getOverbookingMaxPercentage()).thenReturn(0.29);

        // When
        OverbookingApprovalResponse response = overbookingService.approveExtraSeat(1L);

        // Then
        assertThat(response.maxExtraSeats()).isEqualTo(29);
        assertThat(response.approvedExtraSeats()).isEqualTo(29);
        assertThat(response.approvedSeatNumber()).isEqualTo(129);
    }

    @Test
    void shouldApproveExtraSeat_WithFloatingPointPercentageAndMaximumReached_ThrowForbidden() {
        // Given: con 29 aprobadas de un máximo de 29 ya no se puede aprobar otra
        bus.setCapacity(100);
        trip.setOverbookingApprovedSeats(29);
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(ticketRepository.countSoldSeats(1L)).thenReturn(129L);
        when(configService.getOverbookingMaxPercentage()).thenReturn(0.29);

        // When/Then
        assertThatThrownBy(() -> overbookingService.approveExtraSeat(1L))
                .isInstanceOf(OverbookingNotAllowedException.class)
                .hasMessageContaining("29 sillas");
    }

    @Test
    void shouldApproveExtraSeat_WithNullApprovedSeats_TreatAsZero() {
        // Given
        trip.setOverbookingApprovedSeats(null);
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(ticketRepository.countSoldSeats(1L)).thenReturn(39L);
        when(configService.getOverbookingMaxPercentage()).thenReturn(0.05);

        // When
        OverbookingApprovalResponse response = overbookingService.approveExtraSeat(1L);

        // Then
        assertThat(trip.getOverbookingApprovedSeats()).isEqualTo(1);
        assertThat(response.approvedSeatNumber()).isEqualTo(41);
    }

    // ---------- Aprobación ----------

    @Test
    void shouldApproveExtraSeat_WithValidConditions_IncrementSeatsAndReturnResponse() {
        // Given: 40 de 40 vendidas y una silla extra ya aprobada; máximo 10 % = 4
        trip.setOverbookingApprovedSeats(1);
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(ticketRepository.countSoldSeats(1L)).thenReturn(41L);
        when(configService.getOverbookingMaxPercentage()).thenReturn(0.10);

        // When
        OverbookingApprovalResponse response = overbookingService.approveExtraSeat(1L);

        // Then
        assertThat(trip.getOverbookingApprovedSeats()).isEqualTo(2);
        verify(tripRepository).save(argThat(t -> t.getOverbookingApprovedSeats() == 2));
        assertThat(response.tripId()).isEqualTo(1L);
        assertThat(response.capacity()).isEqualTo(40);
        assertThat(response.soldSeats()).isEqualTo(41L);
        assertThat(response.occupancyPercentage()).isCloseTo(102.5, within(1e-9));
        assertThat(response.approvedExtraSeats()).isEqualTo(2);
        assertThat(response.maxExtraSeats()).isEqualTo(4);
        // La silla aprobada es capacity + aprobadas
        assertThat(response.approvedSeatNumber()).isEqualTo(42);
    }

    @Test
    void shouldApproveExtraSeat_WithAuthenticatedDispatcher_RegisterOverbookIncidentReportedByHim() {
        // Given
        authenticate("dispatcher@test.com", "DISPATCHER");
        User dispatcher = User.builder().id(7L).email("dispatcher@test.com").build();
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(ticketRepository.countSoldSeats(1L)).thenReturn(39L);
        when(configService.getOverbookingMaxPercentage()).thenReturn(0.05);
        when(userRepository.findByEmail("dispatcher@test.com")).thenReturn(Optional.of(dispatcher));

        // When
        overbookingService.approveExtraSeat(1L);

        // Then
        ArgumentCaptor<Incident> captor = ArgumentCaptor.forClass(Incident.class);
        verify(incidentRepository).save(captor.capture());
        Incident incident = captor.getValue();
        assertThat(incident.getEntityType()).isEqualTo(Incident.EntityType.TRIP);
        assertThat(incident.getEntityId()).isEqualTo(1L);
        assertThat(incident.getIncidentType()).isEqualTo(Incident.IncidentType.OVERBOOK);
        assertThat(incident.getReportedBy()).isSameAs(dispatcher);
        assertThat(incident.getDescription()).contains("silla 41").contains("(1/2)");
        assertThat(incident.getCreatedAt()).isNotNull();
    }

    @Test
    void shouldApproveExtraSeat_WithAuthenticatedUserNotFound_RegisterIncidentWithoutReporter() {
        // Given
        authenticate("ghost@test.com", "DISPATCHER");
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(ticketRepository.countSoldSeats(1L)).thenReturn(39L);
        when(configService.getOverbookingMaxPercentage()).thenReturn(0.05);
        when(userRepository.findByEmail("ghost@test.com")).thenReturn(Optional.empty());

        // When
        overbookingService.approveExtraSeat(1L);

        // Then
        verify(incidentRepository).save(argThat(i -> i.getReportedBy() == null
                && i.getIncidentType() == Incident.IncidentType.OVERBOOK));
    }

    @Test
    void shouldApproveExtraSeat_WithoutAuthentication_RegisterIncidentWithoutReporter() {
        // Given: sin contexto de seguridad no se consulta el usuario
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(ticketRepository.countSoldSeats(1L)).thenReturn(39L);
        when(configService.getOverbookingMaxPercentage()).thenReturn(0.05);

        // When
        overbookingService.approveExtraSeat(1L);

        // Then
        verify(incidentRepository).save(argThat(i -> i.getReportedBy() == null));
        verifyNoInteractions(userRepository);
    }
}
