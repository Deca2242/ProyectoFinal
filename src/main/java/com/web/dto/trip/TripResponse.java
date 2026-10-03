package com.web.dto.trip;

import com.web.entity.Trip;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record TripResponse(
    Long id,
    Long routeId,
    String routeName,  // Info simple
    String routeOrigin,
    String routeDestination,
    Long busId,
    String busPlate,  // Info simple
    Integer busCapacity,
    LocalDate tripDate,
    LocalDateTime departureTime,
    LocalDateTime arrivalEta,
    Trip.TripStatus status,
    Integer soldSeats,  // Para mostrar ocupación
    Double occupancyPercentage,
    Integer availableSeats,  // Capacidad + overbooking aprobado - vendidas
    String platform,
    LocalDateTime departedAt,  // Hora real de salida
    LocalDateTime arrivedAt    // Hora real de llegada
) implements Serializable {}
