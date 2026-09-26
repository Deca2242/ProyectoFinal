package com.web.dto.ticket.reservations;

import com.web.entity.SeatHold;

import java.io.Serializable;
import java.time.LocalDateTime;

public record SeatHoldResponse(
    Long id,
    Long tripId,
    Integer seatNumber,
    Long userId,
    LocalDateTime expiresAt,
    SeatHold.HoldStatus status,
    LocalDateTime createdAt,
    Long fromStopId,  // null si el hold cubre todo el viaje
    Long toStopId
) implements Serializable {}

