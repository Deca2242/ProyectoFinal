package com.web.service.ticket;

import com.web.dto.baggage.BaggageCreateRequest;
import com.web.dto.ticket.TicketCancelResponse;
import com.web.dto.ticket.TicketCreateRequest;
import com.web.dto.ticket.TicketResponse;
import com.web.dto.ticket.mapper.TicketMapper;
import com.web.entity.*;
import com.web.exception.BusinessException;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
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

    @Mock
    private com.web.service.notification.NotificationService notificationService;

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
        , null);
    }

    @Test
    void shouldPurchaseTicket_WithValidRequest_ReturnTicketResponse() {
        // Given
        TicketCreateRequest request = new TicketCreateRequest(
                1L, 1L, 10, 1L, "Bogotá", 1, 2L, "Medellín", 2,
                BigDecimal.valueOf(50000), Ticket.PaymentMethod.CASH, null, "ADULT"
        );

        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(userRepository.findById(1L)).thenReturn(Optional.of(passenger));
        when(stopRepository.findById(1L)).thenReturn(Optional.of(fromStop));
        when(stopRepository.findById(2L)).thenReturn(Optional.of(toStop));
        when(seatHoldRepository.findOverlappingActiveHolds(eq(1L), eq(10), anyInt(), anyInt(), any(LocalDateTime.class)))
                .thenReturn(List.of());
        when(ticketRepository.isSeatAvailableForSegment(1L, 10, 1, 2)).thenReturn(true);
        when(fareRuleRepository.findByRouteIdAndFromStopIdAndToStopId(1L, 1L, 2L))
                .thenReturn(Optional.empty());
        when(configService.getTicketBasePrice()).thenReturn(BigDecimal.valueOf(50000));
        lenient().when(configService.getTicketPriceMultiplierHighDemand()).thenReturn(BigDecimal.ONE);
        lenient().when(configService.getTicketPriceMultiplierMediumDemand()).thenReturn(BigDecimal.ONE);
        lenient().when(configService.getTicketPriceMultiplierPeakHours()).thenReturn(BigDecimal.ONE);
        when(ticketRepository.countSoldSeatsForSegment(1L, 1, 2)).thenReturn(20L);
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
        when(seatHoldRepository.findOverlappingActiveHolds(eq(1L), eq(10), anyInt(), anyInt(), any(LocalDateTime.class)))
                .thenReturn(List.of());
        when(ticketRepository.isSeatAvailableForSegment(1L, 10, 1, 2)).thenReturn(true);
        when(fareRuleRepository.findByRouteIdAndFromStopIdAndToStopId(1L, 1L, 2L))
                .thenReturn(Optional.empty());
        when(configService.getTicketBasePrice()).thenReturn(BigDecimal.valueOf(50000));
        lenient().when(configService.getTicketPriceMultiplierHighDemand()).thenReturn(BigDecimal.ONE);
        lenient().when(configService.getTicketPriceMultiplierMediumDemand()).thenReturn(BigDecimal.ONE);
        lenient().when(configService.getTicketPriceMultiplierPeakHours()).thenReturn(BigDecimal.ONE);
        when(ticketRepository.countSoldSeatsForSegment(1L, 1, 2)).thenReturn(20L);
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
        when(seatHoldRepository.findOverlappingActiveHolds(eq(1L), eq(10), anyInt(), anyInt(), any(LocalDateTime.class)))
                .thenReturn(List.of());
        when(ticketRepository.isSeatAvailableForSegment(1L, 10, 1, 2)).thenReturn(true);
        when(fareRuleRepository.findByRouteIdAndFromStopIdAndToStopId(1L, 1L, 2L))
                .thenReturn(Optional.empty());
        when(configService.getTicketBasePrice()).thenReturn(BigDecimal.valueOf(50000));
        lenient().when(configService.getTicketPriceMultiplierHighDemand()).thenReturn(BigDecimal.ONE);
        lenient().when(configService.getTicketPriceMultiplierMediumDemand()).thenReturn(BigDecimal.ONE);
        lenient().when(configService.getTicketPriceMultiplierPeakHours()).thenReturn(BigDecimal.ONE);
        when(ticketRepository.countSoldSeatsForSegment(1L, 1, 2)).thenReturn(20L);
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
        when(seatHoldRepository.findOverlappingActiveHolds(eq(1L), eq(10), anyInt(), anyInt(), any(LocalDateTime.class)))
                .thenReturn(List.of());
        when(ticketRepository.isSeatAvailableForSegment(1L, 10, 1, 2)).thenReturn(true);
        when(fareRuleRepository.findByRouteIdAndFromStopIdAndToStopId(1L, 1L, 2L))
                .thenReturn(Optional.empty());
        when(configService.getTicketBasePrice()).thenReturn(BigDecimal.valueOf(50000));
        lenient().when(configService.getTicketPriceMultiplierHighDemand()).thenReturn(BigDecimal.ONE);
        lenient().when(configService.getTicketPriceMultiplierMediumDemand()).thenReturn(BigDecimal.ONE);
        lenient().when(configService.getTicketPriceMultiplierPeakHours()).thenReturn(BigDecimal.ONE);
        when(ticketRepository.countSoldSeatsForSegment(1L, 1, 2)).thenReturn(20L);
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
        // Given: la silla 41 supera la capacidad (40) y no hay sillas de overbooking aprobadas
        TicketCreateRequest request = new TicketCreateRequest(
                1L, 1L, 41, 1L, "Bogotá", 1, 2L, "Medellín", 2,
                BigDecimal.valueOf(50000), Ticket.PaymentMethod.CASH, null, "ADULT"
        );

        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(userRepository.findById(1L)).thenReturn(Optional.of(passenger));
        when(stopRepository.findById(1L)).thenReturn(Optional.of(fromStop));
        when(stopRepository.findById(2L)).thenReturn(Optional.of(toStop));
        when(seatHoldRepository.findOverlappingActiveHolds(eq(1L), eq(41), anyInt(), anyInt(), any(LocalDateTime.class)))
                .thenReturn(List.of());
        when(ticketRepository.isSeatAvailableForSegment(1L, 41, 1, 2)).thenReturn(true);

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
        when(seatHoldRepository.findOverlappingActiveHolds(eq(1L), eq(10), anyInt(), anyInt(), any(LocalDateTime.class)))
                .thenReturn(List.of());
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

    // ==================== purchaseTicket: entidades inexistentes ====================

    @Test
    void shouldPurchaseTicket_WithNonExistentTrip_ThrowResourceNotFound() {
        // Given
        TicketCreateRequest request = buildRequest(10, null, null);
        when(tripRepository.findById(1L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> ticketService.purchaseTicket(request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Viaje");
        verifyNoInteractions(userRepository, stopRepository, ticketRepository);
    }

    @Test
    void shouldPurchaseTicket_WithNonExistentPassenger_ThrowResourceNotFound() {
        // Given
        TicketCreateRequest request = buildRequest(10, null, null);
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(userRepository.findById(1L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> ticketService.purchaseTicket(request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Pasajero");
        verifyNoInteractions(stopRepository, ticketRepository);
    }

    @Test
    void shouldPurchaseTicket_WithNonExistentFromStop_ThrowResourceNotFound() {
        // Given
        TicketCreateRequest request = buildRequest(10, null, null);
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(userRepository.findById(1L)).thenReturn(Optional.of(passenger));
        when(stopRepository.findById(1L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> ticketService.purchaseTicket(request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Parada de origen");
        verifyNoInteractions(ticketRepository);
    }

    @Test
    void shouldPurchaseTicket_WithNonExistentToStop_ThrowResourceNotFound() {
        // Given
        TicketCreateRequest request = buildRequest(10, null, null);
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(userRepository.findById(1L)).thenReturn(Optional.of(passenger));
        when(stopRepository.findById(1L)).thenReturn(Optional.of(fromStop));
        when(stopRepository.findById(2L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> ticketService.purchaseTicket(request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Parada de destino");
        verifyNoInteractions(ticketRepository);
    }

    @ParameterizedTest
    @EnumSource(value = Trip.TripStatus.class, names = {"DEPARTED", "ARRIVED", "CANCELLED"})
    void shouldPurchaseTicket_WithTripNotScheduled_ThrowInvalidSegment(Trip.TripStatus status) {
        // Given
        trip.setStatus(status);
        TicketCreateRequest request = buildRequest(10, null, null);
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));

        // When/Then
        assertThatThrownBy(() -> ticketService.purchaseTicket(request))
                .isInstanceOf(InvalidSegmentException.class)
                .hasMessageContaining(status.name());
        verifyNoInteractions(userRepository, ticketRepository);
    }

    // ==================== purchaseTicket: validación del tramo ====================

    @Test
    void shouldPurchaseTicket_WithFromStopFromAnotherRoute_ThrowInvalidSegment() {
        // Given
        fromStop.setRoute(Route.builder().id(99L).build());
        TicketCreateRequest request = buildRequest(10, null, null);
        stubEntitiesFound();

        // When/Then
        assertThatThrownBy(() -> ticketService.purchaseTicket(request))
                .isInstanceOf(InvalidSegmentException.class)
                .hasMessageContaining("no pertenecen a la ruta");
        verifyNoInteractions(seatHoldRepository, ticketRepository);
    }

    @Test
    void shouldPurchaseTicket_WithToStopFromAnotherRoute_ThrowInvalidSegment() {
        // Given
        toStop.setRoute(Route.builder().id(99L).build());
        TicketCreateRequest request = buildRequest(10, null, null);
        stubEntitiesFound();

        // When/Then
        assertThatThrownBy(() -> ticketService.purchaseTicket(request))
                .isInstanceOf(InvalidSegmentException.class)
                .hasMessageContaining("no pertenecen a la ruta");
        verifyNoInteractions(seatHoldRepository, ticketRepository);
    }

    @ParameterizedTest
    @CsvSource({"2, 2", "3, 1"})
    void shouldPurchaseTicket_WithOriginNotBeforeDestination_ThrowInvalidSegment(int fromOrder, int toOrder) {
        // Given: origen igual o posterior al destino
        fromStop.setOrder(fromOrder);
        toStop.setOrder(toOrder);
        TicketCreateRequest request = buildRequest(10, null, null);
        stubEntitiesFound();

        // When/Then
        assertThatThrownBy(() -> ticketService.purchaseTicket(request))
                .isInstanceOf(InvalidSegmentException.class)
                .hasMessageContaining("anterior a la de destino");
        verifyNoInteractions(seatHoldRepository, ticketRepository);
    }

    // ==================== purchaseTicket: holds y disponibilidad ====================

    @Test
    void shouldPurchaseTicket_WithActiveHoldFromAnotherUser_ThrowSeatNotAvailable() {
        // Given
        TicketCreateRequest request = buildRequest(10, null, null);
        SeatHold foreignHold = SeatHold.builder()
                .id(5L)
                .trip(trip)
                .seatNumber(10)
                .user(User.builder().id(2L).build())
                .expiresAt(LocalDateTime.now().plusMinutes(5))
                .build();
        stubEntitiesFound();
        when(seatHoldRepository.findOverlappingActiveHolds(eq(1L), eq(10), anyInt(), anyInt(), any(LocalDateTime.class)))
                .thenReturn(List.of(foreignHold));

        // When/Then
        assertThatThrownBy(() -> ticketService.purchaseTicket(request))
                .isInstanceOf(SeatNotAvailableException.class)
                .hasMessageContaining("hold activo de otro usuario")
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(be.getCode()).isEqualTo("SEAT_NOT_AVAILABLE");
                });
        verify(ticketRepository, never()).isSeatAvailableForSegment(anyLong(), anyInt(), anyInt(), anyInt());
        verify(ticketRepository, never()).save(any());
        verifyNoInteractions(seatHoldService);
    }

    @Test
    void shouldPurchaseTicket_WithOwnActiveHold_ReleaseHoldAfterSale() {
        // Given: el pasajero tiene un hold activo sobre el mismo asiento
        TicketCreateRequest request = buildRequest(10, null, null);
        SeatHold ownHold = SeatHold.builder()
                .id(5L)
                .trip(trip)
                .seatNumber(10)
                .user(passenger)
                .expiresAt(LocalDateTime.now().plusMinutes(5))
                .build();
        stubEntitiesFound();
        when(seatHoldRepository.findOverlappingActiveHolds(eq(1L), eq(10), anyInt(), anyInt(), any(LocalDateTime.class)))
                .thenReturn(List.of(ownHold));
        when(ticketRepository.isSeatAvailableForSegment(1L, 10, 1, 2)).thenReturn(true);
        stubOverbookingCheck(20L, 0.05);
        stubConfigBasePrice(BigDecimal.valueOf(50000));
        stubTicketPersistence(request);

        // When
        TicketResponse result = ticketService.purchaseTicket(request);

        // Then
        assertThat(result).isEqualTo(ticketResponse);
        verify(seatHoldService).releaseHold(5L);
    }

    @Test
    void shouldPurchaseTicket_WithValidRequest_SetRelationsQrAndNotReleaseAnyHold() {
        // Given
        TicketCreateRequest request = buildRequest(10, null, null);
        stubEntitiesFound();
        stubSeatFree(10);
        stubOverbookingCheck(20L, 0.05);
        stubConfigBasePrice(BigDecimal.valueOf(50000));
        stubTicketPersistence(request);

        // When
        ticketService.purchaseTicket(request);

        // Then
        ArgumentCaptor<Ticket> captor = ArgumentCaptor.forClass(Ticket.class);
        verify(ticketRepository).save(captor.capture());
        Ticket saved = captor.getValue();
        assertThat(saved.getTrip()).isSameAs(trip);
        assertThat(saved.getPassenger()).isSameAs(passenger);
        assertThat(saved.getFromStop()).isSameAs(fromStop);
        assertThat(saved.getToStop()).isSameAs(toStop);
        assertThat(saved.getQrCode()).isEqualTo("QR123");
        assertThat(saved.getPrice()).isEqualByComparingTo("50000");
        assertThat(saved.getBaggage()).isNull();
        verifyNoInteractions(seatHoldService, baggageRepository);
    }

    @Test
    void shouldPurchaseTicket_CheckSegmentAvailabilityUsingStopOrdersNotIds() {
        // Given: los IDs de las paradas no coinciden con su orden en la ruta
        fromStop.setId(7L);
        fromStop.setOrder(2);
        toStop.setId(9L);
        toStop.setOrder(5);
        TicketCreateRequest request = buildRequest(10, null, null);
        stubEntitiesFound();
        when(seatHoldRepository.findOverlappingActiveHolds(eq(1L), eq(10), anyInt(), anyInt(), any(LocalDateTime.class)))
                .thenReturn(List.of());
        when(ticketRepository.isSeatAvailableForSegment(1L, 10, 2, 5)).thenReturn(false);

        // When/Then: la consulta se hace con el ORDEN (2, 5), no con los IDs (7, 9)
        assertThatThrownBy(() -> ticketService.purchaseTicket(request))
                .isInstanceOf(SeatNotAvailableException.class)
                .hasMessageContaining("tramo seleccionado");
        verify(ticketRepository).isSeatAvailableForSegment(1L, 10, 2, 5);
        verify(ticketRepository, never()).isSeatAvailableForSegment(1L, 10, 7, 9);
        verify(ticketRepository, never()).countSoldSeatsForSegment(anyLong(), anyInt(), anyInt());
    }

    // ==================== purchaseTicket: overbooking y número de asiento ====================

    @Test
    void shouldPurchaseTicket_WithSeatAboveApprovedOverbooking_ThrowForbidden() {
        // Given: dos sillas aprobadas (41 y 42); la 999 supera la capacidad más lo aprobado
        trip.setOverbookingApprovedSeats(2);
        TicketCreateRequest request = buildRequest(999, null, null);
        stubEntitiesFound();
        stubSeatFree(999);

        // When/Then: 403 por política de overbooking (tabla de errores estándar)
        assertThatThrownBy(() -> ticketService.purchaseTicket(request))
                .isInstanceOf(OverbookingNotAllowedException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(be.getCode()).isEqualTo("OVERBOOKING_NOT_ALLOWED");
                });
        verify(ticketRepository, never()).save(any());
    }

    @Test
    void shouldPurchaseTicket_WithoutOverbookingApprovalAndBusFull_ThrowOverbooking() {
        // Given: bus lleno y ninguna silla extra aprobada por el DISPATCHER
        TicketCreateRequest request = buildRequest(41, null, null);
        stubEntitiesFound();
        stubSeatFree(41);

        // When/Then
        assertThatThrownBy(() -> ticketService.purchaseTicket(request))
                .isInstanceOf(OverbookingNotAllowedException.class);
        verify(ticketRepository, never()).save(any());
    }

    @Test
    void shouldPurchaseTicket_WithApprovedOverbookingSeats_AllowLastOverbookedSeat() {
        // Given: el DISPATCHER aprobó 2 sillas extra (41 y 42)
        trip.setOverbookingApprovedSeats(2);
        TicketCreateRequest request = buildRequest(42, null, null);
        stubEntitiesFound();
        stubSeatFree(42);
        stubOverbookingCheck(41L, 0.05);
        when(configService.getTicketPriceMultiplierHighDemand()).thenReturn(BigDecimal.ONE);
        stubConfigBasePrice(BigDecimal.valueOf(50000));
        stubTicketPersistence(request);

        // When
        TicketResponse result = ticketService.purchaseTicket(request);

        // Then
        assertThat(result).isEqualTo(ticketResponse);
        verify(ticketRepository).save(any(Ticket.class));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0})
    void shouldPurchaseTicket_WithSeatNumberOutOfRange_ThrowSeatNotAvailable(int seatNumber) {
        // Given: los números de silla empiezan en 1
        TicketCreateRequest request = buildRequest(seatNumber, null, null);
        stubEntitiesFound();
        stubSeatFree(seatNumber);
        stubOverbookingCheck(20L, 0.05);

        // When/Then
        assertThatThrownBy(() -> ticketService.purchaseTicket(request))
                .isInstanceOf(SeatNotAvailableException.class)
                .hasMessageContaining("no existe en este bus");
        verify(ticketRepository, never()).save(any());
        verifyNoInteractions(fareRuleRepository);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 40, 41, 42})
    void shouldPurchaseTicket_WithSeatNumberInsideRange_Succeed(int seatNumber) {
        // Given: capacidad 40 y 2 sillas de overbooking aprobadas => sillas válidas 1..42
        trip.setOverbookingApprovedSeats(2);
        TicketCreateRequest request = buildRequest(seatNumber, null, null);
        stubEntitiesFound();
        stubSeatFree(seatNumber);
        stubOverbookingCheck(20L, 0.05);
        stubConfigBasePrice(BigDecimal.valueOf(50000));
        stubTicketPersistence(request);

        // When
        TicketResponse result = ticketService.purchaseTicket(request);

        // Then
        assertThat(result).isNotNull();
        verify(ticketRepository).save(any(Ticket.class));
    }

    @Test
    void shouldPurchaseTicket_WithManyApprovedSeats_AllowLastSeat() {
        // Given: capacidad 100 y 29 sillas aprobadas => la 129 es válida
        bus.setCapacity(100);
        trip.setOverbookingApprovedSeats(29);
        TicketCreateRequest request = buildRequest(129, null, null);
        stubEntitiesFound();
        stubSeatFree(129);
        stubOverbookingCheck(20L, 0.29);
        stubConfigBasePrice(BigDecimal.valueOf(50000));
        stubTicketPersistence(request);

        // When
        TicketResponse result = ticketService.purchaseTicket(request);

        // Then
        assertThat(result).isNotNull();
    }

    @Test
    void shouldPurchaseTicket_WithSeatBeyondApprovedSeats_ThrowOverbooking() {
        // Given: capacidad 100 y 29 sillas aprobadas => la 130 no se puede vender
        bus.setCapacity(100);
        trip.setOverbookingApprovedSeats(29);
        TicketCreateRequest request = buildRequest(130, null, null);
        stubEntitiesFound();
        stubSeatFree(130);

        // When/Then
        assertThatThrownBy(() -> ticketService.purchaseTicket(request))
                .isInstanceOf(OverbookingNotAllowedException.class)
                .hasMessageContaining("capacidad del bus (100)");
    }

    // ==================== purchaseTicket: cálculo de precio ====================

    @Test
    void shouldPurchaseTicket_WithFareRule_UseFareRuleBasePrice() {
        // Given
        TicketCreateRequest request = buildRequest(10, null, null);
        FareRule fareRule = FareRule.builder()
                .id(1L)
                .route(route)
                .fromStop(fromStop)
                .toStop(toStop)
                .basePrice(BigDecimal.valueOf(80000))
                .build();
        stubEntitiesFound();
        stubSeatFree(10);
        stubOverbookingCheck(20L, 0.05);
        when(fareRuleRepository.findByRouteIdAndFromStopIdAndToStopId(1L, 1L, 2L))
                .thenReturn(Optional.of(fareRule));
        stubTicketPersistence(request);

        // When
        ticketService.purchaseTicket(request);

        // Then
        verify(ticketRepository).save(argThat(t -> t.getPrice().compareTo(BigDecimal.valueOf(80000)) == 0));
    }

    @Test
    void shouldPurchaseTicket_WithHighDemand_ApplyHighDemandMultiplier() {
        // Given: 33/40 = 82.5% > 80%
        TicketCreateRequest request = buildRequest(10, null, null);
        stubEntitiesFound();
        stubSeatFree(10);
        stubOverbookingCheck(33L, 0.05);
        stubConfigBasePrice(BigDecimal.valueOf(50000));
        when(configService.getTicketPriceMultiplierHighDemand()).thenReturn(BigDecimal.valueOf(1.2));
        stubTicketPersistence(request);

        // When
        ticketService.purchaseTicket(request);

        // Then
        verify(ticketRepository).save(argThat(t -> t.getPrice().compareTo(BigDecimal.valueOf(60000)) == 0));
        verify(configService, never()).getTicketPriceMultiplierMediumDemand();
    }

    @ParameterizedTest
    @ValueSource(longs = {25L, 32L})
    void shouldPurchaseTicket_WithMediumDemand_ApplyMediumDemandMultiplier(long soldSeats) {
        // Given: 62.5% y exactamente 80% (el límite superior aún es demanda media)
        TicketCreateRequest request = buildRequest(10, null, null);
        stubEntitiesFound();
        stubSeatFree(10);
        stubOverbookingCheck(soldSeats, 0.05);
        stubConfigBasePrice(BigDecimal.valueOf(50000));
        when(configService.getTicketPriceMultiplierMediumDemand()).thenReturn(BigDecimal.valueOf(1.1));
        stubTicketPersistence(request);

        // When
        ticketService.purchaseTicket(request);

        // Then
        verify(ticketRepository).save(argThat(t -> t.getPrice().compareTo(BigDecimal.valueOf(55000)) == 0));
        verify(configService, never()).getTicketPriceMultiplierHighDemand();
    }

    @Test
    void shouldPurchaseTicket_WithOccupancyExactlySixtyPercent_NotApplyDemandMultiplier() {
        // Given: 24/40 = 60% no supera el umbral de demanda media
        TicketCreateRequest request = buildRequest(10, null, null);
        stubEntitiesFound();
        stubSeatFree(10);
        stubOverbookingCheck(24L, 0.05);
        stubConfigBasePrice(BigDecimal.valueOf(50000));
        stubTicketPersistence(request);

        // When
        ticketService.purchaseTicket(request);

        // Then
        verify(ticketRepository).save(argThat(t -> t.getPrice().compareTo(BigDecimal.valueOf(50000)) == 0));
        verify(configService, never()).getTicketPriceMultiplierMediumDemand();
        verify(configService, never()).getTicketPriceMultiplierHighDemand();
        verify(configService, never()).getTicketPriceMultiplierPeakHours();
    }

    @ParameterizedTest
    @CsvSource({
            "06:00, true", "09:59, true", "17:00, true", "20:59, true",
            "05:59, false", "10:00, false", "16:59, false", "21:00, false"
    })
    void shouldPurchaseTicket_DependingOnDepartureHour_ApplyPeakHoursMultiplier(LocalTime departure, boolean peak) {
        // Given: horas pico 6-9 y 17-20
        trip.setDepartureTime(LocalDate.now().plusDays(1).atTime(departure));
        TicketCreateRequest request = buildRequest(10, null, null);
        stubEntitiesFound();
        stubSeatFree(10);
        stubOverbookingCheck(20L, 0.05);
        stubConfigBasePrice(BigDecimal.valueOf(50000));
        if (peak) {
            when(configService.getTicketPriceMultiplierPeakHours()).thenReturn(BigDecimal.valueOf(1.15));
        }
        stubTicketPersistence(request);

        // When
        ticketService.purchaseTicket(request);

        // Then
        BigDecimal expected = peak ? BigDecimal.valueOf(57500) : BigDecimal.valueOf(50000);
        verify(ticketRepository).save(argThat(t -> t.getPrice().compareTo(expected) == 0));
        if (!peak) {
            verify(configService, never()).getTicketPriceMultiplierPeakHours();
        }
    }

    @Test
    void shouldPurchaseTicket_WithHighDemandPeakHourAndStudent_CombineMultipliersAndDiscount() {
        // Given: 50000 * 1.2 * 1.15 = 69000, con 20% de descuento = 55200
        trip.setDepartureTime(LocalDate.now().plusDays(1).atTime(18, 30));
        TicketCreateRequest request = buildRequest(10, "STUDENT", null);
        stubEntitiesFound();
        stubSeatFree(10);
        stubOverbookingCheck(35L, 0.05);
        stubConfigBasePrice(BigDecimal.valueOf(50000));
        when(configService.getTicketPriceMultiplierHighDemand()).thenReturn(BigDecimal.valueOf(1.2));
        when(configService.getTicketPriceMultiplierPeakHours()).thenReturn(BigDecimal.valueOf(1.15));
        when(configService.getConfig()).thenReturn(createConfigResponse(Map.of("STUDENT", 20)));
        stubTicketPersistence(request);

        // When
        ticketService.purchaseTicket(request);

        // Then
        verify(ticketRepository).save(argThat(t -> t.getPrice().compareTo(new BigDecimal("55200.00")) == 0));
    }

    @ParameterizedTest
    @NullAndEmptySource
    void shouldPurchaseTicket_WithoutPassengerType_NotApplyDiscount(String passengerType) {
        // Given
        TicketCreateRequest request = buildRequest(10, passengerType, null);
        stubEntitiesFound();
        stubSeatFree(10);
        stubOverbookingCheck(20L, 0.05);
        stubConfigBasePrice(BigDecimal.valueOf(50000));
        stubTicketPersistence(request);

        // When
        ticketService.purchaseTicket(request);

        // Then: no se consulta la configuración de descuentos
        verify(ticketRepository).save(argThat(t -> t.getPrice().compareTo(BigDecimal.valueOf(50000)) == 0));
        verify(configService, never()).getConfig();
    }

    @Test
    void shouldPurchaseTicket_WithLowercasePassengerType_ApplyDiscountCaseInsensitive() {
        // Given
        TicketCreateRequest request = buildRequest(10, "student", null);
        stubEntitiesFound();
        stubSeatFree(10);
        stubOverbookingCheck(20L, 0.05);
        stubConfigBasePrice(BigDecimal.valueOf(50000));
        when(configService.getConfig()).thenReturn(createConfigResponse(Map.of("STUDENT", 20)));
        stubTicketPersistence(request);

        // When
        ticketService.purchaseTicket(request);

        // Then
        verify(ticketRepository).save(argThat(t -> t.getPrice().compareTo(BigDecimal.valueOf(40000)) == 0));
    }

    @Test
    void shouldPurchaseTicket_WithUnknownPassengerType_ThrowInvalidPassengerType() {
        // Given: "VIP" no es una tarifa especial (niño / estudiante / adulto mayor)
        TicketCreateRequest request = buildRequest(10, "VIP", null);
        stubEntitiesFound();
        stubSeatFree(10);
        stubOverbookingCheck(20L, 0.05);
        stubConfigBasePrice(BigDecimal.valueOf(50000));
        when(configService.getConfig()).thenReturn(createConfigResponse(Map.of("STUDENT", 20)));

        // When/Then
        assertThatThrownBy(() -> ticketService.purchaseTicket(request))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("INVALID_PASSENGER_TYPE");
        verify(ticketRepository, never()).save(any());
    }

    @Test
    void shouldPurchaseTicket_WithDiscountRequiringRounding_RoundHalfUpToTwoDecimals() {
        // Given: 33333.33 * 15% = 4999.9995 -> 5000.00; precio final 28333.33
        TicketCreateRequest request = buildRequest(10, "SENIOR", null);
        stubEntitiesFound();
        stubSeatFree(10);
        stubOverbookingCheck(20L, 0.05);
        stubConfigBasePrice(new BigDecimal("33333.33"));
        when(configService.getConfig()).thenReturn(createConfigResponse(Map.of("SENIOR", 15)));
        stubTicketPersistence(request);

        // When
        ticketService.purchaseTicket(request);

        // Then
        ArgumentCaptor<Ticket> captor = ArgumentCaptor.forClass(Ticket.class);
        verify(ticketRepository).save(captor.capture());
        assertThat(captor.getValue().getPrice()).isEqualByComparingTo("28333.33");
        assertThat(captor.getValue().getPrice().scale()).isEqualTo(2);
    }

    // ==================== purchaseTicket: equipaje ====================

    @Test
    void shouldPurchaseTicket_WithBaggageUnderLimit_RegisterBaggageWithoutExcessFee() {
        // Given
        TicketCreateRequest request = buildRequest(10, null,
                new BaggageCreateRequest(BigDecimal.valueOf(20), null));
        stubEntitiesFound();
        stubSeatFree(10);
        stubOverbookingCheck(20L, 0.05);
        stubConfigBasePrice(BigDecimal.valueOf(50000));
        stubTicketPersistence(request);
        when(qrCodeGenerator.generateBaggageTag()).thenReturn("BAG-001");
        when(configService.getBaggageWeightLimit()).thenReturn(23.0);
        when(baggageRepository.save(any(Baggage.class))).thenAnswer(inv -> inv.getArgument(0));

        // When
        ticketService.purchaseTicket(request);

        // Then
        ArgumentCaptor<Baggage> captor = ArgumentCaptor.forClass(Baggage.class);
        verify(baggageRepository).save(captor.capture());
        Baggage saved = captor.getValue();
        assertThat(saved.getTicket()).isSameAs(ticket);
        assertThat(saved.getTagCode()).isEqualTo("BAG-001");
        assertThat(saved.getWeightKg()).isEqualByComparingTo("20");
        assertThat(saved.getExcessFee()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(ticket.getBaggage()).isSameAs(saved);
        verify(configService, never()).getExcessFeePerKg();
    }

    @Test
    void shouldPurchaseTicket_WithBaggageExactlyAtLimit_NotChargeExcess() {
        // Given
        TicketCreateRequest request = buildRequest(10, null,
                new BaggageCreateRequest(BigDecimal.valueOf(23), null));
        stubEntitiesFound();
        stubSeatFree(10);
        stubOverbookingCheck(20L, 0.05);
        stubConfigBasePrice(BigDecimal.valueOf(50000));
        stubTicketPersistence(request);
        when(qrCodeGenerator.generateBaggageTag()).thenReturn("BAG-002");
        when(configService.getBaggageWeightLimit()).thenReturn(23.0);
        when(baggageRepository.save(any(Baggage.class))).thenAnswer(inv -> inv.getArgument(0));

        // When
        ticketService.purchaseTicket(request);

        // Then
        verify(baggageRepository).save(argThat(b -> b.getExcessFee().compareTo(BigDecimal.ZERO) == 0));
        verify(configService, never()).getExcessFeePerKg();
    }

    @ParameterizedTest
    @CsvSource({"30, 35000.00", "25.5, 12500.00", "23.01, 50.00"})
    void shouldPurchaseTicket_WithBaggageOverLimit_ChargeExcessPerKg(BigDecimal weightKg, BigDecimal expectedFee) {
        // Given: límite 23 kg y 5000 por kg de exceso
        TicketCreateRequest request = buildRequest(10, null, new BaggageCreateRequest(weightKg, null));
        stubEntitiesFound();
        stubSeatFree(10);
        stubOverbookingCheck(20L, 0.05);
        stubConfigBasePrice(BigDecimal.valueOf(50000));
        stubTicketPersistence(request);
        when(qrCodeGenerator.generateBaggageTag()).thenReturn("BAG-003");
        when(configService.getBaggageWeightLimit()).thenReturn(23.0);
        when(configService.getExcessFeePerKg()).thenReturn(BigDecimal.valueOf(5000));
        when(baggageRepository.save(any(Baggage.class))).thenAnswer(inv -> inv.getArgument(0));

        // When
        ticketService.purchaseTicket(request);

        // Then
        ArgumentCaptor<Baggage> captor = ArgumentCaptor.forClass(Baggage.class);
        verify(baggageRepository).save(captor.capture());
        assertThat(captor.getValue().getExcessFee()).isEqualByComparingTo(expectedFee);
        assertThat(captor.getValue().getExcessFee().scale()).isEqualTo(2);
    }

    // ==================== cancelTicket ====================

    @ParameterizedTest
    @EnumSource(value = Ticket.TicketStatus.class, names = {"CANCELLED", "NO_SHOW"})
    void shouldCancelTicket_WithTicketNotSold_ThrowInvalidSegment(Ticket.TicketStatus status) {
        // Given
        ticket.setStatus(status);
        when(ticketRepository.findById(1L)).thenReturn(Optional.of(ticket));

        // When/Then
        assertThatThrownBy(() -> ticketService.cancelTicket(1L))
                .isInstanceOf(InvalidSegmentException.class)
                .hasMessageContaining("ya está cancelado");
        verify(ticketRepository, never()).save(any());
    }

    @Test
    void shouldCancelTicket_WithDepartureTimeInThePast_ThrowTripAlreadyDeparted() {
        // Given: el viaje sigue SCHEDULED pero la hora de salida ya pasó
        trip.setDepartureTime(LocalDateTime.now().minusHours(1));
        when(ticketRepository.findById(1L)).thenReturn(Optional.of(ticket));

        // When/Then
        assertThatThrownBy(() -> ticketService.cancelTicket(1L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo("TRIP_ALREADY_DEPARTED");
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
        verify(ticketRepository, never()).save(any());
        assertThat(ticket.getStatus()).isEqualTo(Ticket.TicketStatus.SOLD);
    }

    @ParameterizedTest
    @EnumSource(value = Trip.TripStatus.class, names = {"DEPARTED", "ARRIVED"})
    void shouldCancelTicket_WithTripAlreadyDepartedStatus_ThrowTripAlreadyDeparted(Trip.TripStatus status) {
        // Given: la hora de salida es futura pero el viaje ya figura como salido/llegado
        trip.setDepartureTime(LocalDateTime.now().plusHours(5));
        trip.setStatus(status);
        when(ticketRepository.findById(1L)).thenReturn(Optional.of(ticket));

        // When/Then
        assertThatThrownBy(() -> ticketService.cancelTicket(1L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo("TRIP_ALREADY_DEPARTED"));
        verify(ticketRepository, never()).save(any());
    }

    @ParameterizedTest
    @CsvSource({
            // minutos hasta la salida, tramo de política aplicado, porcentaje
            "2910, 48, 90",
            "2850, 24, 70",
            "1470, 24, 70",
            "1410, 12, 50",
            "750, 12, 50",
            "690, 6, 30",
            "390, 6, 30",
            "330, 0, 10",
            "30, 0, 10"
    })
    void shouldCancelTicket_DependingOnHoursUntilDeparture_ApplyRefundPolicy(long minutesUntilDeparture,
                                                                          int policy, int percentage) {
        // Given: margen de 30 minutos respecto a cada límite para no depender del instante de ejecución
        trip.setDepartureTime(LocalDateTime.now().plusMinutes(minutesUntilDeparture));
        when(ticketRepository.findById(1L)).thenReturn(Optional.of(ticket));
        BigDecimal pct = BigDecimal.valueOf(percentage);
        switch (policy) {
            case 48 -> when(configService.getRefundPercentage48Hours()).thenReturn(pct);
            case 24 -> when(configService.getRefundPercentage24Hours()).thenReturn(pct);
            case 12 -> when(configService.getRefundPercentage12Hours()).thenReturn(pct);
            case 6 -> when(configService.getRefundPercentage6Hours()).thenReturn(pct);
            default -> when(configService.getRefundPercentageLess6Hours()).thenReturn(pct);
        }
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(inv -> inv.getArgument(0));

        // When
        TicketCancelResponse result = ticketService.cancelTicket(1L);

        // Then: precio 50000
        assertThat(result.refundPercentage()).isEqualTo(percentage);
        assertThat(result.refundAmount())
                .isEqualByComparingTo(BigDecimal.valueOf(50000L * percentage / 100));
        assertThat(result.status()).isEqualTo(Ticket.TicketStatus.CANCELLED);
        assertThat(result.message()).contains("cancelado");
    }

    @Test
    void shouldCancelTicket_WithRefundRequiringRounding_RoundHalfUpToTwoDecimals() {
        // Given: 33333.33 * 70% = 23333.331 -> 23333.33
        ticket.setPrice(new BigDecimal("33333.33"));
        trip.setDepartureTime(LocalDateTime.now().plusHours(30));
        when(ticketRepository.findById(1L)).thenReturn(Optional.of(ticket));
        when(configService.getRefundPercentage24Hours()).thenReturn(BigDecimal.valueOf(70));
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(inv -> inv.getArgument(0));

        // When
        TicketCancelResponse result = ticketService.cancelTicket(1L);

        // Then
        assertThat(result.refundAmount()).isEqualByComparingTo("23333.33");
        assertThat(result.refundAmount().scale()).isEqualTo(2);
    }

    @Test
    void shouldCancelTicket_WithTripBoardingAndFutureDeparture_AllowCancellation() {
        // Given
        trip.setStatus(Trip.TripStatus.BOARDING);
        trip.setDepartureTime(LocalDateTime.now().plusHours(2));
        when(ticketRepository.findById(1L)).thenReturn(Optional.of(ticket));
        when(configService.getRefundPercentageLess6Hours()).thenReturn(BigDecimal.ZERO);
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(inv -> inv.getArgument(0));

        // When
        TicketCancelResponse result = ticketService.cancelTicket(1L);

        // Then
        assertThat(result.status()).isEqualTo(Ticket.TicketStatus.CANCELLED);
        assertThat(result.refundAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.refundPercentage()).isZero();
    }

    // ==================== consultas ====================

    @Test
    void shouldGetTicketById_WithNonExistentId_ThrowResourceNotFound() {
        // Given
        when(ticketRepository.findById(99L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> ticketService.getTicketById(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");
        verifyNoInteractions(ticketMapper);
    }

    @Test
    void shouldGetTicketByQrCode_WithExistingCode_ReturnTicketResponse() {
        // Given
        when(ticketRepository.findByQrCode("QR123")).thenReturn(Optional.of(ticket));
        when(ticketMapper.toResponse(ticket)).thenReturn(ticketResponse);

        // When
        TicketResponse result = ticketService.getTicketByQrCode("QR123");

        // Then
        assertThat(result).isEqualTo(ticketResponse);
    }

    @Test
    void shouldGetTicketByQrCode_WithUnknownCode_ThrowResourceNotFound() {
        // Given
        when(ticketRepository.findByQrCode("QR-NO")).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> ticketService.getTicketByQrCode("QR-NO"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("QR-NO");
        verifyNoInteractions(ticketMapper);
    }

    @Test
    void shouldGetUserTickets_WithoutTickets_ReturnEmptyList() {
        // Given
        when(ticketRepository.findByPassengerId(2L)).thenReturn(List.of());
        when(ticketMapper.toResponseList(List.of())).thenReturn(List.of());

        // When
        List<TicketResponse> result = ticketService.getUserTickets(2L);

        // Then
        assertThat(result).isEmpty();
    }

    // ==================== helpers ====================

    private TicketCreateRequest buildRequest(Integer seatNumber, String passengerType, BaggageCreateRequest baggage) {
        return new TicketCreateRequest(
                1L, 1L, seatNumber,
                fromStop.getId(), fromStop.getName(), fromStop.getOrder(),
                toStop.getId(), toStop.getName(), toStop.getOrder(),
                BigDecimal.valueOf(50000), Ticket.PaymentMethod.CASH, baggage, passengerType
        );
    }

    private void stubEntitiesFound() {
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(userRepository.findById(1L)).thenReturn(Optional.of(passenger));
        when(stopRepository.findById(fromStop.getId())).thenReturn(Optional.of(fromStop));
        when(stopRepository.findById(toStop.getId())).thenReturn(Optional.of(toStop));
    }

    private void stubSeatFree(int seatNumber) {
        when(seatHoldRepository.findOverlappingActiveHolds(eq(1L), eq(seatNumber), anyInt(), anyInt(), any(LocalDateTime.class)))
                .thenReturn(List.of());
        when(ticketRepository.isSeatAvailableForSegment(1L, seatNumber, fromStop.getOrder(), toStop.getOrder()))
                .thenReturn(true);
    }

    // Ocupación del tramo usada por el precio dinámico (el overbooking ya no depende del porcentaje en la compra:
    // lo limitan las sillas aprobadas por el DISPATCHER). lenient: las ventas rechazadas antes del precio no la consultan
    private void stubOverbookingCheck(long soldSeats, double overbookingMaxPercentage) {
        lenient().when(ticketRepository.countSoldSeatsForSegment(eq(1L), anyInt(), anyInt())).thenReturn(soldSeats);
    }

    private void stubConfigBasePrice(BigDecimal basePrice) {
        when(fareRuleRepository.findByRouteIdAndFromStopIdAndToStopId(1L, fromStop.getId(), toStop.getId()))
                .thenReturn(Optional.empty());
        when(configService.getTicketBasePrice()).thenReturn(basePrice);
    }

    private void stubTicketPersistence(TicketCreateRequest request) {
        when(ticketMapper.toEntity(request)).thenReturn(ticket);
        when(qrCodeGenerator.generateTicketQr()).thenReturn("QR123");
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(inv -> inv.getArgument(0));
        when(ticketMapper.toResponse(any(Ticket.class))).thenReturn(ticketResponse);
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
                LocalDateTime.now(), null, null, null, null, null, null, null
        );
    }
}

