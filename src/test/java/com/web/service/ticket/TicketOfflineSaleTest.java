package com.web.service.ticket;

import com.web.dto.ticket.TicketCreateRequest;
import com.web.dto.ticket.TicketResponse;
import com.web.dto.ticket.mapper.TicketMapper;
import com.web.entity.Bus;
import com.web.entity.Route;
import com.web.entity.Stop;
import com.web.entity.Ticket;
import com.web.entity.Trip;
import com.web.entity.User;
import com.web.exception.BusinessException;
import com.web.exception.SeatNotAvailableException;
import com.web.repository.AssignmentRepository;
import com.web.repository.BaggageRepository;
import com.web.repository.FareRuleRepository;
import com.web.repository.SeatHoldRepository;
import com.web.repository.StopRepository;
import com.web.repository.TicketRepository;
import com.web.repository.TripRepository;
import com.web.repository.UserRepository;
import com.web.service.admin.ConfigService;
import com.web.util.QrCodeGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

// Sobrecargas offline de TicketServiceImpl: venta con hora real (soldAt) y abordaje con hora del dispositivo
@ExtendWith(MockitoExtension.class)
class TicketOfflineSaleTest {

    @Mock
    private TicketRepository ticketRepository;
    @Mock
    private TripRepository tripRepository;
    @Mock
    private StopRepository stopRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private FareRuleRepository fareRuleRepository;
    @Mock
    private BaggageRepository baggageRepository;
    @Mock
    private SeatHoldRepository seatHoldRepository;
    @Mock
    private TicketMapper ticketMapper;
    @Mock
    private SeatHoldService seatHoldService;
    @Mock
    private QrCodeGenerator qrCodeGenerator;
    @Mock
    private ConfigService configService;
    @Mock
    private AssignmentRepository assignmentRepository;

    @InjectMocks
    private TicketServiceImpl ticketService;

    private Trip trip;
    private User passenger;
    private Stop fromStop;
    private Stop toStop;
    private TicketCreateRequest request;

    @BeforeEach
    void setUp() {
        Route route = Route.builder().id(1L).code("R001").name("Santa Marta - Barranquilla").build();
        Bus bus = Bus.builder().id(1L).plate("ABC123").capacity(40).status(Bus.BusStatus.ACTIVE).build();
        LocalDate date = LocalDate.now().plusDays(1);
        trip = Trip.builder()
                .id(1L)
                .route(route)
                .bus(bus)
                .tripDate(date)
                .departureTime(date.atTime(12, 0))
                .status(Trip.TripStatus.SCHEDULED)
                .overbookingApprovedSeats(0)
                .build();
        passenger = User.builder().id(1L).name("Pasajero").email("pasajero@test.com").build();
        fromStop = Stop.builder().id(1L).route(route).name("Santa Marta").order(1).build();
        toStop = Stop.builder().id(2L).route(route).name("Barranquilla").order(2).build();
        request = new TicketCreateRequest(1L, 1L, 10, 1L, null, null, 2L, null, null,
                BigDecimal.ZERO, Ticket.PaymentMethod.CASH, null, null);
    }

    // Stubs del camino feliz de la compra hasta guardar el ticket
    private void givenSellableSeat() {
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(userRepository.findById(1L)).thenReturn(Optional.of(passenger));
        when(stopRepository.findById(1L)).thenReturn(Optional.of(fromStop));
        when(stopRepository.findById(2L)).thenReturn(Optional.of(toStop));
        when(seatHoldRepository.findOverlappingActiveHolds(eq(1L), eq(10), eq(1), eq(2), any(LocalDateTime.class)))
                .thenReturn(List.of());
        when(ticketRepository.isSeatAvailableForSegment(1L, 10, 1, 2)).thenReturn(true);
        when(fareRuleRepository.findByRouteIdAndFromStopIdAndToStopId(1L, 1L, 2L)).thenReturn(Optional.empty());
        when(configService.getTicketBasePrice()).thenReturn(BigDecimal.valueOf(50000));
        lenient().when(configService.getTicketPriceMultiplierPeakHours()).thenReturn(BigDecimal.ONE);
        when(ticketRepository.countSoldSeatsForSegment(eq(1L), anyInt(), anyInt())).thenReturn(0L);
        when(ticketMapper.toEntity(request)).thenReturn(Ticket.builder().seatNumber(10)
                .paymentMethod(Ticket.PaymentMethod.CASH).build());
        when(qrCodeGenerator.generateTicketQr()).thenReturn("QR-OFF");
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(inv -> inv.getArgument(0));
        when(ticketMapper.toResponse(any(Ticket.class))).thenReturn(mock(TicketResponse.class));
    }

    // ---------- purchaseTicket(request, offline) ----------

    @Test
    void shouldPurchaseOffline_WithValidSoldAt_KeepSoldAtAsPurchasedAtAndSetOfflineFields() {
        // Given
        LocalDateTime soldAt = LocalDateTime.now().minusHours(2).truncatedTo(ChronoUnit.SECONDS);
        givenSellableSeat();

        // When
        ticketService.purchaseTicket(request, new OfflineSaleContext("dev-1-uuid", soldAt));

        // Then
        verify(ticketRepository).save(argThat(t -> soldAt.equals(t.getPurchasedAt())
                && "dev-1-uuid".equals(t.getOfflineClientId())
                && t.getSyncedAt() != null
                && "QR-OFF".equals(t.getQrCode())));
        verify(tripRepository).lockById(1L);
    }

    @Test
    void shouldPurchaseOnline_WithoutContext_LeaveOfflineFieldsEmpty() {
        // Given
        givenSellableSeat();

        // When
        ticketService.purchaseTicket(request);

        // Then
        verify(ticketRepository).save(argThat(t -> t.getOfflineClientId() == null && t.getSyncedAt() == null));
    }

    @Test
    void shouldPurchaseOffline_WithTripDepartedAfterSale_AcceptSale() {
        // Given: la venta se hizo sin red antes de la salida y se sincroniza cuando el bus ya salió
        LocalDateTime departure = LocalDateTime.now().minusMinutes(30);
        trip.setDepartureTime(departure);
        trip.setStatus(Trip.TripStatus.DEPARTED);
        trip.setDepartedAt(departure);
        givenSellableSeat();

        // When
        ticketService.purchaseTicket(request, new OfflineSaleContext("dev-2", departure.minusHours(1)));

        // Then
        verify(ticketRepository).save(argThat(t -> departure.minusHours(1).equals(t.getPurchasedAt())));
    }

    @Test
    void shouldPurchaseOffline_WithSoldAtInFuture_ThrowInvalidSoldAt() {
        // Given
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));

        // When/Then
        assertThatThrownBy(() -> ticketService.purchaseTicket(request,
                new OfflineSaleContext("dev-3", LocalDateTime.now().plusMinutes(10))))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("INVALID_SOLD_AT");
        verify(ticketRepository, never()).save(any());
    }

    @Test
    void shouldPurchaseOffline_WithSoldAtWithinClockTolerance_AcceptSale() {
        // Given: reloj del dispositivo 3 minutos adelantado
        givenSellableSeat();

        // When
        ticketService.purchaseTicket(request, new OfflineSaleContext("dev-4", LocalDateTime.now().plusMinutes(3)));

        // Then
        verify(ticketRepository).save(any(Ticket.class));
    }

    @Test
    void shouldPurchaseOffline_WithSoldAtAfterDeparture_ThrowSoldAfterDeparture() {
        // Given
        LocalDateTime departure = LocalDateTime.now().minusHours(1);
        trip.setDepartureTime(departure);
        trip.setStatus(Trip.TripStatus.DEPARTED);
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));

        // When/Then
        assertThatThrownBy(() -> ticketService.purchaseTicket(request,
                new OfflineSaleContext("dev-5", departure.plusMinutes(10))))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("SOLD_AFTER_DEPARTURE");
        verify(ticketRepository, never()).save(any());
    }

    @Test
    void shouldPurchaseOffline_WithSoldAtAfterEarlyActualDeparture_ThrowSoldAfterDeparture() {
        // Given: el bus salió antes de la hora programada
        LocalDateTime departedAt = LocalDateTime.now().minusHours(2);
        trip.setDepartureTime(LocalDateTime.now().minusHours(1));
        trip.setDepartedAt(departedAt);
        trip.setStatus(Trip.TripStatus.DEPARTED);
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));

        // When/Then
        assertThatThrownBy(() -> ticketService.purchaseTicket(request,
                new OfflineSaleContext("dev-6", departedAt.plusMinutes(5))))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("SOLD_AFTER_DEPARTURE");
    }

    @Test
    void shouldPurchaseOffline_WithCancelledTrip_ThrowTripCancelled() {
        // Given
        trip.setStatus(Trip.TripStatus.CANCELLED);
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));

        // When/Then
        assertThatThrownBy(() -> ticketService.purchaseTicket(request,
                new OfflineSaleContext("dev-7", LocalDateTime.now().minusMinutes(5))))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("TRIP_CANCELLED");
    }

    @Test
    void shouldPurchaseOffline_WithSeatAlreadySold_ThrowSeatNotAvailable() {
        // Given: otro dispositivo sincronizó primero la misma silla y tramo
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(userRepository.findById(1L)).thenReturn(Optional.of(passenger));
        when(stopRepository.findById(1L)).thenReturn(Optional.of(fromStop));
        when(stopRepository.findById(2L)).thenReturn(Optional.of(toStop));
        when(seatHoldRepository.findOverlappingActiveHolds(eq(1L), eq(10), eq(1), eq(2), any(LocalDateTime.class)))
                .thenReturn(List.of());
        when(ticketRepository.isSeatAvailableForSegment(1L, 10, 1, 2)).thenReturn(false);

        // When/Then
        assertThatThrownBy(() -> ticketService.purchaseTicket(request,
                new OfflineSaleContext("dev-8", LocalDateTime.now().minusMinutes(5))))
                .isInstanceOf(SeatNotAvailableException.class);
        verify(ticketRepository, never()).save(any());
    }

    // ---------- boardTicket(qrCode, boardedAt) ----------

    private Ticket soldTicket() {
        return Ticket.builder()
                .id(5L)
                .trip(trip)
                .passenger(passenger)
                .seatNumber(10)
                .fromStop(fromStop)
                .toStop(toStop)
                .price(BigDecimal.valueOf(50000))
                .paymentMethod(Ticket.PaymentMethod.CASH)
                .status(Ticket.TicketStatus.SOLD)
                .qrCode("QR-5")
                .build();
    }

    @Test
    void shouldBoardOffline_WithPastBoardedAt_KeepDeviceTime() {
        // Given
        trip.setStatus(Trip.TripStatus.BOARDING);
        Ticket ticket = soldTicket();
        LocalDateTime boardedAt = LocalDateTime.now().minusMinutes(20);
        when(ticketRepository.findByQrCode("QR-5")).thenReturn(Optional.of(ticket));
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(inv -> inv.getArgument(0));

        // When
        ticketService.boardTicket("QR-5", boardedAt);

        // Then
        assertThat(ticket.getBoardedAt()).isEqualTo(boardedAt);
    }

    @Test
    void shouldBoardOffline_WithFutureBoardedAt_UseCurrentTime() {
        // Given
        trip.setStatus(Trip.TripStatus.BOARDING);
        Ticket ticket = soldTicket();
        when(ticketRepository.findByQrCode("QR-5")).thenReturn(Optional.of(ticket));
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(inv -> inv.getArgument(0));

        // When
        ticketService.boardTicket("QR-5", LocalDateTime.now().plusHours(1));

        // Then
        assertThat(ticket.getBoardedAt()).isCloseTo(LocalDateTime.now(), within(5, ChronoUnit.SECONDS));
    }

    @Test
    void shouldBoardOffline_WithNoShowAndBoardedBeforeDeparture_RestoreTicket() {
        // Given: el conductor validó el QR sin red antes de salir; al salir el servidor lo marcó no-show
        LocalDateTime departedAt = LocalDateTime.now().minusMinutes(30);
        trip.setStatus(Trip.TripStatus.DEPARTED);
        trip.setDepartedAt(departedAt);
        Ticket ticket = soldTicket();
        ticket.setStatus(Ticket.TicketStatus.NO_SHOW);
        ticket.setNoShowFee(BigDecimal.valueOf(5000));
        when(ticketRepository.findByQrCode("QR-5")).thenReturn(Optional.of(ticket));
        when(ticketRepository.isSeatAvailableForSegment(1L, 10, 1, 2)).thenReturn(true);
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(inv -> inv.getArgument(0));

        // When
        ticketService.boardTicket("QR-5", departedAt.minusMinutes(10));

        // Then
        assertThat(ticket.getStatus()).isEqualTo(Ticket.TicketStatus.SOLD);
        assertThat(ticket.getNoShowFee()).isNull();
        assertThat(ticket.getBoardedAt()).isEqualTo(departedAt.minusMinutes(10));
    }

    @Test
    void shouldBoardOffline_WithNoShowAndBoardedAfterDeparture_ThrowTicketNotValid() {
        // Given
        LocalDateTime departedAt = LocalDateTime.now().minusMinutes(30);
        trip.setStatus(Trip.TripStatus.DEPARTED);
        trip.setDepartedAt(departedAt);
        Ticket ticket = soldTicket();
        ticket.setStatus(Ticket.TicketStatus.NO_SHOW);
        when(ticketRepository.findByQrCode("QR-5")).thenReturn(Optional.of(ticket));

        // When/Then
        assertThatThrownBy(() -> ticketService.boardTicket("QR-5", departedAt.plusMinutes(5)))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("TICKET_NOT_VALID");
        verify(ticketRepository, never()).save(any());
    }

    @Test
    void shouldBoardOnline_WithNoShowAndTripDeparted_KeepRejecting() {
        // Given: sin hora de dispositivo se mantiene la regla en línea
        trip.setStatus(Trip.TripStatus.DEPARTED);
        trip.setDepartedAt(LocalDateTime.now().minusMinutes(30));
        Ticket ticket = soldTicket();
        ticket.setStatus(Ticket.TicketStatus.NO_SHOW);
        when(ticketRepository.findByQrCode("QR-5")).thenReturn(Optional.of(ticket));

        // When/Then
        assertThatThrownBy(() -> ticketService.boardTicket("QR-5"))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("TICKET_NOT_VALID");
    }
}
