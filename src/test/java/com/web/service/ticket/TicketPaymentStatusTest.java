package com.web.service.ticket;

import com.web.dto.ticket.TicketCancelResponse;
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
import com.web.repository.AssignmentRepository;
import com.web.repository.BaggageRepository;
import com.web.repository.FareRuleRepository;
import com.web.repository.SeatHoldRepository;
import com.web.repository.StopRepository;
import com.web.repository.TicketRepository;
import com.web.repository.TripRepository;
import com.web.repository.UserRepository;
import com.web.service.admin.ConfigService;
import com.web.service.notification.NotificationService;
import com.web.service.payment.PaymentService;
import com.web.util.QrCodeGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

// Estado de pago en TicketServiceImpl: quién cobra en el acto, abordaje y cancelación de tickets PENDING
@ExtendWith(MockitoExtension.class)
class TicketPaymentStatusTest {

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
    @Mock
    private NotificationService notificationService;
    @Mock
    private PaymentService paymentService;

    @InjectMocks
    private TicketServiceImpl ticketService;

    private Trip trip;
    private User passenger;
    private User clerk;
    private Stop fromStop;
    private Stop toStop;
    private TicketCreateRequest request;

    @BeforeEach
    void setUp() {
        Route route = Route.builder().id(1L).code("R001").name("Santa Marta - Barranquilla").build();
        Bus bus = Bus.builder().id(1L).plate("ABC123").capacity(40).status(Bus.BusStatus.ACTIVE).build();
        LocalDate date = LocalDate.now().plusDays(3);
        trip = Trip.builder()
                .id(1L)
                .route(route)
                .bus(bus)
                .tripDate(date)
                .departureTime(date.atTime(12, 0))
                .status(Trip.TripStatus.SCHEDULED)
                .overbookingApprovedSeats(0)
                .build();
        passenger = User.builder().id(1L).name("Pasajero").email("pasajero@test.com").role(User.Role.PASSENGER).build();
        clerk = User.builder().id(2L).name("Taquilla").email("clerk@test.com").role(User.Role.CLERK).build();
        fromStop = Stop.builder().id(1L).route(route).name("Santa Marta").order(1).build();
        toStop = Stop.builder().id(2L).route(route).name("Barranquilla").order(2).build();
        request = new TicketCreateRequest(1L, 1L, 10, 1L, null, null, 2L, null, null,
                BigDecimal.ZERO, Ticket.PaymentMethod.QR, null, null);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(User user) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                user.getEmail(), null, List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole()))));
    }

    // Stubs del camino feliz de la compra hasta guardar el ticket
    private void givenSellableSeat(User seller) {
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(userRepository.findById(1L)).thenReturn(Optional.of(passenger));
        when(userRepository.findByEmail(seller.getEmail())).thenReturn(Optional.of(seller));
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
                .paymentMethod(Ticket.PaymentMethod.QR).build());
        when(qrCodeGenerator.generateTicketQr()).thenReturn("QR-PAY");
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(inv -> inv.getArgument(0));
        when(ticketMapper.toResponse(any(Ticket.class))).thenReturn(mock(TicketResponse.class));
    }

    // ---------- purchaseTicket ----------

    @Test
    void shouldPurchase_AsPassengerThroughApp_LeaveTicketPendingWithoutReceipt() {
        // Given
        authenticate(passenger);
        givenSellableSeat(passenger);

        // When
        ticketService.purchaseTicket(request);

        // Then: paga luego por QR/transferencia confirmada o en taquilla/al subir
        verify(ticketRepository).save(argThat(t -> t.getPaymentStatus() == Ticket.PaymentStatus.PENDING
                && t.getPaidAt() == null));
        verifyNoInteractions(paymentService);
    }

    @Test
    void shouldPurchase_AsClerk_MarkPaidAndRecordCounterPayment() {
        // Given
        authenticate(clerk);
        givenSellableSeat(clerk);

        // When
        ticketService.purchaseTicket(request);

        // Then: la taquilla cobra en el acto y queda el comprobante a su nombre
        verify(ticketRepository).save(argThat(t -> t.getPaymentStatus() == Ticket.PaymentStatus.PAID
                && t.getPaidAt() != null));
        verify(paymentService).recordCounterPayment(argThat(t -> "QR-PAY".equals(t.getQrCode())), eq(clerk));
    }

    @Test
    void shouldPurchaseOffline_MarkPaidAtSyncTime() {
        // Given: venta offline de ayer sincronizada hoy por la taquilla
        authenticate(clerk);
        givenSellableSeat(clerk);
        LocalDateTime soldAt = LocalDateTime.now().minusDays(1);

        // When
        ticketService.purchaseTicket(request, new OfflineSaleContext("dev-pay-1", soldAt));

        // Then: se cobró al vender, pero entra en caja cuando llega al sistema (paidAt = sincronización)
        verify(ticketRepository).save(argThat(t -> t.getPaymentStatus() == Ticket.PaymentStatus.PAID
                && soldAt.equals(t.getPurchasedAt())
                && t.getPaidAt().isAfter(soldAt.plusHours(23))));
        verify(paymentService).recordCounterPayment(any(Ticket.class), eq(clerk));
    }

    // ---------- boardTicket ----------

    private Ticket pendingTicket() {
        return Ticket.builder()
                .id(5L)
                .trip(trip)
                .passenger(passenger)
                .seatNumber(10)
                .fromStop(fromStop)
                .toStop(toStop)
                .price(BigDecimal.valueOf(50000))
                .paymentMethod(Ticket.PaymentMethod.QR)
                .paymentStatus(Ticket.PaymentStatus.PENDING)
                .status(Ticket.TicketStatus.SOLD)
                .qrCode("QR-5")
                .build();
    }

    @Test
    void shouldBoard_WithPendingPayment_ThrowConflictPaymentPending() {
        // Given
        trip.setStatus(Trip.TripStatus.BOARDING);
        Ticket ticket = pendingTicket();
        when(ticketRepository.findByQrCode("QR-5")).thenReturn(Optional.of(ticket));

        // When/Then
        assertThatThrownBy(() -> ticketService.boardTicket("QR-5"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(be.getCode()).isEqualTo("PAYMENT_PENDING");
                });
        assertThat(ticket.getBoardedAt()).isNull();
        verify(ticketRepository, never()).save(any());
    }

    @Test
    void shouldBoard_WithPaidTicket_RegisterBoarding() {
        // Given
        trip.setStatus(Trip.TripStatus.BOARDING);
        Ticket ticket = pendingTicket();
        ticket.setPaymentStatus(Ticket.PaymentStatus.PAID);
        when(ticketRepository.findByQrCode("QR-5")).thenReturn(Optional.of(ticket));
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(inv -> inv.getArgument(0));

        // When
        ticketService.boardTicket("QR-5");

        // Then
        assertThat(ticket.getBoardedAt()).isCloseTo(LocalDateTime.now(), within(5, ChronoUnit.SECONDS));
    }

    // ---------- cancelTicket ----------

    @Test
    void shouldCancel_WithPendingPayment_ReturnZeroRefund() {
        // Given: nunca se pagó, no hay nada que reembolsar
        Ticket ticket = pendingTicket();
        when(ticketRepository.findById(5L)).thenReturn(Optional.of(ticket));

        // When
        TicketCancelResponse response = ticketService.cancelTicket(5L);

        // Then
        assertThat(response.refundAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(ticket.getStatus()).isEqualTo(Ticket.TicketStatus.CANCELLED);
        assertThat(ticket.getRefundAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        verifyNoInteractions(configService);
    }
}
