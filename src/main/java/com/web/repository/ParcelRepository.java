package com.web.repository;

import com.web.entity.Parcel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ParcelRepository extends JpaRepository<Parcel, Long> {

    // Buscar encomienda por código de rastreo
    Optional<Parcel> findByCode(String code);

    // Buscar encomiendas por viaje
    List<Parcel> findByTripId(Long tripId);

    // Buscar encomiendas por estado
    List<Parcel> findByStatus(Parcel.ParcelStatus status);

    // Buscar encomiendas por viaje y estado
    List<Parcel> findByTripIdAndStatus(Long tripId, Parcel.ParcelStatus status);

    // Buscar encomiendas por teléfono del remitente
    List<Parcel> findBySenderPhone(String senderPhone);

    // Buscar encomiendas por teléfono del destinatario
    List<Parcel> findByReceiverPhone(String receiverPhone);

    // Buscar encomiendas por rango de fechas
    @Query("""
        SELECT p FROM Parcel p
        WHERE p.trip.tripDate BETWEEN :startDate AND :endDate
    """)
    List<Parcel> findByDateRange(
        @Param("startDate") LocalDate startDate,
        @Param("endDate") LocalDate endDate
    );

    // Buscar encomiendas en tránsito de un viaje
    @Query("""
        SELECT p FROM Parcel p
        WHERE p.trip.id = :tripId
        AND p.status = 'IN_TRANSIT'
        ORDER BY p.toStop.order
    """)
    List<Parcel> findInTransitParcelsByTrip(@Param("tripId") Long tripId);

    // Buscar encomiendas para entregar en una parada
    @Query("""
        SELECT p FROM Parcel p
        WHERE p.trip.id = :tripId
        AND p.toStop.id = :stopId
        AND p.status = 'IN_TRANSIT'
    """)
    List<Parcel> findParcelsForDeliveryAtStop(
        @Param("tripId") Long tripId,
        @Param("stopId") Long stopId
    );

    // Validar OTP para entrega
    @Query("""
        SELECT p FROM Parcel p
        WHERE p.code = :code
        AND p.deliveryOtp = :otp
        AND p.status = 'IN_TRANSIT'
    """)
    Optional<Parcel> findByCodeAndOtp(
        @Param("code") String code,
        @Param("otp") String otp
    );

    // Métricas: Calcular ingresos por encomiendas
    @Query("""
        SELECT SUM(p.price)
        FROM Parcel p
        WHERE p.status IN ('DELIVERED', 'IN_TRANSIT')
        AND p.trip.tripDate BETWEEN :startDate AND :endDate
    """)
    java.math.BigDecimal calculateParcelRevenue(
        @Param("startDate") LocalDate startDate,
        @Param("endDate") LocalDate endDate
    );

    // Métricas: Contar encomiendas fallidas
    @Query("""
        SELECT COUNT(p)
        FROM Parcel p
        WHERE p.status = 'FAILED'
        AND p.trip.tripDate BETWEEN :startDate AND :endDate
    """)
    Long countFailedParcels(
        @Param("startDate") LocalDate startDate,
        @Param("endDate") LocalDate endDate
    );
}