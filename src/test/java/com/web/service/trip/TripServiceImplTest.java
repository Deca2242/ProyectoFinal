package com.web.service.trip;

import com.web.dto.trip.SeatStatusResponse;
import com.web.dto.trip.TripCreateRequest;
import com.web.dto.trip.TripDetailResponse;
import com.web.dto.trip.TripResponse;
import com.web.dto.trip.mapper.TripMapper;
import com.web.entity.Bus;
import com.web.entity.Route;
import com.web.entity.Stop;
import com.web.entity.Trip;
import com.web.exception.BusinessException;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

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

    @Test
    void shouldUpdateTripStatus_WithValidStatus_UpdateTrip() {
        // Given
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(tripRepository.save(any(Trip.class))).thenReturn(trip);
        when(tripMapper.toResponse(any(Trip.class))).thenReturn(tripResponse);

        // When
        TripResponse result = tripService.updateTripStatus(1L, Trip.TripStatus.BOARDING);

        // Then
        assertThat(result).isNotNull();
        verify(tripRepository).save(argThat(t -> 
            t.getStatus() == Trip.TripStatus.BOARDING
        ));
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

