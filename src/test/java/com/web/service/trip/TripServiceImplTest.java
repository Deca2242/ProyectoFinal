package com.web.service.trip;

import com.web.dto.trip.SeatStatusResponse;
import com.web.dto.trip.TripCreateRequest;
import com.web.dto.trip.TripDetailResponse;
import com.web.dto.trip.TripResponse;
import com.web.dto.trip.mapper.TripMapper;
import com.web.entity.Bus;
import com.web.entity.Route;
import com.web.entity.Stop;
import com.web.entity.Ticket;
import com.web.entity.Trip;
import com.web.exception.BusinessException;
import com.web.exception.InvalidStateTransitionException;
import com.web.exception.ResourceNotFoundException;
import com.web.repository.BusRepository;
import com.web.repository.RouteRepository;
import com.web.repository.SeatHoldRepository;
import com.web.repository.StopRepository;
import com.web.repository.TicketRepository;
import com.web.repository.TripRepository;
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
                Trip.TripStatus.SCHEDULED, 0, 0.0
        );

        tripDetailResponse = new TripDetailResponse(
                1L, null, null,
                LocalDate.now().plusDays(1), LocalDateTime.now().plusDays(1).plusHours(8), null,
                Trip.TripStatus.SCHEDULED, null, 0, 40, 0.0, List.of()
        );
    }

    @Test
    void shouldCreateTrip_WithValidRequest_ReturnTripResponse() {
        // Given
        TripCreateRequest request = new TripCreateRequest(
                1L, 1L, LocalDate.now().plusDays(1),
                LocalDateTime.now().plusDays(1).plusHours(8),
                LocalDateTime.now().plusDays(1).plusHours(12)
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

        when(tripRepository.findByRouteIdAndTripDate(1L, LocalDate.now().plusDays(1)))
                .thenReturn(trips);
        when(tripMapper.toResponseList(trips)).thenReturn(responses);

        // When
        List<TripResponse> result = tripService.searchTrips(1L, LocalDate.now().plusDays(1));

        // Then
        assertThat(result).isNotNull();
        assertThat(result).hasSize(1);
        verify(tripRepository).findByRouteIdAndTripDate(1L, LocalDate.now().plusDays(1));
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
        when(ticketRepository.isSeatAvailableForSegment(anyLong(), anyInt(), anyInt(), anyInt()))
                .thenReturn(true);

        // When
        List<SeatStatusResponse> result = tripService.getSeatAvailability(1L, 1L, 2L);

        // Then
        assertThat(result).isNotNull();
        assertThat(result).hasSize(40); // Bus capacity
        verify(ticketRepository, times(40)).isSeatAvailableForSegment(anyLong(), anyInt(), anyInt(), anyInt());
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
    void shouldSearchTrips_WithOnlyRouteId_FilterByRoute() {
        // Given
        Trip otherRouteTrip = Trip.builder()
                .id(2L)
                .route(Route.builder().id(2L).build())
                .tripDate(LocalDate.now().plusDays(1))
                .build();
        when(tripRepository.findAll()).thenReturn(List.of(trip, otherRouteTrip));
        when(tripMapper.toResponseList(List.of(trip))).thenReturn(List.of(tripResponse));

        // When
        List<TripResponse> result = tripService.searchTrips(1L, null);

        // Then
        assertThat(result).containsExactly(tripResponse);
        verify(tripRepository, never()).findByRouteIdAndTripDate(any(), any());
    }

    @Test
    void shouldSearchTrips_WithOnlyDate_FilterByDate() {
        // Given
        LocalDate date = LocalDate.now().plusDays(1);
        Trip otherDateTrip = Trip.builder()
                .id(2L)
                .route(route)
                .tripDate(date.plusDays(3))
                .build();
        when(tripRepository.findAll()).thenReturn(List.of(otherDateTrip, trip));
        when(tripMapper.toResponseList(List.of(trip))).thenReturn(List.of(tripResponse));

        // When
        List<TripResponse> result = tripService.searchTrips(null, date);

        // Then
        assertThat(result).containsExactly(tripResponse);
        verify(tripRepository, never()).findByRouteIdAndTripDate(any(), any());
    }

    @Test
    void shouldSearchTrips_WithoutFilters_ReturnAllTrips() {
        // Given
        List<Trip> trips = List.of(trip);
        when(tripRepository.findAll()).thenReturn(trips);
        when(tripMapper.toResponseList(trips)).thenReturn(List.of(tripResponse));

        // When
        List<TripResponse> result = tripService.searchTrips(null, null);

        // Then
        assertThat(result).hasSize(1);
        verify(tripRepository).findAll();
        verify(tripRepository, never()).findByRouteIdAndTripDate(any(), any());
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
        // Given: bus de 4 asientos con los asientos 1 y 3 vendidos
        bus.setCapacity(4);
        when(tripRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(trip));
        when(tripMapper.toDetailResponse(trip)).thenReturn(tripDetailResponse);
        when(ticketRepository.countSoldSeats(1L)).thenReturn(2L);
        when(ticketRepository.existsByTripIdAndSeatNumberAndStatus(eq(1L), anyInt(), eq(Ticket.TicketStatus.SOLD)))
                .thenAnswer(inv -> Set.of(1, 3).contains(inv.<Integer>getArgument(1)));

        // When
        TripDetailResponse result = tripService.getTripById(1L);

        // Then
        assertThat(result.id()).isEqualTo(1L);
        assertThat(result.status()).isEqualTo(Trip.TripStatus.SCHEDULED);
        assertThat(result.soldSeats()).isEqualTo(2);
        assertThat(result.availableSeats()).isEqualTo(2);
        assertThat(result.occupancyPercentage()).isEqualTo(50.0);
        assertThat(result.availableSeatNumbers()).containsExactly(2, 4);
        verify(ticketRepository, times(4))
                .existsByTripIdAndSeatNumberAndStatus(eq(1L), anyInt(), eq(Ticket.TicketStatus.SOLD));
    }

    @Test
    void shouldGetTripById_WithZeroCapacityBus_ReturnZeroOccupancyWithoutDivision() {
        // Given
        bus.setCapacity(0);
        when(tripRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(trip));
        when(tripMapper.toDetailResponse(trip)).thenReturn(tripDetailResponse);
        when(ticketRepository.countSoldSeats(1L)).thenReturn(0L);

        // When
        TripDetailResponse result = tripService.getTripById(1L);

        // Then
        assertThat(result.occupancyPercentage()).isEqualTo(0.0);
        assertThat(result.availableSeats()).isZero();
        assertThat(result.availableSeatNumbers()).isEmpty();
        verify(ticketRepository, never()).existsByTripIdAndSeatNumberAndStatus(anyLong(), anyInt(), any());
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
        when(ticketRepository.isSeatAvailableForSegment(eq(1L), anyInt(), eq(2), eq(3)))
                .thenAnswer(inv -> inv.<Integer>getArgument(1) != 2);

        // When
        List<SeatStatusResponse> result = tripService.getSeatAvailability(1L, 5L, 8L);

        // Then
        assertThat(result).containsExactly(
                new SeatStatusResponse(1, true, "AVAILABLE"),
                new SeatStatusResponse(2, false, "OCCUPIED"),
                new SeatStatusResponse(3, true, "AVAILABLE"));
        verify(ticketRepository, never()).isSeatAvailableForSegment(anyLong(), anyInt(), eq(5), eq(8));
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
                LocalDateTime.now().plusDays(1).plusHours(8),
                LocalDateTime.now().plusDays(1).plusHours(12)
        );
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

