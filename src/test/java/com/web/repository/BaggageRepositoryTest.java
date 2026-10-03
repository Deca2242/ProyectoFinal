package com.web.repository;

import com.web.entity.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;


@DisplayName("BaggageRepository Integration Tests")
class BaggageRepositoryTest extends BaseRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private BaggageRepository baggageRepository;

    private Trip trip;
    private User passenger;
    private Ticket ticket1;
    private Ticket ticket2;
    private Stop stopBogota;
    private Stop stopBucaramanga;

    @BeforeEach
    void setUp() {
        entityManager.clear();

        // Crear ruta
        Route route = Route.builder()
                .code("BOG-BGA")
                .name("Bogotá - Bucaramanga")
                .origin("Bogotá")
                .destination("Bucaramanga")
                .distanceKm(new BigDecimal("398.50"))
                .durationMin(420)
                .isActive(true)
                .build();
        entityManager.persist(route);

        // Crear paradas
        stopBogota = Stop.builder()
                .route(route)
                .name("Terminal Bogotá")
                .order(1)
                .latitude(new BigDecimal("4.6097"))
                .longitude(new BigDecimal("-74.0817"))
                .build();

        stopBucaramanga = Stop.builder()
                .route(route)
                .name("Terminal Bucaramanga")
                .order(2)
                .latitude(new BigDecimal("7.1193"))
                .longitude(new BigDecimal("-73.1227"))
                .build();

        entityManager.persist(stopBogota);
        entityManager.persist(stopBucaramanga);

        // Crear bus
        Bus bus = Bus.builder()
                .plate("ABC123")
                .capacity(40)
                .amenities(new HashMap<>())
                .status(Bus.BusStatus.ACTIVE)
                .build();
        entityManager.persist(bus);

        // Crear viaje
        trip = Trip.builder()
                .route(route)
                .bus(bus)
                .tripDate(LocalDate.now())
                .departureTime(LocalDateTime.now().plusHours(2))
                .arrivalEta(LocalDateTime.now().plusHours(9))
                .status(Trip.TripStatus.SCHEDULED)
                .build();
        entityManager.persist(trip);

        // Crear pasajero
        passenger = User.builder()
                .name("Laura Pasajera")
                .email("laura@example.com")
                .phone("3004567890")
                .role(User.Role.PASSENGER)
                .status(User.Status.ACTIVE)
                .passwordHash("$2a$10$hashedpassword")
                .build();
        entityManager.persist(passenger);

        // Crear tickets
        ticket1 = Ticket.builder()
                .trip(trip)
                .passenger(passenger)
                .seatNumber(15)
                .fromStop(stopBogota)
                .toStop(stopBucaramanga)
                .price(new BigDecimal("50000.00"))
                .paymentMethod(Ticket.PaymentMethod.CASH)
                .status(Ticket.TicketStatus.SOLD)
                .qrCode("QR-T1")
                .build();

        ticket2 = Ticket.builder()
                .trip(trip)
                .passenger(passenger)
                .seatNumber(16)
                .fromStop(stopBogota)
                .toStop(stopBucaramanga)
                .price(new BigDecimal("50000.00"))
                .paymentMethod(Ticket.PaymentMethod.CASH)
                .status(Ticket.TicketStatus.SOLD)
                .qrCode("QR-T2")
                .build();

        entityManager.persist(ticket1);
        entityManager.persist(ticket2);
        entityManager.flush();
    }

    @Test
    @DisplayName("Debe encontrar equipaje por ticket")
    void shouldFindBaggageByTicketId() {
        // Given
        Baggage baggage = Baggage.builder()
                .ticket(ticket1)
                .weightKg(new BigDecimal("20.50"))
                .excessFee(BigDecimal.ZERO)
                .tagCode("BAG-001")
                .build();
        entityManager.persist(baggage);
        entityManager.flush();

        // When
        Optional<Baggage> result = baggageRepository.findByTicketId(ticket1.getId());

        // Then
        assertThat(result).isPresent();
        assertThat(result.get().getWeightKg()).isEqualByComparingTo(new BigDecimal("20.50"));
        assertThat(result.get().getTagCode()).isEqualTo("BAG-001");
    }

    @Test
    @DisplayName("Debe encontrar equipaje por código de etiqueta")
    void shouldFindBaggageByTagCode() {
        // Given
        Baggage baggage = Baggage.builder()
                .ticket(ticket1)
                .weightKg(new BigDecimal("25.00"))
                .excessFee(new BigDecimal("15000.00"))
                .tagCode("BAG-XYZ-123")
                .build();
        entityManager.persist(baggage);
        entityManager.flush();

        // When
        Optional<Baggage> result = baggageRepository.findByTagCode("BAG-XYZ-123");

        // Then
        assertThat(result).isPresent();
        assertThat(result.get().getExcessFee()).isEqualByComparingTo(new BigDecimal("15000.00"));
    }

    @Test
    @DisplayName("Debe encontrar equipajes por viaje")
    void shouldFindBaggageByTripId() {
        // Given - crear equipajes para 2 tickets del mismo viaje
        Baggage baggage1 = Baggage.builder()
                .ticket(ticket1)
                .weightKg(new BigDecimal("18.00"))
                .excessFee(BigDecimal.ZERO)
                .tagCode("BAG-001")
                .build();

        Baggage baggage2 = Baggage.builder()
                .ticket(ticket2)
                .weightKg(new BigDecimal("25.00"))
                .excessFee(new BigDecimal("10000.00"))
                .tagCode("BAG-002")
                .build();

        entityManager.persist(baggage1);
        entityManager.persist(baggage2);
        entityManager.flush();

        // When
        List<Baggage> baggages = baggageRepository.findByTripId(trip.getId());

        // Then
        assertThat(baggages).hasSize(2);
        assertThat(baggages).extracting(Baggage::getTagCode)
                .containsExactlyInAnyOrder("BAG-001", "BAG-002");
    }

    @Test
    @DisplayName("Debe calcular total de tarifas de exceso para un viaje")
    void shouldCalculateTotalExcessFeesForTrip() {
        // Given - equipajes con diferentes tarifas de exceso
        Baggage baggage1 = Baggage.builder()
                .ticket(ticket1)
                .weightKg(new BigDecimal("25.00"))
                .excessFee(new BigDecimal("10000.00"))
                .tagCode("BAG-001")
                .build();

        Baggage baggage2 = Baggage.builder()
                .ticket(ticket2)
                .weightKg(new BigDecimal("30.00"))
                .excessFee(new BigDecimal("20000.00"))
                .tagCode("BAG-002")
                .build();

        entityManager.persist(baggage1);
        entityManager.persist(baggage2);
        entityManager.flush();

        // When
        BigDecimal totalFees = baggageRepository.calculateTotalExcessFees(trip.getId());

        // Then
        assertThat(totalFees).isEqualByComparingTo(new BigDecimal("30000.00"));
    }

    @Test
    @DisplayName("Debe calcular ingresos por exceso de equipaje por rango de fechas")
    void shouldCalculateExcessBaggageRevenue() {
        // Given - equipajes con tarifas de exceso
        Baggage baggage1 = Baggage.builder()
                .ticket(ticket1)
                .weightKg(new BigDecimal("28.00"))
                .excessFee(new BigDecimal("15000.00"))
                .tagCode("BAG-001")
                .build();

        Baggage baggage2 = Baggage.builder()
                .ticket(ticket2)
                .weightKg(new BigDecimal("22.00"))
                .excessFee(new BigDecimal("5000.00"))
                .tagCode("BAG-002")
                .build();

        entityManager.persist(baggage1);
        entityManager.persist(baggage2);
        entityManager.flush();

        // When
        BigDecimal revenue = baggageRepository.calculateExcessBaggageRevenue(
                LocalDate.now(),
                LocalDate.now()
        );

        // Then
        assertThat(revenue).isEqualByComparingTo(new BigDecimal("20000.00"));
    }

    @Test
    @DisplayName("Debe retornar Optional vacío cuando no hay equipaje para el ticket")
    void shouldReturnEmptyWhenNoBaggageForTicket() {
        // When - ticket2 no tiene equipaje
        Optional<Baggage> result = baggageRepository.findByTicketId(ticket2.getId());

        // Then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Debe retornar 0 cuando no hay tarifas de exceso en el viaje")
    void shouldReturn0WhenNoExcessFeesInTrip() {
        // Given - equipaje sin tarifa de exceso
        Baggage baggage = Baggage.builder()
                .ticket(ticket1)
                .weightKg(new BigDecimal("15.00"))
                .excessFee(BigDecimal.ZERO)
                .tagCode("BAG-001")
                .build();
        entityManager.persist(baggage);
        entityManager.flush();

        // When
        BigDecimal totalFees = baggageRepository.calculateTotalExcessFees(trip.getId());

        // Then
        assertThat(totalFees).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("Debe retornar lista vacía cuando no hay equipajes en el viaje")
    void shouldReturnEmptyListWhenNoBaggageInTrip() {
        // Given - crear nuevo viaje sin equipajes
        Route newRoute = Route.builder()
                .code("MDE-CTG")
                .name("Medellín - Cartagena")
                .origin("Medellín")
                .destination("Cartagena")
                .distanceKm(new BigDecimal("630.00"))
                .durationMin(720)
                .isActive(true)
                .build();
        entityManager.persist(newRoute);

        Bus newBus = Bus.builder()
                .plate("DEF456")
                .capacity(30)
                .amenities(new HashMap<>())
                .status(Bus.BusStatus.ACTIVE)
                .build();
        entityManager.persist(newBus);

        Trip newTrip = Trip.builder()
                .route(newRoute)
                .bus(newBus)
                .tripDate(LocalDate.now())
                .departureTime(LocalDateTime.now().plusHours(5))
                .arrivalEta(LocalDateTime.now().plusHours(17))
                .status(Trip.TripStatus.SCHEDULED)
                .build();
        entityManager.persist(newTrip);
        entityManager.flush();

        // When
        List<Baggage> baggages = baggageRepository.findByTripId(newTrip.getId());

        // Then
        assertThat(baggages).isEmpty();
    }
}
