package com.web.dto.trip;

import jakarta.validation.constraints.NotNull;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

// arrivalEta es opcional: si no se envía se calcula con la duración de la ruta
public record TripCreateRequest(
    @NotNull Long routeId,
    @NotNull Long busId,
    @NotNull LocalDate tripDate,
    @NotNull LocalDateTime departureTime,
    LocalDateTime arrivalEta
) implements Serializable {}
