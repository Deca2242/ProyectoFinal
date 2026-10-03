package com.web.repository;

import com.web.entity.Bus;
import com.web.entity.Seat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;


@DisplayName("SeatRepository Integration Tests")
class SeatRepositoryTest extends BaseRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private SeatRepository seatRepository;

    private Bus bus1;
    private Bus bus2;

    @BeforeEach
    void setUp() {
        entityManager.clear();

        // Crear buses
        bus1 = Bus.builder()
                .plate("ABC123")
                .capacity(40)
                .amenities(new HashMap<>())
                .status(Bus.BusStatus.ACTIVE)
                .build();

        bus2 = Bus.builder()
                .plate("DEF456")
                .capacity(20)
                .amenities(new HashMap<>())
                .status(Bus.BusStatus.ACTIVE)
                .build();

        entityManager.persist(bus1);
        entityManager.persist(bus2);

        // Crear asientos para bus1 (40 total: 36 STANDARD + 4 PREFERENTIAL)
        for (int i = 1; i <= 40; i++) {
            Seat seat = Seat.builder()
                    .bus(bus1)
                    .seatNumber(i)
                    .seatType(i <= 36 ? Seat.SeatType.STANDARD : Seat.SeatType.PREFERENTIAL)
                    .build();
            entityManager.persist(seat);
        }

        // Crear asientos para bus2 (20 total: 18 STANDARD + 2 PREFERENTIAL)
        for (int i = 1; i <= 20; i++) {
            Seat seat = Seat.builder()
                    .bus(bus2)
                    .seatNumber(i)
                    .seatType(i <= 18 ? Seat.SeatType.STANDARD : Seat.SeatType.PREFERENTIAL)
                    .build();
            entityManager.persist(seat);
        }

        entityManager.flush();
    }

    @Test
    @DisplayName("Debe encontrar asientos de un bus ordenados por número")
    void shouldFindSeatsByBusOrderedBySeatNumber() {
        // When
        List<Seat> seats = seatRepository.findByBusIdOrderBySeatNumberAsc(bus1.getId());

        // Then
        assertThat(seats).hasSize(40);
        assertThat(seats.get(0).getSeatNumber()).isEqualTo(1);
        assertThat(seats.get(39).getSeatNumber()).isEqualTo(40);
        // Verificar que están ordenados
        for (int i = 0; i < seats.size() - 1; i++) {
            assertThat(seats.get(i).getSeatNumber()).isLessThan(seats.get(i + 1).getSeatNumber());
        }
    }

    @Test
    @DisplayName("Debe encontrar asiento por bus y número de asiento")
    void shouldFindSeatByBusIdAndSeatNumber() {
        // When
        Optional<Seat> result = seatRepository.findByBusIdAndSeatNumber(bus1.getId(), 15);

        // Then
        assertThat(result).isPresent();
        assertThat(result.get().getSeatNumber()).isEqualTo(15);
        assertThat(result.get().getBus().getPlate()).isEqualTo("ABC123");
    }

    @Test
    @DisplayName("Debe retornar Optional vacío cuando el asiento no existe")
    void shouldReturnEmptyWhenSeatNotFound() {
        // When
        Optional<Seat> result = seatRepository.findByBusIdAndSeatNumber(bus1.getId(), 99);

        // Then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Debe encontrar asientos por tipo")
    void shouldFindSeatsByBusIdAndSeatType() {
        // When
        List<Seat> standardSeats = seatRepository.findByBusIdAndSeatType(bus1.getId(), Seat.SeatType.STANDARD);
        List<Seat> preferentialSeats = seatRepository.findByBusIdAndSeatType(bus1.getId(), Seat.SeatType.PREFERENTIAL);

        // Then
        assertThat(standardSeats).hasSize(36);
        assertThat(preferentialSeats).hasSize(4);
        assertThat(preferentialSeats).extracting(Seat::getSeatNumber)
                .containsExactlyInAnyOrder(37, 38, 39, 40);
    }

    @Test
    @DisplayName("Debe contar asientos preferenciales de un bus")
    void shouldCountPreferentialSeats() {
        // When
        Long countBus1 = seatRepository.countPreferentialSeats(bus1.getId());
        Long countBus2 = seatRepository.countPreferentialSeats(bus2.getId());

        // Then
        assertThat(countBus1).isEqualTo(4);
        assertThat(countBus2).isEqualTo(2);
    }

    @Test
    @DisplayName("Debe retornar 0 cuando un bus no tiene asientos preferenciales")
    void shouldReturn0WhenBusHasNoPreferentialSeats() {
        // Given - crear bus sin asientos preferenciales
        Bus bus3 = Bus.builder()
                .plate("GHI789")
                .capacity(10)
                .amenities(new HashMap<>())
                .status(Bus.BusStatus.ACTIVE)
                .build();
        entityManager.persist(bus3);

        for (int i = 1; i <= 10; i++) {
            Seat seat = Seat.builder()
                    .bus(bus3)
                    .seatNumber(i)
                    .seatType(Seat.SeatType.STANDARD)
                    .build();
            entityManager.persist(seat);
        }
        entityManager.flush();

        // When
        Long count = seatRepository.countPreferentialSeats(bus3.getId());

        // Then
        assertThat(count).isEqualTo(0);
    }

    @Test
    @DisplayName("Debe retornar lista vacía cuando el bus no tiene asientos")
    void shouldReturnEmptyListWhenBusHasNoSeats() {
        // Given - crear bus sin asientos
        Bus emptyBus = Bus.builder()
                .plate("EMPTY01")
                .capacity(30)
                .amenities(new HashMap<>())
                .status(Bus.BusStatus.ACTIVE)
                .build();
        entityManager.persist(emptyBus);
        entityManager.flush();

        // When
        List<Seat> seats = seatRepository.findByBusIdOrderBySeatNumberAsc(emptyBus.getId());

        // Then
        assertThat(seats).isEmpty();
    }

    @Test
    @DisplayName("Debe diferenciar asientos del mismo número en diferentes buses")
    void shouldDifferentiateSameSeatNumberInDifferentBuses() {
        // When
        Optional<Seat> seat1Bus1 = seatRepository.findByBusIdAndSeatNumber(bus1.getId(), 1);
        Optional<Seat> seat1Bus2 = seatRepository.findByBusIdAndSeatNumber(bus2.getId(), 1);

        // Then
        assertThat(seat1Bus1).isPresent();
        assertThat(seat1Bus2).isPresent();
        assertThat(seat1Bus1.get().getId()).isNotEqualTo(seat1Bus2.get().getId());
        assertThat(seat1Bus1.get().getBus().getPlate()).isEqualTo("ABC123");
        assertThat(seat1Bus2.get().getBus().getPlate()).isEqualTo("DEF456");
    }
}
