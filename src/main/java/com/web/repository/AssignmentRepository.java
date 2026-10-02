package com.web.repository;

import com.web.entity.Assignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AssignmentRepository extends JpaRepository<Assignment, Long> {

    // Buscar asignación por viaje
    Optional<Assignment> findByTripId(Long tripId);

    // Buscar asignaciones por conductor
    List<Assignment> findByDriverId(Long driverId);

    // Buscar asignaciones de un conductor para una fecha
    @Query("""
        SELECT a FROM Assignment a
        WHERE a.driver.id = :driverId
        AND a.trip.tripDate = :date
        ORDER BY a.trip.departureTime
    """)
    List<Assignment> findDriverAssignmentsForDate(
        @Param("driverId") Long driverId,
        @Param("date") LocalDate date
    );

    // Verificar si el conductor está disponible
    @Query("""
        SELECT CASE WHEN COUNT(a) = 0 THEN true ELSE false END
        FROM Assignment a
        WHERE a.driver.id = :driverId
        AND a.trip.tripDate = :date
        AND a.trip.status NOT IN ('ARRIVED', 'CANCELLED')
        AND (
            (a.trip.departureTime <= :departureTime AND a.trip.arrivalEta >= :departureTime)
            OR (a.trip.departureTime <= :arrivalEta AND a.trip.arrivalEta >= :arrivalEta)
            OR (a.trip.departureTime >= :departureTime AND a.trip.arrivalEta <= :arrivalEta)
        )
    """)
    boolean isDriverAvailable(
        @Param("driverId") Long driverId,
        @Param("date") LocalDate date,
        @Param("departureTime") java.time.LocalDateTime departureTime,
        @Param("arrivalEta") java.time.LocalDateTime arrivalEta
    );

    // Buscar asignaciones sin checklist completado
    @Query("""
        SELECT a FROM Assignment a
        WHERE a.trip.status = 'SCHEDULED'
        AND a.checklistOk = false
        AND a.trip.tripDate >= :fromDate
        ORDER BY a.trip.departureTime
    """)
    List<Assignment> findPendingChecklists(@Param("fromDate") LocalDate fromDate);

    // Buscar asignaciones por despachador
    @Query("""
        SELECT a FROM Assignment a
        WHERE a.dispatcher.id = :dispatcherId
        AND a.trip.tripDate >= :fromDate
        ORDER BY a.trip.departureTime
    """)
    List<Assignment> findByDispatcherId(
        @Param("dispatcherId") Long dispatcherId,
        @Param("fromDate") LocalDate fromDate
    );

    // Buscar asignaciones de un despachador para una fecha
    @Query("""
        SELECT a FROM Assignment a
        WHERE a.dispatcher.id = :dispatcherId
        AND a.trip.tripDate = :date
        ORDER BY a.trip.departureTime
    """)
    List<Assignment> findDispatcherAssignmentsForDate(
        @Param("dispatcherId") Long dispatcherId,
        @Param("date") LocalDate date
    );

    // Obtener asignación con detalles completos del viaje
    @Query("""
        SELECT a FROM Assignment a
        LEFT JOIN FETCH a.trip t
        LEFT JOIN FETCH t.bus
        LEFT JOIN FETCH t.route
        LEFT JOIN FETCH a.driver
        LEFT JOIN FETCH a.dispatcher
        WHERE a.id = :assignmentId
    """)
    Optional<Assignment> findByIdWithDetails(@Param("assignmentId") Long assignmentId);

    // Igual que isDriverAvailable pero sin contar la asignación del propio viaje (reprogramación)
    @Query("""
        SELECT CASE WHEN COUNT(a) = 0 THEN true ELSE false END
        FROM Assignment a
        WHERE a.driver.id = :driverId
        AND a.trip.id <> :tripId
        AND a.trip.status NOT IN ('ARRIVED', 'CANCELLED')
        AND a.trip.departureTime < :arrivalEta
        AND a.trip.arrivalEta > :departureTime
    """)
    boolean isDriverAvailableExcludingTrip(
        @Param("driverId") Long driverId,
        @Param("tripId") Long tripId,
        @Param("departureTime") java.time.LocalDateTime departureTime,
        @Param("arrivalEta") java.time.LocalDateTime arrivalEta);
}