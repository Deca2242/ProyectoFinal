package com.web.service.trip;

import com.web.dto.ticket.mapper.TicketMapper;
import com.web.dto.trip.mapper.TripMapper;
import com.web.entity.Ticket;
import com.web.entity.Trip;
import com.web.repository.*;
import com.web.service.notification.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

// Al cancelar un viaje se avisa a los pasajeros (TRIP_CANCELLED) antes de cancelar sus tickets
@ExtendWith(MockitoExtension.class)
class TripCancellationNotificationTest {

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
    private ParcelRepository parcelRepository;
    @Mock
    private IncidentRepository incidentRepository;
    @Mock
    private TripMapper tripMapper;
    @Mock
    private TicketMapper ticketMapper;
    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private TripServiceImpl tripService;

    private Trip trip;
    private Ticket ticket;

    @BeforeEach
    void setUp() {
        trip = Trip.builder().id(1L).status(Trip.TripStatus.SCHEDULED).build();
        ticket = Ticket.builder().id(10L).trip(trip).price(new BigDecimal("50000")).build();
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(ticketRepository.findByTripIdAndStatus(1L, Ticket.TicketStatus.SOLD)).thenReturn(List.of(ticket));
        when(seatHoldRepository.findActiveHoldsByTrip(any(), any())).thenReturn(List.of());
        when(parcelRepository.findByTripId(1L)).thenReturn(List.of());
    }

    @Test
    void shouldCancelTrip_NotifyPassengersBeforeCancellingTickets() {
        // Given: el ticket sigue SOLD en el momento del aviso
        doAnswer(inv -> {
            assertThat(ticket.getStatus()).isEqualTo(Ticket.TicketStatus.SOLD);
            return null;
        }).when(notificationService).notifyTripCancelled(trip);

        // When
        tripService.cancelTrip(1L);

        // Then
        InOrder inOrder = inOrder(notificationService, ticketRepository);
        inOrder.verify(notificationService).notifyTripCancelled(trip);
        inOrder.verify(ticketRepository).saveAll(List.of(ticket));
        assertThat(ticket.getStatus()).isEqualTo(Ticket.TicketStatus.CANCELLED);
        assertThat(trip.getStatus()).isEqualTo(Trip.TripStatus.CANCELLED);
    }

    @Test
    void shouldUpdateTripStatusToCancelled_AlsoNotifyPassengers() {
        // When
        tripService.updateTripStatus(1L, Trip.TripStatus.CANCELLED);

        // Then
        verify(notificationService).notifyTripCancelled(trip);
        verify(notificationService, never()).notifyTripRescheduled(any());
    }
}
