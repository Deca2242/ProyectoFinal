package com.web.service.trip;

import com.web.dto.ticket.mapper.TicketMapper;
import com.web.dto.trip.TripResponse;
import com.web.dto.trip.TripUpdateRequest;
import com.web.dto.trip.mapper.TripMapper;
import com.web.entity.Bus;
import com.web.entity.Route;
import com.web.entity.Ticket;
import com.web.entity.Trip;
import com.web.exception.BusinessException;
import com.web.exception.InvalidStateTransitionException;
import com.web.exception.ResourceNotFoundException;
import com.web.repository.AssignmentRepository;
import com.web.repository.BusRepository;
import com.web.repository.IncidentRepository;
import com.web.repository.ParcelRepository;
import com.web.repository.RouteRepository;
import com.web.repository.SeatHoldRepository;
import com.web.repository.StopRepository;
import com.web.repository.TicketRepository;
import com.web.repository.TripRepository;
import com.web.service.notification.NotificationService;
import com.web.entity.Assignment;
import com.web.entity.SeatHold;
import com.web.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

// Reprogramación de viajes (ADMIN): solo SCHEDULED, fechas coherentes, bus activo y libre, sillas vendidas
@ExtendWith(MockitoExtension.class)
class TripRescheduleTest {

    private static final LocalDate TRIP_DATE = LocalDate.now().plusDays(10);
    private static final LocalDateTime DEPARTURE = TRIP_DATE.atTime(8, 0);
    private static final LocalDateTime ARRIVAL = TRIP_DATE.atTime(12, 0);

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

    private Bus bus;
    private Bus otherBus;
    private Trip trip;
    private TripResponse response;

    @BeforeEach
    void setUp() {
        Route route = Route.builder().id(1L).name("Santa Marta - Barranquilla").build();
        bus = Bus.builder().id(1L).plate("ABC123").capacity(40).status(Bus.BusStatus.ACTIVE).build();
        otherBus = Bus.builder().id(2L).plate("XYZ789").capacity(30).status(Bus.BusStatus.ACTIVE).build();
        trip = Trip.builder().id(1L).route(route).bus(bus).tripDate(TRIP_DATE)
                .departureTime(DEPARTURE).arrivalEta(ARRIVAL)
                .status(Trip.TripStatus.SCHEDULED).overbookingApprovedSeats(0).build();
        response = new TripResponse(1L, 1L, "Santa Marta - Barranquilla", null, null, 1L, "ABC123", 40,
                TRIP_DATE, DEPARTURE, ARRIVAL, Trip.TripStatus.SCHEDULED, null, null, null, null, null, null);
    }

    private Ticket soldTicket(int seat) {
        return Ticket.builder().id((long) seat).trip(trip).seatNumber(seat).status(Ticket.TicketStatus.SOLD).build();
    }

    // ---------- Validaciones de estado y datos ----------

    @Test
    void shouldReschedule_WithNonExistentTrip_ThrowResourceNotFound() {
        // Given
        when(tripRepository.findById(99L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> tripService.rescheduleTrip(99L, new TripUpdateRequest(DEPARTURE, null, null)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");
    }

    @ParameterizedTest
    @EnumSource(value = Trip.TripStatus.class, names = {"BOARDING", "DEPARTED", "ARRIVED", "CANCELLED"})
    void shouldReschedule_WithTripNotScheduled_Throw422(Trip.TripStatus status) {
        // Given
        trip.setStatus(status);
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));

        // When/Then
        assertThatThrownBy(() -> tripService.rescheduleTrip(1L, new TripUpdateRequest(DEPARTURE.plusHours(1), null, null)))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining(status.name())
                .extracting("status").isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        verify(tripRepository, never()).save(any());
    }

    @Test
    void shouldReschedule_WithEmptyRequest_ThrowBadRequest() {
        // Given
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));

        // When/Then
        assertThatThrownBy(() -> tripService.rescheduleTrip(1L, new TripUpdateRequest(null, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("NOTHING_TO_UPDATE");
    }

    @Test
    void shouldReschedule_WithArrivalBeforeDeparture_ThrowInvalidDates() {
        // Given: la nueva salida queda después de la llegada actual
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));

        // When/Then
        assertThatThrownBy(() -> tripService.rescheduleTrip(1L, new TripUpdateRequest(ARRIVAL.plusHours(1), null, null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(be.getCode()).isEqualTo("INVALID_DATES");
                });
        verify(tripRepository, never()).save(any());
    }

    @Test
    void shouldReschedule_WithArrivalEqualToDeparture_ThrowInvalidDates() {
        // Given
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));

        // When/Then
        assertThatThrownBy(() -> tripService.rescheduleTrip(1L, new TripUpdateRequest(null, DEPARTURE, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("INVALID_DATES");
    }

    @Test
    void shouldReschedule_WithDepartureInThePast_ThrowInvalidDates() {
        // Given
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        LocalDateTime past = LocalDateTime.now().minusHours(1);

        // When/Then
        assertThatThrownBy(() -> tripService.rescheduleTrip(1L, new TripUpdateRequest(past, past.plusHours(3), null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("posterior al momento actual")
                .extracting("code").isEqualTo("INVALID_DATES");
    }

    @Test
    void shouldReschedule_WithNonExistentBus_ThrowResourceNotFound() {
        // Given
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(busRepository.findById(9L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> tripService.rescheduleTrip(1L, new TripUpdateRequest(null, null, 9L)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Bus");
    }

    @ParameterizedTest
    @EnumSource(value = Bus.BusStatus.class, names = {"MAINTENANCE", "RETIRED"})
    void shouldReschedule_WithInactiveBus_ThrowBusNotAvailable(Bus.BusStatus status) {
        // Given
        otherBus.setStatus(status);
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(busRepository.findById(2L)).thenReturn(Optional.of(otherBus));

        // When/Then
        assertThatThrownBy(() -> tripService.rescheduleTrip(1L, new TripUpdateRequest(null, null, 2L)))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("BUS_NOT_AVAILABLE");
    }

    @Test
    void shouldReschedule_WithOnlyBusOnTripAlreadyDeparted_ThrowInvalidDates() {
        // Given: la salida del viaje ya pasó y solo se pide cambiar el bus
        LocalDateTime pastDeparture = LocalDateTime.now().minusHours(2);
        trip.setDepartureTime(pastDeparture);
        trip.setArrivalEta(pastDeparture.plusHours(4));
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));

        // When/Then
        assertThatThrownBy(() -> tripService.rescheduleTrip(1L, new TripUpdateRequest(null, null, 2L)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(be.getCode()).isEqualTo("INVALID_DATES");
                });
        verifyNoInteractions(busRepository);
        verify(tripRepository, never()).save(any());
    }

    // ---------- Bus libre en la franja horaria ----------

    @Test
    void shouldReschedule_WithNewBusOverlappingTrip_ThrowConflict() {
        // Given
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(busRepository.findById(2L)).thenReturn(Optional.of(otherBus));
        when(tripRepository.existsOverlappingTripForBus(2L, DEPARTURE, ARRIVAL, 1L)).thenReturn(true);

        // When/Then
        assertThatThrownBy(() -> tripService.rescheduleTrip(1L, new TripUpdateRequest(null, null, 2L)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(be.getCode()).isEqualTo("BUS_BUSY");
                });
        verify(tripRepository, never()).save(any());
    }

    @Test
    void shouldReschedule_WithSameBusMovedToOverlappingSlot_ThrowConflict() {
        // Given: el mismo bus ya tiene otro viaje que se solapa con la nueva franja
        LocalDateTime newDeparture = DEPARTURE.plusDays(1);
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(tripRepository.existsOverlappingTripForBus(1L, newDeparture, newDeparture.plusHours(4), 1L)).thenReturn(true);

        // When/Then
        assertThatThrownBy(() -> tripService.rescheduleTrip(1L,
                new TripUpdateRequest(newDeparture, newDeparture.plusHours(4), null)))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("BUS_BUSY");
    }

    @Test
    void shouldReschedule_WithSameBusAndSameDay_ExcludeOwnTripFromOverlap() {
        // Given: solo cambia la hora; el propio viaje no cuenta como conflicto
        LocalDateTime newDeparture = DEPARTURE.plusHours(2);
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(tripRepository.save(trip)).thenReturn(trip);
        when(tripMapper.toResponse(trip)).thenReturn(response);
        TripUpdateRequest request = new TripUpdateRequest(newDeparture, ARRIVAL.plusHours(2), null);

        // When
        TripResponse result = tripService.rescheduleTrip(1L, request);

        // Then
        assertThat(result).isSameAs(response);
        verify(tripRepository).existsOverlappingTripForBus(1L, newDeparture, ARRIVAL.plusHours(2), 1L);
        verify(tripMapper).updateEntityFromRequest(request, trip);
        assertThat(trip.getTripDate()).isEqualTo(TRIP_DATE);
        assertThat(trip.getBus()).isSameAs(bus);
    }

    @Test
    void shouldReschedule_WithNewDay_UpdateTripDateFromDeparture() {
        // Given
        LocalDateTime newDeparture = DEPARTURE.plusDays(2);
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(tripRepository.save(trip)).thenReturn(trip);
        when(tripMapper.toResponse(trip)).thenReturn(response);

        // When
        tripService.rescheduleTrip(1L, new TripUpdateRequest(newDeparture, newDeparture.plusHours(4), null));

        // Then
        assertThat(trip.getTripDate()).isEqualTo(TRIP_DATE.plusDays(2));
        verify(tripRepository).save(trip);
    }

    // ---------- Capacidad del bus nuevo ----------

    @Test
    void shouldReschedule_WithSoldSeatAboveNewCapacity_ThrowConflict() {
        // Given: el bus nuevo tiene 30 sillas y hay un tiquete en la 35
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(busRepository.findById(2L)).thenReturn(Optional.of(otherBus));
        when(ticketRepository.findByTripIdAndStatus(1L, Ticket.TicketStatus.SOLD))
                .thenReturn(List.of(soldTicket(5), soldTicket(35)));

        // When/Then
        assertThatThrownBy(() -> tripService.rescheduleTrip(1L, new TripUpdateRequest(null, null, 2L)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(be.getCode()).isEqualTo("SEATS_EXCEED_CAPACITY");
                });
        verify(tripRepository, never()).save(any());
    }

    @Test
    void shouldReschedule_WithSoldSeatsWithinCapacityPlusApprovedOverbooking_ChangeBus() {
        // Given: 30 sillas + 2 de overbooking aprobado cubren la silla 32
        trip.setOverbookingApprovedSeats(2);
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(busRepository.findById(2L)).thenReturn(Optional.of(otherBus));
        when(ticketRepository.findByTripIdAndStatus(1L, Ticket.TicketStatus.SOLD))
                .thenReturn(List.of(soldTicket(30), soldTicket(32)));
        when(tripRepository.save(trip)).thenReturn(trip);
        when(tripMapper.toResponse(trip)).thenReturn(response);

        // When
        tripService.rescheduleTrip(1L, new TripUpdateRequest(null, null, 2L));

        // Then
        assertThat(trip.getBus()).isSameAs(otherBus);
        assertThat(trip.getStatus()).isEqualTo(Trip.TripStatus.SCHEDULED);
    }

    @Test
    void shouldReschedule_WithSameBus_NotCheckSoldSeats() {
        // Given
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(busRepository.findById(1L)).thenReturn(Optional.of(bus));
        when(tripRepository.save(trip)).thenReturn(trip);
        when(tripMapper.toResponse(trip)).thenReturn(response);

        // When
        tripService.rescheduleTrip(1L, new TripUpdateRequest(null, ARRIVAL.plusMinutes(30), 1L));

        // Then: busId igual al actual no consulta los tiquetes; la franja nueva se valida excluyendo el propio viaje
        verifyNoInteractions(ticketRepository);
        verify(tripRepository).existsOverlappingTripForBus(1L, DEPARTURE, ARRIVAL.plusMinutes(30), 1L);
    }

    // ---------- Conductor asignado, holds huérfanos y aviso a pasajeros ----------

    @Test
    void shouldReschedule_WithAssignedDriverBusyInNewSchedule_ThrowConflict() {
        // Given
        LocalDateTime newDeparture = DEPARTURE.plusHours(3);
        LocalDateTime newArrival = ARRIVAL.plusHours(3);
        User driver = User.builder().id(7L).build();
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(assignmentRepository.findByTripId(1L)).thenReturn(Optional.of(Assignment.builder().trip(trip).driver(driver).build()));
        when(assignmentRepository.isDriverAvailableExcludingTrip(7L, 1L, newDeparture, newArrival)).thenReturn(false);

        // When/Then
        assertThatThrownBy(() -> tripService.rescheduleTrip(1L, new TripUpdateRequest(newDeparture, newArrival, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("DRIVER_NOT_AVAILABLE");
        verify(tripRepository, never()).save(any());
        verifyNoInteractions(notificationService);
    }

    @Test
    void shouldReschedule_WithNewTimes_NotifyPassengers() {
        // Given: el conductor asignado sigue libre
        LocalDateTime newDeparture = DEPARTURE.plusHours(1);
        User driver = User.builder().id(7L).build();
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(assignmentRepository.findByTripId(1L)).thenReturn(Optional.of(Assignment.builder().trip(trip).driver(driver).build()));
        when(assignmentRepository.isDriverAvailableExcludingTrip(7L, 1L, newDeparture, ARRIVAL)).thenReturn(true);
        when(tripRepository.save(trip)).thenReturn(trip);

        // When
        tripService.rescheduleTrip(1L, new TripUpdateRequest(newDeparture, null, null));

        // Then
        verify(notificationService).notifyTripRescheduled(trip);
    }

    @Test
    void shouldReschedule_OnlyChangingBus_ReleaseHoldsAboveNewCapacityWithoutNotifying() {
        // Given: el bus nuevo tiene 30 sillas; el hold de la silla 35 queda huérfano
        SeatHold inRange = SeatHold.builder().seatNumber(10).status(SeatHold.HoldStatus.HOLD).build();
        SeatHold orphan = SeatHold.builder().seatNumber(35).status(SeatHold.HoldStatus.HOLD).build();
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(busRepository.findById(2L)).thenReturn(Optional.of(otherBus));
        when(seatHoldRepository.findActiveHoldsByTrip(eq(1L), any(LocalDateTime.class))).thenReturn(List.of(inRange, orphan));
        when(tripRepository.save(trip)).thenReturn(trip);

        // When
        tripService.rescheduleTrip(1L, new TripUpdateRequest(null, null, 2L));

        // Then
        assertThat(orphan.getStatus()).isEqualTo(SeatHold.HoldStatus.EXPIRED);
        assertThat(inRange.getStatus()).isEqualTo(SeatHold.HoldStatus.HOLD);
        verify(seatHoldRepository).saveAll(List.of(orphan));
        verifyNoInteractions(notificationService);
    }
}
