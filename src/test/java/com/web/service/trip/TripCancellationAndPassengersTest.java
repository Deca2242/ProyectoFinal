package com.web.service.trip;

import com.web.dto.ticket.TicketResponse;
import com.web.dto.ticket.mapper.TicketMapper;
import com.web.dto.trip.mapper.TripMapper;
import com.web.entity.Route;
import com.web.entity.SeatHold;
import com.web.entity.Stop;
import com.web.entity.Ticket;
import com.web.entity.Trip;
import com.web.exception.BusinessException;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

// Cancelación de viajes (cancela tickets y libera holds) y lista de pasajeros por tramo
@ExtendWith(MockitoExtension.class)
class TripCancellationAndPassengersTest {

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
    private TripMapper tripMapper;
    @Mock
    private TicketMapper ticketMapper;

    @InjectMocks
    private TripServiceImpl tripService;

    private Route route;
    private Trip trip;
    private Ticket sold1;
    private Ticket sold2;
    private SeatHold hold;

    @BeforeEach
    void setUp() {
        route = Route.builder().id(1L).build();
        trip = Trip.builder().id(1L).route(route).status(Trip.TripStatus.SCHEDULED).build();
        sold1 = Ticket.builder().id(1L).status(Ticket.TicketStatus.SOLD).build();
        sold2 = Ticket.builder().id(2L).status(Ticket.TicketStatus.SOLD).build();
        hold = SeatHold.builder().id(1L).status(SeatHold.HoldStatus.HOLD).build();
    }

    @Test
    void shouldCancelTrip_CancelSoldTicketsAndExpireHolds() {
        // Given
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(ticketRepository.findByTripIdAndStatus(1L, Ticket.TicketStatus.SOLD)).thenReturn(List.of(sold1, sold2));
        when(seatHoldRepository.findActiveHoldsByTrip(eq(1L), any(LocalDateTime.class))).thenReturn(List.of(hold));

        // When
        tripService.cancelTrip(1L);

        // Then
        assertThat(trip.getStatus()).isEqualTo(Trip.TripStatus.CANCELLED);
        assertThat(sold1.getStatus()).isEqualTo(Ticket.TicketStatus.CANCELLED);
        assertThat(sold2.getStatus()).isEqualTo(Ticket.TicketStatus.CANCELLED);
        assertThat(hold.getStatus()).isEqualTo(SeatHold.HoldStatus.EXPIRED);
        verify(ticketRepository).saveAll(List.of(sold1, sold2));
        verify(seatHoldRepository).saveAll(List.of(hold));
        verify(tripRepository).save(trip);
    }

    @Test
    void shouldCancelTrip_WithAlreadyCancelled_ThrowInvalidCancel() {
        // Given
        trip.setStatus(Trip.TripStatus.CANCELLED);
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));

        // When/Then
        assertThatThrownBy(() -> tripService.cancelTrip(1L))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("INVALID_CANCEL");
        verifyNoInteractions(ticketRepository, seatHoldRepository);
    }

    @Test
    void shouldCancelTrip_WithDepartedTrip_NotTouchTickets() {
        // Given
        trip.setStatus(Trip.TripStatus.DEPARTED);
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));

        // When/Then
        assertThatThrownBy(() -> tripService.cancelTrip(1L)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(ticketRepository, seatHoldRepository);
    }

    @Test
    void shouldUpdateTripStatus_ToCancelled_CancelSoldTickets() {
        // Given
        trip.setStatus(Trip.TripStatus.BOARDING);
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(ticketRepository.findByTripIdAndStatus(1L, Ticket.TicketStatus.SOLD)).thenReturn(List.of(sold1));
        when(seatHoldRepository.findActiveHoldsByTrip(eq(1L), any(LocalDateTime.class))).thenReturn(List.of());
        when(tripRepository.save(trip)).thenReturn(trip);

        // When
        tripService.updateTripStatus(1L, Trip.TripStatus.CANCELLED);

        // Then
        assertThat(sold1.getStatus()).isEqualTo(Ticket.TicketStatus.CANCELLED);
        assertThat(trip.getStatus()).isEqualTo(Trip.TripStatus.CANCELLED);
    }

    @Test
    void shouldUpdateTripStatus_ToArrived_NotTouchTickets() {
        // Given
        trip.setStatus(Trip.TripStatus.DEPARTED);
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(tripRepository.save(trip)).thenReturn(trip);

        // When
        tripService.updateTripStatus(1L, Trip.TripStatus.ARRIVED);

        // Then
        verifyNoInteractions(ticketRepository, seatHoldRepository);
    }

    @Test
    void shouldGetPassengersBySegment_QueryByStopOrder() {
        // Given
        Stop from = Stop.builder().id(10L).route(route).order(2).build();
        Stop to = Stop.builder().id(11L).route(route).order(4).build();
        List<TicketResponse> responses = List.of(mock(TicketResponse.class));
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(stopRepository.findById(10L)).thenReturn(Optional.of(from));
        when(stopRepository.findById(11L)).thenReturn(Optional.of(to));
        when(ticketRepository.findTicketsBySegment(1L, 2, 4)).thenReturn(List.of(sold1));
        when(ticketMapper.toResponseList(anyList())).thenReturn(responses);

        // When
        List<TicketResponse> result = tripService.getPassengersBySegment(1L, 10L, 11L);

        // Then
        assertThat(result).isSameAs(responses);
        verify(ticketRepository).findTicketsBySegment(1L, 2, 4);
    }

    @Test
    void shouldGetPassengersBySegment_WithReverseOrder_ThrowInvalidSegment() {
        // Given
        Stop from = Stop.builder().id(10L).route(route).order(4).build();
        Stop to = Stop.builder().id(11L).route(route).order(2).build();
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(stopRepository.findById(10L)).thenReturn(Optional.of(from));
        when(stopRepository.findById(11L)).thenReturn(Optional.of(to));

        // When/Then
        assertThatThrownBy(() -> tripService.getPassengersBySegment(1L, 10L, 11L))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("INVALID_SEGMENT");
        verifyNoInteractions(ticketRepository);
    }
}
