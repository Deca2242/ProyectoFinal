package com.web.dto.catalog.Bus;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Map;

// soatExpiresAt / technicalReviewExpiresAt son opcionales: vigencia del checklist que se valida al dar salida
public record BusCreateRequest(
    @NotBlank String plate,
    @NotNull @jakarta.validation.constraints.Positive Integer capacity,
    Map<String, Object> amenities,
    LocalDate soatExpiresAt,
    LocalDate technicalReviewExpiresAt
) implements Serializable {

    // Bus sin fechas de vigencia registradas
    public BusCreateRequest(String plate, Integer capacity, Map<String, Object> amenities) {
        this(plate, capacity, amenities, null, null);
    }
}
