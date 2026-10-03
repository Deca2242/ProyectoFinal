package com.web.repository;

import com.web.entity.Seat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    // Buscar asientos de un bus ordenados por número
    List<Seat> findByBusIdOrderBySeatNumberAsc(Long busId);

    // Buscar asiento por bus y número de asiento
    Optional<Seat> findByBusIdAndSeatNumber(Long busId, Integer seatNumber);

    // Buscar asientos por tipo
    List<Seat> findByBusIdAndSeatType(Long busId, Seat.SeatType seatType);

    // Contar asientos preferenciales en un bus
    @Query("""
        SELECT COUNT(s)
        FROM Seat s
        WHERE s.bus.id = :busId
        AND s.seatType = 'PREFERENTIAL'
    """)
    Long countPreferentialSeats(@Param("busId") Long busId);

    // Elimina las sillas por encima de la nueva capacidad al reducirla
    @Modifying(flushAutomatically = true)
    @Query("""
        DELETE FROM Seat s
        WHERE s.bus.id = :busId
        AND s.seatNumber > :capacity
    """)
    int deleteByBusIdAndSeatNumberGreaterThan(@Param("busId") Long busId, @Param("capacity") Integer capacity);
}
