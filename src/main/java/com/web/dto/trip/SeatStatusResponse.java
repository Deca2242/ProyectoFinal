package com.web.dto.trip;

import java.io.Serializable;

public record SeatStatusResponse(
        Integer seatNumber,
        Boolean available,
        String status
) implements Serializable {
}

