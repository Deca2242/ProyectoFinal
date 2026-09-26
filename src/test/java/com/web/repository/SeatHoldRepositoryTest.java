package com.web.repository;

import com.web.entity.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;


@DisplayName("SeatHoldRepository Integration Tests")
class SeatHoldRepositoryTest extends BaseRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private SeatHoldRepository seatHoldRepository;

    private Trip trip;
    private User user1;
    private User user2;
    private SeatHold hold1;
    private SeatHold hold2;
    private SeatHold expiredHold;

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
                .departureTime(LocalDateTime.now().plusHours(5))
                .arrivalEta(LocalDateTime.now().plusHours(12))
                .status(Trip.TripStatus.SCHEDULED)
                .build();
        entityManager.persist(trip);

        // Crear usuarios
        user1 = User.builder()
                .name("Laura Pasajera")
                .email("laura@example.com")
                .phone("3004567890")
                .role(User.Role.PASSENGER)
                .status(User.Status.ACTIVE)
                .passwordHash("$2a$10$hashedpassword")
                .build();

        user2 = User.builder()
                .name("Jorge Pasajero")
                .email("jorge@example.com")
                .phone("3005678901")
                .role(User.Role.PASSENGER)
                .status(User.Status.ACTIVE)
                .passwordHash("$2a$10$hashedpassword")
                .build();

        entityManager.persist(user1);
        entityManager.persist(user2);

        // Crear holds activos
        hold1 = SeatHold.builder()
                .trip(trip)
                .seatNumber(15)
                .user(user1)
                .expiresAt(LocalDateTime.now().plusMinutes(10))
                .status(SeatHold.HoldStatus.HOLD)
                .build();

        hold2 = SeatHold.builder()
                .trip(trip)
                .seatNumber(20)
                .user(user2)
                .expiresAt(LocalDateTime.now().plusMinutes(8))
                .status(SeatHold.HoldStatus.HOLD)
                .build();

        // Hold expirado
        expiredHold = SeatHold.builder()
                .trip(trip)
                .seatNumber(10)
                .user(user1)
                .expiresAt(LocalDateTime.now().minusMinutes(5))
                .status(SeatHold.HoldStatus.HOLD)
                .build();

        entityManager.persist(hold1);
        entityManager.persist(hold2);
        entityManager.persist(expiredHold);
        entityManager.flush();
    }

    @Test
    @DisplayName("Debe encontrar hold por viaje, asiento y estado")
    void shouldFindHoldByTripIdAndSeatNumberAndStatus() {
        // When
        Optional<SeatHold> result = seatHoldRepository.findByTripIdAndSeatNumberAndStatus(
                trip.getId(),
                15,
                SeatHold.HoldStatus.HOLD
        );

        // Then
        assertThat(result).isPresent();
        assertThat(result.get().getUser().getName()).isEqualTo("Laura Pasajera");
    }

    @Test
    @DisplayName("Debe encontrar holds expirados antes de una fecha")
    void shouldFindHoldsByExpiresAtBeforeAndStatus() {
        // When
        List<SeatHold> expiredHolds = seatHoldRepository.findByExpiresAtBeforeAndStatus(
                LocalDateTime.now(),
                SeatHold.HoldStatus.HOLD
        );

        // Then
        assertThat(expiredHolds).hasSize(1);
        assertThat(expiredHolds.get(0).getSeatNumber()).isEqualTo(10);
    }

    @Test
    @DisplayName("Debe encontrar holds de un usuario con estado específico")
    void shouldFindHoldsByUserIdAndStatus() {
        // When
        List<SeatHold> user1Holds = seatHoldRepository.findByUserIdAndStatus(
                user1.getId(),
                SeatHold.HoldStatus.HOLD
        );

        // Then
        assertThat(user1Holds).hasSize(2); // hold1 y expiredHold
        assertThat(user1Holds).extracting(SeatHold::getSeatNumber)
                .containsExactlyInAnyOrder(15, 10);
    }

    @Test
    @DisplayName("Debe encontrar hold activo para un asiento (no expirado)")
    void shouldFindActiveHold() {
        // When
        List<SeatHold> result = seatHoldRepository.findActiveHolds(
                trip.getId(),
                15,
                LocalDateTime.now()
        );

        // Then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getSeatNumber()).isEqualTo(15);
    }

    @Test
    @DisplayName("No debe encontrar hold activo cuando está expirado")
    void shouldNotFindActiveHoldWhenExpired() {
        // When
        List<SeatHold> result = seatHoldRepository.findActiveHolds(
                trip.getId(),
                10, // Este hold está expirado
                LocalDateTime.now()
        );

        // Then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Debe encontrar todos los holds que han expirado")
    void shouldFindExpiredHolds() {
        // When
        List<SeatHold> expired = seatHoldRepository.findExpiredHolds(LocalDateTime.now());

        // Then
        assertThat(expired).hasSize(1);
        assertThat(expired.get(0).getSeatNumber()).isEqualTo(10);
    }

    @Test
    @Transactional
    @DisplayName("Debe actualizar masivamente holds expirados")
    void shouldExpireHoldsInBulk() {
        // When
        int updated = seatHoldRepository.expireHolds(LocalDateTime.now());
        entityManager.flush();
        entityManager.clear();

        // Then
        assertThat(updated).isEqualTo(1);

        // Verificar que el hold fue actualizado
        SeatHold updatedHold = entityManager.find(SeatHold.class, expiredHold.getId());
        assertThat(updatedHold.getStatus()).isEqualTo(SeatHold.HoldStatus.EXPIRED);
    }

    @Test
    @DisplayName("Debe encontrar todos los holds activos de un viaje")
    void shouldFindActiveHoldsByTrip() {
        // When
        List<SeatHold> activeHolds = seatHoldRepository.findActiveHoldsByTrip(
                trip.getId(),
                LocalDateTime.now()
        );

        // Then
        assertThat(activeHolds).hasSize(2); // hold1 y hold2
        assertThat(activeHolds).extracting(SeatHold::getSeatNumber)
                .containsExactlyInAnyOrder(15, 20);
    }

    @Test
    @DisplayName("Debe encontrar holds activos de un usuario para un viaje específico")
    void shouldFindUserActiveHoldsForTrip() {
        // When
        List<SeatHold> userHolds = seatHoldRepository.findUserActiveHoldsForTrip(
                trip.getId(),
                user1.getId(),
                LocalDateTime.now()
        );

        // Then
        assertThat(userHolds).hasSize(1); // solo hold1, expiredHold no cuenta
        assertThat(userHolds.get(0).getSeatNumber()).isEqualTo(15);
    }

    @Test
    @DisplayName("Un hold sin tramo bloquea el asiento en cualquier tramo")
    void shouldFindFullTripHoldForAnySegment() {
        // When
        List<SeatHold> result = seatHoldRepository.findOverlappingActiveHolds(
                trip.getId(),
                15,
                2,
                3,
                LocalDateTime.now()
        );

        // Then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getSeatNumber()).isEqualTo(15);
        assertThat(result.get(0).getUser().getName()).isEqualTo("Laura Pasajera");
    }

    @Test
    @DisplayName("Debe retornar lista vacía cuando no hay holds activos para el viaje")
    void shouldReturnEmptyListWhenNoActiveHoldsForTrip() {
        // Given - crear nuevo viaje sin holds
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
                .departureTime(LocalDateTime.now().plusHours(10))
                .arrivalEta(LocalDateTime.now().plusHours(22))
                .status(Trip.TripStatus.SCHEDULED)
                .build();
        entityManager.persist(newTrip);
        entityManager.flush();

        // When
        List<SeatHold> activeHolds = seatHoldRepository.findActiveHoldsByTrip(
                newTrip.getId(),
                LocalDateTime.now()
        );

        // Then
        assertThat(activeHolds).isEmpty();
    }
}
