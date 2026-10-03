package com.web.dto.ticket.reservations;

import jakarta.validation.constraints.NotNull;

import java.io.Serializable;

public record SeatHoldRequest(
    @NotNull(message = "userId es obligatorio")
    Long userId,
    
    @NotNull(message = "fromStopId es obligatorio")
    Long fromStopId,
    
    @NotNull(message = "toStopId es obligatorio")
    Long toStopId
) implements Serializable {}

