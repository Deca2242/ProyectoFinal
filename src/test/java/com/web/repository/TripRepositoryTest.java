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


@DisplayName("TripRepository Integration Tests")
class TripRepositoryTest extends BaseRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private TripRepository tripRepository;

    private Route route;
    private Bus bus1;
    private Bus bus2;
    private Trip trip1;
    private Trip trip2;
    private Trip trip3;
    private User driver;
    private User dispatcher;
    private Stop stopBogota;
    private Stop stopTunja;
    private Stop stopBucaramanga;

    @BeforeEach
    void setUp() {
        entityManager.clear();

        // Crear ruta
        route = Route.builder()
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

        stopTunja = Stop.builder()
                .route(route)
                .name("Terminal Tunja")
                .order(2)
                .latitude(new BigDecimal("5.5353"))
                .longitude(new BigDecimal("-73.3678"))
                .build();

        stopBucaramanga = Stop.builder()
                .route(route)
                .name("Terminal Bucaramanga")
                .order(3)
                .latitude(new BigDecimal("7.1193"))
                .longitude(new BigDecimal("-73.1227"))
                .build();

        entityManager.persist(stopBogota);
        entityManager.persist(stopTunja);
        entityManager.persist(stopBucaramanga);

        // Crear buses
        bus1 = Bus.builder()
                .plate("ABC123")
                .capacity(40)
                .amenities(new HashMap<>())
                .status(Bus.BusStatus.ACTIVE)
                .build();

        bus2 = Bus.builder()
                .plate("DEF456")
                .capacity(30)
                .amenities(new HashMap<>())
                .status(Bus.BusStatus.ACTIVE)
                .build();

        entityManager.persist(bus1);
        entityManager.persist(bus2);

        // Crear usuarios
        driver = User.builder()
                .name("Juan Conductor")
                .email("juan.conductor@bus.com")
                .phone("3001234567")
                .role(User.Role.DRIVER)
                .status(User.Status.ACTIVE)
                .passwordHash("$2a$10$hashedpassword")
                .build();

        dispatcher = User.builder()
                .name("Carlos Despachador")
                .email("carlos.despachador@bus.com")
                .phone("3003456789")
                .role(User.Role.DISPATCHER)
                .status(User.Status.ACTIVE)
                .passwordHash("$2a$10$hashedpassword")
                .build();

        entityManager.persist(driver);
        entityManager.persist(dispatcher);

        // Crear viajes
        LocalDate today = LocalDate.now();
        LocalDate tomorrow = today.plusDays(1);

        trip1 = Trip.builder()
                .route(route)
                .bus(bus1)
                .tripDate(today)
                .departureTime(LocalDateTime.now().plusHours(2))
                .arrivalEta(LocalDateTime.now().plusHours(9))
                .status(Trip.TripStatus.SCHEDULED)
                .build();

        trip2 = Trip.builder()
                .route(route)
                .bus(bus2)
                .tripDate(today)
                .departureTime(LocalDateTime.now().plusHours(4))
                .arrivalEta(LocalDateTime.now().plusHours(11))
                .status(Trip.TripStatus.SCHEDULED)
                .build();

        trip3 = Trip.builder()
                .route(route)
                .bus(bus1)
                .tripDate(tomorrow)
                .departureTime(LocalDateTime.now().plusDays(1).plusHours(8))
                .arrivalEta(LocalDateTime.now().plusDays(1).plusHours(15))
                .status(Trip.TripStatus.CANCELLED)
                .build();

        entityManager.persist(trip1);
        entityManager.persist(trip2);
        entityManager.persist(trip3);
        entityManager.flush();
    }

    @Test
    @DisplayName("Debe encontrar viajes por ruta y fecha")
    void shouldFindTripsByRouteAndDate() {
        // When
        List<Trip> trips = tripRepository.findByRouteIdAndTripDate(route.getId(), LocalDate.now());

        // Then
        assertThat(trips).hasSize(2);
        assertThat(trips).extracting(Trip::getStatus)
                .containsExactlyInAnyOrder(Trip.TripStatus.SCHEDULED, Trip.TripStatus.SCHEDULED);
    }

    @Test
    @DisplayName("Debe encontrar viajes por ruta, fecha y estado")
    void shouldFindTripsByRouteAndDateAndStatus() {
        // When
        List<Trip> scheduledTrips = tripRepository.findByRouteIdAndTripDateAndStatus(
                route.getId(),
                LocalDate.now(),
                Trip.TripStatus.SCHEDULED
        );

        List<Trip> cancelledTrips = tripRepository.findByRouteIdAndTripDateAndStatus(
                route.getId(),
                LocalDate.now().plusDays(1),
                Trip.TripStatus.CANCELLED
        );

        // Then
        assertThat(scheduledTrips).hasSize(2);
        assertThat(cancelledTrips).hasSize(1);
    }

    @Test
    @DisplayName("Debe encontrar viajes por estado")
    void shouldFindTripsByStatus() {
        // When
        List<Trip> scheduledTrips = tripRepository.findByStatus(Trip.TripStatus.SCHEDULED);
        List<Trip> cancelledTrips = tripRepository.findByStatus(Trip.TripStatus.CANCELLED);

        // Then
        assertThat(scheduledTrips).hasSize(2);
        assertThat(cancelledTrips).hasSize(1);
    }

    @Test
    @DisplayName("Debe encontrar viajes que salen pronto")
    void shouldFindTripsDepartingSoon() {
        // When
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime maxTime = now.plusHours(3);
        List<Trip> departingSoon = tripRepository.findTripsDepartingSoon(now, maxTime);

        // Then - solo trip1 sale en 2 horas
        assertThat(departingSoon).hasSize(1);
        assertThat(departingSoon.get(0).getId()).isEqualTo(trip1.getId());
    }

    @Test
    @DisplayName("Debe obtener viaje con detalles completos (fetch join)")
    void shouldFindTripByIdWithDetails() {
        // Given - crear asignación
        Assignment assignment = Assignment.builder()
                .trip(trip1)
                .driver(driver)
                .dispatcher(dispatcher)
                .checklistOk(true)
                .build();
        entityManager.persist(assignment);
        entityManager.flush();
        entityManager.clear(); // Limpiar contexto para forzar uso de query con fetch join

        // When
        Optional<Trip> result = tripRepository.findByIdWithDetails(trip1.getId());

        // Then
        assertThat(result).isPresent();
        Trip trip = result.get();
        assertThat(trip.getAssignment()).isNotNull();
        assertThat(trip.getAssignment().getDriver().getName()).isEqualTo("Juan Conductor");
        assertThat(trip.getBus().getPlate()).isEqualTo("ABC123");
        assertThat(trip.getRoute().getCode()).isEqualTo("BOG-BGA");
    }

    @Test
    @DisplayName("Debe calcular porcentaje de ocupación de un viaje")
    void shouldGetOccupancyPercentage() {
        // Given - crear tickets (10 asientos vendidos de 40)
        User passenger = User.builder()
                .name("Laura Pasajera")
                .email("laura@example.com")
                .phone("3004567890")
                .role(User.Role.PASSENGER)
                .status(User.Status.ACTIVE)
                .passwordHash("$2a$10$hashedpassword")
                .build();
        entityManager.persist(passenger);

        for (int i = 1; i <= 10; i++) {
            Ticket ticket = Ticket.builder()
                    .trip(trip1)
                    .passenger(passenger)
                    .seatNumber(i)
                    .fromStop(stopBogota)
                    .toStop(stopBucaramanga)
                    .price(new BigDecimal("50000.00"))
                    .paymentMethod(Ticket.PaymentMethod.CASH)
                    .status(Ticket.TicketStatus.SOLD)
                    .qrCode("QR-" + i)
                    .build();
            entityManager.persist(ticket);
        }
        entityManager.flush();

        // When
        Double occupancy = tripRepository.getOccupancyPercentage(trip1.getId());

        // Then
        assertThat(occupancy).isEqualTo(25.0); // 10/40 * 100 = 25%
    }

    @Test
    @DisplayName("Debe obtener cantidad de asientos disponibles para un tramo")
    void shouldGetAvailableSeatsForSegment() {
        // Given - crear tickets para tramo Bogotá -> Tunja (5 asientos)
        User passenger = User.builder()
                .name("Jorge Pasajero")
                .email("jorge@example.com")
                .phone("3005678901")
                .role(User.Role.PASSENGER)
                .status(User.Status.ACTIVE)
                .passwordHash("$2a$10$hashedpassword")
                .build();
        entityManager.persist(passenger);

        for (int i = 1; i <= 5; i++) {
            Ticket ticket = Ticket.builder()
                    .trip(trip1)
                    .passenger(passenger)
                    .seatNumber(i)
                    .fromStop(stopBogota)
                    .toStop(stopTunja)
                    .price(new BigDecimal("25000.00"))
                    .paymentMethod(Ticket.PaymentMethod.CASH)
                    .status(Ticket.TicketStatus.SOLD)
                    .qrCode("QR-TRAMO-" + i)
                    .build();
            entityManager.persist(ticket);
        }
        entityManager.flush();

        // When - buscar disponibilidad para tramo Bogotá -> Tunja
        Long available = tripRepository.getAvailableSeatsForSegment(
                trip1.getId(),
                stopBogota.getOrder(),
                stopTunja.getOrder()
        );

        // Then - 40 - 5 = 35 disponibles
        assertThat(available).isEqualTo(35);
    }

    @Test
    @DisplayName("Debe retornar capacidad completa cuando no hay tickets vendidos")
    void shouldReturnFullCapacityWhenNoTicketsSold() {
        // When
        Long available = tripRepository.getAvailableSeatsForSegment(
                trip2.getId(),
                stopBogota.getOrder(),
                stopBucaramanga.getOrder()
        );

        // Then
        assertThat(available).isEqualTo(30); // Capacidad completa del bus2
    }

    @Test
    @DisplayName("Debe encontrar viajes sin asignación")
    void shouldFindUnassignedTrips() {
        // When
        List<Trip> unassigned = tripRepository.findUnassignedTrips(LocalDate.now());

        // Then - trip1 y trip2 no tienen asignación
        assertThat(unassigned).hasSize(2);
        assertThat(unassigned).extracting(Trip::getId)
                .containsExactlyInAnyOrder(trip1.getId(), trip2.getId());
    }

    @Test
    @DisplayName("Debe encontrar viajes asignados a un conductor")
    void shouldFindTripsByDriver() {
        // Given - asignar trip1 al conductor
        Assignment assignment = Assignment.builder()
                .trip(trip1)
                .driver(driver)
                .dispatcher(dispatcher)
                .checklistOk(false)
                .build();
        entityManager.persist(assignment);
        entityManager.flush();

        // When
        List<Trip> driverTrips = tripRepository.findTripsByDriver(driver.getId(), LocalDate.now());

        // Then
        assertThat(driverTrips).hasSize(1);
        assertThat(driverTrips.get(0).getId()).isEqualTo(trip1.getId());
    }

    @Test
    @DisplayName("Debe encontrar viajes por rango de fechas")
    void shouldFindTripsByDateRange() {
        // When
        List<Trip> trips = tripRepository.findByDateRange(LocalDate.now(), LocalDate.now().plusDays(1));

        // Then
        assertThat(trips).hasSize(3);
    }

    @Test
    @DisplayName("Debe calcular ocupación promedio de viajes")
    void shouldGetAverageOccupancy() {
        // Given - crear tickets para trip1 (25% ocupación) y trip2 (50% ocupación)
        User passenger = User.builder()
                .name("Ana Pasajera")
                .email("ana@example.com")
                .phone("3006789012")
                .role(User.Role.PASSENGER)
                .status(User.Status.ACTIVE)
                .passwordHash("$2a$10$hashedpassword")
                .build();
        entityManager.persist(passenger);

        // 10 tickets en trip1 (capacidad 40) = 25%
        for (int i = 1; i <= 10; i++) {
            Ticket ticket = Ticket.builder()
                    .trip(trip1)
                    .passenger(passenger)
                    .seatNumber(i)
                    .fromStop(stopBogota)
                    .toStop(stopBucaramanga)
                    .price(new BigDecimal("50000.00"))
                    .paymentMethod(Ticket.PaymentMethod.CASH)
                    .status(Ticket.TicketStatus.SOLD)
                    .qrCode("QR-T1-" + i)
                    .build();
            entityManager.persist(ticket);
        }

        // 15 tickets en trip2 (capacidad 30) = 50%
        for (int i = 1; i <= 15; i++) {
            Ticket ticket = Ticket.builder()
                    .trip(trip2)
                    .passenger(passenger)
                    .seatNumber(i)
                    .fromStop(stopBogota)
                    .toStop(stopBucaramanga)
                    .price(new BigDecimal("50000.00"))
                    .paymentMethod(Ticket.PaymentMethod.CASH)
                    .status(Ticket.TicketStatus.SOLD)
                    .qrCode("QR-T2-" + i)
                    .build();
            entityManager.persist(ticket);
        }
        entityManager.flush();

        // When
        Double avgOccupancy = tripRepository.getAverageOccupancy(LocalDate.now(), LocalDate.now());

        // Then - promedio de 25% y 50% = 37.5%
        assertThat(avgOccupancy).isBetween(35.0, 40.0); // Margen por precision
    }

    @Test
    @DisplayName("Debe retornar 0 cuando no hay viajes en el rango de fechas")
    void shouldReturn0WhenNoTripsInDateRange() {
        // When
        Double avgOccupancy = tripRepository.getAverageOccupancy(
                LocalDate.now().minusDays(10),
                LocalDate.now().minusDays(5)
        );

        // Then
        assertThat(avgOccupancy).isEqualTo(0.0);
    }
}
