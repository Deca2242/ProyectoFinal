package com.web.repository;

import com.web.entity.Trip;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TripRepository extends JpaRepository<Trip, Long> {

    // Buscar viajes disponibles por ruta y fecha
    List<Trip> findByRouteIdAndTripDate(Long routeId, LocalDate tripDate);

    // Buscar viajes por ruta, fecha y estado específico
    List<Trip> findByRouteIdAndTripDateAndStatus(Long routeId, LocalDate tripDate, Trip.TripStatus status);

    // Contar viajes futuros de una ruta
    long countByRouteIdAndTripDateGreaterThanEqual(Long routeId, LocalDate date);

    // Buscar viajes por estado
    List<Trip> findByStatus(Trip.TripStatus status);

    // Buscar viajes que salen pronto overbooking
    @Query("""
        SELECT t FROM Trip t
        WHERE t.status IN ('SCHEDULED', 'BOARDING')
        AND t.departureTime BETWEEN :now AND :maxTime
    """)
    List<Trip> findTripsDepartingSoon(
        @Param("now") LocalDateTime now,
        @Param("maxTime") LocalDateTime maxTime
    );

    // Obtener viaje con detalles completos (asignación, bus, ruta)
    @Query("""
        SELECT t FROM Trip t
        LEFT JOIN FETCH t.assignment a
        LEFT JOIN FETCH a.driver
        LEFT JOIN FETCH t.bus
        LEFT JOIN FETCH t.route
        WHERE t.id = :tripId
    """)
    Optional<Trip> findByIdWithDetails(@Param("tripId") Long tripId);

    // Calcular porcentaje de ocupación de un viaje
    @Query("""
        SELECT COALESCE(COUNT(DISTINCT t.seatNumber) * 100.0 / b.capacity, 0.0)
        FROM Trip tr
        JOIN tr.bus b
        LEFT JOIN Ticket t ON t.trip.id = tr.id AND t.status = 'SOLD'
        WHERE tr.id = :tripId
        GROUP BY b.capacity
    """)
    Double getOccupancyPercentage(@Param("tripId") Long tripId);

    // Obtener cantidad de asientos disponibles para un tramo específico
    @Query("""
        SELECT COALESCE(b.capacity - COUNT(DISTINCT t.seatNumber), b.capacity)
        FROM Bus b
        LEFT JOIN Trip tr ON b.id = tr.bus.id
        LEFT JOIN Ticket t ON tr.id = t.trip.id
            AND t.status = 'SOLD'
            AND t.fromStop.order < :toStopOrder
            AND t.toStop.order > :fromStopOrder
        WHERE tr.id = :tripId
        GROUP BY b.capacity
    """)
    Long getAvailableSeatsForSegment(
        @Param("tripId") Long tripId,
        @Param("fromStopOrder") Integer fromStopOrder,
        @Param("toStopOrder") Integer toStopOrder
    );

    // Buscar viajes sin asignación (para despachador)
    @Query("""
        SELECT t FROM Trip t
        WHERE t.status = 'SCHEDULED'
        AND t.assignment IS NULL
        AND t.tripDate >= :fromDate
        ORDER BY t.departureTime
    """)
    List<Trip> findUnassignedTrips(@Param("fromDate") LocalDate fromDate);

    // Buscar viajes por conductor
    @Query("""
        SELECT t FROM Trip t
        JOIN t.assignment a
        WHERE a.driver.id = :driverId
        AND t.tripDate >= :fromDate
        ORDER BY t.departureTime
    """)
    List<Trip> findTripsByDriver(
        @Param("driverId") Long driverId,
        @Param("fromDate") LocalDate fromDate
    );

    // Métricas: Buscar viajes por rango de fechas para analítica
    @Query("""
        SELECT t FROM Trip t
        WHERE t.tripDate BETWEEN :startDate AND :endDate
        ORDER BY t.departureTime
    """)
    List<Trip> findByDateRange(
        @Param("startDate") LocalDate startDate,
        @Param("endDate") LocalDate endDate
    );

    // Métricas: Calcular ocupación promedio
    @Query("""
        SELECT COALESCE(AVG(
            (SELECT COUNT(DISTINCT t.seatNumber) * 100.0 / b.capacity
             FROM Ticket t
             WHERE t.trip.id = tr.id AND t.status = 'SOLD')
        ), 0.0)
        FROM Trip tr
        JOIN tr.bus b
        WHERE tr.tripDate BETWEEN :startDate AND :endDate
        AND tr.status != 'CANCELLED'
    """)
    Double getAverageOccupancy(
        @Param("startDate") LocalDate startDate,
        @Param("endDate") LocalDate endDate
    );

    // IDs de buses que ya tienen un viaje no cancelado en la fecha (para disponibilidad de flota)
    @Query("""
                SELECT DISTINCT t.bus.id FROM Trip t
                WHERE t.tripDate = :date
                AND t.status <> 'CANCELLED'
            """)
    List<Long> findBusIdsWithTripsOnDate(@Param("date") LocalDate date);
}