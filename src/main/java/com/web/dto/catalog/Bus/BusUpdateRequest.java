package com.web.dto.catalog.Bus;

import com.web.entity.Bus;

import java.io.Serializable;
import java.util.Map;

public record BusUpdateRequest(
    @jakarta.validation.constraints.Positive Integer capacity,
    Map<String, Object> amenities,
    Bus.BusStatus status
) implements Serializable {}

