package com.web.dto.dispatch.OverbookingPolicy;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.io.Serializable;
import java.math.BigDecimal;

// Franja [startHour, endHour) de la hora de salida y % máximo de overbooking como fracción (0.05 = 5 %)
public record OverbookingPolicyCreateRequest(
    @NotNull @Min(0) @Max(23) Integer startHour,
    @NotNull @Min(1) @Max(24) Integer endHour,
    @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal maxPercentage
) implements Serializable {}
