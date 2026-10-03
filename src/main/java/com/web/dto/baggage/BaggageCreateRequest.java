package com.web.dto.baggage;

import jakarta.validation.constraints.NotNull;

import java.io.Serializable;
import java.math.BigDecimal;

public record BaggageCreateRequest(
    @NotNull @jakarta.validation.constraints.Positive @jakarta.validation.constraints.DecimalMax("999.99") BigDecimal weightKg,
    BigDecimal excessFee  // Calculado por servicio
) implements Serializable {}

