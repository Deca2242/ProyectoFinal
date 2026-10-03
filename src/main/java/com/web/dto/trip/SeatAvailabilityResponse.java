package com.web.dto.trip;

import java.io.Serializable;
import java.util.List;

// Mapa de sillas de un viaje para el tramo fromStop → toStop
public record SeatAvailabilityResponse(
        Long tripId,
        Long fromStopId,
        Long toStopId,
        Integer totalSeats,      // Capacidad del bus + sillas de overbooking aprobadas
        Integer availableSeats,  // Sillas libres para el tramo (sin venta ni hold solapado)
        List<SeatStatusResponse> seats
) implements Serializable {
}
