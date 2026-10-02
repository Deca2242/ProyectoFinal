package com.web.dto.catalog.FareRule;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Map;

// discounts: porcentaje (0-100) por tipo de pasajero, solo STUDENT, SENIOR y CHILD
public record FareRuleCreateRequest(
    @NotNull Long fromStopId,
    @NotNull Long toStopId,
    @NotNull @Positive BigDecimal basePrice,
    Map<String, Integer> discounts,
    Boolean dynamicPricingEnabled
) implements Serializable {}
