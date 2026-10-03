package com.web.dto.catalog.FareRule;

import jakarta.validation.constraints.Positive;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Map;

// Actualización parcial: los campos nulos no se modifican
public record FareRuleUpdateRequest(
    Long fromStopId,
    Long toStopId,
    @Positive BigDecimal basePrice,
    Map<String, Integer> discounts,
    Boolean dynamicPricingEnabled
) implements Serializable {}
