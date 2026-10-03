package com.web.dto.catalog.Route;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.io.Serializable;
import java.math.BigDecimal;

// Actualización parcial: los campos nulos no se modifican (el código de la ruta no se cambia)
public record RouteUpdateRequest(
    @Pattern(regexp = ".*\\S.*", message = "no puede estar vacío") String name,
    @Pattern(regexp = ".*\\S.*", message = "no puede estar vacío") String origin,
    @Pattern(regexp = ".*\\S.*", message = "no puede estar vacío") String destination,
    @Positive BigDecimal distanceKm,
    @Positive Integer durationMin,
    Boolean isActive
) implements Serializable {}
