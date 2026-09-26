package com.web.service.ticket;

import com.web.dto.baggage.BaggageCreateRequest;
import com.web.dto.ticket.TicketCancelResponse;
import com.web.dto.ticket.TicketCreateRequest;
import com.web.dto.ticket.TicketResponse;
import com.web.dto.ticket.mapper.TicketMapper;
import com.web.entity.*;
import com.web.exception.InvalidSegmentException;
import com.web.exception.OverbookingNotAllowedException;
import com.web.exception.ResourceNotFoundException;
import com.web.exception.SeatNotAvailableException;
import com.web.repository.*;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;


@ExtendWith(MockitoExtension.class)
class TicketServiceImplTest {

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

    @InjectMocks
    private TicketServiceImpl ticketService;

    private Trip trip;
    private User passenger;
    private Stop fromStop;
    private Stop toStop;
    private Bus bus;
    private Route route;
    private Ticket ticket;
    private TicketResponse ticketResponse;

    @BeforeEach
    void setUp() {
        route = Route.builder()
                .id(1L)
                .code("R001")
                .name("Bogotá - Medellín")
                .build();

        bus = Bus.builder()
                .id(1L)
                .plate("ABC123")
                .capacity(40)
                .status(Bus.BusStatus.ACTIVE)
                .build();

        trip = Trip.builder()
                .id(1L)
                .route(route)
                .bus(bus)
                .tripDate(LocalDate.now().plusDays(1))
                .departureTime(LocalDate.now().plusDays(1).atTime(12, 0))
                .status(Trip.TripStatus.SCHEDULED)
                .build();

        passenger = User.builder()
                .id(1L)
                .name("John Doe")
                .email("john@example.com")
                .role(User.Role.PASSENGER)
                .build();

        fromStop = Stop.builder()
                .id(1L)
                .route(route)
                .name("Bogotá")
                .order(1)
                .build();

        toStop = Stop.builder()
                .id(2L)
                .route(route)
                .name("Medellín")
                .order(2)
                .build();

        ticket = Ticket.builder()
                .id(1L)
                .trip(trip)
                .passenger(passenger)
                .seatNumber(10)
                .fromStop(fromStop)
                .toStop(toStop)
                .price(BigDecimal.valueOf(50000))
                .status(Ticket.TicketStatus.SOLD)
                .paymentMethod(Ticket.PaymentMethod.CASH)
                .build();

        ticketResponse = new TicketResponse(
                1L, 1L, "Bogotá - Medellín", LocalDate.now().plusDays(1),
                LocalDateTime.now().plusDays(1).plusHours(8),
                1L, "John Doe", "john@example.com",
                10, 1L, "Bogotá", 1, 2L, "Medellín", 2,
                BigDecimal.valueOf(50000), Ticket.PaymentMethod.CASH,
                Ticket.TicketStatus.SOLD, "QR123", LocalDateTime.now(), null
        );
    }

    @Test
    void shouldPurchaseTicket_WithValidRequest_ReturnTicketResponse() {
        // Given
        TicketCreateRequest request = new TicketCreateRequest(
                1L, 1L, 10, 1L, "Bogotá", 1, 2L, "Medellín", 2,
                BigDecimal.valueOf(50000), Ticket.PaymentMethod.CASH, null, "ADULT"
        );

        when(configService.getConfig()).thenReturn(createConfigResponse(new HashMap<>()));
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(userRepository.findById(1L)).thenReturn(Optional.of(passenger));
        when(stopRepository.findById(1L)).thenReturn(Optional.of(fromStop));
        when(stopRepository.findById(2L)).thenReturn(Optional.of(toStop));
        when(seatHoldRepository.findActiveHold(eq(1L), eq(10), any(LocalDateTime.class)))
                .thenReturn(Optional.empty());
        when(ticketRepository.isSeatAvailableForSegment(1L, 10, 1, 2)).thenReturn(true);
        when(fareRuleRepository.findByRouteIdAndFromStopIdAndToStopId(1L, 1L, 2L))
                .thenReturn(Optional.empty());
        when(configService.getTicketBasePrice()).thenReturn(BigDecimal.valueOf(50000));
        lenient().when(configService.getTicketPriceMultiplierHighDemand()).thenReturn(BigDecimal.ONE);
        lenient().when(configService.getTicketPriceMultiplierMediumDemand()).thenReturn(BigDecimal.ONE);
        lenient().when(configService.getTicketPriceMultiplierPeakHours()).thenReturn(BigDecimal.ONE);
        when(ticketRepository.countSoldSeats(1L)).thenReturn(20L);
        when(configService.getOverbookingMaxPercentage()).thenReturn(0.05);
        when(ticketMapper.toEntity(request)).thenReturn(ticket);
        when(qrCodeGenerator.generateTicketQr()).thenReturn("QR123");
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(inv -> {
            Ticket t = inv.getArgument(0);
            t.setId(1L);
            return t;
        });
        when(ticketMapper.toResponse(any(Ticket.class))).thenReturn(ticketResponse);

        // When
        TicketResponse result = ticketService.purchaseTicket(request);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.id()).isEqualTo(1L);
        verify(tripRepository).findById(1L);
        verify(userRepository).findById(1L);
        verify(ticketRepository).save(any(Ticket.class));
    }

    @Test
    void shouldPurchaseTicket_WithStudentDiscount_Apply20Percent() {
        // Given
        TicketCreateRequest request = new TicketCreateRequest(
                1L, 1L, 10, 1L, "Bogotá", 1, 2L, "Medellín", 2,
                BigDecimal.valueOf(50000), Ticket.PaymentMethod.CASH, null, "STUDENT"
        );

        Map<String, Integer> discounts = new HashMap<>();
        discounts.put("STUDENT", 20);

        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(userRepository.findById(1L)).thenReturn(Optional.of(passenger));
        when(stopRepository.findById(1L)).thenReturn(Optional.of(fromStop));
        when(stopRepository.findById(2L)).thenReturn(Optional.of(toStop));
        when(seatHoldRepository.findActiveHold(eq(1L), eq(10), any(LocalDateTime.class)))
                .thenReturn(Optional.empty());
        when(ticketRepository.isSeatAvailableForSegment(1L, 10, 1, 2)).thenReturn(true);
        when(fareRuleRepository.findByRouteIdAndFromStopIdAndToStopId(1L, 1L, 2L))
                .thenReturn(Optional.empty());
        when(configService.getTicketBasePrice()).thenReturn(BigDecimal.valueOf(50000));
        lenient().when(configService.getTicketPriceMultiplierHighDemand()).thenReturn(BigDecimal.ONE);
        lenient().when(configService.getTicketPriceMultiplierMediumDemand()).thenReturn(BigDecimal.ONE);
        lenient().when(configService.getTicketPriceMultiplierPeakHours()).thenReturn(BigDecimal.ONE);
        when(ticketRepository.countSoldSeats(1L)).thenReturn(20L);
        when(configService.getOverbookingMaxPercentage()).thenReturn(0.05);
        when(configService.getConfig()).thenReturn(createConfigResponse(discounts));
        when(ticketMapper.toEntity(request)).thenReturn(ticket);
        when(qrCodeGenerator.generateTicketQr()).thenReturn("QR123");
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(inv -> {
            Ticket t = inv.getArgument(0);
            t.setId(1L);
            return t;
        });
        when(ticketMapper.toResponse(any(Ticket.class))).thenReturn(ticketResponse);

        // When
        TicketResponse result = ticketService.purchaseTicket(request);

        // Then
        assertThat(result).isNotNull();
        verify(ticketRepository).save(argThat(t -> {
            // Price should be 50000 * 0.8 = 40000 (20% discount)
            return t.getPrice().compareTo(BigDecimal.valueOf(40000)) == 0;
        }));
    }

    @Test
    void shouldPurchaseTicket_WithSeniorDiscount_Apply15Percent() {
        // Given
        TicketCreateRequest request = new TicketCreateRequest(
                1L, 1L, 10, 1L, "Bogotá", 1, 2L, "Medellín", 2,
                BigDecimal.valueOf(50000), Ticket.PaymentMethod.CASH, null, "SENIOR"
        );

        Map<String, Integer> discounts = new HashMap<>();
        discounts.put("SENIOR", 15);

        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(userRepository.findById(1L)).thenReturn(Optional.of(passenger));
        when(stopRepository.findById(1L)).thenReturn(Optional.of(fromStop));
        when(stopRepository.findById(2L)).thenReturn(Optional.of(toStop));
        when(seatHoldRepository.findActiveHold(eq(1L), eq(10), any(LocalDateTime.class)))
                .thenReturn(Optional.empty());
        when(ticketRepository.isSeatAvailableForSegment(1L, 10, 1, 2)).thenReturn(true);
        when(fareRuleRepository.findByRouteIdAndFromStopIdAndToStopId(1L, 1L, 2L))
                .thenReturn(Optional.empty());
        when(configService.getTicketBasePrice()).thenReturn(BigDecimal.valueOf(50000));
        lenient().when(configService.getTicketPriceMultiplierHighDemand()).thenReturn(BigDecimal.ONE);
        lenient().when(configService.getTicketPriceMultiplierMediumDemand()).thenReturn(BigDecimal.ONE);
        lenient().when(configService.getTicketPriceMultiplierPeakHours()).thenReturn(BigDecimal.ONE);
        when(ticketRepository.countSoldSeats(1L)).thenReturn(20L);
        when(configService.getOverbookingMaxPercentage()).thenReturn(0.05);
        when(configService.getConfig()).thenReturn(createConfigResponse(discounts));
        when(ticketMapper.toEntity(request)).thenReturn(ticket);
        when(qrCodeGenerator.generateTicketQr()).thenReturn("QR123");
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(inv -> {
            Ticket t = inv.getArgument(0);
            t.setId(1L);
            return t;
        });
        when(ticketMapper.toResponse(any(Ticket.class))).thenReturn(ticketResponse);

        // When
        TicketResponse result = ticketService.purchaseTicket(request);

        // Then
        assertThat(result).isNotNull();
        verify(ticketRepository).save(argThat(t -> {
            // Price should be 50000 * 0.85 = 42500 (15% discount)
            return t.getPrice().compareTo(BigDecimal.valueOf(42500)) == 0;
        }));
    }

    @Test
    void shouldPurchaseTicket_WithChildDiscount_Apply50Percent() {
        // Given
        TicketCreateRequest request = new TicketCreateRequest(
                1L, 1L, 10, 1L, "Bogotá", 1, 2L, "Medellín", 2,
                BigDecimal.valueOf(50000), Ticket.PaymentMethod.CASH, null, "CHILD"
        );

        Map<String, Integer> discounts = new HashMap<>();
        discounts.put("CHILD", 50);

        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(userRepository.findById(1L)).thenReturn(Optional.of(passenger));
        when(stopRepository.findById(1L)).thenReturn(Optional.of(fromStop));
        when(stopRepository.findById(2L)).thenReturn(Optional.of(toStop));
        when(seatHoldRepository.findActiveHold(eq(1L), eq(10), any(LocalDateTime.class)))
                .thenReturn(Optional.empty());
        when(ticketRepository.isSeatAvailableForSegment(1L, 10, 1, 2)).thenReturn(true);
        when(fareRuleRepository.findByRouteIdAndFromStopIdAndToStopId(1L, 1L, 2L))
                .thenReturn(Optional.empty());
        when(configService.getTicketBasePrice()).thenReturn(BigDecimal.valueOf(50000));
        lenient().when(configService.getTicketPriceMultiplierHighDemand()).thenReturn(BigDecimal.ONE);
        lenient().when(configService.getTicketPriceMultiplierMediumDemand()).thenReturn(BigDecimal.ONE);
        lenient().when(configService.getTicketPriceMultiplierPeakHours()).thenReturn(BigDecimal.ONE);
        when(ticketRepository.countSoldSeats(1L)).thenReturn(20L);
        when(configService.getOverbookingMaxPercentage()).thenReturn(0.05);
        when(configService.getConfig()).thenReturn(createConfigResponse(discounts));
        when(ticketMapper.toEntity(request)).thenReturn(ticket);
        when(qrCodeGenerator.generateTicketQr()).thenReturn("QR123");
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(inv -> {
            Ticket t = inv.getArgument(0);
            t.setId(1L);
            return t;
        });
        when(ticketMapper.toResponse(any(Ticket.class))).thenReturn(ticketResponse);

        // When
        TicketResponse result = ticketService.purchaseTicket(request);

        // Then
        assertThat(result).isNotNull();
        verify(ticketRepository).save(argThat(t -> {
            // Price should be 50000 * 0.5 = 25000 (50% discount)
            return t.getPrice().compareTo(BigDecimal.valueOf(25000)) == 0;
        }));
    }

    @Test
    void shouldPurchaseTicket_WithOverbookingExceeded_ThrowException() {
        // Given
        TicketCreateRequest request = new TicketCreateRequest(
                1L, 1L, 10, 1L, "Bogotá", 1, 2L, "Medellín", 2,
                BigDecimal.valueOf(50000), Ticket.PaymentMethod.CASH, null, "ADULT"
        );

        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(userRepository.findById(1L)).thenReturn(Optional.of(passenger));
        when(stopRepository.findById(1L)).thenReturn(Optional.of(fromStop));
        when(stopRepository.findById(2L)).thenReturn(Optional.of(toStop));
        when(seatHoldRepository.findActiveHold(eq(1L), eq(10), any(LocalDateTime.class)))
                .thenReturn(Optional.empty());
        when(ticketRepository.isSeatAvailableForSegment(1L, 10, 1, 2)).thenReturn(true);
        when(ticketRepository.countSoldSeats(1L)).thenReturn(42L); // 42/40 = 105% occupancy
        when(configService.getOverbookingMaxPercentage()).thenReturn(0.05); // Max 5% overbooking

        // When/Then
        assertThatThrownBy(() -> ticketService.purchaseTicket(request))
                .isInstanceOf(OverbookingNotAllowedException.class)
                .hasMessageContaining("overbooking");
    }

    @Test
    void shouldPurchaseTicket_WithSeatNotAvailable_ThrowException() {
        // Given
        TicketCreateRequest request = new TicketCreateRequest(
                1L, 1L, 10, 1L, "Bogotá", 1, 2L, "Medellín", 2,
                BigDecimal.valueOf(50000), Ticket.PaymentMethod.CASH, null, "ADULT"
        );

        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(userRepository.findById(1L)).thenReturn(Optional.of(passenger));
        when(stopRepository.findById(1L)).thenReturn(Optional.of(fromStop));
        when(stopRepository.findById(2L)).thenReturn(Optional.of(toStop));
        when(seatHoldRepository.findActiveHold(eq(1L), eq(10), any(LocalDateTime.class)))
                .thenReturn(Optional.empty());
        when(ticketRepository.isSeatAvailableForSegment(1L, 10, 1, 2)).thenReturn(false);

        // When/Then
        assertThatThrownBy(() -> ticketService.purchaseTicket(request))
                .isInstanceOf(SeatNotAvailableException.class)
                .hasMessageContaining("no está disponible");
    }

    @Test
    void shouldPurchaseTicket_WithInvalidTripStatus_ThrowException() {
        // Given
        trip.setStatus(Trip.TripStatus.DEPARTED);
        TicketCreateRequest request = new TicketCreateRequest(
                1L, 1L, 10, 1L, "Bogotá", 1, 2L, "Medellín", 2,
                BigDecimal.valueOf(50000), Ticket.PaymentMethod.CASH, null, "ADULT"
        );

        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));

        // When/Then
        assertThatThrownBy(() -> ticketService.purchaseTicket(request))
                .isInstanceOf(InvalidSegmentException.class)
                .hasMessageContaining("no está disponible");
    }

    @Test
    void shouldCancelTicket_WithValidTicket_ReturnRefundResponse() {
        // Given
        ticket.setStatus(Ticket.TicketStatus.SOLD);
        LocalDateTime departureTime = LocalDateTime.now().plusHours(50); // 50 hours before departure
        trip.setDepartureTime(departureTime);

        when(ticketRepository.findById(1L)).thenReturn(Optional.of(ticket));
        when(configService.getRefundPercentage48Hours()).thenReturn(BigDecimal.valueOf(90));
        when(ticketRepository.save(any(Ticket.class))).thenReturn(ticket);

        // When
        TicketCancelResponse result = ticketService.cancelTicket(1L);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.ticketId()).isEqualTo(1L);
        assertThat(result.status()).isEqualTo(Ticket.TicketStatus.CANCELLED);
        assertThat(result.refundPercentage()).isEqualTo(90);
        verify(ticketRepository).save(argThat(t -> t.getStatus() == Ticket.TicketStatus.CANCELLED));
    }

    @Test
    void shouldCancelTicket_WithInvalidTicket_ThrowException() {
        // Given
        when(ticketRepository.findById(1L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> ticketService.cancelTicket(1L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Ticket");
    }

    @Test
    void shouldGetTicketById_WithValidId_ReturnTicketResponse() {
        // Given
        when(ticketRepository.findById(1L)).thenReturn(Optional.of(ticket));
        when(ticketMapper.toResponse(ticket)).thenReturn(ticketResponse);

        // When
        TicketResponse result = ticketService.getTicketById(1L);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.id()).isEqualTo(1L);
        verify(ticketRepository).findById(1L);
        verify(ticketMapper).toResponse(ticket);
    }

    @Test
    void shouldGetUserTickets_WithValidUserId_ReturnList() {
        // Given
        List<Ticket> tickets = List.of(ticket);
        List<TicketResponse> responses = List.of(ticketResponse);

        when(ticketRepository.findByPassengerId(1L)).thenReturn(tickets);
        when(ticketMapper.toResponseList(tickets)).thenReturn(responses);

        // When
        List<TicketResponse> result = ticketService.getUserTickets(1L);

        // Then
        assertThat(result).isNotNull();
        assertThat(result).hasSize(1);
        verify(ticketRepository).findByPassengerId(1L);
    }

    private com.web.dto.admin.ConfigResponse createConfigResponse(Map<String, Integer> discounts) {
        return new com.web.dto.admin.ConfigResponse(
                10, 10, 5, discounts,
                BigDecimal.valueOf(23.0), BigDecimal.valueOf(5000),
                BigDecimal.valueOf(10000), 0.05,
                BigDecimal.valueOf(90), BigDecimal.valueOf(70),
                BigDecimal.valueOf(50), BigDecimal.valueOf(30), BigDecimal.ZERO,
                BigDecimal.valueOf(50000), BigDecimal.valueOf(1.15),
                BigDecimal.valueOf(1.2), BigDecimal.valueOf(1.1),
                LocalDateTime.now()
        );
    }
}

