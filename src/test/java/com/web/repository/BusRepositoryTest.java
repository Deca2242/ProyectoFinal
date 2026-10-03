package com.web.repository;

import com.web.entity.Bus;
import com.web.entity.Route;
import com.web.entity.Seat;
import com.web.entity.Trip;
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
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;


@DisplayName("BusRepository Integration Tests")
class BusRepositoryTest extends BaseRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private BusRepository busRepository;

    private Bus bus1;
    private Bus bus2;
    private Bus busInMaintenance;
    private Bus busRetired;

    @BeforeEach
    void setUp() {
        entityManager.clear();

        // Bus activo 1 - capacidad 40
        Map<String, Object> amenities1 = new HashMap<>();
        amenities1.put("wifi", true);
        amenities1.put("ac", true);
        amenities1.put("bathroom", true);

        bus1 = Bus.builder()
                .plate("ABC123")
                .capacity(40)
                .amenities(amenities1)
                .status(Bus.BusStatus.ACTIVE)
                .build();

        // Bus activo 2 - capacidad 30
        Map<String, Object> amenities2 = new HashMap<>();
        amenities2.put("wifi", false);
        amenities2.put("ac", true);

        bus2 = Bus.builder()
                .plate("DEF456")
                .capacity(30)
                .amenities(amenities2)
                .status(Bus.BusStatus.ACTIVE)
                .build();

        // Bus en mantenimiento - capacidad 35
        busInMaintenance = Bus.builder()
                .plate("GHI789")
                .capacity(35)
                .amenities(new HashMap<>())
                .status(Bus.BusStatus.MAINTENANCE)
                .build();

        // Bus retirado
        busRetired = Bus.builder()
                .plate("OLD999")
                .capacity(25)
                .amenities(new HashMap<>())
                .status(Bus.BusStatus.RETIRED)
                .build();

        entityManager.persist(bus1);
        entityManager.persist(bus2);
        entityManager.persist(busInMaintenance);
        entityManager.persist(busRetired);
        entityManager.flush();
    }

    @Test
    @DisplayName("Debe encontrar bus por placa")
    void shouldFindBusByPlate() {
        // When
        Optional<Bus> result = busRepository.findByPlate("ABC123");

        // Then
        assertThat(result).isPresent();
        assertThat(result.get().getCapacity()).isEqualTo(40);
        assertThat(result.get().getStatus()).isEqualTo(Bus.BusStatus.ACTIVE);
    }

    @Test
    @DisplayName("Debe retornar Optional vacío cuando la placa no existe")
    void shouldReturnEmptyWhenPlateNotFound() {
        // When
        Optional<Bus> result = busRepository.findByPlate("XXX999");

        // Then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Debe encontrar buses por estado")
    void shouldFindBusesByStatus() {
        // When
        List<Bus> activeBuses = busRepository.findByStatus(Bus.BusStatus.ACTIVE);
        List<Bus> maintenanceBuses = busRepository.findByStatus(Bus.BusStatus.MAINTENANCE);
        List<Bus> retiredBuses = busRepository.findByStatus(Bus.BusStatus.RETIRED);

        // Then
        assertThat(activeBuses).hasSize(2);
        assertThat(activeBuses).extracting(Bus::getPlate)
                .containsExactlyInAnyOrder("ABC123", "DEF456");
        assertThat(maintenanceBuses).hasSize(1);
        assertThat(retiredBuses).hasSize(1);
    }

    @Test
    @DisplayName("Debe encontrar buses disponibles (no en uso en horario específico)")
    void shouldFindAvailableBuses() {
        // Given - crear una ruta y un viaje usando bus1
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

        LocalDateTime departureTime = LocalDateTime.of(2024, 6, 15, 8, 0);
        LocalDateTime arrivalEta = departureTime.plusMinutes(420);

        Trip trip = Trip.builder()
                .route(route)
                .bus(bus1)
                .tripDate(LocalDate.of(2024, 6, 15))
                .departureTime(departureTime)
                .arrivalEta(arrivalEta)
                .status(Trip.TripStatus.SCHEDULED)
                .build();
        entityManager.persist(trip);
        entityManager.flush();

        // When - buscar buses disponibles en horario que NO solapa
        LocalDateTime searchDeparture = LocalDateTime.of(2024, 6, 15, 15, 0);
        LocalDateTime searchArrival = searchDeparture.plusMinutes(420);
        List<Bus> availableBuses = busRepository.findAvailableBuses(
                LocalDate.of(2024, 6, 15),
                searchDeparture,
                searchArrival
        );

        // Then - bus1 está ocupado, bus2 debería estar disponible (busInMaintenance no es ACTIVE)
        assertThat(availableBuses).hasSize(1);
        assertThat(availableBuses.get(0).getPlate()).isEqualTo("DEF456");
    }

    @Test
    @DisplayName("Debe obtener bus con sus asientos (fetch join)")
    void shouldFindBusByIdWithSeats() {
        // Given - crear asientos para bus1
        for (int i = 1; i <= 5; i++) {
            Seat seat = Seat.builder()
                    .bus(bus1)
                    .seatNumber(i)
                    .seatType(i <= 4 ? Seat.SeatType.STANDARD : Seat.SeatType.PREFERENTIAL)
                    .build();
            entityManager.persist(seat);
        }
        entityManager.flush();
        entityManager.clear(); // Limpiar contexto para forzar uso de query con fetch join

        // When
        Optional<Bus> result = busRepository.findByIdWithSeats(bus1.getId());

        // Then
        assertThat(result).isPresent();
        Bus bus = result.get();
        assertThat(bus.getSeats()).isNotNull();
        assertThat(bus.getSeats()).hasSize(5);
        assertThat(bus.getSeats()).extracting(Seat::getSeatNumber)
                .containsExactlyInAnyOrder(1, 2, 3, 4, 5);
    }

    @Test
    @DisplayName("Debe encontrar buses con capacidad mínima especificada")
    void shouldFindBusesByMinimumCapacity() {
        // When
        List<Bus> buses30Plus = busRepository.findByMinimumCapacity(30);
        List<Bus> buses40Plus = busRepository.findByMinimumCapacity(40);

        // Then
        assertThat(buses30Plus).hasSize(2); // bus1 (40) y bus2 (30)
        assertThat(buses30Plus).extracting(Bus::getPlate)
                .containsExactlyInAnyOrder("DEF456", "ABC123");
        assertThat(buses40Plus).hasSize(1); // solo bus1 (40)
        assertThat(buses40Plus.get(0).getPlate()).isEqualTo("ABC123");
    }

    @Test
    @DisplayName("Debe retornar lista vacía cuando no hay buses activos con capacidad mínima")
    void shouldReturnEmptyListWhenNoActiveBusesWithMinCapacity() {
        // When
        List<Bus> buses = busRepository.findByMinimumCapacity(50);

        // Then
        assertThat(buses).isEmpty();
    }

    @Test
    @DisplayName("No debe incluir buses en mantenimiento en búsqueda de disponibles")
    void shouldNotIncludeMaintenanceBusesInAvailableSearch() {
        // When - buscar buses disponibles
        LocalDateTime searchDeparture = LocalDateTime.of(2024, 6, 15, 8, 0);
        LocalDateTime searchArrival = searchDeparture.plusMinutes(420);
        List<Bus> availableBuses = busRepository.findAvailableBuses(
                LocalDate.of(2024, 6, 15),
                searchDeparture,
                searchArrival
        );

        // Then
        assertThat(availableBuses).doesNotContain(busInMaintenance);
        assertThat(availableBuses).allMatch(bus -> bus.getStatus() == Bus.BusStatus.ACTIVE);
    }
}
