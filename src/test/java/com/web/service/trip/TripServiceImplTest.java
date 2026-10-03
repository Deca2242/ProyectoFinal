package com.web.service.trip;

import com.web.dto.trip.SeatAvailabilityResponse;
import com.web.dto.trip.SeatStatusResponse;
import com.web.dto.trip.SegmentOccupancyResponse;
import com.web.dto.trip.TripCreateRequest;
import com.web.dto.trip.TripDetailResponse;
import com.web.dto.trip.TripResponse;
import com.web.dto.trip.mapper.TripMapper;
import com.web.entity.Bus;
import com.web.entity.Route;
import com.web.entity.Seat;
import com.web.entity.SeatHold;
import com.web.entity.Stop;
import com.web.entity.Ticket;
import com.web.entity.Trip;
import com.web.exception.BusinessException;
import com.web.exception.InvalidStateTransitionException;
import com.web.exception.ResourceNotFoundException;
import com.web.repository.BusRepository;
import com.web.repository.RouteRepository;
import com.web.repository.SeatHoldRepository;
import com.web.repository.SeatRepository;
import com.web.repository.StopRepository;
import com.web.repository.TicketRepository;
import com.web.repository.TripRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;


@ExtendWith(MockitoExtension.class)
class TripServiceImplTest {

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
    private TripMapper tripMapper;

    @Mock
    private SeatHoldRepository seatHoldRepository;
    @Mock
    private SeatRepository seatRepository;

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
    private TripResponse tripResponse;
    private TripDetailResponse tripDetailResponse;

    @BeforeEach
    void setUp() {
        route = Route.builder()
                .id(1L)
                .code("R001")
                .name("Bogotá - Medellín")
                .build();

        bus = Bus.builder()
                .id(1L)
                .plate("ABC123")
                .capacity(40)
                .status(Bus.BusStatus.ACTIVE)
                .build();

        trip = Trip.builder()
                .id(1L)
                .route(route)
                .bus(bus)
                .tripDate(LocalDate.now().plusDays(1))
                .departureTime(LocalDateTime.now().plusDays(1).plusHours(8))
                .status(Trip.TripStatus.SCHEDULED)
                .build();

        tripResponse = new TripResponse(
                1L, 1L, "Bogotá - Medellín", "Bogotá", "Medellín",
                1L, "ABC123", 40,
                LocalDate.now().plusDays(1), LocalDateTime.now().plusDays(1).plusHours(8), null,
                Trip.TripStatus.SCHEDULED, 0, 0.0, null, null, null, null
        );

        tripDetailResponse = new TripDetailResponse(
                1L, null, null,
                LocalDate.now().plusDays(1), LocalDateTime.now().plusDays(1).plusHours(8), null,
                Trip.TripStatus.SCHEDULED, null, 0, 40, 0.0, List.of(), null, null, null
        );
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldCreateTrip_WithValidRequest_ReturnTripResponse() {
        // Given
        TripCreateRequest request = new TripCreateRequest(
                1L, 1L, LocalDate.now().plusDays(1),
                LocalDate.now().plusDays(1).atTime(8, 0),
                LocalDate.now().plusDays(1).atTime(12, 0)
        );

        when(routeRepository.findById(1L)).thenReturn(Optional.of(route));
        when(busRepository.findById(1L)).thenReturn(Optional.of(bus));
        when(tripMapper.toEntity(request)).thenReturn(trip);
        when(tripRepository.save(any(Trip.class))).thenAnswer(inv -> {
            Trip t = inv.getArgument(0);
            t.setId(1L);
            return t;
        });
        when(tripMapper.toResponse(any(Trip.class))).thenReturn(tripResponse);

        // When
        TripResponse result = tripService.createTrip(request);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.id()).isEqualTo(1L);
        verify(routeRepository).findById(1L);
        verify(busRepository).findById(1L);
        verify(tripRepository).save(any(Trip.class));
    }

    @Test
    void shouldSearchTrips_WithRouteId_ReturnFilteredList() {
        // Given
        List<Trip> trips = List.of(trip);
        List<TripResponse> responses = List.of(tripResponse);

        when(tripRepository.searchTrips(eq(1L), eq(LocalDate.now().plusDays(1)), eq(false), any(LocalDateTime.class)))
                .thenReturn(trips);
        when(tripMapper.toResponseList(trips)).thenReturn(responses);

        // When
        List<TripResponse> result = tripService.searchTrips(1L, LocalDate.now().plusDays(1), false);

        // Then
        assertThat(result).isNotNull();
        assertThat(result).hasSize(1);
        verify(tripRepository).searchTrips(eq(1L), eq(LocalDate.now().plusDays(1)), eq(false), any(LocalDateTime.class));
    }
    @Test
    void shouldGetTripById_WithValidId_ReturnTripDetail() {
        // Given
        when(tripRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(trip));
        when(tripMapper.toDetailResponse(trip)).thenReturn(tripDetailResponse);

        // When
        TripDetailResponse result = tripService.getTripById(1L);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.id()).isEqualTo(1L);
        verify(tripRepository).findByIdWithDetails(1L);
    }

    @Test
    void shouldGetSeatAvailability_WithValidStops_ReturnSeatStatusList() {
        // Given
        Stop fromStop = Stop.builder()
                .id(1L)
                .route(route)
                .order(1)
                .build();

        Stop toStop = Stop.builder()
                .id(2L)
                .route(route)
                .order(2)
                .build();

        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(stopRepository.findById(1L)).thenReturn(Optional.of(fromStop));
        when(stopRepository.findById(2L)).thenReturn(Optional.of(toStop));
        when(ticketRepository.findTicketsBySegment(1L, 1, 2)).thenReturn(List.of());

        // When
        SeatAvailabilityResponse result = tripService.getSeatAvailability(1L, 1L, 2L);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.tripId()).isEqualTo(1L);
        assertThat(result.fromStopId()).isEqualTo(1L);
        assertThat(result.toStopId()).isEqualTo(2L);
        assertThat(result.totalSeats()).isEqualTo(40); // Bus capacity
        assertThat(result.availableSeats()).isEqualTo(40);
        assertThat(result.seats()).hasSize(40);
        // Sin N+1: una sola consulta de tickets para todo el mapa
        verify(ticketRepository).findTicketsBySegment(1L, 1, 2);
        verify(ticketRepository, never()).isSeatAvailableForSegment(anyLong(), anyInt(), anyInt(), anyInt());
    }
    @Test
    void shouldGetSeatAvailability_WithApprovedOverbooking_IncludeExtraSeats() {
        // Given: 2 sillas de overbooking aprobadas => se listan las sillas 1..42
        trip.setOverbookingApprovedSeats(2);
        Stop fromStop = Stop.builder().id(1L).route(route).order(1).build();
        Stop toStop = Stop.builder().id(2L).route(route).order(2).build();
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(stopRepository.findById(1L)).thenReturn(Optional.of(fromStop));
        when(stopRepository.findById(2L)).thenReturn(Optional.of(toStop));
        when(ticketRepository.findTicketsBySegment(1L, 1, 2)).thenReturn(List.of());

        // When
        SeatAvailabilityResponse result = tripService.getSeatAvailability(1L, 1L, 2L);

        // Then
        assertThat(result.totalSeats()).isEqualTo(42);
        assertThat(result.seats()).hasSize(42);
        assertThat(result.seats().get(41).seatNumber()).isEqualTo(42);
    }

    @ParameterizedTest
    @EnumSource(value = Trip.TripStatus.class, names = {"CANCELLED", "ARRIVED"})
    void shouldGetSeatAvailability_OnCancelledTrip_Throw422(Trip.TripStatus status) {
        // Given
        trip.setStatus(status);
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));

        // When/Then
        assertThatThrownBy(() -> tripService.getSeatAvailability(1L, 1L, 2L))
                .isInstanceOf(InvalidStateTransitionException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        verifyNoInteractions(stopRepository, ticketRepository, seatHoldRepository, seatRepository);
    }

    @Test
    void shouldGetSeatAvailability_ExposeSeatType() {
        // Given: bus de 3 sillas con la 2 preferencial; la 3 no tiene fila en seats (STANDARD por defecto)
        bus.setCapacity(3);
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(stopRepository.findById(1L)).thenReturn(Optional.of(buildStop(1L, route, 1)));
        when(stopRepository.findById(2L)).thenReturn(Optional.of(buildStop(2L, route, 2)));
        when(ticketRepository.findTicketsBySegment(1L, 1, 2)).thenReturn(List.of());
        when(seatRepository.findByBusIdOrderBySeatNumberAsc(1L)).thenReturn(List.of(
                Seat.builder().bus(bus).seatNumber(1).seatType(Seat.SeatType.STANDARD).build(),
                Seat.builder().bus(bus).seatNumber(2).seatType(Seat.SeatType.PREFERENTIAL).build()));

        // When
        SeatAvailabilityResponse result = tripService.getSeatAvailability(1L, 1L, 2L);

        // Then
        assertThat(result.seats()).extracting(SeatStatusResponse::seatType)
                .containsExactly("STANDARD", "PREFERENTIAL", "STANDARD");
    }

    @Test
    void shouldGetSeatAvailability_WithOverlappingHold_MarkSeatHeld() {
        // Given: hold activo en la silla 1 para el tramo 1→3 (se solapa con 1→2)
        bus.setCapacity(2);
        SeatHold hold = SeatHold.builder()
                .seatNumber(1)
                .fromStop(buildStop(1L, route, 1))
                .toStop(buildStop(3L, route, 3))
                .build();
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(stopRepository.findById(1L)).thenReturn(Optional.of(buildStop(1L, route, 1)));
        when(stopRepository.findById(2L)).thenReturn(Optional.of(buildStop(2L, route, 2)));
        when(ticketRepository.findTicketsBySegment(1L, 1, 2)).thenReturn(List.of());
        when(seatHoldRepository.findActiveHoldsByTrip(eq(1L), any(LocalDateTime.class))).thenReturn(List.of(hold));

        // When
        SeatAvailabilityResponse result = tripService.getSeatAvailability(1L, 1L, 2L);

        // Then
        assertThat(result.availableSeats()).isEqualTo(1);
        assertThat(result.seats()).containsExactly(
                new SeatStatusResponse(1, false, "HELD", "STANDARD"),
                new SeatStatusResponse(2, true, "AVAILABLE", "STANDARD"));
    }

    // ==================== getOccupancyBySegment ====================

    @Test
    void shouldGetOccupancyBySegment_CountEachConsecutiveSegment() {
        // Given: ruta A(1) → B(2) → C(3) y bus de 40 sillas
        Stop a = Stop.builder().id(10L).route(route).name("A").order(1).build();
        Stop b = Stop.builder().id(11L).route(route).name("B").order(2).build();
        Stop c = Stop.builder().id(12L).route(route).name("C").order(3).build();
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(stopRepository.findByRouteIdOrderByOrderAsc(1L)).thenReturn(List.of(a, b, c));
        when(ticketRepository.countSoldSeatsForSegment(1L, 1, 2)).thenReturn(2L);
        when(ticketRepository.countSoldSeatsForSegment(1L, 2, 3)).thenReturn(10L);

        // When
        List<SegmentOccupancyResponse> result = tripService.getOccupancyBySegment(1L);

        // Then
        assertThat(result).containsExactly(
                new SegmentOccupancyResponse(10L, "A", 11L, "B", 2, 40, 5.0),
                new SegmentOccupancyResponse(11L, "B", 12L, "C", 10, 40, 25.0));
    }

    @Test
    void shouldGetOccupancyBySegment_WithNonExistentTrip_ThrowResourceNotFound() {
        // Given
        when(tripRepository.findById(99L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> tripService.getOccupancyBySegment(99L))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(stopRepository, ticketRepository);
    }
    @Test
    void shouldGetTripById_WithOverbookingSold_ListNoAvailableSeatNumbers() {
        // Given: las 41 sillas vendibles (40 + 1 aprobada) están vendidas
        trip.setOverbookingApprovedSeats(1);
        when(tripRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(trip));
        when(tripMapper.toDetailResponse(trip)).thenReturn(tripDetailResponse);
        when(ticketRepository.findByTripIdAndStatus(1L, Ticket.TicketStatus.SOLD))
                .thenReturn(java.util.stream.IntStream.rangeClosed(1, 41)
                        .mapToObj(n -> Ticket.builder().seatNumber(n).build())
                        .toList());

        // When
        TripDetailResponse result = tripService.getTripById(1L);

        // Then
        assertThat(result.availableSeatNumbers()).isEmpty();
        // Sin N+1: una sola consulta de tickets vendidos
        verify(ticketRepository, never()).existsByTripIdAndSeatNumberAndStatus(anyLong(), anyInt(), any());
    }
    // ==================== createTrip ====================

    @Test
    void shouldCreateTrip_WithNonExistentRoute_ThrowResourceNotFound() {
        // Given
        TripCreateRequest request = buildTripRequest();
        when(routeRepository.findById(1L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> tripService.createTrip(request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Ruta");
        verifyNoInteractions(busRepository, tripRepository);
    }

    @Test
    void shouldCreateTrip_WithNonExistentBus_ThrowResourceNotFound() {
        // Given
        TripCreateRequest request = buildTripRequest();
        when(routeRepository.findById(1L)).thenReturn(Optional.of(route));
        when(busRepository.findById(1L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> tripService.createTrip(request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Bus");
        verifyNoInteractions(tripRepository);
    }

    @ParameterizedTest
    @EnumSource(value = Bus.BusStatus.class, names = {"MAINTENANCE", "RETIRED"})
    void shouldCreateTrip_WithBusNotActive_ThrowBusNotAvailable(Bus.BusStatus status) {
        // Given
        bus.setStatus(status);
        TripCreateRequest request = buildTripRequest();
        when(routeRepository.findById(1L)).thenReturn(Optional.of(route));
        when(busRepository.findById(1L)).thenReturn(Optional.of(bus));

        // When/Then
        assertThatThrownBy(() -> tripService.createTrip(request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo("BUS_NOT_AVAILABLE");
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
        verifyNoInteractions(tripMapper, tripRepository);
    }

    @Test
    void shouldCreateTrip_WithValidRequest_SetRouteAndBusOnEntity() {
        // Given: el mapper devuelve una entidad sin relaciones
        TripCreateRequest request = buildTripRequest();
        Trip mapped = Trip.builder()
                .tripDate(request.tripDate())
                .departureTime(request.departureTime())
                .build();
        when(routeRepository.findById(1L)).thenReturn(Optional.of(route));
        when(busRepository.findById(1L)).thenReturn(Optional.of(bus));
        when(tripMapper.toEntity(request)).thenReturn(mapped);
        when(tripRepository.save(any(Trip.class))).thenAnswer(inv -> inv.getArgument(0));
        when(tripMapper.toResponse(any(Trip.class))).thenReturn(tripResponse);

        // When
        tripService.createTrip(request);

        // Then
        ArgumentCaptor<Trip> captor = ArgumentCaptor.forClass(Trip.class);
        verify(tripRepository).save(captor.capture());
        assertThat(captor.getValue().getRoute()).isSameAs(route);
        assertThat(captor.getValue().getBus()).isSameAs(bus);
        assertThat(captor.getValue().getStatus()).isEqualTo(Trip.TripStatus.SCHEDULED);
    }

    // ==================== searchTrips ====================

    @Test
    void shouldSearchTrips_ExcludeCancelledArrivedAndPastDepartures() {
        // Given: el filtro de salidas reservables lo aplica la consulta (includeAll = false)
        when(tripRepository.searchTrips(eq(1L), isNull(), eq(false), any(LocalDateTime.class))).thenReturn(List.of(trip));
        when(tripMapper.toResponseList(List.of(trip))).thenReturn(List.of(tripResponse));
        LocalDateTime before = LocalDateTime.now();

        // When
        List<TripResponse> result = tripService.searchTrips(1L, null, false);

        // Then: se compara contra la hora actual para excluir las salidas pasadas
        assertThat(result).containsExactly(tripResponse);
        ArgumentCaptor<LocalDateTime> nowCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(tripRepository).searchTrips(eq(1L), isNull(), eq(false), nowCaptor.capture());
        assertThat(nowCaptor.getValue()).isAfterOrEqualTo(before).isBeforeOrEqualTo(LocalDateTime.now());
    }

    @ParameterizedTest
    @CsvSource({"PASSENGER", "CLERK", "DRIVER"})
    void shouldSearchTrips_WithIncludeAllFromNonStaffRole_IgnoreIt(String role) {
        // Given
        authenticateAs(role);
        LocalDate date = LocalDate.now().plusDays(1);
        when(tripRepository.searchTrips(isNull(), eq(date), eq(false), any(LocalDateTime.class))).thenReturn(List.of());
        when(tripMapper.toResponseList(List.of())).thenReturn(List.of());

        // When
        tripService.searchTrips(null, date, true);

        // Then
        verify(tripRepository).searchTrips(isNull(), eq(date), eq(false), any(LocalDateTime.class));
    }

    @ParameterizedTest
    @CsvSource({"ADMIN", "DISPATCHER"})
    void shouldSearchTrips_WithIncludeAllFromStaff_ReturnAllTrips(String role) {
        // Given
        authenticateAs(role);
        List<Trip> trips = List.of(trip);
        when(tripRepository.searchTrips(eq(1L), isNull(), eq(true), any(LocalDateTime.class))).thenReturn(trips);
        when(tripMapper.toResponseList(trips)).thenReturn(List.of(tripResponse));

        // When
        List<TripResponse> result = tripService.searchTrips(1L, null, true);

        // Then
        assertThat(result).hasSize(1);
        verify(tripRepository).searchTrips(eq(1L), isNull(), eq(true), any(LocalDateTime.class));
    }
    // ==================== getTripById ====================

    @Test
    void shouldGetTripById_WithNonExistentId_ThrowResourceNotFound() {
        // Given
        when(tripRepository.findByIdWithDetails(99L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> tripService.getTripById(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Viaje");
        verifyNoInteractions(tripMapper, ticketRepository);
    }

    @Test
    void shouldGetTripById_WithSoldSeats_CalculateOccupancyAndAvailableSeatNumbers() {
        // Given: bus de 4 asientos con los asientos 1 y 3 vendidos (la ocupación la calcula el mapper)
        bus.setCapacity(4);
        TripDetailResponse mapped = new TripDetailResponse(
                1L, null, null,
                LocalDate.now().plusDays(1), LocalDateTime.now().plusDays(1).plusHours(8), null,
                Trip.TripStatus.SCHEDULED, null, 2, 2, 50.0, null, "A3", null, null);
        when(tripRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(trip));
        when(tripMapper.toDetailResponse(trip)).thenReturn(mapped);
        when(ticketRepository.findByTripIdAndStatus(1L, Ticket.TicketStatus.SOLD)).thenReturn(List.of(
                Ticket.builder().seatNumber(1).build(),
                Ticket.builder().seatNumber(3).build()));

        // When
        TripDetailResponse result = tripService.getTripById(1L);

        // Then
        assertThat(result.id()).isEqualTo(1L);
        assertThat(result.status()).isEqualTo(Trip.TripStatus.SCHEDULED);
        assertThat(result.soldSeats()).isEqualTo(2);
        assertThat(result.availableSeats()).isEqualTo(2);
        assertThat(result.occupancyPercentage()).isEqualTo(50.0);
        assertThat(result.platform()).isEqualTo("A3");
        // Disponibles para el viaje completo
        assertThat(result.availableSeatNumbers()).containsExactly(2, 4);
    }
    @Test
    void shouldGetTripById_WithZeroCapacityBus_ReturnNoAvailableSeatNumbers() {
        // Given
        bus.setCapacity(0);
        when(tripRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(trip));
        when(tripMapper.toDetailResponse(trip)).thenReturn(tripDetailResponse);

        // When
        TripDetailResponse result = tripService.getTripById(1L);

        // Then
        assertThat(result.availableSeatNumbers()).isEmpty();
    }
    // ==================== getSeatAvailability ====================

    @Test
    void shouldGetSeatAvailability_WithNonExistentTrip_ThrowResourceNotFound() {
        // Given
        when(tripRepository.findById(99L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> tripService.getSeatAvailability(99L, 1L, 2L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Viaje");
        verifyNoInteractions(stopRepository, ticketRepository);
    }

    @Test
    void shouldGetSeatAvailability_WithNonExistentFromStop_ThrowResourceNotFound() {
        // Given
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(stopRepository.findById(1L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> tripService.getSeatAvailability(1L, 1L, 2L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Parada origen");
        verifyNoInteractions(ticketRepository);
    }

    @Test
    void shouldGetSeatAvailability_WithNonExistentToStop_ThrowResourceNotFound() {
        // Given
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(stopRepository.findById(1L)).thenReturn(Optional.of(buildStop(1L, route, 1)));
        when(stopRepository.findById(2L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> tripService.getSeatAvailability(1L, 1L, 2L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Parada destino");
        verifyNoInteractions(ticketRepository);
    }

    @ParameterizedTest
    @CsvSource({"99, 1", "1, 99"})
    void shouldGetSeatAvailability_WithStopsFromAnotherRoute_ThrowInvalidStops(long fromRouteId, long toRouteId) {
        // Given
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(stopRepository.findById(1L))
                .thenReturn(Optional.of(buildStop(1L, Route.builder().id(fromRouteId).build(), 1)));
        when(stopRepository.findById(2L))
                .thenReturn(Optional.of(buildStop(2L, Route.builder().id(toRouteId).build(), 2)));

        // When/Then
        assertThatThrownBy(() -> tripService.getSeatAvailability(1L, 1L, 2L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo("INVALID_STOPS");
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
        verifyNoInteractions(ticketRepository);
    }

    @ParameterizedTest
    @CsvSource({"2, 2", "3, 1"})
    void shouldGetSeatAvailability_WithOriginNotBeforeDestination_ThrowInvalidSegment(int fromOrder, int toOrder) {
        // Given
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(stopRepository.findById(1L)).thenReturn(Optional.of(buildStop(1L, route, fromOrder)));
        when(stopRepository.findById(2L)).thenReturn(Optional.of(buildStop(2L, route, toOrder)));

        // When/Then
        assertThatThrownBy(() -> tripService.getSeatAvailability(1L, 1L, 2L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo("INVALID_SEGMENT"));
        verifyNoInteractions(ticketRepository);
    }

    @Test
    void shouldGetSeatAvailability_WithOccupiedSeat_MarkItOccupiedUsingStopOrders() {
        // Given: IDs de parada (5, 8) distintos de su orden (2, 3) y bus de 3 asientos con el 2 ocupado
        bus.setCapacity(3);
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(stopRepository.findById(5L)).thenReturn(Optional.of(buildStop(5L, route, 2)));
        when(stopRepository.findById(8L)).thenReturn(Optional.of(buildStop(8L, route, 3)));
        when(ticketRepository.findTicketsBySegment(1L, 2, 3))
                .thenReturn(List.of(Ticket.builder().seatNumber(2).build()));

        // When
        SeatAvailabilityResponse result = tripService.getSeatAvailability(1L, 5L, 8L);

        // Then
        assertThat(result.availableSeats()).isEqualTo(2);
        assertThat(result.seats()).containsExactly(
                new SeatStatusResponse(1, true, "AVAILABLE", "STANDARD"),
                new SeatStatusResponse(2, false, "OCCUPIED", "STANDARD"),
                new SeatStatusResponse(3, true, "AVAILABLE", "STANDARD"));
        verify(ticketRepository, never()).findTicketsBySegment(anyLong(), eq(5), eq(8));
    }
    // ==================== updateTripStatus ====================

    @Test
    void shouldUpdateTripStatus_WithNonExistentTrip_ThrowResourceNotFound() {
        // Given
        when(tripRepository.findById(99L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> tripService.updateTripStatus(99L, Trip.TripStatus.BOARDING))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Viaje");
        verify(tripRepository, never()).save(any());
    }

    @ParameterizedTest
    @CsvSource({
            "SCHEDULED, CANCELLED",
            "BOARDING, CANCELLED",
            "DEPARTED, ARRIVED"
    })
    void shouldUpdateTripStatus_WithAllowedTransition_SaveNewStatus(Trip.TripStatus current, Trip.TripStatus target) {
        // Given
        trip.setStatus(current);
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(tripRepository.save(any(Trip.class))).thenAnswer(inv -> inv.getArgument(0));
        when(tripMapper.toResponse(any(Trip.class))).thenReturn(tripResponse);

        // When
        TripResponse result = tripService.updateTripStatus(1L, target);

        // Then
        assertThat(result).isEqualTo(tripResponse);
        verify(tripRepository).save(argThat(t -> t.getStatus() == target));
    }

    @ParameterizedTest
    @CsvSource({
            "SCHEDULED, SCHEDULED",
            "SCHEDULED, DEPARTED",
            "SCHEDULED, ARRIVED",
            "BOARDING, SCHEDULED",
            "BOARDING, BOARDING",
            "BOARDING, ARRIVED",
            "DEPARTED, SCHEDULED",
            "DEPARTED, BOARDING",
            "DEPARTED, DEPARTED",
            "DEPARTED, CANCELLED",
            "ARRIVED, SCHEDULED",
            "ARRIVED, CANCELLED",
            "CANCELLED, SCHEDULED",
            "CANCELLED, BOARDING"
    })
    void shouldUpdateTripStatus_WithForbiddenTransition_ThrowInvalidStateTransition(Trip.TripStatus current,
                                                                                    Trip.TripStatus target) {
        // Given
        trip.setStatus(current);
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));

        // When/Then: 422 según la tabla de errores estándar
        assertThatThrownBy(() -> tripService.updateTripStatus(1L, target))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("'" + current + "'")
                .hasMessageContaining("'" + target + "'")
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo("INVALID_STATE_TRANSITION");
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                });
        assertThat(trip.getStatus()).isEqualTo(current);
        verify(tripRepository, never()).save(any());
        verifyNoInteractions(tripMapper);
    }

    private TripCreateRequest buildTripRequest() {
        return new TripCreateRequest(
                1L, 1L, LocalDate.now().plusDays(1),
                LocalDate.now().plusDays(1).atTime(8, 0),
                LocalDate.now().plusDays(1).atTime(12, 0)
        );
    }

    private void authenticateAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "user@test.com", null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private Stop buildStop(Long id, Route stopRoute, int order) {
        return Stop.builder()
                .id(id)
                .route(stopRoute)
                .order(order)
                .build();
    }

    @Test
    void shouldUpdateTripStatus_WithValidStatus_UpdateTrip() {
        // Given
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(tripRepository.save(any(Trip.class))).thenReturn(trip);
        when(tripMapper.toResponse(any(Trip.class))).thenReturn(tripResponse);

        // When
        TripResponse result = tripService.updateTripStatus(1L, Trip.TripStatus.CANCELLED);

        // Then
        assertThat(result).isNotNull();
        verify(tripRepository).save(argThat(t ->
            t.getStatus() == Trip.TripStatus.CANCELLED
        ));
    }

    @ParameterizedTest
    @CsvSource({
            "SCHEDULED, BOARDING",
            "BOARDING, DEPARTED"
    })
    void shouldUpdateTripStatus_ToBoardingOrDeparted_RequireDispatchEndpoints(Trip.TripStatus current,
                                                                              Trip.TripStatus target) {
        // Given: son transiciones válidas, pero con validaciones propias (asignación, checklist, no-show)
        trip.setStatus(current);
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));

        // When/Then
        assertThatThrownBy(() -> tripService.updateTripStatus(1L, target))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("despacho");
        verify(tripRepository, never()).save(any());
    }

    @Test
    void shouldUpdateTripStatus_ToArrived_SetArrivedAt() {
        // Given
        trip.setStatus(Trip.TripStatus.DEPARTED);
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(tripRepository.save(any(Trip.class))).thenAnswer(inv -> inv.getArgument(0));

        // When
        tripService.updateTripStatus(1L, Trip.TripStatus.ARRIVED);

        // Then
        assertThat(trip.getArrivedAt()).isNotNull();
    }

    @Test
    void shouldCancelTrip_WithValidId_UpdateStatus() {
        // Given
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(tripRepository.save(any(Trip.class))).thenReturn(trip);

        // When
        tripService.cancelTrip(1L);

        // Then
        verify(tripRepository).save(argThat(t -> 
            t.getStatus() == Trip.TripStatus.CANCELLED
        ));
    }
}

