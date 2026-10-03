package com.web.repository;

import com.web.entity.SeatHold;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SeatHoldRepository extends JpaRepository<SeatHold, Long> {

    // Buscar hold por viaje, asiento y estado
    Optional<SeatHold> findByTripIdAndSeatNumberAndStatus(Long tripId, Integer seatNumber, SeatHold.HoldStatus status);

    // Buscar holds expirados (antes de una fecha/hora)
    List<SeatHold> findByExpiresAtBeforeAndStatus(LocalDateTime now, SeatHold.HoldStatus status);

    // Buscar todos los holds de un usuario con estado específico
    List<SeatHold> findByUserIdAndStatus(Long userId, SeatHold.HoldStatus status);

    // Holds activos (estado HOLD y no expirados) de un asiento; puede haber varios si reservan tramos distintos
    @Query("""
                SELECT h FROM SeatHold h
                WHERE h.trip.id = :tripId
                AND h.seatNumber = :seatNumber
                AND h.status = 'HOLD'
                AND h.expiresAt > :now
            """)
    List<SeatHold> findActiveHolds(
            @Param("tripId") Long tripId,
            @Param("seatNumber") Integer seatNumber,
            @Param("now") LocalDateTime now);

    // Buscar todos los holds que han expirado y necesitan actualización de estado
    @Query("""
                SELECT h FROM SeatHold h
                WHERE h.status = 'HOLD'
                AND h.expiresAt <= :now
            """)
    List<SeatHold> findExpiredHolds(@Param("now") LocalDateTime now);

    // Actualización masiva de holds expirados
    @Modifying
    @Query("""
                UPDATE SeatHold h
                SET h.status = 'EXPIRED'
                WHERE h.status = 'HOLD'
                AND h.expiresAt < :now
            """)
    int expireHolds(@Param("now") LocalDateTime now);

    // Buscar todos los holds activos de un viaje
    @Query("""
                SELECT h FROM SeatHold h
                WHERE h.trip.id = :tripId
                AND h.status = 'HOLD'
                AND h.expiresAt > :now
            """)
    List<SeatHold> findActiveHoldsByTrip(
            @Param("tripId") Long tripId,
            @Param("now") LocalDateTime now);

    // Buscar holds activos de un usuario para un viaje específico
    @Query("""
                SELECT h FROM SeatHold h
                WHERE h.trip.id = :tripId
                AND h.user.id = :userId
                AND h.status = 'HOLD'
                AND h.expiresAt > :now
            """)
    List<SeatHold> findUserActiveHoldsForTrip(
            @Param("tripId") Long tripId,
            @Param("userId") Long userId,
            @Param("now") LocalDateTime now);

    // Holds activos de un asiento que se solapan con el tramo indicado (por orden de parada).
    // Un hold sin paradas bloquea todo el viaje, por lo que siempre se solapa.
    @Query("""
                SELECT h FROM SeatHold h
                LEFT JOIN h.fromStop fs
                LEFT JOIN h.toStop ts
                WHERE h.trip.id = :tripId
                AND h.seatNumber = :seatNumber
                AND h.status = 'HOLD'
                AND h.expiresAt > :now
                AND (fs IS NULL OR ts IS NULL
                     OR (fs.order < :toStopOrder AND ts.order > :fromStopOrder))
            """)
    List<SeatHold> findOverlappingActiveHolds(
            @Param("tripId") Long tripId,
            @Param("seatNumber") Integer seatNumber,
            @Param("fromStopOrder") Integer fromStopOrder,
            @Param("toStopOrder") Integer toStopOrder,
            @Param("now") LocalDateTime now);
}