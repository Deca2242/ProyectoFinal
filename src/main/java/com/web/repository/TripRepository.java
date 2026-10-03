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

    // Búsqueda pública de salidas: filtros opcionales por ruta y fecha (COALESCE en lugar de ":p IS NULL" porque
    // PostgreSQL no infiere el tipo de un parámetro de fecha nulo). Sin includeAll solo devuelve
    // las salidas reservables (SCHEDULED/BOARDING con salida futura)
    @Query("""
        SELECT t FROM Trip t
        JOIN FETCH t.route
        JOIN FETCH t.bus
        WHERE t.route.id = COALESCE(:routeId, t.route.id)
        AND t.tripDate = COALESCE(:date, t.tripDate)
        AND (:includeAll = true
             OR (t.status IN ('SCHEDULED', 'BOARDING') AND t.departureTime > :now))
        ORDER BY t.departureTime
    """)
    List<Trip> searchTrips(
        @Param("routeId") Long routeId,
        @Param("date") LocalDate date,
        @Param("includeAll") boolean includeAll,
        @Param("now") LocalDateTime now
    );

    // Viajes pendientes (SCHEDULED/BOARDING con salida futura) de una ruta: impiden desactivarla
    @Query("""
        SELECT COUNT(t) FROM Trip t
        WHERE t.route.id = :routeId
        AND t.status IN ('SCHEDULED', 'BOARDING')
        AND t.departureTime > :now
    """)
    long countPendingTripsByRoute(@Param("routeId") Long routeId, @Param("now") LocalDateTime now);

    // Viajes pendientes (SCHEDULED/BOARDING con salida futura) de un bus: impiden retirarlo o mandarlo a mantenimiento
    @Query("""
        SELECT CASE WHEN COUNT(t) > 0 THEN true ELSE false END FROM Trip t
        WHERE t.bus.id = :busId
        AND t.status IN ('SCHEDULED', 'BOARDING')
        AND t.departureTime > :now
    """)
    boolean existsPendingTripsByBus(@Param("busId") Long busId, @Param("now") LocalDateTime now);

    // El bus ya tiene otro viaje activo (no cancelado ni llegado) que se solapa con la franja [departure, arrival).
    // excludeTripId permite ignorar el propio viaje al reprogramarlo (null al crear)
    @Query("""
        SELECT CASE WHEN COUNT(t) > 0 THEN true ELSE false END FROM Trip t
        WHERE t.bus.id = :busId
        AND t.status NOT IN ('CANCELLED', 'ARRIVED')
        AND (:excludeTripId IS NULL OR t.id <> :excludeTripId)
        AND t.departureTime < :arrival
        AND t.arrivalEta > :departure
    """)
    boolean existsOverlappingTripForBus(
        @Param("busId") Long busId,
        @Param("departure") LocalDateTime departure,
        @Param("arrival") LocalDateTime arrival,
        @Param("excludeTripId") Long excludeTripId
    );

    // IDs de buses con algún viaje activo (no cancelado ni llegado) que se solapa con la franja (disponibilidad de flota)
    @Query("""
        SELECT DISTINCT t.bus.id FROM Trip t
        WHERE t.status NOT IN ('CANCELLED', 'ARRIVED')
        AND t.departureTime < :arrival
        AND t.arrivalEta > :departure
    """)
    List<Long> findBusIdsWithOverlappingTrips(
        @Param("departure") LocalDateTime departure,
        @Param("arrival") LocalDateTime arrival
    );

    // Silla vendida más alta del bus en viajes futuros no cancelados (null si no hay): límite para bajar la capacidad
    @Query("""
        SELECT MAX(tk.seatNumber) FROM Ticket tk
        JOIN tk.trip t
        WHERE t.bus.id = :busId
        AND tk.status = 'SOLD'
        AND t.status <> 'CANCELLED'
        AND t.departureTime > :now
    """)
    Integer findMaxSoldSeatNumberInFutureTrips(@Param("busId") Long busId, @Param("now") LocalDateTime now);

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

    // IDs de buses con un viaje activo (no cancelado ni llegado) en la fecha (disponibilidad de flota por día)
    @Query("""
                SELECT DISTINCT t.bus.id FROM Trip t
                WHERE t.tripDate = :date
                AND t.status NOT IN ('CANCELLED', 'ARRIVED')
            """)
    List<Long> findBusIdsWithTripsOnDate(@Param("date") LocalDate date);

    // Bloquea la fila del viaje (SELECT ... FOR UPDATE) hasta el fin de la transacción:
    // serializa ventas, holds y aprobaciones de overbooking del mismo viaje para evitar doble venta
    @Query(value = "SELECT id FROM trips WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<Long> lockById(@Param("id") Long id);

    // Viajes en curso que llegan dentro de la ventana y aún no tienen aviso de llegada próxima
    @Query("""
                SELECT t FROM Trip t
                WHERE t.status = 'DEPARTED'
                AND t.arrivalNotified = false
                AND t.arrivalEta BETWEEN :from AND :to
            """)
    List<Trip> findDepartedTripsArrivingBetween(
        @Param("from") LocalDateTime from,
        @Param("to") LocalDateTime to
    );
}