package com.web.service.payment;

import com.web.dto.payment.CashCloseRequest;
import com.web.dto.payment.CashCloseResponse;
import com.web.dto.payment.PaymentConfirmRequest;
import com.web.dto.payment.PaymentResponse;
import com.web.dto.payment.mapper.PaymentMapper;
import com.web.dto.payment.mapper.PaymentMapperImpl;
import com.web.entity.Assignment;
import com.web.entity.Baggage;
import com.web.entity.CashClose;
import com.web.entity.Payment;
import com.web.entity.Ticket;
import com.web.entity.Trip;
import com.web.entity.User;
import com.web.exception.BusinessException;
import com.web.exception.InvalidStateTransitionException;
import com.web.exception.ResourceNotFoundException;
import com.web.repository.AssignmentRepository;
import com.web.repository.CashCloseRepository;
import com.web.repository.PaymentRepository;
import com.web.repository.TicketRepository;
import com.web.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;


@ExtendWith(MockitoExtension.class)
class PaymentServiceImplTest {

    @Mock
    private TicketRepository ticketRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private CashCloseRepository cashCloseRepository;
    @Mock
    private AssignmentRepository assignmentRepository;
    // Mapper real generado por MapStruct: las respuestas reflejan lo que se guardó
    @Spy
    private PaymentMapper paymentMapper = new PaymentMapperImpl();

    @InjectMocks
    private PaymentServiceImpl paymentService;

    private static final LocalDate DAY = LocalDate.of(2026, 3, 15);

    private Trip trip;
    private Ticket ticket;
    private User clerk;
    private User driver;
    private User passenger;

    @BeforeEach
    void setUp() {
        trip = Trip.builder().id(1L).tripDate(LocalDate.now()).build();
        passenger = User.builder().id(3L).name("Pasajero").email("pax@example.com").role(User.Role.PASSENGER).build();
        clerk = User.builder().id(1L).name("Clerk").email("clerk@example.com").role(User.Role.CLERK).build();
        driver = User.builder().id(2L).name("Driver").email("driver@example.com").role(User.Role.DRIVER).build();

        // Compra del pasajero por la app: pendiente de pago
        ticket = Ticket.builder()
                .id(10L)
                .trip(trip)
                .passenger(passenger)
                .qrCode("QR-10")
                .price(new BigDecimal("50000.00"))
                .status(Ticket.TicketStatus.SOLD)
                .paymentMethod(Ticket.PaymentMethod.QR)
                .paymentStatus(Ticket.PaymentStatus.PENDING)
                .build();
    }

    private PaymentConfirmRequest confirm(Ticket.PaymentMethod method, String reference, BigDecimal amount) {
        return new PaymentConfirmRequest(10L, method, reference, amount, null);
    }

    private void givenConfirmer(User user) {
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(ticketRepository.findById(10L)).thenReturn(Optional.of(ticket));
    }

    private void givenPaymentSaved() {
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> {
            Payment payment = inv.getArgument(0);
            payment.setId(7L);
            return payment;
        });
    }

    private static BusinessException business(Throwable ex) {
        return (BusinessException) ex;
    }

    // ==================== confirmPayment ====================

    @Test
    void shouldConfirmPayment_WithQrAndReference_PersistPaymentAndMarkTicketPaid() {
        // Given
        givenConfirmer(clerk);
        givenPaymentSaved();
        PaymentConfirmRequest request = new PaymentConfirmRequest(10L, Ticket.PaymentMethod.QR, "  NEQUI-123 ",
                new BigDecimal("50000"), "https://comprobantes/123.png");

        // When
        PaymentResponse result = paymentService.confirmPayment(request, 1L);

        // Then: el pago queda registrado con quien lo recibió y el ticket pasa a PAID
        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(captor.capture());
        Payment saved = captor.getValue();
        assertThat(saved.getTicket()).isSameAs(ticket);
        assertThat(saved.getMethod()).isEqualTo(Ticket.PaymentMethod.QR);
        assertThat(saved.getAmount()).isEqualByComparingTo("50000");
        assertThat(saved.getTransactionReference()).isEqualTo("NEQUI-123");
        assertThat(saved.getProofImageUrl()).isEqualTo("https://comprobantes/123.png");
        assertThat(saved.getConfirmedBy()).isSameAs(clerk);
        assertThat(saved.getPaidAt()).isNotNull();

        assertThat(ticket.getPaymentStatus()).isEqualTo(Ticket.PaymentStatus.PAID);
        assertThat(ticket.getPaidAt()).isEqualTo(saved.getPaidAt());
        verify(ticketRepository).save(ticket);

        // El comprobante identifica ticket, método, monto, referencia y cajero
        assertThat(result.receiptNumber()).isEqualTo("RCP-7");
        assertThat(result.ticketId()).isEqualTo(10L);
        assertThat(result.qrCode()).isEqualTo("QR-10");
        assertThat(result.paymentMethod()).isEqualTo(Ticket.PaymentMethod.QR);
        assertThat(result.paymentStatus()).isEqualTo(Ticket.PaymentStatus.PAID);
        assertThat(result.transactionReference()).isEqualTo("NEQUI-123");
        assertThat(result.confirmedBy()).isEqualTo("Clerk");
    }

    @Test
    void shouldConfirmPayment_WithCashAtCounter_ChangeMethodAndNotRequireReference() {
        // Given: el pasajero eligió QR pero paga en efectivo en taquilla (contraentrega)
        givenConfirmer(clerk);
        givenPaymentSaved();

        // When
        PaymentResponse result = paymentService.confirmPayment(confirm(Ticket.PaymentMethod.CASH, null, null), 1L);

        // Then
        assertThat(ticket.getPaymentMethod()).isEqualTo(Ticket.PaymentMethod.CASH);
        assertThat(result.transactionReference()).isNull();
        assertThat(result.amount()).isEqualByComparingTo("50000");
    }

    @ParameterizedTest
    @EnumSource(value = Ticket.PaymentMethod.class, names = {"QR", "TRANSFER", "CARD"})
    void shouldConfirmPayment_WithoutReferenceForElectronicMethod_ThrowBadRequest(Ticket.PaymentMethod method) {
        // Given
        givenConfirmer(clerk);

        // When/Then: sin referencia (o en blanco) no hay forma de verificar el pago
        assertThatThrownBy(() -> paymentService.confirmPayment(confirm(method, "   ", null), 1L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    assertThat(business(ex).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(business(ex).getCode()).isEqualTo("TRANSACTION_REFERENCE_REQUIRED");
                });
        assertThat(ticket.getPaymentStatus()).isEqualTo(Ticket.PaymentStatus.PENDING);
        verify(paymentRepository, never()).save(any());
        verify(ticketRepository, never()).save(any());
    }

    @Test
    void shouldConfirmPayment_WithTicketAlreadyPaid_ThrowConflictAndKeepPaymentMethod() {
        // Given: ticket vendido en taquilla en efectivo (ya PAID); se intenta "confirmarlo" como tarjeta
        ticket.setPaymentStatus(Ticket.PaymentStatus.PAID);
        ticket.setPaymentMethod(Ticket.PaymentMethod.CASH);
        givenConfirmer(clerk);

        // When/Then
        assertThatThrownBy(() -> paymentService.confirmPayment(confirm(Ticket.PaymentMethod.CARD, "TX-1", null), 1L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    assertThat(business(ex).getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(business(ex).getCode()).isEqualTo("PAYMENT_ALREADY_CONFIRMED");
                });
        assertThat(ticket.getPaymentMethod()).isEqualTo(Ticket.PaymentMethod.CASH);
        verify(paymentRepository, never()).save(any());
        verify(ticketRepository, never()).save(any());
    }

    @Test
    void shouldConfirmPayment_WithAmountDifferentFromPrice_ThrowAmountMismatch() {
        // Given
        givenConfirmer(clerk);

        // When/Then
        assertThatThrownBy(() -> paymentService.confirmPayment(
                confirm(Ticket.PaymentMethod.TRANSFER, "TX-2", new BigDecimal("45000")), 1L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    assertThat(business(ex).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(business(ex).getCode()).isEqualTo("AMOUNT_MISMATCH");
                });
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void shouldConfirmPayment_WithDriverNotAssignedToTrip_ThrowForbidden() {
        // Given: el viaje lo conduce otro conductor
        givenConfirmer(driver);
        User otherDriver = User.builder().id(99L).role(User.Role.DRIVER).build();
        when(assignmentRepository.findByTripId(1L))
                .thenReturn(Optional.of(Assignment.builder().trip(trip).driver(otherDriver).build()));

        // When/Then
        assertThatThrownBy(() -> paymentService.confirmPayment(confirm(Ticket.PaymentMethod.CASH, null, null), 2L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    assertThat(business(ex).getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(business(ex).getCode()).isEqualTo("DRIVER_NOT_ASSIGNED");
                });
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void shouldConfirmPayment_WithTripWithoutAssignment_ThrowForbiddenForDriver() {
        // Given
        givenConfirmer(driver);
        when(assignmentRepository.findByTripId(1L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> paymentService.confirmPayment(confirm(Ticket.PaymentMethod.CASH, null, null), 2L))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("DRIVER_NOT_ASSIGNED");
    }

    @Test
    void shouldConfirmPayment_WithAssignedDriver_CollectOnBoarding() {
        // Given: contraentrega al subir, cobrada por el conductor del viaje
        givenConfirmer(driver);
        givenPaymentSaved();
        when(assignmentRepository.findByTripId(1L))
                .thenReturn(Optional.of(Assignment.builder().trip(trip).driver(driver).build()));

        // When
        PaymentResponse result = paymentService.confirmPayment(confirm(Ticket.PaymentMethod.CASH, null, null), 2L);

        // Then
        assertThat(result.confirmedBy()).isEqualTo("Driver");
        assertThat(ticket.getPaymentStatus()).isEqualTo(Ticket.PaymentStatus.PAID);
    }

    @ParameterizedTest
    @EnumSource(value = Ticket.TicketStatus.class, names = {"CANCELLED", "NO_SHOW"})
    void shouldConfirmPayment_WithTicketNotSold_ThrowConflictInvalidTicketStatus(Ticket.TicketStatus status) {
        // Given
        ticket.setStatus(status);
        givenConfirmer(clerk);

        // When/Then
        assertThatThrownBy(() -> paymentService.confirmPayment(confirm(Ticket.PaymentMethod.TRANSFER, "REF-1", null), 1L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    assertThat(business(ex).getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(business(ex).getCode()).isEqualTo("INVALID_TICKET_STATUS");
                });
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void shouldConfirmPayment_WithNonExistentTicket_ThrowResourceNotFound() {
        // Given
        when(userRepository.findById(1L)).thenReturn(Optional.of(clerk));
        when(ticketRepository.findById(10L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> paymentService.confirmPayment(confirm(Ticket.PaymentMethod.CASH, null, null), 1L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Ticket");
        verifyNoInteractions(paymentRepository);
    }

    @Test
    void shouldConfirmPayment_WhenCashierAlreadyClosedToday_ThrowInvalidStateTransition() {
        // Given: el cajero ya cerró la caja de hoy, el pago no tendría cierre donde contarse
        givenConfirmer(clerk);
        when(cashCloseRepository.existsByUserIdAndCloseDate(1L, LocalDate.now())).thenReturn(true);

        // When/Then
        assertThatThrownBy(() -> paymentService.confirmPayment(confirm(Ticket.PaymentMethod.CASH, null, null), 1L))
                .isInstanceOf(InvalidStateTransitionException.class)
                .satisfies(ex -> assertThat(business(ex).getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        assertThat(ticket.getPaymentStatus()).isEqualTo(Ticket.PaymentStatus.PENDING);
        verify(paymentRepository, never()).save(any());
    }

    // ==================== recordCounterPayment ====================

    @Test
    void shouldRecordCounterPayment_WithPaidTicket_SaveReceiptForSeller() {
        // Given: venta en taquilla cobrada en el acto
        LocalDateTime paidAt = LocalDateTime.of(2026, 3, 15, 9, 30);
        ticket.setPaymentStatus(Ticket.PaymentStatus.PAID);
        ticket.setPaymentMethod(Ticket.PaymentMethod.CASH);
        ticket.setPaidAt(paidAt);
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

        // When
        Payment payment = paymentService.recordCounterPayment(ticket, clerk);

        // Then
        assertThat(payment.getTicket()).isSameAs(ticket);
        assertThat(payment.getMethod()).isEqualTo(Ticket.PaymentMethod.CASH);
        assertThat(payment.getAmount()).isEqualByComparingTo("50000");
        assertThat(payment.getPaidAt()).isEqualTo(paidAt);
        assertThat(payment.getConfirmedBy()).isSameAs(clerk);
        assertThat(payment.getTransactionReference()).isNull();
    }

    // ==================== getReceipt ====================

    private Payment paymentOf(Ticket t, Ticket.PaymentMethod method, String amount, User confirmedBy) {
        return Payment.builder()
                .id(t.getId() + 100)
                .ticket(t)
                .method(method)
                .amount(new BigDecimal(amount))
                .paidAt(LocalDateTime.now())
                .confirmedBy(confirmedBy)
                .build();
    }

    @Test
    void shouldGetReceipt_WithOwnerPassenger_ReturnPaymentResponse() {
        // Given
        ticket.setPaymentStatus(Ticket.PaymentStatus.PAID);
        when(ticketRepository.findById(10L)).thenReturn(Optional.of(ticket));
        when(userRepository.findById(3L)).thenReturn(Optional.of(passenger));
        when(paymentRepository.findByTicketId(10L))
                .thenReturn(Optional.of(paymentOf(ticket, Ticket.PaymentMethod.QR, "50000", clerk)));

        // When
        PaymentResponse result = paymentService.getReceipt(10L, 3L);

        // Then
        assertThat(result.receiptNumber()).isEqualTo("RCP-110");
        assertThat(result.confirmedBy()).isEqualTo("Clerk");
    }

    @Test
    void shouldGetReceipt_WithAnotherPassenger_ThrowForbidden() {
        // Given
        User other = User.builder().id(4L).role(User.Role.PASSENGER).build();
        when(ticketRepository.findById(10L)).thenReturn(Optional.of(ticket));
        when(userRepository.findById(4L)).thenReturn(Optional.of(other));

        // When/Then
        assertThatThrownBy(() -> paymentService.getReceipt(10L, 4L))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("NOT_TICKET_OWNER");
        verifyNoInteractions(paymentRepository);
    }

    @Test
    void shouldGetReceipt_WithPendingTicket_ThrowNotFound() {
        // Given: la taquilla consulta un ticket que aún no se ha pagado
        when(ticketRepository.findById(10L)).thenReturn(Optional.of(ticket));
        when(userRepository.findById(1L)).thenReturn(Optional.of(clerk));

        // When/Then
        assertThatThrownBy(() -> paymentService.getReceipt(10L, 1L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("pendiente");
        verifyNoInteractions(paymentRepository);
    }

    // ==================== closeCash ====================

    private void givenNothingClosed(Long userId, LocalDate date) {
        when(userRepository.findById(userId)).thenReturn(Optional.of(userId.equals(1L) ? clerk : driver));
        when(cashCloseRepository.existsByUserIdAndCloseDate(userId, date)).thenReturn(false);
        when(cashCloseRepository.save(any(CashClose.class))).thenAnswer(inv -> {
            CashClose close = inv.getArgument(0);
            close.setId(55L);
            return close;
        });
    }

    private void givenPayments(LocalDate date, List<Payment> received, List<Payment> refunded) {
        when(paymentRepository.findReceivedByUserBetween(1L, date.atStartOfDay(), date.plusDays(1).atStartOfDay()))
                .thenReturn(received);
        when(paymentRepository.findCashRefundedByUserBetween(1L, date.atStartOfDay(), date.plusDays(1).atStartOfDay()))
                .thenReturn(refunded);
    }

    private Ticket paidTicket(long id, String price, Ticket.PaymentMethod method) {
        return Ticket.builder()
                .id(id)
                .trip(trip)
                .price(new BigDecimal(price))
                .status(Ticket.TicketStatus.SOLD)
                .paymentMethod(method)
                .paymentStatus(Ticket.PaymentStatus.PAID)
                .build();
    }

    private Payment received(long id, String price, Ticket.PaymentMethod method) {
        return paymentOf(paidTicket(id, price, method), method, price, clerk);
    }

    @Test
    void shouldCloseCash_WithPaymentsOfAllMethods_ReturnTotalsByMethodAndExpectedCashOnlyFromCash() {
        // Given
        givenNothingClosed(1L, DAY);
        givenPayments(DAY, List.of(
                received(1L, "50000", Ticket.PaymentMethod.CASH),
                received(2L, "30000", Ticket.PaymentMethod.QR),
                received(3L, "20000", Ticket.PaymentMethod.TRANSFER),
                received(4L, "10000", Ticket.PaymentMethod.CARD),
                received(5L, "15000.50", Ticket.PaymentMethod.CASH)), List.of());
        CashCloseRequest request = new CashCloseRequest(DAY, null, new BigDecimal("60000"), "Turno mañana");

        // When
        CashCloseResponse result = paymentService.closeCash(request, 1L);

        // Then: los pagos electrónicos no están en la caja física
        assertThat(result.totalsByMethod()).containsOnlyKeys("CASH", "TRANSFER", "QR", "CARD");
        assertThat(result.totalsByMethod().get("CASH")).isEqualByComparingTo("65000.50");
        assertThat(result.totalsByMethod().get("QR")).isEqualByComparingTo("30000");
        assertThat(result.totalsByMethod().get("TRANSFER")).isEqualByComparingTo("20000");
        assertThat(result.totalsByMethod().get("CARD")).isEqualByComparingTo("10000");
        assertThat(result.expectedAmount()).isEqualByComparingTo("65000.50");
        assertThat(result.difference()).isEqualByComparingTo("-5000.50");
        assertThat(result.ticketCount()).isEqualTo(5);
        assertThat(result.id()).isEqualTo(55L);
        assertThat(result.userId()).isEqualTo(1L);
        assertThat(result.userName()).isEqualTo("Clerk");
        assertThat(result.notes()).isEqualTo("Turno mañana");
    }

    @Test
    void shouldCloseCash_WithValidRequest_PersistCashClose() {
        // Given
        givenNothingClosed(1L, DAY);
        givenPayments(DAY, List.of(received(1L, "50000", Ticket.PaymentMethod.CASH)), List.of());

        // When
        paymentService.closeCash(new CashCloseRequest(DAY, new BigDecimal("999999"), new BigDecimal("50000"), "Sin novedad"), 1L);

        // Then: el esperado enviado por el cliente se ignora
        ArgumentCaptor<CashClose> captor = ArgumentCaptor.forClass(CashClose.class);
        verify(cashCloseRepository).save(captor.capture());
        CashClose saved = captor.getValue();
        assertThat(saved.getUser()).isSameAs(clerk);
        assertThat(saved.getCloseDate()).isEqualTo(DAY);
        assertThat(saved.getExpectedAmount()).isEqualByComparingTo("50000");
        assertThat(saved.getActualAmount()).isEqualByComparingTo("50000");
        assertThat(saved.getDifference()).isEqualByComparingTo("0");
        assertThat(saved.getTicketsCount()).isEqualTo(1);
        assertThat(saved.getNotes()).isEqualTo("Sin novedad");
        assertThat(saved.getClosedAt()).isNotNull();
    }

    @Test
    void shouldCloseCash_WhenAlreadyClosedThatDay_ThrowConflict() {
        // Given
        when(userRepository.findById(1L)).thenReturn(Optional.of(clerk));
        when(cashCloseRepository.existsByUserIdAndCloseDate(1L, DAY)).thenReturn(true);

        // When/Then
        assertThatThrownBy(() -> paymentService.closeCash(new CashCloseRequest(DAY, null, BigDecimal.ZERO, null), 1L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    assertThat(business(ex).getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(business(ex).getCode()).isEqualTo("CASH_ALREADY_CLOSED");
                });
        verify(cashCloseRepository, never()).save(any());
        verifyNoInteractions(paymentRepository);
    }

    @Test
    void shouldCloseCash_WithBaggageAndRefunds_AddExcessFeesAndSubtractCashRefunds() {
        // Given: venta en efectivo con exceso de equipaje, otra vendida y cancelada hoy, y un
        // reembolso de una venta de otro día; el exceso de un pago QR no entra en el efectivo
        Payment withBaggage = received(1L, "50000", Ticket.PaymentMethod.CASH);
        withBaggage.getTicket().setBaggage(Baggage.builder().excessFee(new BigDecimal("15000.50")).build());
        Payment qrWithBaggage = received(2L, "30000", Ticket.PaymentMethod.QR);
        qrWithBaggage.getTicket().setBaggage(Baggage.builder().excessFee(new BigDecimal("8000")).build());
        Payment soldAndCancelled = received(3L, "40000", Ticket.PaymentMethod.CASH);
        soldAndCancelled.getTicket().setStatus(Ticket.TicketStatus.CANCELLED);
        soldAndCancelled.getTicket().setRefundAmount(new BigDecimal("36000"));
        Payment cancelledFromAnotherDay = received(4L, "60000", Ticket.PaymentMethod.CASH);
        cancelledFromAnotherDay.getTicket().setStatus(Ticket.TicketStatus.CANCELLED);
        cancelledFromAnotherDay.getTicket().setRefundAmount(new BigDecimal("30000"));
        Payment cancelledWithoutRefund = received(5L, "10000", Ticket.PaymentMethod.CASH);
        cancelledWithoutRefund.getTicket().setStatus(Ticket.TicketStatus.CANCELLED);

        givenNothingClosed(1L, DAY);
        givenPayments(DAY, List.of(withBaggage, qrWithBaggage, soldAndCancelled),
                List.of(soldAndCancelled, cancelledFromAnotherDay, cancelledWithoutRefund));

        // When
        CashCloseResponse result = paymentService.closeCash(new CashCloseRequest(DAY, null, new BigDecimal("39000.50"), null), 1L);

        // Then: (50000 + 40000) + 15000.50 - (36000 + 30000 + 0) = 39000.50
        assertThat(result.baggageTotal()).isEqualByComparingTo("15000.50");
        assertThat(result.refundsTotal()).isEqualByComparingTo("66000");
        assertThat(result.expectedAmount()).isEqualByComparingTo("39000.50");
        assertThat(result.difference()).isEqualByComparingTo("0");
        assertThat(result.ticketCount()).isEqualTo(3);
    }

    @Test
    void shouldCloseCash_CountingOnlyPaidTickets_IgnorePendingAppPurchases() {
        // Given: el cierre se calcula con los pagos registrados (solo existen para tickets PAID);
        // las compras PENDING por la app no tienen pago y no se consultan en tickets
        givenNothingClosed(1L, DAY);
        givenPayments(DAY, List.of(received(1L, "50000", Ticket.PaymentMethod.CASH)), List.of());

        // When
        CashCloseResponse result = paymentService.closeCash(new CashCloseRequest(DAY, null, new BigDecimal("50000"), null), 1L);

        // Then
        assertThat(result.expectedAmount()).isEqualByComparingTo("50000");
        assertThat(result.ticketCount()).isEqualTo(1);
        verifyNoInteractions(ticketRepository);
    }

    @Test
    void shouldCloseCash_WithOfflineSaleOfYesterdaySyncedToday_CountItInTodaysClose() {
        // Criterio: una venta cuenta en la caja del día en que se cobra en el sistema (paidAt); una venta
        // offline se cobra al sincronizarse, así que la de ayer sincronizada hoy entra en el cierre de hoy
        LocalDate today = LocalDate.now();
        Payment offlineSale = received(1L, "50000", Ticket.PaymentMethod.CASH);
        offlineSale.getTicket().setPurchasedAt(today.minusDays(1).atTime(18, 0));
        offlineSale.getTicket().setSyncedAt(today.atTime(7, 0));
        offlineSale.setPaidAt(today.atTime(7, 0));
        givenNothingClosed(1L, today);
        givenPayments(today, List.of(offlineSale), List.of());

        // When
        CashCloseResponse result = paymentService.closeCash(new CashCloseRequest(today, null, new BigDecimal("50000"), null), 1L);

        // Then: se busca por el rango de pago de hoy, no por la fecha de compra
        assertThat(result.expectedAmount()).isEqualByComparingTo("50000");
        assertThat(result.ticketCount()).isEqualTo(1);
        verify(paymentRepository).findReceivedByUserBetween(1L, today.atStartOfDay(), today.plusDays(1).atStartOfDay());
    }

    @Test
    void shouldCloseCash_WithNonExistentUser_ThrowResourceNotFound() {
        // Given
        when(userRepository.findById(1L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> paymentService.closeCash(new CashCloseRequest(DAY, null, BigDecimal.ZERO, null), 1L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Usuario");
        verifyNoInteractions(paymentRepository, cashCloseRepository);
    }

    // ==================== getCashCloses ====================

    private CashClose closeOf(User user, LocalDate date) {
        return CashClose.builder().id(user.getId() * 10).user(user).closeDate(date)
                .expectedAmount(BigDecimal.ZERO).actualAmount(BigDecimal.ZERO).difference(BigDecimal.ZERO)
                .ticketsCount(0).refundsTotal(BigDecimal.ZERO).baggageTotal(BigDecimal.ZERO)
                .closedAt(date.atTime(20, 0)).build();
    }

    @Test
    void shouldGetCashCloses_AsAdminWithDate_ReturnClosesOfAllUsers() {
        // Given
        User admin = User.builder().id(9L).role(User.Role.ADMIN).build();
        when(userRepository.findById(9L)).thenReturn(Optional.of(admin));
        when(cashCloseRepository.findByCloseDateOrderByClosedAtAsc(DAY))
                .thenReturn(List.of(closeOf(clerk, DAY), closeOf(driver, DAY)));

        // When
        List<CashCloseResponse> result = paymentService.getCashCloses(DAY, 9L);

        // Then
        assertThat(result).extracting(CashCloseResponse::userName).containsExactly("Clerk", "Driver");
    }

    @Test
    void shouldGetCashCloses_AsClerk_ReturnOnlyOwnCloses() {
        // Given
        when(userRepository.findById(1L)).thenReturn(Optional.of(clerk));
        when(cashCloseRepository.findByUserIdOrderByCloseDateDesc(1L)).thenReturn(List.of(closeOf(clerk, DAY)));

        // When
        List<CashCloseResponse> result = paymentService.getCashCloses(null, 1L);

        // Then
        assertThat(result).singleElement().satisfies(c -> {
            assertThat(c.userId()).isEqualTo(1L);
            assertThat(c.date()).isEqualTo(DAY);
        });
        verify(cashCloseRepository, never()).findAllByOrderByCloseDateDescClosedAtDesc();
    }

    @Test
    void shouldGetCashCloses_AsDriverWithDate_FilterOwnByDate() {
        // Given
        when(userRepository.findById(2L)).thenReturn(Optional.of(driver));
        when(cashCloseRepository.findByUserIdAndCloseDate(2L, DAY)).thenReturn(List.of());

        // When
        List<CashCloseResponse> result = paymentService.getCashCloses(DAY, 2L);

        // Then
        assertThat(result).isEmpty();
        verify(cashCloseRepository, never()).findByCloseDateOrderByClosedAtAsc(any());
    }
}
