package com.web.dto.catalog.Bus;

import com.web.entity.Bus;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Map;

public record BusResponse(
    Long id,
    String plate,
    Integer capacity,
    Map<String, Object> amenities,  // JSON
    Bus.BusStatus status,
    LocalDate soatExpiresAt,
    LocalDate technicalReviewExpiresAt
) implements Serializable {

    // Bus sin fechas de vigencia registradas
    public BusResponse(Long id, String plate, Integer capacity, Map<String, Object> amenities, Bus.BusStatus status) {
        this(id, plate, capacity, amenities, status, null, null);
    }
}
