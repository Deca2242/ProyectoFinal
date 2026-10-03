package com.web.dto.trip;

import java.io.Serializable;

// Ocupación de un tramo consecutivo Stop[i] → Stop[i+1] de la ruta del viaje (monitoreo del despachador)
public record SegmentOccupancyResponse(
        Long fromStopId,
        String fromStopName,
        Long toStopId,
        String toStopName,
        Integer soldSeats,
        Integer capacity,
        Double occupancyPercentage
) implements Serializable {
}
