package com.web.service.ticket;

import com.web.dto.admin.ConfigResponse;
import com.web.dto.ticket.TicketCancelResponse;
import com.web.dto.ticket.TicketCreateRequest;
import com.web.dto.ticket.TicketResponse;
import com.web.dto.ticket.mapper.TicketMapper;
import com.web.entity.Assignment;
import com.web.entity.Bus;
import com.web.entity.FareRule;
import com.web.entity.Route;
import com.web.entity.Stop;
import com.web.entity.Ticket;
import com.web.entity.Trip;
import com.web.entity.User;
import com.web.exception.BusinessException;
import com.web.exception.OverbookingNotAllowedException;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

// Reglas de precio (FareRule, descuentos, precio dinámico), canal de venta, overbooking,
// cancelación y abordaje con conductor asignado / no-show
@ExtendWith(MockitoExtension.class)
class TicketPricingAndBoardingRulesTest {

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

    private Route route;
    private Bus bus;
    private Trip trip;
    private User passenger;
    private Stop fromStop;
    private Stop toStop;
    private Ticket ticket;
    private TicketResponse ticketResponse;

    @BeforeEach
    void setUp() {
        route = Route.builder().id(1L).code("R001").name("Santa Marta - Barranquilla").build();
        bus = Bus.builder().id(1L).plate("ABC123").capacity(40).status(Bus.BusStatus.ACTIVE).build();
        trip = Trip.builder()
                .id(1L)
                .route(route)
                .bus(bus)
                .tripDate(LocalDate.now().plusDays(1))
                // 12:00 no es hora pico
                .departureTime(LocalDate.now().plusDays(1).atTime(12, 0))
                .status(Trip.TripStatus.SCHEDULED)
                .build();
        passenger = User.builder().id(1L).name("Pasajero").email("pasajero@test.com").build();
        fromStop = Stop.builder().id(1L).route(route).name("Santa Marta").order(1).build();
        toStop = Stop.builder().id(2L).route(route).name("Barranquilla").order(2).build();
        ticket = Ticket.builder()
                .trip(trip)
                .seatNumber(10)
                .price(BigDecimal.valueOf(50000))
                .paymentMethod(Ticket.PaymentMethod.CASH)
                .status(Ticket.TicketStatus.SOLD)
                .build();
        ticketResponse = mock(TicketResponse.class);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ---------- Utilidades ----------

    private void authenticate(String username, String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                username, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private TicketCreateRequest request(int seatNumber, String passengerType) {
        return new TicketCreateRequest(1L, 1L, seatNumber, 1L, "Santa Marta", 1, 2L, "Barranquilla", 2,
                BigDecimal.valueOf(50000), Ticket.PaymentMethod.CASH, null, passengerType);
    }

    // Stubs de una compra válida hasta el guardado; el precio se configura en cada prueba
    private void givenValidPurchase(TicketCreateRequest request) {
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(userRepository.findById(1L)).thenReturn(Optional.of(passenger));
        when(stopRepository.findById(1L)).thenReturn(Optional.of(fromStop));
        when(stopRepository.findById(2L)).thenReturn(Optional.of(toStop));
        when(seatHoldRepository.findOverlappingActiveHolds(eq(1L), eq(request.seatNumber()), eq(1), eq(2),
                any(LocalDateTime.class))).thenReturn(List.of());
        when(ticketRepository.isSeatAvailableForSegment(1L, request.seatNumber(), 1, 2)).thenReturn(true);
        when(ticketMapper.toEntity(request)).thenReturn(ticket);
        when(qrCodeGenerator.generateTicketQr()).thenReturn("QR-NEW");
        when(ticketRepository.save(any(Ticket.class))).then(returnsFirstArg());
        when(ticketMapper.toResponse(any(Ticket.class))).thenReturn(ticketResponse);
    }

    // Pasos previos al cálculo del precio (para errores de validación que ocurren antes)
    private void givenPurchaseUntilSeatValidation(TicketCreateRequest request) {
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(userRepository.findById(1L)).thenReturn(Optional.of(passenger));
        when(stopRepository.findById(1L)).thenReturn(Optional.of(fromStop));
        when(stopRepository.findById(2L)).thenReturn(Optional.of(toStop));
        when(seatHoldRepository.findOverlappingActiveHolds(eq(1L), eq(request.seatNumber()), eq(1), eq(2),
                any(LocalDateTime.class))).thenReturn(List.of());
        when(ticketRepository.isSeatAvailableForSegment(1L, request.seatNumber(), 1, 2)).thenReturn(true);
    }

    private FareRule fareRule(String basePrice, Boolean dynamicPricing, Map<String, Object> discounts) {
        return FareRule.builder()
                .id(1L)
                .route(route)
                .fromStop(fromStop)
                .toStop(toStop)
                .basePrice(new BigDecimal(basePrice))
                .dynamicPricingEnabled(dynamicPricing)
                .discounts(discounts)
                .build();
    }

    private void givenFareRule(FareRule rule) {
        when(fareRuleRepository.findByRouteIdAndFromStopIdAndToStopId(1L, 1L, 2L)).thenReturn(Optional.of(rule));
    }

    private static ConfigResponse configWithDiscounts(Map<String, Integer> discounts) {
        return new ConfigResponse(10, 10, 5, discounts,
                BigDecimal.valueOf(23), BigDecimal.valueOf(5000),
                BigDecimal.valueOf(10000), 0.05,
                BigDecimal.valueOf(90), BigDecimal.valueOf(70), BigDecimal.valueOf(50),
                BigDecimal.valueOf(30), BigDecimal.ZERO,
                BigDecimal.valueOf(50000), BigDecimal.valueOf(1.15), BigDecimal.valueOf(1.2), BigDecimal.valueOf(1.1),
                LocalDateTime.now());
    }

    private void givenConfigDiscounts() {
        Map<String, Integer> discounts = new HashMap<>();
        discounts.put("STUDENT", 20);
        discounts.put("SENIOR", 15);
        discounts.put("CHILD", 50);
        when(configService.getConfig()).thenReturn(configWithDiscounts(discounts));
    }

    // ---------- FareRule y precio dinámico ----------

    @Test
    void shouldPurchaseTicket_WithFareRuleDynamicPricingDisabled_NotApplyMultipliers() {
        // Given: hora pico y bus casi lleno, pero la regla tiene el precio dinámico apagado
        trip.setDepartureTime(LocalDate.now().plusDays(1).atTime(8, 0));
        TicketCreateRequest request = request(10, "ADULT");
        givenValidPurchase(request);
        givenFareRule(fareRule("30000", false, null));

        // When
        ticketService.purchaseTicket(request);

        // Then
        assertThat(ticket.getPrice()).isEqualByComparingTo("30000.00");
        verify(ticketRepository, never()).countSoldSeatsForSegment(anyLong(), anyInt(), anyInt());
        verify(configService, never()).getTicketPriceMultiplierHighDemand();
        verify(configService, never()).getTicketPriceMultiplierMediumDemand();
        verify(configService, never()).getTicketPriceMultiplierPeakHours();
        verify(configService, never()).getTicketBasePrice();
    }

    @Test
    void shouldPurchaseTicket_WithFareRuleDynamicPricingNull_TreatAsDisabled() {
        // Given
        TicketCreateRequest request = request(10, null);
        givenValidPurchase(request);
        givenFareRule(fareRule("30000", null, null));

        // When
        ticketService.purchaseTicket(request);

        // Then
        assertThat(ticket.getPrice()).isEqualByComparingTo("30000.00");
        verify(ticketRepository, never()).countSoldSeatsForSegment(anyLong(), anyInt(), anyInt());
    }

    @Test
    void shouldPurchaseTicket_WithFareRuleDynamicPricingEnabled_ApplyMultipliersOverRuleBasePrice() {
        // Given: 35/40 = 87.5 % de ocupación del tramo -> demanda alta (x1.2)
        TicketCreateRequest request = request(10, "ADULT");
        givenValidPurchase(request);
        givenFareRule(fareRule("30000", true, null));
        when(ticketRepository.countSoldSeatsForSegment(1L, 1, 2)).thenReturn(35L);
        when(configService.getTicketPriceMultiplierHighDemand()).thenReturn(new BigDecimal("1.2"));

        // When
        ticketService.purchaseTicket(request);

        // Then
        assertThat(ticket.getPrice()).isEqualByComparingTo("36000.00");
        verify(configService, never()).getTicketBasePrice();
    }

    // ---------- Descuentos de la regla de tarifa ----------

    @Test
    void shouldPurchaseTicket_WithFareRuleNumericDiscount_OverrideConfigDiscount() {
        // Given: config STUDENT 20 %, la regla define 30 %
        TicketCreateRequest request = request(10, "STUDENT");
        givenValidPurchase(request);
        givenFareRule(fareRule("30000", false, Map.of("STUDENT", 30)));
        givenConfigDiscounts();

        // When
        ticketService.purchaseTicket(request);

        // Then: 30000 - 30 %
        assertThat(ticket.getPrice()).isEqualByComparingTo("21000.00");
    }

    @Test
    void shouldPurchaseTicket_WithFareRuleDecimalDiscount_TruncateToInteger() {
        // Given: un Number no entero (p. ej. leído del JSON) se trunca con intValue
        TicketCreateRequest request = request(10, "STUDENT");
        givenValidPurchase(request);
        givenFareRule(fareRule("30000", false, Map.of("STUDENT", 30.9)));
        givenConfigDiscounts();

        // When
        ticketService.purchaseTicket(request);

        // Then
        assertThat(ticket.getPrice()).isEqualByComparingTo("21000.00");
    }

    @Test
    void shouldPurchaseTicket_WithFareRuleNumericStringDiscount_ParseAndApplyIt() {
        // Given: el descuento de la regla viene como texto numérico y con otra capitalización
        TicketCreateRequest request = request(10, "senior");
        givenValidPurchase(request);
        givenFareRule(fareRule("40000", false, Map.of("Senior", "25")));
        givenConfigDiscounts();

        // When
        ticketService.purchaseTicket(request);

        // Then: 40000 - 25 %
        assertThat(ticket.getPrice()).isEqualByComparingTo("30000.00");
    }

    @Test
    void shouldPurchaseTicket_WithFareRuleDecimalStringDiscount_TruncateToInteger() {
        // Given
        TicketCreateRequest request = request(10, "CHILD");
        givenValidPurchase(request);
        givenFareRule(fareRule("10000", false, Map.of("CHILD", "12.7")));
        givenConfigDiscounts();

        // When
        ticketService.purchaseTicket(request);

        // Then: 10000 - 12 %
        assertThat(ticket.getPrice()).isEqualByComparingTo("8800.00");
    }

    @Test
    void shouldPurchaseTicket_WithFareRuleInvalidDiscount_FallBackToConfigDiscount() {
        // Given: valor no numérico en la regla -> descuento de la configuración (STUDENT 20 %)
        TicketCreateRequest request = request(10, "STUDENT");
        givenValidPurchase(request);
        givenFareRule(fareRule("30000", false, Map.of("STUDENT", "veinte")));
        givenConfigDiscounts();

        // When
        ticketService.purchaseTicket(request);

        // Then
        assertThat(ticket.getPrice()).isEqualByComparingTo("24000.00");
    }

    @Test
    void shouldPurchaseTicket_WithFareRuleDiscountsWithoutPassengerType_UseConfigDiscount() {
        // Given: la regla define descuentos, pero no para SENIOR
        TicketCreateRequest request = request(10, "SENIOR");
        givenValidPurchase(request);
        givenFareRule(fareRule("20000", false, Map.of("STUDENT", 40)));
        givenConfigDiscounts();

        // When
        ticketService.purchaseTicket(request);

        // Then: SENIOR 15 % de la configuración
        assertThat(ticket.getPrice()).isEqualByComparingTo("17000.00");
    }

    @Test
    void shouldPurchaseTicket_WithTypeOnlyInFareRule_AcceptRuleDiscount() {
        // Given: el tipo no existe en la configuración pero la regla le da descuento
        TicketCreateRequest request = request(10, "VETERAN");
        givenValidPurchase(request);
        givenFareRule(fareRule("20000", false, Map.of("VETERAN", 10)));
        givenConfigDiscounts();

        // When
        ticketService.purchaseTicket(request);

        // Then
        assertThat(ticket.getPrice()).isEqualByComparingTo("18000.00");
    }

    // ---------- Límites del descuento y del precio ----------

    @Test
    void shouldPurchaseTicket_WithDiscountAboveHundred_CapAtHundredAndPriceZero() {
        // Given
        TicketCreateRequest request = request(10, "STUDENT");
        givenValidPurchase(request);
        givenFareRule(fareRule("30000", false, Map.of("STUDENT", 150)));
        givenConfigDiscounts();

        // When
        ticketService.purchaseTicket(request);

        // Then: nunca negativo
        assertThat(ticket.getPrice()).isEqualByComparingTo("0.00");
        assertThat(ticket.getPrice().signum()).isZero();
    }

    @Test
    void shouldPurchaseTicket_WithNegativeDiscount_CapAtZero() {
        // Given: un descuento negativo no puede encarecer el tiquete
        TicketCreateRequest request = request(10, "STUDENT");
        givenValidPurchase(request);
        givenFareRule(fareRule("30000", false, Map.of("STUDENT", -20)));
        givenConfigDiscounts();

        // When
        ticketService.purchaseTicket(request);

        // Then
        assertThat(ticket.getPrice()).isEqualByComparingTo("30000.00");
    }

    @Test
    void shouldPurchaseTicket_WithConfigDiscountAboveHundred_CapAtHundred() {
        // Given
        TicketCreateRequest request = request(10, "CHILD");
        givenValidPurchase(request);
        when(fareRuleRepository.findByRouteIdAndFromStopIdAndToStopId(1L, 1L, 2L)).thenReturn(Optional.empty());
        when(configService.getTicketBasePrice()).thenReturn(BigDecimal.valueOf(50000));
        when(ticketRepository.countSoldSeatsForSegment(1L, 1, 2)).thenReturn(0L);
        when(configService.getConfig()).thenReturn(configWithDiscounts(Map.of("CHILD", 120)));

        // When
        ticketService.purchaseTicket(request);

        // Then
        assertThat(ticket.getPrice()).isEqualByComparingTo("0.00");
    }

    @Test
    void shouldPurchaseTicket_WithNegativeBasePrice_NeverReturnNegativePrice() {
        // Given: tarifa mal configurada con precio negativo
        TicketCreateRequest request = request(10, "ADULT");
        givenValidPurchase(request);
        givenFareRule(fareRule("-5000", false, null));

        // When
        ticketService.purchaseTicket(request);

        // Then
        assertThat(ticket.getPrice()).isEqualByComparingTo("0.00");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "ADULT", "adult", " Adult "})
    void shouldPurchaseTicket_WithAdultOrWithoutType_NotApplyDiscountNorReadConfig(String passengerType) {
        // Given
        TicketCreateRequest request = request(10, passengerType);
        givenValidPurchase(request);
        givenFareRule(fareRule("30000", false, Map.of("ADULT", 50)));

        // When
        ticketService.purchaseTicket(request);

        // Then: ADULT nunca tiene descuento, aunque la regla lo defina
        assertThat(ticket.getPrice()).isEqualByComparingTo("30000.00");
        verify(configService, never()).getConfig();
    }

    @Test
    void shouldPurchaseTicket_WithUnknownPassengerType_ThrowInvalidPassengerTypeAndNotSave() {
        // Given
        TicketCreateRequest request = request(10, "ALIEN");
        givenPurchaseUntilSeatValidation(request);
        givenFareRule(fareRule("30000", false, Map.of("STUDENT", 30)));
        givenConfigDiscounts();

        // When/Then
        assertThatThrownBy(() -> ticketService.purchaseTicket(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ALIEN")
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(be.getCode()).isEqualTo("INVALID_PASSENGER_TYPE");
                });
        verify(ticketRepository, never()).save(any());
        verifyNoInteractions(ticketMapper, qrCodeGenerator);
    }

    // ---------- Canal de venta ----------

    @Test
    void shouldPurchaseTicket_WithClerkAuthenticated_SetBoxOfficeChannel() {
        // Given
        authenticate("clerk@test.com", "CLERK");
        TicketCreateRequest request = request(10, null);
        givenValidPurchase(request);
        givenFareRule(fareRule("30000", false, null));

        // When
        ticketService.purchaseTicket(request);

        // Then
        verify(ticketRepository).save(argThat(t -> t.getChannel() == Ticket.SalesChannel.BOX_OFFICE));
    }

    @Test
    void shouldPurchaseTicket_WithPassengerAuthenticated_SetAppChannel() {
        // Given: el ticket del mapper viene con otro canal para comprobar que se sobrescribe
        authenticate("pasajero@test.com", "PASSENGER");
        ticket.setChannel(Ticket.SalesChannel.BOX_OFFICE);
        TicketCreateRequest request = request(10, null);
        givenValidPurchase(request);
        givenFareRule(fareRule("30000", false, null));

        // When
        ticketService.purchaseTicket(request);

        // Then
        assertThat(ticket.getChannel()).isEqualTo(Ticket.SalesChannel.APP);
    }

    @Test
    void shouldPurchaseTicket_WithoutAuthentication_SetAppChannel() {
        // Given
        TicketCreateRequest request = request(10, null);
        givenValidPurchase(request);
        givenFareRule(fareRule("30000", false, null));

        // When
        ticketService.purchaseTicket(request);

        // Then
        assertThat(ticket.getChannel()).isEqualTo(Ticket.SalesChannel.APP);
    }

    // ---------- Estado del viaje, salida y overbooking ----------

    @Test
    void shouldPurchaseTicket_WithTripInBoarding_Allow() {
        // Given
        trip.setStatus(Trip.TripStatus.BOARDING);
        TicketCreateRequest request = request(10, null);
        givenValidPurchase(request);
        givenFareRule(fareRule("30000", false, null));

        // When
        TicketResponse result = ticketService.purchaseTicket(request);

        // Then
        assertThat(result).isSameAs(ticketResponse);
        verify(ticketRepository).save(ticket);
    }

    @Test
    void shouldPurchaseTicket_WithDepartureInThePast_ThrowTripAlreadyDeparted() {
        // Given: el viaje sigue en BOARDING pero su hora de salida ya pasó
        trip.setStatus(Trip.TripStatus.BOARDING);
        trip.setDepartureTime(LocalDateTime.now().minusMinutes(1));
        TicketCreateRequest request = request(10, null);
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));

        // When/Then
        assertThatThrownBy(() -> ticketService.purchaseTicket(request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(be.getCode()).isEqualTo("TRIP_ALREADY_DEPARTED");
                });
        verifyNoInteractions(userRepository, stopRepository, seatHoldRepository);
        verify(ticketRepository, never()).save(any());
    }

    @Test
    void shouldPurchaseTicket_WithSeatAboveCapacityPlusApproved_ThrowOverbookingForbidden() {
        // Given: 40 sillas + 2 aprobadas; la 43 no está aprobada
        trip.setOverbookingApprovedSeats(2);
        TicketCreateRequest request = request(43, null);
        givenPurchaseUntilSeatValidation(request);

        // When/Then
        assertThatThrownBy(() -> ticketService.purchaseTicket(request))
                .isInstanceOf(OverbookingNotAllowedException.class)
                .hasMessageContaining("43")
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(be.getCode()).isEqualTo("OVERBOOKING_NOT_ALLOWED");
                });
        verifyNoInteractions(fareRuleRepository, ticketMapper);
        verify(ticketRepository, never()).save(any());
    }

    @Test
    void shouldPurchaseTicket_WithNullApprovedSeatsAndSeatAboveCapacity_ThrowOverbookingForbidden() {
        // Given
        trip.setOverbookingApprovedSeats(null);
        TicketCreateRequest request = request(41, null);
        givenPurchaseUntilSeatValidation(request);

        // When/Then
        assertThatThrownBy(() -> ticketService.purchaseTicket(request))
                .isInstanceOf(OverbookingNotAllowedException.class)
                .hasMessageContaining("aprobadas: 0");
    }

    @Test
    void shouldPurchaseTicket_WithApprovedOverbookingSeat_SellIt() {
        // Given: la silla 42 es la segunda silla de overbooking aprobada
        trip.setOverbookingApprovedSeats(2);
        TicketCreateRequest request = request(42, null);
        givenValidPurchase(request);
        givenFareRule(fareRule("30000", false, null));

        // When
        ticketService.purchaseTicket(request);

        // Then
        verify(ticketRepository).save(ticket);
    }

    @Test
    void shouldPurchaseTicket_LockTripBeforeReadingIt() {
        // Given
        TicketCreateRequest request = request(10, null);
        givenValidPurchase(request);
        givenFareRule(fareRule("30000", false, null));

        // When
        ticketService.purchaseTicket(request);

        // Then: el bloqueo de la fila del viaje va antes de cualquier lectura
        InOrder inOrder = inOrder(tripRepository, ticketRepository);
        inOrder.verify(tripRepository).lockById(1L);
        inOrder.verify(tripRepository).findById(1L);
        inOrder.verify(ticketRepository).isSeatAvailableForSegment(1L, 10, 1, 2);
        inOrder.verify(ticketRepository).save(ticket);
    }

    // ---------- Cancelación ----------

    @Test
    void shouldCancelTicket_WithRefund_SaveRefundAmountAndCancelledAt() {
        // Given: salida en 72 h -> política de 48 h (90 %)
        ticket.setId(1L);
        trip.setDepartureTime(LocalDateTime.now().plusHours(72));
        when(ticketRepository.findById(1L)).thenReturn(Optional.of(ticket));
        when(configService.getRefundPercentage48Hours()).thenReturn(BigDecimal.valueOf(90));
        LocalDateTime before = LocalDateTime.now();

        // When
        TicketCancelResponse response = ticketService.cancelTicket(1L);

        // Then
        assertThat(response.refundAmount()).isEqualByComparingTo("45000.00");
        verify(ticketRepository).save(argThat(t -> t.getStatus() == Ticket.TicketStatus.CANCELLED
                && t.getRefundAmount() != null && t.getRefundAmount().compareTo(new BigDecimal("45000.00")) == 0
                && t.getCancelledAt() != null));
        assertThat(ticket.getCancelledAt()).isBetween(before, LocalDateTime.now());
    }

    @Test
    void shouldCancelTicket_WithZeroRefundPolicy_SaveZeroRefundAmount() {
        // Given: menos de 6 h -> 0 %
        ticket.setId(1L);
        trip.setDepartureTime(LocalDateTime.now().plusHours(2));
        when(ticketRepository.findById(1L)).thenReturn(Optional.of(ticket));
        when(configService.getRefundPercentageLess6Hours()).thenReturn(BigDecimal.ZERO);

        // When
        ticketService.cancelTicket(1L);

        // Then: el reembolso queda registrado aunque sea 0 (cierre de caja)
        assertThat(ticket.getRefundAmount()).isEqualByComparingTo("0");
        assertThat(ticket.getCancelledAt()).isNotNull();
    }

    // ---------- Abordaje: conductor asignado ----------

    private void givenTicketByQr() {
        ticket.setId(1L);
        ticket.setQrCode("QR-1");
        ticket.setFromStop(fromStop);
        ticket.setToStop(toStop);
        when(ticketRepository.findByQrCode("QR-1")).thenReturn(Optional.of(ticket));
    }

    @Test
    void shouldBoardTicket_WithDriverNotAssigned_ThrowForbidden() {
        // Given
        authenticate("other.driver@test.com", "DRIVER");
        trip.setStatus(Trip.TripStatus.BOARDING);
        givenTicketByQr();
        Assignment assignment = Assignment.builder().trip(trip)
                .driver(User.builder().id(9L).email("driver@test.com").build()).build();
        when(assignmentRepository.findByTripId(1L)).thenReturn(Optional.of(assignment));

        // When/Then
        assertThatThrownBy(() -> ticketService.boardTicket("QR-1"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(be.getCode()).isEqualTo("DRIVER_NOT_ASSIGNED");
                });
        assertThat(ticket.getBoardedAt()).isNull();
        verify(ticketRepository, never()).save(any());
    }

    @Test
    void shouldBoardTicket_WithDriverAndTripWithoutAssignment_ThrowForbidden() {
        // Given
        authenticate("driver@test.com", "DRIVER");
        trip.setStatus(Trip.TripStatus.BOARDING);
        givenTicketByQr();
        when(assignmentRepository.findByTripId(1L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> ticketService.boardTicket("QR-1"))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("DRIVER_NOT_ASSIGNED");
    }

    @Test
    void shouldBoardTicket_WithAssignedDriver_SetBoardedAt() {
        // Given
        authenticate("driver@test.com", "DRIVER");
        trip.setStatus(Trip.TripStatus.BOARDING);
        givenTicketByQr();
        Assignment assignment = Assignment.builder().trip(trip)
                .driver(User.builder().id(9L).email("driver@test.com").build()).build();
        when(assignmentRepository.findByTripId(1L)).thenReturn(Optional.of(assignment));
        when(ticketRepository.save(ticket)).thenReturn(ticket);
        when(ticketMapper.toResponse(ticket)).thenReturn(ticketResponse);

        // When
        TicketResponse result = ticketService.boardTicket("QR-1");

        // Then
        assertThat(result).isSameAs(ticketResponse);
        assertThat(ticket.getBoardedAt()).isNotNull();
    }

    @Test
    void shouldBoardTicket_WithDispatcher_NotCheckAssignment() {
        // Given
        authenticate("dispatcher@test.com", "DISPATCHER");
        trip.setStatus(Trip.TripStatus.BOARDING);
        givenTicketByQr();
        when(ticketRepository.save(ticket)).thenReturn(ticket);
        when(ticketMapper.toResponse(ticket)).thenReturn(ticketResponse);

        // When
        ticketService.boardTicket("QR-1");

        // Then
        verifyNoInteractions(assignmentRepository);
        assertThat(ticket.getBoardedAt()).isNotNull();
    }

    // ---------- Abordaje: no-show que llega tarde ----------

    @Test
    void shouldBoardTicket_WithNoShowAndSeatStillFree_RestoreToSoldAndClearFee() {
        // Given
        trip.setStatus(Trip.TripStatus.BOARDING);
        givenTicketByQr();
        ticket.setStatus(Ticket.TicketStatus.NO_SHOW);
        ticket.setNoShowFee(BigDecimal.valueOf(5000));
        when(ticketRepository.isSeatAvailableForSegment(1L, 10, 1, 2)).thenReturn(true);
        when(ticketRepository.save(ticket)).thenReturn(ticket);
        when(ticketMapper.toResponse(ticket)).thenReturn(ticketResponse);

        // When
        ticketService.boardTicket("QR-1");

        // Then
        assertThat(ticket.getStatus()).isEqualTo(Ticket.TicketStatus.SOLD);
        assertThat(ticket.getNoShowFee()).isNull();
        assertThat(ticket.getBoardedAt()).isNotNull();
        verify(ticketRepository).save(argThat(t -> t.getStatus() == Ticket.TicketStatus.SOLD));
    }

    @Test
    void shouldBoardTicket_WithNoShowAndSeatResold_ThrowSeatNotAvailable() {
        // Given
        trip.setStatus(Trip.TripStatus.BOARDING);
        givenTicketByQr();
        ticket.setStatus(Ticket.TicketStatus.NO_SHOW);
        ticket.setNoShowFee(BigDecimal.valueOf(5000));
        when(ticketRepository.isSeatAvailableForSegment(1L, 10, 1, 2)).thenReturn(false);

        // When/Then
        assertThatThrownBy(() -> ticketService.boardTicket("QR-1"))
                .isInstanceOf(SeatNotAvailableException.class)
                .hasMessageContaining("10");
        assertThat(ticket.getStatus()).isEqualTo(Ticket.TicketStatus.NO_SHOW);
        assertThat(ticket.getNoShowFee()).isEqualByComparingTo("5000");
        assertThat(ticket.getBoardedAt()).isNull();
        verify(ticketRepository, never()).save(any());
    }

    @Test
    void shouldBoardTicket_WithNoShowAndTripDeparted_ThrowTicketNotValid() {
        // Given: con el bus en marcha un no-show ya no se restituye
        trip.setStatus(Trip.TripStatus.DEPARTED);
        givenTicketByQr();
        ticket.setStatus(Ticket.TicketStatus.NO_SHOW);

        // When/Then
        assertThatThrownBy(() -> ticketService.boardTicket("QR-1"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(be.getCode()).isEqualTo("TICKET_NOT_VALID");
                });
        verify(ticketRepository, never()).isSeatAvailableForSegment(anyLong(), anyInt(), anyInt(), anyInt());
        verify(ticketRepository, never()).save(any());
    }

    // ---------- Tarea programada de no-show ----------

    @Test
    void shouldProcessNoShows_WithUnboardedTickets_SaveNoShowFee() {
        // Given
        Ticket t1 = Ticket.builder().id(1L).status(Ticket.TicketStatus.SOLD).build();
        Ticket t2 = Ticket.builder().id(2L).status(Ticket.TicketStatus.SOLD).build();
        when(ticketRepository.findUnboardedTicketsDepartingBetween(any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.of(t1, t2));
        when(configService.getNoShowFee()).thenReturn(BigDecimal.valueOf(8000));

        // When
        ticketService.processNoShows();

        // Then
        assertThat(List.of(t1, t2)).allSatisfy(t -> {
            assertThat(t.getStatus()).isEqualTo(Ticket.TicketStatus.NO_SHOW);
            assertThat(t.getNoShowFee()).isEqualByComparingTo("8000");
        });
        verify(ticketRepository).save(t1);
        verify(ticketRepository).save(t2);
        verify(configService, times(1)).getNoShowFee();
    }

    @Test
    void shouldProcessNoShows_WithoutTickets_NotQueryFee() {
        // Given
        when(ticketRepository.findUnboardedTicketsDepartingBetween(any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.of());

        // When
        ticketService.processNoShows();

        // Then
        verifyNoInteractions(configService);
        verify(ticketRepository, never()).save(any());
    }

    @Test
    void shouldProcessNoShows_QueryFiveMinuteWindowFromNow() {
        // Given
        LocalDateTime before = LocalDateTime.now();
        when(ticketRepository.findUnboardedTicketsDepartingBetween(any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.of());

        // When
        ticketService.processNoShows();

        // Then
        verify(ticketRepository).findUnboardedTicketsDepartingBetween(
                argThat(now -> !now.isBefore(before) && !now.isAfter(LocalDateTime.now())),
                argThat(cutoff -> !cutoff.isBefore(before.plusMinutes(5))
                        && !cutoff.isAfter(LocalDateTime.now().plusMinutes(5))));
    }
}
