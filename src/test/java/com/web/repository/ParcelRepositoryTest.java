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


@DisplayName("ParcelRepository Integration Tests")
class ParcelRepositoryTest extends BaseRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private ParcelRepository parcelRepository;

    private Trip trip;
    private Stop stopBogota;
    private Stop stopTunja;
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
        entityManager.flush();
    }

    @Test
    @DisplayName("Debe encontrar encomienda por código de rastreo")
    void shouldFindParcelByCode() {
        // Given
        Parcel parcel = Parcel.builder()
                .code("PARC-001")
                .trip(trip)
                .senderName("Juan Remitente")
                .senderPhone("3001234567")
                .receiverName("María Destinataria")
                .receiverPhone("3007654321")
                .fromStop(stopBogota)
                .toStop(stopBucaramanga)
                .price(new BigDecimal("30000.00"))
                .weightKg(new BigDecimal("5.50"))
                .status(Parcel.ParcelStatus.CREATED)
                .deliveryOtp("123456")
                .build();
        entityManager.persist(parcel);
        entityManager.flush();

        // When
        Optional<Parcel> result = parcelRepository.findByCode("PARC-001");

        // Then
        assertThat(result).isPresent();
        assertThat(result.get().getSenderName()).isEqualTo("Juan Remitente");
        assertThat(result.get().getReceiverName()).isEqualTo("María Destinataria");
    }

    @Test
    @DisplayName("Debe encontrar encomiendas por viaje")
    void shouldFindParcelsByTripId() {
        // Given - crear 3 encomiendas para el viaje
        for (int i = 1; i <= 3; i++) {
            Parcel parcel = Parcel.builder()
                    .code("PARC-" + i)
                    .trip(trip)
                    .senderName("Remitente " + i)
                    .senderPhone("30012345" + i + "0")
                    .receiverName("Destinatario " + i)
                    .receiverPhone("30076543" + i + "0")
                    .fromStop(stopBogota)
                    .toStop(stopBucaramanga)
                    .price(new BigDecimal("25000.00"))
                    .status(Parcel.ParcelStatus.CREATED)
                    .deliveryOtp("12345" + i)
                    .build();
            entityManager.persist(parcel);
        }
        entityManager.flush();

        // When
        List<Parcel> parcels = parcelRepository.findByTripId(trip.getId());

        // Then
        assertThat(parcels).hasSize(3);
    }

    @Test
    @DisplayName("Debe encontrar encomiendas por estado")
    void shouldFindParcelsByStatus() {
        // Given - crear encomiendas con diferentes estados
        Parcel parcel1 = Parcel.builder()
                .code("PARC-TRANSIT-1")
                .trip(trip)
                .senderName("Juan")
                .senderPhone("3001234567")
                .receiverName("María")
                .receiverPhone("3007654321")
                .fromStop(stopBogota)
                .toStop(stopBucaramanga)
                .price(new BigDecimal("30000.00"))
                .status(Parcel.ParcelStatus.IN_TRANSIT)
                .deliveryOtp("123456")
                .build();

        Parcel parcel2 = Parcel.builder()
                .code("PARC-DELIVERED-1")
                .trip(trip)
                .senderName("Pedro")
                .senderPhone("3002345678")
                .receiverName("Ana")
                .receiverPhone("3008765432")
                .fromStop(stopBogota)
                .toStop(stopTunja)
                .price(new BigDecimal("20000.00"))
                .status(Parcel.ParcelStatus.DELIVERED)
                .deliveryOtp("654321")
                .deliveredAt(LocalDateTime.now())
                .build();

        entityManager.persist(parcel1);
        entityManager.persist(parcel2);
        entityManager.flush();

        // When
        List<Parcel> inTransit = parcelRepository.findByStatus(Parcel.ParcelStatus.IN_TRANSIT);
        List<Parcel> delivered = parcelRepository.findByStatus(Parcel.ParcelStatus.DELIVERED);

        // Then
        assertThat(inTransit).hasSize(1);
        assertThat(delivered).hasSize(1);
    }

    @Test
    @DisplayName("Debe encontrar encomiendas en tránsito de un viaje")
    void shouldFindInTransitParcelsByTrip() {
        // Given
        Parcel parcel1 = Parcel.builder()
                .code("PARC-T1")
                .trip(trip)
                .senderName("Juan")
                .senderPhone("3001234567")
                .receiverName("María")
                .receiverPhone("3007654321")
                .fromStop(stopBogota)
                .toStop(stopTunja)
                .price(new BigDecimal("20000.00"))
                .status(Parcel.ParcelStatus.IN_TRANSIT)
                .deliveryOtp("111111")
                .build();

        Parcel parcel2 = Parcel.builder()
                .code("PARC-T2")
                .trip(trip)
                .senderName("Pedro")
                .senderPhone("3002345678")
                .receiverName("Ana")
                .receiverPhone("3008765432")
                .fromStop(stopTunja)
                .toStop(stopBucaramanga)
                .price(new BigDecimal("25000.00"))
                .status(Parcel.ParcelStatus.IN_TRANSIT)
                .deliveryOtp("222222")
                .build();

        Parcel parcel3 = Parcel.builder()
                .code("PARC-D1")
                .trip(trip)
                .senderName("Carlos")
                .senderPhone("3003456789")
                .receiverName("Laura")
                .receiverPhone("3009876543")
                .fromStop(stopBogota)
                .toStop(stopBucaramanga)
                .price(new BigDecimal("30000.00"))
                .status(Parcel.ParcelStatus.DELIVERED)
                .deliveryOtp("333333")
                .build();

        entityManager.persist(parcel1);
        entityManager.persist(parcel2);
        entityManager.persist(parcel3);
        entityManager.flush();

        // When
        List<Parcel> inTransit = parcelRepository.findInTransitParcelsByTrip(trip.getId());

        // Then
        assertThat(inTransit).hasSize(2);
        assertThat(inTransit).extracting(Parcel::getCode)
                .containsExactlyInAnyOrder("PARC-T1", "PARC-T2");
    }

    @Test
    @DisplayName("Debe encontrar encomiendas para entregar en una parada")
    void shouldFindParcelsForDeliveryAtStop() {
        // Given - encomiendas con diferentes destinos
        Parcel parcel1 = Parcel.builder()
                .code("PARC-TUNJA-1")
                .trip(trip)
                .senderName("Juan")
                .senderPhone("3001234567")
                .receiverName("María")
                .receiverPhone("3007654321")
                .fromStop(stopBogota)
                .toStop(stopTunja) // Se entrega en Tunja
                .price(new BigDecimal("20000.00"))
                .status(Parcel.ParcelStatus.IN_TRANSIT)
                .deliveryOtp("111111")
                .build();

        Parcel parcel2 = Parcel.builder()
                .code("PARC-BGA-1")
                .trip(trip)
                .senderName("Pedro")
                .senderPhone("3002345678")
                .receiverName("Ana")
                .receiverPhone("3008765432")
                .fromStop(stopBogota)
                .toStop(stopBucaramanga) // Se entrega en Bucaramanga
                .price(new BigDecimal("30000.00"))
                .status(Parcel.ParcelStatus.IN_TRANSIT)
                .deliveryOtp("222222")
                .build();

        entityManager.persist(parcel1);
        entityManager.persist(parcel2);
        entityManager.flush();

        // When - buscar encomiendas para entregar en Tunja
        List<Parcel> parcelsForTunja = parcelRepository.findParcelsForDeliveryAtStop(
                trip.getId(),
                stopTunja.getId()
        );

        // Then
        assertThat(parcelsForTunja).hasSize(1);
        assertThat(parcelsForTunja.get(0).getCode()).isEqualTo("PARC-TUNJA-1");
    }

    @Test
    @DisplayName("Debe validar encomienda con código y OTP para entrega")
    void shouldFindParcelByCodeAndOtp() {
        // Given
        Parcel parcel = Parcel.builder()
                .code("PARC-OTP-1")
                .trip(trip)
                .senderName("Juan")
                .senderPhone("3001234567")
                .receiverName("María")
                .receiverPhone("3007654321")
                .fromStop(stopBogota)
                .toStop(stopBucaramanga)
                .price(new BigDecimal("30000.00"))
                .status(Parcel.ParcelStatus.IN_TRANSIT)
                .deliveryOtp("987654")
                .build();
        entityManager.persist(parcel);
        entityManager.flush();

        // When - OTP correcto
        Optional<Parcel> validResult = parcelRepository.findByCodeAndOtp("PARC-OTP-1", "987654");

        // OTP incorrecto
        Optional<Parcel> invalidResult = parcelRepository.findByCodeAndOtp("PARC-OTP-1", "000000");

        // Then
        assertThat(validResult).isPresent();
        assertThat(invalidResult).isEmpty();
    }

    @Test
    @DisplayName("Debe calcular ingresos por encomiendas en rango de fechas")
    void shouldCalculateParcelRevenue() {
        // Given - encomiendas con diferentes estados
        Parcel parcel1 = Parcel.builder()
                .code("PARC-REV-1")
                .trip(trip)
                .senderName("Juan")
                .senderPhone("3001234567")
                .receiverName("María")
                .receiverPhone("3007654321")
                .fromStop(stopBogota)
                .toStop(stopBucaramanga)
                .price(new BigDecimal("25000.00"))
                .status(Parcel.ParcelStatus.DELIVERED)
                .deliveryOtp("111111")
                .build();

        Parcel parcel2 = Parcel.builder()
                .code("PARC-REV-2")
                .trip(trip)
                .senderName("Pedro")
                .senderPhone("3002345678")
                .receiverName("Ana")
                .receiverPhone("3008765432")
                .fromStop(stopBogota)
                .toStop(stopTunja)
                .price(new BigDecimal("20000.00"))
                .status(Parcel.ParcelStatus.IN_TRANSIT)
                .deliveryOtp("222222")
                .build();

        Parcel parcel3 = Parcel.builder()
                .code("PARC-REV-3")
                .trip(trip)
                .senderName("Carlos")
                .senderPhone("3003456789")
                .receiverName("Laura")
                .receiverPhone("3009876543")
                .fromStop(stopBogota)
                .toStop(stopBucaramanga)
                .price(new BigDecimal("30000.00"))
                .status(Parcel.ParcelStatus.FAILED)
                .deliveryOtp("333333")
                .build();

        entityManager.persist(parcel1);
        entityManager.persist(parcel2);
        entityManager.persist(parcel3);
        entityManager.flush();

        // When
        BigDecimal revenue = parcelRepository.calculateParcelRevenue(
                LocalDate.now(),
                LocalDate.now()
        );

        // Then - Solo DELIVERED e IN_TRANSIT (no FAILED)
        assertThat(revenue).isEqualByComparingTo(new BigDecimal("45000.00"));
    }

    @Test
    @DisplayName("Debe contar encomiendas fallidas por rango de fechas")
    void shouldCountFailedParcels() {
        // Given - crear encomiendas con diferentes estados
        Parcel parcel1 = Parcel.builder()
                .code("PARC-FAIL-1")
                .trip(trip)
                .senderName("Juan")
                .senderPhone("3001234567")
                .receiverName("María")
                .receiverPhone("3007654321")
                .fromStop(stopBogota)
                .toStop(stopBucaramanga)
                .price(new BigDecimal("30000.00"))
                .status(Parcel.ParcelStatus.FAILED)
                .deliveryOtp("111111")
                .build();

        Parcel parcel2 = Parcel.builder()
                .code("PARC-FAIL-2")
                .trip(trip)
                .senderName("Pedro")
                .senderPhone("3002345678")
                .receiverName("Ana")
                .receiverPhone("3008765432")
                .fromStop(stopBogota)
                .toStop(stopTunja)
                .price(new BigDecimal("20000.00"))
                .status(Parcel.ParcelStatus.FAILED)
                .deliveryOtp("222222")
                .build();

        Parcel parcel3 = Parcel.builder()
                .code("PARC-OK-1")
                .trip(trip)
                .senderName("Carlos")
                .senderPhone("3003456789")
                .receiverName("Laura")
                .receiverPhone("3009876543")
                .fromStop(stopBogota)
                .toStop(stopBucaramanga)
                .price(new BigDecimal("25000.00"))
                .status(Parcel.ParcelStatus.DELIVERED)
                .deliveryOtp("333333")
                .build();

        entityManager.persist(parcel1);
        entityManager.persist(parcel2);
        entityManager.persist(parcel3);
        entityManager.flush();

        // When
        Long failedCount = parcelRepository.countFailedParcels(
                LocalDate.now(),
                LocalDate.now()
        );

        // Then
        assertThat(failedCount).isEqualTo(2);
    }

    @Test
    @DisplayName("Debe retornar Optional vacío cuando el código no existe")
    void shouldReturnEmptyWhenCodeNotFound() {
        // When
        Optional<Parcel> result = parcelRepository.findByCode("NOEXISTE-001");

        // Then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Debe retornar lista vacía cuando no hay encomiendas en el viaje")
    void shouldReturnEmptyListWhenNoParcelsinTrip() {
        // Given - crear nuevo viaje sin encomiendas
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
        List<Parcel> parcels = parcelRepository.findByTripId(newTrip.getId());

        // Then
        assertThat(parcels).isEmpty();
    }
}
