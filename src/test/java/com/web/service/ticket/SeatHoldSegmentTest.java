package com.web.service.ticket;

import com.web.dto.ticket.reservations.SeatHoldRequest;
import com.web.dto.ticket.reservations.SeatHoldResponse;
import com.web.dto.ticket.reservations.mapper.SeatHoldMapper;
import com.web.entity.Bus;
import com.web.entity.Route;
import com.web.entity.SeatHold;
import com.web.entity.Stop;
import com.web.entity.Trip;
import com.web.entity.User;
import com.web.exception.InvalidSegmentException;
import com.web.exception.ResourceNotFoundException;
import com.web.exception.SeatNotAvailableException;
import com.web.repository.SeatHoldRepository;
import com.web.repository.StopRepository;
import com.web.repository.TicketRepository;
import com.web.repository.TripRepository;
import com.web.repository.UserRepository;
import com.web.service.admin.ConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

// Holds de asiento por tramo (fromStopId / toStopId de SeatHoldRequest)
@ExtendWith(MockitoExtension.class)
class SeatHoldSegmentTest {

    @Mock
    private SeatHoldRepository seatHoldRepository;
    @Mock
    private TicketRepository ticketRepository;
    @Mock
    private TripRepository tripRepository;
    @Mock
    private StopRepository stopRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private SeatHoldMapper seatHoldMapper;
    @Mock
    private ConfigService configService;

    @InjectMocks
    private SeatHoldServiceImpl seatHoldService;

    private Route route;
    private Trip trip;
    private User user;
    private Stop stopA;
    private Stop stopB;
    private Stop stopC;

    @BeforeEach
    void setUp() {
        route = Route.builder().id(1L).build();
        Bus bus = Bus.builder().id(1L).capacity(40).build();
        trip = Trip.builder()
                .id(1L)
                .route(route)
                .bus(bus)
                .departureTime(LocalDateTime.now().plusDays(1))
                .status(Trip.TripStatus.SCHEDULED)
                .build();
        user = User.builder().id(1L).build();
        stopA = Stop.builder().id(10L).route(route).order(1).build();
        stopB = Stop.builder().id(11L).route(route).order(2).build();
        stopC = Stop.builder().id(12L).route(route).order(3).build();
    }

    private void givenTripAndStops(Stop from, Stop to) {
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(stopRepository.findById(from.getId())).thenReturn(Optional.of(from));
        when(stopRepository.findById(to.getId())).thenReturn(Optional.of(to));
    }

    @Test
    void shouldCreateHold_ForSegment_SaveStopsAndCheckSegmentAvailability() {
        // Given
        givenTripAndStops(stopB, stopC);
        SeatHoldResponse response = mock(SeatHoldResponse.class);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(seatHoldRepository.findOverlappingActiveHolds(eq(1L), eq(5), eq(2), eq(3), any(LocalDateTime.class)))
                .thenReturn(List.of());
        when(ticketRepository.isSeatAvailableForSegment(1L, 5, 2, 3)).thenReturn(true);
        when(configService.getHoldDurationMinutes()).thenReturn(10);
        when(seatHoldRepository.save(any(SeatHold.class))).thenAnswer(inv -> inv.getArgument(0));
        when(seatHoldMapper.toResponse(any(SeatHold.class))).thenReturn(response);

        // When
        SeatHoldResponse result = seatHoldService.createHold(1L, 5, new SeatHoldRequest(1L, 11L, 12L));

        // Then
        assertThat(result).isSameAs(response);
        verify(seatHoldRepository).save(argThat(h -> h.getFromStop() == stopB && h.getToStop() == stopC
                && h.getStatus() == SeatHold.HoldStatus.HOLD && h.getSeatNumber() == 5));
        // El asiento puede estar vendido en otro tramo: ya no se exige que esté libre en todo el viaje
        verify(ticketRepository, never()).isSeatAvailableForFullTrip(any(), any());
    }

    @Test
    void shouldCreateHold_WithSeatSoldInSegment_ThrowSeatNotAvailable() {
        // Given
        givenTripAndStops(stopA, stopB);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(seatHoldRepository.findOverlappingActiveHolds(eq(1L), eq(5), eq(1), eq(2), any(LocalDateTime.class)))
                .thenReturn(List.of());
        when(ticketRepository.isSeatAvailableForSegment(1L, 5, 1, 2)).thenReturn(false);

        // When/Then
        assertThatThrownBy(() -> seatHoldService.createHold(1L, 5, new SeatHoldRequest(1L, 10L, 11L)))
                .isInstanceOf(SeatNotAvailableException.class)
                .hasMessageContaining("vendido");
        verify(seatHoldRepository, never()).save(any());
    }

    @Test
    void shouldCreateHold_WithOverlappingHoldOfOtherUser_ThrowSeatNotAvailable() {
        // Given
        givenTripAndStops(stopA, stopC);
        SeatHold other = SeatHold.builder().id(3L).user(User.builder().id(2L).build())
                .expiresAt(LocalDateTime.now().plusMinutes(5)).build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(seatHoldRepository.findOverlappingActiveHolds(eq(1L), eq(5), eq(1), eq(3), any(LocalDateTime.class)))
                .thenReturn(List.of(other));

        // When/Then
        assertThatThrownBy(() -> seatHoldService.createHold(1L, 5, new SeatHoldRequest(1L, 10L, 12L)))
                .isInstanceOf(SeatNotAvailableException.class)
                .hasMessageContaining("hold activo");
    }

    @Test
    void shouldCreateHold_WithOwnHoldOnSameSegment_ReturnExistingHold() {
        // Given
        givenTripAndStops(stopA, stopB);
        SeatHold own = SeatHold.builder().id(3L).user(user).fromStop(stopA).toStop(stopB).build();
        SeatHoldResponse response = mock(SeatHoldResponse.class);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(seatHoldRepository.findOverlappingActiveHolds(eq(1L), eq(5), eq(1), eq(2), any(LocalDateTime.class)))
                .thenReturn(List.of(own));
        when(seatHoldMapper.toResponse(own)).thenReturn(response);

        // When
        SeatHoldResponse result = seatHoldService.createHold(1L, 5, new SeatHoldRequest(1L, 10L, 11L));

        // Then
        assertThat(result).isSameAs(response);
        verify(seatHoldRepository, never()).save(any());
    }

    @Test
    void shouldCreateHold_WithOwnHoldOnOtherOverlappingSegment_ReplaceIt() {
        // Given
        givenTripAndStops(stopA, stopC);
        SeatHold own = SeatHold.builder().id(3L).user(user).fromStop(stopA).toStop(stopB)
                .status(SeatHold.HoldStatus.HOLD).build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(seatHoldRepository.findOverlappingActiveHolds(eq(1L), eq(5), eq(1), eq(3), any(LocalDateTime.class)))
                .thenReturn(List.of(own));
        when(ticketRepository.isSeatAvailableForSegment(1L, 5, 1, 3)).thenReturn(true);
        when(configService.getHoldDurationMinutes()).thenReturn(10);
        when(seatHoldRepository.save(any(SeatHold.class))).thenAnswer(inv -> inv.getArgument(0));

        // When
        seatHoldService.createHold(1L, 5, new SeatHoldRequest(1L, 10L, 12L));

        // Then: el hold anterior queda expirado y se crea uno nuevo para A -> C
        assertThat(own.getStatus()).isEqualTo(SeatHold.HoldStatus.EXPIRED);
        verify(seatHoldRepository, times(2)).save(any(SeatHold.class));
    }

    @Test
    void shouldCreateHold_WithStopsInReverseOrder_ThrowInvalidSegment() {
        // Given
        givenTripAndStops(stopC, stopA);

        // When/Then
        assertThatThrownBy(() -> seatHoldService.createHold(1L, 5, new SeatHoldRequest(1L, 12L, 10L)))
                .isInstanceOf(InvalidSegmentException.class);
        verifyNoInteractions(seatHoldRepository);
    }

    @Test
    void shouldCreateHold_WithStopOfOtherRoute_ThrowInvalidSegment() {
        // Given
        Stop foreign = Stop.builder().id(20L).route(Route.builder().id(2L).build()).order(2).build();
        givenTripAndStops(stopA, foreign);

        // When/Then
        assertThatThrownBy(() -> seatHoldService.createHold(1L, 5, new SeatHoldRequest(1L, 10L, 20L)))
                .isInstanceOf(InvalidSegmentException.class);
    }

    @Test
    void shouldCreateHold_WithUnknownTrip_ThrowNotFound() {
        // Given
        when(tripRepository.findById(1L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> seatHoldService.createHold(1L, 5, new SeatHoldRequest(1L, 10L, 11L)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void shouldCreateHold_WithUnknownStop_ThrowNotFound() {
        // Given
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(stopRepository.findById(10L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> seatHoldService.createHold(1L, 5, new SeatHoldRequest(1L, 10L, 11L)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void shouldHasActiveHold_WithNoHolds_ReturnFalse() {
        // Given
        when(seatHoldRepository.findActiveHolds(eq(1L), eq(5), any(LocalDateTime.class))).thenReturn(List.of());

        // When/Then
        assertThat(seatHoldService.hasActiveHold(1L, 5)).isFalse();
    }
}
