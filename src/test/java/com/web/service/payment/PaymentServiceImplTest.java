package com.web.service.payment;

import com.web.dto.payment.CashCloseRequest;
import com.web.dto.payment.CashCloseResponse;
import com.web.dto.payment.PaymentConfirmRequest;
import com.web.dto.ticket.TicketResponse;
import com.web.dto.ticket.mapper.TicketMapper;
import com.web.entity.Ticket;
import com.web.entity.Trip;
import com.web.entity.User;
import com.web.exception.ResourceNotFoundException;
import com.web.repository.TicketRepository;
import com.web.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
        when(ticketRepository.findSoldCashTicketsPurchasedBetween(
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
    }
}

