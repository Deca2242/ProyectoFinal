package com.web.dto.catalog.Bus;

import com.web.entity.Bus;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Map;

public record BusUpdateRequest(
    @jakarta.validation.constraints.Positive Integer capacity,
    Map<String, Object> amenities,
    Bus.BusStatus status,
    LocalDate soatExpiresAt,
    LocalDate technicalReviewExpiresAt
) implements Serializable {

    // Actualización sin tocar las fechas de vigencia del checklist
    public BusUpdateRequest(Integer capacity, Map<String, Object> amenities, Bus.BusStatus status) {
        this(capacity, amenities, status, null, null);
    }
}
