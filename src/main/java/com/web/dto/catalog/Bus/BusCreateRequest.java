package com.web.dto.catalog.Bus;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.io.Serializable;
import java.util.Map;

public record BusCreateRequest(
    @NotBlank String plate,
    @NotNull @jakarta.validation.constraints.Positive Integer capacity,
    Map<String, Object> amenities
) implements Serializable {}

