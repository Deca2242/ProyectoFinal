package com.web.service.ticket;

import com.web.dto.ticket.TicketCreateRequest;
import com.web.dto.ticket.TicketResponse;
import com.web.dto.ticket.mapper.TicketMapper;
import com.web.entity.Bus;
import com.web.entity.Route;
import com.web.entity.SeatHold;
import com.web.entity.Stop;
import com.web.entity.Ticket;
import com.web.entity.Trip;
import com.web.entity.User;
import com.web.exception.BusinessException;
import com.web.exception.ResourceNotFoundException;
import com.web.exception.SeatNotAvailableException;
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
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

// Abordaje por QR, proceso de no-show y holds por tramo en la compra
@ExtendWith(MockitoExtension.class)
class TicketBoardingAndNoShowTest {

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
    private com.web.service.notification.NotificationService notificationService;
    @Mock
    private com.web.service.payment.PaymentService paymentService;

    @InjectMocks
    private TicketServiceImpl ticketService;

    private Trip trip;
    private User passenger;
    private Stop fromStop;
    private Stop toStop;
    private Ticket ticket;

    @BeforeEach
    void setUp() {
        Route route = Route.builder().id(1L).code("R001").name("Santa Marta - Barranquilla").build();
        Bus bus = Bus.builder().id(1L).plate("ABC123").capacity(40).status(Bus.BusStatus.ACTIVE).build();

        trip = Trip.builder()
                .id(1L)
                .route(route)
                .bus(bus)
                .tripDate(LocalDate.now().plusDays(1))
                .departureTime(LocalDateTime.now().plusDays(1))
                .status(Trip.TripStatus.BOARDING)
                .build();

        passenger = User.builder().id(1L).name("Pasajero").email("pasajero@test.com").build();
        fromStop = Stop.builder().id(1L).route(route).name("Santa Marta").order(1).build();
        toStop = Stop.builder().id(2L).route(route).name("Barranquilla").order(2).build();

        ticket = Ticket.builder()
                .id(1L)
                .trip(trip)
                .passenger(passenger)
                .seatNumber(10)
                .fromStop(fromStop)
                .toStop(toStop)
                .price(BigDecimal.valueOf(50000))
                .paymentMethod(Ticket.PaymentMethod.CASH)
                .status(Ticket.TicketStatus.SOLD)
                .qrCode("QR-1")
                .build();
    }

    // ---------- boardTicket ----------

    @Test
    void shouldBoardTicket_WithSoldTicketAndBoardingOpen_SetBoardedAt() {
        // Given
        TicketResponse response = mock(TicketResponse.class);
        when(ticketRepository.findByQrCode("QR-1")).thenReturn(Optional.of(ticket));
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(inv -> inv.getArgument(0));
        when(ticketMapper.toResponse(any(Ticket.class))).thenReturn(response);

        // When
        TicketResponse result = ticketService.boardTicket("QR-1");

        // Then
        assertThat(result).isSameAs(response);
        verify(ticketRepository).save(argThat(t -> t.getBoardedAt() != null));
    }

    @Test
    void shouldBoardTicket_WithTripDeparted_AllowIntermediateStopBoarding() {
        // Given
        trip.setStatus(Trip.TripStatus.DEPARTED);
        when(ticketRepository.findByQrCode("QR-1")).thenReturn(Optional.of(ticket));
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(inv -> inv.getArgument(0));

        // When
        ticketService.boardTicket("QR-1");

        // Then
        assertThat(ticket.getBoardedAt()).isNotNull();
    }

    @Test
    void shouldBoardTicket_WithUnknownQr_ThrowNotFound() {
        // Given
        when(ticketRepository.findByQrCode("NOPE")).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> ticketService.boardTicket("NOPE"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void shouldBoardTicket_WithCancelledTicket_ThrowTicketNotValid() {
        // Given
        ticket.setStatus(Ticket.TicketStatus.CANCELLED);
        when(ticketRepository.findByQrCode("QR-1")).thenReturn(Optional.of(ticket));

        // When/Then
        assertThatThrownBy(() -> ticketService.boardTicket("QR-1"))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("TICKET_NOT_VALID");
        verify(ticketRepository, never()).save(any());
    }

    @Test
    void shouldBoardTicket_WithAlreadyBoarded_ThrowConflict() {
        // Given
        ticket.setBoardedAt(LocalDateTime.now().minusMinutes(5));
        when(ticketRepository.findByQrCode("QR-1")).thenReturn(Optional.of(ticket));

        // When/Then
        assertThatThrownBy(() -> ticketService.boardTicket("QR-1"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo("TICKET_ALREADY_BOARDED");
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
    }

    @Test
    void shouldBoardTicket_WithTripScheduled_ThrowBoardingNotOpen() {
        // Given
        trip.setStatus(Trip.TripStatus.SCHEDULED);
        when(ticketRepository.findByQrCode("QR-1")).thenReturn(Optional.of(ticket));

        // When/Then
        assertThatThrownBy(() -> ticketService.boardTicket("QR-1"))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("BOARDING_NOT_OPEN");
    }

    @Test
    void shouldBoardTicket_WithTripCancelled_ThrowBoardingNotOpen() {
        // Given
        trip.setStatus(Trip.TripStatus.CANCELLED);
        when(ticketRepository.findByQrCode("QR-1")).thenReturn(Optional.of(ticket));

        // When/Then
        assertThatThrownBy(() -> ticketService.boardTicket("QR-1"))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("BOARDING_NOT_OPEN");
    }

    // ---------- processNoShows ----------

    @Test
    void shouldProcessNoShows_MarkOnlyReturnedTicketsAsNoShow() {
        // Given
        Ticket other = Ticket.builder().id(2L).status(Ticket.TicketStatus.SOLD).build();
        when(ticketRepository.findUnboardedTicketsDepartingBetween(any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.of(ticket, other));

        // When
        ticketService.processNoShows();

        // Then
        assertThat(ticket.getStatus()).isEqualTo(Ticket.TicketStatus.NO_SHOW);
        assertThat(other.getStatus()).isEqualTo(Ticket.TicketStatus.NO_SHOW);
        verify(ticketRepository, times(2)).save(any(Ticket.class));
    }

    @Test
    void shouldProcessNoShows_UseFiveMinuteWindow() {
        // Given
        when(ticketRepository.findUnboardedTicketsDepartingBetween(any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.of());

        // When
        ticketService.processNoShows();

        // Then
        verify(ticketRepository).findUnboardedTicketsDepartingBetween(
                any(LocalDateTime.class),
                argThat(cutoff -> !cutoff.isBefore(LocalDateTime.now().plusMinutes(4))
                        && !cutoff.isAfter(LocalDateTime.now().plusMinutes(5))));
        verify(ticketRepository, never()).save(any());
        verify(ticketRepository, never()).findAll();
    }

    // ---------- cancelTicket con abordaje ----------

    @Test
    void shouldCancelTicket_WithBoardedPassenger_ThrowAlreadyBoarded() {
        // Given
        ticket.setBoardedAt(LocalDateTime.now().minusMinutes(1));
        when(ticketRepository.findById(1L)).thenReturn(Optional.of(ticket));

        // When/Then
        assertThatThrownBy(() -> ticketService.cancelTicket(1L))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("TICKET_ALREADY_BOARDED");
        verify(ticketRepository, never()).save(any());
    }

    // ---------- purchaseTicket con holds por tramo ----------

    @Test
    void shouldPurchaseTicket_WithOverlappingHoldOfOtherUser_ThrowSeatNotAvailable() {
        // Given
        trip.setStatus(Trip.TripStatus.SCHEDULED);
        TicketCreateRequest request = new TicketCreateRequest(
                1L, 1L, 10, 1L, "Santa Marta", 1, 2L, "Barranquilla", 2,
                BigDecimal.valueOf(50000), Ticket.PaymentMethod.CASH, null, "ADULT");
        SeatHold otherHold = SeatHold.builder()
                .id(5L)
                .user(User.builder().id(99L).build())
                .expiresAt(LocalDateTime.now().plusMinutes(5))
                .build();

        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(userRepository.findById(1L)).thenReturn(Optional.of(passenger));
        when(stopRepository.findById(1L)).thenReturn(Optional.of(fromStop));
        when(stopRepository.findById(2L)).thenReturn(Optional.of(toStop));
        when(seatHoldRepository.findOverlappingActiveHolds(eq(1L), eq(10), eq(1), eq(2), any(LocalDateTime.class)))
                .thenReturn(List.of(otherHold));

        // When/Then
        assertThatThrownBy(() -> ticketService.purchaseTicket(request))
                .isInstanceOf(SeatNotAvailableException.class)
                .hasMessageContaining("otro usuario");
        verify(ticketRepository, never()).save(any());
    }
}
