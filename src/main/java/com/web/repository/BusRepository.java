package com.web.repository;

import com.web.entity.Bus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface BusRepository extends JpaRepository<Bus, Long> {

    // Buscar bus por placa
    Optional<Bus> findByPlate(String plate);

    // Buscar buses por estado
    List<Bus> findByStatus(Bus.BusStatus status);

    // Buscar buses disponibles
    @Query("""
                SELECT b FROM Bus b
                WHERE b.status = 'ACTIVE'
                AND b.id NOT IN (
                    SELECT t.bus.id FROM Trip t
                    WHERE t.tripDate = :date
                    AND t.status NOT IN ('ARRIVED', 'CANCELLED')
                    AND (
                        (t.departureTime <= :departureTime AND t.arrivalEta >= :departureTime)
                        OR (t.departureTime <= :arrivalEta AND t.arrivalEta >= :arrivalEta)
                        OR (t.departureTime >= :departureTime AND t.arrivalEta <= :arrivalEta)
                    )
                )
                ORDER BY b.capacity DESC
            """)
    List<Bus> findAvailableBuses(
            @Param("date") LocalDate date,
            @Param("departureTime") LocalDateTime departureTime,
            @Param("arrivalEta") LocalDateTime arrivalEta);

    // Obtener bus con sus asientos
    @Query("""
                SELECT b FROM Bus b
                LEFT JOIN FETCH b.seats
                WHERE b.id = :busId
            """)
    Optional<Bus> findByIdWithSeats(@Param("busId") Long busId);

    // Buscar buses con capacidad mínima
    @Query("""
                SELECT b FROM Bus b
                WHERE b.status = 'ACTIVE'
                AND b.capacity >= :minCapacity
                ORDER BY b.capacity ASC
            """)
    List<Bus> findByMinimumCapacity(@Param("minCapacity") Integer minCapacity);
}