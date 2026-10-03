package com.web.service.trip;

import com.web.dto.ticket.TicketResponse;
import com.web.dto.ticket.mapper.TicketMapper;
import com.web.dto.trip.TripCreateRequest;
import com.web.dto.trip.TripResponse;
import com.web.dto.trip.mapper.TripMapper;
import com.web.entity.Assignment;
import com.web.entity.Bus;
import com.web.entity.Route;
import com.web.entity.Stop;
import com.web.entity.Ticket;
import com.web.entity.Trip;
import com.web.entity.User;
import com.web.exception.BusinessException;
import com.web.repository.AssignmentRepository;
import com.web.repository.BusRepository;
import com.web.repository.RouteRepository;
import com.web.repository.SeatHoldRepository;
import com.web.repository.StopRepository;
import com.web.repository.TicketRepository;
import com.web.repository.TripRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

// Validaciones de creación de viajes, reembolso al cancelar y lista de pasajeros restringida al conductor asignado
@ExtendWith(MockitoExtension.class)
class TripCreationAndDriverRulesTest {

    private static final LocalDate TRIP_DATE = LocalDate.of(2026, 12, 20);

    @Mock
    private TripRepository tripRepository;
    @Mock
    private RouteRepository routeRepository;
    @Mock
    private BusRepository busRepository;
    @Mock
    private StopRepository stopRepository;
    @Mock
    private TicketRepository ticketRepository;
    @Mock
    private SeatHoldRepository seatHoldRepository;
    @Mock
    private AssignmentRepository assignmentRepository;
    @Mock
    private TripMapper tripMapper;
    @Mock
    private TicketMapper ticketMapper;

    @Mock
    private com.web.repository.ParcelRepository parcelRepository;
    @Mock
    private com.web.repository.IncidentRepository incidentRepository;

    @Mock
    private com.web.service.notification.NotificationService notificationService;

    @InjectMocks
    private TripServiceImpl tripService;

    private Route route;
    private Bus bus;
    private Trip trip;

    @BeforeEach
    void setUp() {
        route = Route.builder().id(1L).code("R001").name("Santa Marta - Barranquilla").isActive(true).build();
        bus = Bus.builder().id(1L).plate("ABC123").capacity(40).status(Bus.BusStatus.ACTIVE).build();
        trip = Trip.builder().id(1L).route(route).bus(bus).status(Trip.TripStatus.SCHEDULED).build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(String username, String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                username, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private TripCreateRequest validRequest() {
        return new TripCreateRequest(1L, 1L, TRIP_DATE, TRIP_DATE.atTime(8, 0), TRIP_DATE.atTime(12, 0));
    }

    private void givenRouteAndBus() {
        when(routeRepository.findById(1L)).thenReturn(Optional.of(route));
        when(busRepository.findById(1L)).thenReturn(Optional.of(bus));
    }

    private static void assertBusinessError(Throwable ex, HttpStatus status, String code) {
        assertThat(ex).isInstanceOf(BusinessException.class);
        BusinessException be = (BusinessException) ex;
        assertThat(be.getStatus()).isEqualTo(status);
        assertThat(be.getCode()).isEqualTo(code);
    }

    // ---------- createTrip ----------

    @Test
    void shouldCreateTrip_WithInactiveRoute_ThrowRouteInactive() {
        // Given
        route.setIsActive(false);
        givenRouteAndBus();

        // When/Then
        assertThatThrownBy(() -> tripService.createTrip(validRequest()))
                .satisfies(ex -> assertBusinessError(ex, HttpStatus.BAD_REQUEST, "ROUTE_INACTIVE"));
        verify(tripRepository, never()).existsOverlappingTripForBus(any(), any(), any(), any());
        verify(tripRepository, never()).save(any());
        verifyNoInteractions(tripMapper);
    }

    @Test
    void shouldCreateTrip_WithRouteActiveFlagNull_TreatAsActive() {
        // Given
        route.setIsActive(null);
        givenRouteAndBus();
        TripCreateRequest request = validRequest();
        TripResponse response = mock(TripResponse.class);
        when(tripMapper.toEntity(request)).thenReturn(trip);
        when(tripRepository.save(trip)).thenReturn(trip);
        when(tripMapper.toResponse(trip)).thenReturn(response);

        // When
        TripResponse result = tripService.createTrip(request);

        // Then
        assertThat(result).isSameAs(response);
    }

    @Test
    void shouldCreateTrip_WithDepartureOnAnotherDate_ThrowInvalidDates() {
        // Given: la fecha del viaje no coincide con el día de la salida
        givenRouteAndBus();
        TripCreateRequest request = new TripCreateRequest(1L, 1L, TRIP_DATE,
                TRIP_DATE.plusDays(1).atTime(8, 0), TRIP_DATE.plusDays(1).atTime(12, 0));

        // When/Then
        assertThatThrownBy(() -> tripService.createTrip(request))
                .satisfies(ex -> assertBusinessError(ex, HttpStatus.BAD_REQUEST, "INVALID_DATES"));
        verify(tripRepository, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, -120})
    void shouldCreateTrip_WithArrivalNotAfterDeparture_ThrowInvalidDates(long minutesAfterDeparture) {
        // Given: llegada igual o anterior a la salida
        givenRouteAndBus();
        LocalDateTime departure = TRIP_DATE.atTime(8, 0);
        TripCreateRequest request = new TripCreateRequest(1L, 1L, TRIP_DATE,
                departure, departure.plusMinutes(minutesAfterDeparture));

        // When/Then
        assertThatThrownBy(() -> tripService.createTrip(request))
                .satisfies(ex -> assertBusinessError(ex, HttpStatus.BAD_REQUEST, "INVALID_DATES"));
        verify(tripRepository, never()).existsOverlappingTripForBus(any(), any(), any(), any());
        verify(tripRepository, never()).save(any());
    }

    @Test
    void shouldCreateTrip_WithArrivalOnNextDay_Allow() {
        // Given: viaje nocturno que llega al día siguiente
        givenRouteAndBus();
        TripCreateRequest request = new TripCreateRequest(1L, 1L, TRIP_DATE,
                TRIP_DATE.atTime(22, 0), TRIP_DATE.plusDays(1).atTime(4, 0));
        when(tripMapper.toEntity(request)).thenReturn(trip);
        when(tripRepository.save(trip)).thenReturn(trip);
        when(tripMapper.toResponse(trip)).thenReturn(mock(TripResponse.class));

        // When
        tripService.createTrip(request);

        // Then
        verify(tripRepository).save(trip);
    }

    @Test
    void shouldCreateTrip_WithOverlappingHours_ThrowBusBusy() {
        // Given: el bus tiene otro viaje activo que se solapa con 08:00-12:00
        givenRouteAndBus();
        when(tripRepository.existsOverlappingTripForBus(1L, TRIP_DATE.atTime(8, 0), TRIP_DATE.atTime(12, 0), null))
                .thenReturn(true);

        // When/Then
        assertThatThrownBy(() -> tripService.createTrip(validRequest()))
                .satisfies(ex -> assertBusinessError(ex, HttpStatus.CONFLICT, "BUS_BUSY"));
        verify(tripRepository, never()).save(any());
        verifyNoInteractions(tripMapper);
    }

    @Test
    void shouldCreateTrip_WithBusArrivedEarlierSameDay_AllowSecondTrip() {
        // Given: el bus hizo un viaje en la mañana del mismo día que no se solapa con 08:00-12:00
        // (la comprobación es por franja horaria, no por día)
        givenRouteAndBus();
        TripCreateRequest request = validRequest();
        when(tripRepository.existsOverlappingTripForBus(1L, TRIP_DATE.atTime(8, 0), TRIP_DATE.atTime(12, 0), null))
                .thenReturn(false);
        when(tripMapper.toEntity(request)).thenReturn(trip);
        when(tripRepository.save(trip)).thenReturn(trip);
        when(tripMapper.toResponse(trip)).thenReturn(mock(TripResponse.class));

        // When
        tripService.createTrip(request);

        // Then
        verify(tripRepository).save(argThat(t -> t.getRoute() == route && t.getBus() == bus));
        verify(tripRepository, never()).findBusIdsWithTripsOnDate(any());
    }

    @Test
    void shouldCreateTrip_WithDepartureInThePast_ThrowInvalidDates() {
        // Given: salida hace una hora
        givenRouteAndBus();
        LocalDateTime departure = LocalDateTime.now().minusHours(1);
        TripCreateRequest request = new TripCreateRequest(1L, 1L, departure.toLocalDate(),
                departure, departure.plusHours(4));

        // When/Then
        assertThatThrownBy(() -> tripService.createTrip(request))
                .satisfies(ex -> assertBusinessError(ex, HttpStatus.BAD_REQUEST, "INVALID_DATES"));
        verify(tripRepository, never()).existsOverlappingTripForBus(any(), any(), any(), any());
        verify(tripRepository, never()).save(any());
    }

    @Test
    void shouldCreateTrip_WithoutArrivalEta_ComputeFromRouteDuration() {
        // Given: ruta de 150 minutos y petición sin llegada
        route.setDurationMin(150);
        givenRouteAndBus();
        TripCreateRequest request = new TripCreateRequest(1L, 1L, TRIP_DATE, TRIP_DATE.atTime(8, 0), null);
        Trip mapped = Trip.builder().tripDate(TRIP_DATE).departureTime(TRIP_DATE.atTime(8, 0)).build();
        when(tripMapper.toEntity(request)).thenReturn(mapped);
        when(tripRepository.save(mapped)).thenReturn(mapped);
        when(tripMapper.toResponse(mapped)).thenReturn(mock(TripResponse.class));

        // When
        tripService.createTrip(request);

        // Then: llegada = salida + duración, y el solapamiento se valida con esa franja
        assertThat(mapped.getArrivalEta()).isEqualTo(TRIP_DATE.atTime(10, 30));
        verify(tripRepository).existsOverlappingTripForBus(1L, TRIP_DATE.atTime(8, 0), TRIP_DATE.atTime(10, 30), null);
    }

    @Test
    void shouldCreateTrip_WithoutArrivalEtaAndRouteWithoutDuration_ThrowInvalidDates() {
        // Given
        givenRouteAndBus();
        TripCreateRequest request = new TripCreateRequest(1L, 1L, TRIP_DATE, TRIP_DATE.atTime(8, 0), null);

        // When/Then
        assertThatThrownBy(() -> tripService.createTrip(request))
                .satisfies(ex -> assertBusinessError(ex, HttpStatus.BAD_REQUEST, "INVALID_DATES"));
        verify(tripRepository, never()).save(any());
    }

    // ---------- Reembolso al cancelar ----------

    @Test
    void shouldCancelTrip_SetFullRefundAndCancelledAtOnSoldTickets() {
        // Given
        Ticket t1 = Ticket.builder().id(1L).price(new BigDecimal("50000")).status(Ticket.TicketStatus.SOLD).build();
        Ticket t2 = Ticket.builder().id(2L).price(new BigDecimal("32500.50")).status(Ticket.TicketStatus.SOLD).build();
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(ticketRepository.findByTripIdAndStatus(1L, Ticket.TicketStatus.SOLD)).thenReturn(List.of(t1, t2));
        when(seatHoldRepository.findActiveHoldsByTrip(eq(1L), any(LocalDateTime.class))).thenReturn(List.of());
        LocalDateTime before = LocalDateTime.now();

        // When
        tripService.cancelTrip(1L);

        // Then: la cancelación es de la empresa, se reembolsa el precio completo
        assertThat(t1.getRefundAmount()).isEqualByComparingTo("50000");
        assertThat(t2.getRefundAmount()).isEqualByComparingTo("32500.50");
        assertThat(List.of(t1, t2)).allSatisfy(t -> {
            assertThat(t.getStatus()).isEqualTo(Ticket.TicketStatus.CANCELLED);
            assertThat(t.getCancelledAt()).isNotNull().isBetween(before, LocalDateTime.now());
        });
        verify(ticketRepository).saveAll(List.of(t1, t2));
    }

    @Test
    void shouldUpdateTripStatusToCancelled_SetFullRefundAndCancelledAtOnSoldTickets() {
        // Given
        Ticket sold = Ticket.builder().id(1L).price(new BigDecimal("45000")).status(Ticket.TicketStatus.SOLD).build();
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(ticketRepository.findByTripIdAndStatus(1L, Ticket.TicketStatus.SOLD)).thenReturn(List.of(sold));
        when(seatHoldRepository.findActiveHoldsByTrip(eq(1L), any(LocalDateTime.class))).thenReturn(List.of());
        when(tripRepository.save(trip)).thenReturn(trip);
        when(tripMapper.toResponse(trip)).thenReturn(mock(TripResponse.class));

        // When
        tripService.updateTripStatus(1L, Trip.TripStatus.CANCELLED);

        // Then
        assertThat(sold.getStatus()).isEqualTo(Ticket.TicketStatus.CANCELLED);
        assertThat(sold.getRefundAmount()).isEqualByComparingTo("45000");
        assertThat(sold.getCancelledAt()).isNotNull();
        assertThat(trip.getStatus()).isEqualTo(Trip.TripStatus.CANCELLED);
    }

    // ---------- Pasajeros por tramo ----------

    private void givenStopsOfRoute() {
        Stop from = Stop.builder().id(10L).route(route).order(1).build();
        Stop to = Stop.builder().id(11L).route(route).order(3).build();
        when(stopRepository.findById(10L)).thenReturn(Optional.of(from));
        when(stopRepository.findById(11L)).thenReturn(Optional.of(to));
    }

    private Assignment assignmentFor(String driverEmail) {
        return Assignment.builder().id(1L).trip(trip)
                .driver(driverEmail == null ? null : User.builder().id(5L).email(driverEmail).build())
                .build();
    }

    @Test
    void shouldGetPassengersBySegment_WithAssignedDriver_ReturnPassengers() {
        // Given
        authenticate("driver@test.com", "DRIVER");
        List<TicketResponse> responses = List.of(new TicketResponse(
                1L, 1L, "Ruta", null, null, 7L, "Pasajero", "pasajero@test.com", 4,
                10L, "Origen", 2, 11L, "Destino", 4, java.math.BigDecimal.TEN,
                com.web.entity.Ticket.PaymentMethod.CASH, com.web.entity.Ticket.TicketStatus.SOLD,
                "QR", null, null, null));
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(assignmentRepository.findByTripId(1L)).thenReturn(Optional.of(assignmentFor("DRIVER@test.com")));
        givenStopsOfRoute();
        when(ticketRepository.findTicketsBySegment(1L, 1, 3)).thenReturn(List.of());
        when(ticketMapper.toResponseList(anyList())).thenReturn(responses);

        // When
        List<TicketResponse> result = tripService.getPassengersBySegment(1L, 10L, 11L);

        // Then
        // La lista conserva los datos del pasajero, pero no expone su email
        assertThat(result).singleElement().satisfies(r -> {
            assertThat(r.passengerName()).isEqualTo("Pasajero");
            assertThat(r.seatNumber()).isEqualTo(4);
            assertThat(r.passengerEmail()).isNull();
        });
    }

    @Test
    void shouldGetPassengersBySegment_WithDriverNotAssigned_ThrowForbidden() {
        // Given
        authenticate("other.driver@test.com", "DRIVER");
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(assignmentRepository.findByTripId(1L)).thenReturn(Optional.of(assignmentFor("driver@test.com")));

        // When/Then
        assertThatThrownBy(() -> tripService.getPassengersBySegment(1L, 10L, 11L))
                .satisfies(ex -> assertBusinessError(ex, HttpStatus.FORBIDDEN, "DRIVER_NOT_ASSIGNED"));
        verifyNoInteractions(stopRepository, ticketRepository, ticketMapper);
    }

    @Test
    void shouldGetPassengersBySegment_WithDriverAndNoAssignment_ThrowForbidden() {
        // Given
        authenticate("driver@test.com", "DRIVER");
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(assignmentRepository.findByTripId(1L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> tripService.getPassengersBySegment(1L, 10L, 11L))
                .satisfies(ex -> assertBusinessError(ex, HttpStatus.FORBIDDEN, "DRIVER_NOT_ASSIGNED"));
    }

    @Test
    void shouldGetPassengersBySegment_WithDriverAndAssignmentWithoutDriver_ThrowForbidden() {
        // Given
        authenticate("driver@test.com", "DRIVER");
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(assignmentRepository.findByTripId(1L)).thenReturn(Optional.of(assignmentFor(null)));

        // When/Then
        assertThatThrownBy(() -> tripService.getPassengersBySegment(1L, 10L, 11L))
                .satisfies(ex -> assertBusinessError(ex, HttpStatus.FORBIDDEN, "DRIVER_NOT_ASSIGNED"));
    }

    @Test
    void shouldGetPassengersBySegment_WithDispatcher_NotCheckAssignment() {
        // Given
        authenticate("dispatcher@test.com", "DISPATCHER");
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        givenStopsOfRoute();
        when(ticketRepository.findTicketsBySegment(1L, 1, 3)).thenReturn(List.of());
        when(ticketMapper.toResponseList(anyList())).thenReturn(List.of());

        // When
        tripService.getPassengersBySegment(1L, 10L, 11L);

        // Then
        verifyNoInteractions(assignmentRepository);
    }
}
