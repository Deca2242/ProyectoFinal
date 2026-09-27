package com.web.repository;

import com.web.entity.Ticket;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TicketRepository extends JpaRepository<Ticket, Long> {

    // Buscar tickets por viaje y asiento
    List<Ticket> findByTripIdAndSeatNumber(Long tripId, Integer seatNumber);

    // Buscar todos los tickets de un viaje
    List<Ticket> findByTripId(Long tripId);

    // Buscar tickets por pasajero
    List<Ticket> findByPassengerId(Long passengerId);

    // Buscar ticket por código QR para validación
    Optional<Ticket> findByQrCode(String qrCode);

    // Buscar tickets conflictivos para validación de tramos - CASO DE USO 1
    @Query("""
                SELECT t FROM Ticket t
                WHERE t.trip.id = :tripId
                AND t.seatNumber = :seatNumber
                AND t.status = 'SOLD'
                AND t.fromStop.order < :toStopOrder
                AND t.toStop.order > :fromStopOrder
            """)
    List<Ticket> findConflictingTicketsForSegment(
            @Param("tripId") Long tripId,
            @Param("seatNumber") Integer seatNumber,
            @Param("fromStopOrder") Integer fromStopOrder,
            @Param("toStopOrder") Integer toStopOrder);

    // Verificar si un asiento está disponible para TODO el viaje (para SeatHold)
    @Query("""
                SELECT CASE WHEN COUNT(t) = 0 THEN true ELSE false END
                FROM Ticket t
                WHERE t.trip.id = :tripId
                AND t.seatNumber = :seatNumber
                AND t.status = 'SOLD'
            """)
    Boolean isSeatAvailableForFullTrip(
            @Param("tripId") Long tripId,
            @Param("seatNumber") Integer seatNumber);

    // Verificar si un asiento está disponible para un tramo específico - CASO DE
    // USO 1
    @Query("""
                SELECT CASE WHEN COUNT(t) = 0 THEN true ELSE false END
                FROM Ticket t
                WHERE t.trip.id = :tripId
                AND t.seatNumber = :seatNumber
                AND t.status = 'SOLD'
                AND t.fromStop.order < :toStopOrder
                AND t.toStop.order > :fromStopOrder
            """)
    boolean isSeatAvailableForSegment(
            @Param("tripId") Long tripId,
            @Param("seatNumber") Integer seatNumber,
            @Param("fromStopOrder") Integer fromStopOrder,
            @Param("toStopOrder") Integer toStopOrder);

    // Obtener cantidad de asientos vendidos para un viaje
    @Query("""
                SELECT COUNT(DISTINCT t.seatNumber)
                FROM Ticket t
                WHERE t.trip.id = :tripId
                AND t.status = 'SOLD'
            """)
    Long countSoldSeats(@Param("tripId") Long tripId);

    // Obtener tickets de un viaje en una parada específica (para abordaje)
    @Query("""
                SELECT t FROM Ticket t
                WHERE t.trip.id = :tripId
                AND t.status = 'SOLD'
                AND t.fromStop.order <= :stopOrder
                AND t.toStop.order > :stopOrder
                ORDER BY t.seatNumber
            """)
    List<Ticket> findTicketsAtStop(
            @Param("tripId") Long tripId,
            @Param("stopOrder") Integer stopOrder);

    // Pasajeros que van a bordo en algún punto del tramo indicado (lista de pasajeros):
    // incluye a quienes suben antes o bajan después, no solo a quienes hacen exactamente ese tramo
    @Query("""
                SELECT t FROM Ticket t
                WHERE t.trip.id = :tripId
                AND t.status = 'SOLD'
                AND t.fromStop.order < :toStopOrder
                AND t.toStop.order > :fromStopOrder
                ORDER BY t.seatNumber
            """)
    List<Ticket> findTicketsBySegment(
            @Param("tripId") Long tripId,
            @Param("fromStopOrder") Integer fromStopOrder,
            @Param("toStopOrder") Integer toStopOrder);

    // Buscar tickets elegibles para cancelación (para política de reembolso)
    @Query("""
                SELECT t FROM Ticket t
                WHERE t.id = :ticketId
                AND t.status = 'SOLD'
                AND t.trip.departureTime > :now
            """)
    Optional<Ticket> findCancellableTicket(
            @Param("ticketId") Long ticketId,
            @Param("now") LocalDateTime now);

    // Buscar tickets CASH en rango de tiempo (para cierre de caja)
    @Query("""
                SELECT t FROM Ticket t
                WHERE t.passenger.id = :userId
                AND t.paymentMethod = 'CASH'
                AND t.status = 'SOLD'
                AND t.trip.departureTime BETWEEN :startTime AND :endTime
                ORDER BY t.trip.departureTime
            """)
    List<Ticket> findCashTicketsInTimeRange(
            @Param("userId") Long userId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    // Tickets vendidos en efectivo dentro de un rango de fecha de compra (para cierre de caja)
    @Query("""
                SELECT t FROM Ticket t
                WHERE t.paymentMethod = 'CASH'
                AND t.status = 'SOLD'
                AND t.purchasedAt >= :start
                AND t.purchasedAt < :end
                ORDER BY t.purchasedAt
            """)
    List<Ticket> findSoldCashTicketsPurchasedBetween(
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);

    // Métricas: Calcular ingresos por rango de fechas
    @Query("""
                SELECT SUM(t.price)
                FROM Ticket t
                WHERE t.status = 'SOLD'
                AND t.trip.tripDate BETWEEN :startDate AND :endDate
            """)
    BigDecimal calculateRevenue(
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    // Métricas: Calcular ingresos por método de pago
    @Query("""
                SELECT t.paymentMethod, SUM(t.price)
                FROM Ticket t
                WHERE t.status = 'SOLD'
                AND t.trip.tripDate BETWEEN :startDate AND :endDate
                GROUP BY t.paymentMethod
            """)
    List<Object[]> calculateRevenueByPaymentMethod(
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    // Métricas: Contar tickets vendidos en rango de fechas
    @Query("""
                SELECT COUNT(t)
                FROM Ticket t
                WHERE t.status = 'SOLD'
                AND t.trip.tripDate BETWEEN :startDate AND :endDate
            """)
    Long countSoldTicketsInRange(
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    // Métricas: Calcular ingresos por viaje
    @Query("""
                SELECT SUM(t.price)
                FROM Ticket t
                WHERE t.trip.id = :tripId
                AND t.status = 'SOLD'
            """)
    BigDecimal calculateRevenueByTrip(@Param("tripId") Long tripId);

    // Métricas: Contar cancelaciones
    @Query("""
                SELECT COUNT(t)
                FROM Ticket t
                WHERE t.status = 'CANCELLED'
                AND t.trip.tripDate BETWEEN :startDate AND :endDate
            """)
    Long countCancellationsInRange(
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);


    // Métricas: Contar NO_SHOW en rango de fechas
    @Query("""
                SELECT COUNT(t)
                FROM Ticket t
                WHERE t.status = 'NO_SHOW'
                AND t.trip.tripDate BETWEEN :startDate AND :endDate
            """)
    Long countNoShowsInRange(
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    // Buscar tickets elegibles para marcar como NO_SHOW
    @Query("""
                SELECT t FROM Ticket t
                JOIN t.trip tr
                WHERE t.trip.id = :tripId
                AND t.status = 'SOLD'
                AND tr.departureTime <= :cutoffTime
                AND t.fromStop.order = (
                    SELECT MIN(s.order) FROM Stop s WHERE s.route.id = tr.route.id
                )
            """)
    List<Ticket> findNoShowEligibleTickets(
            @Param("tripId") Long tripId,
            @Param("cutoffTime") LocalDateTime cutoffTime);

    // Tickets sin abordar que suben en la primera parada de viajes en abordaje que salen en la ventana (now, cutoff].
    // Si el abordaje no se abrió (viaje retrasado o sin despachar) no se marca a nadie; al dar salida se marcan los restantes
    // Los pasajeros de paradas intermedias no se marcan porque no hay hora estimada por parada
    @Query("""
                SELECT t FROM Ticket t
                JOIN t.trip tr
                WHERE t.status = 'SOLD'
                AND t.boardedAt IS NULL
                AND tr.status = 'BOARDING'
                AND tr.departureTime > :now
                AND tr.departureTime <= :cutoffTime
                AND t.fromStop.order = (
                    SELECT MIN(s.order) FROM Stop s WHERE s.route.id = tr.route.id
                )
            """)
    List<Ticket> findUnboardedTicketsDepartingBetween(
            @Param("now") LocalDateTime now,
            @Param("cutoffTime") LocalDateTime cutoffTime);

    // Tickets vendidos de un viaje (para cancelarlos junto con el viaje)
    List<Ticket> findByTripIdAndStatus(Long tripId, Ticket.TicketStatus status);

    // Verificar si existe un ticket vendido para un viaje y asiento específico
    boolean existsByTripIdAndSeatNumberAndStatus(Long tripId, Integer seatNumber, Ticket.TicketStatus status);

    // Sillas distintas vendidas que se solapan con el tramo (ocupación real del tramo)
    @Query("""
                SELECT COUNT(DISTINCT t.seatNumber)
                FROM Ticket t
                WHERE t.trip.id = :tripId
                AND t.status = 'SOLD'
                AND t.fromStop.order < :toStopOrder
                AND t.toStop.order > :fromStopOrder
            """)
    Long countSoldSeatsForSegment(
            @Param("tripId") Long tripId,
            @Param("fromStopOrder") Integer fromStopOrder,
            @Param("toStopOrder") Integer toStopOrder);

    // Tickets vendidos sin abordar que suben en la primera parada (no-show al dar salida)
    @Query("""
                SELECT t FROM Ticket t
                JOIN t.trip tr
                WHERE tr.id = :tripId
                AND t.status = 'SOLD'
                AND t.boardedAt IS NULL
                AND t.fromStop.order = (
                    SELECT MIN(s.order) FROM Stop s WHERE s.route.id = tr.route.id
                )
            """)
    List<Ticket> findUnboardedOriginTickets(@Param("tripId") Long tripId);

    // Tickets en efectivo comprados en el rango, en cualquier estado: el dinero se recibió al vender
    @Query("""
                SELECT t FROM Ticket t
                WHERE t.paymentMethod = 'CASH'
                AND t.purchasedAt >= :start
                AND t.purchasedAt < :end
                ORDER BY t.purchasedAt
            """)
    List<Ticket> findCashTicketsPurchasedBetween(
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);

    // Tickets en efectivo cancelados en el rango (su reembolso sale de la caja ese día)
    @Query("""
                SELECT t FROM Ticket t
                WHERE t.paymentMethod = 'CASH'
                AND t.status = 'CANCELLED'
                AND t.cancelledAt >= :start
                AND t.cancelledAt < :end
            """)
    List<Ticket> findCashTicketsCancelledBetween(
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);

    // Métricas: tickets de viajes en el rango de fechas (ventas por canal, no-show, fees)
    @Query("""
                SELECT t FROM Ticket t
                WHERE t.trip.tripDate BETWEEN :startDate AND :endDate
            """)
    List<Ticket> findByTripDateBetween(
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);
}