package com.web.dto.catalog.Seat;

import com.web.entity.Seat;

import java.io.Serializable;

public record SeatResponse(
    Long id,
    Long busId,
    Integer seatNumber,
    Seat.SeatType seatType
) implements Serializable {}
