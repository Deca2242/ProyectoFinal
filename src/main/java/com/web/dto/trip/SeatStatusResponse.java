package com.web.dto.trip;

import java.io.Serializable;

// Estado de una silla para un tramo: AVAILABLE, HELD (hold activo) u OCCUPIED (vendida).
// seatType sale de la entidad Seat del bus (STANDARD si la silla no está registrada, p. ej. overbooking)
public record SeatStatusResponse(
        Integer seatNumber,
        Boolean available,
        String status,
        String seatType
) implements Serializable {
}
