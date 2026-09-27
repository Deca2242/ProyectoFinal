package com.web.service.payment;

import com.web.dto.payment.CashCloseRequest;
import com.web.dto.payment.CashCloseResponse;
import com.web.dto.payment.PaymentConfirmRequest;
import com.web.dto.ticket.TicketResponse;
import com.web.dto.ticket.mapper.TicketMapper;
import com.web.entity.Ticket;
import com.web.entity.Trip;
import com.web.entity.User;
import com.web.exception.BusinessException;
import com.web.exception.ResourceNotFoundException;
import com.web.repository.TicketRepository;
import com.web.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
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
    private TicketMapper ticketMapper;

    @InjectMocks
    private PaymentServiceImpl paymentService;

    private Ticket ticket;
    private Trip trip;
    private User user;
    private TicketResponse ticketResponse;

    @BeforeEach
    void setUp() {
        trip = Trip.builder()
                .id(1L)
                .tripDate(LocalDate.now())
                .build();

        ticket = Ticket.builder()
                .id(1L)
                .trip(trip)
                .price(BigDecimal.valueOf(50000))
                .status(Ticket.TicketStatus.SOLD)
                .paymentMethod(Ticket.PaymentMethod.CASH)
                .build();

        user = User.builder()
                .id(1L)
                .name("Clerk")
                .email("clerk@example.com")
                .build();

        ticketResponse = new TicketResponse(
                1L, 1L, "Route Name", LocalDate.now(), LocalDateTime.now(),
                1L, "Passenger", "passenger@example.com",
                10, 1L, "Origin", 1, 2L, "Destination", 2,
                BigDecimal.valueOf(50000), Ticket.PaymentMethod.CASH,
                Ticket.TicketStatus.SOLD, "QR123", LocalDateTime.now(), null
        , null);
    }

    @Test
    void shouldConfirmPayment_WithValidRequest_ReturnTicketResponse() {
        // Given
        PaymentConfirmRequest request = new PaymentConfirmRequest(
                1L, Ticket.PaymentMethod.CARD, null, null, null
        );

        when(ticketRepository.findById(1L)).thenReturn(Optional.of(ticket));
        when(ticketRepository.save(any(Ticket.class))).thenReturn(ticket);
        when(ticketMapper.toResponse(any(Ticket.class))).thenReturn(ticketResponse);

        // When
        TicketResponse result = paymentService.confirmPayment(request);

        // Then
        assertThat(result).isNotNull();
        verify(ticketRepository).save(argThat(t -> 
            t.getPaymentMethod() == Ticket.PaymentMethod.CARD
        ));
    }

    @Test
    void shouldCloseCash_WithValidRequest_ReturnCashCloseResponse() {
        // Given
        CashCloseRequest request = new CashCloseRequest(
                1L, LocalDate.now(), BigDecimal.valueOf(100000), BigDecimal.valueOf(100000), null
        );

        List<Ticket> cashTickets = List.of(ticket);

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(ticketRepository.findCashTicketsPurchasedBetween(
                LocalDate.now().atStartOfDay(), LocalDate.now().plusDays(1).atStartOfDay()))
                .thenReturn(cashTickets);

        // When
        CashCloseResponse result = paymentService.closeCash(request, 1L);

        // Then: el esperado lo calcula el sistema (1 ticket de 50000) y se compara con lo reportado
        assertThat(result).isNotNull();
        assertThat(result.id()).isEqualTo(1L);
        assertThat(result.ticketCount()).isEqualTo(1);
        assertThat(result.expectedAmount()).isEqualByComparingTo("50000");
        assertThat(result.difference()).isEqualByComparingTo("50000");
        verify(userRepository).findById(1L);
    }

    @Test
    void shouldCloseCash_WithInvalidDateRange_ThrowException() {
        // Given
        CashCloseRequest request = new CashCloseRequest(
                1L, LocalDate.now(), BigDecimal.valueOf(100000), BigDecimal.valueOf(100000), null
        );

        when(userRepository.findById(1L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> paymentService.closeCash(request, 1L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Usuario");
        verifyNoInteractions(ticketRepository);
    }

    // ==================== confirmPayment ====================

    @Test
    void shouldConfirmPayment_WithNonExistentTicket_ThrowResourceNotFound() {
        // Given
        PaymentConfirmRequest request = new PaymentConfirmRequest(
                99L, Ticket.PaymentMethod.CARD, null, null, null
        );
        when(ticketRepository.findById(99L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> paymentService.confirmPayment(request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Ticket");
        verify(ticketRepository, never()).save(any());
        verifyNoInteractions(ticketMapper);
    }

    @ParameterizedTest
    @EnumSource(value = Ticket.TicketStatus.class, names = {"CANCELLED", "NO_SHOW"})
    void shouldConfirmPayment_WithTicketNotSold_ThrowConflictInvalidTicketStatus(Ticket.TicketStatus status) {
        // Given
        ticket.setStatus(status);
        PaymentConfirmRequest request = new PaymentConfirmRequest(
                1L, Ticket.PaymentMethod.TRANSFER, "REF-1", BigDecimal.valueOf(50000), null
        );
        when(ticketRepository.findById(1L)).thenReturn(Optional.of(ticket));

        // When/Then
        assertThatThrownBy(() -> paymentService.confirmPayment(request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(be.getCode()).isEqualTo("INVALID_TICKET_STATUS");
                });
        assertThat(ticket.getPaymentMethod()).isEqualTo(Ticket.PaymentMethod.CASH);
        verify(ticketRepository, never()).save(any());
        verifyNoInteractions(ticketMapper);
    }

    @ParameterizedTest
    @EnumSource(Ticket.PaymentMethod.class)
    void shouldConfirmPayment_WithAnyPaymentMethod_UpdateTicketPaymentMethod(Ticket.PaymentMethod method) {
        // Given
        PaymentConfirmRequest request = new PaymentConfirmRequest(1L, method, null, null, null);
        when(ticketRepository.findById(1L)).thenReturn(Optional.of(ticket));
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(inv -> inv.getArgument(0));
        when(ticketMapper.toResponse(ticket)).thenReturn(ticketResponse);

        // When
        TicketResponse result = paymentService.confirmPayment(request);

        // Then
        assertThat(result).isEqualTo(ticketResponse);
        assertThat(ticket.getPaymentMethod()).isEqualTo(method);
        assertThat(ticket.getStatus()).isEqualTo(Ticket.TicketStatus.SOLD);
    }

    // ==================== closeCash ====================

    @Test
    void shouldCloseCash_WithoutCashTickets_ReturnZeroExpectedAndFullDifference() {
        // Given
        LocalDate date = LocalDate.of(2026, 3, 15);
        CashCloseRequest request = new CashCloseRequest(
                1L, date, null, BigDecimal.valueOf(20000), null
        );
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(ticketRepository.findCashTicketsPurchasedBetween(
                date.atStartOfDay(), date.plusDays(1).atStartOfDay()))
                .thenReturn(List.of());

        // When
        CashCloseResponse result = paymentService.closeCash(request, 1L);

        // Then
        assertThat(result.ticketCount()).isZero();
        assertThat(result.expectedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.actualAmount()).isEqualByComparingTo("20000");
        assertThat(result.difference()).isEqualByComparingTo("20000");
    }

    @Test
    void shouldCloseCash_WithCashShortage_ReturnNegativeDifference() {
        // Given: esperado 50000 + 30000.50 = 80000.50, reportado 70000 => faltante de 10000.50
        LocalDate date = LocalDate.of(2026, 3, 15);
        Ticket second = Ticket.builder()
                .id(2L)
                .trip(trip)
                .price(new BigDecimal("30000.50"))
                .status(Ticket.TicketStatus.SOLD)
                .paymentMethod(Ticket.PaymentMethod.CASH)
                .build();
        CashCloseRequest request = new CashCloseRequest(
                1L, date, null, BigDecimal.valueOf(70000), "Cierre turno tarde"
        );
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(ticketRepository.findCashTicketsPurchasedBetween(
                date.atStartOfDay(), date.plusDays(1).atStartOfDay()))
                .thenReturn(List.of(ticket, second));

        // When
        CashCloseResponse result = paymentService.closeCash(request, 1L);

        // Then
        assertThat(result.ticketCount()).isEqualTo(2);
        assertThat(result.expectedAmount()).isEqualByComparingTo("80000.50");
        assertThat(result.difference()).isEqualByComparingTo("-10000.50");
        assertThat(result.difference().signum()).isNegative();
    }

    @Test
    void shouldCloseCash_WithReportedExpectedAmount_IgnoreItAndUseTicketsOfTheDay() {
        // Given: el esperado enviado por el cliente (999999) no se usa
        LocalDate date = LocalDate.of(2026, 3, 15);
        CashCloseRequest request = new CashCloseRequest(
                1L, date, BigDecimal.valueOf(999999), BigDecimal.valueOf(50000), null
        );
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(ticketRepository.findCashTicketsPurchasedBetween(
                date.atStartOfDay(), date.plusDays(1).atStartOfDay()))
                .thenReturn(List.of(ticket));

        // When
        CashCloseResponse result = paymentService.closeCash(request, 1L);

        // Then: el cuadre es exacto y la respuesta refleja usuario, fecha y cierre
        assertThat(result.expectedAmount()).isEqualByComparingTo("50000");
        assertThat(result.difference()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.id()).isEqualTo(1L);
        assertThat(result.userName()).isEqualTo("Clerk");
        assertThat(result.date()).isEqualTo(date);
        assertThat(result.closedAt()).isNotNull();
        verify(ticketRepository).findCashTicketsPurchasedBetween(
                LocalDateTime.of(2026, 3, 15, 0, 0), LocalDateTime.of(2026, 3, 16, 0, 0));
    }
}

