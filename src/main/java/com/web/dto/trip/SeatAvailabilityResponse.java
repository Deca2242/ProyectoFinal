package com.web.dto.trip;

import java.io.Serializable;
import java.util.List;

public record SeatAvailabilityResponse(
        Long tripId,
        Long fromStopId,
        Long toStopId,
        List<SeatStatusResponse> seats,
        Integer totalSeats,
        Integer availableSeats
) implements Serializable {
}

