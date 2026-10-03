package com.web.dto.catalog.Seat;

import com.web.entity.Seat;
import jakarta.validation.constraints.NotNull;

import java.io.Serializable;

// Cambio del tipo de una silla (p. ej. marcarla PREFERENTIAL para accesibilidad)
public record SeatUpdateRequest(
    @NotNull Seat.SeatType seatType
) implements Serializable {}
