package com.web.service.trip;

import com.web.dto.ticket.mapper.TicketMapper;
import com.web.dto.trip.SeatStatusResponse;
import com.web.dto.trip.mapper.TripMapper;
import com.web.entity.*;
import com.web.repository.*;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

// Mapa de asientos con holds (HELD) y encomiendas pendientes al cancelar un viaje
@ExtendWith(MockitoExtension.class)
class TripSeatMapAndParcelRulesTest {

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
    private com.web.service.notification.NotificationService notificationService;
    @Mock
    private TripMapper tripMapper;
    @Mock
    private TicketMapper ticketMapper;

    @InjectMocks
    private TripServiceImpl tripService;

    private Route route;
    private Stop stopA;
    private Stop stopB;
    private Stop stopC;
    private Trip trip;

    @BeforeEach
    void setUp() {
        route = Route.builder().id(1L).build();
        stopA = Stop.builder().id(10L).route(route).order(1).build();
        stopB = Stop.builder().id(11L).route(route).order(2).build();
        stopC = Stop.builder().id(12L).route(route).order(3).build();
        trip = Trip.builder()
                .id(1L)
                .route(route)
                .bus(Bus.builder().id(1L).capacity(4).build())
                .status(Trip.TripStatus.SCHEDULED)
                .build();
    }

    @Test
    void shouldGetSeatAvailability_WithHolds_MarkOverlappingHoldsAsHeld() {
        // Given: silla 1 con hold A->B, silla 2 con hold B->C, silla 3 con hold de viaje completo, silla 4 vendida
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(stopRepository.findById(10L)).thenReturn(Optional.of(stopA));
        when(stopRepository.findById(11L)).thenReturn(Optional.of(stopB));
        when(seatHoldRepository.findActiveHoldsByTrip(eq(1L), any(LocalDateTime.class))).thenReturn(List.of(
                SeatHold.builder().seatNumber(1).fromStop(stopA).toStop(stopB).build(),
                SeatHold.builder().seatNumber(2).fromStop(stopB).toStop(stopC).build(),
                SeatHold.builder().seatNumber(3).build()));
        when(ticketRepository.isSeatAvailableForSegment(anyLong(), anyInt(), anyInt(), anyInt())).thenReturn(true);
        when(ticketRepository.isSeatAvailableForSegment(1L, 4, 1, 2)).thenReturn(false);

        // When: se consulta el tramo A -> B
        List<SeatStatusResponse> seats = tripService.getSeatAvailability(1L, 10L, 11L);

        // Then
        assertThat(seats).extracting(SeatStatusResponse::status)
                .containsExactly("HELD", "AVAILABLE", "HELD", "OCCUPIED");
        assertThat(seats).extracting(SeatStatusResponse::available)
                .containsExactly(false, true, false, false);
    }

    @Test
    void shouldCancelTrip_WithPendingParcels_MarkThemFailedWithIncident() {
        // Given
        Parcel created = Parcel.builder().id(1L).code("P1").status(Parcel.ParcelStatus.CREATED).build();
        Parcel inTransit = Parcel.builder().id(2L).code("P2").status(Parcel.ParcelStatus.IN_TRANSIT).build();
        Parcel delivered = Parcel.builder().id(3L).code("P3").status(Parcel.ParcelStatus.DELIVERED).build();
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(parcelRepository.findByTripId(1L)).thenReturn(List.of(created, inTransit, delivered));

        // When
        tripService.cancelTrip(1L);

        // Then
        assertThat(created.getStatus()).isEqualTo(Parcel.ParcelStatus.FAILED);
        assertThat(inTransit.getStatus()).isEqualTo(Parcel.ParcelStatus.FAILED);
        assertThat(delivered.getStatus()).isEqualTo(Parcel.ParcelStatus.DELIVERED);
        verify(incidentRepository, times(2)).save(argThat(i ->
                i.getIncidentType() == Incident.IncidentType.DELIVERY_FAIL
                        && i.getEntityType() == Incident.EntityType.PARCEL));
        verify(parcelRepository).saveAll(List.of(created, inTransit));
    }
}
